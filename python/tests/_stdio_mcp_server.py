"""A tiny self-contained stdio MCP server used by ``test_stdio_outbound.py``.

Run as ``python -m tests._stdio_mcp_server`` (or with the file path); it exposes
three trivial tools over the ``mcp`` SDK's **stdio** transport. This is the
outbound-stdio counterpart to the HTTP MCP servers the rest of the suite uses,
and the child process under test for the ADR-0007 regression (v0.9.0 stdio
connect killing the subprocess right after a successful connect).
"""
from __future__ import annotations

from mcp.server.fastmcp import FastMCP

server = FastMCP("stdio-fixture")


@server.tool(description="Echo the given text back.")
def echo(text: str) -> str:
    return text


@server.tool(description="Add two integers.")
def add(a: int, b: int) -> int:
    return a + b


@server.tool(description="Return a fixed greeting.")
def ping() -> str:
    return "pong"


if __name__ == "__main__":
    server.run(transport="stdio")
