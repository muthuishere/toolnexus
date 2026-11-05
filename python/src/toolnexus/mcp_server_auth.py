"""MCP server authorization (SPEC §2A.2, §7C.2): an :class:`~toolnexus.McpHandler` as an
OAuth 2.1 protected resource.

:class:`McpServerAuth` is a plain helper producing the handler's ``authenticate`` hook and
the RFC 9728 metadata ASGI app; the host mounts both. Identity, tenants and token storage
stay the host's::

    auth = McpServerAuth("https://login.example", scopes={"*": ["mcp"]})
    app = tk.mcp_handler(protocol_versions=["2026-07-28"], authenticate=auth.authenticate)
    # mount `app` at /mcp and `auth.metadata()` at /.well-known/oauth-protected-resource[/mcp]

The default verifier checks RS256 JWTs against the issuer's JWKS (standard library RSA, no
extra dependency); pass ``verify`` for opaque tokens or introspection.
"""
from __future__ import annotations

import json
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

import httpx

from ._rsa import b64url_decode, verify_rs256
from .mcp_handler import McpAuthError, McpRequest, _call, _decode_header_value

PRM_PATH = "/.well-known/oauth-protected-resource"


@dataclass
class McpPrincipal:
    """The verified caller. :meth:`McpServerAuth.authenticate` returns it, so hooks and
    tools read it with ``identity_from(ctx)``."""

    subject: str = ""
    scopes: list[str] = field(default_factory=list)
    claims: dict[str, Any] = field(default_factory=dict)


def _origin(req: McpRequest) -> str:
    scheme = req.scope.get("scheme") or "http"
    if scheme == "wss":
        scheme = "https"
    return f"{scheme}://{req.host}"


