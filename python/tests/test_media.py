"""Media generation (SPEC §7G) — hermetic replay of EVERY case in ``conformance/media/``
(verbs, schemas, cases, clients, cli, routing) plus cancellation, model visibility and
``RunResult.files``. Fixtures are read in place (never copied)."""
from __future__ import annotations

import asyncio
import base64
import email
import email.policy
import http.server
import io
import json
import os
import re
import threading
import time

import pytest

from toolnexus import (
    A2EMediaClient,
    CodexMediaClient,
    ElevenLabsMediaClient,
    FalMediaClient,
    GrokMediaClient,
    MediaContext,
    MediaError,
    MediaResult,
    MediaFile,
    MediaUnavailableError,
    OpenAIMediaClient,
    OpenRouterMediaClient,
    ToolResult,
    create_client,
    create_in_process_client,
    create_media,
    create_toolkit,
    load_media_style,
    style_media_client,
    MediaStyle,
    generate_media,
)
import types

from toolnexus.client import Client, _run_files, _tool_message, _tool_result_block
from toolnexus.media import A2E_STYLE_TEXT, VERBS, VERBS_JSON_TEXT, media_config, model_parts

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MEDIA_DIR = os.path.join(REPO, "conformance", "media")
FAKE_CLI = os.path.join(MEDIA_DIR, "fake-cli", "fake-cli.sh")


def _load(name):
    with open(os.path.join(MEDIA_DIR, name), encoding="utf-8") as f:
        return json.load(f)


CASES = _load("cases.json")["cases"]
CLIENT_CASES = _load("clients.json")["cases"]
CLI_CASES = _load("cli.json")["cases"]
ROUTING = _load("routing.json")
SCHEMAS = _load("schemas.json")
VERBS_JSON = _load("verbs.json")

BUILTINS = {
    "a2e": A2EMediaClient, "elevenlabs": ElevenLabsMediaClient, "openai": OpenAIMediaClient,
    "openrouter": OpenRouterMediaClient, "fal": FalMediaClient, "grok": GrokMediaClient, "codex": CodexMediaClient,
}


def test_case_counts_exact():
    assert len(CASES) == 49
    assert len(CLIENT_CASES) == 76
    assert len(CLI_CASES) == 35
    assert len(ROUTING["cases"]) == 19
    assert len(ROUTING["schemaCases"]) == 1


# --------------------------------------------------------------------------- #
# Harness helpers
# --------------------------------------------------------------------------- #
def sub(obj, m):
    """Replace every ``{placeholder}`` of ``m`` in every string of ``obj``."""
    if isinstance(obj, str):
        for k, v in m.items():
            obj = obj.replace(k, v)
        return obj
    if isinstance(obj, list):
        return [sub(x, m) for x in obj]
    if isinstance(obj, dict):
        return {k: sub(v, m) for k, v in obj.items()}
    return obj


class FakeServer:
    """Serves ``script[]`` in order (last entry repeats) and records every request."""

    def __init__(self, script=None):
        self.script = script or []
        self.requests = []
        self.lock = threading.Lock()
        srv = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _handle(self):
                n = int(self.headers.get("Content-Length", 0) or 0)
                raw = self.rfile.read(n) if n else b""
                rec = {"method": self.command, "path": self.path,
                       "headers": [(k.lower(), v) for k, v in self.headers.items()], "raw": raw}
                ct = self.headers.get("Content-Type", "") or ""
                if raw and ct.startswith("application/json"):
                    rec["body"] = json.loads(raw)
                elif raw and ct.startswith("multipart/form-data"):
                    rec["multipart"] = _parse_multipart(ct, raw)
                with srv.lock:
                    srv.requests.append(rec)
                    entry = srv.script[min(len(srv.requests), len(srv.script)) - 1]
                if "bodyBase64" in entry:
                    data = base64.b64decode(entry["bodyBase64"])
                elif "rawBody" in entry:
                    data = entry["rawBody"].encode()
                else:
                    data = json.dumps(entry.get("body")).encode()
                self.send_response(entry["status"])
                for hk, hv in (entry.get("headers") or {}).items():
                    self.send_header(hk, hv)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = do_PUT = do_DELETE = _handle

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.httpd.daemon_threads = True
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def _parse_multipart(ct, raw):
    msg = email.message_from_bytes(b"Content-Type: " + ct.encode() + b"\r\n\r\n" + raw, policy=email.policy.HTTP)
    out = []
    for part in msg.iter_parts():
        name = part.get_param("name", header="content-disposition")
        fname = part.get_filename()
        payload = part.get_payload(decode=True)
        if fname is not None:
            out.append({"name": name, "filename": fname, "contentType": part.get_content_type(),
                        "bytesBase64": base64.b64encode(payload).decode()})
        else:
            out.append({"name": name, "value": payload.decode()})
    return out


def _hdr(rec, name):
    for k, v in rec["headers"]:
        if k == name.lower():
            return v
    return None


