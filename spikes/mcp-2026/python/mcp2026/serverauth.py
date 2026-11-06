"""Server-side authorization: the MCP server as an OAuth 2.1 protected resource
(MCP 2026-07-28 basic/authorization). One object, set server.auth, done."""
from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, field
from typing import Callable

from .auth import PRM_PATH, discover_as, get_json
from .http import HttpClient, Request, b64url_decode, default_http


@dataclass
class Principal:
    """The authenticated caller; tool handlers read it as ctx.principal."""

    subject: str = ""
    scopes: list[str] = field(default_factory=list)
    claims: dict = field(default_factory=dict)


class ServerAuth:
    """Protects a Server. scopes["*"] is required for every request (and advertised as scopes_supported);
    scopes[tool_name] is additionally required for that tools/call."""

    def __init__(self, issuer: str, *, audience: str = "", scopes: dict[str, list[str]] | None = None,
                 verify: Callable[[str], Principal] | None = None, http: HttpClient | None = None) -> None:
        self.issuer = issuer  # the authorization server's issuer identifier
        self.audience = audience  # canonical resource URI; "" = derived from the request (scheme://host/mcp)
        self.scopes = scopes if scopes is not None else {}
        self.verify = verify  # replaces the JWT/JWKS verifier (opaque tokens, introspection, ...); raise to reject
        self.http = http or default_http()  # for AS metadata + JWKS fetches
        self._lock = threading.Lock()
        self._keys: dict | None = None  # JWKS cache by kid
        self._fetched = 0.0  # last JWKS fetch: unknown kids refetch at most every 30 s

    def _audience(self, req: Request) -> str:
        return self.audience or f"{req.scheme}://{req.host}/mcp"

    def metadata(self, req: Request) -> dict | None:
        """RFC 9728 PRM at both the path-inserted and root well-known URIs (None = not a PRM path)."""
        if req.path not in (PRM_PATH, PRM_PATH + "/mcp"):
            return None
        return {"resource": self._audience(req), "authorization_servers": [self.issuer],
                "scopes_supported": self.scopes.get("*"), "bearer_methods_supported": ["header"]}

    def check(self, req: Request, method: str, params: dict):
        """(principal, None) when allowed, else (None, (status, headers)) for a 401/403."""
        def challenge(status, extra, scopes):
            v = f'Bearer resource_metadata="{req.scheme}://{req.host}{PRM_PATH}/mcp"'
            if scopes:
                v += f', scope="{" ".join(scopes)}"'
            return None, (status, [("WWW-Authenticate", v + extra)])

        base = list(self.scopes.get("*") or [])
        auth = req.header("Authorization")
        tok = auth[7:] if auth.startswith("Bearer ") else ""
        if not tok:
            return challenge(401, "", base)
        try:
            pr = self.verify(tok) if self.verify else self._verify_jwt(tok, self._audience(req))
        except Exception:  # never echo the token or the detailed reason
            return challenge(401, ', error="invalid_token"', base)
        need = base + (self.scopes.get(params.get("name"), []) if method == "tools/call" else [])
        if any(s not in pr.scopes for s in need):
            return challenge(403, ', error="insufficient_scope"', need)
        return pr, None

    # ---- default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ----

    def _verify_jwt(self, tok: str, aud: str) -> Principal:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding

        parts = tok.split(".")
        if len(parts) != 3:
            raise ValueError("malformed jwt")
        hdr, claims = json.loads(b64url_decode(parts[0])), json.loads(b64url_decode(parts[1]))
        if not isinstance(hdr, dict) or not isinstance(claims, dict) or hdr.get("alg") != "RS256":
            raise ValueError("bad jwt header/claims")
        key = self._key(str(hdr.get("kid", "")))
        key.verify(b64url_decode(parts[2]), f"{parts[0]}.{parts[1]}".encode(), padding.PKCS1v15(), hashes.SHA256())
        if claims.get("iss") != self.issuer:
            raise ValueError("wrong issuer")
        a = claims.get("aud")
        if not (a == aud or (isinstance(a, list) and aud in a)):
            raise ValueError("wrong audience")
        exp = claims.get("exp")
        if not isinstance(exp, (int, float)) or int(exp) <= time.time():
            raise ValueError("expired")
        sc = claims.get("scope")
        return Principal(str(claims.get("sub") or ""), sc.split() if isinstance(sc, str) else [], claims)

    def _key(self, kid: str):
        from cryptography.hazmat.primitives.asymmetric import rsa

        with self._lock:
            if self._keys and kid in self._keys:
                return self._keys[kid]
            if self._keys is not None and time.time() - self._fetched < 30:  # garbage kids must not hammer the AS
                raise ValueError("unknown kid")
            md = discover_as(self.http, self.issuer)  # same RFC 8414 / OIDC order the client uses
            jwks = get_json(self.http, md.get("jwks_uri", ""))
            self._keys, self._fetched = {}, time.time()
            for k in jwks.get("keys") or []:
                try:
                    if k.get("kty") == "RSA":
                        n = int.from_bytes(b64url_decode(k["n"]), "big")
                        e = int.from_bytes(b64url_decode(k["e"]), "big")
                        self._keys[k.get("kid", "")] = rsa.RSAPublicNumbers(e, n).public_key()
                except (KeyError, ValueError):
                    continue
            if kid in self._keys:
                return self._keys[kid]
            raise ValueError("unknown kid")
