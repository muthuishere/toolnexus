"""Hermetic resilience tests for the unified client (no real LLM, no network).

Mirrors the JS resilience suite:
  * a local http.server that returns 503 twice then a valid OpenAI JSON body —
    assert the client retried and ultimately succeeded;
  * a slow server + ``timeout_ms`` — assert the run raises a timeout.

Also covers streaming over a local SSE server and conversation memory continuation.
Everything runs against ``http.server`` on an ephemeral port; nothing leaves the box.
"""
from __future__ import annotations

import asyncio
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from toolnexus import RunTimeout, create_client, create_toolkit


# --------------------------------------------------------------------------- #
# Tiny configurable local server
# --------------------------------------------------------------------------- #
def _openai_text_body(text: str) -> bytes:
    return json.dumps(
        {
            "choices": [{"message": {"role": "assistant", "content": text}}],
            "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
        }
    ).encode("utf-8")


class _Server:
    """Spins a ThreadingHTTPServer driven by a per-request handler callable."""

    def __init__(self, handler):
        self._handler = handler

        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):  # silence
                pass

            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                self.rfile.read(length)
                outer._handler(self)

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


def _send_json(req, status: int, body: bytes, headers=None):
    req.send_response(status)
    req.send_header("Content-Type", "application/json")
    req.send_header("Content-Length", str(len(body)))
    for k, v in (headers or {}).items():
        req.send_header(k, v)
    req.end_headers()
    req.wfile.write(body)


# --------------------------------------------------------------------------- #
# 1. Retry: 503 twice, then success
# --------------------------------------------------------------------------- #
async def test_retries_on_503_then_succeeds():
    calls = {"n": 0}

    def handler(req):
        calls["n"] += 1
        if calls["n"] <= 2:
            body = b'{"error":"unavailable"}'
            _send_json(req, 503, body, headers={"Retry-After": "0"})
        else:
            _send_json(req, 200, _openai_text_body("hello after retry"))

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="test-model",
                api_key="sk-test",  # never a real key
                retries=3,
                retry_base_ms=1,
            )
            result = await client.run("hi", tk)
        assert calls["n"] == 3, "expected 2 failures + 1 success"
        assert result.text == "hello after retry"
        assert result.usage["total_tokens"] == 8
    finally:
        await tk.close()


# --------------------------------------------------------------------------- #
# 2. Retry exhaustion surfaces the error
# --------------------------------------------------------------------------- #
async def test_gives_up_after_retries_exhausted():
    def handler(req):
        _send_json(req, 503, b'{"error":"down"}', headers={"Retry-After": "0"})

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="test-model",
                api_key="sk-test",
                retries=2,
                retry_base_ms=1,
            )
            with pytest.raises(Exception) as ei:
                await client.run("hi", tk)
        assert "503" in str(ei.value)
    finally:
        await tk.close()


# --------------------------------------------------------------------------- #
# 3. Non-retryable status (400) is not retried
# --------------------------------------------------------------------------- #
async def test_does_not_retry_on_400():
    calls = {"n": 0}

    def handler(req):
        calls["n"] += 1
        _send_json(req, 400, b'{"error":"bad request"}')

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="test-model",
                api_key="sk-test",
                retries=3,
                retry_base_ms=1,
            )
            with pytest.raises(Exception) as ei:
                await client.run("hi", tk)
        assert calls["n"] == 1, "400 must not be retried"
        assert "400" in str(ei.value)
    finally:
        await tk.close()


# --------------------------------------------------------------------------- #
# 4. Timeout: a slow server + timeout_ms raises RunTimeout
# --------------------------------------------------------------------------- #
async def test_timeout_on_slow_server():
    def handler(req):
        time.sleep(2.0)  # slower than the run deadline
        _send_json(req, 200, _openai_text_body("too late"))

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="test-model",
                api_key="sk-test",
                retries=0,
                timeout_ms=300,
            )
            start = time.monotonic()
            with pytest.raises(RunTimeout):
                await client.run("hi", tk)
            elapsed = time.monotonic() - start
        assert elapsed < 1.8, "should have given up well before the server responded"
    finally:
        await tk.close()