def check_requests(case, requests, cases_json=False):
    if "expectRequests" in case:
        exp = case["expectRequests"]
        assert len(requests) == len(exp), [(r["method"], r["path"]) for r in requests]
        for got, want in zip(requests, exp):
            assert got["method"] == want["method"]
            assert got["path"] == want["path"]
            if "body" in want:
                assert got.get("body") == want["body"]
            if "multipart" in want:
                assert got.get("multipart") == want["multipart"]
            for hk, hv in (want.get("headers") or {}).items():
                assert _hdr(got, hk) == hv, hk
            for hk in want.get("absentHeaders") or []:
                assert _hdr(got, hk) is None, hk
        if cases_json and requests and requests[0]["method"] == "POST":
            assert _hdr(requests[0], "Content-Type") == "application/json"
        for r in requests:  # §7G.8: polls/GETs send no body and no Content-Type
            if r["method"] == "GET":
                assert not r["raw"] and _hdr(r, "Content-Type") is None
    for hk, pat in (case.get("expectHeaderPatterns") or {}).items():  # D23
        assert requests, "no requests recorded"
        for r in requests:
            assert re.search(pat, _hdr(r, hk) or ""), (hk, _hdr(r, hk), r["path"])
    for hk, hv in (case.get("expectHeaders") or {}).items():
        assert requests, "no requests recorded"
        for r in requests:
            assert (_hdr(r, hk) or "") == hv, (hk, r["path"])


def check_result(got: MediaResult, want, client, verb, gdir):
    d = got.to_dict()
    for k in ("style", "model"):  # asserted only when the case names them (§7G.10b)
        if k not in want:
            d.pop(k, None)
        else:
            assert d.get(k) == want[k], (k, d.get(k), want[k])
    assert set(d) == set(want), (d, want)
    for k in ("text", "jobId", "status"):
        if k in want:
            assert d[k] == want[k]
    assert len(got.files) == len(want["files"])
    for f, w in zip(got.files, want["files"]):
        if "saved" in w:
            assert f.type == w["type"] and f.mime_type == w["mimeType"] and f.data is None
            assert os.path.isabs(f.path) and os.path.dirname(f.path) == gdir
            ext = re.escape(w["saved"]["ext"])
            assert re.fullmatch(rf"{client}-{re.escape(verb)}-[0-9]+-[0-9]+{ext}", os.path.basename(f.path)), f.path
            assert f.url == "file://" + f.path
            with open(f.path, "rb") as fh:
                assert base64.b64encode(fh.read()).decode() == w["saved"]["bytesBase64"]
        else:
            assert f.to_dict() == w


def check_error(e: BaseException, case):
    assert isinstance(e, MediaError), repr(e)
    if "error" in case:
        assert str(e) == case["error"]
    if "errorPrefix" in case:
        assert str(e).startswith(case["errorPrefix"]), str(e)
    if "status" in case:
        assert e.status == case["status"]
        assert isinstance(e, MediaUnavailableError) == (case["status"] == "unavailable")


def run_files_of(r: ToolResult, name: str):
    """RunResult.files for a transcript holding this one tool result — through the client's
    own call-record and run-files functions (§7G.12 D20)."""
    stub = types.SimpleNamespace(tool_tiers=None)
    stub._unwrap_call = lambda n, a: Client._unwrap_call(stub, n, a)
    rec = Client._tool_call_record(stub, name, {}, r, "call_0")
    return _run_files([rec])


def check_run_files(case, r: ToolResult, gdir):
    """D20 runFilePath / D21 noUsage on the tool face."""
    if case.get("noUsage"):
        assert "usage" not in (r.metadata or {})
    if "runFilePath" not in case:
        return
    files = run_files_of(r, case["tool"].get("name") or _tool_name(case["verb"]))
    for p in r.parts or []:
        assert "path" not in p  # §1B: a part never holds a path
    if case["runFilePath"]:
        assert files and len(files) == len(r.parts or [])
        lines = r.output.split(": ", 1)[1].split("\n")  # the files' paths, in order
        for f, line in zip(files, lines):
            assert os.path.isabs(f["path"]) and f["path"] == line and os.path.isfile(f["path"]), (f, line)
    else:
        assert all("path" not in f for f in files)


def check_tool(r: ToolResult, want, gdir=None):
    assert r.is_error == want["isError"], r.output
    if "output" in want:
        assert r.output == want["output"]
    if "outputPattern" in want:
        pat = want["outputPattern"].replace("{dir}", re.escape(gdir or ""))
        assert re.search(pat, r.output), (pat, r.output)
    if "outputPrefix" in want:
        assert r.output.startswith(want["outputPrefix"]), r.output
    if "parts" in want:
        assert r.parts == want["parts"]
    if want.get("noParts") or "parts" not in want:
        assert r.parts is None
    if "metadata" in want:
        assert r.metadata == want["metadata"]
    if "modelParts" in want:
        # §7G.6 model visibility, through the real assembly functions (§8A).
        assert model_parts(r) == want["modelParts"]
        msg = _tool_message("call_0", r_name(want), r)
        assert msg.get("parts", []) == want["modelParts"]
        assert ("name" in msg) == bool(want["modelParts"])
        assert _tool_result_block("tu_0", r).get("parts", []) == want["modelParts"]


def r_name(want):
    return want.get("name", "tool")


def _progress_sink():
    events = []

    def cb(ev):
        events.append(ev)

    return events, cb


def _with_progress(config, cb):
    cfg = media_config(config)
    cfg.loop.on_progress = cb
    return cfg


def check_progress(case, events, client):
    if "expectProgress" in case:
        assert [e["status"] for e in events] == case["expectProgress"]
    for e in events:
        assert e["verb"] == case["verb"] and e["client"] == client
        assert isinstance(e["elapsedMs"], int)
        assert set(e) <= {"verb", "client", "jobId", "status", "elapsedMs"}


def _env(case, monkeypatch, m=None):
    for k, v in (case.get("env") or {}).items():
        monkeypatch.setenv(k, sub(v, m or {}))
    for k in case.get("unsetEnv") or []:
        monkeypatch.delenv(k, raising=False)
    monkeypatch.delenv("MEDIA_UNSET_VAR", raising=False)


