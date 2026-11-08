"""Shared wire constants and small helpers for MCP 2026-07-28 (stateless Streamable HTTP)."""
from __future__ import annotations

import json
from typing import Any

PROTOCOL_VERSION = "2026-07-28"
META_VERSION = "io.modelcontextprotocol/protocolVersion"
META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities"
META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
SKILLS_EXT = "io.modelcontextprotocol/skills"

CODE_METHOD_NOT_FOUND = -32601
CODE_INVALID_PARAMS = -32602
CODE_HEADER_MISMATCH = -32020
CODE_UNSUPPORTED_PROTOCOL = -32022


class RpcError(Exception):
    """A JSON-RPC error object (raised by the client, returned by the server)."""

    def __init__(self, code: int, message: str, data: Any = None):
        super().__init__(message)
        self.code, self.message, self.data = code, message, data

    def to_json(self) -> dict:
        d: dict = {"code": self.code, "message": self.message}
        if self.data is not None:
            d["data"] = self.data
        return d


def text(result: dict | None) -> str:
    """The first text content of a tool result ("" if none)."""
    content = (result or {}).get("content") or []
    if not content or not isinstance(content[0], dict):
        return ""
    t = content[0].get("text")
    return t if isinstance(t, str) else ""


def dumps(v: Any) -> str:
    """Compact JSON with sorted keys (the same bytes Go's encoding/json produces for maps)."""
    return json.dumps(v, separators=(",", ":"), sort_keys=True, ensure_ascii=False)
