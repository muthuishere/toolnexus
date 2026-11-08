"""The async face of an MCP 2026-07-28 server: discover-first probe, MRTR → §10, remote skills.

:class:`Session2026` quacks like the SDK ``ClientSession`` where the MCP source needs it
(``list_tools`` / ``list_resources`` / ``read_resource``), so tool filtering, naming, schema
and result mapping are shared with the frozen fallback path (§2.F) byte for byte.
"""
from __future__ import annotations

import asyncio
import hashlib
import inspect
import os
import secrets
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from mcp.types import ListResourcesResult, ListToolsResult, ReadResourceResult

from ..types import Answer, Request, ToolContext, ToolResult, pending
from .auth import AuthPending, OAuth
from .conn import Conn, HttpStatusError, NotJsonRpc
from .stdio import StdioClosed, StdioConn
from .wire import META_SERVER_INFO, PROTOCOL_VERSION, SKILLS_EXT, RpcError, parse_skill_uri

STDIO_PROBE_MAX = 5.0  # seconds: an old stdio server may ignore a pre-initialize request
MAX_INPUT_ROUNDS = 8


class AuthorizationPending(RuntimeError):
    """A pause-mode authorization during load with no ``wait_for`` (the server is marked failed)."""

    def __init__(self) -> None:
        super().__init__("authorization pending")


async def _ask(wait_for: Any, req: Request) -> Answer:
    r = wait_for(req)
    if inspect.isawaitable(r):
        r = await r
    if not isinstance(r, Answer):
        raise TypeError("wait_for must return an Answer")
    return r


def _denied(ans: Answer) -> str:
    return f"authorization denied: {ans.reason or 'cancelled'}"


def _callback_url(ans: Answer) -> str:
    cb = (ans.data or {}).get("callbackUrl")
    if not isinstance(cb, str) or not cb:
        raise ValueError("authorization answer has no data.callbackUrl")
    return cb


def input_request_to_request(key: str, ir: Any) -> Request:
    """One MRTR input request → a §10 Request (§2.5): form → ``input``, url → ``authorization``."""
    if not isinstance(ir, dict) or ir.get("method") != "elicitation/create":
        method = ir.get("method") if isinstance(ir, dict) else None
        raise ValueError(f"unsupported input request {key!r}: {method!r}")
    p = ir.get("params") if isinstance(ir.get("params"), dict) else {}
    rid = f"mrtr-{secrets.token_hex(6)}"
    msg = p.get("message")
    prompt = str(msg) if msg is not None else ""
    if p.get("mode") == "url":
        return Request(id=rid, kind="authorization", prompt=prompt, url=p.get("url") or None)
    req = Request(id=rid, kind="input", prompt=prompt)
    if p.get("requestedSchema"):
        req.data = {"schema": p["requestedSchema"]}
    return req


def answer_to_input_response(ans: Answer) -> dict[str, Any]:
    """§2.5: ok → accept(content), else decline when reason == declined, else cancel."""
    if ans.ok:
        return {"action": "accept", "content": ans.data or {}}
    return {"action": "decline" if ans.reason == "declined" else "cancel"}


@dataclass
class _CallState:
    name: str
    args: dict[str, Any]
    responses: Optional[dict[str, Any]] = None
    request_state: Any = None
    todo: list[tuple[str, Any]] = field(default_factory=list)
    rounds: int = 0


@dataclass
class _Paused:
    kind: str  # "auth" | "input"
    state: _CallState
    key: str = ""


