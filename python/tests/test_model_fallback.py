"""§8 fallback_models — opt-in model fallback chain on overload (503/529). Hermetic."""
from __future__ import annotations

import json
import time

import pytest

from toolnexus import create_client, create_toolkit
from toolnexus.client import ProviderError

from test_client_resilience import _Server, _openai_text_body, _send_json


def _scripted(statuses_by_model):
    """Handler that answers by the request body's model; records the models seen."""
    seen: list[str] = []

    def handler(req):
        length = int(req.headers.get("Content-Length", 0))
        body = json.loads(req.rfile.read(length) or b"{}")
        m = body.get("model")
        seen.append(m)
        st = statuses_by_model.get(m, 200)
        if st != 200:
            _send_json(req, st, b'{"error":"busy"}', headers={"Retry-After": "0"})
        elif body.get("stream"):
            req.send_response(200)
            req.send_header("Content-Type", "text/event-stream")
            req.end_headers()
            for c in ['{"choices":[{"delta":{"content":"ok"}}]}', "[DONE]"]:
                req.wfile.write(f"data: {c}\n\n".encode())
                req.wfile.flush()
        else:
            _send_json(req, 200, _openai_text_body(f"from {m}"))

    return handler, seen


class _BodyServer(_Server):
    """_Server reads (and discards) the body before the handler; re-expose it."""

    def __init__(self, handler):
        super().__init__(handler)
        H = self._server.RequestHandlerClass

        def do_POST(req):  # noqa: N802
            handler(req)

        H.do_POST = do_POST


def _client(srv, **kw):
    kw.setdefault("retries", 0)
    return create_client(
        base_url=srv.base_url, style="openai", model="m1", api_key="sk-test",
        retry_base_ms=1, **kw,
    )


async def _run(statuses, **kw):
    handler, seen = _scripted(statuses)
    tk = await create_toolkit()
    events: list[dict] = []
    after: list[str] = []
    try:
        with _BodyServer(handler) as srv:
            c = _client(
                srv, on_metric=events.append,
                hooks={"after_llm": lambda ev: after.append(ev["model"])}, **kw,
            )
            try:
                res = await c.run("hi", tk)
            except ProviderError as e:
                res = e
    finally:
        await tk.close()
    return res, seen, events, after


async def test_529_switches_to_next_model_and_reports_it():
    res, seen, events, after = await _run({"m1": 529}, fallback_models=["m2"])
    assert seen == ["m1", "m2"]
    assert res.model == "m2" and res.text == "from m2"
    assert after == ["m2"]
    assert [e["model"] for e in events if e["event"] in ("llm", "run")] == ["m2", "m2"]


async def test_budget_spent_on_current_model_first_and_dups_skipped():
    res, seen, _, _ = await _run({"m1": 503}, retries=1, fallback_models=["", "m1", "m2"])
    assert seen == ["m1", "m1", "m2"]
    assert res.model == "m2"


async def test_429_never_switches():
    res, seen, _, _ = await _run({"m1": 429}, fallback_models=["m2"])
    assert isinstance(res, ProviderError) and res.status == 429
    assert seen == ["m1"]


async def test_on_error_fail_does_not_switch():
    res, seen, _, _ = await _run({"m1": 503}, retries=2, fallback_models=["m2"], on_error=lambda i: "fail")
    assert isinstance(res, ProviderError) and res.status == 503
    assert seen == ["m1"]


async def test_exhausted_chain_surfaces_last_error():
    res, seen, _, _ = await _run({"m1": 529, "m2": 503}, fallback_models=["m2"])
    assert isinstance(res, ProviderError) and res.status == 503
    assert seen == ["m1", "m2"]


@pytest.mark.parametrize("val", ["1", "TRUE"])
async def test_kill_switch_disables(monkeypatch, val):
    monkeypatch.setenv("TOOLNEXUS_DISABLE_MODEL_FALLBACK", val)
    res, seen, _, _ = await _run({"m1": 529}, fallback_models=["m2"])
    assert isinstance(res, ProviderError) and res.status == 529
    assert seen == ["m1"]


async def test_absent_option_unchanged():
    res, seen, _, _ = await _run({"m1": 529})
    assert isinstance(res, ProviderError) and res.status == 529
    assert seen == ["m1"]


async def test_stream_emits_fallback_before_text():
    handler, seen = _scripted({"m1": 529})
    tk = await create_toolkit()
    try:
        with _BodyServer(handler) as srv:
            c = _client(srv, fallback_models=["m2"])
            evs = [ev async for ev in c.stream("hi", tk)]
    finally:
        await tk.close()
    types = [e["type"] for e in evs]
    assert evs[0] == {"type": "fallback", "from": "m1", "to": "m2"}
    assert types.index("fallback") < types.index("text")
    assert evs[-1]["result"].model == "m2"
    assert seen == ["m1", "m2"]
