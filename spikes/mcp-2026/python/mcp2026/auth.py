"""Client-side OAuth for MCP 2026-07-28 (basic/authorization).

Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
-> client id (pre-registered | CIMD; never DCR) -> code + PKCE S256 + resource (RFC 8707)
-> iss (RFC 9207) + state check -> token -> retry with Bearer.
"""
from __future__ import annotations

import base64
import hashlib
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
from typing import Callable, Protocol
from urllib.parse import parse_qs, quote, urlencode, urlsplit

from .http import HttpClient, Request, default_http, respond
from .protocol import text

PRM_PATH = "/.well-known/oauth-protected-resource"


@dataclass
class Token:
    """Bound to the issuer that minted it and the resource it is for. Never log it (repr is redacted)."""

    access_token: str
    refresh_token: str = ""
    expiry: float = 0.0  # epoch seconds; 0 = no expiry
    scopes: list[str] = field(default_factory=list)
    issuer: str = ""
    resource: str = ""
    client_id: str = ""

    def __repr__(self) -> str:
        return f"Token{{issuer={self.issuer}, redacted}}"

    __str__ = __repr__

    def expired(self) -> bool:
        return bool(self.expiry) and time.time() > self.expiry - 10


class TokenStore(Protocol):
    """Persists tokens per resource. Implementations must keep them confidential."""

    def load(self, resource: str) -> Token | None: ...
    def save(self, resource: str, token: Token) -> None: ...
    def delete(self, resource: str) -> None: ...


class MemoryTokenStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._m: dict[str, Token] = {}

    def load(self, resource: str) -> Token | None:
        with self._lock:
            return self._m.get(resource)

    def save(self, resource: str, token: Token) -> None:
        with self._lock:
            self._m[resource] = token

    def delete(self, resource: str) -> None:
        with self._lock:
            self._m.pop(resource, None)


@dataclass
class Pending:
    """Mirrors toolnexus SPEC §10 Request (kind "authorization")."""

    id: str
    kind: str
    prompt: str
    url: str
    expires_at: str


class PendingError(Exception):
    """Raised instead of blocking when the Authorizer pauses. Finish with OAuth.resume(id, callback_url)."""

    def __init__(self, pending: Pending):
        super().__init__("authorization pending: " + pending.prompt)
        self.pending = pending


def pending_of(err: BaseException | None) -> Pending | None:
    """The Pending inside err, or None."""
    return err.pending if isinstance(err, PendingError) else None


class AuthPaused(Exception):
    """What a pausing AuthSession.wait raises."""


class AuthSession(Protocol):
    def redirect_uri(self) -> str: ...
    def wait(self, pending: Pending) -> str: ...  # the full callback URL, or raise AuthPaused
    def close(self) -> None: ...


class Authorizer(Protocol):
    """Decides how the user reaches the authorization URL and how the callback comes back."""

    def begin(self) -> AuthSession: ...


@dataclass
class PauseAuthorizer:
    """Never blocks: every authorization becomes a Pending."""

    redirect: str

    def begin(self) -> "PauseAuthorizer":
        return self

    def redirect_uri(self) -> str:
        return self.redirect

    def wait(self, pending: Pending) -> str:
        raise AuthPaused()

    def close(self) -> None:
        pass


@dataclass
class LoopbackAuthorizer:
    """Opens a browser and listens on 127.0.0.1:<random>/callback (RFC 8252 §7.3)."""

    open: Callable[[str], None] | None = None  # None = system browser
    timeout: float = 300.0

    def begin(self) -> "_LoopbackSession":
        return _LoopbackSession(self.open or open_browser, self.timeout)


class _LoopbackSession:
    def __init__(self, opener, timeout):
        self._q: queue.Queue = queue.Queue()
        self._open, self._timeout = opener, timeout
        q = self._q

        class H(BaseHTTPRequestHandler):
            def do_GET(self):
                if urlsplit(self.path).path != "/callback":
                    self.send_error(404)
                    return
                q.put(("cb", f"http://{self.headers.get('Host')}{self.path}"))
                b = b"Authorization received. You can close this window."
                self.send_response(200)
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def log_message(self, *a):
                pass

        self._srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self._srv.daemon_threads = True
        threading.Thread(target=self._srv.serve_forever, daemon=True).start()

    def redirect_uri(self) -> str:
        return f"http://127.0.0.1:{self._srv.server_address[1]}/callback"

    def wait(self, pending: Pending) -> str:
        def run():  # the opener may block (tests follow redirects synchronously)
            try:
                self._open(pending.url)
            except Exception as e:  # a failed opener must not leave us waiting for a callback that cannot come
                self._q.put(("err", e))

        threading.Thread(target=run, daemon=True).start()
        try:
            kind, v = self._q.get(timeout=self._timeout)
        except queue.Empty:
            raise TimeoutError("authorization timed out") from None
        if kind == "err":
            raise OSError(f"open authorization URL: {v}")
        return v

    def close(self) -> None:
        self._srv.shutdown()
        self._srv.server_close()


