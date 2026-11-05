"""TEST SUPPORT ONLY: a dev OAuth AS (ported from spikes/mcp-2026/python devauth.py) over plain HTTP,
auto-approving as dev-user, issuing opaque tokens the fixture server checks in-process."""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import threading
import time
from urllib.parse import parse_qs, urlencode, urlsplit

from .server import Running, _json


def _r(n=16):
    return base64.urlsafe_b64encode(secrets.token_bytes(n)).rstrip(b"=").decode()


class DevAuth:
    def __init__(self):
        self.clients = {}  # client_id -> [redirect uris]
        self.access_ttl = 3600
        self.iss_override = ""
        self.no_s256 = False
        self.no_cimd = True
        self.reject_refresh_resource = False
        self.form_encoded = False
        self.authorizations = 0
        self.refreshes = 0
        self.last_authorize = {}
        self.last_token_form = {}
        self._lock = threading.Lock()
        self._codes, self._rts, self._ats = {}, {}, {}
        self.run = Running(self)
        self.issuer = self.run.base + "/dev"

    def close(self):
        self.run.close()

    def check(self, authz, resource, need, url):
        prm = url.replace("/mcp", "") + "/.well-known/oauth-protected-resource/mcp"
        base = f'Bearer resource_metadata="{prm}"'
        if not authz.startswith("Bearer "):
            return 401, base + ', scope="mcp:read"'
        t = self._ats.get(authz[7:])
        if t is None or t["aud"] != resource or time.time() > t["exp"]:
            return 401, base + ', error="invalid_token"'
        if any(s not in t["scope"].split() for s in need):
            return 403, base + f', error="insufficient_scope", scope="{" ".join(need)}"'
        return None

    def __call__(self, environ, start_response):
        path = environ.get("PATH_INFO", "")
        if path == "/.well-known/oauth-authorization-server/dev":
            i = self.issuer
            return _json(start_response, 200, {
                "issuer": i, "authorization_endpoint": i + "/authorize", "token_endpoint": i + "/token",
                "code_challenge_methods_supported": [] if self.no_s256 else ["S256"],
                "authorization_response_iss_parameter_supported": True,
                "client_id_metadata_document_supported": not self.no_cimd})
        if path == "/dev/authorize":
            q = {k: v[0] for k, v in parse_qs(environ.get("QUERY_STRING", "")).items()}
            with self._lock:
                self.authorizations += 1
                self.last_authorize = q
            redirect = q.get("redirect_uri", "")
            ok = any(urlsplit(a).path == urlsplit(redirect).path for a in self.clients.get(q.get("client_id"), []))
            if not ok:
                return _json(start_response, 400, {"error": "invalid_client"})
            code = _r()
            self._codes[code] = (q["client_id"], redirect, q["code_challenge"], q["resource"], q.get("scope", ""))
            loc = redirect + "?" + urlencode({"code": code, "state": q.get("state", ""), "iss": self.iss_override or self.issuer})
            start_response("302 Found", [("Location", loc), ("Content-Length", "0")])
            return [b""]
        if path == "/dev/token":
            n = int(environ.get("CONTENT_LENGTH") or 0)
            f = {k: v[0] for k, v in parse_qs(environ["wsgi.input"].read(n).decode()).items()}
            with self._lock:
                self.last_token_form = dict(f)
                if f.get("grant_type") == "authorization_code":
                    c = self._codes.pop(f.get("code", ""), None)
                    v = base64.urlsafe_b64encode(hashlib.sha256(f.get("code_verifier", "").encode()).digest()).rstrip(b"=").decode()
                    if c is None or v != c[2] or f.get("resource") != c[3] or f.get("redirect_uri") != c[1]:
                        return _json(start_response, 400, {"error": "invalid_grant"})
                elif f.get("grant_type") == "refresh_token":
                    c = self._rts.pop(f.get("refresh_token", ""), None)
                    if c is None:
                        return _json(start_response, 400, {"error": "invalid_grant"})
                    if f.get("resource") and self.reject_refresh_resource:
                        return _json(start_response, 400, {"error": "invalid_target"})
                    self.refreshes += 1
                else:
                    return _json(start_response, 400, {"error": "unsupported_grant_type"})
                at, rt = _r(24), _r(24)
                self._rts[rt] = c
                self._ats[at] = {"aud": c[3], "scope": c[4], "exp": time.time() + self.access_ttl}
            body = {"access_token": at, "token_type": "Bearer", "expires_in": self.access_ttl,
                    "refresh_token": rt, "scope": c[4]}
            if self.form_encoded:
                b = urlencode(body).encode()
                start_response("200 OK", [("Content-Type", "application/x-www-form-urlencoded"), ("Content-Length", str(len(b)))])
                return [b]
            return _json(start_response, 200, body)
        return _json(start_response, 404, {})
