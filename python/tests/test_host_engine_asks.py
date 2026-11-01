"""add-go-host-asks (SPEC §4C): concurrent register, per-server MCP readiness
(on_server), wait_for_ctx, tool hints, permission gates on before_tool, call ids.
Hermetic: scripted LLM transports, the shared examples/mcp-progress-server.mjs.
Mirrors golang/host_engine_asks_test.go."""
from __future__ import annotations

import asyncio
import io
import json
import shutil
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from toolnexus import (
    Answer,
    RunCancelled,
    Tool,
    ToolHints,
    ToolResult,
    create_client,
    create_toolkit,
    define_tool,
    load_mcp,
    mcp_hints,
    pending,
)
from toolnexus.builtin import create_builtin_tools

FIXTURE = Path(__file__).resolve().parents[2] / "examples" / "mcp-progress-server.mjs"
NODE = shutil.which("node")
needs_node = pytest.mark.skipif(NODE is None, reason="node not installed")
SCHEMA = {"type": "object", "properties": {}}


def _tool(name: str, fn, hints: ToolHints | None = None) -> Tool:
    return Tool(name=name, description=name, input_schema=SCHEMA, source="native", execute=fn, hints=hints)


# ---- concurrent register ---------------------------------------------------

async def test_toolkit_register_concurrent_with_reads():
    tk = await create_toolkit(builtins=False)
    stop = threading.Event()

    async def ok(args=None, ctx=None):
        return ToolResult(output="ok", is_error=False)

    def reader():
        loop = asyncio.new_event_loop()
        while not stop.is_set():
            tk.tools()
            tk.get("t0")
            loop.run_until_complete(tk.execute("t0"))
            tk.to_openai()
        loop.close()

    readers = [threading.Thread(target=reader) for _ in range(4)]
    for r in readers:
        r.start()
    writers = [
        threading.Thread(target=lambda i=i: tk.register(*(_tool(f"t{j}", ok) for j in range(i, 200, 4))))
        for i in range(4)
    ]
    for w in writers:
        w.start()
    for w in writers:
        w.join()
    stop.set()
    for r in readers:
        r.join()
    assert len(tk.tools()) == 200


# ---- per-server readiness --------------------------------------------------

@needs_node
async def test_load_mcp_on_server_reports_fast_server_first():
    cfg = {
        "mcp": {
            # slow first in config order: readiness must not follow config order
            "slow": {"command": ["sh", "-c", f"sleep 1; exec {NODE} {FIXTURE}"], "timeout": 5000},
            "fast": {"command": [NODE, str(FIXTURE)], "timeout": 5000},
            "bad": {"command": ["/nonexistent/toolnexus-no-such-binary"], "timeout": 2000},
            "off": {"command": [NODE, str(FIXTURE)], "enabled": False},
        }
    }
    tk = await create_toolkit(builtins=False)
    start = time.monotonic()
    order: list[str] = []
    at: dict[str, float] = {}
    results: dict[str, Any] = {}

    def on_server(r):
        order.append(r.name)
        at[r.name] = time.monotonic() - start
        results[r.name] = r
        tk.register(*r.tools)

    src = await load_mcp(cfg, on_server=on_server)
    try:
        assert sorted(order) == ["bad", "fast", "off", "slow"]
        assert results["fast"].status == "connected" and results["fast"].tools
        assert results["slow"].status == "connected"
        assert results["bad"].status == "failed" and results["bad"].error is not None
        assert results["off"].status == "disabled"
        assert at["fast"] < at["slow"] and at["slow"] - at["fast"] >= 0.5, at
        assert tk.get("fast_slow") is not None
        assert src.status == {"slow": "connected", "fast": "connected", "bad": "failed", "off": "disabled"}
        r = await tk.execute("fast_slow", {"totalMs": 10, "intervalMs": 0})
        assert not r.is_error, r.output
    finally:
        try:
            await src.close()
        except Exception:  # noqa: BLE001 — pre-existing SDK teardown race
            pass


