"""MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C).

The inbound mirror of the A2A ``serve`` (§7B). Where A2A advertises the toolkit's
**skills** and fulfils a Task through the whole client loop, MCP advertises the
toolkit's **unified tools** (every source — mcp · skill · native · http · builtin
· a2a) and dispatches each ``tools/call`` straight to ``Tool.execute``. The calling
MCP client *is* the LLM host, so there is **no client, no Task, and no TaskStore** —
just ``tools/list`` + ``tools/call`` over the toolkit registry.

The server is exposed over **streamable-HTTP**, mounted at ``POST /mcp`` on the
``serve(addr, …)`` server, alongside the A2A routes (see serve.py). Built on the
same ``mcp`` SDK the client side already uses.

Mirrors the JS reference (``js/src/mcpserve.ts``).
"""
from __future__ import annotations

import asyncio
import inspect
from typing import Any, Awaitable, Callable, Optional, Union

import anyio
from mcp.server.lowlevel import Server
from mcp.server.streamable_http import StreamableHTTPServerTransport
from mcp.shared.exceptions import McpError
from mcp.types import INVALID_PARAMS, CallToolResult, ErrorData, TextContent
from mcp.types import Tool as McpToolDef

from .types import Tool, ToolResult

DEFAULT_NAME = "toolnexus"
DEFAULT_VERSION = "0.1.0"

# Opt-in MCP serve profile — the toolkit is exposed as an MCP server. A plain dict,
# mirroring the JS `MCPServeConfig` interface (and the wire config block):
#   { "name"?, "version"?, "tools"?: [str] }
#   name default "toolnexus", version default "0.1.0"; tools omit ⇒ all, unknown
#   names in the filter are ignored (never an error).
MCPServeConfig = dict[str, Any]

# on_call(ev) — fires per inbound tools/call. ev = { "name", "source", "ms",
# "is_error" } (snake_case, matching the port's other event dicts).
OnCall = Callable[[dict[str, Any]], Union[None, Awaitable[None]]]


def _coalesce(value: Any, default: Any) -> Any:
    """JS ``??`` semantics — the default applies only for None (not "" / falsy)."""
    return value if value is not None else default


def exposed_mcp_tools(
    tools: list[Tool], cfg: Optional[MCPServeConfig] = None
) -> list[Tool]:
    """The tools this profile exposes: every toolkit tool, filtered to ``cfg["tools"]``
    when set (unknown names in the filter are ignored, never an error).
    """
    wanted = cfg.get("tools") if cfg else None
    if wanted is None:
        return tools
    wanted_set = set(wanted)
    return [t for t in tools if t.name in wanted_set]


def build_mcp_server(
    tools: list[Tool],
    cfg: Optional[MCPServeConfig] = None,
    on_call: Optional[OnCall] = None,
) -> Server:
    """Build a low-level MCP :class:`Server` exposing ``tools`` as MCP tools.

    ``tools/list`` maps each Tool's name (verbatim — already sanitized at
    registration), description, and input schema; ``tools/call`` dispatches to
    ``Tool.execute`` and maps the :class:`ToolResult` to a ``CallToolResult``
    (``output`` → text, ``is_error`` propagates). A fresh server is cheap to build,
    so the HTTP path makes one per request (stateless).
    """
    by_name = {t.name: t for t in tools}
    server: Server = Server(
        name=_coalesce(cfg.get("name") if cfg else None, DEFAULT_NAME),
        version=_coalesce(cfg.get("version") if cfg else None, DEFAULT_VERSION),
    )

    @server.list_tools()
    async def _list_tools() -> list[McpToolDef]:
        return [
            McpToolDef(name=t.name, description=t.description, inputSchema=t.input_schema)
            for t in tools
        ]

    # validate_input=False: no input validation — execute() is called directly,
    # matching the JS reference (which does not validate against inputSchema).
    @server.call_tool(validate_input=False)
    async def _call_tool(name: str, arguments: dict[str, Any]) -> CallToolResult:
        tool = by_name.get(name)
        if tool is None:
            # Unknown tool name → the SDK's standard error (InvalidParams / -32602).
            raise McpError(ErrorData(code=INVALID_PARAMS, message=f"Unknown tool: {name}"))
        started = asyncio.get_running_loop().time()
        # A single tool invocation. An execute() throw becomes an is_error result —
        # never a crash.
        try:
            result = await tool.execute(arguments or {})
        except Exception as e:  # noqa: BLE001 — surfaced as an is_error result
            result = ToolResult(output=str(e), is_error=True)
        if on_call is not None:
            try:
                ev = on_call(
                    {
                        "name": tool.name,
                        "source": tool.source,
                        "ms": (asyncio.get_running_loop().time() - started) * 1000.0,
                        "is_error": result.is_error,
                    }
                )
                if inspect.isawaitable(ev):
                    await ev
            except Exception:  # noqa: BLE001 — host callback errors are isolated
                pass
        return CallToolResult(
            content=[TextContent(type="text", text=result.output)],
            isError=result.is_error,
        )

    return server


# ---------------------------------------------------------------------------
# streamable-HTTP — a single stateless request handled through the MCP transport.
# ---------------------------------------------------------------------------


async def _run_stateless(
    server: Server,
    transport: StreamableHTTPServerTransport,
    *,
    task_status: Any = anyio.TASK_STATUS_IGNORED,
) -> None:
    async with transport.connect() as (read_stream, write_stream):
        task_status.started()
        try:
            await server.run(
                read_stream,
                write_stream,
                server.create_initialization_options(),
                stateless=True,
            )
        except Exception:  # noqa: BLE001 — a crashed session never crashes the host
            pass


async def serve_mcp_http_request(
    server: Server,
    method: str,
    path: str,
    req_headers: list[tuple[bytes, bytes]],
    body: bytes,
) -> tuple[int, list[tuple[bytes, bytes]], bytes]:
    """Handle ONE streamable-HTTP MCP request through a fresh stateless transport,
    returning ``(status, headers, body)`` for the caller to write to the socket.

    Mirrors the JS serve.ts ``/mcp`` path: a fresh server+transport per request
    (stateless — no session id). Built on top of the MCP SDK's ASGI transport via
    a minimal in-memory ASGI bridge, so the sync HTTP server in serve.py can drive it.
    """
    captured: dict[str, Any] = {"status": 500, "headers": [], "body": bytearray()}

    async def receive() -> dict[str, Any]:
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message: dict[str, Any]) -> None:
        if message["type"] == "http.response.start":
            captured["status"] = message["status"]
            captured["headers"] = message.get("headers", [])
        elif message["type"] == "http.response.body":
            captured["body"].extend(message.get("body", b""))

    scope: dict[str, Any] = {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": method,
        "scheme": "http",
        "path": path,
        "raw_path": path.encode("ascii"),
        "query_string": b"",
        "root_path": "",
        "headers": req_headers,
        "server": ("127.0.0.1", 0),
        "client": ("127.0.0.1", 0),
    }

    transport = StreamableHTTPServerTransport(
        mcp_session_id=None, is_json_response_enabled=True
    )
    async with anyio.create_task_group() as tg:
        await tg.start(_run_stateless, server, transport)
        await transport.handle_request(scope, receive, send)
        await transport.terminate()
        tg.cancel_scope.cancel()

    return captured["status"], captured["headers"], bytes(captured["body"])
