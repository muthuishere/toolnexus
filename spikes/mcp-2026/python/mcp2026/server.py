"""A stateless MCP 2026-07-28 server over POST /mcp, as a WSGI app."""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
from dataclasses import dataclass
from typing import Any, Callable

from .http import MAX_BODY_BYTES, Request, respond, respond_json
from .protocol import (CODE_HEADER_MISMATCH, CODE_INVALID_PARAMS, CODE_METHOD_NOT_FOUND,
                       CODE_UNSUPPORTED_PROTOCOL, META_SERVER_INFO, META_VERSION, PROTOCOL_VERSION,
                       SKILLS_EXT, RpcError, dumps)
from .skills import parse_skill_uri


@dataclass
class ToolContext:
    """Passed to every code-defined tool. principal is the verified caller (None when unprotected)."""

    principal: Any = None  # serverauth.Principal


# handler(args, ctx) -> str (text result) | dict with "content" (a full CallToolResult) | any JSON value
ToolFunc = Callable[[dict, ToolContext], Any]


@dataclass
class _Skill:
    uri: str
    frontmatter: dict
    files: list[tuple[str, bytes]]  # (uri, data), sorted by uri; includes SKILL.md

    def entry(self) -> dict:
        return {"uri": self.uri, "frontmatter": self.frontmatter, "resources": [
            {"uri": u, "digest": "sha256:" + hashlib.sha256(d).hexdigest(), "size": len(d)} for u, d in self.files]}


