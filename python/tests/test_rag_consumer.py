"""Tests for the ADR-0001 rag_go consumer needs (7 gaps) + the DisableTools/
DisableSkills ergonomic layer. Mirrors ``golang/rag_consumer_test.go``.

All hermetic — no external network, no live LLM:
- Client-side gaps (1/2/4/5) use a threaded local ``http.server`` that records the
  last decoded request body (the ``captureLLM`` analogue), or an injected transport.
- MCP-side gaps (3/6/7 + DisableTools) use a real in-process streamable-HTTP MCP
  server stood up via ``toolkit.serve(..., mcp=...)`` exposing native tools a,b,c.
"""
from __future__ import annotations

import asyncio
import http.server
import json
import threading
import time
from contextlib import contextmanager

import pytest

from toolnexus import (
    SkillDef,
    create_client,
    create_toolkit,
    define_tool,
    list_mcp_tools,
    load_mcp,
    load_mcp_with_context,
)
from toolnexus.mcp_source import ToolInfo


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
_CANNED = (
    b'{"choices":[{"message":{"content":"ok"}}],'
    b'"content":[{"type":"text","text":"ok"}],'
    b'"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,'
    b'"input_tokens":1,"output_tokens":1}}'
)


class _CaptureHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        self.server.last_body = json.loads(raw or b"{}")  # type: ignore[attr-defined]
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(_CANNED)))
        self.end_headers()
        self.wfile.write(_CANNED)

    def log_message(self, *args):  # silence
        pass


@contextmanager
def capture_llm():
    """A threaded local LLM that records the last decoded request body."""
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _CaptureHandler)
    srv.last_body = None  # type: ignore[attr-defined]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}", lambda: srv.last_body  # type: ignore[attr-defined]
    finally:
        srv.shutdown()
        srv.server_close()


def mk_tool(name: str):
    return define_tool(
        lambda **_kw: name,
        name=name,
        description=name,
        input_schema={"type": "object", "properties": {}, "additionalProperties": False},
    )


async def serve_mcp(tools):
    """Stand up an in-process streamable-HTTP MCP server exposing ``tools``.
    Returns ``(toolkit, serve_handle, mcp_url)``."""
    tk = await create_toolkit(builtins=False, extra_tools=tools)
    srv = await tk.serve("127.0.0.1:0", mcp={"name": "srv"})
    return tk, srv, srv.url + "/mcp"


def names(tools) -> set[str]:
    return {t.name for t in tools}


# --------------------------------------------------------------------------- #
# Gap 1 — request_params merge (caller wins) + body_transform + forbidden keys
# --------------------------------------------------------------------------- #
async def test_gap1_request_params_merge_and_caller_wins():
    tk = await create_toolkit(builtins=False)
    with capture_llm() as (url, body):
        oa = create_client(
            base_url=url, style="openai", model="m", api_key="k",
            request_params={"temperature": 0.42, "chat_template_kwargs": {"enable_thinking": False}},
        )
        await oa.run("hi", tk)
        assert body()["temperature"] == 0.42
        assert body()["chat_template_kwargs"] == {"enable_thinking": False}

        an = create_client(
            base_url=url, style="anthropic", model="m", api_key="k",
            request_params={"max_tokens": 999},
        )
        await an.run("hi", tk)
        # caller wins over the anthropic default 4096
        assert body()["max_tokens"] == 999


async def test_gap1_body_transform_runs_last_and_forbidden_keys_stripped():
    tk = await create_toolkit(builtins=False)
    with capture_llm() as (url, body):
        def transform(b):
            b["injected"] = "yes"
            del b["temperature"]
            return b

        c = create_client(
            base_url=url, style="openai", model="m", api_key="k",
            request_params={"temperature": 0.5, "messages": ["nope"], "tools": [1], "stream": True},
            body_transform=transform,
        )
        await c.run("hi", tk)
        # body_transform output is what hit the wire
        assert body()["injected"] == "yes"
        # transform ran after the merge (it saw + removed temperature)
        assert "temperature" not in body()
        # forbidden request_params keys never leak
        assert "stream" not in body()
        assert body()["messages"] and body()["messages"] != ["nope"]
        assert "tools" not in body()  # empty toolkit + forbidden tools override ignored


