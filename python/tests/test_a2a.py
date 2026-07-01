"""A2A (outbound) tests — hermetic, no external network, no LLM.

Mirrors the JS reference suite (``js/test/unit.test.ts`` a2a block). A tiny fake
A2A agent runs in-process on an ephemeral port:

  GET /.well-known/agent-card.json → card { name:"reviewer", url→self,
      skills:[review, plan, fail] }
  POST /  (JSON-RPC) SendMessage → Task {state:"submitted"}; GetTask →
      "working" once, then terminal. Behavior keyed on the message text:
      "fail" → failed(boom); "slow" → stays "working"; else → completed(REVIEWED).
"""
from __future__ import annotations

import asyncio
import http.server
import json
import threading


from toolnexus import agent, create_toolkit, parse_agents_config
from toolnexus.types import ToolContext


def _start_stub():
    """Spin up the in-process fake A2A agent. Returns (server, card_url, seen)."""
    tasks: dict[str, dict] = {}
    seen = {"auth": None, "sendMessageCalls": 0, "getTaskCalls": 0}
    counter = {"n": 0}
    holder: dict = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):  # silence
            pass

        def _json(self, obj):
            body = json.dumps(obj).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/.well-known/agent-card.json":
                port = holder["port"]
                self._json(
                    {
                        "name": "reviewer",
                        "description": "a code reviewer",
                        "version": "1.0.0",
                        "protocolVersion": "0.3.0",
                        "capabilities": {"streaming": False, "pushNotifications": False},
                        "defaultInputModes": ["text/plain"],
                        "defaultOutputModes": ["text/plain"],
                        "url": f"http://127.0.0.1:{port}/",
                        "skills": [
                            {"id": "review", "name": "Review", "description": "Review some code"},
                            {"id": "plan", "name": "Plan", "description": "Plan a task"},
                            {"id": "fail", "name": "Fail", "description": "Always fails"},
                        ],
                    }
                )
                return
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"nope")

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8")
            seen["auth"] = self.headers.get("Authorization")
            rpc = json.loads(body)
            method = rpc.get("method")
            if method == "SendMessage":
                seen["sendMessageCalls"] += 1
                text = "".join(p.get("text", "") for p in rpc["params"]["message"]["parts"])
                mode = "failed" if "fail" in text else "slow" if "slow" in text else "completed"
                counter["n"] += 1
                tid = f"t{counter['n']}"
                tasks[tid] = {"polls": 0, "mode": mode}
                self._json({"jsonrpc": "2.0", "id": rpc["id"], "result": {"id": tid, "status": {"state": "submitted"}}})
            elif method == "GetTask":
                seen["getTaskCalls"] += 1
                tid = rpc["params"]["id"]
                t = tasks[tid]
                t["polls"] += 1
                if t["mode"] == "slow" or t["polls"] < 2:
                    result = {"id": tid, "status": {"state": "working"}}
                elif t["mode"] == "failed":
                    result = {
                        "id": tid,
                        "status": {"state": "failed", "message": {"role": "agent", "parts": [{"kind": "text", "text": "boom"}]}},
                    }
                else:
                    result = {
                        "id": tid,
                        "status": {"state": "completed"},
                        "artifacts": [{"parts": [{"kind": "text", "text": "REVIEWED"}]}],
                    }
                self._json({"jsonrpc": "2.0", "id": rpc["id"], "result": result})
            else:
                self._json({"jsonrpc": "2.0", "id": rpc["id"], "error": {"code": -32601, "message": "unknown method"}})

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    holder["port"] = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    card_url = f"http://127.0.0.1:{holder['port']}/.well-known/agent-card.json"
    return server, card_url, seen


