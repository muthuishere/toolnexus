"""MCP — INBOUND, embeddable: a plain streamable-HTTP MCP server as an ASGI app with
generic per-request hooks (SPEC §7C.1, ADR 0039).

``toolkit.serve(addr, mcp=…)`` (serve.py) owns a listener and exposes one static tool
list. :meth:`Toolkit.mcp_handler` returns an ASGI application — ``async (scope, receive,
send)`` — that a host mounts on its own router (Starlette ``Mount``, FastAPI
``app.mount``, a bare uvicorn app, …) behind its own TLS, CORS and OAuth. Every policy is
a hook; toolnexus has no notion of tenant, user, role or storage — the host decides those
inside the hooks.

The transport is implemented here by hand over plain JSON-RPC, mirroring the Go reference
(``golang/mcphandler.go``) byte for byte: status codes, error codes and messages, header
handling and wire shapes. No web framework and no MCP SDK server type is involved, so no
dependency is added and the public API carries toolnexus's own types only.

Every hook receives an :class:`McpContext` (identity, request, session id, request
``params._meta``). Hooks may be plain functions or coroutines. Inside a tool's ``execute``
the same values are reachable through :func:`identity_from`, :func:`request_from`,
:func:`mcp_session_id_from` and :func:`mcp_request_meta_from` (backed by a contextvar).
"""
from __future__ import annotations

import asyncio
import base64
import contextvars
import hashlib
import inspect
import itertools
import json
import os
import re
import secrets
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional, Protocol, Union

from .mcp_serve import DEFAULT_NAME, DEFAULT_VERSION, tool_result_to_call_result
from .types import Answer, Request, Tool, ToolContext, ToolResult, pending_of

#: The opt-in stateless wire of SPEC §7C.2 (ADR 0040). Never a default; ``initialize``
#: never negotiates it.
MCP_PROTOCOL_2026 = "2026-07-28"
#: The SEP-2640 skills extension key advertised by ``server/discover``.
MCP_SKILLS_EXTENSION = "io.modelcontextprotocol/skills"
_META_VERSION = "io.modelcontextprotocol/protocolVersion"
_META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities"
_META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

#: MCP protocol versions whose wire the handler implements (ADR 0039 §versions, ADR 0040).
#: ``2026-07-28`` is opt-in only (list it in ``protocol_versions``). ``2025-03-26``
#: (permits batching) is never accepted.
MCP_HANDLER_IMPLEMENTED_VERSIONS: tuple[str, ...] = (MCP_PROTOCOL_2026, "2025-11-25", "2025-06-18")
_DEFAULT_VERSIONS: tuple[str, ...] = ("2025-06-18",)

MCP_ALLOW = "allow"
MCP_DENY = "deny"
MCP_CONFIRM = "confirm"


# ── public types ──────────────────────────────────────────────────────────


@dataclass
class McpDecision:
    """What ``before_call`` returns. The default allows.

    - ``allow``: the tool runs.
    - ``deny``: the tool does not run; the caller gets ``result`` when set, else an
      ``is_error`` result carrying ``message`` (default "denied").
    - ``confirm``: the client is asked (``elicitation/create``) when it declared the
      elicitation capability; accept ⇒ run, otherwise ⇒ ``is_error`` "declined". When the
      client cannot elicit, the caller gets ``result`` when set, else ``is_error``
      "confirmation required: <message>".
    """

    kind: str = MCP_ALLOW
    message: str = ""
    result: Optional[ToolResult] = None


@dataclass
class McpServerInfo:
    """Advertised at ``initialize``. ``title`` empty ⇒ omitted."""

    name: str = ""
    title: str = ""
    version: str = ""
    instructions: str = ""


@dataclass
class McpResource:
    uri: str
    name: str = ""
    title: str = ""
    description: str = ""
    mime_type: str = ""


@dataclass
class McpResourceContent:
    """One ``resources/read`` entry. Set ``text``, or ``blob`` (base64)."""

    uri: str
    mime_type: str = ""
    text: str = ""
    blob: str = ""


@dataclass
class McpPromptArgument:
    name: str
    description: str = ""
    required: bool = False


@dataclass
class McpPrompt:
    name: str
    title: str = ""
    description: str = ""
    arguments: list[McpPromptArgument] = field(default_factory=list)


@dataclass
class McpPromptMessage:
    role: str  # "user" | "assistant"
    text: str


@dataclass
class McpPromptResult:
    messages: list[McpPromptMessage] = field(default_factory=list)
    description: str = ""


@dataclass
class McpCallEvent:
    """Surfaced once per inbound ``tools/call`` (also for deny/confirm outcomes)."""

    name: str
    source: str
    ms: int = 0
    is_error: bool = False
    err: Optional[BaseException] = None  # the exception execute raised, if any
    result_bytes: int = 0  # size of the JSON CallToolResult sent back
    decision: str = MCP_ALLOW
    identity: Any = None
    session_id: str = ""


@dataclass
class McpRequest:
    """The inbound HTTP request being served (``request_from``). ``headers`` keys are
    lower-case; :meth:`header` returns the first value, like Go's ``Header.Get``."""

    method: str
    path: str
    query_string: str
    headers: dict[str, list[str]]
    host: str
    scope: dict[str, Any] = field(repr=False, default_factory=dict)

    def header(self, name: str) -> str:
        vals = self.headers.get(name.lower())
        return vals[0] if vals else ""


@dataclass
class McpContext:
    """The per-request context every hook receives."""

    identity: Any = None
    request: Optional[McpRequest] = None
    session_id: str = ""
    meta: Optional[dict[str, Any]] = None


class McpSessionIds(Protocol):
    """Issues and checks ``Mcp-Session-Id`` values. Methods may be sync or async. The
    default is an in-memory set; a host plugs its own (signed ids, a DB lookup)."""

    def generate(self, ctx: McpContext) -> Any: ...
    def validate(self, ctx: McpContext, id: str) -> Any: ...  # noqa: A002 — bool, False ⇒ 404
    def terminate(self, ctx: McpContext, id: str) -> Any: ...  # noqa: A002


Hook = Callable[..., Union[Any, Awaitable[Any]]]


@dataclass
class McpSkillFile:
    """One text file of a published skill; ``path`` relative, ``/``-separated."""

    path: str
    text: str


@dataclass
class McpSkill:
    """One skill published through the SEP-2640 skills extension (§7C.2). ``files``
    MUST include ``SKILL.md``. ``frontmatter`` None ⇒ ``{name}``."""

    name: str
    frontmatter: Optional[dict[str, Any]] = None
    files: list[McpSkillFile] = field(default_factory=list)


class McpAuthError(Exception):
    """An ``authenticate`` failure with an explicit HTTP status (401 | 403) and
    challenge. On any wire the handler answers ``status`` with ``WWW-Authenticate`` =
    ``challenge`` (a non-empty ``www_authenticate`` hook result wins) and the body
    ``{"error":"unauthorized"}``. ``reason`` is never sent on the wire."""

    def __init__(self, status: int = 401, challenge: str = "", reason: str = "") -> None:
        super().__init__(reason or "unauthorized")
        self.status = status or 401
        self.challenge = challenge
        self.reason = reason


