"""SPEC §8 beforeLLM contract, every entry point (run, stream, agent run, translate),
both styles:

* a ``before_llm`` hook that fails STOPS the call — the error propagates and no
  provider request is sent;
* a ``model`` override is transmitted for that turn only (absent ⇒ configured), and
  the model REPORTED is the model transmitted: the turn's ``llm`` metric event, the
  ``run`` event + ``RunResult.model`` (last model call), ``translate``'s ``result.model``.
"""
from __future__ import annotations

import io
import json
from typing import Any

import pytest

from toolnexus import create_client
from toolnexus.agents import AgentRuntime, Handle

from _agent_mocks import registry


class Boom(Exception):
    pass


def _openai_json(turn: int) -> dict[str, Any]:
    if turn == 0:
        return {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
            {"id": "c1", "type": "function", "function": {"name": "nope", "arguments": "{}"}}]}}]}
    return {"choices": [{"message": {"role": "assistant", "content": "done"}}]}


def _anthropic_json(turn: int) -> dict[str, Any]:
    if turn == 0:
        return {"content": [{"type": "tool_use", "id": "c1", "name": "nope", "input": {}}], "stop_reason": "tool_use"}
    return {"content": [{"type": "text", "text": "done"}], "stop_reason": "end_turn"}


def _sse(events: list[Any]) -> bytes:
    return "".join(f"data: {e if isinstance(e, str) else json.dumps(e)}\n\n" for e in events).encode()


def _openai_sse(turn: int) -> bytes:
    if turn == 0:
        return _sse([{"choices": [{"delta": {"tool_calls": [
            {"index": 0, "id": "c1", "function": {"name": "nope", "arguments": "{}"}}]}}]}, "[DONE]"])
    return _sse([{"choices": [{"delta": {"content": "done"}}]}, "[DONE]"])


