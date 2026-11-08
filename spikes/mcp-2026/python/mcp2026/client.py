"""A stateless MCP 2026-07-28 client: Streamable HTTP or stdio, MRTR, lazy tool search, skill download."""
from __future__ import annotations

import base64
import hashlib
import itertools
import json
import math
import os
import threading
import time
from typing import Callable

from .http import HttpClient, default_http
from .protocol import (META_CLIENT_CAPS, META_CLIENT_INFO, META_VERSION, PROTOCOL_VERSION, RpcError, dumps)
from .skills import parse_skill_uri

# respond(key, request) -> answer. Answers one MRTR input request (stands in for toolnexus §10 waitFor).
Responder = Callable[[str, dict], dict]


class Client:
    """Point it at a server's /mcp URL; there is no handshake. Client.stdio(argv) spawns a stdio server."""

    def __init__(self, url: str = "", *, http: HttpClient | None = None, auth=None) -> None:
        self.url = url
        self.version = PROTOCOL_VERSION
        self.info = {"name": "toolnexus-spike-python", "version": "0.0.1"}
        self.caps: dict = {"elicitation": {"form": {}}}
        self.http = http or default_http()
        self.auth = auth  # OAuth; None = no authorization (HTTP only)
        # tool_search: tools() exposes search_tools + call_tool instead of every server tool.
        self.tool_search = False
        self.description = ""  # what this server is for; shown in search_tools results
        self._stdio = None
        self._ids = itertools.count(1)
        self._tool_cache: list[dict] | None = None
        self._tool_expiry = 0.0
        self._tool_lock = threading.Lock()
        self._header_override: dict[str, str] = {}  # the self-test forges headers (negative cases only)

    @classmethod
    def stdio(cls, argv: list[str], *, env: dict | None = None, cwd: str | None = None, stderr=None) -> "Client":
        """Spawns argv and speaks one JSON-RPC message per line. stderr None = passed through, never parsed."""
        from .stdio import StdioConn
        c = cls("")
        c._stdio = StdioConn(argv, env=env, cwd=cwd, stderr=stderr)
        return c

    def close(self) -> None:
        """Stops a stdio server (close stdin, kill after 2 s); no-op for HTTP."""
        if self._stdio is not None:
            self._stdio.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # ---- the wire ----

    def call(self, method: str, params: dict | None = None) -> dict:
        """Sends one request (params get the per-request _meta). Raises RpcError for a JSON-RPC error.

        Retry rule (spec issue #3394): a request is re-sent ONLY after a 401, or a 403 insufficient_scope,
        carrying WWW-Authenticate: the server rejected it before dispatch, so the tool never ran. A lost
        connection, timeout, 5xx or unreadable response raises and is NEVER retried."""
        params = {} if params is None else params
        params["_meta"] = {META_VERSION: self.version, META_CLIENT_CAPS: self.caps, META_CLIENT_INFO: self.info}
        rid = next(self._ids)
        body = json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}).encode()
        msg = self._stdio.round_trip(rid, body) if self._stdio else self._http_round_trip(method, params, body)
        if msg.get("error") is not None:
            e = msg["error"] if isinstance(msg["error"], dict) else {}
            raise RpcError(int(e.get("code", 0)), str(e.get("message", "")), e.get("data"))
        res = msg.get("result")
        if not isinstance(res, dict):  # hostile/broken server: no result and no error
            raise ValueError("response has neither result nor error")
        res.setdefault("resultType", "complete")  # earlier-protocol servers
        return res

    def _http_round_trip(self, method: str, params: dict, body: bytes) -> dict:
        attempt = 0
        while True:
            sent = self.auth._bearer(self.url) if self.auth else ""
            resp = self._post(method, params, body, sent)  # an exception here is never retried
            www = resp.header("WWW-Authenticate")
            if self.auth is None or resp.status not in (401, 403) or not www:
                break
            if attempt >= 3:  # spec: Step-Up retry limit
                raise PermissionError(f"authorization failed: HTTP {resp.status} after {attempt + 1} attempts")
            self.auth._challenge(self.url, resp.status, www, sent, lambda: self.call(method, params))
            attempt += 1
        msg = None
        if resp.header("Content-Type").startswith("text/event-stream"):
            # Take the last data: payload that carries a result or error (the final response).
            for line in resp.body.decode("utf-8", "replace").splitlines():
                if line.startswith("data:"):
                    try:
                        m = json.loads(line[5:])
                    except ValueError:
                        continue
                    if isinstance(m, dict) and ("result" in m or "error" in m):
                        msg = m
        else:
            try:
                msg = json.loads(resp.body)
            except ValueError as e:
                raise ValueError(f"HTTP {resp.status}: {e}") from None
        if not isinstance(msg, dict) or (msg.get("result") is None and msg.get("error") is None):
            raise ValueError(f"HTTP {resp.status}: response has neither result nor error")
        return msg

    def _post(self, method: str, params: dict, body: bytes, bearer: str):
        h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
             "MCP-Protocol-Version": self.version, "Mcp-Method": method}
        if method in ("tools/call", "prompts/get"):
            h["Mcp-Name"] = encode_header(params.get("name") or "")
        elif method == "resources/read":
            h["Mcp-Name"] = encode_header(params.get("uri") or "")
        h.update(self._header_override)
        if bearer:
            h["Authorization"] = "Bearer " + bearer
        return self.http.request("POST", self.url, body, h)

    # ---- tools ----

    def call_tool(self, name: str, args: dict | None = None, respond: Responder | None = None) -> dict:
        """tools/call, looping over input_required rounds via respond."""
        return self._call_tool(name, args or {}, respond, None, None, 0)

    def _call_tool(self, name, args, respond, responses, state, rnd) -> dict:
        from .auth import PendingError
        while rnd < 8:
            p: dict = {"name": name, "arguments": args}
            if responses is not None:
                p["inputResponses"] = responses
            if state is not None:
                p["requestState"] = state
            try:
                res = self.call("tools/call", p)
            except PendingError as pe:
                # The paused request got 401/403 before dispatch: the server has NOT run this round. Resume
                # sends exactly this round (same inputResponses/requestState) for the first time — earlier
                # rounds are not replayed and the user is not re-asked.
                r, st, n = responses, state, rnd
                self.auth._set_retry(pe.pending.id, lambda: self._call_tool(name, args, respond, r, st, n))
                raise
            if res.get("resultType") != "input_required":
                return res
            state = res.get("requestState")
            responses = {}
            if respond is None:
                raise ValueError(f"tools/call {name}: server asked for input but no Responder was given")
            for k, v in (res.get("inputRequests") or {}).items():
                if not isinstance(v, dict):
                    raise ValueError(f"tools/call {name}: malformed input request {k!r}")
                responses[k] = respond(k, v)
            rnd += 1
        raise ValueError(f"tools/call {name}: too many input_required rounds")

    def _list_tools(self) -> list[dict]:
        """tools/list, cached for the server's ttlMs."""
        with self._tool_lock:
            if self._tool_cache is not None and time.monotonic() < self._tool_expiry:
                return self._tool_cache
            r = self.call("tools/list")
            tools = [t for t in (r.get("tools") or []) if isinstance(t, dict)]
            ttl = r.get("ttlMs") if isinstance(r.get("ttlMs"), (int, float)) else 0
            self._tool_cache, self._tool_expiry = tools, time.monotonic() + ttl / 1000
            return tools

    def tools(self) -> list[dict]:
        """The tool definitions to give the model: every server tool, or (tool_search) the two meta-tools."""
        if not self.tool_search:
            return self._list_tools()
        about = self.description or "this MCP server"
        return [
            {"name": "search_tools",
             "description": f"Search the tools of {about} by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.",
             "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}},
                             "required": ["query"]}},
            {"name": "call_tool", "description": "Run a tool found with search_tools.",
             "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "arguments": {"type": "object"}},
                             "required": ["name"]}},
        ]

    def invoke(self, name: str, args: dict | None = None, respond: Responder | None = None) -> dict:
        """Runs a tool the model asked for: a server tool, or search_tools / call_tool in tool_search mode."""
        args = args or {}
        if not self.tool_search:
            return self.call_tool(name, args, respond)
        if name == "search_tools":
            limit = args.get("limit")
            limit = int(limit) if isinstance(limit, (int, float)) and limit > 0 else 5
            hits = self.search_tools(str(args.get("query") or ""), limit)
            return {"resultType": "complete", "content": [{"type": "text", "text": dumps(hits)}],
                    "structuredContent": {"tools": hits}, "isError": False}
        if name == "call_tool":
            a = args.get("arguments")
            return self.call_tool(str(args.get("name") or ""), a if isinstance(a, dict) else {}, respond)
        raise ValueError(f"unknown tool {name!r} (tool_search mode exposes search_tools and call_tool)")

    def search_tools(self, query: str, limit: int = 5) -> list[dict]:
        """Ranks the server's tools against query (BM25; name tokens count twice)."""
        tools = self._list_tools()
        docs, df, total = [], {}, 0
        for t in tools:
            nt = tokens(str(t.get("name") or ""))
            d = nt + nt + tokens(str(t.get("description") or ""))
            docs.append(d)
            total += len(d)
            for w in set(d):
                df[w] = df.get(w, 0) + 1
        avg = total / max(1, len(tools))
        hits = []
        for i, d in enumerate(docs):
            tf: dict[str, int] = {}
            for w in d:
                tf[w] = tf.get(w, 0) + 1
            s = 0.0
            for q in tokens(query):
                f = float(tf.get(q, 0))
                if f == 0:
                    continue
                idf = math.log(1 + (len(tools) - df[q] + 0.5) / (df[q] + 0.5))
                s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * len(d) / avg))
            if s > 0:
                hits.append((i, s))
        hits.sort(key=lambda h: -h[1])  # stable
        return [{"name": tools[i].get("name"), "description": tools[i].get("description"),
                 "inputSchema": tools[i].get("inputSchema"), "server": self.description} for i, _ in hits[:limit]]

    # ---- skills ----

    def download_skill(self, uri: str, dir: str) -> None:
        """Fetches a skill (skills/get + resources/read per file) into dir/<name>/, verifying each sha256
        digest. A file whose URI is not inside the skill is refused; nothing is written unless all pass."""
        name, _ = parse_skill_uri(uri)
        sk = self.call("skills/get", {"uri": uri}).get("skill") or {}
        files = []
        for m in sk.get("resources") or []:
            fu = m.get("uri") if isinstance(m, dict) and isinstance(m.get("uri"), str) else ""
            try:
                n, rel = parse_skill_uri(fu)
            except ValueError:
                n, rel = "", ""
            if n != name or not rel:
                raise ValueError(f"refusing skill resource {fu!r}: outside skill {name}")
            cs = self.call("resources/read", {"uri": fu}).get("contents") or []
            if not cs:
                raise ValueError(f"{fu}: empty resources/read")
            t = str(cs[0].get("text") or "").encode("utf-8")
            d = m.get("digest") or ""
            if d and d != "sha256:" + hashlib.sha256(t).hexdigest():
                raise ValueError(f"{fu}: digest mismatch")
            files.append((os.path.join(dir, name, *rel.split("/")), t))
        for path, data in files:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)


def encode_header(v: str) -> str:
    safe = v == v.strip() and not (v.startswith("=?base64?") and v.endswith("?=")) and \
        all(0x20 <= ord(ch) <= 0x7E for ch in v)
    return v if safe else "=?base64?" + base64.b64encode(v.encode()).decode() + "?="


def tokens(s: str) -> list[str]:
    """Lowercases and splits on non-alphanumerics and camelCase boundaries."""
    out, cur, prev_lower = [], [], False
    for ch in s:
        if ch.isupper() and prev_lower:
            if cur:
                out.append("".join(cur).lower())
            cur = [ch]
        elif ch.isalpha() or ch.isdigit():
            cur.append(ch)
        elif cur:
            out.append("".join(cur).lower())
            cur = []
        prev_lower = ch.islower()
    if cur:
        out.append("".join(cur).lower())
    return out

