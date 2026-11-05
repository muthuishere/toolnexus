"""TEST SUPPORT ONLY: a stateless MCP 2026-07-28 fixture server (ported from spikes/mcp-2026/python),
serving examples/mcp-2026/fixture.json over HTTP (WSGI) or stdio, with an optional opaque-token guard."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import socketserver
import sys
import threading
from wsgiref.simple_server import WSGIRequestHandler, WSGIServer

PV = "2026-07-28"
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
FIXTURE = os.path.join(REPO, "examples", "mcp-2026", "fixture.json")


class RpcError(Exception):
    def __init__(self, code, message, data=None):
        super().__init__(message)
        self.code, self.message, self.data = code, message, data

    def to_json(self):
        d = {"code": self.code, "message": self.message}
        if self.data is not None:
            d["data"] = self.data
        return d


def _parse_skill_uri(uri):
    sys.path.insert(0, os.path.join(REPO, "python", "src"))
    from toolnexus.mcp2026.wire import parse_skill_uri
    return parse_skill_uri(uri)


class Server:
    def __init__(self, fixture=FIXTURE):
        with open(fixture) as f:
            fx = json.load(f)
        self.info = fx["server"]
        self.tools = sorted(fx["tools"], key=lambda t: t["name"])
        self.executions = 0
        self.skills = []
        root = os.path.join(os.path.dirname(fixture), fx.get("skillsDir", ""))
        for d, _, files in os.walk(root):
            if "SKILL.md" in files:
                self.skills.append(_load_skill(d))
        self.skills.sort(key=lambda s: s["uri"])
        self.auth = None  # DevAuth or None
        self.fail_after_run = None  # None | "502" | "drop"
        self.extra_tools = []  # [(def, fn)]

    def dispatch(self, method, p):
        si = {"io.modelcontextprotocol/serverInfo": self.info}
        if method == "server/discover":
            return {"resultType": "complete", "supportedVersions": [PV],
                    "capabilities": {"tools": {}, "resources": {}, "extensions": {"io.modelcontextprotocol/skills": {}}},
                    "_meta": si}
        if method == "tools/list":
            return {"resultType": "complete", "tools": self.tools + [d for d, _ in self.extra_tools],
                    "ttlMs": 60000, "cacheScope": "public"}
        if method == "tools/call":
            return self._call(p)
        if method == "skills/list":
            return {"resultType": "complete", "skills": [{"uri": s["uri"], "frontmatter": {}} for s in self.skills],
                    "ttlMs": 60000, "cacheScope": "public"}
        if method in ("skills/get", "resources/read"):
            uri = p.get("uri") or ""
            try:
                _parse_skill_uri(uri)
            except ValueError as e:
                raise RpcError(-32602, f"Invalid URI: {e}")
            for s in self.skills:
                if method == "skills/get" and s["uri"] == uri:
                    return {"resultType": "complete", "skill": {"uri": uri, "frontmatter": {}, "resources": [
                        {"uri": u, "digest": "sha256:" + hashlib.sha256(d).hexdigest(), "size": len(d)}
                        for u, d in s["files"]]}}
                for u, d in s["files"]:
                    if method == "resources/read" and u == uri:
                        return {"resultType": "complete", "contents": [
                            {"uri": u, "mimeType": "text/markdown" if u.endswith(".md") else "text/plain",
                             "text": d.decode()}]}
            raise RpcError(-32602, "Unknown: " + uri)
        raise RpcError(-32601, "Method not found: " + method)

    def _call(self, p):
        name, args = p.get("name"), p.get("arguments") or {}
        for d, fn in self.extra_tools:
            if d["name"] == name:
                self.executions += 1
                return {"resultType": "complete", "content": [{"type": "text", "text": fn(args)}], "isError": False}
        if name == "echo":
            self.executions += 1
            t = args.get("text", "")
            return {"resultType": "complete", "content": [{"type": "text", "text": t}],
                    "structuredContent": {"text": t}, "isError": False}
        if name == "greet":
            nr = (p.get("inputResponses") or {}).get("name")
            if isinstance(nr, dict) and nr.get("action") == "accept" and (nr.get("content") or {}).get("name"):
                if p.get("requestState") != "st-1":
                    raise RpcError(-32602, "requestState not echoed")
                self.executions += 1
                n = nr["content"]["name"]
                return {"resultType": "complete", "content": [{"type": "text", "text": f"Hello, {n}!"}], "isError": False}
            return {"resultType": "input_required", "requestState": "st-1", "inputRequests": {"name": {
                "method": "elicitation/create", "params": {"mode": "form", "message": "What is your name?",
                    "requestedSchema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}}}}
        raise RpcError(-32602, "Unknown tool: " + str(name))

    # ---- WSGI ----
    def __call__(self, environ, start_response):
        path = environ.get("PATH_INFO", "")
        if self.auth is not None and path.startswith("/.well-known/oauth-protected-resource"):
            return _json(start_response, 200, {"resource": self.url, "authorization_servers": [self.auth.issuer],
                                               "scopes_supported": ["mcp:read"], "bearer_methods_supported": ["header"]})
        if path != "/mcp":
            return _json(start_response, 404, {"error": "nf"})
        n = int(environ.get("CONTENT_LENGTH") or 0)
        msg = json.loads(environ["wsgi.input"].read(n))
        method, p, rid = msg.get("method"), msg.get("params") or {}, msg.get("id")
        if environ.get("HTTP_MCP_METHOD") != method or environ.get("HTTP_MCP_PROTOCOL_VERSION") != PV:
            return _json(start_response, 400, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32020, "message": "Header mismatch"}})
        if method == "tools/call":
            want = p.get("name")
            got = environ.get("HTTP_MCP_NAME", "")
            if got.startswith("=?base64?"):
                got = base64.b64decode(got[9:-2]).decode()
            if got != want:
                return _json(start_response, 400, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32020, "message": "Header mismatch"}})
        if self.auth is not None:
            need = ["mcp:read"] + (["mcp:greet"] if method == "tools/call" and p.get("name") == "greet" else [])
            deny = self.auth.check(environ.get("HTTP_AUTHORIZATION", ""), self.url, need, self.url)
            if deny:
                status, www = deny
                return _json(start_response, status, {"error": "unauthorized"}, [("WWW-Authenticate", www)])
        try:
            res = self.dispatch(method, p)
            body = {"jsonrpc": "2.0", "id": rid, "result": res}
        except RpcError as e:
            body = {"jsonrpc": "2.0", "id": rid, "error": e.to_json()}
        if method == "tools/call" and self.fail_after_run == "502":
            return _json(start_response, 502, {"error": "bad gateway"})
        if method == "tools/call" and self.fail_after_run == "drop":
            raise ConnectionAbortedError("drop")
        if method == "tools/call" and p.get("name") == "echo" and environ.get("HTTP_X_SSE"):
            pass
        return _json(start_response, 200, body)


def _json(start_response, status, v, headers=()):
    b = json.dumps(v).encode()
    start_response(f"{status} X", [("Content-Type", "application/json"), ("Content-Length", str(len(b))), *headers])
    return [b]


def _load_skill(d):
    name = os.path.basename(d)
    files = []
    for root, _, names in os.walk(d):
        for n in names:
            path = os.path.join(root, n)
            rel = os.path.relpath(path, d).replace(os.sep, "/")
            with open(path, "rb") as f:
                files.append((f"skill://{name}/{rel}", f.read()))
    files.sort()
    return {"uri": f"skill://{name}/SKILL.md", "files": files}


class _Quiet(WSGIRequestHandler):
    def log_message(self, *a):
        pass

    def handle(self):
        try:
            super().handle()
        except Exception:
            pass


class _Srv(socketserver.ThreadingMixIn, WSGIServer):
    daemon_threads = True

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]
        self.setup_environ()

    def handle_error(self, *a):
        pass


class Running:
    def __init__(self, app):
        self.srv = _Srv(("127.0.0.1", 0), _Quiet)
        self.srv.set_app(app)
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def close(self):
        self.srv.shutdown()
        self.srv.server_close()


def serve_stdio(server):
    for line in sys.stdin.buffer:
        req = json.loads(line)
        if req.get("id") is None:
            continue
        msg = {"jsonrpc": "2.0", "id": req["id"]}
        meta = (req.get("params") or {}).get("_meta") or {}
        try:
            if meta.get("io.modelcontextprotocol/protocolVersion") != PV:
                raise RpcError(-32022, "Unsupported protocol version", {"supported": [PV], "requested": ""})
            msg["result"] = server.dispatch(req.get("method"), req.get("params") or {})
        except RpcError as e:
            msg["error"] = e.to_json()
        sys.stdout.write(json.dumps(msg) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    serve_stdio(Server())
