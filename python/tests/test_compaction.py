"""Context-compaction conformance (SPEC.md §7F) — C1–C6, 11 checks.

Mirrors the JS spike (``js/spike/compaction-demo.ts``) exactly and runs against the
shared fixture ``examples/compaction/fixture.json``: the transcript is built
deterministically (systemPrompt + 30 tool-group turns) so every port produces the
same bytes and therefore splits at the same point. C1–C5 exercise the pure
``compactor`` helper; C6 wires it through the SHIPPED client loop via the
``before_llm`` seam against a local mock LLM (no network, zero cost).
"""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from toolnexus import create_client, create_toolkit, define_tool
from toolnexus.agents import compactor, estimate_tokens

FIXTURE = (
    Path(__file__).resolve().parents[2] / "examples" / "compaction" / "fixture.json"
)


def _load_fixture() -> dict:
    return json.loads(FIXTURE.read_text())


def _transcript(user_turns: int, system_prompt: str = "You are Ava. SOUL.") -> list[dict]:
    """Build the fixture transcript: system prompt + `user_turns` tool groups.

    Byte-identical construction to the JS spike so the token estimate (and thus the
    split) matches across ports.
    """
    m: list[dict] = [{"role": "system", "content": system_prompt}]
    for i in range(user_turns):
        m.append({"role": "user", "content": f"question {i} " + "pad " * 40})
        m.append(
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": f"c{i}",
                        "type": "function",
                        "function": {"name": "lookup", "arguments": "{}"},
                    }
                ],
            }
        )
        m.append({"role": "tool", "tool_call_id": f"c{i}", "content": f"result {i} " + "data " * 40})
        m.append({"role": "assistant", "content": f"answer {i}"})
    return m


def _summarize(older: list[dict]) -> str:
    return f"summarized {len(older)} messages"


# --------------------------------------------------------------------------- #
# C1 — no-op below budget (byte-identical)
# --------------------------------------------------------------------------- #
async def test_c1_below_budget_is_noop():
    hook = compactor(max_tokens=100_000, summarize=_summarize)
    msgs = _transcript(3)
    out = await hook({"messages": msgs, "tools": [], "model": "m", "turn": 0})
    assert out is None  # returns nothing when under budget


# --------------------------------------------------------------------------- #
# C2 — compacts above budget: summarize head, keep tail
# --------------------------------------------------------------------------- #
async def test_c2_above_budget_compacts():
    fx = _load_fixture()
    opts = fx["options"]
    msgs = _transcript(fx["input"]["generator"]["userTurns"], fx["input"]["systemPrompt"])
    before = estimate_tokens(msgs)
    hook = compactor(
        max_tokens=opts["maxTokens"], keep_tail=opts["keepTail"], summarize=_summarize
    )
    out = await hook({"messages": msgs, "tools": [], "model": "m", "turn": 0})

    assert out is not None, "compacts when over budget"
    assert estimate_tokens(out["messages"]) < before, "result is smaller than the original"
    assert any(
        str(m.get("content")).startswith("[Summary of earlier conversation]")
        for m in out["messages"]
    ), "a summary system message is inserted"


# --------------------------------------------------------------------------- #
# C3 — tool-pair safety: no orphaned tool result
# --------------------------------------------------------------------------- #
async def test_c3_tool_pair_safety():
    msgs = _transcript(30)
    out = await compactor(max_tokens=2000, keep_tail=800, summarize=_summarize)(
        {"messages": msgs, "tools": [], "model": "m", "turn": 0}
    )
    t = out["messages"]

    safe = True
    for i, msg in enumerate(t):
        if msg.get("role") == "tool":
            tid = msg.get("tool_call_id")
            has_parent = any(
                m.get("role") == "assistant"
                and any(c.get("id") == tid for c in (m.get("tool_calls") or []))
                for m in t[:i]
            )
            if not has_parent:
                safe = False
    assert safe, "no tool message is orphaned from its tool_calls"

    first_non_system = next((m for m in t if m.get("role") != "system"), None)
    assert first_non_system is not None and first_non_system.get("role") == "user", (
        "the tail begins at a clean user turn"
    )


# --------------------------------------------------------------------------- #
# C4 — leading system prompt preserved verbatim
# --------------------------------------------------------------------------- #
async def test_c4_system_prompt_preserved():
    msgs = _transcript(30)
    out = await compactor(max_tokens=2000, summarize=_summarize)(
        {"messages": msgs, "tools": [], "model": "m", "turn": 0}
    )
    first = out["messages"][0]
    assert first["role"] == "system" and "SOUL" in str(first["content"]), (
        "original SOUL system prompt is still first"
    )


# --------------------------------------------------------------------------- #
# C5 — flush_to_memory injects a pre-compact reminder
# --------------------------------------------------------------------------- #
async def test_c5_flush_to_memory():
    msgs = _transcript(30)
    out = await compactor(max_tokens=2000, summarize=_summarize, flush_to_memory=True)(
        {"messages": msgs, "tools": [], "model": "m", "turn": 0}
    )
    assert any(
        "save it with the memory tool" in str(m.get("content")) for m in out["messages"]
    ), "a 'save with the memory tool' reminder is present"


# --------------------------------------------------------------------------- #
# C6 — end-to-end through the SHIPPED client loop via before_llm
# --------------------------------------------------------------------------- #
class _MockLLM:
    """Local OpenAI-style server: 6 padding tool calls, then a final answer.

    The final message reports whether the request it saw was already compacted
    (a system message beginning with "[Summary"), proving compaction fired mid-run.
    """

    def __init__(self):
        self.calls = 0
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):  # silence
                pass

            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                compacted = any(
                    str(m.get("content") or "").startswith("[Summary")
                    for m in body.get("messages", [])
                )
                done = outer.calls >= 6
                if done:
                    message = {
                        "role": "assistant",
                        "content": f"final (compacted={str(compacted).lower()})",
                    }
                else:
                    message = {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": f"c{outer.calls}",
                                "type": "function",
                                "function": {"name": "pad", "arguments": "{}"},
                            }
                        ],
                    }
                    outer.calls += 1
                payload = json.dumps(
                    {
                        "choices": [{"message": message}],
                        "usage": {
                            "prompt_tokens": 50,
                            "completion_tokens": 10,
                            "total_tokens": 60,
                        },
                    }
                ).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}/v1"

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()


async def test_c6_end_to_end_across_compaction():
    pad = define_tool(
        lambda: "x " * 600,
        name="pad",
        description="pads",
        input_schema={"type": "object", "properties": {}},
    )
    tk = await create_toolkit(builtins=False, extra_tools=[pad])
    try:
        with _MockLLM() as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="m",
                api_key="spike",  # never a real key
                max_turns=20,
                system_prompt="You are Ava. SOUL.",
                hooks={
                    "before_llm": compactor(
                        max_tokens=600, keep_tail=250, summarize=_summarize
                    )
                },
            )
            r = await client.run("start", tk)

        assert r.status == "done" and r.text.startswith("final"), (
            f"run completes to a final answer despite mid-run compaction: {r.text!r}"
        )
        assert "compacted=true" in r.text, (
            f"compaction actually fired during the run: {r.text!r}"
        )
        assert estimate_tokens(r.messages) < 4000, (
            f"final transcript is bounded, not the full raw history: "
            f"{estimate_tokens(r.messages)}"
        )
    finally:
        await tk.close()