@dataclass
class McpHandlerConfig:
    """Configures :meth:`Toolkit.mcp_handler`. Every field is optional."""

    name: str = ""
    title: str = ""
    version: str = ""
    instructions: str = ""
    #: ``server_info(ctx) -> McpServerInfo``; wins over the static fields.
    server_info: Optional[Hook] = None
    #: Accepted versions, newest first; unimplemented entries dropped. Default ["2025-06-18"].
    protocol_versions: Optional[list[str]] = None
    #: ``authenticate(request) -> identity``; raising ⇒ HTTP 401.
    authenticate: Optional[Hook] = None
    #: ``www_authenticate(request, err) -> str``; the 401's WWW-Authenticate header.
    www_authenticate: Optional[Hook] = None
    #: ``list_tools(ctx) -> list[Tool]``, per request; None ⇒ the toolkit's tools.
    list_tools: Optional[Hook] = None
    #: ``before_call(ctx, tool, args: dict) -> McpDecision``; raising ⇒ deny with its text.
    before_call: Optional[Hook] = None
    #: Elicitation round-trip bound, seconds. Default 300.
    confirm_timeout: float = 0
    #: ``on_call(McpCallEvent)``; its exceptions are isolated.
    on_call: Optional[Hook] = None
    list_resources: Optional[Hook] = None  # (ctx) -> list[McpResource]
    read_resource: Optional[Hook] = None  # (ctx, uri) -> list[McpResourceContent]
    list_prompts: Optional[Hook] = None  # (ctx) -> list[McpPrompt]
    get_prompt: Optional[Hook] = None  # (ctx, name, args: dict[str, str]) -> McpPromptResult
    #: ``list_skills(ctx) -> list[McpSkill]``: the SEP-2640 skills extension on the
    #: 2026-07-28 wire (skills/list, skills/get, resources/read of skill:// URIs).
    #: None ⇒ not advertised. :func:`load_mcp_skills` builds the list from a directory.
    list_skills: Optional[Hook] = None
    session_ids: Optional[McpSessionIds] = None
    #: No Mcp-Session-Id issued or checked; elicitation is never used.
    stateless: bool = False
    #: Stateless only: echoed as Mcp-Session-Id on initialize, never validated.
    stateless_session_id: str = ""
    #: POST body cap. Default 4 MiB.
    max_body_bytes: int = 0


# ── context accessors ─────────────────────────────────────────────────────

_current: contextvars.ContextVar[Optional[McpContext]] = contextvars.ContextVar(
    "toolnexus_mcp_context", default=None
)


def _ctx(ctx: Optional[McpContext]) -> Optional[McpContext]:
    return ctx if ctx is not None else _current.get()


def identity_from(ctx: Optional[McpContext] = None) -> Any:
    """The value ``authenticate`` produced for this request (None without one)."""
    c = _ctx(ctx)
    return c.identity if c else None


def request_from(ctx: Optional[McpContext] = None) -> Optional[McpRequest]:
    """The inbound HTTP request the handler is serving."""
    c = _ctx(ctx)
    return c.request if c else None


def mcp_session_id_from(ctx: Optional[McpContext] = None) -> str:
    """The request's Mcp-Session-Id ("" when none / stateless)."""
    c = _ctx(ctx)
    return c.session_id if c else ""


def mcp_request_meta_from(ctx: Optional[McpContext] = None) -> Optional[dict[str, Any]]:
    """The JSON-RPC request's ``params._meta`` (None when absent)."""
    c = _ctx(ctx)
    return c.meta if c else None


# ── helpers ───────────────────────────────────────────────────────────────


async def _call(fn: Hook, *args: Any) -> Any:
    out = fn(*args)
    if inspect.isawaitable(out):
        out = await out
    return out


def _dumps(v: Any) -> str:
    return json.dumps(v, separators=(",", ":"), ensure_ascii=False)


def _omit_empty(m: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in m.items() if not (isinstance(v, str) and v == "")}


def _rpc_error(code: int, message: str) -> dict[str, Any]:
    return {"code": code, "message": message}


class _RpcError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class _MemorySessionIds:
    def __init__(self) -> None:
        self._live: set[str] = set()

    def generate(self, ctx: McpContext) -> str:
        sid = secrets.token_hex(16)
        self._live.add(sid)
        return sid

    def validate(self, ctx: McpContext, id: str) -> bool:  # noqa: A002
        return id in self._live

    def terminate(self, ctx: McpContext, id: str) -> None:  # noqa: A002
        self._live.discard(id)


def mcp_wire_result(result: ToolResult) -> dict[str, Any]:
    """The JSON ``CallToolResult`` for ``result`` (§7C mapping): text block, §1B parts,
    ``isError`` only when true, ``structuredContent`` / ``_meta`` only when set."""
    wire = tool_result_to_call_result(result).model_dump(by_alias=True, exclude_none=True, mode="json")
    if not wire.get("isError"):
        wire.pop("isError", None)
    return wire


def mcp_wire_tool(t: Tool) -> dict[str, Any]:
    """The ``tools/list`` entry for ``t``: name verbatim, description, inputSchema,
    outputSchema when set, and annotations (``Tool.annotations`` verbatim; else derived
    from ``Tool.hints``; else omitted)."""
    m: dict[str, Any] = {
        "name": t.name,
        "description": t.description,
        "inputSchema": t.input_schema if t.input_schema is not None else {"type": "object"},
    }
    if t.output_schema is not None:
        m["outputSchema"] = t.output_schema
    ann: dict[str, Any] = {}
    a = t.annotations
    if a is not None:
        if a.title:
            ann["title"] = a.title
        for key, val in (
            ("readOnlyHint", a.read_only_hint),
            ("destructiveHint", a.destructive_hint),
            ("idempotentHint", a.idempotent_hint),
            ("openWorldHint", a.open_world_hint),
        ):
            if val is not None:
                ann[key] = val
    elif t.hints is not None:
        ann = {
            "readOnlyHint": t.hints.read_only,
            "destructiveHint": t.hints.destructive,
            "openWorldHint": t.hints.open_world,
        }
    if ann:
        m["annotations"] = ann
    return m


# ── the ASGI responder for one request ────────────────────────────────────