# --------------------------------------------------------------------------- #
# verbs.json + a2e.json embedded verbatim; schemas.json
# --------------------------------------------------------------------------- #
def test_verbs_json_embedded_verbatim():
    with open(os.path.join(MEDIA_DIR, "verbs.json"), encoding="utf-8") as f:
        assert VERBS_JSON_TEXT == f.read()
    assert VERBS == VERBS_JSON["verbs"]


def test_a2e_preset_embedded_verbatim():
    with open(os.path.join(REPO, "examples", "media-styles", "a2e.json"), encoding="utf-8") as f:
        assert A2E_STYLE_TEXT == f.read()
    assert A2EMediaClient().name == "a2e"


def test_media_schemas():
    media = create_media(style_media_client(SCHEMAS["style"]))
    got = [{"name": t.name, "description": t.description, "source": t.source, "inputSchema": t.input_schema}
           for t in media.tools()]
    assert got == SCHEMAS["tools"]
    assert json.dumps(got) == json.dumps(SCHEMAS["tools"])  # key order too


def test_load_media_style(tmp_path):
    p = tmp_path / "s.json"
    p.write_text(json.dumps(SCHEMAS["style"]))
    assert load_media_style(str(p)) == SCHEMAS["style"]
    bad = tmp_path / "b.json"
    bad.write_text(json.dumps({"baseUrl": "x", "verbs": {"talking": {}}}))
    with pytest.raises(MediaError, match='style: unknown verb "talking"'):
        load_media_style(str(bad))


# --------------------------------------------------------------------------- #
# cases.json — the style engine
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
async def test_style_cases(case, monkeypatch):
    _env(case, monkeypatch)
    # function face
    srv = FakeServer()
    m = {"{server}": srv.url}
    srv.script = sub(case["script"], m)
    events, cb = _progress_sink()
    try:
        try:
            client = style_media_client(sub(case["style"], m), _with_progress(case.get("config"), cb))
            res = await create_media(client).generate(case["verb"], case["args"])
            outcome = ("result", res)
        except MediaError as e:
            outcome = ("error", e)
    finally:
        srv.close()
    if "result" in case:
        assert outcome[0] == "result", str(outcome[1])
        check_result(outcome[1], case["result"], "style", case["verb"], None)
    else:
        assert outcome[0] == "error", outcome[1]
        check_error(outcome[1], case)
    if case.get("styleError"):
        assert srv.requests == []
    check_requests(case, srv.requests, cases_json=True)
    check_progress(case, events, case["style"].get("name") or "style")

    if "tool" not in case:
        return
    srv = FakeServer()
    m = {"{server}": srv.url}
    srv.script = sub(case["script"], m)
    try:
        media = create_media(style_media_client(sub(case["style"], m), case.get("config")))
        tool = next(t for t in media.tools() if t.name == case["tool"].get("name", _tool_name(case["verb"])))
        assert tool.source == "media"
        r = await tool.execute(case["args"])
    finally:
        srv.close()
    check_tool(r, case["tool"])


# --------------------------------------------------------------------------- #
# clients.json — the built-in API clients
# --------------------------------------------------------------------------- #
async def _client_face(case, tmp, monkeypatch, face):
    gdir = os.path.join(tmp, f"dir-{face}")
    inputs = os.path.join(tmp, f"inputs-{face}")
    os.makedirs(gdir)
    os.makedirs(inputs)
    for name, b64 in (case.get("inputFiles") or {}).items():
        with open(os.path.join(inputs, name), "wb") as f:
            f.write(base64.b64decode(b64))
    srv = FakeServer()
    m = {"{server}": srv.url, "{dir}": gdir, "{inputs}": inputs}
    c = sub(case, m)
    srv.script = c["script"]
    _env(c, monkeypatch)
    events, cb = _progress_sink()
    try:
        client = BUILTINS[c["client"]](_with_progress(c.get("config"), cb))
        media = create_media(client)
        if face == "function":
            try:
                out = ("result", await media.generate(c["verb"], c["args"], ctx=_ctx(c)))
            except MediaError as e:
                out = ("error", e)
        else:
            tool = next(t for t in media.tools() if t.name == (c["tool"].get("name") or _tool_name(c["verb"])))
            out = ("tool", await tool.execute(c["args"]))
    finally:
        srv.close()
    return c, gdir, srv.requests, events, out


def _ctx(c):
    """D12: ctxTimeoutMs ⇒ ctx.deadline = now + ctxTimeoutMs (function face only)."""
    if "ctxTimeoutMs" not in c:
        return None
    return MediaContext(deadline=time.time() + c["ctxTimeoutMs"] / 1000.0)


def _tool_name(verb):
    return next(v["tool"] for v in VERBS if v["verb"] == verb)


@pytest.mark.parametrize("case", CLIENT_CASES, ids=[c["name"] for c in CLIENT_CASES])
async def test_client_cases(case, monkeypatch, tmp_path):
    t0 = time.monotonic()
    c, gdir, requests, events, (kind, value) = await _client_face(case, str(tmp_path), monkeypatch, "function")
    if "maxElapsedMs" in c:
        assert (time.monotonic() - t0) * 1000 < c["maxElapsedMs"]
    if "result" in c:
        assert kind == "result", str(value)
        check_result(value, c["result"], c["client"], c["verb"], gdir)
        if c.get("noUsage"):
            assert value.usage is None
    else:
        assert kind == "error", value
        check_error(value, c)
    check_requests(c, requests)
    check_progress(c, events, c["client"])
    if "tool" in case and "ctxTimeoutMs" not in case:
        c, gdir, requests, _, (_, r) = await _client_face(case, str(tmp_path), monkeypatch, "tool")
        check_tool(r, c["tool"], gdir)
        check_requests(c, requests)
        check_run_files(c, r, gdir)


