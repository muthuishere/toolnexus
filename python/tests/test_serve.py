"""A2A (inbound) tests — serve a toolkit as an A2A agent; real localhost http.

Mirrors the JS reference suite (``js/test/unit.test.ts`` a2a-inbound block). All
hermetic: a mock OpenAI-style LLM runs in-process on an ephemeral port, the served
toolkit runs on another, and the OUTBOUND agent drives it over real localhost.
No external network, no real LLM.
"""
from __future__ import annotations

import asyncio
import http.server
import json
import os
import shutil
import tempfile
import threading
import urllib.error
import urllib.request
from pathlib import Path

from toolnexus import (
    FileTaskStore,
    InMemoryTaskStore,
    agent,
    build_agent_card,
    create_client,
    create_toolkit,
    resolve_store,
)
from toolnexus.skill import SkillInfo

# examples/skills lives at <repo>/examples/skills; this file is at python/tests/.
SKILLS_DIR = str((Path(__file__).resolve().parents[2] / "examples" / "skills").resolve())

TERMINAL_STATES = {"completed", "failed", "canceled"}


# ---------------------------------------------------------------------------
# Mock OpenAI-style LLM endpoint + tiny JSON-RPC helpers.
# ---------------------------------------------------------------------------


def _start_mock_llm(handler):
    """Spin up a mock LLM. ``handler(handler_self)`` writes the reply. Returns
    (server, base_url)."""

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):  # silence
            pass

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0) or 0)
            self.rfile.read(length)
            handler(self)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


