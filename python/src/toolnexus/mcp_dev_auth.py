"""McpDevAuthServer — DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION.

An in-process OAuth 2.1 authorization server that auto-approves every request as
``dev-user`` (no login, no consent): RFC 8414 metadata, JWKS, authorization code + PKCE
S256, resource indicators (RFC 8707), ``iss`` in the response (RFC 9207), refresh-token
rotation, pre-registered clients and Client ID Metadata Documents. No dynamic client
registration. Plain HTTP on 127.0.0.1, so a toolnexus client needs ``allow_http`` against it
(SPEC §7C.2)::

    as_ = start_mcp_dev_auth_server(clients={"my-app": ["http://127.0.0.1/callback"]})
    auth = McpServerAuth(as_.issuer, scopes={"*": ["mcp"]})
    ...
    as_.close()
"""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import secrets
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Optional

from ._rsa import RsaKey, b64url


def _rand(n: int) -> str:
    return secrets.token_hex(n)


def _basic_auth(h: str) -> Optional[tuple[str, str]]:
    """``Authorization: Basic`` credentials (form-urlencoded per RFC 6749 §2.3.1), or None."""
    if not h[:6].lower() == "basic ":
        return None
    try:
        cid, sep, sec = base64.b64decode(h[6:], validate=True).decode().partition(":")
    except ValueError:
        return None
    if not sep:
        return None
    return urllib.parse.unquote_plus(cid), urllib.parse.unquote_plus(sec)


def _dumps(v: Any) -> bytes:
    return (json.dumps(v, separators=(",", ":")) + "\n").encode()