class Session2026:
    """A connected 2026-path server. All network work runs on a worker thread."""

    def __init__(self, name: str, conn: Conn, discover: dict[str, Any], wait_for: Any, timeout: float):
        self.name = name
        self.conn = conn
        self.wait_for = wait_for
        self.timeout = timeout
        si = discover.get("serverInfo")
        if not isinstance(si, dict):
            meta = discover.get("_meta") if isinstance(discover.get("_meta"), dict) else {}
            si = meta.get(META_SERVER_INFO) if isinstance(meta.get(META_SERVER_INFO), dict) else {}
        self.server_info: dict[str, Any] = si
        caps = discover.get("capabilities")
        self.capabilities: dict[str, Any] = caps if isinstance(caps, dict) else {}
        self.tools_ttl_ms: Optional[float] = None
        self._paused: dict[str, _Paused] = {}

    # ---- plumbing ----

    async def _call(self, method: str, params: Optional[dict[str, Any]] = None) -> dict[str, Any]:
        return await asyncio.to_thread(self.conn.call, method, params)

    async def call_load(self, method: str, params: Optional[dict[str, Any]] = None) -> dict[str, Any]:
        """A load-time request: a pause-mode authorization goes through ``wait_for`` inline, or fails
        the server with ``authorization pending`` when there is none (§2A.1a)."""
        for _ in range(4):
            try:
                return await self._call(method, params)
            except AuthPending as ap:
                await self._finish_auth(ap.request)
        raise PermissionError("authorization failed")

    async def _finish_auth(self, req: Request) -> None:
        if self.wait_for is None:
            raise AuthorizationPending()
        ans = await _ask(self.wait_for, req)
        if not ans.ok:
            raise PermissionError(_denied(ans))
        assert self.conn.oauth is not None
        await asyncio.to_thread(self.conn.oauth.complete, req.id, _callback_url(ans))

    def close(self) -> None:
        self.conn.close()

    # ---- ClientSession-shaped surface ----

    async def list_tools(self, cursor: Optional[str] = None) -> ListToolsResult:
        r = await self.call_load("tools/list")
        ttl = r.get("ttlMs")
        self.tools_ttl_ms = float(ttl) if isinstance(ttl, (int, float)) and not isinstance(ttl, bool) else None
        tools = [t for t in (r.get("tools") or []) if isinstance(t, dict)]
        return ListToolsResult.model_validate({"tools": tools})  # no nextCursor on the 2026 path

    async def list_resources(self) -> ListResourcesResult:
        r = await self.call_load("resources/list")
        return ListResourcesResult.model_validate({"resources": r.get("resources") or []})

    async def read_resource(self, uri: Any) -> ReadResourceResult:
        r = await self.call_load("resources/read", {"uri": str(uri)})
        return ReadResourceResult.model_validate({"contents": r.get("contents") or []})

    # ---- tools/call with MRTR (§2.5) and pause/resume (§2A.1 step 10) ----

    async def call_tool(
        self,
        name: str,
        args: dict[str, Any],
        ctx: Optional[ToolContext],
        map_result: Callable[[dict[str, Any]], ToolResult],
        server: str,
    ) -> ToolResult:
        def err(msg: str) -> ToolResult:
            return ToolResult(output=msg, is_error=True, metadata={"server": server})

        answer = ctx.answer if ctx is not None else None
        rec = self._paused.pop(answer.id, None) if answer is not None else None
        if rec is None:
            st = _CallState(name, dict(args))
        else:
            assert answer is not None
            st = rec.state
            if rec.kind == "auth":
                if not answer.ok:
                    return err(_denied(answer))
                try:
                    assert self.conn.oauth is not None
                    await asyncio.to_thread(self.conn.oauth.complete, answer.id, _callback_url(answer))
                except Exception as e:  # noqa: BLE001
                    return err(str(e))
            else:
                assert st.responses is not None
                st.responses[rec.key] = answer_to_input_response(answer)
                st.todo.pop(0)
        try:
            return await self._run(st, map_result, server)
        except Exception as e:  # noqa: BLE001 - failures isolated per call
            return err(str(e))

    async def _run(
        self, st: _CallState, map_result: Callable[[dict[str, Any]], ToolResult], server: str
    ) -> ToolResult:
        while True:
            while st.todo:
                key, ir = st.todo[0]
                req = input_request_to_request(key, ir)
                if self.wait_for is None:
                    self._paused[req.id] = _Paused("input", st, key)
                    return _pending_result(req, server)
                ans = await _ask(self.wait_for, req)
                assert st.responses is not None
                st.responses[key] = answer_to_input_response(ans)
                st.todo.pop(0)
            p: dict[str, Any] = {"name": st.name, "arguments": st.args}
            if st.responses is not None:
                p["inputResponses"] = st.responses
            if st.request_state is not None:
                p["requestState"] = st.request_state
            try:
                res = await self._call("tools/call", p)
            except AuthPending as ap:
                # Rejected before dispatch: the server has NOT run this round. Resuming sends this
                # exact round (same inputResponses/requestState) for the first time.
                if self.wait_for is None:
                    self._paused[ap.request.id] = _Paused("auth", st)
                    return _pending_result(ap.request, server)
                ans = await _ask(self.wait_for, ap.request)
                if not ans.ok:
                    raise PermissionError(_denied(ans)) from None
                assert self.conn.oauth is not None
                await asyncio.to_thread(self.conn.oauth.complete, ap.request.id, _callback_url(ans))
                continue
            rt = res.get("resultType")
            if rt == "complete":
                return map_result(res)
            if rt != "input_required":
                raise ValueError(f"tools/call {st.name}: unknown resultType {rt!r}")
            st.rounds += 1
            if st.rounds > MAX_INPUT_ROUNDS:
                raise ValueError(f"tools/call {st.name}: too many input_required rounds")
            irs = res.get("inputRequests")
            if not isinstance(irs, dict) or not irs:
                raise ValueError(f"tools/call {st.name}: input_required without inputRequests")
            st.request_state = res.get("requestState")
            st.responses = {}
            st.todo = list(irs.items())

    # ---- skills extension (§2.7) ----

    def has_skills(self) -> bool:
        ext = self.capabilities.get("extensions")
        return isinstance(ext, dict) and SKILLS_EXT in ext

    async def fetch_skills(self) -> list[tuple[str, str, list[str]]]:
        """``(name, SKILL.md text, other resource paths)`` for every remote skill, verified (§2.7)."""
        out: list[tuple[str, str, list[str]]] = []
        r = await self.call_load("skills/list")
        for entry in r.get("skills") or []:
            uri = entry.get("uri") if isinstance(entry, dict) else None
            if not isinstance(uri, str):
                continue
            name, rel = parse_skill_uri(uri)
            if rel != "SKILL.md":
                raise ValueError(f"refusing skill {uri!r}: not skill://<name>/SKILL.md")
            sk = (await self.call_load("skills/get", {"uri": uri})).get("skill") or {}
            rels: list[str] = []
            digest = ""
            for m in sk.get("resources") or []:
                fu = m.get("uri") if isinstance(m, dict) and isinstance(m.get("uri"), str) else ""
                try:
                    n, frel = parse_skill_uri(fu)
                except ValueError:
                    n, frel = "", ""
                if n != name or not frel:
                    raise ValueError(f"refusing skill resource {fu!r}: outside skill {name}")
                if frel == "SKILL.md":
                    digest = str(m.get("digest") or "")
                else:
                    rels.append(frel)
            cs = (await self.call_load("resources/read", {"uri": uri})).get("contents") or []
            if not cs or not isinstance(cs[0], dict):
                raise ValueError(f"{uri}: empty resources/read")
            text = str(cs[0].get("text") or "")
            if digest and digest != "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest():
                raise ValueError(f"{uri}: digest mismatch")
            out.append((name, text, sorted(rels)))
        return out