class _Responder:
    """Answers one HTTP request: plain JSON, unless a server→client request was sent
    first, in which case the response is upgraded to an SSE stream."""

    def __init__(self, send: Callable[[dict[str, Any]], Awaitable[None]]) -> None:
        self._send = send
        self.sse = False
        self.done = False

    async def start(self, status: int, headers: list[tuple[str, str]]) -> None:
        await self._send(
            {
                "type": "http.response.start",
                "status": status,
                "headers": [(k.lower().encode("latin-1"), v.encode("latin-1")) for k, v in headers],
            }
        )

    async def body(self, data: bytes, more: bool = False) -> None:
        await self._send({"type": "http.response.body", "body": data, "more_body": more})
        if not more:
            self.done = True

    async def plain(self, status: int, text: str, headers: Optional[list[tuple[str, str]]] = None) -> None:
        """Go's ``http.Error``: text/plain body with a trailing newline."""
        hs = list(headers or []) + [
            ("Content-Type", "text/plain; charset=utf-8"),
            ("X-Content-Type-Options", "nosniff"),
        ]
        await self.start(status, hs)
        await self.body((text + "\n").encode())

    async def empty(self, status: int) -> None:
        await self.start(status, [])
        await self.body(b"")

    async def json(self, status: int, msg: Any, headers: Optional[list[tuple[str, str]]] = None) -> None:
        await self.start(status, list(headers or []) + [("Content-Type", "application/json")])
        await self.body((_dumps(msg) + "\n").encode())

    async def event(self, msg: Any, final: bool = False) -> None:
        if not self.sse:
            await self.start(200, [("Content-Type", "text/event-stream"), ("Cache-Control", "no-cache")])
            self.sse = True
        await self.body(f"event: message\ndata: {_dumps(msg)}\n\n".encode(), more=not final)

    async def respond(
        self,
        id: Any,  # noqa: A002
        result: Any = None,
        err: Optional[dict[str, Any]] = None,
        headers: Optional[list[tuple[str, str]]] = None,
    ) -> None:
        msg: dict[str, Any] = {"jsonrpc": "2.0", "id": id}
        if err is not None:
            msg["error"] = err
        else:
            msg["result"] = result
        if self.sse:
            await self.event(msg, final=True)
            return
        await self.json(200, msg, headers)

    async def rpc_error(self, id: Any, code: int, message: str) -> None:  # noqa: A002
        await self.json(400, {"jsonrpc": "2.0", "id": id, "error": _rpc_error(code, message)})


_ABSENT = object()


# ── the handler ───────────────────────────────────────────────────────────