@needs_node
async def test_load_mcp_on_server_cancel_aborts_and_closes():
    cancel = asyncio.Event()
    seen: list[str] = []
    cfg = {"mcp": {"slow": {"command": ["sh", "-c", f"sleep 3; exec {NODE} {FIXTURE}"], "timeout": 5000}}}
    asyncio.get_running_loop().call_later(0.2, cancel.set)
    from toolnexus import load_mcp_with_context

    t0 = time.monotonic()
    with pytest.raises(asyncio.CancelledError):
        await load_mcp_with_context(cfg, cancel=cancel, on_server=lambda r: seen.append(r.name))
    assert time.monotonic() - t0 < 2.5 and seen == []


# ---- wait_for_ctx ----------------------------------------------------------

class _BalanceLLM:
    """openai style: a user turn calls get_balance; after the tool result, answer."""

    def post(self, url, headers, payload, timeout):
        last = payload["messages"][-1]
        if last.get("role") == "tool":
            return {"choices": [{"message": {"role": "assistant", "content": "balance is 42"}}]}
        return {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
            {"id": "b1", "type": "function", "function": {"name": "get_balance", "arguments": "{}"}}]}}]}


def _balance_toolkit_tools(authed: dict) -> list[Tool]:
    async def get_balance(args=None, ctx=None):
        if ctx is not None and ctx.answer is not None:
            authed["v"] = True
        if not authed["v"]:
            return pending(kind="authorization", prompt="log in", url="https://login.example")
        return ToolResult(output="42", is_error=False)

    return [_tool("get_balance", get_balance)]


def _client(**kw):
    return create_client(base_url="http://stub.local", style="openai", model="stub", api_key="k",
                         http_transport=kw.pop("transport", _BalanceLLM()), **kw)


async def test_wait_for_ctx_cancels_with_turn():
    authed = {"v": False}
    tk = await create_toolkit(builtins=False, extra_tools=_balance_toolkit_tools(authed))
    cancel = asyncio.Event()
    got: dict[str, Any] = {}

    def wait_for(req):
        raise AssertionError("wait_for must not run when wait_for_ctx is set")

    async def wait_for_ctx(ctx, req):
        asyncio.get_running_loop().call_later(0.05, cancel.set)
        try:
            await asyncio.wait_for(ctx.wait(), timeout=5)
            got["seen"] = "cancelled"
        except asyncio.CancelledError:
            got["seen"] = "task-cancelled"
            raise
        return Answer(id=req.id, ok=True)

    c = _client(wait_for=wait_for, wait_for_ctx=wait_for_ctx)
    t0 = time.monotonic()
    with pytest.raises(RunCancelled):
        await c.run("what is my balance?", tk, cancel=cancel)
    assert got["seen"] in ("cancelled", "task-cancelled")
    assert time.monotonic() - t0 < 2


async def test_wait_for_ctx_resolves():
    authed = {"v": False}
    tk = await create_toolkit(builtins=False, extra_tools=_balance_toolkit_tools(authed))

    async def wait_for_ctx(ctx, req):
        assert isinstance(ctx, asyncio.Event)
        return Answer(id=req.id, ok=True)

    res = await _client(wait_for_ctx=wait_for_ctx).run("what is my balance?", tk)
    assert res.status == "done" and res.text == "balance is 42"


# ---- hints -----------------------------------------------------------------

def test_builtin_hints():
    want = {"read": True, "grep": True, "glob": True, "webfetch": True, "question": True, "todowrite": True,
            "bash": False, "write": False, "edit": False, "apply_patch": False}
    tools = create_builtin_tools()
    assert sorted(t.name for t in tools) == sorted(want)
    for t in tools:
        assert t.hints is not None, t.name
        assert t.hints.read_only == want[t.name] and t.hints.destructive != want[t.name], (t.name, t.hints)
    assert next(t for t in tools if t.name == "webfetch").hints.open_world
    assert next(t for t in tools if t.name == "bash").hints.open_world


def test_mcp_hints_mapping():
    ann = lambda ro=None, de=None, ow=None: SimpleNamespace(readOnlyHint=ro, destructiveHint=de, openWorldHint=ow)  # noqa: E731
    assert mcp_hints(None) is None
    assert mcp_hints(ann()) is None
    assert mcp_hints(ann(ro=True)) == ToolHints(read_only=True, destructive=False, open_world=True)
    assert mcp_hints(ann(de=False, ow=False)) == ToolHints(read_only=False, destructive=False, open_world=False)
    assert mcp_hints(ann(ro=False)) == ToolHints(read_only=False, destructive=True, open_world=True)


