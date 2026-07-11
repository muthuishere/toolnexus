"""Observability + streaming-memory tests — ``stream({id})``, ``ask(on_text)``,
``on_metric``, and ``client.metrics()`` (Prometheus text).

Mirrors the JS reference quartet (``js/test/unit.test.ts``):
  * "client: stream({ id }) remembers the transcript across streams"
  * "client: ask({ on_text }) streams deltas AND returns the full RunResult"
  * "client: onMetric fires llm, tool, and a final aggregated run event"
  * "client: metrics() renders well-formed Prometheus text after a run"

All hermetic: a mock OpenAI-style LLM runs in-process on an ephemeral port. No
external network, no real LLM. See ../../SPEC.md §8 (Streaming, Conversation
memory, Observability). The ``on_metric`` event field casing is idiomatic
snake_case Python (NOT byte-identical to JS); the ``metrics()`` text IS.
"""
from __future__ import annotations

import http.server
import json
import re
import threading

from toolnexus import create_client, create_toolkit, define_tool


def _start_server(handler_fn):
    """Start a threaded HTTP server; ``handler_fn(request_body_dict) -> bytes|str``
    returns the raw response body (SSE text or JSON string). Returns (server, base_url)."""

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):  # silence
            pass

        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length else b"{}"
            msg = json.loads(raw.decode("utf-8"))
            content_type, body = handler_fn(msg)
            if isinstance(body, str):
                body = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


def _sse(*events: dict) -> str:
    return "".join(f"data: {json.dumps(e)}\n\n" for e in events) + "data: [DONE]\n\n"


# --------------------------------------------------------------------------- #
# 1. stream({id}) remembers the transcript across streams
# --------------------------------------------------------------------------- #
async def test_stream_id_remembers_transcript_across_streams():
    # mock streaming LLM whose text = number of messages it received → proves
    # history is loaded (and saved on done).
    def handler(msg):
        n = str(len(msg["messages"]))
        return "text/event-stream", _sse(
            {"choices": [{"delta": {"content": n}}]},
            {"choices": [{"delta": {}}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}},
        )

    srv, base_url = _start_server(handler)
    tk = await create_toolkit(builtins=False)  # no skills ⇒ no system msg; count = turns
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")

    async def consume(prompt, id=None):  # noqa: A002
        text = ""
        done = None
        async for ev in client.stream(prompt, tk, id=id):
            if ev["type"] == "text":
                text += ev["delta"]
            elif ev["type"] == "done":
                done = ev["result"]
        return text, done

    try:
        solo_text, _ = await consume("x")
        assert solo_text == "1"  # no id ⇒ stateless (just the user turn)

        first_text, _ = await consume("first", "s1")
        assert first_text == "1"  # first stream: 1 message
        second_text, second_done = await consume("second", "s1")
        assert second_text == "3"  # same id remembers: user+assistant+user = 3
        assert len(second_done.messages) == 4  # transcript saved on done (now 4)

        other_text, _ = await consume("other", "s2")
        assert other_text == "1"  # a different id is independent
    finally:
        await tk.close()
        srv.shutdown()