class Server:
    """WSGI app: mount it anywhere, or mcp2026.serve(server). Add tools with tool(), protect with .auth."""

    def __init__(self) -> None:
        self.info: dict = {}
        self.auth = None  # ServerAuth; None = unprotected (cross-port checks run without auth)
        self._handlers: dict[str, ToolFunc] = {}
        self._tools: list[dict] = []
        self._skills: list[_Skill] = []

    def tool(self, name: str, description: str, handler: ToolFunc) -> "Server":
        """Registers a code-defined tool (checked before the fixture tools). Not safe once serving."""
        self._handlers[name] = handler
        self._tools = [t for t in self._tools if t["name"] != name]
        self._tools.append({"name": name, "description": description, "inputSchema": {"type": "object"}})
        self._tools.sort(key=lambda t: t["name"])
        return self

    @classmethod
    def load(cls, fixture_path: str) -> "Server":
        with open(fixture_path) as f:
            fx = json.load(f)
        s = cls()
        s.info = fx.get("server") or {}
        for t in fx.get("tools") or []:
            if not isinstance(t.get("name"), str):
                raise ValueError(f"fixture tool without a name: {t}")
            s._tools.append(t)
        s._tools.sort(key=lambda t: t["name"])
        root = os.path.join(os.path.dirname(fixture_path), fx.get("skillsDir", ""))
        for d, _, files in os.walk(root):
            if "SKILL.md" in files:
                s._skills.append(_load_skill(d))
        s._skills.sort(key=lambda k: k.uri)
        return s

    def _server_info(self) -> dict:
        return {META_SERVER_INFO: self.info}

    # ---- WSGI ----

    def __call__(self, environ, start_response):
        req = Request(environ)
        if self.auth is not None and req.method == "GET":
            prm = self.auth.metadata(req)
            if prm is not None:
                return respond_json(start_response, 200, prm)
        if req.path != "/mcp":
            return respond(start_response, 404, "404 page not found\n", [("Content-Type", "text/plain")])
        if req.method != "POST":
            return respond(start_response, 405)
        try:
            body = req.body(MAX_BODY_BYTES)
        except ValueError:
            return _write_rpc(start_response, 413, None, None, RpcError(-32600, "Request body too large or unreadable"))
        try:
            msg = json.loads(body)
            if not isinstance(msg, dict):
                raise ValueError
        except ValueError:
            return _write_rpc(start_response, 400, None, None, RpcError(-32700, "Parse error"))
        rid, method = msg.get("id"), msg.get("method")
        if rid is None:  # notification: none defined in core; accept
            return respond(start_response, 202)
        method = method if isinstance(method, str) else ""
        params = msg.get("params") if isinstance(msg.get("params"), dict) else {}
        err = self._validate(req, method, params)
        if err:
            return _write_rpc(start_response, 400, rid, None, err)
        ctx = ToolContext()
        if self.auth is not None:
            principal, deny = self.auth.check(req, method, params)
            if deny is not None:
                status, headers = deny
                return respond(start_response, status, b'{"error":"unauthorized"}',
                               [("Content-Type", "application/json"), *headers])
            ctx.principal = principal
        try:
            res = self.dispatch(method, params, ctx)
        except RpcError as e:
            return _write_rpc(start_response, 404 if e.code == CODE_METHOD_NOT_FOUND else 200, rid, None, e)
        return _write_rpc(start_response, 200, rid, res, None)

    def _validate(self, req: Request, method: str, p: dict) -> RpcError | None:
        def mismatch(m):
            return RpcError(CODE_HEADER_MISMATCH, "Header mismatch: " + m)

        hv = req.header("MCP-Protocol-Version")
        if hv == "":
            return mismatch("MCP-Protocol-Version header missing")
        hm = req.header("Mcp-Method")
        if hm != method:
            return mismatch(f"Mcp-Method header value '{hm}' does not match body value '{method}'")
        if method in ("tools/call", "resources/read", "prompts/get"):
            want = p.get("uri" if method == "resources/read" else "name")
            got, ok = _decode_header(req.header("Mcp-Name"))
            if not ok or got != want:
                return mismatch(f"Mcp-Name header value '{got}' does not match body value '{want if isinstance(want, str) else ''}'")
        meta = p.get("_meta") if isinstance(p.get("_meta"), dict) else {}
        mv = meta.get(META_VERSION) if isinstance(meta.get(META_VERSION), str) else ""
        if mv != hv:
            return mismatch(f"MCP-Protocol-Version header value '{hv}' does not match _meta value '{mv}'")
        if mv != PROTOCOL_VERSION:
            return RpcError(CODE_UNSUPPORTED_PROTOCOL, "Unsupported protocol version",
                            {"supported": [PROTOCOL_VERSION], "requested": mv})
        return None

    # ---- methods (also used by serve_stdio) ----

    def dispatch(self, method: str, p: dict, ctx: ToolContext | None = None) -> dict:
        ctx = ctx or ToolContext()
        si = self._server_info()
        if method == "server/discover":
            return {"resultType": "complete", "supportedVersions": [PROTOCOL_VERSION],
                    "capabilities": {"tools": {}, "resources": {}, "extensions": {SKILLS_EXT: {}}},
                    "_meta": si, "ttlMs": 3600000, "cacheScope": "public"}
        if method == "tools/list":
            return {"resultType": "complete", "tools": self._tools, "ttlMs": 60000, "cacheScope": "public", "_meta": si}
        if method == "tools/call":
            return self._call_tool(p, ctx)
        if method == "skills/list":
            return {"resultType": "complete", "skills": [s.entry() for s in self._skills],
                    "ttlMs": 60000, "cacheScope": "public", "_meta": si}
        if method == "skills/get":
            uri = p.get("uri") if isinstance(p.get("uri"), str) else ""
            try:
                parse_skill_uri(uri)
            except ValueError as e:
                raise RpcError(CODE_INVALID_PARAMS, f"Invalid skill URI: {e}") from None
            for s in self._skills:
                if s.uri == uri:
                    return {"resultType": "complete", "skill": s.entry(), "_meta": si}
            raise RpcError(CODE_INVALID_PARAMS, "Unknown skill: " + uri)
        if method == "resources/read":
            uri = p.get("uri") if isinstance(p.get("uri"), str) else ""
            try:
                parse_skill_uri(uri)
            except ValueError as e:
                raise RpcError(CODE_INVALID_PARAMS, f"Invalid resource URI: {e}") from None
            for s in self._skills:
                for u, data in s.files:
                    if u == uri:
                        mime = "text/markdown" if uri.endswith(".md") else "text/plain"
                        return {"resultType": "complete",
                                "contents": [{"uri": uri, "mimeType": mime, "text": data.decode("utf-8", "replace")}],
                                "ttlMs": 60000, "cacheScope": "public", "_meta": si}
            raise RpcError(CODE_INVALID_PARAMS, "Resource not found: " + uri)
        raise RpcError(CODE_METHOD_NOT_FOUND, "Method not found: " + method)

    def _call_tool(self, p: dict, ctx: ToolContext) -> dict:
        name = p.get("name") if isinstance(p.get("name"), str) else ""
        args = p.get("arguments") if isinstance(p.get("arguments"), dict) else {}
        h = self._handlers.get(name)
        if h is not None:
            try:
                v = h(args, ctx)
            except Exception as e:  # a tool error is a result, not a protocol error
                return {"resultType": "complete", "content": [{"type": "text", "text": str(e)}], "isError": True}
            if isinstance(v, str):
                return {"resultType": "complete", "content": [{"type": "text", "text": v}], "isError": False}
            if isinstance(v, dict) and "content" in v:
                v.setdefault("resultType", "complete")
                return v
            try:
                t = dumps(v)
            except (TypeError, ValueError) as e:
                raise RpcError(-32603, f"tool result is not JSON: {e}") from None
            return {"resultType": "complete", "content": [{"type": "text", "text": t}], "structuredContent": v,
                    "isError": False}

        def result(t: str, structured: dict) -> dict:
            return {"resultType": "complete", "content": [{"type": "text", "text": t}], "isError": False,
                    "_meta": self._server_info(), "structuredContent": structured}

        if name == "echo":
            t = args.get("text") if isinstance(args.get("text"), str) else ""
            return result(t, {"text": t})
        if name == "greet":
            nr = (p.get("inputResponses") or {}).get("name") if isinstance(p.get("inputResponses"), dict) else None
            if isinstance(nr, dict) and nr.get("action") == "accept" and isinstance(nr.get("content"), dict):
                n = nr["content"].get("name")
                if isinstance(n, str) and n:
                    return result(f"Hello, {n}!", {"greeting": f"Hello, {n}!"})
            # Missing/declined answer: ask (again), per MRTR error-handling guidance.
            return {"resultType": "input_required", "_meta": self._server_info(), "inputRequests": {
                "name": {"method": "elicitation/create", "params": {
                    "mode": "form", "message": "What is your name?",
                    "requestedSchema": {"type": "object", "properties": {"name": {"type": "string"}},
                                        "required": ["name"]}}}}}
        raise RpcError(CODE_INVALID_PARAMS, "Unknown tool: " + name)


