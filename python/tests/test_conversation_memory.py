"""Conversation memory tests — ``client.ask`` + ``ConversationStore`` + a2a serve.

Mirrors the JS reference trio (``js/test/unit.test.ts``):
  * "client: ask remembers by id via the conversation store"
  * "client: custom ConversationStore provider is used (get/save)"
  * "a2a serve: remembers a peer's turns by contextId"

All hermetic: a mock OpenAI-style LLM runs in-process on an ephemeral port and
replies with ``str(len(messages))`` — the number of messages it received — so a
growing reply proves prior history was loaded and threaded back in. No external
network, no real LLM. See ../../SPEC.md §8 "Conversation memory".
"""
from __future__ import annotations

import asyncio
import http.server
import json
import threading
import urllib.request

from toolnexus import create_client, create_toolkit

TERMINAL_STATES = {"completed", "failed", "canceled"}


def _start_counting_llm():
    """Mock LLM whose reply is the number of messages it received — proves history
    is loaded. Returns (server, base_url)."""

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):  # silence
            pass

        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length else b"{}"
            msg = json.loads(raw.decode("utf-8"))
            body = json.dumps(
                {
                    "choices": [{"message": {"content": str(len(msg["messages"]))}}],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                }
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


async def test_ask_remembers_by_id_via_conversation_store():
    llm, base_url = _start_counting_llm()
    # no skills / no builtins ⇒ no system-prompt message, so counts are exactly the turns
    tk = await create_toolkit(builtins=False)
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    try:
        solo = await client.ask("hi", tk)
        assert solo.text == "1"  # no id ⇒ stateless one-shot (just the user turn)

        a = await client.ask("first", tk, id="c1")
        assert a.text == "1"  # first turn: 1 message
        b = await client.ask("second", tk, id="c1")
        assert b.text == "3"  # same id remembers: user+assistant+user = 3

        c = await client.ask("other", tk, id="c2")
        assert c.text == "1"  # a different id is an independent conversation
    finally:
        await tk.close()
        llm.shutdown()


async def test_custom_conversation_store_provider_is_used():
    calls: list[str] = []
    backing: dict[str, list] = {}

    class StubStore:
        async def get(self, id):  # noqa: A002
            calls.append(f"get:{id}")
            return backing.get(id)

        async def save(self, id, messages):  # noqa: A002
            calls.append(f"save:{id}")
            backing[id] = messages

    def _start_ok_llm():
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0) or 0)
                self.rfile.read(length)
                body = json.dumps(
                    {
                        "choices": [{"message": {"content": "ok"}}],
                        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                    }
                ).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return server, f"http://127.0.0.1:{server.server_address[1]}"

    llm, base_url = _start_ok_llm()
    tk = await create_toolkit(builtins=False)
    store = StubStore()
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k", store=store)
    try:
        await client.ask("hi", tk, id="u1")
        assert calls == ["get:u1", "save:u1"]  # custom store: get then save
        assert "u1" in backing  # custom store persisted the transcript
    finally:
        await tk.close()
        llm.shutdown()


async def _rpc(endpoint: str, method: str, params) -> dict:
    def _do():
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
        req = urllib.request.Request(
            endpoint, data=body, method="POST", headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode())

    env = await asyncio.to_thread(_do)
    return env["result"]


async def _send(endpoint: str, text: str, context_id: str) -> dict:
    task = await _rpc(
        endpoint,
        "SendMessage",
        {"message": {"role": "user", "contextId": context_id, "parts": [{"kind": "text", "text": text}]}},
    )
    for _ in range(100):
        t = await _rpc(endpoint, "GetTask", {"id": task["id"]})
        if t["status"]["state"] in TERMINAL_STATES:
            return t
        await asyncio.sleep(0.01)
    raise AssertionError("timeout")


async def test_a2a_serve_remembers_peer_turns_by_context_id():
    llm, base_url = _start_counting_llm()
    # no skills ⇒ no system-prompt message, so counts are exactly the turns
    tk = await create_toolkit(builtins=False)
    client = create_client(base_url=base_url, style="openai", model="x", api_key="k")
    srv = await tk.serve("127.0.0.1:0", client=client, a2a={"name": "mem-desk"})
    endpoint = srv.url + "/"
    try:
        t1 = await _send(endpoint, "first", "ctxA")
        assert t1["artifacts"][0]["parts"][0]["text"] == "1"  # first served turn: 1 message
        t2 = await _send(endpoint, "second", "ctxA")
        assert t2["artifacts"][0]["parts"][0]["text"] == "3"  # same contextId remembers: 3
        t3 = await _send(endpoint, "other", "ctxB")
        assert t3["artifacts"][0]["parts"][0]["text"] == "1"  # a different contextId is independent
    finally:
        await srv.stop()
        await tk.close()
        llm.shutdown()