# --------------------------------------------------------------------------- #
# 2. ask(on_text) streams deltas AND returns the full RunResult
# --------------------------------------------------------------------------- #
async def test_ask_on_text_streams_deltas_and_returns_full_result():
    def handler(msg):
        if msg.get("stream"):
            return "text/event-stream", _sse(
                {"choices": [{"delta": {"content": "Hel"}}]},
                {"choices": [{"delta": {"content": "lo"}}]},
                {"choices": [{"delta": {}}], "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}},
            )
        return "application/json", json.dumps(
            {"choices": [{"message": {"content": "Hello"}}], "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}}
        )

    srv, base_url = _start_server(handler)
    tk = await create_toolkit(builtins=False)
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    try:
        deltas: list[str] = []
        streamed = await client.ask("hi", tk, on_text=lambda d: deltas.append(d))
        assert deltas == ["Hel", "lo"]  # on_text receives each text delta
        assert streamed.text == "Hello"  # ask still returns the full RunResult
        assert streamed.usage["total_tokens"] == 5

        # async on_text is also supported
        adeltas: list[str] = []

        async def aon(d):
            adeltas.append(d)

        streamed2 = await client.ask("hi", tk, on_text=aon)
        assert adeltas == ["Hel", "lo"]
        assert streamed2.text == "Hello"

        plain = await client.ask("hi", tk)  # without on_text: non-streaming path
        assert plain.text == "Hello"
    finally:
        await tk.close()
        srv.shutdown()


# --------------------------------------------------------------------------- #
# 3. on_metric fires llm, tool, and a final aggregated run event
# --------------------------------------------------------------------------- #
async def test_on_metric_fires_llm_tool_run_events():
    def handler(msg):
        has_tool_result = any(m.get("role") == "tool" for m in msg["messages"])
        if not has_tool_result:
            body = {
                "choices": [{"message": {"content": None, "tool_calls": [
                    {"id": "c1", "type": "function", "function": {"name": "add", "arguments": json.dumps({"a": 2, "b": 3})}}
                ]}}],
                "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9},
            }
        else:
            body = {"choices": [{"message": {"content": "5"}}], "usage": {"prompt_tokens": 6, "completion_tokens": 1, "total_tokens": 7}}
        return "application/json", json.dumps(body)

    srv, base_url = _start_server(handler)
    tk = await create_toolkit(builtins=False)
    tk.register(define_tool(
        lambda a, b: str(a + b),
        name="add", description="add",
        input_schema={"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]},
    ))
    events: list[dict] = []
    client = create_client(base_url=base_url, style="openai", model="gpt-x", api_key="k", on_metric=events.append)
    try:
        await client.run("add them", tk)

        llm = next(e for e in events if e["event"] == "llm")
        assert llm["model"] == "gpt-x"
        assert llm["status"] == "ok"
        assert isinstance(llm["ms"], (int, float))
        assert "prompt_tokens" in llm and "completion_tokens" in llm

        tool = next(e for e in events if e["event"] == "tool")
        assert tool["tool"] == "add"
        assert tool["source"] == "native"
        assert tool["is_error"] is False
        assert isinstance(tool["ms"], (int, float))

        run = events[-1]
        assert run["event"] == "run"  # run event is last
        assert run["model"] == "gpt-x"
        assert run["tool_calls"] == 1  # one tool call aggregated
        assert run["turns"] == 2  # two LLM round trips
        assert run["total_tokens"] == 16  # 9 + 7 summed
        assert "error" not in run
        assert len([e for e in events if e["event"] == "llm"]) == 2  # one llm event per call
    finally:
        await tk.close()
        srv.shutdown()


# --------------------------------------------------------------------------- #
# 4. metrics() renders well-formed Prometheus text after a run
# --------------------------------------------------------------------------- #
async def test_metrics_renders_prometheus_text():
    def handler(msg):
        return "application/json", json.dumps(
            {"choices": [{"message": {"content": "ok"}}], "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}}
        )

    srv, base_url = _start_server(handler)
    tk = await create_toolkit(builtins=False)
    client = create_client(base_url=base_url, style="openai", model="gpt-x", api_key="k")
    try:
        before = client.metrics()
        assert "# TYPE toolnexus_llm_requests_total counter" in before  # empty-but-valid
        # before any activity: only HELP/TYPE lines, no samples
        assert not re.search(r"^toolnexus_", before, re.MULTILINE)
        assert before.endswith("\n")

        await client.run("hi", tk)
        text = client.metrics()

        # counters present with labels
        assert 'toolnexus_llm_requests_total{model="gpt-x",status="ok"} 1' in text
        assert 'toolnexus_llm_tokens_total{type="prompt"} 3' in text
        assert 'toolnexus_llm_tokens_total{type="completion"} 2' in text
        # histogram: HELP/TYPE + _bucket / _sum / _count
        assert "# TYPE toolnexus_llm_request_duration_seconds histogram" in text
        assert re.search(r'toolnexus_llm_request_duration_seconds_bucket\{model="gpt-x",le="0\.05"\} \d+', text)
        assert re.search(r'toolnexus_llm_request_duration_seconds_bucket\{model="gpt-x",le="\+Inf"\} \d+', text)
        assert re.search(r'toolnexus_llm_request_duration_seconds_sum\{model="gpt-x"\} ', text)
        assert 'toolnexus_llm_request_duration_seconds_count{model="gpt-x"} 1' in text
        # trailing newline
        assert text.endswith("\n")
        # every non-empty line is a comment or a well-formed metric sample
        line_re = re.compile(r'^[a-zA-Z_][a-zA-Z0-9_]*(\{[^}]*\})? -?[0-9.eE+]+$')
        for line in filter(None, text.split("\n")):
            assert line.startswith("#") or line_re.match(line), f"well-formed line: {line}"
    finally:
        await tk.close()
        srv.shutdown()


# --------------------------------------------------------------------------- #
# 5. metrics() text matches the exact documented Prometheus format (byte-level)
# --------------------------------------------------------------------------- #
def test_metrics_registry_exact_bytes():
    """Feed a fixed event sequence straight into the registry and assert the full
    rendered text byte-for-byte. This pins the format the JS reference produces:
    metric order, HELP/TYPE lines, lexicographic label sort, histogram buckets +
    le rendering, label escaping, and the trailing newline.
    """
    from toolnexus.client import MetricsRegistry

    reg = MetricsRegistry()
    # ms=100 → 0.1s (≤ 0.1, 0.25, …); ms=300 → 0.3s (≤ 0.5, …). Deterministic buckets.
    reg.record({"event": "llm", "model": "gpt-x", "status": "ok", "ms": 100, "prompt_tokens": 3, "completion_tokens": 2})
    reg.record({"event": "tool", "tool": "add", "source": "native", "is_error": False, "ms": 300})
    reg.record({"event": "run", "model": "gpt-x", "turns": 2, "tool_calls": 1, "total_tokens": 5, "ms": 400})

    expected = (
        "# HELP toolnexus_llm_requests_total Total LLM requests.\n"
        "# TYPE toolnexus_llm_requests_total counter\n"
        'toolnexus_llm_requests_total{model="gpt-x",status="ok"} 1\n'
        "# HELP toolnexus_llm_tokens_total Total tokens, by type.\n"
        "# TYPE toolnexus_llm_tokens_total counter\n"
        'toolnexus_llm_tokens_total{type="completion"} 2\n'
        'toolnexus_llm_tokens_total{type="prompt"} 3\n'
        "# HELP toolnexus_llm_request_duration_seconds LLM request duration in seconds.\n"
        "# TYPE toolnexus_llm_request_duration_seconds histogram\n"
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.05"} 0\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.1"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.25"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.5"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="1"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="2.5"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="5"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="10"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="30"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="60"} 1\n'
        'toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="+Inf"} 1\n'
        'toolnexus_llm_request_duration_seconds_sum{model="gpt-x"} 0.1\n'
        'toolnexus_llm_request_duration_seconds_count{model="gpt-x"} 1\n'
        "# HELP toolnexus_tool_calls_total Total tool calls.\n"
        "# TYPE toolnexus_tool_calls_total counter\n"
        'toolnexus_tool_calls_total{tool="add",source="native",is_error="false",pending="false"} 1\n'
        "# HELP toolnexus_tool_duration_seconds Tool execution duration in seconds.\n"
        "# TYPE toolnexus_tool_duration_seconds histogram\n"
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="0.05"} 0\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="0.1"} 0\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="0.25"} 0\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="0.5"} 1\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="1"} 1\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="2.5"} 1\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="5"} 1\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="10"} 1\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="30"} 1\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="60"} 1\n'
        'toolnexus_tool_duration_seconds_bucket{tool="add",le="+Inf"} 1\n'
        'toolnexus_tool_duration_seconds_sum{tool="add"} 0.3\n'
        'toolnexus_tool_duration_seconds_count{tool="add"} 1\n'
        "# HELP toolnexus_run_errors_total Total run errors.\n"
        "# TYPE toolnexus_run_errors_total counter\n"
    )
    assert reg.render() == expected


def test_metrics_registry_empty_is_help_type_only():
    from toolnexus.client import MetricsRegistry

    expected = (
        "# HELP toolnexus_llm_requests_total Total LLM requests.\n"
        "# TYPE toolnexus_llm_requests_total counter\n"
        "# HELP toolnexus_llm_tokens_total Total tokens, by type.\n"
        "# TYPE toolnexus_llm_tokens_total counter\n"
        "# HELP toolnexus_llm_request_duration_seconds LLM request duration in seconds.\n"
        "# TYPE toolnexus_llm_request_duration_seconds histogram\n"
        "# HELP toolnexus_tool_calls_total Total tool calls.\n"
        "# TYPE toolnexus_tool_calls_total counter\n"
        "# HELP toolnexus_tool_duration_seconds Tool execution duration in seconds.\n"
        "# TYPE toolnexus_tool_duration_seconds histogram\n"
        "# HELP toolnexus_run_errors_total Total run errors.\n"
        "# TYPE toolnexus_run_errors_total counter\n"
    )
    assert MetricsRegistry().render() == expected
