"""add-go-host-asks (SPEC §4C), agents side: compactor on_compact + CompactInfo and
the compactor fixes; as_tool cancellation with the calling turn; as_tool_with under
the parent's gate and ask bridge. Mirrors golang/agents/compaction_test.go
(TestCompactor*) and golang/agents/astool_ctx_test.go."""
from __future__ import annotations

import asyncio
import threading
import time

from toolnexus import Answer, Tool, ToolContext, ToolHints, ToolResult, pending
from toolnexus.agents import CompactInfo, agent, compactor, estimate_tokens

from _agent_mocks import openai_body, tool_call


def _transcript(n: int) -> list[dict]:
    m: list[dict] = [{"role": "system", "content": "SOUL"}]
    for i in range(n):
        m.append({"role": "user", "content": f"question {i} " + "pad " * 10})
        m.append({"role": "assistant", "content": f"answer {i} " + "pad " * 10})
    return m


def _summarize(older):
    return f"summarized {len(older)} messages"


async def test_compactor_on_compact():
    got: list[CompactInfo] = []
    hook = compactor(max_tokens=50, keep_tail=20, summarize=_summarize, on_compact=got.append)
    msgs = _transcript(10)
    out = await hook({"messages": msgs})
    assert out is not None and len(got) == 1
    i = got[0]
    assert i.messages_before == len(msgs) and i.messages_after == len(out["messages"])
    assert i.summarized > 0 and i.summary
    assert i.tokens_before == estimate_tokens(msgs) and i.tokens_after == estimate_tokens(out["messages"])
    assert i.tokens_after < i.tokens_before

    def boom(_):
        raise AssertionError("on_compact on a no-op")

    big = compactor(max_tokens=1 << 30, summarize=_summarize, on_compact=boom)
    assert await big({"messages": msgs}) is None


async def test_compactor_keeps_sole_user_prompt():
    data = "data " * 40
    msgs: list[dict] = [{"role": "system", "content": "SOUL"}, {"role": "user", "content": "do the task"}]
    for i in range(8):
        msgs.append({"role": "assistant", "content": None, "tool_calls": [
            {"id": f"c{i}", "type": "function", "function": {"name": "lookup", "arguments": "{}"}}]})
        msgs.append({"role": "tool", "tool_call_id": f"c{i}", "content": data})
    info: list[CompactInfo] = []

    async def on_compact(i):  # async callbacks are awaited
        info.append(i)

    hook = compactor(max_tokens=100, keep_tail=20, summarize=_summarize, on_compact=on_compact)
    out = await hook({"messages": msgs})
    assert out is not None
    assert len(out["messages"]) == 3 and out["messages"][1]["content"] == "do the task"
    assert len(info[0].folded) == len(msgs) - 2


async def test_compactor_no_op_when_not_smaller():
    msgs = _transcript(2)

    def boom(_):
        raise AssertionError("on_compact on a no-op")

    hook = compactor(max_tokens=10, keep_tail=5, summarize=lambda older: "long summary " * 500, on_compact=boom)
    assert await hook({"messages": msgs}) is None


async def test_compactor_folded_is_verbatim():
    msgs = _transcript(10)
    info: list[CompactInfo] = []
    hook = compactor(max_tokens=50, keep_tail=20, summarize=_summarize, on_compact=info.append)
    assert await hook({"messages": msgs}) is not None
    f = info[0].folded
    assert f and len(f) == info[0].summarized
    assert f == msgs[1 : 1 + len(f)]


# ---- agents as tools ---------------------------------------------------------

class _Blocking:
    """An LLM that never answers (until released at teardown)."""

    def __init__(self):
        self.release = threading.Event()

    def post(self, url, headers, payload, timeout):
        self.release.wait(10)
        raise TimeoutError("released")


async def test_as_tool_cancelled_with_calling_turn():
    llm = _Blocking()
    try:
        child = agent("child", does="d", soul="s", model="m")
        tool = child.as_tool(transport=llm)
        cancel = asyncio.Event()
        asyncio.get_running_loop().call_later(0.1, cancel.set)
        t0 = time.monotonic()
        r = await tool.execute({"prompt": "go"}, ToolContext(signal=cancel))
        assert time.monotonic() - t0 < 3
        assert r.is_error and r.output.startswith("cancelled"), r
    finally:
        llm.release.set()


class _ChildScript:
    """First call asks for the "danger" tool, then answers."""

    def __init__(self):
        self.n = 0

    def post(self, url, headers, payload, timeout):
        self.n += 1
        if self.n == 1:
            return openai_body(tool_call("danger", {}, "d1"))
        return openai_body({"content": "child done"})


async def test_as_tool_with_gate_and_ask_bridge():
    ran = {"v": False}

    async def danger(args=None, ctx=None):
        ran["v"] = True
        return ToolResult(output="did it", is_error=False)

    tool_def = Tool(name="danger", description="mutates", input_schema={"type": "object", "properties": {}},
                    source="native", execute=danger, hints=ToolHints(destructive=True))
    child = agent("child", does="d", soul="s", model="m", uses={"tools": [tool_def]})
    gated: list[str] = []
    asked = {"n": 0}

    async def before_tool(ev):
        gated.append(ev["name"])
        if ev["hints"] is None or not ev["hints"].read_only:
            return {"result": pending(kind="approval", prompt=f"allow {ev['name']}?")}
        return None

    def wait_for(req):
        asked["n"] += 1
        return Answer(id=req.id, ok=True)

    tool = child.as_tool_with(lambda ctx: {
        "transport": _ChildScript(),
        "hooks": {"before_tool": before_tool},
        "wait_for": wait_for,
    })
    r = await tool.execute({"prompt": "go"}, ToolContext())
    assert not r.is_error and r.output == "child done", r
    assert gated == ["danger"] and asked["n"] == 1 and ran["v"]
