"""conformance/acp-client/cases.json (SPEC §8C, ADR 0041) — the plain ACP client
(passthrough, agent exit, unknown requests, incremental streaming) and
``Toolkit.as_mcp_server``. Hermetic: a stdlib fake ACP agent over real pipes, and
the toolkit served on 127.0.0.1."""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from toolnexus import (
    ACPError,
    ACPOptions,
    create_in_process_client,
    create_toolkit,
    define_tool,
    load_acp,
)

_ROOT = Path(__file__).resolve().parents[2]
CASES = json.loads((_ROOT / "conformance" / "acp-client" / "cases.json").read_text())
_FAKE = str(Path(__file__).resolve().parent / "fixtures" / "acp_conformance_fake.py")


def _load(tmp_path, fake_env=None, **kw):
    rec = tmp_path / "rec.jsonl"
    env = {"TN_FAKE_REC": str(rec), **(fake_env or {})}
    client = load_acp(ACPOptions(command=sys.executable, args=[_FAKE], env=env, permission_timeout=10.0, **kw))
    return client, rec


def _recorded(rec: Path, method: str) -> list[dict]:
    out = []
    for line in rec.read_text().splitlines():
        m = json.loads(line)
        if m["method"] == method:
            out.append(m["params"])
    return out


def test_passthrough_options(tmp_path):
    case = CASES["passthrough"]
    o = case["options"]
    client, rec = _load(
        tmp_path,
        mcp_servers=o["mcpServers"],
        session_meta=o["sessionMeta"],
        client_caps_meta=o["clientCapsMeta"],
        model=o["model"],
    )
    try:
        init = _recorded(rec, "initialize")[0]
        assert init["clientCapabilities"] == case["expectInitializeClientCapabilities"]
        new = _recorded(rec, "session/new")[0]
        assert new["mcpServers"] == o["mcpServers"]  # deep-equal, in order
        assert json.dumps(new["mcpServers"]) == json.dumps(o["mcpServers"])
        assert new["_meta"] == o["sessionMeta"]
        sco = _recorded(rec, "session/set_config_option")[0]
        for k, v in case["expectSetConfigOption"].items():
            assert sco[k] == v
        assert sco["sessionId"] == "s1"
        assert _recorded(rec, "session/set_model") == []
    finally:
        client.close()


def test_passthrough_set_model_fallback(tmp_path):
    case = CASES["passthrough"]
    fb = case["fallbackSetModel"]
    client, rec = _load(tmp_path, fake_env=fb["fakeEnv"], model=case["options"]["model"])
    try:
        sm = _recorded(rec, "session/set_model")[0]
        for k, v in fb["expectSetModel"].items():
            assert sm[k] == v
    finally:
        client.close()


def test_passthrough_bare(tmp_path):
    bare = CASES["passthrough"]["bareExpect"]
    client, rec = _load(tmp_path)
    try:
        new = _recorded(rec, "session/new")[0]
        assert new["mcpServers"] == bare["mcpServers"]
        assert ("_meta" in new) is bare["sessionNewHasMeta"]
        init = _recorded(rec, "initialize")[0]
        assert ("_meta" in init["clientCapabilities"]) is bare["clientCapabilitiesHasMeta"]
        assert _recorded(rec, "session/set_config_option") == []
    finally:
        client.close()


def test_agent_exit_fails_promptly(tmp_path):
    case = CASES["agentExit"]
    client, _ = _load(tmp_path, fake_env=case["fakeEnv"])
    try:
        start = time.monotonic()
        with pytest.raises(ACPError, match=re.escape(case["expectError"])):
            client.generate({"messages": [{"role": "user", "content": "hi"}]})
        assert time.monotonic() - start < 5.0  # well under permission_timeout
    finally:
        client.close()