def _load_skill(d: str) -> _Skill:
    with open(os.path.join(d, "SKILL.md"), encoding="utf-8") as f:
        fm = parse_frontmatter(f.read())
    name = fm.get("name", "")
    if not name or name != os.path.basename(d):
        raise ValueError(f"skill {d}: name must match dir")
    files = []
    for root, _, names in os.walk(d):
        for n in names:
            path = os.path.join(root, n)
            rel = os.path.relpath(path, d).replace(os.sep, "/")
            with open(path, "rb") as f:
                files.append((f"skill://{name}/{rel}", f.read()))
    files.sort()
    return _Skill(f"skill://{name}/SKILL.md", fm, files)


def parse_frontmatter(md: str) -> dict:
    """The flat `key: value` YAML subset the fixture skills use."""
    fm: dict = {}
    md = md.replace("\r\n", "\n")
    if not md.startswith("---\n"):
        return fm
    end = md.find("\n---", 4)
    if end < 0:
        return fm
    for line in md[4:end].split("\n"):
        k, sep, v = line.partition(":")
        if sep and not line.startswith(" "):
            fm[k.strip()] = v.strip().strip("\"'")
    return fm


def _decode_header(v: str) -> tuple[str, bool]:
    if v.startswith("=?base64?") and v.endswith("?=") and len(v) >= 11:
        try:
            return base64.b64decode(v[9:-2], validate=True).decode("utf-8"), True
        except (binascii.Error, UnicodeDecodeError):
            return "", False
    return v, v != ""


def _write_rpc(start_response, status: int, rid, result, err: RpcError | None):
    msg: dict = {"jsonrpc": "2.0", "id": rid}
    if err is not None:
        msg["error"] = err.to_json()
    else:
        msg["result"] = result
    return respond(start_response, status, json.dumps(msg) + "\n", [("Content-Type", "application/json")])