def _pending_result(req: Request, server: str) -> ToolResult:
    r = pending(kind=req.kind, prompt=req.prompt, id=req.id, url=req.url, data=req.data, expiresAt=req.expiresAt)
    assert r.metadata is not None
    r.metadata["server"] = server
    return r


# ---------------------------------------------------------------- discover first (§2.2, §2.2a)


def _supports(res: dict[str, Any]) -> bool:
    sv = res.get("supportedVersions")
    return isinstance(sv, list) and PROTOCOL_VERSION in sv


async def probe_http(
    name: str, url: str, headers: Optional[dict[str, str]], timeout: float, oauth: OAuth, wait_for: Any
) -> Optional[Session2026]:
    """``server/discover`` over HTTP. ``None`` ⇒ frozen fallback; raises ⇒ load failure."""
    conn = Conn(url=url, headers=headers, timeout=timeout, oauth=oauth, client_info=_client_info())
    s = Session2026(name, conn, {}, wait_for, timeout)
    try:
        res = await s.call_load("server/discover")
    except (RpcError, NotJsonRpc):
        return None
    except HttpStatusError as e:
        if e.status in (400, 404, 405) or e.status >= 500:  # §2.2a: 5xx on discover ⇒ fallback
            return None
        raise
    if not _supports(res):
        return None
    return Session2026(name, conn, res, wait_for, timeout)


async def probe_stdio(
    name: str, argv: list[str], env: dict[str, str], cwd: Optional[str], timeout: float, wait_for: Any
) -> "Session2026 | StdioConn | None":
    """``server/discover`` over stdio, waiting ``min(timeout, 5 s)``. Returns a :class:`Session2026`
    for a 2026-07-28 server; the still-running probe :class:`StdioConn` when the server REPLIED without
    2026 (error or old result) — the frozen path continues on that same child (§2.2a reuse);
    ``None`` on silence (the probe child is killed, the caller spawns afresh). A child that exits
    before replying is a load failure."""
    sc = await asyncio.to_thread(StdioConn, argv, env, cwd, True)
    conn = Conn(stdio=sc, timeout=timeout, client_info=_client_info())
    try:
        res = await asyncio.to_thread(conn.call, "server/discover", None, min(timeout, STDIO_PROBE_MAX))
    except (RpcError, NotJsonRpc):
        return sc  # replied: the reader thread has stopped; hand the pipes to the SDK
    except TimeoutError:
        await asyncio.to_thread(sc.kill)
        return None
    except StdioClosed:
        await asyncio.to_thread(sc.kill)
        raise ConnectionError("stdio server exited before replying to server/discover") from None
    except BaseException:
        await asyncio.to_thread(sc.kill)
        raise
    if not _supports(res):
        return sc
    sc.resume()
    return Session2026(name, conn, res, wait_for, timeout)


def _client_info() -> dict[str, str]:
    try:
        from importlib.metadata import version

        v = version("toolnexus")
    except Exception:  # noqa: BLE001
        v = "0"
    return {"name": "toolnexus", "version": v}


def merged_env(cfg_env: Optional[dict[str, str]]) -> dict[str, str]:
    return {**os.environ, **(cfg_env or {})}

