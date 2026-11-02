"""Media generation (SPEC §7G) — conformance replay of ``conformance/media/`` plus
cancellation and ``RunResult.files``. Fixtures are read in place (never copied)."""
from __future__ import annotations

import asyncio
import http.server
import io
import json
import os
import threading

import pytest

from toolnexus import (
    MediaError,
    create_client,
    create_in_process_client,
    create_media,
    create_toolkit,
    load_media_style,
)

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MEDIA_DIR = os.path.join(REPO, "conformance", "media")
CASES = json.load(open(os.path.join(MEDIA_DIR, "cases.json"), encoding="utf-8"))["cases"]
SCHEMAS = json.load(open(os.path.join(MEDIA_DIR, "schemas.json"), encoding="utf-8"))
VERBS_JSON = json.load(open(os.path.join(MEDIA_DIR, "verbs.json"), encoding="utf-8"))


class FakeServer:
    """Serves ``script[]`` in order (last entry repeats) and records every request."""

    def __init__(self, script):
        self.script = script
        self.requests = []
        self.lock = threading.Lock()
        srv = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _handle(self):
                n = int(self.headers.get("Content-Length", 0) or 0)
                raw = self.rfile.read(n) if n else b""
                with srv.lock:
                    rec = {"method": self.command, "path": self.path, "headers": dict(self.headers)}
                    if raw:
                        rec["body"] = json.loads(raw)
                    srv.requests.append(rec)
                    i = min(len(srv.requests) - 1, len(srv.script) - 1)
                    entry = srv.script[i]
                data = (entry["rawBody"] if "rawBody" in entry else json.dumps(entry["body"])).encode()
                self.send_response(entry["status"])
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = _handle

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def _style(case, url):
    s = json.loads(json.dumps(case["style"]))
    s["baseUrl"] = s["baseUrl"].replace("{server}", url)
    return s


def _tool_name(verb):
    return next(v["tool"] for v in VERBS_JSON["verbs"] if v["verb"] == verb)


async def _run_function_face(case, monkeypatch):
    for k, v in (case.get("env") or {}).items():
        monkeypatch.setenv(k, v)
    monkeypatch.delenv("MEDIA_UNSET_VAR", raising=False)
    srv = FakeServer(case["script"])
    progress = []
    try:
        media = create_media(_style(case, srv.url), on_progress=lambda p: progress.append(p))
        try:
            res = await media.generate(case["verb"], case["args"])
            outcome = ("result", res.to_dict())
        except MediaError as e:
            outcome = ("error", str(e))
        return srv.requests, progress, outcome
    finally:
        srv.close()


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
async def test_media_conformance_function_face(case, monkeypatch):
    requests, progress, (kind, value) = await _run_function_face(case, monkeypatch)
    if "result" in case:
        assert kind == "result", value
        assert value == case["result"]
    else:
        assert kind == "error"
        assert value == case["error"]
    if "expectRequests" in case:
        got = []
        for r in requests:
            g = {"method": r["method"], "path": r["path"]}
            if "body" in r:
                g["body"] = r["body"]
            got.append(g)
        assert got == case["expectRequests"]
        if requests and requests[0]["method"] == "POST":
            assert requests[0]["headers"].get("Content-Type") == "application/json"
    for name, want in (case.get("expectHeaders") or {}).items():
        assert requests, "no requests recorded"
        for r in requests:
            assert r["headers"].get(name, "") == want
    if "expectProgress" in case:
        assert [p["status"] for p in progress] == case["expectProgress"]
        for p in progress:
            assert set(p) == {"verb", "jobId", "status"} and p["verb"] == case["verb"]


@pytest.mark.parametrize("case", [c for c in CASES if "tool" in c], ids=[c["name"] for c in CASES if "tool" in c])
async def test_media_conformance_tool_face(case, monkeypatch):
    for k, v in (case.get("env") or {}).items():
        monkeypatch.setenv(k, v)
    srv = FakeServer(case["script"])
    try:
        media = create_media(_style(case, srv.url))
        want = case["tool"]
        tool = next(t for t in media.tools() if t.name == want["name"])
        assert tool.source == "media"
        r = await tool.execute(case["args"])
    finally:
        srv.close()
    assert r.is_error == want["isError"]
    assert r.output == want["output"]
    assert r.parts == want.get("parts")
    assert r.metadata == want.get("metadata")


def test_all_cases_present():
    assert len(CASES) >= 19


def test_media_schemas():
    media = create_media(SCHEMAS["style"])
    got = [
        {"name": t.name, "description": t.description, "source": t.source, "inputSchema": t.input_schema}
        for t in media.tools()
    ]
    assert got == SCHEMAS["tools"]
    # Byte-level: key order too.
    assert json.dumps(got) == json.dumps(SCHEMAS["tools"])


def test_load_media_style_and_verb_table_matches_fixture(tmp_path):
    from toolnexus.media import VERBS

    assert VERBS == VERBS_JSON["verbs"]
    p = tmp_path / "s.json"
    p.write_text(json.dumps(SCHEMAS["style"]))
    assert load_media_style(str(p)) == SCHEMAS["style"]


