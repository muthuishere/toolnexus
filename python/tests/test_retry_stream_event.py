"""SPEC §8 Resilience: the streaming ``retry`` event and the ``x-should-retry``
override (issue #189). Hermetic: a scripted ``http_transport`` drives each attempt."""
from __future__ import annotations

import io
import json
import urllib.error

import pytest

from toolnexus import create_client
from toolnexus.client import ProviderError

_SSE = {
    "openai": 'data: {"choices":[{"delta":{"content":"hi"}}]}\n\n'
    'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n',
    "anthropic": 'data: {"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}\n\n'
    'data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n'
    'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}\n\n'
    'data: {"type":"content_block_stop","index":0}\n\n'
    'data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}\n\n'
    'data: {"type":"message_stop"}\n\n',
}
_JSON = {
    "openai": {"choices": [{"message": {"role": "assistant", "content": "hi"}}], "usage": {}},
    "anthropic": {"content": [{"type": "text", "text": "hi"}], "stop_reason": "end_turn",
                  "usage": {"input_tokens": 1, "output_tokens": 1}},
}


class _Scripted:
    """Each step: ``"ok"``, ``"network"``, or ``(status, retry_after, should_retry)``."""

    def __init__(self, steps, style):
        self.steps, self.style, self.calls = steps, style, 0

    def _step(self):
        step = self.steps[min(self.calls, len(self.steps) - 1)]
        self.calls += 1
        if step == "network":
            raise urllib.error.URLError("connection refused")
        if step != "ok":
            status, ra, sr = step
            raise ProviderError(status, "err", ra, sr)

    def post(self, url, headers, payload, timeout):
        self._step()
        return _JSON[self.style]

    def open(self, url, headers, payload, timeout):  # noqa: A003
        self._step()
        return io.BytesIO(_SSE[self.style].encode())


def _mk(style, steps, **extra):
    t = _Scripted(steps, style)
    client = create_client(base_url="http://example.invalid/v1", style=style, model="m", api_key="k",
                           retries=2, retry_base_ms=1, http_transport=t, **extra)
    return client, t


async def _collect(client):
    events = []
    try:
        async for ev in client.stream("hello"):
            events.append(ev)
    except Exception as e:  # noqa: BLE001
        return events, e
    return events, None


STYLES = pytest.mark.parametrize("style", ["openai", "anthropic"])


@STYLES
async def test_429_retry_after_zero_yields_retry_event(style):
    client, t = _mk(style, [(429, "0", None), "ok"])
    events, err = await _collect(client)
    assert err is None
    types = [e["type"] for e in events if e["type"] != "usage"]
    assert types[:2] == ["retry", "text"] and types[-1] == "done"
    assert events[0] == {"type": "retry", "attempt": 1, "max": 2, "delay_ms": 0, "status": 429, "reason": "HTTP 429"}
    assert t.calls == 2


@STYLES
async def test_network_retry_event_has_no_status(style):
    client, _ = _mk(style, ["network", "ok"])
    events, err = await _collect(client)
    assert err is None
    r = next(e for e in events if e["type"] == "retry")
    assert r["reason"] == "network" and "status" not in r
    assert r["attempt"] == 1 and r["max"] == 2 and 1 <= r["delay_ms"] < 102


@STYLES
async def test_400_no_event_and_error(style):
    client, t = _mk(style, [(400, None, None), "ok"])
    events, err = await _collect(client)
    assert isinstance(err, ProviderError)
    assert not [e for e in events if e["type"] == "retry"]
    assert t.calls == 1


@STYLES
async def test_should_retry_true_makes_400_retry(style):
    client, t = _mk(style, [(400, None, " TRUE "), "ok"])
    events, err = await _collect(client)
    assert err is None
    assert len([e for e in events if e["type"] == "retry"]) == 1
    assert t.calls == 2


@STYLES
async def test_should_retry_false_makes_503_terminal(style):
    client, t = _mk(style, [(503, None, "False"), "ok"])
    events, err = await _collect(client)
    assert isinstance(err, ProviderError)
    assert not [e for e in events if e["type"] == "retry"]
    assert t.calls == 1


@STYLES
async def test_on_error_sees_override_and_fail_wins(style):
    seen = []

    def on_error(info):
        seen.append(info)
        return "fail"

    client, t = _mk(style, [(400, None, "true"), "ok"], on_error=on_error)
    _, err = await _collect(client)
    assert isinstance(err, ProviderError)
    assert len(seen) == 1 and seen[0]["retryable"] is True
    assert t.calls == 1


@STYLES
async def test_run_unchanged(style):
    client, t = _mk(style, [(429, "0", None), "ok"])
    res = await client.run("hello")
    assert res.text == "hi"
    assert t.calls == 2


def test_urllib_transport_reads_x_should_retry_case_insensitively():
    from email.message import Message
    from unittest import mock
    from toolnexus.client import _open

    hdrs = Message()
    hdrs["X-Should-Retry"] = "false"
    err = urllib.error.HTTPError("http://x", 503, "busy", hdrs, io.BytesIO(b"busy"))
    with mock.patch("urllib.request.urlopen", side_effect=err):
        with pytest.raises(ProviderError) as ei:
            _open("http://x", {}, {}, 1.0)
    assert ei.value.should_retry == "false"