def _canned(text: str):
    """Canned assistant completion with no tool calls."""

    def _h(h):
        body = json.dumps(
            {
                "choices": [{"message": {"content": text}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }
        ).encode("utf-8")
        h.send_response(200)
        h.send_header("Content-Type", "application/json")
        h.send_header("Content-Length", str(len(body)))
        h.end_headers()
        h.wfile.write(body)

    return _h


def _five_hundred(h):
    h.send_response(500)
    h.end_headers()
    h.wfile.write(b"model down")


async def _rpc(endpoint: str, method: str, params) -> dict:
    """POST one JSON-RPC 2.0 request to a served endpoint; return the envelope."""

    def _do():
        body = json.dumps({"jsonrpc": "2.0", "id": "req-1", "method": method, "params": params}).encode()
        req = urllib.request.Request(
            endpoint, data=body, method="POST", headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode())

    return await asyncio.to_thread(_do)


async def _fetch(url: str):
    def _do():
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                return resp.status, resp.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    return await asyncio.to_thread(_do)


async def _poll_until_terminal(endpoint: str, id: str) -> dict:
    for _ in range(100):
        env = await _rpc(endpoint, "GetTask", {"id": id})
        state = (env.get("result") or {}).get("status", {}).get("state")
        if state in TERMINAL_STATES:
            return env["result"]
        await asyncio.sleep(0.01)
    raise AssertionError("task never reached a terminal state")


def _text_message(text: str) -> dict:
    return {"message": {"role": "user", "parts": [{"kind": "text", "text": text}]}}


# ---------------------------------------------------------------------------
# Tests.
# ---------------------------------------------------------------------------


async def test_serve_roundtrip_outbound_agent_client_run_artifact():
    llm, base_url = _start_mock_llm(_canned("TRANSCRIBED"))
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    tk = await create_toolkit(skills_dir=SKILLS_DIR, builtins=False)
    srv = await tk.serve("127.0.0.1:0", client=client, a2a={"name": "video-desk", "skills": ["hello-world"]})
    # Caller side: the OUTBOUND agent consumes the served card and exposes its
    # hello-world skill as a tool.
    caller = await create_toolkit(
        builtins=False,
        agents=[agent(srv.url + "/.well-known/agent-card.json", poll_every=10)],
    )
    try:
        tool = caller.get("video-desk_hello-world")
        assert tool is not None  # outbound tool built from the served skill
        assert tool.source == "a2a"

        r = await caller.execute("video-desk_hello-world", {"task": "do it"})
        assert r.is_error is False
        assert r.output == "TRANSCRIBED"  # submit→poll→client.run→artifact round-trips end to end
        assert r.metadata["state"] == "completed"
    finally:
        await caller.close()
        await srv.stop()
        await tk.close()
        llm.shutdown()


async def test_serve_card_advertises_skills_not_raw_tools_streaming_false():
    llm, base_url = _start_mock_llm(_canned("ok"))
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    # builtins ON so bash/read exist as tools — they must NOT leak into the card.
    tk = await create_toolkit(skills_dir=SKILLS_DIR)
    srv = await tk.serve("127.0.0.1:0", client=client, a2a={"name": "video-desk"})
    try:
        status, text = await _fetch(srv.url + "/.well-known/agent-card.json")
        card = json.loads(text)
        ids = [s["id"] for s in card["skills"]]
        assert "hello-world" in ids  # SKILL.md skill advertised
        assert "bash" not in ids and "read" not in ids  # raw builtin tools stay private
        assert card["capabilities"]["streaming"] is False
        assert card["name"] == "video-desk"
        assert card["url"] == srv.url + "/"  # card.url is the JSON-RPC endpoint
        assert card["defaultInputModes"] == ["text"]
        assert card["protocolVersion"] == "0.3.0"
        assert card["version"] == "0.1.0"
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()


async def test_serve_profile_opt_in_no_a2a_card_404():
    llm, base_url = _start_mock_llm(_canned("ok"))
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    tk = await create_toolkit(skills_dir=SKILLS_DIR, builtins=False)
    srv = await tk.serve("127.0.0.1:0", client=client)
    try:
        status, _ = await _fetch(srv.url + "/.well-known/agent-card.json")
        assert status == 404  # no A2A routes mounted without an a2a profile
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()


async def test_serve_fulfilment_error_failed_task_server_keeps_serving():
    llm, base_url = _start_mock_llm(_five_hundred)
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k", retries=0)
    tk = await create_toolkit(skills_dir=SKILLS_DIR, builtins=False)
    events: list[dict] = []
    srv = await tk.serve(
        "127.0.0.1:0", client=client, a2a={"name": "video-desk"}, on_task=lambda ev: events.append(ev)
    )
    endpoint = srv.url + "/"
    try:
        sent = await _rpc(endpoint, "SendMessage", _text_message("go"))
        assert sent["result"]["status"]["state"] == "submitted"  # returns submitted immediately
        tid = sent["result"]["id"]
        task = await _poll_until_terminal(endpoint, tid)
        assert task["status"]["state"] == "failed"

        # server survives a fulfilment error and still answers the card.
        status, _ = await _fetch(srv.url + "/.well-known/agent-card.json")
        assert status == 200

        assert events[-1]["state"] == "failed"  # onTask surfaced the failed outcome
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()


async def test_serve_host_supplied_taskstore_used_and_error_codes():
    class StubStore:
        def __init__(self):
            self.saved: list[str] = []
            self.gotten: list[str] = []
            self._map: dict[str, dict] = {}

        async def save(self, t):
            self.saved.append(t["status"]["state"])
            self._map[t["id"]] = t

        async def get(self, id):
            self.gotten.append(id)
            return self._map.get(id)

    llm, base_url = _start_mock_llm(_canned("DONE"))
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    tk = await create_toolkit(skills_dir=SKILLS_DIR, builtins=False)
    store = StubStore()
    srv = await tk.serve("127.0.0.1:0", client=client, a2a={"name": "video-desk", "store": store})
    endpoint = srv.url + "/"
    try:
        sent = await _rpc(endpoint, "SendMessage", _text_message("go"))
        tid = sent["result"]["id"]
        task = await _poll_until_terminal(endpoint, tid)
        assert task["status"]["state"] == "completed"
        assert store.saved == ["submitted", "working", "completed"]  # all writes via host store
        assert len(store.gotten) >= 1  # GetTask read through the host store
        # GetTask on an unknown id ⇒ JSON-RPC error.
        miss = await _rpc(endpoint, "GetTask", {"id": "nope"})
        assert miss["error"]["code"] == -32001
        # Unknown method ⇒ -32601.
        bad = await _rpc(endpoint, "Frobnicate", {})
        assert bad["error"]["code"] == -32601
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()


async def test_serve_parse_error_returns_32700():
    llm, base_url = _start_mock_llm(_canned("ok"))
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    tk = await create_toolkit(skills_dir=SKILLS_DIR, builtins=False)
    srv = await tk.serve("127.0.0.1:0", client=client, a2a={"name": "video-desk"})
    endpoint = srv.url + "/"
    try:

        def _do():
            req = urllib.request.Request(
                endpoint, data=b"{not json", method="POST", headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                return json.loads(resp.read().decode())

        env = await asyncio.to_thread(_do)
        assert env["error"]["code"] == -32700
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()


async def test_filetaskstore_round_trips_and_resolve_store_selectors():
    dir = tempfile.mkdtemp()
    try:
        store = FileTaskStore(dir)
        await store.save(
            {"id": "abc-123", "status": {"state": "completed"}, "artifacts": [{"parts": [{"kind": "text", "text": "hi"}]}]}
        )
        got = await store.get("abc-123")
        assert got["status"]["state"] == "completed"
        assert got["artifacts"][0]["parts"][0]["text"] == "hi"
        assert await store.get("missing") is None
        assert os.path.exists(os.path.join(dir, "abc-123.json"))  # one JSON file per task id

        # resolve_store selectors.
        assert isinstance(resolve_store(), InMemoryTaskStore)
        assert isinstance(resolve_store("memory"), InMemoryTaskStore)
        assert isinstance(resolve_store(f"file:{dir}"), FileTaskStore)
        assert resolve_store(store) is store  # an object is used as-is
        try:
            resolve_store("redis:whatever")
            raise AssertionError("expected resolve_store to reject an unknown selector")
        except ValueError as e:
            assert "Unknown A2A store" in str(e)
    finally:
        shutil.rmtree(dir, ignore_errors=True)


def test_build_agent_card_filters_skills_and_defaults():
    skills = [
        SkillInfo(name="alpha", description="A", location="/x/alpha/SKILL.md", content=""),
        SkillInfo(name="beta", description="B", location="/x/beta/SKILL.md", content=""),
    ]
    filtered = build_agent_card({"name": "n", "skills": ["alpha"]}, skills, "http://h/")
    assert [s["id"] for s in filtered["skills"]] == ["alpha"]
    all_card = build_agent_card({}, skills, "http://h/")
    assert sorted(s["id"] for s in all_card["skills"]) == ["alpha", "beta"]
    assert all_card["name"] == "toolnexus-agent"
    assert all_card["capabilities"]["streaming"] is False
    assert all_card["protocolVersion"] == "0.3.0"
    assert all_card["description"] == ""
    assert all_card["version"] == "0.1.0"


async def test_serve_top_level_a2a_config_block_picked_up():
    llm, base_url = _start_mock_llm(_canned("ok"))
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    tk = await create_toolkit(mcp_config={"a2a": {"name": "from-config"}}, skills_dir=SKILLS_DIR, builtins=False)
    srv = await tk.serve("127.0.0.1:0", client=client)  # no inline a2a → falls back to config
    try:
        status, text = await _fetch(srv.url + "/.well-known/agent-card.json")
        card = json.loads(text)
        assert card["name"] == "from-config"
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()


def _question_toolcall(h):
    """Mock LLM completion: model calls the `question` builtin (suspends, no answer)."""
    body = json.dumps(
        {
            "choices": [
                {
                    "message": {
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "c1",
                                "type": "function",
                                "function": {
                                    "name": "question",
                                    "arguments": '{"questions": [{"question": "Pick a color?", "options": ["red", "green"]}]}',
                                },
                            }
                        ],
                    }
                }
            ],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }
    ).encode("utf-8")
    h.send_response(200)
    h.send_header("Content-Type", "application/json")
    h.send_header("Content-Length", str(len(body)))
    h.end_headers()
    h.wfile.write(body)


async def _poll_until_state(endpoint: str, id: str, wanted: str) -> dict:
    for _ in range(100):
        env = await _rpc(endpoint, "GetTask", {"id": id})
        state = (env.get("result") or {}).get("status", {}).get("state")
        if state == wanted:
            return env["result"]
        await asyncio.sleep(0.01)
    raise AssertionError(f"task never reached state {wanted!r}")


async def test_serve_suspended_run_surfaces_input_required_not_completed_g1():
    # Model calls `question`; with no wait_for the run halts pending — serve must NOT report completed.
    llm, base_url = _start_mock_llm(_question_toolcall)
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")  # no wait_for
    tk = await create_toolkit()  # builtins on → `question` exists
    events: list[dict] = []
    srv = await tk.serve(
        "127.0.0.1:0", client=client, a2a={"name": "ask-desk"}, on_task=lambda ev: events.append(ev)
    )
    endpoint = srv.url + "/"
    try:
        sent = await _rpc(endpoint, "SendMessage", _text_message("go"))
        tid = sent["result"]["id"]
        task = await _poll_until_state(endpoint, tid, "input-required")
        assert task["status"]["state"] == "input-required", "suspended run → input-required, never completed"
        assert "Pick a color? (options: red, green)" in task["status"]["message"]["parts"][0]["text"], (
            "prompt carried in the status message"
        )
        assert not task.get("artifacts"), "no artifact passing the prompt off as a real answer"
        assert events[-1]["state"] == "input-required"  # onTask surfaced input-required
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()