async def test_a2a_success_roundtrip_and_env_header(monkeypatch):
    server, card_url, seen = _start_stub()
    monkeypatch.setenv("TN_A2A_TOKEN", "a2a-secret")
    try:
        tk = await create_toolkit(
            builtins=False,
            agents=[agent(card_url, poll_every=5, headers={"Authorization": "Bearer ${TN_A2A_TOKEN}"})],
        )
        # skills → tools, source:"a2a"
        review = tk.get("reviewer_review")
        assert review is not None
        assert review.source == "a2a"
        assert tk.get("reviewer_plan") is not None
        # in the provider schema
        names = [f["function"]["name"] for f in tk.to_openai()]
        assert "reviewer_review" in names and "reviewer_plan" in names

        # execute → SendMessage once → poll GetTask → completed → "REVIEWED"
        r = await tk.execute("reviewer_review", {"task": "please review"})
        assert r.is_error is False
        assert r.output == "REVIEWED"
        assert seen["sendMessageCalls"] == 1
        assert seen["getTaskCalls"] >= 2
        assert seen["auth"] == "Bearer a2a-secret"  # env header expanded at call time
        assert r.metadata["state"] == "completed"
        assert r.metadata["agent"] == "reviewer"
        assert r.metadata["taskId"]
        await tk.close()
    finally:
        server.shutdown()


async def test_a2a_failed_task_maps_to_error():
    server, card_url, _ = _start_stub()
    try:
        tk = await create_toolkit(builtins=False, agents=[agent(card_url, poll_every=5)])
        r = await tk.execute("reviewer_fail", {"task": "please fail"})
        assert r.is_error is True
        assert "failed" in r.output
        assert "boom" in r.output  # carries the status message text
        assert r.metadata["state"] == "failed"
        await tk.close()
    finally:
        server.shutdown()


async def test_a2a_cancel_mid_poll_stops_further_gettask():
    server, card_url, seen = _start_stub()
    try:
        tk = await create_toolkit(builtins=False, agents=[agent(card_url, poll_every=10)])
        cancel = asyncio.Event()
        ctx = ToolContext(signal=cancel)
        task = asyncio.create_task(tk.execute("reviewer_review", {"task": "slow one"}, ctx))

        async def _abort():
            await asyncio.sleep(0.035)
            cancel.set()

        asyncio.create_task(_abort())
        r = await task
        assert r.is_error is True
        assert r.metadata["state"] == "canceled"
        after_abort = seen["getTaskCalls"]
        await asyncio.sleep(0.06)
        assert seen["getTaskCalls"] == after_abort  # no GetTask calls after abort
        await tk.close()
    finally:
        server.shutdown()


async def test_a2a_config_block_enabled_false_skipped():
    server, card_url, _ = _start_stub()
    try:
        off = await create_toolkit(
            builtins=False,
            mcp_config={"agents": {"rev": {"card": card_url, "disabled": True}}},
        )
        assert off.get("reviewer_review") is None  # disabled agent skipped
        await off.close()

        on = await create_toolkit(
            builtins=False,
            mcp_config={"agents": {"rev": {"card": card_url, "pollEvery": 5}}},
        )
        assert on.get("reviewer_review") is not None  # enabled agent resolved from config
        await on.close()
    finally:
        server.shutdown()


async def test_a2a_add_agent_registers_tools_at_runtime():
    server, card_url, _ = _start_stub()
    try:
        tk = await create_toolkit(builtins=False)
        assert tk.get("reviewer_review") is None  # absent before add_agent
        await tk.add_agent(agent(card_url, poll_every=5))
        assert tk.get("reviewer_review") is not None
        assert tk.get("reviewer_plan") is not None
        # bare card URL form also works
        await tk.add_agent(card_url, poll_every=5)  # dup names — kept first, no error
        await tk.close()
    finally:
        server.shutdown()


def test_parse_agents_config_precedence():
    parsed = parse_agents_config(
        {
            "a": {"card": "http://x/1"},
            "b": {"card": "http://x/2", "disabled": True},
            "c": {"card": "http://x/3", "enabled": False},
            "d": {"card": "http://x/4", "enabled": True, "disabled": True},
        }
    )
    assert len(parsed) == 1
    assert parsed[0].card == "http://x/1"
