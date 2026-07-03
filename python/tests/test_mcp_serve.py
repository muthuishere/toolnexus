"""MCP inbound (§7C) tests — serve a toolkit as an MCP server.

Mirrors the JS reference suite (``js/test/unit.test.ts`` "mcp inbound:" block).
All hermetic: the port's own MCP **client** connects to the served toolkit over an
in-memory linked transport (``tools/list`` + ``tools/call`` round-trips) or, for the
streamable-HTTP path, over an ephemeral localhost port. No external network.
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import timedelta

import anyio
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.memory import create_client_server_memory_streams

from toolnexus import (
    build_mcp_server,
    create_toolkit,
    define_tool,
    exposed_mcp_tools,
)

# ---------------------------------------------------------------------------
# Fixtures: two native tools mirroring the JS echoTool / boomTool.
# ---------------------------------------------------------------------------

echo_tool = define_tool(
    lambda text="": str(text),
    name="echo",
    description="echo back text",
    input_schema={
        "type": "object",
        "properties": {"text": {"type": "string"}},
        "required": ["text"],
        "additionalProperties": False,
    },
)


def _boom() -> str:
    raise RuntimeError("kaboom")


boom_tool = define_tool(_boom, name="boom", description="always throws")


@asynccontextmanager
async def _connect_in_proc(server):
    """Connect an in-process MCP client to a built server via a linked transport
    pair; yields ``(session, init_result)`` so tests can read serverInfo."""
    async with create_client_server_memory_streams() as (client_streams, server_streams):
        client_read, client_write = client_streams
        server_read, server_write = server_streams
        async with anyio.create_task_group() as tg:
            tg.start_soon(
                lambda: server.run(
                    server_read,
                    server_write,
                    server.create_initialization_options(),
                )
            )
            async with ClientSession(
                read_stream=client_read,
                write_stream=client_write,
                read_timeout_seconds=timedelta(seconds=5),
            ) as session:
                init = await session.initialize()
                try:
                    yield session, init
                finally:
                    tg.cancel_scope.cancel()


# ---------------------------------------------------------------------------
# Tests.
# ---------------------------------------------------------------------------


def test_exposed_mcp_tools_filters_by_name():
    tools = [echo_tool, boom_tool]
    assert [t.name for t in exposed_mcp_tools(tools)] == ["echo", "boom"]
    assert [t.name for t in exposed_mcp_tools(tools, {"tools": ["echo"]})] == ["echo"]
    # unknown names in the filter are ignored, not an error
    assert [t.name for t in exposed_mcp_tools(tools, {"tools": ["echo", "nope"]})] == ["echo"]


async def test_initialize_reports_server_info():
    server = build_mcp_server([echo_tool], {"name": "gateway", "version": "2.0.0"})
    async with _connect_in_proc(server) as (_session, init):
        assert init.serverInfo.name == "gateway"
        assert init.serverInfo.version == "2.0.0"


async def test_tools_list_advertises_unified_tools_with_input_schema():
    server = build_mcp_server([echo_tool, boom_tool])
    async with _connect_in_proc(server) as (session, _init):
        res = await session.list_tools()
        assert sorted(t.name for t in res.tools) == ["boom", "echo"]
        echo = next(t for t in res.tools if t.name == "echo")
        assert echo.inputSchema == echo_tool.input_schema


async def test_mcp_tools_narrows_the_advertised_surface():
    server = build_mcp_server(
        exposed_mcp_tools([echo_tool, boom_tool], {"tools": ["echo"]}),
        {"tools": ["echo"]},
    )
    async with _connect_in_proc(server) as (session, _init):
        res = await session.list_tools()
        assert [t.name for t in res.tools] == ["echo"]


async def test_tools_call_dispatches_to_execute():
    calls: list[dict] = []
    server = build_mcp_server([echo_tool], None, lambda ev: calls.append(ev))
    async with _connect_in_proc(server) as (session, _init):
        res = await session.call_tool("echo", {"text": "hi"})
        assert res.isError is False
        assert res.content[0].type == "text"
        assert res.content[0].text == "hi"
        assert len(calls) == 1
        assert calls[0]["name"] == "echo"
        assert calls[0]["source"] == "native"
        assert calls[0]["is_error"] is False


async def test_erroring_tool_isError_server_survives():
    server = build_mcp_server([echo_tool, boom_tool])
    async with _connect_in_proc(server) as (session, _init):
        bad = await session.call_tool("boom", {})
        assert bad.isError is True
        assert "kaboom" in bad.content[0].text
        # server keeps serving
        ok = await session.call_tool("echo", {"text": "still up"})
        assert ok.isError is False
        assert ok.content[0].text == "still up"


async def test_streamable_http_mcp_round_trip_via_serve():
    tk = await create_toolkit(builtins=False, extra_tools=[echo_tool])
    srv = await tk.serve("127.0.0.1:0", mcp={"name": "http-gw"})
    try:
        async with streamable_http_client(srv.url + "/mcp") as (read, write, _):
            async with ClientSession(read, write) as session:
                init = await session.initialize()
                assert init.serverInfo.name == "http-gw"
                res = await session.list_tools()
                assert any(t.name == "echo" for t in res.tools)
                call = await session.call_tool("echo", {"text": "over http"})
                assert call.content[0].text == "over http"
    finally:
        await srv.stop()
        await tk.close()


async def test_absent_profile_no_mcp_surface():
    import json
    import urllib.request

    tk = await create_toolkit(builtins=False, extra_tools=[echo_tool])
    srv = await tk.serve("127.0.0.1:0", mcp=None, a2a={"name": "only-a2a"})
    try:
        req = urllib.request.Request(
            srv.url + "/mcp",
            data=json.dumps(
                {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}
            ).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
            },
            method="POST",
        )
        with urllib.request.urlopen(req) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        # A2A POST handler treats it as an unknown JSON-RPC method (not an MCP endpoint).
        assert body.get("error"), "expected a JSON-RPC error, not an MCP tools/list result"
        assert body.get("result") is None
    finally:
        await srv.stop()
        await tk.close()


async def test_top_level_mcp_server_config_picked_up():
    tk = await create_toolkit(
        mcp_config={"mcpServer": {"name": "from-config"}},
        builtins=False,
        extra_tools=[echo_tool],
    )
    # serve() with no inline mcp falls back to the config block
    srv = await tk.serve("127.0.0.1:0")
    try:
        async with streamable_http_client(srv.url + "/mcp") as (read, write, _):
            async with ClientSession(read, write) as session:
                init = await session.initialize()
                assert init.serverInfo.name == "from-config"
    finally:
        await srv.stop()
        await tk.close()
