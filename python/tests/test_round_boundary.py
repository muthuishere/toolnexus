"""SPEC §8 ``after_round`` (change add-round-boundary-hook, #192).

Shared fixture: ``examples/round-boundary/fixture.json`` (R1-R6). Hermetic: a
scripted in-process HTTP transport (run = ``post``, stream = ``open`` SSE).
"""
from __future__ import annotations

import copy
import io
import json
from typing import Any

import pytest

from toolnexus import create_client, create_toolkit
from toolnexus.agents import AgentRuntime
from toolnexus.types import Tool, ToolResult

from _agent_mocks import registry


async def _echo(args=None, ctx=None):
    return ToolResult(output=str((args or {}).get("text")), is_error=False)


ECHO = Tool(name="echo", description="echo", input_schema={"type": "object", "properties": {"text": {"type": "string"}}}, source="native", execute=_echo)


def _toolkit():
    return create_toolkit(builtins=False, extra_tools=[ECHO])


def say(content: str) -> dict[str, Any]:
    return {"role": "assistant", "content": content}


CALL_ECHO = {"role": "assistant", "content": None, "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "echo", "arguments": json.dumps({"text": "hi"})}}]}


def _sse(chunks: list[Any]) -> bytes:
    return "".join(f"data: {c if isinstance(c, str) else json.dumps(c)}\n\n" for c in chunks).encode()


class Scripted:
    """OpenAI or Anthropic scripted LLM recording every request body."""

    def __init__(self, replies: list[dict[str, Any]], style: str = "openai") -> None:
        self.replies, self.style, self.sent, self.i = replies, style, [], 0

    def _next(self, payload):
        self.sent.append(copy.deepcopy(payload))
        m = self.replies[min(self.i, len(self.replies) - 1)]
        self.i += 1
        return m

    def post(self, url, headers, payload, timeout):
        m = self._next(payload)
        if self.style == "anthropic":
            return {"content": [{"type": "text", "text": m["content"]}], "stop_reason": "end_turn", "usage": {"input_tokens": 1, "output_tokens": 1}}
        return {"choices": [{"index": 0, "message": m, "finish_reason": "tool_calls" if m.get("tool_calls") else "stop"}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}

    def open(self, url, headers, payload, timeout):  # noqa: A003
        m = self._next(payload)
        if m.get("tool_calls"):
            c = m["tool_calls"][0]
            chunks = [{"choices": [{"index": 0, "delta": {"tool_calls": [{"index": 0, "id": c["id"], "type": "function", "function": c["function"]}]}}]},
                      {"choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]}]
        else:
            chunks = [{"choices": [{"index": 0, "delta": {"content": m["content"]}}]},
                      {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}]
        return io.BytesIO(_sse([*chunks, "[DONE]"]))


def _client(t: Scripted, hooks: Any, max_turns: int = 10, style: str = "openai"):
    return create_client(base_url="http://scripted.invalid", style=style, model="m", api_key="unused",
                         http_transport=t, hooks=hooks, max_turns=max_turns)


async def _go(mode: str, c, kit):
    if mode == "run":
        return await c.run("go", toolkit=kit)
    res = None
    events = []
    async for ev in c.stream("go", toolkit=kit):
        events.append(ev["type"])
        if ev["type"] == "done":
            res = ev["result"]
    res._events = events  # type: ignore[attr-defined]
    return res


def _tail(body, n):
    return [{"role": m["role"], "content": m["content"]} for m in body["messages"][-n:]]


def _rec(calls):
    def hook_factory(rule):
        def h(ev):
            calls.append({"turn": ev["turn"], "final": ev["final"], "text": ev["text"]})
            return rule(ev)
        return h
    return hook_factory


def _text(r):
    return r.text if hasattr(r, "text") else r["text"]


def _status(r):
    return r.status if hasattr(r, "status") else r["status"]


MODES = ["run", "stream"]


@pytest.mark.parametrize("mode", MODES)
async def test_r1_inject_after_tool_results(mode):
    t = Scripted([CALL_ECHO, say("done")])
    calls: list = []
    h = _rec(calls)(lambda ev: None if ev["final"] else {"inject": ["todo: 2 open"]})
    r = await _go(mode, _client(t, {"after_round": h}), await _toolkit())
    assert len(t.sent) == 2
    assert _text(r) == "done"
    assert _tail(t.sent[1], 2) == [{"role": "tool", "content": "hi"}, {"role": "user", "content": "todo: 2 open"}]
    assert calls == [{"turn": 0, "final": False, "text": ""}, {"turn": 1, "final": True, "text": "done"}]


@pytest.mark.parametrize("mode", MODES)
async def test_r2_continue_when_model_stops(mode):
    t = Scripted([say("first"), say("second")])
    calls: list = []
    h = _rec(calls)(lambda ev: {"inject": ["keep going"]} if ev["final"] and ev["turn"] == 0 else None)
    r = await _go(mode, _client(t, {"after_round": h}), await _toolkit())
    assert len(t.sent) == 2
    assert _text(r) == "second"
    assert _tail(t.sent[1], 2) == [{"role": "assistant", "content": "first"}, {"role": "user", "content": "keep going"}]
    assert calls == [{"turn": 0, "final": True, "text": "first"}, {"turn": 1, "final": True, "text": "second"}]
    if mode == "stream":
        assert r._events.count("usage") == 1 and r._events.count("done") == 1


@pytest.mark.parametrize("mode", MODES)
async def test_r3_empty_injection_ends_normally(mode):
    t = Scripted([say("only")])
    calls: list = []
    h = _rec(calls)(lambda ev: {"inject": [""]})
    r = await _go(mode, _client(t, {"after_round": h}), await _toolkit())
    assert len(t.sent) == 1
    assert _text(r) == "only" and _status(r) == "done"
    assert calls == [{"turn": 0, "final": True, "text": "only"}]


@pytest.mark.parametrize("mode", MODES)
async def test_r4_no_continuation_past_max_turns(mode):
    t = Scripted([say("only")])
    calls: list = []
    h = _rec(calls)(lambda ev: {"inject": ["again"]})
    r = await _go(mode, _client(t, {"after_round": h}, max_turns=1), await _toolkit())
    assert len(t.sent) == 1
    assert _text(r) == "only"
    assert calls == []


@pytest.mark.parametrize("mode", MODES)
async def test_r5_failing_hook_stops_the_run(mode):
    t = Scripted([CALL_ECHO, say("never")])

    def boom(ev):
        raise RuntimeError("round-boom")

    with pytest.raises(Exception, match="round-boom"):
        await _go(mode, _client(t, {"after_round": boom}), await _toolkit())
    assert len(t.sent) == 1


async def test_r2_anthropic_run():
    t = Scripted([say("first"), say("second")], style="anthropic")
    h = lambda ev: {"inject": ["keep going"]} if ev["final"] and ev["turn"] == 0 else None  # noqa: E731
    r = await _client(t, {"after_round": h}, style="anthropic").run("go", toolkit=await _toolkit())
    assert len(t.sent) == 2
    assert r.text == "second"
    tail = t.sent[1]["messages"][-2:]
    assert tail[0]["role"] == "assistant" and tail[0]["content"][0]["text"] == "first"
    assert tail[1] == {"role": "user", "content": "keep going"}


@pytest.mark.parametrize("mode", MODES)
async def test_absent_hook_is_byte_identical(mode):
    a = Scripted([CALL_ECHO, say("done")])
    b = Scripted([CALL_ECHO, say("done")])
    ra = await _go(mode, _client(a, None), await _toolkit())
    rb = await _go(mode, _client(b, {"after_round": lambda ev: None}), await _toolkit())
    assert a.sent == b.sent
    assert ra.messages == rb.messages


class _AgentScripted:
    """§7D transport: first reply 'first', then 'second'."""

    def __init__(self) -> None:
        self.sent: list = []

    def post(self, url, headers, payload, timeout):
        self.sent.append(copy.deepcopy(payload))
        content = "first" if len(self.sent) == 1 else "second"
        return {"choices": [{"message": {"role": "assistant", "content": content}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}

    def open(self, url, headers, payload, timeout):  # noqa: A003
        raise NotImplementedError


async def test_r6_agent_path_forwards_after_round():
    t = _AgentScripted()
    h = lambda ev: {"inject": ["keep going"]} if ev["final"] and ev["turn"] == 0 else None  # noqa: E731
    rt = AgentRuntime(transport=t, registry=registry({"peer": {"model": "m-a"}}), hooks={"after_round": h})
    hd = rt.spawn(rt.root, "peer")
    r = await rt.run_turn(hd, "hello")
    assert not r.is_error, r.text
    assert len(t.sent) == 2
    assert r.text == "second"
    await rt.close(rt.root)
