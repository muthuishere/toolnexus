"""OAuth 2.1 client for MCP servers (SPEC.md §2A.1, §2A.1a).

401/403 + ``WWW-Authenticate`` → Protected Resource Metadata (RFC 9728) → AS metadata (RFC 8414 /
OIDC) → client identity (pre-registered ``clientId``/``clientIds`` or a Client ID Metadata Document;
**never** Dynamic Client Registration) → authorization code + PKCE S256 + ``resource`` (RFC 8707) →
``state`` / ``iss`` (RFC 9207) check → token → retry with ``Authorization: Bearer``.

Two modes: ``auto`` opens a browser (``open_url``) and receives the callback on a loopback listener;
``pause`` never blocks — the flow surfaces as a §10 ``authorization`` :class:`~toolnexus.types.Request`
(:class:`AuthPending`) and is finished with :meth:`OAuth.complete`.

Tokens, codes, verifiers and secrets are never logged and never appear in error text.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import inspect
import json
import queue
import secrets
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Optional, Protocol
from urllib.parse import parse_qs, quote, urlencode, urlsplit

from ..types import Request
from .http import HttpClient

PRM_PATH = "/.well-known/oauth-protected-resource"
PAUSE_TTL = 600.0  # seconds a paused authorization stays resumable
AUTO_TIMEOUT = 300.0  # seconds auto mode waits for the browser callback
MAX_AUTH_RETRIES = 3


@dataclass
class Token:
    """An OAuth token for one MCP server (resource), bound to the issuer that minted it.

    ``repr``/``str`` never show a token value."""

    access_token: str
    refresh_token: str = ""
    expires_at: float = 0.0  # epoch seconds; 0 = no expiry
    scopes: list[str] = field(default_factory=list)
    issuer: str = ""
    client_id: str = ""
    resource: str = ""

    def __repr__(self) -> str:
        return f"Token(issuer={self.issuer!r}, client_id={self.client_id!r}, scopes={self.scopes!r}, <redacted>)"

    __str__ = __repr__

    def expired(self) -> bool:
        return bool(self.expires_at) and time.time() > self.expires_at - 10


class TokenStore(Protocol):
    """Persists tokens per resource (the MCP server URL). Implementations keep them confidential."""

    def load(self, resource: str) -> Optional[Token]: ...
    def save(self, resource: str, token: Token) -> None: ...
    def delete(self, resource: str) -> None: ...


class MemoryTokenStore:
    """The default token store: in memory, per load."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._m: dict[str, Token] = {}

    def load(self, resource: str) -> Optional[Token]:
        with self._lock:
            return self._m.get(resource)

    def save(self, resource: str, token: Token) -> None:
        with self._lock:
            self._m[resource] = token

    def delete(self, resource: str) -> None:
        with self._lock:
            self._m.pop(resource, None)


class AuthPending(Exception):
    """A ``pause``-mode authorization: answer :attr:`request` and call :meth:`OAuth.complete`."""

    def __init__(self, request: Request):
        super().__init__("authorization pending")
        self.request = request


OpenUrl = Callable[[str], Any]


def open_browser(url: str) -> None:
    """The default ``open_url``: the OS opener."""
    if sys.platform == "darwin":
        subprocess.Popen(["open", url])
    elif sys.platform == "win32":
        subprocess.Popen(["rundll32", "url.dll,FileProtocolHandler", url])
    else:
        subprocess.Popen(["xdg-open", url])


def _run_opener(opener: OpenUrl, url: str) -> None:
    r = opener(url)
    if inspect.isawaitable(r):
        async def _await() -> Any:
            return await r
        asyncio.run(_await())