class McpDevAuthServer:
    """The running dev authorization server (DEV ONLY). ``issuer`` is
    ``http://127.0.0.1:<port>``. Use :func:`start_mcp_dev_auth_server`."""

    def __init__(
        self,
        *,
        clients: Optional[dict[str, list[str]]] = None,
        secrets: Optional[dict[str, str]] = None,  # noqa: A002 — mirrors the SPEC option name
        access_ttl: float = 3600,
        no_cimd: bool = False,
        reject_refresh_resource: bool = False,
    ) -> None:
        self.clients = dict(clients or {})
        self.secrets = dict(secrets or {})
        self.access_ttl = access_ttl if access_ttl > 0 else 3600
        self.no_cimd = no_cimd
        self.reject_refresh_resource = reject_refresh_resource
        self._key = RsaKey(2048)
        self._lock = threading.Lock()
        self._codes: dict[str, tuple[str, str, str, str, str]] = {}
        self._rts: dict[str, tuple[str, str, str, str, str]] = {}
        self._auths = 0
        dev = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a: Any) -> None:  # quiet
                pass

            def do_GET(self) -> None:  # noqa: N802
                dev._serve(self)

            def do_POST(self) -> None:  # noqa: N802
                dev._serve(self)

        self._srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._srv.daemon_threads = True
        self.issuer = f"http://127.0.0.1:{self._srv.server_address[1]}"
        self._thread = threading.Thread(target=self._srv.serve_forever, daemon=True)
        self._thread.start()

    def close(self) -> None:
        """Stop the server."""
        self._srv.shutdown()
        self._srv.server_close()

    def authorizations(self) -> int:
        """How many ``/authorize`` requests were served."""
        with self._lock:
            return self._auths

    def mint(self, claims: dict[str, Any]) -> str:
        """Sign an access token with the server's key (tests: forge expired or
        wrong-audience tokens)."""
        h = b64url(json.dumps({"alg": "RS256", "kid": "dev", "typ": "JWT"}, separators=(",", ":")).encode())
        c = b64url(json.dumps(claims, separators=(",", ":"), sort_keys=True).encode())
        return f"{h}.{c}.{b64url(self._key.sign_rs256(f'{h}.{c}'.encode()))}"

    # ── HTTP ──

    def _serve(self, rq: BaseHTTPRequestHandler) -> None:
        u = urllib.parse.urlsplit(rq.path)

        def js(status: int, v: Any) -> None:
            body = _dumps(v)
            rq.send_response(status)
            rq.send_header("Content-Type", "application/json")
            rq.send_header("Content-Length", str(len(body)))
            rq.end_headers()
            rq.wfile.write(body)

        if u.path == "/.well-known/oauth-authorization-server":
            i = self.issuer
            js(
                200,
                {
                    "issuer": i,
                    "authorization_endpoint": i + "/authorize",
                    "token_endpoint": i + "/token",
                    "jwks_uri": i + "/jwks",
                    "response_types_supported": ["code"],
                    "grant_types_supported": ["authorization_code", "refresh_token"],
                    "code_challenge_methods_supported": ["S256"],
                    "token_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
                    "authorization_response_iss_parameter_supported": True,
                    "client_id_metadata_document_supported": not self.no_cimd,
                },
            )
        elif u.path == "/jwks":
            js(200, {"keys": [self._key.jwk("dev")]})
        elif u.path == "/authorize":
            self._authorize(rq, u.query)
        elif u.path == "/token":
            self._token(rq, js)
        else:
            self._plain(rq, 404, "404 page not found")

    @staticmethod
    def _plain(rq: BaseHTTPRequestHandler, status: int, text: str) -> None:
        body = (text + "\n").encode()
        rq.send_response(status)
        rq.send_header("Content-Type", "text/plain; charset=utf-8")
        rq.send_header("Content-Length", str(len(body)))
        rq.end_headers()
        rq.wfile.write(body)

    def _authorize(self, rq: BaseHTTPRequestHandler, query: str) -> None:
        q = {k: v[0] for k, v in urllib.parse.parse_qs(query, keep_blank_values=True).items()}
        with self._lock:
            self._auths += 1
        cid, redirect = q.get("client_id", ""), q.get("redirect_uri", "")
        if not self._redirect_allowed(cid, redirect):
            # never redirect to an unverified URI
            self._plain(rq, 400, "invalid_client or redirect_uri")
            return

        def back(v: dict[str, str]) -> None:
            v = {**v, "state": q.get("state", ""), "iss": self.issuer}
            sep = "&" if "?" in redirect else "?"
            rq.send_response(302)
            rq.send_header("Location", redirect + sep + urllib.parse.urlencode(sorted(v.items())))
            rq.send_header("Content-Length", "0")
            rq.end_headers()

        if q.get("response_type") != "code" or q.get("code_challenge_method") != "S256" or not q.get("code_challenge"):
            back({"error": "invalid_request"})
            return
        if not q.get("resource"):
            back({"error": "invalid_target"})
            return
        code = _rand(16)
        with self._lock:
            self._codes[code] = (cid, redirect, q["code_challenge"], q["resource"], q.get("scope", ""))
        back({"code": code})

    def _redirect_allowed(self, cid: str, redirect: str) -> bool:
        """Exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3)."""
        allowed = self.clients.get(cid, [])
        if cid.startswith("https://") or cid.startswith("http://"):  # Client ID Metadata Document
            if self.no_cimd:
                return False
            try:
                r = urllib.request.Request(cid, headers={"Accept": "application/json"})
                with urllib.request.urlopen(r, timeout=10) as resp:  # noqa: S310 — dev only
                    if resp.status != 200:
                        return False
                    doc = json.loads(resp.read())
            except Exception:  # noqa: BLE001
                return False
            if not isinstance(doc, dict) or doc.get("client_id") != cid or not doc.get("client_name"):
                return False
            allowed = [a for a in doc.get("redirect_uris") or [] if isinstance(a, str)]
        try:
            ru = urllib.parse.urlsplit(redirect)
        except ValueError:
            return False
        for a in allowed:
            if a == redirect:
                return True
            try:
                au = urllib.parse.urlsplit(a)
                loop = ipaddress.ip_address(ru.hostname or "").is_loopback
            except ValueError:
                continue
            if loop and au.hostname == ru.hostname and au.scheme == ru.scheme and au.path == ru.path:
                return True
        return False

    def _token(self, rq: BaseHTTPRequestHandler, js: Any) -> None:
        n = int(rq.headers.get("Content-Length") or 0)
        raw = rq.rfile.read(n).decode("utf-8", errors="replace") if n else ""
        f = {k: v[0] for k, v in urllib.parse.parse_qs(raw, keep_blank_values=True).items()}

        def bad(e: str, status: int = 400) -> None:
            js(status, {"error": e})

        with self._lock:
            basic = _basic_auth(rq.headers.get("Authorization") or "")
            if basic is not None:
                cid, sec = basic
                if (f.get("client_id") and f["client_id"] != cid) or not self.secrets.get(cid) or self.secrets[cid] != sec:
                    bad("invalid_client", 401)
                    return
                f["client_id"] = cid
            else:
                want = self.secrets.get(f.get("client_id", ""))
                if want and f.get("client_secret") != want:
                    bad("invalid_client", 401)
                    return
            gt = f.get("grant_type")
            if gt == "authorization_code":
                g = self._codes.pop(f.get("code", ""), None)  # single use
                if g is None:
                    bad("invalid_grant")
                    return
                challenge = b64url(hashlib.sha256(f.get("code_verifier", "").encode()).digest())
                if challenge != g[2] or f.get("client_id") != g[0] or f.get("redirect_uri") != g[1] or f.get("resource", "") != g[3]:
                    bad("invalid_grant")
                    return
            elif gt == "refresh_token":
                g = self._rts.get(f.get("refresh_token", ""))
                if g is None or f.get("client_id") != g[0]:
                    bad("invalid_grant")
                    return
                del self._rts[f["refresh_token"]]  # rotation
                res = f.get("resource", "")
                if res and (res != g[3] or self.reject_refresh_resource):
                    bad("invalid_target")
                    return
            else:
                bad("unsupported_grant_type")
                return
            rt = _rand(24)
            self._rts[rt] = g
        now = int(time.time())
        ttl = int(self.access_ttl)
        at = self.mint(
            {
                "iss": self.issuer,
                "sub": "dev-user",
                "aud": g[3],
                "scope": g[4],
                "client_id": g[0],
                "jti": _rand(8),
                "exp": now + ttl,
                "iat": now,
            }
        )
        js(200, {"access_token": at, "token_type": "Bearer", "expires_in": ttl, "refresh_token": rt, "scope": g[4]})


def start_mcp_dev_auth_server(
    *,
    clients: Optional[dict[str, list[str]]] = None,
    secrets: Optional[dict[str, str]] = None,  # noqa: A002
    access_ttl: float = 3600,
    no_cimd: bool = False,
    reject_refresh_resource: bool = False,
) -> McpDevAuthServer:
    """Start the DEV-ONLY authorization server on ``127.0.0.1:<random>`` (SPEC §7C.2).

    - ``clients``: pre-registered ``client_id`` → allowed redirect URIs (a loopback
      redirect matches on any port).
    - ``secrets``: confidential clients, ``client_id`` → secret (basic or post).
    - ``access_ttl``: access-token lifetime in seconds (default 3600).
    - ``no_cimd``: do not accept / advertise Client ID Metadata Documents.
    - ``reject_refresh_resource``: answer ``invalid_target`` when a refresh carries
      ``resource`` (an Entra-like provider, for client tests).

    Never use it in production: it approves every request without a login.
    """
    return McpDevAuthServer(
        clients=clients,
        secrets=secrets,
        access_ttl=access_ttl,
        no_cimd=no_cimd,
        reject_refresh_resource=reject_refresh_resource,
    )
