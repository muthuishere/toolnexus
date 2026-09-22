#!/usr/bin/env python3
"""Offline mock LLM for issue #86. No network, no API key, no cost.

Speaks the OpenAI chat-completions shape, answers with a fixed sentence, and
appends every request body it saw to requests.ndjson so a probe can assert on
the exact wire shape (specifically: is there a `tools` key at all?).
"""
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

OUT = os.environ.get("SPIKE86_LOG", "requests.ndjson")
REPLY = "A toolkit-less completion."


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("content-length", "0"))
        raw = self.rfile.read(n).decode()
        try:
            body = json.loads(raw)
        except Exception:
            body = {"_unparsed": raw}
        with open(OUT, "a") as f:
            f.write(json.dumps({"path": self.path, "body": body}) + "\n")
        out = json.dumps({
            "id": "mock", "model": body.get("model", "mock"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": REPLY}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8686
    print(f"mock llm on :{port} -> {OUT}", flush=True)
    HTTPServer(("127.0.0.1", port), H).serve_forever()