async def test_typed_verbs_map_kwargs_and_provider_options():
    srv = FakeServer([{"status": 200, "body": {"u": "https://x/a.png"}}])
    try:
        style = {"baseUrl": srv.url, "verbs": {"image": {
            "mode": "sync", "kind": "image", "start": {"path": "/i"},
            "defaults": {"model": "m", "keep": 1},
            "args": {"prompt": "p", "aspectRatio": "ar"}, "resultPath": "u"}}}
        media = create_media([style])
        res = await media.image("cat", aspect_ratio="16:9", provider_options={"model": "m2", "x": True})
        assert res.status == "completed" and res.files[0].url == "https://x/a.png"
        assert srv.requests[0]["body"] == {"model": "m2", "keep": 1, "p": "cat", "ar": "16:9", "x": True}
        with pytest.raises(MediaError, match='image: argument "seed" not supported by this style'):
            await media.image("cat", seed=3)
    finally:
        srv.close()


async def test_first_style_declaring_verb_wins():
    a = {"baseUrl": "http://unused-a", "verbs": {"speech": {"mode": "sync", "kind": "audio", "start": {"path": "/"},
                                                            "args": {"text": "t"}, "resultPath": "u"}}}
    b = {"baseUrl": "http://unused-b", "verbs": {
        "speech": {"mode": "sync", "kind": "audio", "start": {"path": "/"}, "args": {"voice": "v"}, "resultPath": "u"},
        "transcribe": {"mode": "sync", "kind": "text", "start": {"path": "/"}, "args": {"audio": "a"}, "resultPath": "t"}}}
    tools = create_media([a, b]).tools()
    assert [t.name for t in tools] == ["generate_speech", "transcribe_audio"]
    assert list(tools[0].input_schema["properties"]) == ["text"]


async def test_cancel_stops_poll_loop():
    srv = FakeServer([{"status": 200, "body": {"id": "j"}}, {"status": 200, "body": {"st": "processing"}}])
    try:
        style = {"baseUrl": srv.url, "verbs": {"video": {
            "mode": "async", "kind": "video", "start": {"path": "/v"}, "args": {"prompt": "p"}, "idPath": "id",
            "poll": {"path": "/v/{id}", "statusPath": "st", "done": ["ok"], "failed": ["x"],
                     "intervalMs": 5, "timeoutMs": 600000},
            "resultPath": "u"}}}
        seen = asyncio.Event()
        media = create_media(style, on_progress=lambda p: seen.set())
        task = asyncio.create_task(media.video("wave"))
        await asyncio.wait_for(seen.wait(), 5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        n = len(srv.requests)
        await asyncio.sleep(0.05)
        assert len(srv.requests) == n, "polling continued after cancel"
    finally:
        srv.close()


class _Transport:
    """A recording transport double, injected through ``http_transport``."""

    def __init__(self):
        self.calls = []

    def request(self, method, url, headers, body, timeout):
        self.calls.append((method, url))
        return 200, json.dumps({"u": ["https://x/one.png", "https://x/two.mp3"]})


async def test_http_transport_injection():
    t = _Transport()
    media = create_media({"baseUrl": "http://nowhere/", "verbs": {"image": {
        "mode": "sync", "kind": "image", "start": {"path": "/i"}, "args": {"prompt": "p"}, "resultPath": "u"}}},
        http_transport=t)
    res = await media.image("x")
    assert t.calls == [("POST", "http://nowhere/i")]
    assert [f.mime_type for f in res.files] == ["image/png", "audio/mpeg"]


# --------------------------------------------------------------------------- #
# RunResult.files + tool-call parts (§8) — run and stream paths.
# --------------------------------------------------------------------------- #
def _media_for_run():
    t = _Transport()
    return create_media({"baseUrl": "http://nowhere", "verbs": {
        "image": {"mode": "sync", "kind": "image", "start": {"path": "/i"}, "args": {"prompt": "p"}, "resultPath": "u"},
        "transcribe": {"mode": "sync", "kind": "text", "start": {"path": "/t"}, "args": {"audio": "a"},
                       "resultPath": "u.0"}}}, http_transport=t)


EXPECTED_FILES = [
    {"type": "image", "mimeType": "image/png", "url": "https://x/one.png", "from": "generate_image", "callId": "call_0"},
    {"type": "image", "mimeType": "audio/mpeg", "url": "https://x/two.mp3", "from": "generate_image", "callId": "call_0"},
]


async def test_run_result_files_and_parts():
    turns = {"n": 0}

    def generate(req):
        turns["n"] += 1
        if turns["n"] == 1:
            return {"tool_calls": [{"name": "generate_image", "arguments": {"prompt": "cat"}},
                                   {"name": "transcribe_audio", "arguments": {"audio": "https://x/a.mp3"}}]}
        return {"content": "done"}

    tk = await create_toolkit(builtins=False, media=_media_for_run())
    try:
        assert [t.name for t in tk.tools()] == ["generate_image", "transcribe_audio"]
        r = await create_in_process_client(model="m", generate=generate).run("draw", toolkit=tk)
    finally:
        await tk.close()
    assert r.text == "done"
    assert r.files == EXPECTED_FILES
    assert r.tool_calls[0]["parts"] == [{k: v for k, v in f.items() if k not in ("from", "callId")} for f in EXPECTED_FILES]
    assert "parts" not in r.tool_calls[1]  # text kind: no parts
    assert r.tool_calls[1]["output"] == "https://x/one.png"


async def test_run_without_media_has_empty_files():
    tk = await create_toolkit(builtins=False)
    try:
        r = await create_in_process_client(model="m", generate=lambda req: {"content": "hi"}).run("x", toolkit=tk)
    finally:
        await tk.close()
    assert r.files == [] and r.text == "hi"


class _SSETransport:
    """Streams a scripted OpenAI SSE answer per turn."""

    def __init__(self):
        self.turn = 0

    def post(self, url, headers, payload, timeout):  # pragma: no cover — stream path only
        raise AssertionError("non-streaming not expected")

    def open(self, url, headers, payload, timeout):  # noqa: A003
        self.turn += 1
        if self.turn == 1:
            chunks = [{"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_0", "function": {
                "name": "generate_image", "arguments": json.dumps({"prompt": "cat"})}}]}}]}]
        else:
            chunks = [{"choices": [{"delta": {"content": "done"}}]}]
        data = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n"
        return io.BytesIO(data.encode())


