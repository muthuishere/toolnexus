#!/usr/bin/env python3
"""Deterministic, zero-cost mock OpenAI-compatible Chat Completions server.

Every framework in this benchmark points its ``base_url`` at this server so we
measure *framework overhead*, not model latency (which is provider-bound and
would swamp everything). The server:

  * accepts POST /chat/completions  and  POST /v1/chat/completions
  * turn 1 (no tool results in the transcript yet) -> returns TWO tool_calls:
        get_weather(city="Paris")  and  add(a=2, b=2)
  * turn 2 (a role=="tool" message is present) -> returns a final assistant
    message. Deterministic, no network, no cost.

It also serves a tiny non-streaming / streaming (SSE) variant so streaming
clients work too. Anthropic /v1/messages is stubbed with the same script so an
Anthropic-style client can target it as well (unused by the current runners).
"""
from __future__ import annotations

import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FINAL_TEXT = "The weather in Paris is Sunny, 22C. And 2 + 2 = 4."


def _pick(tools, suffix, fallback):
    """Pick the advertised tool whose name ends with `suffix` (frameworks such as
    Spring AI prefix MCP tool names, e.g. `bench_get_weather`). This makes the mock
    behave like a real LLM: it chooses from the tools the framework actually sent.
    Falls back to the bare name when no tool list is provided."""
    for t in tools or []:
        fn = t.get("function", t) if isinstance(t, dict) else {}
        name = fn.get("name") or (t.get("name") if isinstance(t, dict) else None)
        if name and (name == suffix or name.endswith("_" + suffix) or name.endswith(suffix)):
            return name
    return fallback


def _openai_tool_calls(tools):
    w = _pick(tools, "get_weather", "get_weather")
    a = _pick(tools, "add", "add")
    return [
        {"id": "call_weather_1", "type": "function",
         "function": {"name": w, "arguments": json.dumps({"city": "Paris"})}},
        {"id": "call_add_1", "type": "function",
         "function": {"name": a, "arguments": json.dumps({"a": 2, "b": 2})}},
    ]


def _has_tool_result(messages):
    for m in messages:
        role = m.get("role")
        if role == "tool":
            return True
        # Anthropic-style user turn carrying tool_result blocks
        content = m.get("content")
        if isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    return True
    return False


def _openai_body(model, want_tools, tools=None):
    if want_tools:
        message = {"role": "assistant", "content": None,
                   "tool_calls": _openai_tool_calls(tools)}
        finish = "tool_calls"
    else:
        message = {"role": "assistant", "content": FINAL_TEXT}
        finish = "stop"
    return {
        "id": "chatcmpl-mock-0001",
        "object": "chat.completion",
        "created": 0,
        "model": model or "mock-model",
        "choices": [{"index": 0, "message": message, "finish_reason": finish}],
        "usage": {"prompt_tokens": 20, "completion_tokens": 8, "total_tokens": 28},
    }


def _anthropic_body(model, want_tools, tools=None):
    if want_tools:
        w = _pick(tools, "get_weather", "get_weather")
        a = _pick(tools, "add", "add")
        content = [
            {"type": "tool_use", "id": "toolu_w1", "name": w, "input": {"city": "Paris"}},
            {"type": "tool_use", "id": "toolu_a1", "name": a, "input": {"a": 2, "b": 2}},
        ]
        stop = "tool_use"
    else:
        content = [{"type": "text", "text": FINAL_TEXT}]
        stop = "end_turn"
    return {
        "id": "msg_mock_0001", "type": "message", "role": "assistant",
        "model": model or "mock-model", "content": content, "stop_reason": stop,
        "usage": {"input_tokens": 20, "output_tokens": 8},
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silence per-request logging
        pass

    def _read_json(self):
        te = (self.headers.get("Transfer-Encoding", "") or "").lower()
        if "chunked" in te:
            raw = self._read_chunked()
        else:
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw or b"{}")
        except Exception:
            return {}

    def _read_chunked(self):
        """Decode an HTTP/1.1 chunked request body (Spring AI's RestClient sends
        requests without a Content-Length, using Transfer-Encoding: chunked)."""
        buf = bytearray()
        while True:
            size_line = self.rfile.readline().strip()
            if not size_line:
                break
            try:
                size = int(size_line.split(b";")[0], 16)
            except ValueError:
                break
            if size == 0:
                self.rfile.readline()  # trailing CRLF after the last chunk
                break
            buf += self.rfile.read(size)
            self.rfile.readline()  # CRLF after each chunk
        return bytes(buf)

    def _send_json(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_sse(self, chunks):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for c in chunks:
            self.wfile.write(f"data: {json.dumps(c)}\n\n".encode())
        self.wfile.write(b"data: [DONE]\n\n")

    def do_GET(self):
        if self.path.rstrip("/") in ("/health", ""):
            self._send_json({"ok": True})
        elif re.search(r"/models", self.path):
            self._send_json({"object": "list", "data": [{"id": "mock-model",
                             "object": "model", "owned_by": "mock"}]})
        else:
            self._send_json({"ok": True})

    def do_POST(self):
        body = self._read_json()
        messages = body.get("messages", [])
        model = body.get("model")
        want_tools = not _has_tool_result(messages)
        stream = bool(body.get("stream"))
        path = self.path

        if "messages" in path and "chat/completions" not in path:
            # Anthropic Messages API
            self._send_json(_anthropic_body(model, want_tools, body.get("tools")))
            return

        # OpenAI Chat Completions (default)
        resp = _openai_body(model, want_tools, body.get("tools"))
        if stream:
            msg = resp["choices"][0]["message"]
            delta = {"role": "assistant"}
            if msg.get("tool_calls"):
                delta["tool_calls"] = [
                    {"index": i, **tc} for i, tc in enumerate(msg["tool_calls"])]
            else:
                delta["content"] = msg.get("content")
            chunk = {"id": resp["id"], "object": "chat.completion.chunk",
                     "model": resp["model"],
                     "choices": [{"index": 0, "delta": delta,
                                  "finish_reason": resp["choices"][0]["finish_reason"]}]}
            self._send_sse([chunk])
        else:
            self._send_json(resp)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8900)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"mock-llm listening on http://{args.host}:{args.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
