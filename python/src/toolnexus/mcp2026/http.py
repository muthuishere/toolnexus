"""A tiny stdlib (``http.client``) HTTP client for the MCP 2026 path and its OAuth flow.

Blocking by design: the async MCP source runs it on a worker thread (``asyncio.to_thread``).
"""
from __future__ import annotations

import http.client
import json
import ssl
from dataclasses import dataclass, field
from typing import Any, Optional
from urllib.parse import urljoin, urlsplit

DEFAULT_TIMEOUT = 30.0


@dataclass
class Response:
    status: int
    headers: http.client.HTTPMessage
    body: bytes
    url: str

    def header(self, name: str) -> str:
        return self.headers.get(name, "") or ""

    def json(self) -> Any:
        return json.loads(self.body)


@dataclass
class HttpClient:
    """Minimal HTTP client. ``headers`` go on every request (the configured remote headers)."""

    timeout: float = DEFAULT_TIMEOUT
    headers: dict[str, str] = field(default_factory=dict)
    follow_redirects: bool = False
    ssl_context: Optional[ssl.SSLContext] = None

    def request(
        self,
        method: str,
        url: str,
        body: bytes | str | None = None,
        headers: Optional[dict[str, str]] = None,
    ) -> Response:
        for _ in range(10):
            resp = self._once(method, url, body, headers)
            loc = resp.header("Location")
            if not (self.follow_redirects and resp.status in (301, 302, 303, 307, 308) and loc):
                return resp
            url = urljoin(url, loc)
            if resp.status in (301, 302, 303):
                method, body = "GET", None
        raise OSError("too many redirects")

    def get(self, url: str, headers: Optional[dict[str, str]] = None) -> Response:
        return self.request("GET", url, headers=headers)

    def _once(self, method: str, url: str, body: Any, headers: Optional[dict[str, str]]) -> Response:
        u = urlsplit(url)
        conn: http.client.HTTPConnection
        if u.scheme == "https":
            conn = http.client.HTTPSConnection(
                u.hostname or "", u.port, timeout=self.timeout,
                context=self.ssl_context or ssl.create_default_context(),
            )
        elif u.scheme == "http":
            conn = http.client.HTTPConnection(u.hostname or "", u.port, timeout=self.timeout)
        else:
            raise ValueError(f"unsupported URL scheme: {u.scheme!r}")
        h = {**self.headers, **(headers or {})}
        if isinstance(body, str):
            body = body.encode()
        try:
            conn.request(method, (u.path or "/") + (f"?{u.query}" if u.query else ""), body=body, headers=h)
            r = conn.getresponse()
            return Response(r.status, r.headers, r.read(), url)
        finally:
            conn.close()
