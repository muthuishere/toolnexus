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
import contextvars
import inspect
import itertools
import json
import secrets
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional, Protocol, Union

from .mcp_serve import DEFAULT_NAME, DEFAULT_VERSION, tool_result_to_call_result
from .types import Tool, ToolContext, ToolResult

#: MCP protocol versions whose wire the handler implements (ADR 0039 §versions).
#: ``2025-03-26`` (permits batching) and ``2026-07-28`` (#134) are never accepted.
MCP_HANDLER_IMPLEMENTED_VERSIONS: tuple[str, ...] = ("2025-11-25", "2025-06-18")
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
        if self.cfg.authenticate is not None:
            try:
                ctx.identity = await _call(self.cfg.authenticate, req)
            except Exception as e:  # noqa: BLE001 — any failure is a 401
                hdrs: list[tuple[str, str]] = []
                if self.cfg.www_authenticate is not None:
                    v = await _call(self.cfg.www_authenticate, req, e)
                    if v:
                        hdrs.append(("WWW-Authenticate", v))
                await rs.plain(401, "Unauthorized", hdrs)
                return
        method = req.method
        if method == "POST":
            await self._post(ctx, rs, receive)
        elif method == "GET":
            await self._get(ctx, rs, receive)
        elif method == "DELETE":
            await self._delete(ctx, rs)
        else:
            await rs.plain(405, "Method Not Allowed", [("Allow", "GET, POST, DELETE")])

    async def _check_session(self, ctx: McpContext, rs: _Responder) -> bool:
        assert ctx.request is not None
        v = ctx.request.header("mcp-protocol-version")
        if v and v not in self.versions:
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

    async def _post(self, ctx: McpContext, rs: _Responder, receive: Any) -> None:
        assert ctx.request is not None
        ct = ctx.request.header("content-type").split(";", 1)[0].strip().lower()
        if ct != "application/json":
            await rs.plain(415, "Unsupported Media Type: must be application/json")
            return
        body = bytearray()
        while True:
            m = await receive()
            if m["type"] == "http.disconnect":
                return
            body.extend(m.get("body", b""))
            if len(body) > self._max_body:
                await rs.plain(413, "Request Entity Too Large")
                return
            if not m.get("more_body"):
                break
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
        version = pv if isinstance(pv, str) and pv in self.versions else self.versions[0]
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
