"""MCP 2026-07-28 client path (SPEC.md §2, §2A): discover first, MRTR, OAuth, skills, tool search.

No MCP SDK on this path; the SDK-based code in ``mcp_source`` is the frozen fallback (§2.F)."""
from .auth import AuthPending, MemoryTokenStore, OAuth, Token, TokenStore, open_browser
from .wire import PROTOCOL_VERSION, RpcError, parse_skill_uri

__all__ = [
    "AuthPending",
    "MemoryTokenStore",
    "OAuth",
    "PROTOCOL_VERSION",
    "RpcError",
    "Token",
    "TokenStore",
    "open_browser",
    "parse_skill_uri",
]