class _Loopback:
    """Receives the AS redirect on ``http://127.0.0.1:<port>/callback`` (RFC 8252 §7.3)."""

    def __init__(self, host: str = "127.0.0.1", port: int = 0, path: str = "/callback"):
        q: queue.Queue[tuple[str, Any]] = queue.Queue()
        self._q = q
        self._path = path

        class H(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802
                if urlsplit(self.path).path != path:
                    self.send_error(404)
                    return
                q.put(("cb", f"http://{self.headers.get('Host')}{self.path}"))
                b = b"Authorization received. You can close this window."
                self.send_response(200)
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def log_message(self, *a: Any) -> None:  # never log the callback (it carries the code)
                pass

        self._srv = ThreadingHTTPServer((host, port), H)
        self._srv.daemon_threads = True
        self._host = host
        threading.Thread(target=self._srv.serve_forever, daemon=True).start()

    def redirect_uri(self) -> str:
        return f"http://{self._host}:{self._srv.server_address[1]}{self._path}"

    def wait(self, opener: OpenUrl, url: str, timeout: float) -> str:
        def run() -> None:
            try:
                _run_opener(opener, url)
            except Exception as e:  # noqa: BLE001 - a failed opener must not leave us waiting
                self._q.put(("err", e))

        threading.Thread(target=run, daemon=True).start()
        try:
            kind, v = self._q.get(timeout=timeout)
        except queue.Empty:
            raise TimeoutError("authorization timed out") from None
        if kind == "err":
            raise OSError(f"open authorization URL: {type(v).__name__}")
        return str(v)

    def close(self) -> None:
        self._srv.shutdown()
        self._srv.server_close()


@dataclass
class _Flow:
    resource: str
    issuer: str
    client_id: str
    redirect: str
    verifier: str
    state: str
    token_endpoint: str
    iss_supported: bool
    scopes: list[str]
    expires: float


class OAuth:
    """Per-server OAuth engine. ``config`` is the server's ``auth`` object (camelCase keys, §2A.1)."""

    def __init__(
        self,
        config: Optional[dict[str, Any]] = None,
        *,
        store: Optional[TokenStore] = None,
        open_url: Optional[OpenUrl] = None,
        http: Optional[HttpClient] = None,
    ) -> None:
        c = dict(config or {})
        self.client_id = str(c.get("clientId") or "")
        self.client_secret = str(c.get("clientSecret") or "")  # never logged
        self.token_auth_method = str(c.get("tokenAuthMethod") or "")
        ids = c.get("clientIds")
        self.client_ids: dict[str, str] = dict(ids) if isinstance(ids, dict) else {}
        self.client_metadata_url = str(c.get("clientMetadataUrl") or "")
        self.redirect_uri = str(c.get("redirectUri") or "")
        self.mode = "pause" if c.get("mode") == "pause" else "auto"
        self.omit_refresh_resource = c.get("omitRefreshResource") is True
        self.allow_http = c.get("allowHttp") is True
        self.store: TokenStore = store if store is not None else MemoryTokenStore()
        self.open_url: OpenUrl = open_url or open_browser
        self.http = http or HttpClient()
        self._lock = threading.Lock()
        self._auth_lock = threading.RLock()  # one login / one refresh at a time (single-flight)
        self._issuer: dict[str, str] = {}  # resource -> confirmed issuer (only then is a token sent)
        self._flows: dict[str, _Flow] = {}

    # ---- used by the connection ----

    def bearer(self, resource: str) -> str:
        """The access token to send for ``resource`` ("" = none), refreshing it when expired."""
        with self._lock:
            iss = self._issuer.get(resource, "")
        t = self.store.load(resource)
        if t is None or not iss or t.issuer != iss:
            return ""
        if t.expired():
            with self._auth_lock:  # N callers -> one refresh (refresh tokens rotate)
                t = self.store.load(resource)
                if t is None:
                    return ""
                if t.expired():
                    try:
                        self._refresh(resource, t)
                    except Exception:  # noqa: BLE001 - fall through to a fresh authorization
                        return ""
                t = self.store.load(resource)
        return t.access_token if t else ""

    def challenge(self, resource: str, status: int, www: str, sent: str) -> None:
        """Handles a 401/403. Returning means: re-send the request now. Raises :class:`AuthPending`
        in pause mode, or an error when the challenge cannot be satisfied."""
        params = parse_challenge(www)
        with self._auth_lock:
            cur = self.store.load(resource)
            with self._lock:
                known = self._issuer.get(resource, "")
            if cur and known and cur.issuer == known and cur.access_token != sent and not cur.expired():
                return  # another caller refreshed/authorized while we waited
            if status == 401 and cur and cur.refresh_token and params.get("error") == "invalid_token" and known:
                try:
                    self._refresh(resource, cur)
                    return
                except Exception:  # noqa: BLE001 - re-authorize below
                    cur = None
            if status == 403 and params.get("error") != "insufficient_scope":
                raise PermissionError(f"forbidden: {params.get('error', '')}".rstrip())
            prm = self._fetch_prm(resource, params.get("resource_metadata", ""))
            servers = prm.get("authorization_servers") or []
            if not isinstance(servers, list) or not servers:
                raise ValueError("PRM lists no authorization_servers")
            issuer = str(servers[0])
            if not self.allow_http and not issuer.startswith("https://"):
                raise ValueError("authorization server must use https")
            md = discover_as(self.http, issuer)
            if "S256" not in (md.get("code_challenge_methods_supported") or []):
                raise ValueError("authorization server does not advertise PKCE S256; refusing")
            with self._lock:
                self._issuer[resource] = issuer
            if status == 401 and cur and cur.issuer == issuer and not params.get("error") and not sent:
                return  # first request after a restart: the issuer is confirmed, send the stored token
            if status == 401 and cur and cur.refresh_token and params.get("error") == "invalid_token":
                try:
                    self._refresh(resource, cur)
                    return
                except Exception:  # noqa: BLE001
                    pass
            scopes = params.get("scope", "").split() or [
                s for s in (prm.get("scopes_supported") or []) if isinstance(s, str)
            ]
            if cur and status == 403:  # step-up: union with what was granted before
                scopes += [s for s in cur.scopes if s not in scopes]
            client_id = self.client_ids.get(issuer) or self.client_id
            cimd = md.get("client_id_metadata_document_supported") is True
            if not client_id and self.client_metadata_url and cimd:
                client_id = self.client_metadata_url
            if not client_id:
                why = (
                    "auth.clientMetadataUrl is not set"
                    if cimd
                    else "it does not support Client ID Metadata Documents"
                )
                raise ValueError(
                    f"no client identity for {issuer}: {why} and no client id is configured — register an app "
                    f"with {issuer} and set auth.clientId (dynamic client registration is not supported)"
                )
            if self.mode == "pause":
                if not self.redirect_uri:
                    raise ValueError("auth.redirectUri is required for pause mode")
                f = self._new_flow(resource, issuer, client_id, self.redirect_uri, md, scopes)
                req = self._pending_request(f, md)
                with self._lock:
                    now = time.time()
                    for k in [k for k, old in self._flows.items() if now > old.expires]:
                        del self._flows[k]
                    self._flows[req.id] = f
                raise AuthPending(req)
            lb = self._loopback()
            try:
                f = self._new_flow(resource, issuer, client_id, lb.redirect_uri(), md, scopes)
                req = self._pending_request(f, md)
                cb = lb.wait(self.open_url, req.url or "", AUTO_TIMEOUT)
            finally:
                lb.close()
            self._complete(f, cb)

    def complete(self, pending_id: str, callback_url: str) -> None:
        """Finishes a paused authorization with the URL the AS redirected to."""
        with self._lock:
            f = self._flows.pop(pending_id, None)
        if f is None:
            raise KeyError(f"unknown pending authorization {pending_id!r}")
        if time.time() > f.expires:
            raise TimeoutError("pending authorization expired")
        self._complete(f, callback_url)

    def has_pending(self, pending_id: str) -> bool:
        with self._lock:
            return pending_id in self._flows

    # ---- internals ----

    def _loopback(self) -> _Loopback:
        if self.redirect_uri:
            u = urlsplit(self.redirect_uri)
            if u.scheme == "http" and u.hostname in ("127.0.0.1", "localhost", "::1"):
                return _Loopback(u.hostname, u.port or 0, u.path or "/callback")
        return _Loopback()

    def _new_flow(
        self, resource: str, issuer: str, client_id: str, redirect: str, md: dict[str, Any], scopes: list[str]
    ) -> _Flow:
        return _Flow(
            resource, issuer, client_id, redirect, _rand(32), _rand(16), str(md.get("token_endpoint", "")),
            md.get("authorization_response_iss_parameter_supported") is True, scopes, time.time() + PAUSE_TTL,
        )

    def _pending_request(self, f: _Flow, md: dict[str, Any]) -> Request:
        q = {
            "response_type": "code", "client_id": f.client_id, "redirect_uri": f.redirect, "state": f.state,
            "code_challenge": _b64url(hashlib.sha256(f.verifier.encode()).digest()),
            "code_challenge_method": "S256", "resource": f.resource,
        }
        if f.scopes:
            q["scope"] = " ".join(f.scopes)
        return Request(
            id="auth-" + _rand(12),
            kind="authorization",
            prompt="Authorize access to " + f.resource,
            url=str(md.get("authorization_endpoint", "")) + "?" + urlencode(sorted(q.items())),
            expiresAt=datetime.fromtimestamp(f.expires, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        )

    def _refresh(self, resource: str, t: Token) -> None:
        if not t.refresh_token:
            raise ValueError("no refresh token")
        md = discover_as(self.http, t.issuer)
        form = {"grant_type": "refresh_token", "refresh_token": t.refresh_token, "client_id": t.client_id}
        if not self.omit_refresh_resource:
            form["resource"] = resource
        try:
            nt = self._token_request(str(md.get("token_endpoint", "")), form, t.issuer, resource, t.client_id, t.scopes)
        except Exception:
            self.store.delete(resource)
            raise
        nt.refresh_token = nt.refresh_token or t.refresh_token
        self.store.save(resource, nt)

    def _complete(self, f: _Flow, callback_url: str) -> None:
        q = parse_qs(urlsplit(callback_url).query, keep_blank_values=True)
        if "iss" in q:  # RFC 9207: before anything else, error responses included
            if q["iss"][0] != f.issuer:
                raise ValueError("authorization response iss mismatch (possible mix-up attack)")
        elif f.iss_supported:
            raise ValueError("authorization response missing iss")
        if (q.get("state") or [""])[0] != f.state:
            raise ValueError("authorization response state mismatch")
        if q.get("error"):
            raise PermissionError(f"authorization denied: {q['error'][0]}")
        t = self._token_request(f.token_endpoint, {
            "grant_type": "authorization_code", "code": (q.get("code") or [""])[0], "redirect_uri": f.redirect,
            "client_id": f.client_id, "code_verifier": f.verifier, "resource": f.resource,
        }, f.issuer, f.resource, f.client_id, f.scopes)
        self.store.save(f.resource, t)

    def _token_request(
        self, ep: str, form: dict[str, str], issuer: str, resource: str, client_id: str, scopes: list[str]
    ) -> Token:
        method = self.token_auth_method
        if client_id == self.client_metadata_url or not self.client_secret:
            method = "none"  # CIMD clients and secretless pre-registered clients are public
        elif not method:
            method = "client_secret_basic"
        form = dict(form)
        headers = {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"}
        if method == "client_secret_post":
            form["client_secret"] = self.client_secret
        if method == "client_secret_basic":
            form.pop("client_id", None)  # RFC 6749 §2.3.1: one authentication method only
            cred = f"{quote(client_id, safe='')}:{quote(self.client_secret, safe='')}"
            headers["Authorization"] = "Basic " + base64.b64encode(cred.encode()).decode()
        resp = self.http.request("POST", ep, urlencode(form), headers)
        tr: Any = {}
        try:
            tr = json.loads(resp.body)
        except ValueError:
            if resp.header("Content-Type").startswith("application/x-www-form-urlencoded"):
                tr = {k: v[0] for k, v in parse_qs(resp.body.decode()).items()}  # GitHub
        if not isinstance(tr, dict) or resp.status != 200 or not tr.get("access_token"):
            err = tr.get("error", "") if isinstance(tr, dict) else ""
            raise PermissionError(f"token endpoint: HTTP {resp.status} {err}")  # never the body
        t = Token(str(tr["access_token"]), str(tr.get("refresh_token") or ""), 0.0, list(scopes), issuer,
                  client_id, resource)
        if tr.get("scope"):
            t.scopes = str(tr["scope"]).split()
        try:
            exp = int(tr.get("expires_in") or 0)
        except (TypeError, ValueError):
            exp = 0
        if exp > 0:
            t.expires_at = time.time() + exp
        return t

    def _fetch_prm(self, resource: str, hinted: str) -> dict[str, Any]:
        cands = [hinted]
        if not hinted:
            u = urlsplit(resource)
            root = f"{u.scheme}://{u.netloc}{PRM_PATH}"
            p = u.path.rstrip("/")
            cands = ([root + p] if p else []) + [root]
        last = ""
        for c in cands:
            try:
                m = get_json(self.http, c)
            except Exception as e:  # noqa: BLE001
                last = str(e)
                continue
            if m.get("resource") != resource:  # RFC 9728 §3.3
                raise ValueError(f"PRM resource {m.get('resource')} does not match {resource}")
            return m
        raise ValueError(f"protected resource metadata: {last}")


def discover_as(hc: HttpClient, issuer: str) -> dict[str, Any]:
    """RFC 8414 then OIDC metadata endpoints in the spec's order; validates ``issuer`` equality."""
    u = urlsplit(issuer)
    base, p = f"{u.scheme}://{u.netloc}", u.path.rstrip("/")
    if p:
        cands = [
            base + "/.well-known/oauth-authorization-server" + p,
            base + "/.well-known/openid-configuration" + p,
            base + p + "/.well-known/openid-configuration",
        ]
    else:
        cands = [base + "/.well-known/oauth-authorization-server", base + "/.well-known/openid-configuration"]
    for c in cands:
        try:
            m = get_json(hc, c)
        except Exception:  # noqa: BLE001
            continue
        if m.get("issuer") != issuer:
            raise ValueError(f"AS metadata issuer {m.get('issuer')} != {issuer}")
        return m
    raise ValueError(f"no authorization server metadata for {issuer}")


def get_json(hc: HttpClient, url: str) -> dict[str, Any]:
    r = hc.get(url)
    if r.status != 200:
        raise OSError(f"GET {url}: HTTP {r.status}")
    v = r.json()
    if not isinstance(v, dict):
        raise ValueError(f"GET {url}: not a JSON object")
    return v


def parse_challenge(h: str) -> dict[str, str]:
    """Parses ``Bearer k="v", k2=v2`` (RFC 6750 §3)."""
    out: dict[str, str] = {}
    h = h.strip()
    if h[:6].lower() == "bearer":
        h = h[6:]
    h = h.strip()
    while h:
        eq = h.find("=")
        if eq < 0:
            break
        k, h = h[:eq].strip().lower(), h[eq + 1:].strip()
        if h.startswith('"'):
            end = 1
            while end < len(h) and h[end] != '"':
                end += 2 if h[end] == "\\" else 1
            v = h[1:min(end, len(h))].replace('\\"', '"')
            h = h[min(end + 1, len(h)):]
        else:
            end = h.find(",")
            end = len(h) if end < 0 else end
            v, h = h[:end].strip(), h[end:]
        out[k] = v
        h = h.strip().removeprefix(",").strip()
    return out


def _rand(n: int) -> str:
    return _b64url(secrets.token_bytes(n))


def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