# --------------------------------------------------------------------------- #
# cli.json — the CLI clients over the portable fake CLI
# --------------------------------------------------------------------------- #
async def _cli_face(case, tmp, monkeypatch, face):
    out = os.path.join(tmp, f"out-{face}")
    gdir = os.path.join(tmp, f"dir-{face}")
    os.makedirs(out)
    os.makedirs(gdir)
    fixture = os.path.join(tmp, f"fixture-{face}.jsonl")
    with open(fixture, "w", encoding="utf-8") as f:
        f.write("".join(line + "\n" for line in case["fixture"]))
    m = {"{out}": out, "{dir}": gdir}
    c = sub({k: v for k, v in case.items() if k != "fixture"}, m)
    _env(c, monkeypatch)
    cfg = dict(c.get("config") or {})
    cfg["command"] = c.get("command") or ["/bin/sh", FAKE_CLI, fixture, out]
    events, cb = _progress_sink()
    style = _cli_style(c)
    cfg = _with_progress(cfg, cb)
    media = create_media(load_media_style(style, cfg))
    t0 = time.monotonic()
    if face == "function":
        try:
            res = ("result", await media.generate(c["verb"], c["args"], ctx=_ctx(c)))
        except MediaError as e:
            res = ("error", e)
    else:
        tool = next(t for t in media.tools() if t.name == _tool_name(c["verb"]))
        res = ("tool", await tool.execute(c["args"]))
    return c, out, gdir, events, res, (time.monotonic() - t0) * 1000


def _cli_style(c):
    """Every cli.json case names its preset in ``style``."""
    return c["style"]


def _cli_name(c):
    return {"xai": "grok", "openai": "codex", "google": "agy"}[_cli_style(c).split("-")[0]]


def _check_cli_side_effects(c, out):
    argv_file = os.path.join(out, "argv.txt")
    if c.get("expectNoSpawn"):
        assert not os.path.exists(argv_file)
    if "expectArgv" in c:
        with open(argv_file, encoding="utf-8") as f:
            got = f.read().split("\n")[:-1]
        with open(os.path.join(out, "cwd.txt"), encoding="utf-8") as f:
            cwd = f.read().strip()
        want = c["expectArgv"]
        assert len(got) == len(want), got
        for g, w in zip(got, want):
            if w == "{cwd}":
                assert g == cwd  # D17: byte for byte with the fake's `pwd -P`
            else:
                assert g == w
    for k, v in (c.get("expectEnv") or {}).items():
        with open(os.path.join(out, f"env-{k}.txt"), encoding="utf-8") as f:
            assert f.read() == v


@pytest.mark.parametrize("case", CLI_CASES, ids=[c["name"] for c in CLI_CASES])
async def test_cli_cases(case, monkeypatch, tmp_path):
    c, out, gdir, events, (kind, value), ms = await _cli_face(case, str(tmp_path), monkeypatch, "function")
    name = _cli_name(c)
    if "result" in c:
        assert kind == "result", str(value)
        check_result(value, c["result"], name, c["verb"], gdir)
        if c.get("noUsage"):
            assert value.usage is None
    else:
        assert kind == "error", value
        check_error(value, c)
    if c.get("oneShot"):
        cfg = dict(c.get("config") or {})
        cfg["command"] = c.get("command") or ["/nonexistent/toolnexus-must-not-run"]
        one = await generate_media(_cli_style(c), c["verb"], c["args"], cfg)
        check_result(one, c["result"], name, c["verb"], gdir)
    _check_cli_side_effects(c, out)
    for p in c.get("expectExists") or []:
        assert os.path.exists(p), p
    for p in c.get("expectMissing") or []:
        assert not os.path.exists(p), p
    if "maxElapsedMs" in c:
        assert ms < c["maxElapsedMs"]
    check_progress(c, events, name)
    if "tool" in case and "ctxTimeoutMs" not in case:
        c, out, gdir, _, (_, r), _ = await _cli_face(case, str(tmp_path), monkeypatch, "tool")
        check_tool(r, c["tool"], gdir)
        check_run_files(c, r, gdir)
        _check_cli_side_effects(c, out)


CLI_AVAIL_CASES = _load("cli.json")["availabilityCases"]


def test_cli_availability_case_count():
    assert len(CLI_AVAIL_CASES) == 7


@pytest.mark.parametrize("case", CLI_AVAIL_CASES, ids=[c["name"] for c in CLI_AVAIL_CASES])
def test_cli_availability_cases(case, monkeypatch, tmp_path):
    d = os.path.realpath(str(tmp_path))
    c = sub(case, {"{dir}": d})
    for k, v in (c.get("env") or {}).items():
        monkeypatch.setenv(k, v)
    for k in c.get("unsetEnv") or []:
        monkeypatch.delenv(k, raising=False)
    for p in c.get("createFiles") or []:
        open(p, "wb").close()
    if isinstance(c["style"], str):
        style = load_media_style(c["style"], c.get("config"))
    else:
        style = MediaStyle(c["style"], config=c.get("config"))
    t0 = time.monotonic()
    got = style.available()
    assert time.monotonic() - t0 < 1.0
    assert got == c["expect"]