def open_browser(url: str) -> None:
    if sys.platform == "darwin":
        subprocess.Popen(["open", url])
    elif sys.platform == "win32":
        subprocess.Popen(["rundll32", "url.dll,FileProtocolHandler", url])
    else:
        subprocess.Popen(["xdg-open", url])


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
    retry: Callable[[], dict]


class OAuth:
    """The client-side auth engine; attach it as Client.auth."""

    def __init__(self, *, authorizer: Authorizer | None = None, client_id: str = "", client_secret: str = "",
                 token_auth_method: str = "", omit_refresh_resource: bool = False, store: TokenStore | None = None,
                 client_ids: dict[str, str] | None = None, client_metadata_url: str = "",
                 http: HttpClient | None = None, allow_http: bool = False) -> None:
        self.authorizer = authorizer  # None = LoopbackAuthorizer() (system browser + loopback callback)
        self.client_id = client_id  # pre-registered client_id for any issuer not in client_ids
        self.client_secret = client_secret  # optional: makes the pre-registered client confidential. Never logged.
        self.token_auth_method = token_auth_method  # none | client_secret_basic | client_secret_post
        # Drop the RFC 8707 `resource` from refresh_token requests (Entra rejects it, python-sdk #2578).
        self.omit_refresh_resource = omit_refresh_resource
        self.store: TokenStore = store or MemoryTokenStore()
        self.client_ids = client_ids or {}  # issuer -> pre-registered client_id (bound to that issuer)
        self.client_metadata_url = client_metadata_url  # CIMD client_id, used when the AS advertises support
        self.http = http or default_http()
        self.allow_http = allow_http  # allow non-HTTPS AS endpoints (dev only; spec requires HTTPS)
        self._lock = threading.Lock()
        self._auth_lock = threading.RLock()  # one login / one refresh at a time
        self._issuer: dict[str, str] = {}  # resource -> discovered issuer (only then do we send a stored token)
        self._flows: dict[str, _Flow] = {}

    # ---- used by Client ----

    def _bearer(self, resource: str) -> str:
        """The token to send for resource, refreshing if expired. "" = send none."""
        with self._lock:
            iss = self._issuer.get(resource, "")
        t = self.store.load(resource)
        if t is None or not iss or t.issuer != iss:  # only tokens from this server's AS (spec: Token Handling)
            return ""
        if t.expired():
            with self._auth_lock:  # one refresh for N callers (refresh tokens rotate: a 2nd would fail)
                t = self.store.load(resource)
                if t is None:
                    return ""
                if t.expired():
                    try:
                        self._refresh(t)
                    except Exception:
                        return ""
                t = self.store.load(resource)
        return t.access_token if t else ""

    def _refresh(self, t: Token) -> None:
        if not t.refresh_token:
            raise ValueError("no refresh token")
        md = discover_as(self.http, t.issuer)
        form = {"grant_type": "refresh_token", "refresh_token": t.refresh_token, "client_id": t.client_id}
        if not self.omit_refresh_resource:
            form["resource"] = t.resource
        try:
            nt = self._token_request(md.get("token_endpoint", ""), form, t.issuer, t.resource, t.client_id, t.scopes)
        except Exception:
            self.store.delete(t.resource)
            raise
        nt.refresh_token = nt.refresh_token or t.refresh_token
        self.store.save(t.resource, nt)

    def _challenge(self, resource: str, status: int, www: str, sent: str, retry: Callable[[], dict]) -> None:
        """Handles a 401/403. Returns = retry the request now; PendingError = paused."""
        params = parse_challenge(www)
        with self._auth_lock:  # N concurrent 401s -> one authorization, the rest retry with its token
            cur = self.store.load(resource)
            with self._lock:
                known = self._issuer.get(resource, "")
            if cur and known and cur.issuer == known and cur.access_token != sent and not cur.expired():
                return  # someone else refreshed/authorized while we waited
            if status == 401 and cur and cur.refresh_token and params.get("error") == "invalid_token":
                try:
                    self._refresh(cur)
                    return
                except Exception:
                    cur = None
            if status == 403 and params.get("error") != "insufficient_scope":
                raise PermissionError(f"forbidden: {params.get('error', '')}")
            prm = self._fetch_prm(resource, params.get("resource_metadata", ""))
            servers = prm.get("authorization_servers") or []
            if not servers:
                raise ValueError("PRM lists no authorization_servers")
            issuer = servers[0]  # RFC 9728 §7.6: client's choice; we take the first
            if not self.allow_http and not str(issuer).startswith("https://"):
                raise ValueError("authorization server must use https")
            md = discover_as(self.http, issuer)
            if "S256" not in (md.get("code_challenge_methods_supported") or []):
                raise ValueError("authorization server does not advertise PKCE S256; refusing")
            with self._lock:
                self._issuer[resource] = issuer
            if status == 401 and cur and cur.issuer == issuer and not params.get("error"):
                return  # first request after restart: we now know the issuer, so the stored token may be sent
            # Scope selection: challenge scope, else scopes_supported; on step-up union with previous.
            scopes = params.get("scope", "").split() or [s for s in (prm.get("scopes_supported") or []) if isinstance(s, str)]
            if cur and status == 403:
                scopes += [s for s in cur.scopes if s not in scopes]
            client_id = self.client_ids.get(issuer) or self.client_id
            cimd = md.get("client_id_metadata_document_supported") is True
            if not client_id and self.client_metadata_url and cimd:
                client_id = self.client_metadata_url
            if not client_id:  # no DCR, ever: the user registers an app once and configures its id
                why = "OAuth.client_metadata_url is not set" if cimd else "it does not support Client ID Metadata Documents"
                raise ValueError(f"no client identity for {issuer}: {why} and no client id is configured — register an app "
                                 f"with {issuer} and set OAuth.client_id (dynamic client registration is not supported)")
            sess = (self.authorizer or LoopbackAuthorizer()).begin()
            f = _Flow(resource, issuer, client_id, sess.redirect_uri(), _rand(32), _rand(16), md.get("token_endpoint", ""),
                      md.get("authorization_response_iss_parameter_supported") is True, scopes, time.time() + 600, retry)
            q = {"response_type": "code", "client_id": client_id, "redirect_uri": f.redirect, "state": f.state,
                 "code_challenge": _b64url(hashlib.sha256(f.verifier.encode()).digest()),
                 "code_challenge_method": "S256", "resource": resource}
            if scopes:
                q["scope"] = " ".join(scopes)
            p = Pending("auth-" + _rand(12), "authorization", "Authorize access to " + resource,
                        md.get("authorization_endpoint", "") + "?" + urlencode(sorted(q.items())),
                        datetime.fromtimestamp(f.expires, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
            try:
                cb = sess.wait(p)
            except AuthPaused:
                with self._lock:
                    now = time.time()
                    for k in [k for k, old in self._flows.items() if now > old.expires]:  # drop abandoned pendings
                        del self._flows[k]
                    self._flows[p.id] = f
                raise PendingError(p) from None
            finally:
                sess.close()
            self._complete(f, cb)

    def _set_retry(self, pid: str, fn: Callable[[], dict]) -> None:
        with self._lock:
            if pid in self._flows:
                self._flows[pid].retry = fn

    # ---- pause / resume ----

    def resume(self, pending_id: str, callback_url: str) -> dict:
        """Finishes a paused authorization and returns the ORIGINAL operation's result."""
        with self._lock:
            f = self._flows.pop(pending_id, None)
        if f is None:
            raise KeyError(f"unknown pending authorization {pending_id!r}")
        if time.time() > f.expires:
            raise TimeoutError("pending authorization expired")
        self._complete(f, callback_url)
        return f.retry()

    def resume_callback(self, callback_url: str) -> dict:
        """resume for web apps: the pending is found by the callback's state."""
        st = (parse_qs(urlsplit(callback_url).query).get("state") or [""])[0]
        with self._lock:
            pid = next((k for k, f in self._flows.items() if st and f.state == st), "")
        return self.resume(pid, callback_url)

    def web_handler(self, call: Callable[[Request], dict]):
        """A WSGI app serving one tool call to a browser user, pausing for authorization when needed: call runs;
        if it pauses, the browser is redirected to the AS; when the AS redirects back to this same app (use its
        URL as PauseAuthorizer.redirect) the original call is resumed and its text written. Errors become 502."""
        def app(environ, start_response):
            req = Request(environ)
            q = parse_qs(req.query, keep_blank_values=True)
            try:
                if q.get("state") and ("code" in q or "error" in q):
                    res = self.resume_callback(req.url)
                else:
                    res = call(req)
            except PendingError as pe:
                return respond(start_response, 302, b"", [("Location", pe.pending.url)])
            except Exception as e:
                return respond(start_response, 502, str(e) + "\n", [("Content-Type", "text/plain; charset=utf-8")])
            return respond(start_response, 200, text(res), [("Content-Type", "text/plain; charset=utf-8")])
        return app

    # ---- internals ----

    def _complete(self, f: _Flow, callback_url: str) -> None:
        q = parse_qs(urlsplit(callback_url).query, keep_blank_values=True)
        # RFC 9207 §2.4 (spec: Authorization Response Validation) — before anything else, error responses included.
        if "iss" in q:
            if q["iss"][0] != f.issuer:  # simple string comparison, no normalization
                raise ValueError("authorization response iss mismatch (possible mix-up attack)")
        elif f.iss_supported:
            raise ValueError("authorization response missing iss")
        if (q.get("state") or [""])[0] != f.state:
            raise ValueError("authorization response state mismatch")
        if q.get("error"):
            raise PermissionError(f"authorization denied: {q['error'][0]}")
        t = self._token_request(f.token_endpoint, {
            "grant_type": "authorization_code", "code": (q.get("code") or [""])[0], "redirect_uri": f.redirect,
            "client_id": f.client_id, "code_verifier": f.verifier, "resource": f.resource},
            f.issuer, f.resource, f.client_id, f.scopes)
        self.store.save(f.resource, t)

    def _token_request(self, ep: str, form: dict, issuer: str, resource: str, client_id: str,
                       scopes: list[str]) -> Token:
        method = self.token_auth_method
        if client_id == self.client_metadata_url or not self.client_secret:
            method = "none"  # CIMD clients and secretless pre-registered clients are public
        elif not method:
            method = "client_secret_basic"
        form = dict(form)
        headers = {"Content-Type": "application/x-www-form-urlencoded",
                   "Accept": "application/json"}  # GitHub answers form-encoded without it
        if method == "client_secret_post":
            form["client_secret"] = self.client_secret
        if method == "client_secret_basic":
            form.pop("client_id", None)  # RFC 6749 §2.3.1: authenticate with one method only
            cred = f"{quote(client_id, safe='')}:{quote(self.client_secret, safe='')}"
            headers["Authorization"] = "Basic " + base64.b64encode(cred.encode()).decode()
        resp = self.http.request("POST", ep, urlencode(form), headers)
        tr: dict = {}
        try:
            tr = json.loads(resp.body)
        except ValueError:
            if resp.header("Content-Type").startswith("application/x-www-form-urlencoded"):
                # RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding.
                tr = {k: v[0] for k, v in parse_qs(resp.body.decode()).items()}
        if not isinstance(tr, dict) or resp.status != 200 or not tr.get("access_token"):
            err = tr.get("error", "") if isinstance(tr, dict) else ""
            raise PermissionError(f"token endpoint: HTTP {resp.status} {err}")  # never include the body
        t = Token(tr["access_token"], tr.get("refresh_token") or "", 0.0, list(scopes), issuer, resource, client_id)
        if tr.get("scope"):
            t.scopes = str(tr["scope"]).split()
        try:
            exp = int(tr.get("expires_in") or 0)
        except (TypeError, ValueError):
            exp = 0
        if exp > 0:
            t.expiry = time.time() + exp
        return t

    def _fetch_prm(self, resource: str, hinted: str) -> dict:
        """Header URL if present, else path-inserted well-known, else root (spec: PRM Discovery Requirements)."""
        cands = [hinted]
        if not hinted:
            u = urlsplit(resource)
            root = f"{u.scheme}://{u.netloc}{PRM_PATH}"
            p = u.path.rstrip("/")
            cands = ([root + p] if p else []) + [root]
        last: Exception | None = None
        for c in cands:
            try:
                m = get_json(self.http, c)
            except Exception as e:
                last = e
                continue
            if m.get("resource") != resource:  # RFC 9728 §3.3
                raise ValueError(f"PRM resource {m.get('resource')} does not match {resource}")
            return m
        raise ValueError(f"protected resource metadata: {last}")


def discover_as(hc: HttpClient, issuer: str) -> dict:
    """RFC 8414 then OIDC endpoints in the spec's order; validates issuer equality."""
    u = urlsplit(issuer)
    base, p = f"{u.scheme}://{u.netloc}", u.path.rstrip("/")
    if p:
        cands = [base + "/.well-known/oauth-authorization-server" + p, base + "/.well-known/openid-configuration" + p,
                 base + p + "/.well-known/openid-configuration"]
    else:
        cands = [base + "/.well-known/oauth-authorization-server", base + "/.well-known/openid-configuration"]
    for c in cands:
        try:
            m = get_json(hc, c)
        except Exception:
            continue
        if m.get("issuer") != issuer:
            raise ValueError(f"AS metadata issuer {m.get('issuer')} != {issuer}")
        return m
    raise ValueError(f"no authorization server metadata for {issuer}")


def get_json(hc: HttpClient, url: str) -> dict:
    r = hc.get(url)
    if r.status != 200:
        raise OSError(f"GET {url}: HTTP {r.status}")
    v = r.json()
    if not isinstance(v, dict):
        raise ValueError(f"GET {url}: not a JSON object")
    return v


def parse_challenge(h: str) -> dict[str, str]:
    """Parses `Bearer k="v", k2=v2` (RFC 6750 §3 / RFC 9110 auth-param)."""
    out: dict[str, str] = {}
    h = h.strip()
    if h.startswith("Bearer"):
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
