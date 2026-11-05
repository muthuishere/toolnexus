"""DevAuthServer — DEV/TEST ONLY. NEVER USE IN PRODUCTION.

A one-line OAuth 2.1 authorization server that auto-approves every request as "dev-user" (no login, no
consent screen), so local dev and tests exercise the real MCP auth flow: RFC 8414 metadata, JWKS, auth code
+ PKCE S256, resource (RFC 8707), iss in the response (RFC 9207), refresh-token rotation, pre-registered and
CIMD clients.

    as_ = DevAuthServer.start()             # https://127.0.0.1:<port>/dev
    server.auth = ServerAuth(as_.issuer, scopes=...)
"""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import secrets
import threading
import time
from urllib.parse import parse_qs, unquote, urlencode, urlsplit

from .auth import get_json
from .http import HttpClient, Request, b64url, respond, respond_json, serve


def _rand(n: int) -> str:
    return b64url(secrets.token_bytes(n))


class DevAuthServer:
    def __init__(self) -> None:
        from cryptography.hazmat.primitives.asymmetric import rsa

        self._key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.clients: dict[str, list[str]] = {}  # pre-registered client_id -> redirect URIs
        self.secrets: dict[str, str] = {}  # confidential clients: client_id -> secret (basic or post)
        self.access_ttl = 3600  # seconds
        # Test knobs: misbehave on purpose.
        self.iss_override = ""  # non-empty: send this iss instead of the real one
        self.no_cimd = False  # don't advertise client_id_metadata_document_supported
        self.reject_refresh_resource = False  # Entra-like: invalid_target if a refresh carries resource
        # Recorded for tests.
        self.last_token_form: dict[str, str] = {}
        self.last_authorize_query: dict[str, str] = {}
        self.authorizations = 0
        self.refreshes = 0
        self._lock = threading.Lock()
        self._codes: dict[str, tuple] = {}
        self._rts: dict[str, tuple] = {}
        self._run = serve(self, tls=True)
        self.issuer = self._run.url + "/dev"
        self.fetch_http = self.client()  # fetches CIMD documents

    @classmethod
    def start(cls) -> "DevAuthServer":
        """Starts a TLS dev AS on 127.0.0.1 with issuer https://127.0.0.1:<port>/dev (trusted in-process)."""
        return cls()

    def client(self) -> HttpClient:
        """An HTTP client that trusts the dev TLS cert and follows redirects (a stand-in browser)."""
        return HttpClient(follow_redirects=True)

    def approve(self, url: str) -> None:
        """A headless "browser": visits an authorization URL and follows the redirect back to the callback
        (use as LoopbackAuthorizer(open=as_.approve))."""
        self.client().get(url)

    def stats(self) -> tuple[int, int]:
        with self._lock:
            return self.authorizations, self.refreshes

    def close(self) -> None:
        self._run.close()

    def mint(self, claims: dict) -> str:
        """Signs an access token directly (tests: forge wrong audience / expired tokens)."""
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding

        h = b64url(json.dumps({"alg": "RS256", "typ": "JWT", "kid": "dev"}).encode())
        c = b64url(json.dumps(claims).encode())
        sig = self._key.sign(f"{h}.{c}".encode(), padding.PKCS1v15(), hashes.SHA256())
        return f"{h}.{c}.{b64url(sig)}"

    # ---- WSGI ----

    def __call__(self, environ, start_response):
        req = Request(environ)
        if req.path == "/.well-known/oauth-authorization-server/dev":
            i = self.issuer
            return respond_json(start_response, 200, {
                "issuer": i, "authorization_endpoint": i + "/authorize", "token_endpoint": i + "/token",
                "jwks_uri": i + "/jwks", "response_types_supported": ["code"],
                "grant_types_supported": ["authorization_code", "refresh_token"],
                "code_challenge_methods_supported": ["S256"],
                "token_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
                "authorization_response_iss_parameter_supported": True,
                "client_id_metadata_document_supported": not self.no_cimd})
        if req.path == "/dev/jwks":
            pub = self._key.public_key().public_numbers()
            n = pub.n.to_bytes((pub.n.bit_length() + 7) // 8, "big")
            e = pub.e.to_bytes((pub.e.bit_length() + 7) // 8, "big")
            return respond_json(start_response, 200, {"keys": [
                {"kty": "RSA", "kid": "dev", "alg": "RS256", "use": "sig", "n": b64url(n), "e": b64url(e)}]})
        if req.path == "/dev/authorize":
            return self._authorize(req, start_response)
        if req.path == "/dev/token":
            return self._token(req, start_response)
        return respond(start_response, 404, "404 page not found\n")

    def _authorize(self, req: Request, start_response):
        q = {k: v[0] for k, v in parse_qs(req.query, keep_blank_values=True).items()}
        with self._lock:
            self.authorizations += 1
            self.last_authorize_query = q
        cid, redirect = q.get("client_id", ""), q.get("redirect_uri", "")
        if not self._redirect_allowed(cid, redirect):
            return respond(start_response, 400, "invalid_client or redirect_uri\n")  # never redirect to an unverified URI

        def back(params):
            return respond(start_response, 302, b"", [("Location", redirect + "?" + urlencode(sorted(params.items())))])

        if q.get("response_type") != "code" or q.get("code_challenge_method") != "S256" or not q.get("code_challenge"):
            return back({"error": "invalid_request", "state": q.get("state", ""), "iss": self.issuer})
        if not q.get("resource"):
            return back({"error": "invalid_target", "state": q.get("state", ""), "iss": self.issuer})
        code = _rand(16)
        with self._lock:
            self._codes[code] = (cid, redirect, q["code_challenge"], q["resource"], q.get("scope", ""))
        return back({"code": code, "state": q.get("state", ""), "iss": self.iss_override or self.issuer})

    def _redirect_allowed(self, cid: str, redirect: str) -> bool:
        """Exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3)."""
        allowed = self.clients.get(cid, [])
        if cid.startswith("https://"):  # Client ID Metadata Document
            try:
                doc = get_json(self.fetch_http, cid)
            except Exception:
                return False
            if doc.get("client_id") != cid or not doc.get("client_name"):
                return False
            allowed = doc.get("redirect_uris") or []
        ru = urlsplit(redirect)
        for a in allowed:
            if a == redirect:
                return True
            au = urlsplit(a)
            try:
                loop = ipaddress.ip_address(ru.hostname or "").is_loopback
            except ValueError:
                loop = False
            if loop and au.hostname == ru.hostname and au.scheme == ru.scheme and au.path == ru.path:
                return True
        return False

    def _token(self, req: Request, start_response):
        f = {k: v[0] for k, v in parse_qs(req.body().decode(), keep_blank_values=True).items()}

        def bad(e, status=400):
            return respond_json(start_response, status, {"error": e})

        with self._lock:
            self.last_token_form = dict(f)
            auth = req.header("Authorization")
            if auth.startswith("Basic "):
                try:
                    cid, _, sec = base64.b64decode(auth[6:]).decode().partition(":")
                except ValueError:
                    return bad("invalid_client", 401)
                cid, sec = unquote(cid), unquote(sec)
                if (f.get("client_id") and f["client_id"] != cid) or not self.secrets.get(cid) or self.secrets[cid] != sec:
                    return bad("invalid_client", 401)
                f["client_id"] = cid
            elif self.secrets.get(f.get("client_id", "")) and f.get("client_secret") != self.secrets[f["client_id"]]:
                return bad("invalid_client", 401)
            gt = f.get("grant_type")
            if gt == "authorization_code":
                c = self._codes.pop(f.get("code", ""), None)  # single use
                if c is None:
                    return bad("invalid_grant")
                if b64url(hashlib.sha256(f.get("code_verifier", "").encode()).digest()) != c[2]:  # PKCE verified here
                    return bad("invalid_grant")
                if f.get("client_id") != c[0] or f.get("redirect_uri") != c[1] or f.get("resource") != c[3]:
                    return bad("invalid_grant")
            elif gt == "refresh_token":
                c = self._rts.get(f.get("refresh_token", ""))
                if c is None or f.get("client_id") != c[0]:
                    return bad("invalid_grant")
                del self._rts[f["refresh_token"]]  # rotation (required for public clients)
                res = f.get("resource", "")
                if res and (res != c[3] or self.reject_refresh_resource):
                    return bad("invalid_target")
                self.refreshes += 1
            else:
                return bad("unsupported_grant_type")
            rt = _rand(24)
            self._rts[rt] = c
        now = int(time.time())
        at = self.mint({"iss": self.issuer, "sub": "dev-user", "aud": c[3], "scope": c[4], "client_id": c[0],
                        "jti": _rand(8), "exp": now + int(self.access_ttl), "iat": now})
        return respond_json(start_response, 200, {"access_token": at, "token_type": "Bearer",
                                                   "expires_in": int(self.access_ttl), "refresh_token": rt, "scope": c[4]})