# --------------------------------------------------------------------------- #
# 5. Streaming over a local SSE server (text deltas + done)
# --------------------------------------------------------------------------- #
async def test_streaming_text_deltas():
    chunks = [
        '{"choices":[{"delta":{"content":"Hel"}}]}',
        '{"choices":[{"delta":{"content":"lo"}}]}',
        '{"choices":[{"delta":{}}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}',
        "[DONE]",
    ]

    def handler(req):
        req.send_response(200)
        req.send_header("Content-Type", "text/event-stream")
        req.end_headers()
        for c in chunks:
            req.wfile.write(f"data: {c}\n\n".encode("utf-8"))
            req.wfile.flush()
            time.sleep(0.01)

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="test-model",
                api_key="sk-test",
            )
            deltas = []
            done = None
            async for ev in client.stream("hi", tk):
                if ev["type"] == "text":
                    deltas.append(ev["delta"])
                elif ev["type"] == "done":
                    done = ev["result"]
        assert deltas == ["Hel", "lo"]
        assert done is not None
        assert done.text == "Hello"
        assert done.usage["total_tokens"] == 3
    finally:
        await tk.close()


# --------------------------------------------------------------------------- #
# 6. Conversation memory continues the transcript across sends
# --------------------------------------------------------------------------- #
async def test_conversation_continues_history():
    seen = {"messages": []}

    def handler(req):
        # Capture nothing fancy; just always answer. We assert message growth below.
        _send_json(req, 200, _openai_text_body("ok"))

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url, style="openai", model="m", api_key="sk-test"
            )
            convo = client.conversation(tk)
            r1 = await convo.send("first")
            n1 = len(convo.messages)
            r2 = await convo.send("second")
            n2 = len(convo.messages)
        # transcript grows; second turn includes the first turn's user+assistant.
        assert n2 > n1
        # the running transcript contains both user prompts
        users = [m for m in convo.messages if m.get("role") == "user"]
        contents = [m.get("content") for m in users]
        assert "first" in contents and "second" in contents
    finally:
        await tk.close()


# --------------------------------------------------------------------------- #
# 7. on_error="fail" on a normally-retryable 429 surfaces immediately (§8)
# --------------------------------------------------------------------------- #
async def test_on_error_fail_short_circuits_retryable_status():
    calls = {"n": 0}
    seen = []

    def handler(req):
        calls["n"] += 1
        _send_json(req, 429, b'{"error":"rate limited"}', headers={"Retry-After": "0"})

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="test-model",
                api_key="sk-test",
                retries=3,
                retry_base_ms=1,
                on_error=lambda info: seen.append(info) or "fail",
            )
            with pytest.raises(Exception) as ei:
                await client.run("hi", tk)
        assert calls["n"] == 1, "on_error='fail' must surface a 429 with exactly one request"
        assert "429" in str(ei.value)
        assert seen and seen[0]["status"] == 429 and seen[0]["retryable"] is True
        assert seen[0]["attempt"] == 0
    finally:
        await tk.close()


# --------------------------------------------------------------------------- #
# 8. on_error="retry" on a normally-non-retryable 400 retries to the budget (§8)
# --------------------------------------------------------------------------- #
async def test_on_error_retry_extends_nonretryable_status_to_budget():
    calls = {"n": 0}

    def handler(req):
        calls["n"] += 1
        _send_json(req, 400, b'{"error":"bad request"}')

    tk = await create_toolkit()
    try:
        with _Server(handler) as srv:
            client = create_client(
                base_url=srv.base_url,
                style="openai",
                model="test-model",
                api_key="sk-test",
                retries=3,
                retry_base_ms=1,
                on_error=lambda info: "retry",
            )
            with pytest.raises(Exception) as ei:
                await client.run("hi", tk)
        # 1 initial + 3 retries; a "retry" is always bounded by the budget.
        assert calls["n"] == 4, "on_error='retry' must retry a 400 up to the budget (1 + retries)"
        assert "400" in str(ei.value)
    finally:
        await tk.close()
