"""Harness, loop and the completion gate (openspec/changes/add-harness-and-loop).

Hermetic — a scripted transport stands in for the LLM, so no network and no key.
These mirror ``golang/agents/loop_test.go`` and ``js/test/loop.test.ts`` case for
case: the point of the change is that seven ports agree, and a test that exists in
one port only is how that stops being true.
"""
from __future__ import annotations

import json

import pytest

from toolnexus import create_toolkit
from toolnexus.agents import Completion, Verdict, agent, all_todos_done, guarded_hooks, harness


class Scripted:
    """A transport that replays scripted assistant messages and records requests."""

    def __init__(self, messages):
        self.messages = messages
        self.sent = []
        self._i = 0

    def post(self, url, headers, payload, timeout):
        self.sent.append(payload)
        message = self.messages[min(self._i, len(self.messages) - 1)]
        self._i += 1
        return {
            "choices": [{
                "index": 0,
                "message": message,
                "finish_reason": "tool_calls" if "tool_calls" in message else "stop",
            }],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }

    def open(self, url, headers, payload, timeout):  # noqa: A003
        raise NotImplementedError


def say(content):
    return {"role": "assistant", "content": content}


def call_todo(todos):
    return {
        "role": "assistant",
        "tool_calls": [{
            "id": "t1", "type": "function",
            "function": {"name": "todowrite", "arguments": json.dumps({"todos": todos})},
        }],
    }


def base_opts(transport):
    return {
        "base_url": "http://scripted.invalid", "style": "openai",
        "model": "test-model", "api_key": "unused", "http_transport": transport,
    }


async def todo_toolkit():
    return await create_toolkit(builtins={"tools": {
        "todowrite": True, "bash": False, "read": False, "write": False, "edit": False,
        "glob": False, "grep": False, "webfetch": False, "apply_patch": False, "question": False,
    }})


def test_harness_is_the_spec():
    spec = harness(does="x", soul="y")
    assert spec == {"does": "x", "soul": "y"}, "harness is a name, not a wrapper"


@pytest.mark.asyncio
async def test_absent_options_unchanged():
    tk = await create_toolkit(builtins=False)
    a = agent("plain", does="answers")
    out = await a.loop(base_opts(Scripted([say("hello")])), tk).run("hi")
    assert out.status == "done"
    assert out.text == "hello"
    assert out.attempts == 1
    assert out.stopped_by == "", "a done run names no stop reason"


@pytest.mark.asyncio
async def test_gate_blocks_then_passes():
    # Attempt 1 must END with an open item: the client loops on tool calls, so a
    # closing todowrite in the same run would be judged and pass with no retry.
    transport = Scripted([
        call_todo([{"id": "1", "text": "draft", "completed": True},
                   {"id": "2", "text": "proofread", "completed": False}]),
        say("I think I am finished"),
        call_todo([{"id": "1", "text": "draft", "completed": True},
                   {"id": "2", "text": "proofread", "completed": True}]),
        say("all done"),
    ])
    tk = await todo_toolkit()
    a = agent("gated", does="plans", completion=Completion(verify=all_todos_done, max_attempts=3))
    out = await a.loop(base_opts(transport), tk).run("do the thing")
    assert out.status == "done"
    assert out.attempts >= 2, f"expected a retry, got {out.attempts}"


@pytest.mark.asyncio
async def test_unverifiable_run_stops_loudly():
    tk = await create_toolkit(builtins=False)
    a = agent("never", does="never verifies",
              completion=Completion(verify=lambda r: Verdict(False, "always red"), max_attempts=2))
    out = await a.loop(base_opts(Scripted([say("done!")])), tk).run("go")
    assert out.status == "incomplete", "never a silent done"
    assert out.attempts == 2, "bounded by max_attempts"
    assert "always red" in out.stopped_by, "the reason is named"
    assert out.result.limit == "completion", "structured, so a caller can tell WHICH limit"