CLI_VERB_CASES = _load("cli.json")["verbCases"]


def test_cli_verb_case_count():
    assert len(CLI_VERB_CASES) == 8


@pytest.mark.parametrize("vc", CLI_VERB_CASES, ids=[v["name"] for v in CLI_VERB_CASES])
async def test_cli_verb_cases(vc):
    srcs = []
    for s in vc["styles"]:
        if isinstance(s, str):
            srcs.append(load_media_style(s, command=["/nonexistent/toolnexus-must-not-run"]))
        else:
            srcs.append(MediaStyle(s))
    m = create_media(srcs)
    assert m.verbs() == vc["expectVerbs"]
    assert [t.name for t in m.tools()] == vc["expectTools"]
    with pytest.raises(MediaError) as ei:
        await m.generate(vc["unsupported"], {})
    assert str(ei.value) == vc["error"]


# --------------------------------------------------------------------------- #
# routing.json — createMedia routing and tools, with test-only custom clients
# --------------------------------------------------------------------------- #
class FakeClient:
    """A custom MediaClient with only ``supports``/``args``/``generate`` (the router falls
    back to ``generate`` when a verb method is absent)."""

    def __init__(self, spec, calls):
        self.name = spec["name"]
        self.spec = spec
        self.calls = calls

    def supports(self, verb):
        return verb in self.spec["verbs"]

    def args(self, verb):
        return self.spec["verbs"][verb].get("args")

    async def generate(self, verb, args, ctx):
        self.calls.append({"client": self.name, "verb": verb, "args": args})
        out = self.spec["verbs"][verb]["outcome"]
        if "error" in out:
            if out["status"] == "unavailable":
                raise MediaUnavailableError(out["error"])
            raise MediaError(out["error"], out["status"])
        r = out["result"]
        files = [MediaFile(f["type"], f["mimeType"], url=f.get("url"), path=f.get("path"), data=f.get("data"))
                 for f in r["files"]]
        return MediaResult(files=files, text=r.get("text"), job_id=r.get("jobId"), status=r["status"], usage=r.get("usage"))


@pytest.mark.parametrize("case", ROUTING["cases"], ids=[c["name"] for c in ROUTING["cases"]])
async def test_routing_cases(case):
    calls = []
    media = create_media([FakeClient(s, calls) for s in case["clients"]])
    try:
        res = await media.generate(case["verb"], case["args"])
        assert "result" in case, res
        got = res.to_dict()
        for k in ("style", "model"):  # asserted only when the case names them (§7G.10b)
            if k not in case["result"]:
                got.pop(k, None)
        assert got == case["result"]
    except MediaError as e:
        assert "result" not in case, str(e)
        check_error(e, case)
    if "expectCalls" in case:
        assert calls == case["expectCalls"]
    if "tool" in case:
        calls = []
        media = create_media([FakeClient(s, calls) for s in case["clients"]])
        tool = next(t for t in media.tools() if t.name == case["tool"].get("name", _tool_name(case["verb"])))
        check_tool(await tool.execute(case["args"]), case["tool"])


@pytest.mark.parametrize("case", ROUTING["schemaCases"], ids=[c["name"] for c in ROUTING["schemaCases"]])
def test_routing_schema_cases(case):
    tools = create_media([FakeClient(s, []) for s in case["clients"]]).tools()
    got = [{"name": t.name, "description": t.description, "source": t.source, "inputSchema": t.input_schema}
           for t in tools]
    assert got == case["tools"]
    assert json.dumps(got) == json.dumps(case["tools"])


def test_routing_empty_clients():
    with pytest.raises(MediaError) as ei:
        create_media([])
    assert str(ei.value) == ROUTING["emptyClients"]["error"]


# --------------------------------------------------------------------------- #
# Port-specific behaviour: typed verbs, ctx, cancellation, transport seam
# --------------------------------------------------------------------------- #
class VerbMethodClient:
    """A custom client implementing per-verb methods (the Protocol's other shape)."""

    name = "mine"

    def __init__(self):
        self.seen = []

    def supports(self, verb):
        return verb in ("talk", "music")

    async def talk(self, args, ctx):
        self.seen.append(("talk", args, ctx))
        if ctx.progress:
            ctx.progress({"verb": "talk", "client": self.name, "status": "x", "elapsedMs": 0})
        return MediaResult(files=[MediaFile("file", "video/mp4", url="https://x/t.mp4")], status="completed")

    async def music(self, args, ctx):
        self.seen.append(("music", args, ctx))
        return MediaResult(files=[MediaFile("audio", "audio/mpeg", url="https://x/m.mp3")], status="completed")


async def test_custom_client_verb_methods_and_ctx():
    c = VerbMethodClient()
    media = create_media(c)
    got = []
    r = await media.talk("https://x/face.png", text="hello", deadline=time.time() + 5, progress=got.append)
    assert r.files[0].url == "https://x/t.mp4"
    verb, args, ctx = c.seen[0]
    assert args == {"image": "https://x/face.png", "text": "hello"}
    assert isinstance(ctx, MediaContext) and ctx.deadline is not None and got[0]["status"] == "x"
    await media.music("lofi", instrumental=False, provider_options={"k": 1})
    assert c.seen[1][1] == {"prompt": "lofi", "instrumental": False}
    assert c.seen[1][2].provider_options == {"k": 1}
    assert media.supports("talk") and not media.supports("image")


