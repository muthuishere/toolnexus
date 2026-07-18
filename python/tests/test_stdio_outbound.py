"""Outbound (local) stdio MCP regression guard — ADR-0007.

v0.9.0 introduced a Go regression (ADR-0001 Gap 3): the ctx-aware connect bound a
local stdio MCP child's lifetime to a connect-timeout context, so the subprocess
died the moment connect returned — every call *after* a successful connect then hit
``transport closed``. Go was fixed by spawning the child on a background context.

The Python port shares the ADR-0001 ctx/cancel plumbing, but its MCP tests only ever
exercised **HTTP** servers — outbound stdio was unverified. This test spawns a genuine
stdio MCP server as a child process (``tests/_stdio_mcp_server.py``) and drives it
through ``load_mcp`` with a ``command`` config, asserting the regression signature is
absent: connect succeeds, ``tools/list`` returns the tools, and a tool **executes**
AFTER connect (i.e. the child survived the connect-timeout scope).
"""
from __future__ import annotations

import os
import sys

from toolnexus import create_toolkit, load_mcp

_SERVER = os.path.join(os.path.dirname(__file__), "_stdio_mcp_server.py")


def _stdio_config():
    # A real local stdio MCP server: python <path-to-server>. No `type`/`url` ⇒ local.
    return {"stdio": {"command": [sys.executable, _SERVER]}}


def names(tools) -> set[str]:
    return {t.name for t in tools}


async def test_stdio_outbound_child_stays_alive():
    """Connect to a stdio MCP child, list, then EXECUTE a tool post-connect."""
    src = await load_mcp(_stdio_config())
    try:
        # connect + tools/list succeeded
        assert src.status["stdio"] == "connected", src.status
        assert names(src.tools) == {"stdio_echo", "stdio_add", "stdio_ping"}

        # The regression signature is: connect works, then the child is dead so any
        # subsequent call fails with "transport closed". Executing a tool AFTER connect
        # is exactly that post-connect call — it must succeed.
        # FastMCP wraps a scalar return in structured content ({"result": ...}),
        # which _convert_tool JSON-encodes — so assert the value is present, not an
        # exact string. The load-bearing assertion is is_error is False: the call
        # reached a LIVE child. Under the regression it would be "transport closed".
        echo = next(t for t in src.tools if t.name == "stdio_echo")
        res = await echo.execute({"text": "hello"})
        assert res.is_error is False, res.output
        assert "hello" in res.output

        add = next(t for t in src.tools if t.name == "stdio_add")
        res2 = await add.execute({"a": 2, "b": 3})
        assert res2.is_error is False, res2.output
        assert "5" in res2.output

        ping = next(t for t in src.tools if t.name == "stdio_ping")
        res3 = await ping.execute({})
        assert res3.is_error is False, res3.output
        assert "pong" in res3.output
    finally:
        await src.close()


async def test_stdio_outbound_via_toolkit():
    """Same child, driven end-to-end through ``create_toolkit`` (the real entry point)."""
    tk = await create_toolkit(builtins=False, mcp_config=_stdio_config())
    try:
        assert tk.get("stdio_echo") is not None
        tool = tk.get("stdio_echo")
        res = await tool.execute({"text": "world"})
        assert res.is_error is False, res.output
        assert "world" in res.output
    finally:
        await tk.close()