@pytest.mark.asyncio
async def test_max_attempts_is_required():
    tk = await create_toolkit(builtins=False)
    a = agent("bad", does="x", completion=Completion(verify=lambda r: Verdict(True), max_attempts=0))
    with pytest.raises(ValueError, match="max_attempts"):
        await a.loop(base_opts(Scripted([say("hi")])), tk).run("go")


@pytest.mark.asyncio
async def test_no_plan_declared_passes():
    tk = await todo_toolkit()
    a = agent("noplan", does="x", completion=Completion(verify=all_todos_done, max_attempts=2))
    out = await a.loop(base_opts(Scripted([say("answered without a plan")])), tk).run("go")
    assert out.status == "done", "the gate must not punish an agent for not using the builtin"
    assert out.attempts == 1


@pytest.mark.asyncio
async def test_gate_judges_accumulated_work():
    # Attempt 1 declares an open item; attempt 2 declares no plan at all. Judging
    # only the latest attempt would see "no plan" and pass.
    transport = Scripted([
        call_todo([{"id": "1", "text": "ship it", "completed": False}]),
        say("I am finished, honest"),
    ])
    tk = await todo_toolkit()
    a = agent("escaper", does="x", completion=Completion(verify=all_todos_done, max_attempts=2))
    out = await a.loop(base_opts(transport), tk).run("go")
    assert out.status == "incomplete", "the earlier open plan must still be visible"
    assert "ship it" in out.stopped_by


@pytest.mark.asyncio
async def test_guardrails_first_deny_wins():
    seen = []

    def first(ev):
        return "policy: no" if ev.get("name") == "danger" else "allow"

    def second(ev):
        seen.append("second ran")
        return "allow"

    hooks = guarded_hooks([first, second], None)
    denied = await hooks["before_tool"]({"name": "danger", "args": {}, "turn": 1})
    assert denied["result"]["is_error"] is True
    assert "policy: no" in denied["result"]["output"]
    assert seen == [], "a later guardrail never runs after a denial"

    allowed = await hooks["before_tool"]({"name": "safe", "args": {}, "turn": 1})
    assert allowed is None, "an allowed call falls through"
    assert seen == ["second ran"]


@pytest.mark.asyncio
async def test_guardrails_run_before_an_existing_hook():
    calls = {"prior": 0}

    def prior(ev):
        calls["prior"] += 1
        return None

    hooks = guarded_hooks([lambda ev: "nope" if ev.get("name") == "danger" else "allow"],
                          {"before_tool": prior})
    await hooks["before_tool"]({"name": "danger", "args": {}, "turn": 1})
    assert calls["prior"] == 0, "denied => the prior hook is not reached"
    await hooks["before_tool"]({"name": "safe", "args": {}, "turn": 1})
    assert calls["prior"] == 1, "allowed => the prior hook runs"


def test_guardrails_survive_the_registry_projection():
    child = agent("child", does="does work", guardrails=[lambda ev: "denied by policy"])
    defn = child.registry()["child"]
    assert defn.hooks["before_tool"], "the compiled guardrail must be on the projected def"


def test_completion_is_projected_into_the_registry():
    child = agent("child", does="does work",
                  completion=Completion(verify=all_todos_done, max_attempts=2))
    defn = child.registry()["child"]
    assert defn.completion.max_attempts == 2, "the gate travels with the agent"


@pytest.mark.asyncio
async def test_per_call_model_override_reaches_the_wire():
    transport = Scripted([say("a"), say("b")])
    tk = await create_toolkit(builtins=False)
    loop = agent("m", does="x").loop(base_opts(transport), tk)
    await loop.run("one", model="override-model")
    await loop.run("two")
    assert transport.sent[0]["model"] == "override-model"
    assert transport.sent[1]["model"] == "test-model", "the override does not persist"


@pytest.mark.asyncio
async def test_turns_accumulate_and_status_is_observed():
    tk = await create_toolkit(builtins=False)
    loop = agent("t", does="x").loop(base_opts(Scripted([say("a"), say("b")])), tk)
    assert loop.status == "idle"
    await loop.run("one")
    after_first = loop.turns
    await loop.run("two")
    assert loop.turns > after_first, "turns accumulate across runs"
    assert loop.status == "idle"
