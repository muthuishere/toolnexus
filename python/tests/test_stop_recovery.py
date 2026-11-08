"""conformance/stop_recovery/cases.json (SPEC §8 stop-reason recovery) — every case
replayed in both styles (openai, anthropic) and both loops (run, stream) against a
scripted fake transport."""
from __future__ import annotations

import io
import json
from pathlib import Path

import pytest

from toolnexus import create_client, create_toolkit
from toolnexus.types import Tool, ToolResult

_ROOT = Path(__file__).resolve().parents[2]
FIXTURE = json.loads((_ROOT / "conformance" / "stop_recovery" / "cases.json").read_text())
MESSAGES = FIXTURE["messages"]
CASES = FIXTURE["cases"]

_OAI_FINISH = {"end": "stop", "truncated": "length", "tool": "tool_calls"}
_ANT_STOP = {"end": "end_turn", "truncated": "max_tokens", "tool": "tool_use"}


def _parsed_obj(raw: str):
    try:
        v = json.loads(raw)
    except Exception:
        return None
    return v if isinstance(v, dict) else None


def _openai_body(r, n):
    msg = {"role": "assistant", "content": r.get("text", "")}
    if r.get("thinking"):
        msg["reasoning_content"] = r["thinking"]
    if r.get("toolCalls"):
        msg["tool_calls"] = [
            {"id": f"c{n}_{i}", "type": "function", "function": {"name": c["name"], "arguments": c["arguments"]}}
            for i, c in enumerate(r["toolCalls"])
        ]
    return {"choices": [{"index": 0, "message": msg, "finish_reason": _OAI_FINISH[r["stop"]]}], "usage": {"prompt_tokens": 1, "completion_tokens": 1}}


def _anthropic_body(r, n):
    content = []
    if r.get("thinking"):
        content.append({"type": "thinking", "thinking": r["thinking"], "signature": "sig"})
    if r.get("text"):
        content.append({"type": "text", "text": r["text"]})
    for i, c in enumerate(r.get("toolCalls") or []):
        obj = _parsed_obj(c["arguments"])
        content.append({"type": "tool_use", "id": f"c{n}_{i}", "name": c["name"], "input": obj if obj is not None else c["arguments"]})
    return {"content": content, "stop_reason": _ANT_STOP[r["stop"]], "usage": {"input_tokens": 1, "output_tokens": 1}}


def _sse(events):
    return "".join(f"data: {json.dumps(e)}\n\n" for e in events).encode()


def _openai_sse(r, n):
    ev = []
    if r.get("thinking"):
        ev.append({"choices": [{"index": 0, "delta": {"reasoning_content": r["thinking"]}}]})
    if r.get("text"):
        ev.append({"choices": [{"index": 0, "delta": {"content": r["text"]}}]})
    for i, c in enumerate(r.get("toolCalls") or []):
        ev.append({"choices": [{"index": 0, "delta": {"tool_calls": [{"index": i, "id": f"c{n}_{i}", "type": "function", "function": {"name": c["name"], "arguments": c["arguments"]}}]}}]})
    ev.append({"choices": [{"index": 0, "delta": {}, "finish_reason": _OAI_FINISH[r["stop"]]}]})
    ev.append({"choices": [], "usage": {"prompt_tokens": 1, "completion_tokens": 1}})
    return _sse(ev) + b"data: [DONE]\n\n"


def _anthropic_sse(r, n):
    ev = [{"type": "message_start", "message": {"usage": {"input_tokens": 1, "output_tokens": 0}}}]
    idx = 0
    if r.get("thinking"):
        ev.append({"type": "content_block_start", "index": idx, "content_block": {"type": "thinking", "thinking": ""}})
        ev.append({"type": "content_block_delta", "index": idx, "delta": {"type": "thinking_delta", "thinking": r["thinking"]}})
        ev.append({"type": "content_block_delta", "index": idx, "delta": {"type": "signature_delta", "signature": "sig"}})
        ev.append({"type": "content_block_stop", "index": idx})
        idx += 1
    if r.get("text"):
        ev.append({"type": "content_block_start", "index": idx, "content_block": {"type": "text", "text": ""}})
        ev.append({"type": "content_block_delta", "index": idx, "delta": {"type": "text_delta", "text": r["text"]}})
        ev.append({"type": "content_block_stop", "index": idx})
        idx += 1
    for i, c in enumerate(r.get("toolCalls") or []):
        ev.append({"type": "content_block_start", "index": idx, "content_block": {"type": "tool_use", "id": f"c{n}_{i}", "name": c["name"], "input": {}}})
        ev.append({"type": "content_block_delta", "index": idx, "delta": {"type": "input_json_delta", "partial_json": c["arguments"]}})
        ev.append({"type": "content_block_stop", "index": idx})
        idx += 1
    ev.append({"type": "message_delta", "delta": {"stop_reason": _ANT_STOP[r["stop"]]}, "usage": {"output_tokens": 1}})
    ev.append({"type": "message_stop"})
    return _sse(ev)


class _Scripted:
    def __init__(self, style, responses):
        self.style = style
        self.responses = responses
        self.bodies = []

    def _next(self, payload):
        self.bodies.append(json.loads(json.dumps(payload)))
        n = len(self.bodies) - 1
        assert n < len(self.responses), "more provider calls than scripted"
        return self.responses[n], n

    def post(self, url, headers, payload, timeout):
        r, n = self._next(payload)
        return _openai_body(r, n) if self.style == "openai" else _anthropic_body(r, n)

    def open(self, url, headers, payload, timeout):  # noqa: A003
        r, n = self._next(payload)
        return io.BytesIO(_openai_sse(r, n) if self.style == "openai" else _anthropic_sse(r, n))


@pytest.mark.parametrize("loop", ["run", "stream"])
@pytest.mark.parametrize("style", ["openai", "anthropic"])
@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
async def test_stop_recovery_case(case, style, loop):
    runs = []

    async def _echo(args=None, ctx=None):
        runs.append(args)
        return ToolResult(output="ok", is_error=False)

    echo = Tool(name="echo", description="echo", input_schema={"type": "object", "properties": {}}, source="native", execute=_echo)
    toolkit = await create_toolkit(builtins=False, extra_tools=[echo])
    transport = _Scripted(style, case["responses"])
    kw = {"max_turns": case["maxTurns"]} if "maxTurns" in case else {}
    client = create_client(
        base_url="http://mock.local", style=style, model="m", api_key="unused",
        http_transport=transport, stop_recovery=case["stopRecovery"], retries=0, **kw,
    )
    if loop == "run":
        result = await client.run("go", toolkit)
    else:
        result = None
        async for ev in client.stream("go", toolkit):
            if ev["type"] == "done":
                result = ev["result"]
    exp = case["expect"]
    assert len(transport.bodies) == exp["turns"]
    assert result.turns == exp["turns"]
    assert result.text == exp["text"]
    got = []
    for body in transport.bodies[1:]:
        last = body["messages"][-1]
        if last.get("role") == "user" and isinstance(last.get("content"), str) and last["content"] in MESSAGES.values():
            assert last == {"role": "user", "content": last["content"]}
            got.append(next(k for k, v in MESSAGES.items() if v == last["content"]))
    assert got == exp["recoveries"]
    assert runs == exp["toolRuns"]


def test_messages_are_pinned():
    from toolnexus.client import RECOVERY_CONTINUE, RECOVERY_CORRECTIVE, RECOVERY_NUDGE

    assert MESSAGES == {"NUDGE": RECOVERY_NUDGE, "CONTINUE": RECOVERY_CONTINUE, "CORRECTIVE": RECOVERY_CORRECTIVE}
