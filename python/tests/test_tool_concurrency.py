"""add-tool-concurrency (SPEC §8 "Tool scheduling"), ported from golang/tool_concurrency_test.go.

Hermetic: a local HTTP server answers turn 1 with one call per name and turn 2 with "done", in
all four wire modes (openai/anthropic × run/stream). Plus the §7D background-run recipe.
"""

from __future__ import annotations

import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from toolnexus import (
    Tool,
    ToolHints,
    ToolResult,
    create_client,
    create_toolkit,
    is_concurrency_safe,
)
from toolnexus.agents import AgentRuntime, Handle

from _agent_mocks import MockTransport, registry


def _server(names: list[str]) -> ThreadingHTTPServer:
    state = {"turn": 0}
    lock = threading.Lock()

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):  # noqa: D401 - silence
            pass

        def do_POST(self):  # noqa: N802
            body = json.loads(self.rfile.read(int(self.headers.get("content-length") or 0)) or b"{}")
            with lock:
                state["turn"] += 1
                first = state["turn"] == 1
            anth = "messages" in self.path
            stream = bool(body.get("stream"))
            if not stream:
                if anth:
                    resp = (
                        {"content": [{"type": "tool_use", "id": f"c{i+1}", "name": n, "input": {}} for i, n in enumerate(names)], "stop_reason": "tool_use"}
                        if first
                        else {"content": [{"type": "text", "text": "done"}], "stop_reason": "end_turn"}
                    )
                else:
                    msg = (
                        {"role": "assistant", "content": None, "tool_calls": [
                            {"id": f"c{i+1}", "type": "function", "function": {"name": n, "arguments": "{}"}} for i, n in enumerate(names)]}
                        if first
                        else {"role": "assistant", "content": "done"}
                    )
                    resp = {"choices": [{"message": msg}]}
                data = json.dumps(resp).encode()
                self.send_response(200)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            ev: list = []
            if not anth:
                if first:
                    ev += [{"choices": [{"delta": {"tool_calls": [{"index": i, "id": f"c{i+1}", "type": "function", "function": {"name": n, "arguments": "{}"}}]}}]} for i, n in enumerate(names)]
                    ev.append({"choices": [{"delta": {}, "finish_reason": "tool_calls"}]})
                else:
                    ev += [{"choices": [{"delta": {"content": "done"}}]}, {"choices": [{"delta": {}, "finish_reason": "stop"}]}]
            else:
                if first:
                    for i, n in enumerate(names):
                        ev.append({"type": "content_block_start", "index": i, "content_block": {"type": "tool_use", "id": f"c{i+1}", "name": n}})
                        ev.append({"type": "content_block_delta", "index": i, "delta": {"type": "input_json_delta", "partial_json": "{}"}})
                        ev.append({"type": "content_block_stop", "index": i})
                    ev.append({"type": "message_delta", "delta": {"stop_reason": "tool_use"}})
                else:
                    ev.append({"type": "content_block_start", "index": 0, "content_block": {"type": "text"}})
                    ev.append({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "done"}})
                    ev.append({"type": "message_delta", "delta": {"stop_reason": "end_turn"}})
                ev.append({"type": "message_stop"})
            out = "".join(f"data: {json.dumps(e)}\n\n" for e in ev) + ("" if anth else "data: [DONE]\n\n")
            data = out.encode()
            self.send_response(200)
            self.send_header("content-type", "text/event-stream")
            self.send_header("content-length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


class Tracker:
    def __init__(self) -> None:
        self.inflight = 0
        self.peak = 0
        self.log: list[str] = []

    def tool(self, name: str, read_only: bool, secs: float = 0.06) -> Tool:
        async def execute(args, ctx=None):
            self.inflight += 1
            self.peak = max(self.peak, self.inflight)
            self.log.append(f"start {name}")
            await asyncio.sleep(secs)
            self.inflight -= 1
            self.log.append(f"end {name}")
            return ToolResult(output=f"out {name}", is_error=False)

        return Tool(
            name=name,
            description=name,
            input_schema={"type": "object", "properties": {}},
            source="native",
            execute=execute,
            hints=ToolHints(read_only=True) if read_only else None,
        )


MODES = [("openai", False), ("anthropic", False), ("openai", True), ("anthropic", True)]


async def _run(
    style: str, stream: bool, names: list[str], tools: list[Tool], cap: int | None = None, field: str = "name"
) -> list[str]:
    srv = _server(names)
    tk = await create_toolkit(builtins=False, extra_tools=tools)
    try:
        base = f"http://127.0.0.1:{srv.server_address[1]}" + ("/v1" if style == "openai" else "")
        client = create_client(base_url=base, style=style, model="m", api_key="k", max_tool_concurrency=cap)
        if not stream:
            r = await client.run("go", toolkit=tk)
            return [c[field] if isinstance(c, dict) else getattr(c, field) for c in r.tool_calls]
        got: list[str] = []
        async for ev in client.stream("go", toolkit=tk):
            if ev["type"] == "done":
                got = [c[field] if isinstance(c, dict) else getattr(c, field) for c in ev["result"].tool_calls]
        return got
    finally:
        await tk.close()
        srv.shutdown()


def _reads(tr: Tracker, n: int):
    names = [f"r{i+1}" for i in range(n)]
    return names, [tr.tool(x, True) for x in names]


@pytest.mark.parametrize("style,stream", MODES)
async def test_unsafe_calls_serialize(style, stream):
    tr = Tracker()
    got = await _run(style, stream, ["w1", "w2"], [tr.tool("w1", False), tr.tool("w2", False)])
    assert tr.peak == 1
    assert tr.log == ["start w1", "end w1", "start w2", "end w2"]
    assert got == ["w1", "w2"]


@pytest.mark.parametrize("style,stream", MODES)
async def test_read_only_calls_overlap(style, stream):
    tr = Tracker()
    names, tools = _reads(tr, 6)
    got = await _run(style, stream, names, tools)
    assert tr.peak == 6
    assert got == names


@pytest.mark.parametrize("style,stream", MODES)
async def test_results_keep_emitted_order_when_calls_finish_out_of_order(style, stream):
    tr = Tracker()
    names = ["r1", "r2", "r3", "r4"]
    # r1 is slowest, r4 fastest: completion order is the reverse of emitted order
    tools = [tr.tool(x, True, 0.02 * (len(names) - i)) for i, x in enumerate(names)]
    outputs = await _run(style, stream, names, tools, field="output")
    assert [e for e in tr.log if e.startswith("end ")] == ["end r4", "end r3", "end r2", "end r1"]
    # each call keeps its own output, in emitted order
    assert outputs == [f"out {x}" for x in names]


@pytest.mark.parametrize("style,stream", MODES)
async def test_cap_is_honoured(style, stream):
    tr = Tracker()
    names, tools = _reads(tr, 6)
    await _run(style, stream, names, tools, cap=2)
    assert tr.peak == 2


@pytest.mark.parametrize("style,stream", MODES)
async def test_mixed_runs_keep_order(style, stream):
    tr = Tracker()
    names = ["r1", "r2", "w", "r3", "r4"]
    tools = [tr.tool("r1", True), tr.tool("r2", True), tr.tool("w", False), tr.tool("r3", True), tr.tool("r4", True)]
    got = await _run(style, stream, names, tools)
    pos = tr.log.index
    for r in ("r1", "r2"):
        assert pos(f"end {r}") < pos("start w"), tr.log
    for r in ("r3", "r4"):
        assert pos(f"start {r}") > pos("end w"), tr.log
    assert tr.peak == 2
    assert got == names


async def test_env_override_and_fallback(monkeypatch):
    async def peak(env: str, cap: int | None = None) -> int:
        monkeypatch.setenv("TOOLNEXUS_MAX_TOOL_CONCURRENCY", env)
        tr = Tracker()
        names, tools = _reads(tr, 6)
        await _run("openai", False, names, tools, cap)
        return tr.peak

    assert await peak("3") == 3
    assert await peak("3", 4) == 4
    for bad in ("0", "-2", "abc", ""):
        assert await peak(bad) == 6, bad


async def test_safety_rule():
    tk = await create_toolkit()
    try:
        want = {"read": True, "grep": True, "glob": True, "webfetch": True,
                "bash": False, "write": False, "edit": False, "apply_patch": False}
        for name, safe in want.items():
            t = tk.get(name)
            assert t is not None, name
            assert is_concurrency_safe(t, {}) is safe, name
    finally:
        await tk.close()

    async def nop(args, ctx=None):
        return ToolResult(output="", is_error=False)

    assert not is_concurrency_safe(Tool(name="x", description="", input_schema={}, source="native", execute=nop), {})
    own = Tool(name="y", description="", input_schema={}, source="native", execute=nop,
               hints=ToolHints(read_only=True), concurrency_safe=lambda a: a.get("dry") is True)
    assert not is_concurrency_safe(own, {})
    assert is_concurrency_safe(own, {"dry": True})


async def test_background_run_recipe():
    """SPEC §7D "Background runs": spawn + wake return at once with the id; a background wait
    delivers the completion to the host's callback. No new API; this pins the recipe."""
    rt = AgentRuntime(transport=MockTransport(), registry=registry())
    h = rt.spawn(rt.root, "coordinator")
    assert isinstance(h, Handle)
    rt.wake(h, "answer using two lookups")
    assert h.id, "the handle id is available at once"
    done: asyncio.Future = asyncio.get_running_loop().create_future()

    async def background() -> None:
        r = await rt.wait(h)
        done.set_result((h.id, r))  # on_done(id, r)

    asyncio.ensure_future(background())
    hid, r = await asyncio.wait_for(done, 5)
    assert hid == h.id
    assert r.status == "done"
    assert "found:" in r.text
