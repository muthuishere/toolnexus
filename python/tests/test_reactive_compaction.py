"""SPEC §7F.1 — reactive compaction on context overflow, replaying the shared fixture
``conformance/reactive_compaction/cases.json`` against a local fake LLM endpoint
(no network, no live LLM). Every loop case runs through run AND stream, both styles.
"""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from toolnexus import (
    ContextOverflowError,
    create_client,
    create_toolkit,
    define_tool,
    is_context_overflow,
)
from toolnexus.client import ProviderError

CASES = json.loads(
    (Path(__file__).resolve().parents[2] / "conformance" / "reactive_compaction" / "cases.json").read_text()
)


@pytest.mark.parametrize("case", CASES["detection"], ids=lambda c: c["name"])
def test_detection(case):
    assert is_context_overflow(case["status"], case["body"]) is case["overflow"]


def _openai_json(kind: str, n: int) -> dict:
    if kind == "text":
        msg = {"role": "assistant", "content": "done"}
    else:
        msg = {
            "role": "assistant",
            "content": None,
            "tool_calls": [{"id": f"c{n}", "type": "function", "function": {"name": "t", "arguments": "{}"}}],
        }
    return {"choices": [{"message": msg}], "usage": {"prompt_tokens": 1, "completion_tokens": 1}}


def _anthropic_json(kind: str, n: int) -> dict:
    if kind == "text":
        return {"content": [{"type": "text", "text": "done"}], "stop_reason": "end_turn"}
    return {"content": [{"type": "tool_use", "id": f"c{n}", "name": "t", "input": {}}], "stop_reason": "tool_use"}


def _openai_sse(kind: str, n: int) -> list[dict]:
    if kind == "text":
        return [{"choices": [{"delta": {"content": "done"}}]}]
    return [
        {
            "choices": [
                {"delta": {"tool_calls": [{"index": 0, "id": f"c{n}", "function": {"name": "t", "arguments": "{}"}}]}}
            ]
        }
    ]


def _anthropic_sse(kind: str, n: int) -> list[dict]:
    if kind == "text":
        return [
            {"type": "message_start", "message": {"usage": {"input_tokens": 1}}},
            {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}},
            {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "done"}},
            {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 1}},
        ]
    return [
        {"type": "message_start", "message": {"usage": {"input_tokens": 1}}},
        {"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": f"c{n}", "name": "t"}},
        {"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{}"}},
        {"type": "message_delta", "delta": {"stop_reason": "tool_use"}, "usage": {"output_tokens": 1}},
    ]


class _Scripted:
    def __init__(self, script: list[str], style: str):
        self.script = list(script)
        self.style = style
        self.calls = 0
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):  # noqa: N802
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                outer.serve(self, body)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def serve(self, req, body):
        n = self.calls
        self.calls += 1
        kind = self.script[n] if n < len(self.script) else "text"
        if kind in ("overflow", "bad400"):
            spec = CASES["overflowBodies"][self.style] if kind == "overflow" else CASES["bad400"]
            data = spec["body"].encode()
            req.send_response(spec["status"])
            req.send_header("Content-Type", "application/json")
            req.send_header("Content-Length", str(len(data)))
            req.end_headers()
            req.wfile.write(data)
            return
        if body.get("stream"):
            events = (_openai_sse if self.style == "openai" else _anthropic_sse)(kind, n)
            out = "".join(f"data: {json.dumps(e)}\n\n" for e in events)
            if self.style == "openai":
                out += "data: [DONE]\n\n"
            data = out.encode()
            ctype = "text/event-stream"
        else:
            data = json.dumps((_openai_json if self.style == "openai" else _anthropic_json)(kind, n)).encode()
            ctype = "application/json"
        req.send_response(200)
        req.send_header("Content-Type", ctype)
        req.send_header("Content-Length", str(len(data)))
        req.end_headers()
        req.wfile.write(data)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._server.shutdown()
        self._server.server_close()


def _history() -> list[dict]:
    return [{"role": "user" if i % 2 == 0 else "assistant", "content": f"h{i}"} for i in range(8)]


@pytest.mark.parametrize("mode", ["run", "stream"])
@pytest.mark.parametrize("style", ["openai", "anthropic"])
@pytest.mark.parametrize("case", CASES["loops"], ids=lambda c: c["name"])
async def test_loop(case, style, mode):
    hook_calls = 0

    def hook(ev):
        nonlocal hook_calls
        hook_calls += 1
        if case.get("hookNoop"):
            return None
        return {"messages": ev["messages"][1:]}

    tool = define_tool(lambda: "ok", name="t", description="t", input_schema={"type": "object", "properties": {}})
    tk = await create_toolkit(builtins=False, extra_tools=[tool])
    try:
        with _Scripted(case["script"], style) as srv:
            client = create_client(
                base_url=f"http://127.0.0.1:{srv._server.server_address[1]}/v1",
                style=style,
                model="m",
                api_key="sk-test",  # never a real key
                max_turns=20,
                retries=0,
                compact_on_overflow=hook if case["option"] else None,
            )
            expect = case["expect"]
            err = None
            result = None
            try:
                if mode == "run":
                    result = await client.run("go", tk, history=_history())
                else:
                    await client.conversation_store.save("t", _history())
                    async for ev in client.stream("go", tk, id="t"):
                        if ev["type"] == "done":
                            result = ev["result"]
            except Exception as e:  # noqa: BLE001
                err = e
            if "text" in expect:
                assert err is None, err
                assert result.text == expect["text"]
                assert hook_calls == expect["compactions"]
            elif expect["error"] == "ContextOverflowError":
                assert isinstance(err, ContextOverflowError), err
                assert err.compactions == expect["compactions"]
                assert str(err) == expect["message"]
                assert err.status == 400
            else:
                assert isinstance(err, ProviderError) and not isinstance(err, ContextOverflowError), err
                assert err.status == expect["status"]
                assert hook_calls == 0
            assert srv.calls == case["calls"]
    finally:
        await tk.close()
