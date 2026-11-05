"""One blocking MCP 2026-07-28 connection: Streamable HTTP (stateless POST) or stdio (§2.2–§2.6)."""
from __future__ import annotations

import itertools
import json
from typing import Any, Optional

from .auth import MAX_AUTH_RETRIES, OAuth
from .http import HttpClient
from .stdio import StdioConn
from .wire import (
    CLIENT_CAPABILITIES,
    META_CLIENT_CAPS,
    META_CLIENT_INFO,
    META_VERSION,
    PROTOCOL_VERSION,
    RpcError,
    encode_header,
)


class HttpStatusError(OSError):
    """A non-2xx HTTP reply that carried no JSON-RPC response."""

    def __init__(self, status: int):
        super().__init__(f"HTTP {status}")
        self.status = status


class NotJsonRpc(ValueError):
    """A reply that is not a JSON-RPC response for the request."""


class Conn:
    """Sends requests with the per-request ``_meta`` (and, over HTTP, the 2026 headers).

    Retry rule (§2.6): a request is re-sent ONLY after a pre-dispatch auth rejection (401, or 403
    ``insufficient_scope``, with ``WWW-Authenticate``), at most 3 times. A lost connection, timeout,
    5xx or unreadable response raises and is never retried."""

    def __init__(
        self,
        *,
        url: str = "",
        headers: Optional[dict[str, str]] = None,
        stdio: Optional[StdioConn] = None,
        timeout: float = 30.0,
        oauth: Optional[OAuth] = None,
        client_info: Optional[dict[str, str]] = None,
    ) -> None:
        self.url = url
        self.stdio = stdio
        self.timeout = timeout
        self.oauth = oauth
        self.http = HttpClient(timeout=timeout, headers=dict(headers or {}))
        self.info = client_info or {"name": "toolnexus", "version": "0"}
        self._ids = itertools.count(1)

    def close(self) -> None:
        if self.stdio is not None:
            self.stdio.close()

    def call(self, method: str, params: Optional[dict[str, Any]] = None, timeout: Optional[float] = None) -> dict[str, Any]:
        p: dict[str, Any] = dict(params or {})
        p["_meta"] = {
            META_VERSION: PROTOCOL_VERSION,
            META_CLIENT_CAPS: CLIENT_CAPABILITIES,
            META_CLIENT_INFO: self.info,
        }
        rid = next(self._ids)
        body = json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": p}).encode()
        if self.stdio is not None:
            msg = self.stdio.round_trip(rid, body, timeout if timeout is not None else self.timeout)
        else:
            msg = self._http_round_trip(method, p, body, timeout)
        if msg.get("id") != rid:
            raise NotJsonRpc("response id does not match the request")
        if msg.get("error") is not None:
            e = msg["error"] if isinstance(msg["error"], dict) else {}
            raise RpcError(int(e.get("code", 0) or 0), str(e.get("message", "")), e.get("data"))
        res = msg.get("result")
        if not isinstance(res, dict):
            raise NotJsonRpc("response has neither result nor error")
        res.setdefault("resultType", "complete")
        return res

    def _http_round_trip(self, method: str, params: dict[str, Any], body: bytes, timeout: Optional[float]) -> dict[str, Any]:
        h = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": PROTOCOL_VERSION,
            "Mcp-Method": method,
        }
        if method in ("tools/call", "prompts/get"):
            h["Mcp-Name"] = encode_header(str(params.get("name") or ""))
        elif method == "resources/read":
            h["Mcp-Name"] = encode_header(str(params.get("uri") or ""))
        hc = self.http if timeout is None else HttpClient(timeout=timeout, headers=self.http.headers)
        attempt = 0
        while True:
            sent = self.oauth.bearer(self.url) if self.oauth is not None else ""
            hh = dict(h)
            if sent:
                hh["Authorization"] = "Bearer " + sent
            resp = hc.request("POST", self.url, body, hh)  # an exception here is never retried
            www = resp.header("WWW-Authenticate")
            if self.oauth is None or resp.status not in (401, 403) or not www:
                break
            if attempt >= MAX_AUTH_RETRIES:
                raise PermissionError(f"authorization failed: HTTP {resp.status} after {attempt + 1} attempts")
            self.oauth.challenge(self.url, resp.status, www, sent)
            attempt += 1
        msg: Any = None
        if resp.header("Content-Type").startswith("text/event-stream"):
            for line in resp.body.decode("utf-8", "replace").splitlines():
                if line.startswith("data:"):
                    try:
                        m = json.loads(line[5:])
                    except ValueError:
                        continue
                    if isinstance(m, dict) and ("result" in m or "error" in m):
                        msg = m
        else:
            try:
                msg = json.loads(resp.body) if resp.body else None
            except ValueError:
                msg = None
        if not isinstance(msg, dict) or (msg.get("result") is None and msg.get("error") is None):
            if not 200 <= resp.status < 300:
                raise HttpStatusError(resp.status)
            raise NotJsonRpc(f"HTTP {resp.status}: response is not a JSON-RPC response")
        if resp.status >= 500:
            raise HttpStatusError(resp.status)
        return msg
