#!/usr/bin/env python3
"""toolnexus mock MCP server: stdio and streamable HTTP, both protocol paths. Stdlib only.

    python3 tools/mock-llm/mock_mcp.py                        # stdio
    python3 tools/mock-llm/mock_mcp.py --http --port 0        # prints "toolnexus mock mcp at http://127.0.0.1:<p>/mcp"
    ... --legacy                                              # answer server/discover with -32601 (frozen path only)

Tools (sorted): add(a, b) -> the sum as text; echo(text) -> the text, plus
structuredContent {"text": text}.

Paths (SPEC §2.2 / §2.2a): server/discover answers supportedVersions ["2026-07-28"]
(every result carries resultType "complete" and _meta serverInfo), so a 2026 client
stays on the direct path; with --legacy it answers -32601 and the client falls back
to initialize + tools/list on the frozen path. Both share the same tools.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

V2026 = "2026-07-28"
INFO = {"name": "mock-mcp", "version": "1.0.0"}
LEGACY = False
TOOLS = [
    {"name": "add", "description": "Add two integers.",
     "inputSchema": {"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
                     "required": ["a", "b"]}},
    {"name": "echo", "description": "Echo the text back.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
]


def _call(name, args):
    if name == "add":
        return {"content": [{"type": "text", "text": str(int(args.get("a", 0)) + int(args.get("b", 0)))}],
                "isError": False}
    if name == "echo":
        t = str(args.get("text", ""))
        return {"content": [{"type": "text", "text": t}], "structuredContent": {"text": t}, "isError": False}
    return {"content": [{"type": "text", "text": f"unknown tool {name}"}], "isError": True}


def handle(msg):
    """One JSON-RPC message in, the reply dict out (None for a notification)."""
    mid, method, p = msg.get("id"), msg.get("method"), msg.get("params") or {}
    if mid is None:
        return None
    v26 = isinstance(p.get("_meta"), dict) and "io.modelcontextprotocol/protocolVersion" in p["_meta"]

    def ok(res):
        if v26 or method == "server/discover":
            res = dict(res, resultType="complete",
                       _meta={"io.modelcontextprotocol/serverInfo": INFO})
        return {"jsonrpc": "2.0", "id": mid, "result": res}

    if method == "server/discover":
        if LEGACY:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "Method not found"}}
        return ok({"supportedVersions": [V2026], "capabilities": {"tools": {}}, "serverInfo": INFO})
    if method == "initialize":
        return ok({"protocolVersion": p.get("protocolVersion") or "2025-06-18",
                   "capabilities": {"tools": {}}, "serverInfo": INFO})
    if method == "ping":
        return ok({})
    if method == "tools/list":
        return ok({"tools": TOOLS})
    if method == "tools/call":
        return ok(_call(p.get("name"), p.get("arguments") or {}))
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"Method not found: {method}"}}


def serve_stdio():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            out = handle(json.loads(line))
        except ValueError:
            continue
        if out is not None:
            sys.stdout.write(json.dumps(out) + "\n")
            sys.stdout.flush()


class Http(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # never log headers
        pass

    def _send(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # no server-initiated stream
        self._send(405)

    def do_DELETE(self):
        self._send(200)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            msg = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self._send(400)
        out = [handle(m) for m in msg] if isinstance(msg, list) else handle(msg)
        if out is None or out == []:
            return self._send(202)
        self._send(200, json.dumps(out).encode())


def start_http(host="127.0.0.1", port=0, legacy=False):
    """Background HTTP server; returns (server, url)."""
    global LEGACY
    LEGACY = legacy
    srv = ThreadingHTTPServer((host, port), Http)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, f"http://{host}:{srv.server_address[1]}/mcp"


def main(argv=None):
    global LEGACY
    ap = argparse.ArgumentParser(description="Mock MCP server for toolnexus tests.")
    ap.add_argument("--http", action="store_true")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--legacy", action="store_true", help="refuse server/discover (frozen path)")
    a = ap.parse_args(argv)
    LEGACY = a.legacy
    if not a.http:
        return serve_stdio()
    srv, url = start_http(a.host, a.port, a.legacy)
    print(f"toolnexus mock mcp at {url}", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