class McpServerAuth:
    """Protects an MCP handler with bearer tokens.

    - ``issuer``: the authorization server's issuer (advertised in the protected resource
      metadata; the JWT ``iss`` the default verifier requires).
    - ``audience``: the canonical resource URI (PRM ``resource``, the JWT ``aud``);
      "" ⇒ derived per request as ``<scheme>://<Host><resource_path>``.
    - ``resource_path``: where the handler is mounted (default ``/mcp``).
    - ``scopes``: ``"*"`` is required for every request (and advertised as
      ``scopes_supported``); a tool name adds scopes for that ``tools/call``. Exact match.
    - ``verify(request, token, audience) -> McpPrincipal`` (sync or async) replaces the
      default RS256/JWKS verifier; raising rejects the token.
    - ``http_client``: an ``httpx.AsyncClient`` for AS metadata + JWKS (default: 10 s).
    """

    def __init__(
        self,
        issuer: str,
        *,
        audience: str = "",
        resource_path: str = "",
        scopes: Optional[dict[str, list[str]]] = None,
        verify: Optional[Callable[..., Any]] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        self.issuer = issuer
        self.audience = audience
        self.resource_path = resource_path or "/mcp"
        self.scopes = dict(scopes or {})
        self.verify = verify
        self.http_client = http_client
        self._keys: Optional[dict[str, tuple[int, int]]] = None
        self._fetched = 0.0

    def _audience(self, req: McpRequest) -> str:
        return self.audience or _origin(req) + self.resource_path

    def metadata_url(self, req: McpRequest) -> str:
        """The RFC 9728 metadata URL the 401 challenge names."""
        if self.audience:
            u = urllib.parse.urlsplit(self.audience)
            if u.netloc:
                return f"{u.scheme}://{u.netloc}{PRM_PATH}{u.path}"
        return _origin(req) + PRM_PATH + self.resource_path

    def metadata(self) -> Any:
        """An ASGI app answering ``GET /.well-known/oauth-protected-resource`` and
        ``…/oauth-protected-resource<resource_path>``; anything else ``404``."""
        from .mcp_handler import _make_request

        async def app(scope: dict[str, Any], receive: Any, send: Any) -> None:
            if scope["type"] != "http":
                return
            req = _make_request(scope)
            if req.method != "GET" or req.path not in (PRM_PATH, PRM_PATH + self.resource_path):
                await send(
                    {
                        "type": "http.response.start",
                        "status": 404,
                        "headers": [
                            (b"content-type", b"text/plain; charset=utf-8"),
                            (b"x-content-type-options", b"nosniff"),
                        ],
                    }
                )
                await send({"type": "http.response.body", "body": b"404 page not found\n"})
                return
            doc = {
                "resource": self._audience(req),
                "authorization_servers": [self.issuer],
                "scopes_supported": list(self.scopes.get("*") or []),
                "bearer_methods_supported": ["header"],
            }
            await send(
                {"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"application/json")]}
            )
            await send({"type": "http.response.body", "body": (json.dumps(doc, separators=(",", ":")) + "\n").encode()})

        return app

    def _challenge(self, req: McpRequest, status: int, scopes: list[str], err: str) -> McpAuthError:
        v = f'Bearer resource_metadata="{self.metadata_url(req)}"'
        if scopes:
            v += f', scope="{" ".join(scopes)}"'
        if err:
            v += f', error="{err}"'
        return McpAuthError(status=status, challenge=v, reason=err)

    async def authenticate(self, req: McpRequest) -> McpPrincipal:
        """An ``authenticate`` hook. Missing token ⇒ 401; invalid / expired / wrong
        audience ⇒ 401 ``error="invalid_token"``; a missing scope ⇒ 403
        ``error="insufficient_scope"`` naming the full needed set. Per-tool scopes are
        read from the validated ``Mcp-Method`` / ``Mcp-Name`` headers, so they apply on
        the 2026-07-28 wire; older wires check ``scopes["*"]`` only. The token and the
        failure reason never reach the wire."""
        base = list(self.scopes.get("*") or [])
        authz = req.header("authorization")
        tok = authz[len("Bearer ") :] if authz.startswith("Bearer ") else ""
        if not tok.strip():
            raise self._challenge(req, 401, base, "")
        try:
            if self.verify is not None:
                p = await _call(self.verify, req, tok, self._audience(req))
            else:
                p = await self._verify_jwt(tok, self._audience(req))
        except Exception:  # noqa: BLE001 — any verify failure is invalid_token
            p = None
        if not isinstance(p, McpPrincipal):
            raise self._challenge(req, 401, base, "invalid_token")
        need = list(base)
        if req.header("mcp-method") == "tools/call":
            name, ok = _decode_header_value(req.header("mcp-name"))
            if ok:
                for s in self.scopes.get(name) or []:
                    if s not in need:
                        need.append(s)
        for s in need:
            if s not in p.scopes:
                raise self._challenge(req, 403, need, "insufficient_scope")
        return p

    async def _verify_jwt(self, tok: str, aud: str) -> McpPrincipal:
        parts = tok.split(".")
        if len(parts) != 3:
            raise ValueError("malformed jwt")
        hdr = json.loads(b64url_decode(parts[0]))
        claims = json.loads(b64url_decode(parts[1]))
        if not isinstance(hdr, dict) or not isinstance(claims, dict) or hdr.get("alg") != "RS256":
            raise ValueError("bad jwt header or claims")
        kid = hdr.get("kid") if isinstance(hdr.get("kid"), str) else ""
        n, e = await self._key(kid)
        if not verify_rs256(n, e, f"{parts[0]}.{parts[1]}".encode(), b64url_decode(parts[2])):
            raise ValueError("bad signature")
        if claims.get("iss") != self.issuer:
            raise ValueError("wrong issuer")
        a = claims.get("aud")
        if not ((isinstance(a, str) and a == aud) or (isinstance(a, list) and aud in a)):
            raise ValueError("wrong audience")
        exp = claims.get("exp")
        if not isinstance(exp, (int, float)) or isinstance(exp, bool) or int(exp) <= int(time.time()):
            raise ValueError("expired")
        sub = claims.get("sub") if isinstance(claims.get("sub"), str) else ""
        sc = claims.get("scope") if isinstance(claims.get("scope"), str) else ""
        return McpPrincipal(subject=sub, scopes=sc.split(), claims=claims)

    async def _get_json(self, client: httpx.AsyncClient, url: str) -> Any:
        r = await client.get(url, headers={"Accept": "application/json"})
        if r.status_code != 200:
            raise ValueError(f"GET {url}: HTTP {r.status_code}")
        return r.json()

    async def _key(self, kid: str) -> tuple[int, int]:
        """The JWKS key for ``kid``; an unknown kid refetches at most every 30 s."""
        if self._keys is not None and kid in self._keys:
            return self._keys[kid]
        if self._keys is not None and time.monotonic() - self._fetched < 30:
            raise ValueError("unknown kid")
        client = self.http_client or httpx.AsyncClient(timeout=10)
        try:
            md = await _discover_as(client, self.issuer, self._get_json)
            jwks = await self._get_json(client, md.get("jwks_uri", ""))
        finally:
            if self.http_client is None:
                await client.aclose()
        keys: dict[str, tuple[int, int]] = {}
        for k in jwks.get("keys") or [] if isinstance(jwks, dict) else []:
            if not isinstance(k, dict) or k.get("kty") != "RSA":
                continue
            try:
                n = int.from_bytes(b64url_decode(k.get("n", "")), "big")
                e = int.from_bytes(b64url_decode(k.get("e", "")), "big")
            except ValueError:
                continue
            keys[str(k.get("kid", ""))] = (n, e)
        self._keys, self._fetched = keys, time.monotonic()
        if kid in keys:
            return keys[kid]
        raise ValueError("unknown kid")


async def _discover_as(client: httpx.AsyncClient, issuer: str, get_json: Any) -> dict[str, Any]:
    """AS metadata in the SPEC §2A.1 step 3 order, whose ``issuer`` equals ``issuer``."""
    u = urllib.parse.urlsplit(issuer)
    origin, path = f"{u.scheme}://{u.netloc}", u.path.rstrip("/")
    cands = [origin + "/.well-known/oauth-authorization-server" + path, origin + "/.well-known/openid-configuration" + path]
    if path:
        cands.append(origin + path + "/.well-known/openid-configuration")
    for c in cands:
        try:
            md = await get_json(client, c)
        except Exception:  # noqa: BLE001
            continue
        if isinstance(md, dict) and md.get("issuer") == issuer:
            return md
    raise ValueError("authorization server metadata not found for " + issuer)