async def test_stream_result_files():
    tk = await create_toolkit(builtins=False, media=_media_for_run())
    try:
        client = create_client(base_url="http://llm", style="openai", model="m", api_key="k",
                               http_transport=_SSETransport())
        final = None
        async for ev in client.stream("draw", toolkit=tk):
            if ev["type"] == "done":
                final = ev["result"]
    finally:
        await tk.close()
    assert final is not None and final.text == "done"
    assert final.files == EXPECTED_FILES
    assert final.tool_calls[0]["parts"][0]["url"] == "https://x/one.png"


async def test_tool_face_cancel_propagates():
    srv = FakeServer([{"status": 200, "body": {"id": "j"}}, {"status": 200, "body": {"st": "processing"}}])
    try:
        style = {"baseUrl": srv.url, "verbs": {"video": {
            "mode": "async", "kind": "video", "start": {"path": "/v"}, "args": {"prompt": "p"}, "idPath": "id",
            "poll": {"path": "/v/{id}", "statusPath": "st", "done": ["ok"], "failed": ["x"], "intervalMs": 5},
            "resultPath": "u"}}}
        tool = create_media(style).tools()[0]
        task = asyncio.create_task(tool.execute({"prompt": "wave"}))
        await asyncio.sleep(0.1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    finally:
        srv.close()


async def test_edge_rules_job_id_and_envelope_and_text():
    def t(body):
        class T:
            def request(self, *a):
                return 200, json.dumps(body)
        return T()
    async_style = {"baseUrl": "http://n", "verbs": {"image": {"mode": "async", "kind": "image", "start": {"path": "/"},
                   "args": {}, "idPath": "id", "poll": {"path": "/{id}", "statusPath": "s", "done": ["ok"], "failed": []},
                   "resultPath": "u"}}}
    for bad in (True, 1.5, {"a": 1}, None):
        with pytest.raises(MediaError, match="image: no job id at id"):
            await create_media(async_style, http_transport=t({"id": bad})).generate("image", {})
    env_style = {"baseUrl": "http://n", "errorEnvelope": {"codePath": "c", "okValues": [0]},
                 "verbs": {"image": {"mode": "sync", "kind": "image", "start": {"path": "/"}, "args": {}, "resultPath": "u"}}}
    for code, txt in (("E1", "E1"), (7.0, "7"), (True, "true"), ([1], "[1]")):
        with pytest.raises(MediaError) as ei:
            await create_media(env_style, http_transport=t({"c": code})).generate("image", {})
        assert str(ei.value) == f"API error {txt}: "
    img = {"baseUrl": "http://n", "verbs": {"image": {"mode": "sync", "kind": "image", "start": {"path": "/"}, "args": {}, "resultPath": "u"}}}
    r = await create_media(img, http_transport=t({"u": [["https://x/a.gif", 3, ""], [[None, "https://x/b"]]]})).generate("image", {})
    assert [f.url for f in r.files] == ["https://x/a.gif", "https://x/b"]
    txt = {"baseUrl": "http://n", "verbs": {"transcribe": {"mode": "sync", "kind": "text", "start": {"path": "/"}, "args": {}, "resultPath": "t"}}}
    assert (await create_media(txt, http_transport=t({"t": ""})).generate("transcribe", {})).text == ""
    assert (await create_media(txt, http_transport=t({"t": 4.0})).generate("transcribe", {})).text == "4"
    with pytest.raises(MediaError, match="transcribe: no result at t"):
        await create_media(txt, http_transport=t({"t": None})).generate("transcribe", {})