async def test_gap1_body_transform_none_leaves_body_unchanged():
    tk = await create_toolkit(builtins=False)
    with capture_llm() as (url, body):
        c = create_client(
            base_url=url, style="openai", model="m", api_key="k",
            request_params={"temperature": 0.1},
            body_transform=lambda _b: None,  # None ⇒ unchanged
        )
        await c.run("hi", tk)
        assert body()["temperature"] == 0.1


# --------------------------------------------------------------------------- #
# Gap 2 — injectable HTTP transport (LLM path)
# --------------------------------------------------------------------------- #
class _RecordingTransport:
    def __init__(self):
        self.hits = 0

    def post(self, url, headers, payload, timeout):
        self.hits += 1
        return {"choices": [{"message": {"content": "ok"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}

    def open(self, url, headers, payload, timeout):  # pragma: no cover - unused here
        raise NotImplementedError


async def test_gap2_http_transport_injected():
    tk = await create_toolkit(builtins=False)
    rt = _RecordingTransport()
    c = create_client(base_url="http://unused", style="openai", model="m", api_key="k", http_transport=rt)
    await c.run("hi", tk)
    assert rt.hits > 0


# --------------------------------------------------------------------------- #
# Gap 5 — omit empty tools / tool_choice
# --------------------------------------------------------------------------- #
async def test_gap5_omit_empty_tools():
    with capture_llm() as (url, body):
        empty = await create_toolkit(builtins=False)
        c1 = create_client(base_url=url, style="openai", model="m", api_key="k")
        await c1.run("hi", empty)
        assert "tools" not in body()
        assert "tool_choice" not in body()

        one = await create_toolkit(builtins=False, extra_tools=[mk_tool("t")])
        c2 = create_client(base_url=url, style="openai", model="m", api_key="k")
        await c2.run("hi", one)
        assert "tools" in body()
        assert body()["tool_choice"] == "auto"


# --------------------------------------------------------------------------- #
# Gap 4 — conversation_store accessor
# --------------------------------------------------------------------------- #
async def test_gap4_conversation_store_accessor():
    from toolnexus import InMemoryConversationStore

    custom = InMemoryConversationStore()
    c1 = create_client(base_url="http://x", style="openai", model="m", store=custom)
    assert c1.conversation_store is custom

    with capture_llm() as (url, _body):
        tk = await create_toolkit(builtins=False)
        c2 = create_client(base_url=url, style="openai", model="m", api_key="k")
        assert c2.conversation_store is not None
        await c2.ask("hi", tk, id="conv1")
        hist = await c2.conversation_store.get("conv1")
        assert hist is not None and len(hist) >= 2


# --------------------------------------------------------------------------- #
# Gap 7 — per-server tool allowlist
# --------------------------------------------------------------------------- #
async def test_gap7_per_server_tool_allowlist():
    tk, srv, url = await serve_mcp([mk_tool("a"), mk_tool("b"), mk_tool("c")])
    try:
        allow = await load_mcp({"srv": {"url": url, "tools": {"a": True, "b": True}}})
        assert names(allow.tools) == {"srv_a", "srv_b"}
        await allow.close()

        drop = await load_mcp({"srv": {"url": url, "tools": {"c": False}}})
        assert names(drop.tools) == {"srv_a", "srv_b"}
        await drop.close()

        unknown = await load_mcp({"srv": {"url": url, "tools": {"a": True, "nope": True}}})
        assert names(unknown.tools) == {"srv_a"}  # unknown ignored, no error
        await unknown.close()

        all_ = await load_mcp({"srv": {"url": url}})
        assert names(all_.tools) == {"srv_a", "srv_b", "srv_c"}
        await all_.close()

        empty = await load_mcp({"srv": {"url": url, "tools": {}}})
        assert names(empty.tools) == {"srv_a", "srv_b", "srv_c"}  # {} ⇒ all
        await empty.close()
    finally:
        await srv.stop()
        await tk.close()


# --------------------------------------------------------------------------- #
# Gap 6 — list-only inventory (unfiltered + status, incl. a failed server)
# --------------------------------------------------------------------------- #
async def test_gap6_list_mcp_tools():
    tk, srv, url = await serve_mcp([mk_tool("a"), mk_tool("b"), mk_tool("c")])
    try:
        inv = await list_mcp_tools({
            "good": {"url": url, "tools": {"a": True}},  # filter IGNORED by inventory
            "bad": {"url": "http://127.0.0.1:1/mcp", "timeout": 500},
        })
        assert len(inv.tools["good"]) == 3  # unfiltered (a,b,c)
        assert {ti.name for ti in inv.tools["good"]} == {"a", "b", "c"}
        assert all(isinstance(ti, ToolInfo) for ti in inv.tools["good"])
        assert inv.status["good"] == "connected"
        assert inv.status["bad"] == "failed"
    finally:
        await srv.stop()
        await tk.close()


async def test_gap6_disabled_server_not_connected():
    inv = await list_mcp_tools({"off": {"url": "http://127.0.0.1:1/mcp", "disabled": True}})
    assert inv.status["off"] == "disabled"
    assert "off" not in inv.tools


# --------------------------------------------------------------------------- #
# Gap 3 — hanging server bounded by timeout → failed; cancellation aborts
# --------------------------------------------------------------------------- #
async def test_gap3_hanging_bounded_and_cancel():
    stop = asyncio.Event()

    async def handle(reader, writer):
        try:
            await stop.wait()  # accept but never respond
        finally:
            try:
                writer.close()
            except Exception:
                pass

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    url = f"http://127.0.0.1:{port}/mcp"
    try:
        # Per-server timeout isolates the hang: no whole-load error, status failed,
        # bounded well under the 30s default.
        t0 = time.monotonic()
        src = await load_mcp({"hang": {"url": url, "timeout": 500}})
        assert src.status["hang"] == "failed"
        assert time.monotonic() - t0 < 5, "not bounded by the per-server timeout"
        await src.close()

        # Parent cancellation (a set asyncio.Event) aborts the WHOLE load.
        cancel = asyncio.Event()
        cancel.set()
        with pytest.raises(asyncio.CancelledError):
            await load_mcp_with_context(
                {"hang": {"url": url, "timeout": 60000}}, cancel=cancel
            )
    finally:
        stop.set()
        server.close()
        await server.wait_closed()


# --------------------------------------------------------------------------- #
# DisableTools / DisableSkills
# --------------------------------------------------------------------------- #
async def test_disable_tools():
    tk, srv, url = await serve_mcp([mk_tool("a"), mk_tool("b"), mk_tool("c")])
    try:
        client_tk = await create_toolkit(
            builtins=False,
            mcp_config={"srv": {"url": url}},
            disable_tools=["srv_b"],
        )
        try:
            assert client_tk.get("srv_b") is None  # dropped
            assert client_tk.get("srv_a") is not None  # kept
            assert client_tk.get("srv_c") is not None
        finally:
            await client_tk.close()
    finally:
        await srv.stop()
        await tk.close()


async def test_disable_skills():
    sk = await create_toolkit(
        builtins=False,
        skills=[
            SkillDef(name="keep", description="k", content="k"),
            SkillDef(name="drop", description="d", content="d"),
        ],
        disable_skills=["drop"],
    )
    prompt = sk.skills_prompt()
    assert "keep" in prompt
    assert "**drop**" not in prompt
