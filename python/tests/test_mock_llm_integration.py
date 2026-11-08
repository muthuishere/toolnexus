"""Integration suite against the shared mock model server (tools/mock-llm/mock_llm.py).

Real toolnexus, real HTTP, no key, no cost. Starts the mock as a subprocess on a free
port, or uses TN_MOCK_LLM_URL when set (point it at a mutated mock to prove these
assertions bite).
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

import pytest

from toolnexus import (
    ChoiceQuestion,
    NoulQuestion,
    ScoreQuestion,
    create_classifier,
    create_client,
    create_media,
    create_toolkit,
    define_tool,
    load_media_style,
)

ROOT = Path(__file__).resolve().parents[2]
MOCK = ROOT / "tools" / "mock-llm" / "mock_llm.py"
PNG = ROOT / "examples" / "media" / "fixture.png"
PNG_SHA = (ROOT / "examples" / "media" / "fixture.png.sha256").read_text().split()[0]
DEFAULT_TEXT = "This is a reply from the toolnexus mock model."


@pytest.fixture(scope="module")
def base():
    url = os.environ.get("TN_MOCK_LLM_URL")
    if url:
        yield url.rstrip("/")
        return
    if not shutil.which("python3") and not sys.executable:
        pytest.skip("python3 not available for the mock server")
    p = subprocess.Popen([sys.executable, str(MOCK), "--port", "0", "--quiet"],
                         stdout=subprocess.PIPE, text=True)
    try:
        line = p.stdout.readline()
        yield line.strip().rsplit(" ", 1)[-1]
    finally:
        p.terminate()
        p.wait(5)


def _http(base, path, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


async def _add_toolkit():
    calls = []

    def add(a: int, b: int) -> str:
        calls.append((a, b))
        return str(a + b)

    tk = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add", description="Add two numbers.")])
    return tk, calls


@pytest.mark.parametrize("style", ["openai", "anthropic"])
async def test_tool_call_loop(base, style):
    tk, calls = await _add_toolkit()
    try:
        r = await create_client(base_url=base, style=style, model="mock-model", api_key="mock-key").run("add", tk)
    finally:
        await tk.close()
    assert calls == [(1, 1)]
    assert r.text == "Tool result: 2"


@pytest.mark.parametrize("style", ["openai", "anthropic"])
async def test_streaming_text(base, style):
    client = create_client(base_url=base, style=style, model="mock-model", api_key="mock-key")
    deltas, final = [], None
    async for ev in client.stream("hi"):
        if ev["type"] == "text":
            deltas.append(ev.get("delta") or ev.get("text") or "")
        if ev["type"] == "done":
            final = ev["result"]
    assert "".join(deltas) == DEFAULT_TEXT
    assert final.text == DEFAULT_TEXT


@pytest.mark.parametrize("style", ["openai", "anthropic"])
async def test_image_attachment_arrives_intact(base, style):
    import base64
    client = create_client(base_url=base, style=style, model="mock-model", api_key="mock-key")
    r = await client.run([{"type": "text", "text": "what is this?"},
                          {"type": "image", "mimeType": "image/png",
                           "data": base64.b64encode(PNG.read_bytes()).decode()}])
    assert f"image/png 82 bytes sha256={PNG_SHA[:12]}" in r.text


async def test_retry_after_429(base):
    _http(base, "/_mock/reset", {})
    _http(base, "/_mock/faults", {"path": "/chat/completions", "status": 429, "times": 1, "retryAfter": "0"})
    r = await create_client(base_url=base, style="openai", model="mock-model", api_key="mock-key").run("hi")
    assert r.text == DEFAULT_TEXT
    assert _http(base, "/_mock/requests")["/chat/completions"] == 2


QS = {
    "damaged": NoulQuestion("Does the customer report a damaged item?"),
    "team": ChoiceQuestion("Which team should handle this?",
                           {"shipping": "lost or late parcels", "billing": "refunds and payments", "other": "anything else"}),
    "upset": ScoreQuestion("How upset is the customer?", ["calm", "annoyed", "furious"]),
}


@pytest.mark.parametrize("backend,path", [("custom", ""), ("cloudflare", "/accounts/acc/ai/run"), ("openai", "")])
async def test_classifier_wires(base, backend, path):
    kw = {"supports": {"images": False}} if backend == "custom" else {}
    c = create_classifier(backend=backend, base_url=base + path, api_key_env="TN_MOCK_FAKE_KEY", **kw)
    state = "The package arrived with a broken screen."
    d = await c.evaluate(state, QS)
    assert d.answers["damaged"].noul == 0.95
    team = d.answers["team"]
    assert team.choice in {"shipping", "billing", "other"}
    assert set(team.probabilities) == {"shipping", "billing", "other"}
    assert abs(sum(team.probabilities.values()) - 1) < 1e-9
    assert team.probabilities[team.choice] == 0.7
    up = d.answers["upset"]
    assert 0 <= up.score <= 2 and up.legend == {"0": "calm", "1": "annoyed", "2": "furious"}
    again = await c.evaluate(state, QS)
    assert again.answers["team"].choice == team.choice and again.answers["upset"].score == up.score


async def test_media_every_verb(base, tmp_path):
    p = tmp_path / "mock-style.json"
    p.write_text(json.dumps(_http(base, "/_mock/media-style.json")))
    m = create_media(load_media_style(str(p)))
    img = await m.image("a red square")
    assert img.status == "completed" and img.job_id
    assert [(f.type, f.mime_type) for f in img.files] == [("image", "image/png")]
    with urllib.request.urlopen(img.files[0].url) as r:
        assert r.read() == PNG.read_bytes()
    vid = await m.video("waves")
    assert [(f.type, f.mime_type) for f in vid.files] == [("file", "video/mp4")]
    sp = await m.speech("hello")
    assert sp.status == "completed" and [(f.type, f.mime_type) for f in sp.files] == [("audio", "audio/wav")]
    tr = await m.transcribe("https://x/a.wav")
    assert tr.text == "Mock transcript of https://x/a.wav." and tr.files == []
    cv = await m.clone_voice("muthu", ["https://x/s.wav"])
    assert cv.text.startswith("voice-") and len(cv.text) == 14
    tk = await m.talk("https://x/face.png", audio="https://x/a.wav")
    assert tk.files[0].url.endswith("/files/talking.mp4") and tk.files[0].mime_type == "video/mp4"
    with pytest.raises(Exception, match="mock: job asked to fail"):
        await m.image("[fail] please")


# ---------------------------------------------------------------- every tool source, CLI and ACP

KIT = Path(os.environ.get("TN_MOCK_KIT_DIR") or ROOT / "tools" / "mock-llm")


@pytest.fixture(scope="module")
def mcp_http():
    p = subprocess.Popen([sys.executable, str(KIT / "mock_mcp.py"), "--http", "--port", "0"],
                         stdout=subprocess.PIPE, text=True)
    try:
        yield p.stdout.readline().strip().rsplit(" ", 1)[-1]
    finally:
        p.terminate()
        p.wait(5)


async def _mixed_toolkit(base, mcp_url):
    from toolnexus import agent, http_tool

    def add(a: int, b: int) -> str:
        return str(a + b)

    origin = base[: -len("/v1")]
    return await create_toolkit(
        builtins=False,
        mcp_config={"mcpServers": {
            "stdio": {"type": "local", "command": [sys.executable, str(KIT / "mock_mcp.py")]},
            "legacy": {"type": "local", "command": [sys.executable, str(KIT / "mock_mcp.py"), "--legacy"]},
            "web": {"type": "remote", "url": mcp_url},
        }},
        skills_dir=str(ROOT / "examples" / "skills"),
        agents=[agent(origin + "/.well-known/agent-card.json", poll_every=20)],
        extra_tools=[
            define_tool(add, name="native_add", description="Add two integers."),
            http_tool(name="http_add", description="Add via HTTP.", method="POST", url=base + "/tools/add",
                      input_schema={"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
                                    "required": ["a", "b"]}),
        ],
    )


async def test_mixed_toolkit_every_source(base, mcp_http):
    tk = await _mixed_toolkit(base, mcp_http)
    try:
        names = {t.name for t in tk.tools()}
        for n in ("stdio_add", "legacy_add", "web_echo", "mockagent_summarize", "native_add", "http_add", "skill"):
            assert n in names, (n, sorted(names))
        by = {t.name: t for t in tk.tools()}
        assert (await by["stdio_add"].execute({"a": 2, "b": 3})).output == "5"
        assert (await by["legacy_add"].execute({"a": 2, "b": 3})).output == "5"
        assert json.loads((await by["web_echo"].execute({"text": "hi"})).output) == {"text": "hi"}
        assert (await by["mockagent_summarize"].execute({"task": "x"})).output == "A2A mock done: x"
        assert json.loads((await by["http_add"].execute({"a": 2, "b": 3})).output) == {"sum": 5}
        assert (await by["native_add"].execute({"a": 2, "b": 3})).output == "5"
        sk = await by["skill"].execute({"name": "hello-world"})
        assert not sk.is_error and "hello-world" in sk.output
    finally:
        await tk.close()


async def test_client_run_over_mixed_toolkit(base, mcp_http):
    tk = await _mixed_toolkit(base, mcp_http)
    try:
        r = await create_client(base_url=base, style="openai", model="mock-model", api_key="mock-key").run("go", tk)
    finally:
        await tk.close()
    assert len(r.tool_calls) == 1
    assert r.text.startswith("Tool result: ") and r.text != "Tool result: "


async def test_in_process_cli_client():
    from toolnexus import create_in_process_client

    calls = []

    def add(a: int, b: int) -> str:
        calls.append((a, b))
        return str(a + b)

    def generate(req):
        lines, called = [], {}
        for m in req["messages"]:
            for c in m.get("tool_calls") or []:
                called[c["id"]] = c["function"]["name"]
            if m["role"] == "tool":
                lines.append(f"[tool {called.get(m.get('tool_call_id'))}] {m['content']}")
            elif isinstance(m.get("content"), str):
                lines.append(f"[{m['role']}] {m['content']}")
        out = subprocess.run([sys.executable, str(KIT / "fake_cli.py")], input="\n".join(lines),
                             capture_output=True, text=True, timeout=20).stdout
        return json.loads(out)

    tk = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add")])
    try:
        r = await create_in_process_client(model="fake-cli", generate=generate).run("add 19 and 23", tk)
    finally:
        await tk.close()
    assert calls == [(19, 23)]
    assert r.text == "The sum is 42."


async def test_acp_agent_tool_call_and_stream():
    from toolnexus import ACPOptions, create_in_process_client, load_acp

    calls = []

    def add(a: int, b: int) -> str:
        calls.append((a, b))
        return str(a + b)

    tk = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add")])
    server = tk.as_mcp_server()
    acp = load_acp(ACPOptions(command=sys.executable, args=[str(KIT / "fake_acp_agent.py")],
                              mcp_servers=[{"type": "http", "name": "toolnexus", "url": server.url,
                                            "headers": [{"name": "Authorization", "value": "Bearer " + server.token}]}]))
    try:
        client = create_in_process_client(model="fake-acp", generate_stream=acp.generate_stream)
        empty = await create_toolkit(builtins=False)
        deltas, final = [], None
        async for ev in client.stream("add 17 and 25", empty):
            if ev["type"] == "text":
                deltas.append(ev["delta"])
            elif ev["type"] == "done":
                final = ev["result"]
        await empty.close()
    finally:
        acp.close()
        server.close()
        await tk.close()
    assert calls == [(17, 25)]
    assert "".join(deltas) == "The sum is 42." and final.text == "The sum is 42."


async def test_acp_cancel():
    import asyncio
    import threading

    from toolnexus import ACPCancelledError, ACPOptions, load_acp

    acp = load_acp(ACPOptions(command=sys.executable, args=[str(KIT / "fake_acp_agent.py")]))
    try:
        assert acp.generate({"messages": [{"role": "user", "content": "hello"}]})["content"].endswith("hello")
        cancel = threading.Event()
        threading.Timer(0.3, cancel.set).start()
        with pytest.raises(ACPCancelledError):
            await asyncio.to_thread(acp.generate, {"messages": [{"role": "user", "content": "[wait]"}]}, cancel)
    finally:
        acp.close()