def test_hints_never_reach_the_wire():
    from toolnexus import to_anthropic, to_gemini, to_openai

    tools = create_builtin_tools()
    for out in (to_openai(tools), to_anthropic(tools), to_gemini(tools)):
        blob = json.dumps(out)
        assert "read_only" not in blob and "readOnly" not in blob and "open_world" not in blob


async def test_permission_gate_denies_mutating_tool():
    authed = {"v": True}
    base = _balance_toolkit_tools(authed)[0]
    base.hints = ToolHints(destructive=True)
    tk = await create_toolkit(builtins=False, extra_tools=[base])
    seen: dict[str, Any] = {}

    async def before_tool(ev):
        seen["hints"] = ev["hints"]
        if ev["hints"] is None or not ev["hints"].read_only:
            return {"result": ToolResult(output="denied: plan mode allows read-only tools only", is_error=True)}
        return None

    res = await _client(hooks={"before_tool": before_tool}).run("balance?", tk)
    assert res.status == "done"
    assert seen["hints"] is not None and seen["hints"].destructive
    assert len(res.tool_calls) == 1 and res.tool_calls[0]["is_error"]
    assert res.tool_calls[0]["output"] == "denied: plan mode allows read-only tools only"


# ---- call id ---------------------------------------------------------------

def _sse(events):
    return io.BytesIO("".join(f"data: {e if isinstance(e, str) else json.dumps(e)}\n\n" for e in events).encode())


class _TwoCalls:
    """Always answers with calls c1 (fast) + c2 (slow)."""

    def __init__(self, style):
        self.style = style

    def post(self, url, headers, payload, timeout):
        if self.style == "openai":
            return {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "fast", "arguments": "{}"}},
                {"id": "c2", "type": "function", "function": {"name": "slow", "arguments": "{}"}}]}}]}
        return {"content": [{"type": "tool_use", "id": "c1", "name": "fast", "input": {}},
                            {"type": "tool_use", "id": "c2", "name": "slow", "input": {}}], "stop_reason": "tool_use"}

    def open(self, url, headers, payload, timeout):  # noqa: A003
        if self.style == "openai":
            return _sse([{"choices": [{"delta": {"tool_calls": [
                {"index": 0, "id": "c1", "function": {"name": "fast", "arguments": "{}"}},
                {"index": 1, "id": "c2", "function": {"name": "slow", "arguments": "{}"}}]}}]}, "[DONE]"])
        return _sse([
            {"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "c1", "name": "fast"}},
            {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{}"}},
            {"type": "content_block_start", "index": 1, "content_block": {"type": "tool_use", "id": "c2", "name": "slow"}},
            {"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "{}"}},
            {"type": "message_delta", "delta": {"stop_reason": "tool_use"}},
        ])


@pytest.mark.parametrize("stream", [False, True])
@pytest.mark.parametrize("style", ["openai", "anthropic"])
async def test_tool_context_call_id_on_parallel_calls(style, stream):
    seen: dict[str, Any] = {}

    def mk(name, delay):
        async def run(args=None, ctx=None):
            await asyncio.sleep(delay)
            seen[name] = ctx.call_id
            return ToolResult(output="ok", is_error=False)

        return _tool(name, run)

    tk = await create_toolkit(builtins=False, extra_tools=[mk("fast", 0), mk("slow", 0.02)])
    c = create_client(base_url="http://stub.local", style=style, model="m", api_key="k",
                      max_turns=1, http_transport=_TwoCalls(style))
    if stream:
        async for _ in c.stream("go", tk):
            pass
    else:
        await c.run("go", tk)
    assert seen == {"fast": "c1", "slow": "c2"}


async def test_call_id_absent_outside_the_loop():
    seen = {}

    async def run(args=None, ctx=None):
        seen["ctx"] = ctx
        return ToolResult(output="ok", is_error=False)

    tk = await create_toolkit(builtins=False, extra_tools=[_tool("t", run)])
    await tk.execute("t")
    assert seen["ctx"] is None


def test_define_tool_has_no_hints_by_default():
    async def fn(args):  # pragma: no cover - not run
        return "x"

    t = define_tool(fn, name="x", description="d", input_schema=SCHEMA)
    assert t.hints is None