def test_create_media_rejects_raw_style_dict():
    with pytest.raises(TypeError):
        create_media(SCHEMAS["style"])


async def test_typed_verbs_map_kwargs_and_provider_options():
    srv = FakeServer([{"status": 200, "body": {"u": "https://x/a.png"}}])
    try:
        style = {"baseUrl": srv.url, "verbs": {"image": {
            "mode": "sync", "kind": "image", "start": {"path": "/i"},
            "defaults": {"model": "m", "keep": 1},
            "args": {"prompt": "p", "aspectRatio": "ar"}, "resultPath": "u"}}}
        media = create_media([style_media_client(style)])
        res = await media.image("cat", aspect_ratio="16:9", provider_options={"model": "m2", "x": True})
        assert res.status == "completed" and res.files[0].url == "https://x/a.png"
        assert srv.requests[0]["body"] == {"model": "m2", "keep": 1, "p": "cat", "ar": "16:9", "x": True}
        with pytest.raises(MediaError, match='media: no client available for "image": style: argument "seed" not supported'):
            await media.image("cat", seed=3)
    finally:
        srv.close()


async def test_ctx_progress_replaces_on_progress_never_both():
    srv = FakeServer([{"status": 200, "body": {"id": "j"}}, {"status": 200, "body": {"id": "j", "st": "ok", "u": "https://x/v"}}])
    try:
        style = {"baseUrl": srv.url, "verbs": {"video": {
            "mode": "async", "kind": "video", "start": {"path": "/v"}, "args": {"prompt": "p"}, "idPath": "id",
            "poll": {"path": "/v/{id}", "statusPath": "st", "done": ["ok"], "intervalMs": 1}, "resultPath": "u"}}}
        loop_events, ctx_events = [], []
        media = create_media(style_media_client(style, loop={"on_progress": loop_events.append}))
        await media.video("wave", progress=ctx_events.append)
        assert [e["status"] for e in ctx_events] == ["ok"] and loop_events == []
        assert ctx_events[0]["jobId"] == "j" and ctx_events[0]["client"] == "style"
        await media.video("wave")
        assert [e["status"] for e in loop_events] == ["ok"]
    finally:
        srv.close()


async def test_cancel_stops_poll_loop():
    srv = FakeServer([{"status": 200, "body": {"id": "j"}}, {"status": 200, "body": {"st": "processing"}}])
    try:
        style = {"baseUrl": srv.url, "verbs": {"video": {
            "mode": "async", "kind": "video", "start": {"path": "/v"}, "args": {"prompt": "p"}, "idPath": "id",
            "poll": {"path": "/v/{id}", "statusPath": "st", "done": ["ok"], "failed": ["x"],
                     "intervalMs": 5, "timeoutMs": 600000},
            "resultPath": "u"}}}
        seen = asyncio.Event()
        media = create_media(style_media_client(style, loop={"onProgress": lambda p: seen.set()}))
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


