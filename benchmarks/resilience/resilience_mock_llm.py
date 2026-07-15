#!/usr/bin/env python3
"""Deterministic, zero-cost mock LLM for the RESILIENCE benchmark.

The SAME mock serves every language port (fairness: identical wire for all), and
routes behaviour by URL PREFIX so one server drives every scenario at once:

  POST /e402/chat/completions      -> always HTTP 402 (out of credits)
  POST /e401/chat/completions      -> always HTTP 401 (auth)
  POST /e500/chat/completions      -> always HTTP 500 (persistent server error)
  POST /retry/<id>/chat/completions-> per <id>: 1st call 429, then 200 final text
  POST /boom/chat/completions      -> turn 1 calls the `boom` tool; turn 2 final text

The `<id>` segment in /retry isolates each runner invocation so the 429-then-200
sequence is measured per run (no cross-runner state bleed). All final answers are
the fixed sentinel ``RESILIENCE_OK`` so runners can assert the recovered text.

Stdlib only. Byte-identical to the overhead ``mock_llm.py`` pattern next door.
"""
from __future__ import annotations

import argparse
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FINAL_TEXT = "RESILIENCE_OK"

# per-id first-call tracker for the /retry route
_retry_seen: dict[str, int] = {}
_retry_lock = threading.Lock()


def _has_tool_result(messages) -> bool:
    for m in messages:
        if m.get("role") == "tool":
            return True
        content = m.get("content")
        if isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    return True
    return False


def _final_body():
    return {
        "id": "chatcmpl-res-final", "object": "chat.completion", "created": 0,
        "model": "mock-model",
        "choices": [{"index": 0, "message": {"role": "assistant", "content": FINAL_TEXT},
                     "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
    }


def _tool_call_body(name):
    return {
        "id": "chatcmpl-res-tool", "object": "chat.completion", "created": 0,
        "model": "mock-model",
        "choices": [{"index": 0, "message": {
            "role": "assistant", "content": None,
            "tool_calls": [{"id": "call_1", "type": "function",
                            "function": {"name": name, "arguments": "{}"}}]},
            "finish_reason": "tool_calls"}],
        "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # silence
        pass

    def _read_json(self):
        te = (self.headers.get("Transfer-Encoding", "") or "").lower()
        if "chunked" in te:
            raw = self._read_chunked()
        else:
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw or b"{}")
        except Exception:  # noqa: BLE001
            return {}

    def _read_chunked(self):
        buf = bytearray()
        while True:
            size_line = self.rfile.readline().strip()
            if not size_line:
                break
            try:
                size = int(size_line.split(b";")[0], 16)
            except ValueError:
                break
            if size == 0:
                self.rfile.readline()
                break
            buf += self.rfile.read(size)
            self.rfile.readline()
        return bytes(buf)

    def _send(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._send({"ok": True})

    def do_POST(self):
        body = self._read_json()
        messages = body.get("messages", [])
        # strip the trailing /chat/completions (or /v1/chat/completions) to get the mode
        path = re.sub(r"/v1/chat/completions/?$|/chat/completions/?$", "", self.path)
        path = path.strip("/")

        if path == "e402":
            self._send({"error": {"message": "insufficient credits", "type": "insufficient_quota"}}, 402)
        elif path == "e401":
            self._send({"error": {"message": "invalid api key", "type": "invalid_request_error"}}, 401)
        elif path == "e500":
            self._send({"error": {"message": "internal server error"}}, 500)
        elif path.startswith("retry/"):
            rid = path[len("retry/"):]
            with _retry_lock:
                n = _retry_seen.get(rid, 0)
                _retry_seen[rid] = n + 1
            if n == 0:
                self.send_response(429)
                self.send_header("Retry-After", "0")
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                self._send(_final_body())
        elif path == "boom":
            if _has_tool_result(messages):
                self._send(_final_body())
            else:
                self._send(_tool_call_body("boom"))
        else:
            # default: a plain successful final answer
            self._send(_final_body())


def start_in_process(host="127.0.0.1", port=0):
    """Start the mock on a daemon thread; return (server, port). Call server.shutdown()."""
    srv = ThreadingHTTPServer((host, port), Handler)
    p = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8901)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"resilience-mock listening on http://{args.host}:{args.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