def _anthropic_sse(turn: int) -> bytes:
    if turn == 0:
        return _sse([
            {"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "c1", "name": "nope"}},
            {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{}"}},
            {"type": "message_delta", "delta": {"stop_reason": "tool_use"}},
        ])
    return _sse([
        {"type": "content_block_start", "index": 0, "content_block": {"type": "text"}},
        {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "done"}},
        {"type": "message_delta", "delta": {"stop_reason": "end_turn"}},
    ])


class Recorder:
    """Scripted transport: turn 0 = one tool call, turn 1 = final text. Records bodies."""

    def __init__(self, style: str) -> None:
        self.style = style
        self.bodies: list[dict[str, Any]] = []

    def post(self, url, headers, payload, timeout):
        self.bodies.append(json.loads(json.dumps(payload)))
        n = len(self.bodies) - 1
        return (_openai_json if self.style == "openai" else _anthropic_json)(n)

    def open(self, url, headers, payload, timeout):  # noqa: A003
        self.bodies.append(json.loads(json.dumps(payload)))
        n = len(self.bodies) - 1
        return io.BytesIO((_openai_sse if self.style == "openai" else _anthropic_sse)(n))


def _client(style: str, hooks: dict[str, Any], events: list[dict[str, Any]] | None = None):
    rec = Recorder(style)
    c = create_client(base_url="http://x/v1", style=style, model="configured", api_key="k",
                      hooks=hooks, http_transport=rec, max_turns=4,
                      on_metric=(events.append if events is not None else None))
    return c, rec


async def _drive(c, mode: str):
    if mode == "run":
        return await c.run("go")
    result = None
    async for ev in c.stream("go"):
        if ev["type"] == "done":
            result = ev["result"]
    return result


STYLES = ["openai", "anthropic"]
MODES = ["run", "stream"]


def _raise(ev):
    raise Boom("hook failed")


async def _araise(ev):
    raise Boom("async hook failed")


# --------------------------------------------------------------------------- #
# 1 — a failing before_llm stops the call: error propagates, zero requests.
# --------------------------------------------------------------------------- #
@pytest.mark.asyncio
@pytest.mark.parametrize("style", STYLES)
@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize("hook", [_raise, _araise], ids=["sync", "async"])
async def test_failing_before_llm_stops_the_loop(style, mode, hook):
    c, rec = _client(style, {"before_llm": hook})
    with pytest.raises(Boom):
        await _drive(c, mode)
    assert rec.bodies == []


@pytest.mark.asyncio
@pytest.mark.parametrize("style", STYLES)
async def test_failing_before_llm_stops_translate(style):
    c, rec = _client(style, {"before_llm": _raise})
    with pytest.raises(Boom):
        await c.translate([{"role": "user", "content": "go"}])
    assert rec.bodies == []


@pytest.mark.asyncio
async def test_failing_before_llm_fails_the_agent_run():
    posts: list[Any] = []

    class T:
        def post(self, *a):
            posts.append(a)
            raise AssertionError("no provider request may be sent")

        def open(self, *a):  # noqa: A003
            posts.append(a)
            raise AssertionError("no provider request may be sent")

    rt = AgentRuntime(transport=T(), registry=registry({"peer": {"model": "m-a"}}), hooks={"before_llm": _raise})
    h = rt.spawn(rt.root, "peer")
    assert isinstance(h, Handle)
    r = await rt.run_turn(h, "hello")
    assert r.is_error and r.status == "error", r
    assert "hook failed" in r.text
    assert posts == []
    await rt.close(rt.root)


# --------------------------------------------------------------------------- #
# 2 — model override: in the body for that turn only, absent ⇒ configured; the
#     reported model is the transmitted one.
# --------------------------------------------------------------------------- #
@pytest.mark.asyncio
@pytest.mark.parametrize("style", STYLES)
@pytest.mark.parametrize("mode", MODES)
async def test_model_override_first_turn_only(style, mode):
    events: list[dict[str, Any]] = []
    c, rec = _client(style, {"before_llm": lambda ev: {"model": "small-fast"} if ev["turn"] == 0 else None}, events)
    r = await _drive(c, mode)
    assert [b["model"] for b in rec.bodies] == ["small-fast", "configured"]
    assert [e["model"] for e in events if e["event"] == "llm"] == ["small-fast", "configured"]
    # last call made was not overridden ⇒ configured
    assert r.model == "configured"
    assert [e["model"] for e in events if e["event"] == "run"] == ["configured"]


@pytest.mark.asyncio
@pytest.mark.parametrize("style", STYLES)
@pytest.mark.parametrize("mode", MODES)
async def test_model_override_last_call_is_reported(style, mode):
    events: list[dict[str, Any]] = []
    c, rec = _client(style, {"before_llm": lambda ev: {"model": "big"} if ev["turn"] == 1 else None}, events)
    r = await _drive(c, mode)
    assert [b["model"] for b in rec.bodies] == ["configured", "big"]
    assert [e["model"] for e in events if e["event"] == "llm"] == ["configured", "big"]
    assert r.model == "big"
    assert [e["model"] for e in events if e["event"] == "run"] == ["big"]


@pytest.mark.asyncio
@pytest.mark.parametrize("style", STYLES)
@pytest.mark.parametrize("mode", MODES)
async def test_no_override_is_configured(style, mode):
    events: list[dict[str, Any]] = []
    c, rec = _client(style, {"before_llm": lambda ev: {"model": ""}}, events)
    r = await _drive(c, mode)
    assert [b["model"] for b in rec.bodies] == ["configured", "configured"]
    assert r.model == "configured"
    assert {e["model"] for e in events} == {"configured"}


@pytest.mark.asyncio
@pytest.mark.parametrize("style", STYLES)
@pytest.mark.parametrize("mode", MODES)
async def test_failed_run_reports_last_transmitted_model(style, mode):
    events: list[dict[str, Any]] = []

    def hook(ev):
        if ev["turn"] == 1:
            raise Boom("second turn")
        return {"model": "small-fast"}

    c, rec = _client(style, {"before_llm": hook}, events)
    with pytest.raises(Boom):
        await _drive(c, mode)
    assert len(rec.bodies) == 1
    run = [e for e in events if e["event"] == "run"]
    assert len(run) == 1 and run[0]["model"] == "small-fast" and "error" in run[0]


@pytest.mark.asyncio
@pytest.mark.parametrize("style", STYLES)
async def test_translate_model_override(style):
    events: list[dict[str, Any]] = []
    c, rec = _client(style, {"before_llm": lambda ev: {"model": "small-fast"}}, events)
    r = await c.translate([{"role": "user", "content": "go"}])
    assert rec.bodies[0]["model"] == "small-fast"
    assert r.model == "small-fast"
    assert [e["model"] for e in events if e["event"] == "llm"] == ["small-fast"]

    events.clear()
    c, rec = _client(style, {"before_llm": lambda ev: None}, events)
    r = await c.translate([{"role": "user", "content": "go"}])
    assert rec.bodies[0]["model"] == "configured" and r.model == "configured"
    assert [e["model"] for e in events if e["event"] == "llm"] == ["configured"]