def test_unknown_request_answered_with_method_not_found(tmp_path):
    case = CASES["unknownRequest"]
    assert case["method"] == "fs/read_text_file" and case["expectCode"] == -32601
    client, _ = _load(tmp_path, fake_env=case["fakeEnv"])
    try:
        assert client.generate({"messages": [{"role": "user", "content": "hi"}]})["content"] == case["expectText"]
    finally:
        client.close()


async def test_stream_acp_drip(tmp_path):
    case = CASES["stream"]["acpDrip"]
    acp, _ = _load(tmp_path, fake_env=case["fakeEnv"])
    try:
        client = create_in_process_client(model="m", generate_stream=acp.generate_stream)
        tk = await create_toolkit(builtins=False)
        texts, thoughts, first, done_at, done = [], [], None, None, None
        async for ev in client.stream("q", toolkit=tk):
            if ev["type"] == "text":
                texts.append(ev["delta"])
                if first is None:
                    first = time.monotonic()
            elif ev["type"] == "thought":
                thoughts.append(ev)
            elif ev["type"] == "done":
                done_at, done = time.monotonic(), ev["result"]
        assert texts == case["chunks"]
        assert [{k: t.get(k) for k in case["expectThought"]} for t in thoughts] == [case["expectThought"]]
        assert done is not None and done.text == case["expectDoneText"]
        assert (done_at - first) * 1000 >= case["minLeadMs"]
        assert (await client.run("q", toolkit=tk)).text == case["expectDoneText"]
    finally:
        acp.close()


# --------------------------------------------------------------------------- #
# asMcpServer
# --------------------------------------------------------------------------- #

_echo = define_tool(
    lambda text="": str(text),
    name="echo",
    description="echo back text",
    input_schema={"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
)


def _http(url, method="POST", token=None, body=None):
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    if token is not None:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


_INIT = json.dumps({
    "jsonrpc": "2.0", "id": 1, "method": "initialize",
    "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "t", "version": "0"}},
}).encode()


async def test_as_mcp_server(tmp_path):
    case = CASES["asMcpServer"]
    tk = await create_toolkit(skills_dir=str(_ROOT / "examples" / "skills"), builtins=False, extra_tools=[_echo])
    h = tk.as_mcp_server()
    h2 = tk.as_mcp_server()
    try:
        assert re.match(case["urlPattern"], h.url)
        assert re.match(case["tokenPattern"], h.token)
        assert h.token not in repr(h)
        if case["twoServersDistinctTokens"]:
            assert h.token != h2.token and h.url != h2.url

        status, headers, _ = _http(h.url, body=_INIT)
        assert status == case["noTokenStatus"]
        for k, v in case["noTokenHeader"].items():
            assert headers.get(k) == v
        assert _http(h.url, token="0" * 64, body=_INIT)[0] == case["wrongTokenStatus"]
        assert _http(h.url, method="GET", token=h.token)[0] == case["getWithTokenStatus"]
        other = h.url[: -len("/mcp")] + "/other"
        assert _http(other, token=h.token, body=_INIT)[0] == case["otherPathStatus"]
        assert _http(h.url, token=h.token, body=_INIT)[0] == 200

        # The port's own MCP client.
        mcp_cfg = {"mcpServers": {"tn": {"type": "remote", "url": h.url,
                                         "headers": {"Authorization": "Bearer " + h.token}}}}
        async with await create_toolkit(mcp_config=mcp_cfg, builtins=False) as client_tk:
            names = [t.name for t in client_tk.tools()]
            for want in case["toolsListContains"]:
                assert any(n == want or n.endswith("_" + want) for n in names), names
            echo = next(n for n in names if n.endswith("echo"))
            res = await client_tk.execute(echo, case["callEcho"]["arguments"])
            assert res.output == case["callEcho"]["expectText"]
    finally:
        h.close()
        h.close()  # idempotent
        h2.close()
    if case["afterCloseRefused"]:
        with pytest.raises((urllib.error.URLError, ConnectionError, OSError)):
            urllib.request.urlopen(urllib.request.Request(h.url, data=_INIT, method="POST"), timeout=3)