class McpHandler:
    """The embeddable MCP server returned by :meth:`Toolkit.mcp_handler` — an ASGI app."""

    def __init__(self, toolkit: Any, cfg: Optional[McpHandlerConfig] = None) -> None:
        cfg = cfg or McpHandlerConfig()
        self._tk = toolkit
        self.cfg = cfg
        self._ids: Any = cfg.session_ids if cfg.session_ids is not None else _MemorySessionIds()
        versions: list[str] = []
        for v in cfg.protocol_versions or []:
            if v in MCP_HANDLER_IMPLEMENTED_VERSIONS and v not in versions:
                versions.append(v)
        self.versions: list[str] = versions or list(_DEFAULT_VERSIONS)
        #: The initialize/session wire: every accepted version except 2026-07-28.
        self._legacy: list[str] = [v for v in self.versions if v != MCP_PROTOCOL_2026]
        self._confirm_timeout = cfg.confirm_timeout if cfg.confirm_timeout > 0 else 300.0
        self._max_body = cfg.max_body_bytes if cfg.max_body_bytes > 0 else 4 << 20
        self._caps: dict[str, bool] = {}
        self._streams: dict[asyncio.Queue[str], asyncio.AbstractEventLoop] = {}
        self._pending: dict[tuple[str, str], asyncio.Future[dict[str, Any]]] = {}
        self._seq = itertools.count(1)

    def notify_tools_changed(self) -> None:
        """Send ``notifications/tools/list_changed`` on every open GET stream. Safe to
        call from any thread; a full (slow) stream drops the message."""
        msg = _dumps({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
        for q, loop in list(self._streams.items()):

            def put(q: asyncio.Queue[str] = q) -> None:
                try:
                    q.put_nowait(msg)
                except asyncio.QueueFull:
                    pass

            try:
                running = asyncio.get_running_loop()
            except RuntimeError:
                running = None
            if running is loop:
                put()
            else:
                try:
                    loop.call_soon_threadsafe(put)
                except RuntimeError:
                    pass  # loop closed

    # ── ASGI entry ──

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope["type"] == "lifespan":
            while True:
                m = await receive()
                if m["type"] == "lifespan.startup":
                    await send({"type": "lifespan.startup.complete"})
                elif m["type"] == "lifespan.shutdown":
                    await send({"type": "lifespan.shutdown.complete"})
                    return
        if scope["type"] != "http":
            return
        rs = _Responder(send)
        req = _make_request(scope)
        ctx = McpContext(request=req)
        method = req.method
        pre: Optional[tuple[bytes, bool]] = None
        if method == "POST" and MCP_PROTOCOL_2026 in self.versions:
            ct = req.header("content-type").split(";", 1)[0].strip().lower()
            if ct == "application/json":  # else the legacy path answers 415 identically
                pre = await self._read_body(receive)
                if pre is None:
                    return  # client went away
                if not pre[1] and await self._post2026(ctx, rs, pre[0]):
                    return
        if self._only2026() and method != "POST":
            await rs.plain(405, "Method Not Allowed", [("Allow", "POST")])
            return
        if self.cfg.authenticate is not None:
            try:
                ctx.identity = await _call(self.cfg.authenticate, req)
            except Exception as e:  # noqa: BLE001 — any failure is a 401
                await self._auth_failure(rs, req, e)
                return
        if method == "POST":
            await self._post(ctx, rs, receive, pre)
        elif method == "GET":
            await self._get(ctx, rs, receive)
        elif method == "DELETE":
            await self._delete(ctx, rs)
        else:
            await rs.plain(405, "Method Not Allowed", [("Allow", "GET, POST, DELETE")])

    async def _auth_failure(self, rs: _Responder, req: McpRequest, err: BaseException) -> None:
        """Answer an ``authenticate`` failure (both wires)."""
        challenge = ""
        if self.cfg.www_authenticate is not None:
            challenge = await _call(self.cfg.www_authenticate, req, err) or ""
        if isinstance(err, McpAuthError):
            challenge = challenge or err.challenge
            hdrs = [("WWW-Authenticate", challenge)] if challenge else []
            await rs.start(err.status or 401, hdrs + [("Content-Type", "application/json")])
            await rs.body(b'{"error":"unauthorized"}')
            return
        await rs.plain(401, "Unauthorized", [("WWW-Authenticate", challenge)] if challenge else [])

    async def _read_body(self, receive: Any) -> Optional[tuple[bytes, bool]]:
        """The POST body and whether it exceeded the cap; None when the client left."""
        body = bytearray()
        while True:
            m = await receive()
            if m["type"] == "http.disconnect":
                return None
            body.extend(m.get("body", b""))
            if len(body) > self._max_body:
                return bytes(body), True
            if not m.get("more_body"):
                return bytes(body), False

    async def _check_session(self, ctx: McpContext, rs: _Responder) -> bool:
        assert ctx.request is not None
        v = ctx.request.header("mcp-protocol-version")
        if v and v not in self._legacy:
            await rs.plain(400, "Bad Request: unsupported MCP-Protocol-Version")
            return False
        if self.cfg.stateless:
            return True
        sid = ctx.request.header("mcp-session-id")
        if not sid:
            await rs.plain(400, "Bad Request: missing Mcp-Session-Id")
            return False
        try:
            ok = await _call(self._ids.validate, ctx, sid)
        except Exception:  # noqa: BLE001
            ok = False
        if not ok:
            await rs.plain(404, "Session not found")
            return False
        ctx.session_id = sid
        return True

    async def _post(
        self, ctx: McpContext, rs: _Responder, receive: Any, pre: Optional[tuple[bytes, bool]] = None
    ) -> None:
        assert ctx.request is not None
        ct = ctx.request.header("content-type").split(";", 1)[0].strip().lower()
        if ct != "application/json":
            await rs.plain(415, "Unsupported Media Type: must be application/json")
            return
        if pre is None:
            pre = await self._read_body(receive)
            if pre is None:
                return
        body, too_large = pre
        if too_large:
            await rs.plain(413, "Request Entity Too Large")
            return
        text = body.decode("utf-8", errors="replace").strip()
        if text.startswith("["):
            # JSON-RPC batching was removed in protocol 2025-06-18.
            await rs.rpc_error(None, -32600, "batch requests are not supported")
            return
        try:
            msg = json.loads(text)
        except ValueError:
            msg = None
        if not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0":
            await rs.rpc_error(None, -32700, "parse error")
            return
        method = msg.get("method")
        method = method if isinstance(method, str) else ""
        mid = msg.get("id", _ABSENT)

        if method == "initialize":
            await self._initialize(ctx, rs, msg)
            return
        if not await self._check_session(ctx, rs):
            return
        if method == "":
            # A response from the client (the answer to our elicitation).
            if mid is not _ABSENT:
                self._deliver(ctx.session_id, msg)
            await rs.empty(202)
            return
        if mid is _ABSENT or mid is None:
            await rs.empty(202)  # a notification
            return

        params = msg.get("params")
        if isinstance(params, dict) and isinstance(params.get("_meta"), dict):
            ctx.meta = params["_meta"]
        token = _current.set(ctx)
        try:
            try:
                result = await self._dispatch(ctx, rs, method, params)
                await rs.respond(mid, result)
            except _RpcError as e:
                await rs.respond(mid, err=_rpc_error(e.code, e.message))
        finally:
            _current.reset(token)

    async def _initialize(self, ctx: McpContext, rs: _Responder, msg: dict[str, Any]) -> None:
        params = msg.get("params") if isinstance(msg.get("params"), dict) else {}
        pv = params.get("protocolVersion")
        legacy = self._legacy or list(_DEFAULT_VERSIONS)
        version = pv if isinstance(pv, str) and pv in legacy else legacy[0]
        sid = ""
        if self.cfg.stateless:
            sid = self.cfg.stateless_session_id
        else:
            try:
                sid = str(await _call(self._ids.generate, ctx))
            except Exception:  # noqa: BLE001
                await rs.rpc_error(msg.get("id"), -32603, "cannot create session")
                return
            ctx.session_id = sid
            caps = params.get("capabilities")
            self._caps[sid] = isinstance(caps, dict) and caps.get("elicitation", None) is not None

        token = _current.set(ctx)
        try:
            if self.cfg.server_info is not None:
                info = await _call(self.cfg.server_info, ctx)
            else:
                info = McpServerInfo(self.cfg.name, self.cfg.title, self.cfg.version, self.cfg.instructions)
        finally:
            _current.reset(token)
        caps_out: dict[str, Any] = {"tools": {"listChanged": True}}
        if self.cfg.list_resources is not None:
            caps_out["resources"] = {}
        if self.cfg.list_prompts is not None:
            caps_out["prompts"] = {}
        res: dict[str, Any] = {
            "protocolVersion": version,
            "capabilities": caps_out,
            "serverInfo": _omit_empty(
                {
                    "name": info.name or DEFAULT_NAME,
                    "title": info.title,
                    "version": info.version or DEFAULT_VERSION,
                }
            ),
        }
        if info.instructions:
            res["instructions"] = info.instructions
        hdrs = [("Mcp-Session-Id", sid)] if sid else []
        await rs.respond(msg.get("id"), res, headers=hdrs)

    async def _get(self, ctx: McpContext, rs: _Responder, receive: Any) -> None:
        assert ctx.request is not None
        if "text/event-stream" not in ctx.request.header("accept"):
            await rs.plain(405, "Method Not Allowed")
            return
        if not await self._check_session(ctx, rs):
            return
        await rs.start(200, [("Content-Type", "text/event-stream"), ("Cache-Control", "no-cache")])
        await rs.body(b"", more=True)
        q: asyncio.Queue[str] = asyncio.Queue(maxsize=16)
        self._streams[q] = asyncio.get_running_loop()

        async def disconnected() -> None:
            while True:
                m = await receive()
                if m["type"] == "http.disconnect":
                    return

        gone = asyncio.ensure_future(disconnected())
        try:
            while True:
                nxt = asyncio.ensure_future(q.get())
                done, _ = await asyncio.wait({nxt, gone}, return_when=asyncio.FIRST_COMPLETED)
                if gone in done:
                    nxt.cancel()
                    return
                try:
                    await rs.body(f"event: message\ndata: {nxt.result()}\n\n".encode(), more=True)
                except Exception:  # noqa: BLE001 — the client went away
                    return
        finally:
            gone.cancel()
            self._streams.pop(q, None)
            if not rs.done:
                try:
                    await rs.body(b"")
                except Exception:  # noqa: BLE001
                    pass

    async def _delete(self, ctx: McpContext, rs: _Responder) -> None:
        if self.cfg.stateless:
            await rs.plain(405, "Method Not Allowed")
            return
        if not await self._check_session(ctx, rs):
            return
        try:
            await _call(self._ids.terminate, ctx, ctx.session_id)
        except Exception:  # noqa: BLE001
            await rs.plain(500, "cannot terminate session")
            return
        self._caps.pop(ctx.session_id, None)
        await rs.empty(200)

    # ── JSON-RPC dispatch ──

    async def _tools(self, ctx: McpContext) -> list[Tool]:
        if self.cfg.list_tools is not None:
            return list(await _call(self.cfg.list_tools, ctx) or [])
        return list(self._tk.tools())

    async def _dispatch(self, ctx: McpContext, rs: _Responder, method: str, params: Any) -> Any:
        cfg = self.cfg
        p = params if isinstance(params, dict) else {}
        if method == "ping":
            return {}
        if method == "tools/list":
            try:
                tools = await self._tools(ctx)
            except Exception as e:  # noqa: BLE001
                raise _RpcError(-32603, str(e)) from e
            return {"tools": [mcp_wire_tool(t) for t in tools]}
        if method == "tools/call":
            return await self._call_tool(ctx, rs, params)
        if method == "resources/list" and cfg.list_resources is not None:
            try:
                rs_ = await _call(cfg.list_resources, ctx) or []
            except Exception as e:  # noqa: BLE001
                raise _RpcError(-32603, str(e)) from e
            return {
                "resources": [
                    _omit_empty(
                        {"uri": r.uri, "name": r.name, "title": r.title, "description": r.description, "mimeType": r.mime_type}
                    )
                    for r in rs_
                ]
            }
        if method == "resources/read" and cfg.list_resources is not None and cfg.read_resource is not None:
            uri = p.get("uri") if isinstance(p.get("uri"), str) else ""
            try:
                cs = await _call(cfg.read_resource, ctx, uri) or []
            except Exception as e:  # noqa: BLE001
                raise _RpcError(-32002, str(e)) from e
            out = []
            for c in cs:
                m = _omit_empty({"uri": c.uri, "mimeType": c.mime_type, "blob": c.blob})
                if not c.blob:
                    m["text"] = c.text
                out.append(m)
            return {"contents": out}
        if method == "prompts/list" and cfg.list_prompts is not None:
            try:
                ps = await _call(cfg.list_prompts, ctx) or []
            except Exception as e:  # noqa: BLE001
                raise _RpcError(-32603, str(e)) from e
            out = []
            for pr in ps:
                m = _omit_empty({"name": pr.name, "title": pr.title, "description": pr.description})
                if pr.arguments:
                    args = []
                    for a in pr.arguments:
                        am = _omit_empty({"name": a.name, "description": a.description})
                        if a.required:
                            am["required"] = True
                        args.append(am)
                    m["arguments"] = args
                out.append(m)
            return {"prompts": out}
        if method == "prompts/get" and cfg.list_prompts is not None and cfg.get_prompt is not None:
            name = p.get("name", "")
            args = p.get("arguments") or {}
            if (
                not isinstance(params, dict)
                or not isinstance(name, str)
                or not isinstance(args, dict)
                or not all(isinstance(v, str) for v in args.values())
            ):
                raise _RpcError(-32602, "invalid params")
            try:
                pres = await _call(cfg.get_prompt, ctx, name, args)
            except Exception as e:  # noqa: BLE001
                raise _RpcError(-32602, str(e)) from e
            msgs = [{"role": m.role, "content": {"type": "text", "text": m.text}} for m in pres.messages]
            return _omit_empty({"description": pres.description, "messages": msgs})
        raise _RpcError(-32601, "method not found: " + method)

    async def _call_tool(self, ctx: McpContext, rs: _Responder, params: Any) -> Any:
        if not isinstance(params, dict) or not isinstance(params.get("name", ""), str):
            raise _RpcError(-32602, "invalid params")
        name = params.get("name", "")
        try:
            tools = await self._tools(ctx)
        except Exception as e:  # noqa: BLE001
            raise _RpcError(-32603, str(e)) from e
        tool = next((t for t in tools if t.name == name), None)
        if tool is None or tool.execute is None:
            raise _RpcError(-32602, "tool not found: " + name)
        raw = params.get("arguments")
        if raw is None:
            args: dict[str, Any] = {}
        elif isinstance(raw, dict):
            args = raw
        else:
            assert ctx.request is not None
            if ctx.request.header("mcp-protocol-version") == "2025-11-25":
                # 2025-11-25: input validation errors are tool execution errors.
                return mcp_wire_result(ToolResult(output="invalid arguments: must be an object", is_error=True))
            raise _RpcError(-32602, "arguments must be an object")

        started = time.monotonic()
        ev = McpCallEvent(
            name=tool.name,
            source=tool.source,
            decision=MCP_ALLOW,
            identity=ctx.identity,
            session_id=ctx.session_id,
        )

        async def finish(res: ToolResult, exec_err: Optional[BaseException] = None) -> Any:
            wire = mcp_wire_result(res)
            ev.ms = int((time.monotonic() - started) * 1000)
            ev.is_error = res.is_error
            ev.err = exec_err
            ev.result_bytes = len(_dumps(wire).encode())
            if self.cfg.on_call is not None:
                try:
                    await _call(self.cfg.on_call, ev)
                except Exception:  # noqa: BLE001 — host callback errors are isolated
                    pass
            return wire

        if self.cfg.before_call is not None:
            try:
                d = await _call(self.cfg.before_call, ctx, tool, args)
                if d is None:
                    d = McpDecision()
            except Exception as e:  # noqa: BLE001
                d = McpDecision(kind=MCP_DENY, message=str(e))
            kind = d.kind or MCP_ALLOW
            ev.decision = kind
            if kind == MCP_DENY:
                if d.result is not None:
                    return await finish(d.result)
                return await finish(ToolResult(output=d.message or "denied", is_error=True))
            if kind == MCP_CONFIRM:
                if not self._can_elicit(ctx.session_id):
                    if d.result is not None:
                        return await finish(d.result)
                    return await finish(ToolResult(output="confirmation required: " + d.message, is_error=True))
                try:
                    accepted = await self._elicit_confirm(rs, ctx.session_id, d.message)
                except Exception as e:  # noqa: BLE001
                    return await finish(ToolResult(output="confirmation failed: " + str(e), is_error=True))
                if not accepted:
                    return await finish(ToolResult(output="declined", is_error=True))

        exec_err: Optional[BaseException] = None
        try:
            res = await _call(tool.execute, args, ToolContext())
        except Exception as e:  # noqa: BLE001 — surfaced as an is_error result
            exec_err = e
            res = ToolResult(output=str(e), is_error=True)
        return await finish(res, exec_err)

    def _can_elicit(self, sid: str) -> bool:
        return bool(sid) and self._caps.get(sid, False)

    async def _elicit_confirm(self, rs: _Responder, sid: str, message: str) -> bool:
        """Send ``elicitation/create`` on this POST's SSE stream and wait for the
        client's answer (a separate POST, delivered via :meth:`_deliver`)."""
        rid = f"toolnexus-confirm-{next(self._seq)}"
        key = (sid, rid)
        fut: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        self._pending[key] = fut
        try:
            message = message or "Confirm this action?"
            await rs.event(
                {
                    "jsonrpc": "2.0",
                    "id": rid,
                    "method": "elicitation/create",
                    "params": {
                        "message": message,
                        "requestedSchema": {
                            "type": "object",
                            "properties": {
                                "confirm": {"type": "boolean", "title": "Confirm", "description": message}
                            },
                        },
                    },
                }
            )
            try:
                m = await asyncio.wait_for(fut, self._confirm_timeout)
            except asyncio.TimeoutError:
                raise RuntimeError("context deadline exceeded") from None
        finally:
            self._pending.pop(key, None)
        if m.get("error") is not None:
            raise RuntimeError("client returned an error")
        r = m.get("result")
        if not isinstance(r, dict):
            raise RuntimeError("invalid elicitation result")
        if r.get("action") != "accept":
            return False
        content = r.get("content")
        if isinstance(content, dict) and content.get("confirm") is False:
            return False
        return True

    def _deliver(self, sid: str, msg: dict[str, Any]) -> None:
        rid = msg.get("id")
        if not isinstance(rid, str):
            return
        fut = self._pending.get((sid, rid))
        if fut is not None and not fut.done():
            fut.set_result(msg)

    # ── MCP 2026-07-28 (SPEC §7C.2, ADR 0040) ──
    #
    # Opt-in: list "2026-07-28" in protocol_versions. The profile is stateless: no
    # initialize, no Mcp-Session-Id, no server→client request. A confirm decision and a
    # tool suspension (§10 pending) leave as an MRTR ``input_required`` result; the
    # client's retry carries ``inputResponses`` and the echoed ``requestState``.

    def _only2026(self) -> bool:
        return self.versions == [MCP_PROTOCOL_2026]

    def _route2026(self, req: McpRequest, method: str, meta: Optional[dict[str, Any]]) -> bool:
        if MCP_PROTOCOL_2026 not in self.versions:
            return False
        if not self._legacy:
            return True
        if method == "initialize":
            return False
        hv = req.header("mcp-protocol-version")
        if hv == MCP_PROTOCOL_2026:
            return True
        if hv and hv in self.versions:
            return False
        return method == "server/discover" or req.header("mcp-method") != "" or (meta is not None and _META_VERSION in meta)

    async def _post2026(self, ctx: McpContext, rs: _Responder, body: bytes) -> bool:
        """Serve one POST on the 2026 profile; False ⇒ it belongs to the legacy wire."""
        assert ctx.request is not None
        msg: Any = None
        try:
            text = body.decode("utf-8")
            msg = json.loads(text)
        except ValueError:
            text = body.decode("utf-8", errors="replace")
        trimmed = text.strip()
        if trimmed.startswith("[") or not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0":
            if not self._only2026():
                return False
            if trimmed.startswith("["):
                await _write26(rs, 400, None, err=_Err26(-32600, "batch requests are not supported"))
            else:
                await _write26(rs, 400, None, err=_Err26(-32700, "Parse error"))
            return True
        method = msg.get("method") if isinstance(msg.get("method"), str) else ""
        params = msg.get("params")
        p = params if isinstance(params, dict) else {}
        meta = p.get("_meta") if isinstance(p.get("_meta"), dict) else None
        if not self._route2026(ctx.request, method, meta):
            return False
        mid = msg.get("id", None)
        if method == "" or mid is None:
            await rs.empty(202)  # a notification (or a stray response): acknowledged
            return True
        e = _validate26(ctx.request, method, p, meta)
        if e is not None:
            await _write26(rs, e.status, mid, err=e)
            return True
        if self.cfg.authenticate is not None:
            try:
                ctx.identity = await _call(self.cfg.authenticate, ctx.request)
            except Exception as ex:  # noqa: BLE001
                await self._auth_failure(rs, ctx.request, ex)
                return True
        ctx.meta = meta
        token = _current.set(ctx)
        try:
            try:
                res = await self._dispatch2026(ctx, rs, method, params, p, meta)
            except _Err26 as er:
                await _write26(rs, er.status or 200, mid, err=er)
                return True
        finally:
            _current.reset(token)
        await _write26(rs, 200, mid, result=res)
        return True

    async def _info2026(self, ctx: McpContext) -> McpServerInfo:
        if self.cfg.server_info is not None:
            info = await _call(self.cfg.server_info, ctx)
        else:
            info = McpServerInfo(self.cfg.name, self.cfg.title, self.cfg.version, self.cfg.instructions)
        return McpServerInfo(info.name or DEFAULT_NAME, info.title, info.version or DEFAULT_VERSION, info.instructions)

    async def _complete26(self, ctx: McpContext, m: dict[str, Any], result_type: str = "complete") -> dict[str, Any]:
        info = await self._info2026(ctx)
        meta = m.get("_meta") if isinstance(m.get("_meta"), dict) else {}
        meta = dict(meta)
        meta[_META_SERVER_INFO] = _omit_empty({"name": info.name, "title": info.title, "version": info.version})
        m["_meta"] = meta
        m["resultType"] = result_type
        return m

    def _cache_scope26(self) -> str:
        return "private" if self.cfg.authenticate is not None else "public"

    async def _skills26(self, ctx: McpContext) -> list[McpSkill]:
        try:
            skills = list(await _call(self.cfg.list_skills, ctx) or [])  # type: ignore[arg-type]
        except Exception as e:  # noqa: BLE001
            raise _Err26(-32603, str(e)) from e
        return sorted(skills, key=lambda s: s.name)

    async def _legacy26(self, ctx: McpContext, rs: _Responder, method: str, params: Any) -> dict[str, Any]:
        """Same payload as the legacy wire; only the envelope differs."""
        try:
            res = await self._dispatch(ctx, rs, method, params)
        except _RpcError as e:
            raise _Err26(e.code, e.message) from e
        return await self._complete26(ctx, dict(res) if isinstance(res, dict) else {})

    async def _dispatch2026(
        self, ctx: McpContext, rs: _Responder, method: str, params: Any, p: dict[str, Any], meta: Optional[dict[str, Any]]
    ) -> dict[str, Any]:
        cfg = self.cfg
        if method == "server/discover":
            info = await self._info2026(ctx)
            caps: dict[str, Any] = {"tools": {}}
            if cfg.list_resources is not None or cfg.list_skills is not None:
                caps["resources"] = {}
            if cfg.list_prompts is not None:
                caps["prompts"] = {}
            if cfg.list_skills is not None:
                caps["extensions"] = {MCP_SKILLS_EXTENSION: {}}
            res: dict[str, Any] = {
                "supportedVersions": [MCP_PROTOCOL_2026],
                "capabilities": caps,
                "ttlMs": 3600000,
                "cacheScope": "public",
            }
            if info.instructions:
                res["instructions"] = info.instructions
            return await self._complete26(ctx, res)
        if method == "ping":
            return await self._complete26(ctx, {})
        if method == "tools/list":
            try:
                tools = await self._tools(ctx)
            except Exception as e:  # noqa: BLE001
                raise _Err26(-32603, str(e)) from e
            out = [mcp_wire_tool(t) for t in sorted(tools, key=lambda t: t.name)]
            return await self._complete26(ctx, {"tools": out, "ttlMs": 60000, "cacheScope": self._cache_scope26()})
        if method == "tools/call":
            return await self._call_tool2026(ctx, p, meta)
        if method == "skills/list" and cfg.list_skills is not None:
            skills = await self._skills26(ctx)
            lst = [{"uri": f"skill://{s.name}/SKILL.md", "frontmatter": _frontmatter26(s)} for s in skills]
            return await self._complete26(ctx, {"skills": lst, "ttlMs": 60000, "cacheScope": self._cache_scope26()})
        if method == "skills/get" and cfg.list_skills is not None:
            uri = p.get("uri") if isinstance(p.get("uri"), str) else ""
            try:
                _parse_skill_uri(uri)
            except ValueError as e:
                raise _Err26(-32602, f"Invalid skill URI: {e}") from None
            for s in await self._skills26(ctx):
                if f"skill://{s.name}/SKILL.md" == uri:
                    return await self._complete26(ctx, {"skill": _skill_entry26(s)})
            raise _Err26(-32602, "Unknown skill: " + uri)
        if method == "resources/list" and cfg.list_resources is not None:
            return await self._legacy26(ctx, rs, method, params)
        if method == "resources/read":
            uri = p.get("uri") if isinstance(p.get("uri"), str) else ""
            if uri.startswith("skill://") and cfg.list_skills is not None:
                try:
                    name, rel = _parse_skill_uri(uri)
                except ValueError as e:
                    raise _Err26(-32602, f"Invalid resource URI: {e}") from None
                for s in await self._skills26(ctx):
                    if s.name != name:
                        continue
                    for f in s.files:
                        if f.path == rel:
                            mt = "text/markdown" if rel.endswith(".md") else "text/plain"
                            return await self._complete26(
                                ctx,
                                {
                                    "contents": [{"uri": uri, "mimeType": mt, "text": f.text}],
                                    "ttlMs": 60000,
                                    "cacheScope": self._cache_scope26(),
                                },
                            )
                raise _Err26(-32602, "Resource not found: " + uri)
            if cfg.list_resources is not None and cfg.read_resource is not None:
                return await self._legacy26(ctx, rs, method, params)
        if method in ("prompts/list", "prompts/get") and cfg.list_prompts is not None:
            return await self._legacy26(ctx, rs, method, params)
        raise _Err26(-32601, "Method not found: " + method, status=404)

    async def _call_tool2026(self, ctx: McpContext, p: dict[str, Any], meta: Optional[dict[str, Any]]) -> dict[str, Any]:
        name = p.get("name") if isinstance(p.get("name"), str) else ""
        try:
            tools = await self._tools(ctx)
        except Exception as e:  # noqa: BLE001
            raise _Err26(-32603, str(e)) from e
        tool = next((t for t in tools if t.name == name), None)
        if tool is None or tool.execute is None:
            raise _Err26(-32602, "Unknown tool: " + name)
        started = time.monotonic()
        ev = McpCallEvent(name=tool.name, source=tool.source, decision=MCP_ALLOW, identity=ctx.identity)

        async def finish(m: dict[str, Any], is_err: bool, exec_err: Optional[BaseException] = None) -> dict[str, Any]:
            ev.ms = int((time.monotonic() - started) * 1000)
            ev.is_error, ev.err, ev.result_bytes = is_err, exec_err, len(_dumps(m).encode())
            if self.cfg.on_call is not None:
                try:
                    await _call(self.cfg.on_call, ev)
                except Exception:  # noqa: BLE001 — host callback errors are isolated
                    pass
            return m

        async def result(res: ToolResult, exec_err: Optional[BaseException] = None) -> dict[str, Any]:
            return await finish(await self._complete26(ctx, mcp_wire_result(res)), res.is_error, exec_err)

        args: dict[str, Any] = {}
        raw = p.get("arguments")
        if raw is not None:
            if not isinstance(raw, dict):
                return await result(ToolResult(output="invalid arguments: must be an object", is_error=True))
            args = raw

        state = _decode_state26(p.get("requestState"))
        if state is not None and state.get("tool") != name:
            state = None
        responses = p.get("inputResponses") if isinstance(p.get("inputResponses"), dict) else None
        confirmed = bool(state and state.get("confirmed") is True)

        if self.cfg.before_call is not None:
            try:
                d = await _call(self.cfg.before_call, ctx, tool, args)
                if d is None:
                    d = McpDecision()
            except Exception as e:  # noqa: BLE001
                d = McpDecision(kind=MCP_DENY, message=str(e))
            kind = d.kind or MCP_ALLOW
            ev.decision = kind
            if kind == MCP_DENY:
                if d.result is not None:
                    return await result(d.result)
                return await result(ToolResult(output=d.message or "denied", is_error=True))
            if kind == MCP_CONFIRM and not confirmed:
                ans = _input_response26("confirm", (responses or {}).get("confirm"))
                if state is not None and ans is not None:
                    if not ans.ok or (ans.data or {}).get("confirm") is False:
                        return await result(ToolResult(output="declined", is_error=True))
                    confirmed = True
                    responses = None  # the confirm round is consumed
                elif not _can_elicit26(meta):
                    if d.result is not None:
                        return await result(d.result)
                    return await result(ToolResult(output="confirmation required: " + d.message, is_error=True))
                else:
                    msg = d.message or "Confirm this action?"
                    ir = {
                        "confirm": {
                            "method": "elicitation/create",
                            "params": {
                                "mode": "form",
                                "message": msg,
                                "requestedSchema": {
                                    "type": "object",
                                    "properties": {
                                        "confirm": {"type": "boolean", "title": "Confirm", "description": msg}
                                    },
                                },
                            },
                        }
                    }
                    out = {"inputRequests": ir, "requestState": _encode_state26(name, False)}
                    return await finish(await self._complete26(ctx, out, "input_required"), False)

        tc = ToolContext()
        if state is not None and responses is not None and len(responses) == 1:
            k, v = next(iter(responses.items()))
            tc.answer = _input_response26(k, v)
        exec_err: Optional[BaseException] = None
        try:
            res = await _call(tool.execute, args, tc)
        except Exception as e:  # noqa: BLE001 — surfaced as an is_error result
            exec_err = e
            res = ToolResult(output=str(e), is_error=True)
        req = pending_of(res) if exec_err is None else None
        if req is not None:
            out = {"inputRequests": {req.id: _input_request26(req)}, "requestState": _encode_state26(name, confirmed)}
            return await finish(await self._complete26(ctx, out, "input_required"), False)
        return await result(res, exec_err)


# ── MCP 2026-07-28 helpers (SPEC §7C.2) ───────────────────────────────────


class _Err26(Exception):
    """A JSON-RPC error on the 2026 wire; ``status`` 0 ⇒ HTTP 200."""

    def __init__(self, code: int, message: str, status: int = 0, data: Any = None) -> None:
        super().__init__(message)
        self.code, self.message, self.status, self.data = code, message, status, data


async def _write26(
    rs: _Responder, status: int, id: Any, result: Any = None, err: Optional[_Err26] = None  # noqa: A002
) -> None:
    msg: dict[str, Any] = {"jsonrpc": "2.0", "id": id}
    if err is not None:
        er: dict[str, Any] = {"code": err.code, "message": err.message}
        if err.data is not None:
            er["data"] = err.data
        msg["error"] = er
    else:
        msg["result"] = result
    await rs.json(status, msg)


def _decode_header_value(v: str) -> tuple[str, bool]:
    """Decode the ``=?base64?…?=`` sentinel (SPEC §2.3). Header values arrive as
    latin-1; raw UTF-8 bytes are re-read as UTF-8."""
    if v.startswith("=?base64?") and v.endswith("?=") and len(v) >= 11:
        try:
            return base64.b64decode(v[9:-2], validate=True).decode("utf-8", errors="replace"), True
        except ValueError:
            return "", False
    try:
        v = v.encode("latin-1").decode("utf-8")
    except UnicodeError:
        pass
    return v, v != ""


def _validate26(req: McpRequest, method: str, p: dict[str, Any], meta: Optional[dict[str, Any]]) -> Optional[_Err26]:
    """SPEC §7C.2 header validation, in order (HTTP 400, before auth)."""

    def mismatch(m: str) -> _Err26:
        return _Err26(-32020, "Header mismatch: " + m, status=400)

    hv = req.header("mcp-protocol-version")
    if not hv:
        return mismatch("MCP-Protocol-Version header missing")
    hm = req.header("mcp-method")
    if hm != method:
        return mismatch(f"Mcp-Method header value '{hm}' does not match body value '{method}'")
    if method in ("tools/call", "prompts/get", "resources/read"):
        key = "uri" if method == "resources/read" else "name"
        want = p.get(key) if isinstance(p.get(key), str) else ""
        got, ok = _decode_header_value(req.header("mcp-name"))
        if not ok or got != want:
            return mismatch(f"Mcp-Name header value '{got}' does not match body value '{want}'")
    mv = (meta or {}).get(_META_VERSION)
    mv = mv if isinstance(mv, str) else ""
    if mv != hv:
        return mismatch(f"MCP-Protocol-Version header value '{hv}' does not match _meta value '{mv}'")
    if mv != MCP_PROTOCOL_2026:
        return _Err26(
            -32022, "Unsupported protocol version", status=400, data={"supported": [MCP_PROTOCOL_2026], "requested": mv}
        )
    return None


def _frontmatter26(s: McpSkill) -> dict[str, Any]:
    return s.frontmatter if s.frontmatter is not None else {"name": s.name}


def _skill_entry26(s: McpSkill) -> dict[str, Any]:
    res = []
    for f in sorted(s.files, key=lambda f: f.path):
        b = f.text.encode("utf-8")
        res.append(
            {"uri": f"skill://{s.name}/{f.path}", "digest": "sha256:" + hashlib.sha256(b).hexdigest(), "size": len(b)}
        )
    return {"uri": f"skill://{s.name}/SKILL.md", "frontmatter": _frontmatter26(s), "resources": res}


_BAD_PERCENT = re.compile(r"%(?![0-9A-Fa-f]{2})")


def _parse_skill_uri(uri: str) -> tuple[str, str]:
    """Validate ``skill://<name>/<path>`` (SPEC §2.7 containment); raises ValueError."""
    if not uri.startswith("skill://"):
        raise ValueError("not a skill:// URI")
    rest = uri[len("skill://") :]
    if _BAD_PERCENT.search(rest):
        raise ValueError("bad percent-encoding")
    dec = urllib.parse.unquote_to_bytes(rest).decode("utf-8", errors="surrogateescape")
    if any(c in dec for c in ("%", "\\", "\x00")):
        raise ValueError("skill URI has encoded, backslash or NUL characters")
    segs = dec.split("/")
    if len(segs) < 2:
        raise ValueError("skill URI needs skill://<name>/<path>")
    for s in segs:
        if s in ("", ".", "..") or ":" in s:
            raise ValueError("skill URI path escapes the skill (empty, '.', '..' or ':' segment)")
    return segs[0], "/".join(segs[1:])


def _go_json(v: Any) -> str:
    """Compact JSON spelled as Go's encoding/json does (HTML-safe escapes)."""
    out = _dumps(v)
    for a, b in (("<", "\\u003c"), (">", "\\u003e"), ("&", "\\u0026"), (" ", "\\u2028"), (" ", "\\u2029")):
        out = out.replace(a, b)
    return out


def _encode_state26(tool: str, confirmed: bool) -> str:
    """``requestState``: base64url-nopad of compact ``{"tool":…[,"confirmed":true]}``.
    Not integrity-protected — the client could equally answer "accept"."""
    st: dict[str, Any] = {"tool": tool}
    if confirmed:
        st["confirmed"] = True
    return base64.urlsafe_b64encode(_go_json(st).encode()).rstrip(b"=").decode("ascii")


def _decode_state26(v: Any) -> Optional[dict[str, Any]]:
    if not isinstance(v, str) or "=" in v:
        return None
    try:
        raw = base64.b64decode(v + "=" * (-len(v) % 4), altchars=b"-_", validate=True)
        st = json.loads(raw.decode("utf-8"))
    except ValueError:
        return None
    if st is None:
        st = {}
    if not isinstance(st, dict):
        return None
    tool, conf = st.get("tool", ""), st.get("confirmed", False)
    if not isinstance(tool, str) or not isinstance(conf, bool):
        return None
    return {"tool": tool, "confirmed": conf}


def _input_response26(key: str, v: Any) -> Optional[Answer]:
    """One MRTR input response as a §10 Answer."""
    if not isinstance(v, dict):
        return None
    action = v.get("action")
    ok = action == "accept"
    content = v.get("content")
    a = Answer(id=key, ok=ok, data=content if isinstance(content, dict) else None)
    if not ok:
        a.reason = "declined" if action == "decline" else "cancelled"
    return a


def _can_elicit26(meta: Optional[dict[str, Any]]) -> bool:
    caps = (meta or {}).get(_META_CLIENT_CAPS)
    return isinstance(caps, dict) and "elicitation" in caps


def _input_request26(r: Request) -> dict[str, Any]:
    """A §10 Request as an MRTR input request: ``authorization`` with a URL ⇒ URL mode,
    anything else ⇒ form mode."""
    if r.kind == "authorization" and r.url:
        return {
            "method": "elicitation/create",
            "params": {"mode": "url", "message": r.prompt, "url": r.url, "elicitationId": r.id},
        }
    schema = (r.data or {}).get("schema")
    if not isinstance(schema, dict):
        schema = {"type": "object", "properties": {}}
    return {"method": "elicitation/create", "params": {"mode": "form", "message": r.prompt, "requestedSchema": schema}}


def load_mcp_skills(dir: str) -> list[McpSkill]:  # noqa: A002
    """Read every skill under ``dir`` (any depth, ``**/SKILL.md``, symlinks not followed)
    for :attr:`McpHandlerConfig.list_skills`. Frontmatter is ``{name, description}``
    parsed as in §3; a skill without a ``name`` is skipped. Files are every regular,
    valid-UTF-8 file under the skill directory (binary files are skipped — the wire
    carries text), paths ``/``-separated and sorted. Skills are sorted by name."""
    from .skill import _parse_frontmatter

    def regular_files(root: str) -> list[str]:
        out: list[str] = []
        for d, dirs, files in os.walk(root, followlinks=False):
            dirs.sort()
            for f in sorted(files):
                fp = os.path.join(d, f)
                if os.path.isfile(fp) and not os.path.islink(fp):
                    out.append(fp)
        return out

    skills: list[McpSkill] = []
    for p in regular_files(dir):
        if os.path.basename(p) != "SKILL.md":
            continue
        with open(p, "rb") as fh:
            raw = fh.read().decode("utf-8", errors="replace")
        fm, _, malformed, _ = _parse_frontmatter(raw)
        if malformed or not fm.get("name"):
            continue
        root = os.path.dirname(p)
        sk = McpSkill(name=fm["name"], frontmatter={"name": fm["name"], "description": fm.get("description", "")})
        for fp in regular_files(root):
            with open(fp, "rb") as fh:
                b = fh.read()
            try:
                text = b.decode("utf-8")
            except UnicodeDecodeError:
                continue
            sk.files.append(McpSkillFile(path=os.path.relpath(fp, root).replace(os.sep, "/"), text=text))
        sk.files.sort(key=lambda f: f.path)
        skills.append(sk)
    skills.sort(key=lambda s: s.name)
    return skills


def _make_request(scope: dict[str, Any]) -> McpRequest:
    headers: dict[str, list[str]] = {}
    for k, v in scope.get("headers", []):
        headers.setdefault(k.decode("latin-1").lower(), []).append(v.decode("latin-1"))
    host = (headers.get("host") or [""])[0]
    if not host and scope.get("server"):
        h, port = scope["server"][0], scope["server"][1]
        host = f"{h}:{port}" if port else str(h)
    return McpRequest(
        method=scope.get("method", "GET").upper(),
        path=scope.get("path", ""),
        query_string=(scope.get("query_string") or b"").decode("latin-1"),
        headers=headers,
        host=host,
        scope=scope,
    )