async def test_tool_face_cancel_propagates():
    srv = FakeServer([{"status": 200, "body": {"id": "j"}}, {"status": 200, "body": {"st": "processing"}}])
    try:
        style = {"baseUrl": srv.url, "verbs": {"video": {
            "mode": "async", "kind": "video", "start": {"path": "/v"}, "args": {"prompt": "p"}, "idPath": "id",
            "poll": {"path": "/v/{id}", "statusPath": "st", "done": ["ok"], "failed": ["x"], "intervalMs": 5},
            "resultPath": "u"}}}
        tool = create_media(style_media_client(style)).tools()[0]
        task = asyncio.create_task(tool.execute({"prompt": "wave"}))
        await asyncio.sleep(0.1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    finally:
        srv.close()


async def test_cli_cancel_kills_process(tmp_path):
    fixture = tmp_path / "f.jsonl"
    fixture.write_text('{"type":"text","data":"thinking"}\n#hang\n')
    out = tmp_path / "out"
    client = GrokMediaClient(command=["/bin/sh", FAKE_CLI, str(fixture), str(out)], gather={"dir": str(tmp_path)})
    events = []
    task = asyncio.create_task(create_media(client).image("x", progress=events.append))
    for _ in range(200):
        if (out / "cwd.txt").exists():
            break
        await asyncio.sleep(0.02)
    await asyncio.sleep(0.2)
    t0 = time.monotonic()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert time.monotonic() - t0 < 2


class _Transport:
    """A recording transport double, injected through ``http_client``."""

    def __init__(self):
        self.calls = []

    def request(self, method, url, headers, body, timeout):
        self.calls.append((method, url))
        return 200, json.dumps({"u": ["https://x/one.png", "https://x/two.mp3"]})


async def test_http_client_injection():
    t = _Transport()
    media = create_media(style_media_client({"baseUrl": "http://nowhere/", "verbs": {"image": {
        "mode": "sync", "kind": "image", "start": {"path": "/i"}, "args": {"prompt": "p"}, "resultPath": "u"}}},
        http_client=t))
    res = await media.image("x")
    assert t.calls == [("POST", "http://nowhere/i")]
    assert [f.mime_type for f in res.files] == ["image/png", "audio/mpeg"]


async def test_builtin_url_override_trailing_slash_and_inline_bytes():
    class T:
        def __init__(self):
            self.urls = []

        def request(self, method, url, headers, body, timeout):
            self.urls.append(url)
            return 200, b"\x00\x01MP3", {"Content-Type": "audio/mpeg"}

    t = T()
    r = await create_media(OpenAIMediaClient(key="FAKE", url="http://compat/v1/", http_client=t,
                                             gather={"mode": "inline"})).speech("hi")
    assert t.urls == ["http://compat/v1/audio/speech"]
    assert r.files[0].data == base64.b64encode(b"\x00\x01MP3").decode()


async def test_edge_rules_job_id_and_envelope_and_text():
    def t(body):
        class T:
            def request(self, *a):
                return 200, json.dumps(body)
        return T()

    def mk(style, body):
        return create_media(style_media_client(style, http_client=t(body)))

    async_style = {"baseUrl": "http://n", "verbs": {"image": {"mode": "async", "kind": "image", "start": {"path": "/"},
                   "args": {}, "idPath": "id", "poll": {"path": "/{id}", "statusPath": "s", "done": ["ok"], "failed": []},
                   "resultPath": "u"}}}
    for bad in (True, 1.5, {"a": 1}, None):
        with pytest.raises(MediaError, match="image: no job id at id"):
            await mk(async_style, {"id": bad}).generate("image", {})
    env_style = {"baseUrl": "http://n", "errorEnvelope": {"codePath": "c", "okValues": [0]},
                 "verbs": {"image": {"mode": "sync", "kind": "image", "start": {"path": "/"}, "args": {}, "resultPath": "u"}}}
    for code, txt in (("E1", "E1"), (7.0, "7"), (True, "true"), ([1], "[1]")):
        with pytest.raises(MediaError) as ei:
            await mk(env_style, {"c": code}).generate("image", {})
        assert str(ei.value) == f"API error {txt}: "
    img = {"baseUrl": "http://n", "verbs": {"image": {"mode": "sync", "kind": "image", "start": {"path": "/"}, "args": {}, "resultPath": "u"}}}
    r = await mk(img, {"u": [["https://x/a.gif", 3, ""], [[None, "https://x/b"]]]}).generate("image", {})
    assert [f.url for f in r.files] == ["https://x/a.gif", "https://x/b"]
    txt = {"baseUrl": "http://n", "verbs": {"transcribe": {"mode": "sync", "kind": "text", "start": {"path": "/"}, "args": {"audio": "a"}, "resultPath": "t"}}}
    assert (await mk(txt, {"t": ""}).generate("transcribe", {"audio": "x"})).text == ""
    assert (await mk(txt, {"t": 4.0}).generate("transcribe", {"audio": "x"})).text == "4"
    with pytest.raises(MediaError, match="transcribe: no result at t"):
        await mk(txt, {"t": None}).generate("transcribe", {"audio": "x"})


def test_media_tools_are_concurrency_safe():
    """Media tools are concurrency-safe for every argument set (§7G.6, §8 Tool scheduling)."""
    from toolnexus import is_concurrency_safe

    style = {"baseUrl": "http://unused", "verbs": {v["verb"]: {
        "mode": "sync", "kind": "text", "start": {"path": "/"}, "args": {a["name"]: a["name"] for a in v["args"]},
        "resultPath": "u"} for v in VERBS}}
    tools = create_media(style_media_client(style)).tools()
    assert len(tools) == 7
    for t in tools:
        assert is_concurrency_safe(t, {}), t.name


# §7G.8 Redirects: a cross-origin redirect never carries the style headers.
async def test_cross_origin_redirect_drops_style_headers():
    b = FakeServer([{"status": 200, "body": {"u": "https://x/r.png"}}])

    class Redirect(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
            self.send_response(302)
            self.send_header("Location", b.url + "/landed")
            self.send_header("Content-Length", "0")
            self.end_headers()

    a = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
    threading.Thread(target=a.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()
    try:
        media = create_media(style_media_client({
            "baseUrl": f"http://127.0.0.1:{a.server_address[1]}",
            "headers": {"Authorization": "Bearer FAKE", "X-Style": "s"},
            "verbs": {"image": {"mode": "sync", "kind": "image", "start": {"path": "/i"},
                                "args": {"prompt": "p"}, "resultPath": "u"}}}))
        r = await media.image("x")
        assert r.files[0].url == "https://x/r.png"
        assert len(b.requests) == 1
        assert _hdr(b.requests[0], "authorization") is None and _hdr(b.requests[0], "x-style") is None
    finally:
        a.shutdown()
        a.server_close()
        b.close()


# §7G.8 Per-request timeout: a server that never answers is bounded by the job deadline.
async def test_hung_server_bounded_by_deadline():
    release = threading.Event()

    class Hang(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _reply(self, data):
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
            if self.path == "/a":
                return self._reply(b'{"id":"h1"}')
            release.wait(10)

        def do_GET(self):
            release.wait(10)

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Hang)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()
    url = f"http://127.0.0.1:{srv.server_address[1]}"
    try:
        sync = create_media(style_media_client({"baseUrl": url, "verbs": {"image": {
            "mode": "sync", "kind": "image", "start": {"path": "/s"}, "args": {"prompt": "p"},
            "poll": {"timeoutMs": 1000}, "resultPath": "u"}}}))
        t0 = time.monotonic()
        with pytest.raises(MediaError, match=r"^image: timed out after 1000ms$") as ei:
            await sync.image("x")
        assert ei.value.status == "timeout"
        assert time.monotonic() - t0 < 4
        asy = create_media(style_media_client({"baseUrl": url, "verbs": {"image": {
            "mode": "async", "kind": "image", "start": {"path": "/a"}, "args": {"prompt": "p"}, "idPath": "id",
            "poll": {"path": "/p/{id}", "statusPath": "st", "done": ["ok"], "intervalMs": 1, "timeoutMs": 1000},
            "resultPath": "u"}}}))
        with pytest.raises(MediaError, match=r"^job h1 timed out after 1000ms$") as ei:
            await asy.image("x")
        assert ei.value.status == "timeout" and ei.value.job_id == "h1"
    finally:
        release.set()
        srv.shutdown()
        srv.server_close()


def test_dotset_through_array_with_word_segment():
    from toolnexus.media import _set

    body = {"a": []}
    _set(body, "a.x.y", 1)
    assert body == {"a": {"x": {"y": 1}}}


# --------------------------------------------------------------------------- #
# RunResult.files + tool-call parts (§8) + model visibility (§7G.6) — run and stream paths.
# --------------------------------------------------------------------------- #
def _media_for_run():
    t = _Transport()
    return create_media(style_media_client({"baseUrl": "http://nowhere", "verbs": {
        "image": {"mode": "sync", "kind": "image", "start": {"path": "/i"}, "args": {"prompt": "p"}, "resultPath": "u"},
        "video": {"mode": "sync", "kind": "video", "start": {"path": "/v"}, "args": {"prompt": "p"}, "resultPath": "u"},
        "transcribe": {"mode": "sync", "kind": "text", "start": {"path": "/t"}, "args": {"audio": "a"},
                       "resultPath": "u.0"}}}, http_client=t))


EXPECTED_FILES = [
    {"type": "image", "mimeType": "image/png", "url": "https://x/one.png", "from": "generate_image", "callId": "call_0"},
    {"type": "image", "mimeType": "audio/mpeg", "url": "https://x/two.mp3", "from": "generate_image", "callId": "call_0"},
]


async def test_run_result_files_parts_and_model_visibility():
    turns = {"n": 0}
    seen = []

    def generate(req):
        turns["n"] += 1
        seen.append(req)
        if turns["n"] == 1:
            return {"tool_calls": [{"name": "generate_image", "arguments": {"prompt": "cat"}},
                                   {"name": "transcribe_audio", "arguments": {"audio": "https://x/a.mp3"}},
                                   {"name": "generate_video", "arguments": {"prompt": "wave"}}]}
        return {"content": "done"}

    tk = await create_toolkit(builtins=False, media=_media_for_run())
    try:
        assert [t.name for t in tk.tools()] == ["generate_image", "generate_video", "transcribe_audio"]
        r = await create_in_process_client(model="m", generate=generate).run("draw", toolkit=tk)
    finally:
        await tk.close()
    assert r.text == "done"
    assert r.files[:2] == EXPECTED_FILES
    video_files = [f for f in r.files if f["from"] == "generate_video"]
    assert [f["type"] for f in video_files] == ["file", "file"]
    assert r.tool_calls[0]["parts"] == [{k: v for k, v in f.items() if k not in ("from", "callId")} for f in EXPECTED_FILES]
    assert "parts" not in r.tool_calls[1]  # text verb: no parts
    assert r.tool_calls[1]["output"] == "https://x/one.png"
    assert len(r.tool_calls[2]["parts"]) == 2  # video parts kept for the host...
    tool_msgs = [m for m in r.messages if m.get("role") == "tool"]
    assert [p["type"] for p in tool_msgs[0].get("parts", [])] == ["image", "image"]
    assert "parts" not in tool_msgs[2]  # ...but never emitted to the model (§7G.6)


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


# --------------------------------------------------------------------------- #
# D9 (SPEC §7G.12): no image parts ⇒ every message style sends the plain output string
# --------------------------------------------------------------------------- #
_D9 = [(f, c["name"], c["tool"])
       for f, cases in (("cases", CASES), ("clients", CLIENT_CASES), ("cli", CLI_CASES), ("routing", ROUTING["cases"]))
       for c in cases if "tool" in c and c["tool"].get("modelParts") == [] and "output" in c["tool"]]


def test_d9_cases_present():
    assert len(_D9) > 0


@pytest.mark.parametrize("style", ["openai", "anthropic"])  # the python port has no gemini client style
@pytest.mark.parametrize("item", _D9, ids=[f"{f}:{n}" for f, n, _ in _D9])
def test_d9_no_image_parts_sends_plain_output(item, style):
    _, _, want = item
    r = ToolResult(output=want["output"], is_error=want["isError"], parts=want.get("parts"),
                   metadata=want.get("metadata"))
    client = create_client(base_url="http://llm", style=style, model="m", api_key="k")
    if style == "openai":
        transcript = [{"role": "assistant", "content": None, "tool_calls": [
            {"id": "call_0", "type": "function", "function": {"name": "t", "arguments": "{}"}}]},
            _tool_message("call_0", "t", r)]
        wire = client._wire_messages(transcript, style)
        assert len(wire) == 2  # no relocated user message
        tool_msg = wire[1]
        assert tool_msg["role"] == "tool" and tool_msg["content"] == want["output"]
        assert "parts" not in tool_msg and "name" not in tool_msg
    else:
        transcript = [{"role": "assistant", "content": [{"type": "tool_use", "id": "tu_0", "name": "t", "input": {}}]},
                      {"role": "user", "content": [_tool_result_block("tu_0", r)]}]
        wire = client._wire_messages(transcript, style)
        assert len(wire) == 2
        block = wire[1]["content"][0]
        assert block["type"] == "tool_result" and block["content"] == want["output"]
        assert isinstance(block["content"], str) and "parts" not in block
