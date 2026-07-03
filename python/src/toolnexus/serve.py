"""A2A (agent-to-agent) — INBOUND: serve a toolkit as a remote A2A agent.

Mirrors the JS reference (``js/src/serve.ts``). ``toolkit.serve(addr, client=...,
a2a=...)`` stands up a minimal HTTP server that, when the ``a2a`` profile is
present, exposes:

  GET  /.well-known/agent-card.json  → an Agent Card built from the toolkit's
       **skills** (SKILL.md name+description), never raw tools.
  POST /                             → JSON-RPC 2.0: ``SendMessage`` (submit a
       Task, fulfil it asynchronously via ``client.run``) + ``GetTask`` (poll).

This is the inbound counterpart to a2a.py (outbound) and speaks the same wire
subset (verified against a2a-python). Task persistence is a pluggable
:class:`TaskStore` (in-memory default; file / custom selectable via ``a2a.store``).
See ../../SPEC.md §7B and openspec/changes/add-a2a-agents.

The port's HTTP server is ``http.server.ThreadingHTTPServer`` (same as the
outbound test stub). Requests are handled on worker threads; the async
``TaskStore`` and ``client.run`` calls are bridged back onto the toolkit's event
loop via :func:`asyncio.run_coroutine_threadsafe`.
"""
from __future__ import annotations

import asyncio
import http.server
import inspect
import json
import os
import threading
import uuid
from abc import ABC, abstractmethod
from typing import Any, Awaitable, Callable, Optional, Union

from .a2a import A2ATask
from .mcp_serve import (
    MCPServeConfig,
    OnCall,
    build_mcp_server,
    exposed_mcp_tools,
    serve_mcp_http_request,
)
from .skill import SkillInfo
from .types import Tool, sanitize

# ---------------------------------------------------------------------------
# TaskStore adapter — pluggable persistence for served Tasks.
# ---------------------------------------------------------------------------


class TaskStore(ABC):
    """Pluggable Task persistence. The-factory can plug NATS/JetStream the same way."""

    @abstractmethod
    async def get(self, id: str) -> Optional[A2ATask]:  # noqa: A002 — mirrors JS `get(id)`
        ...

    @abstractmethod
    async def save(self, task: A2ATask) -> None:
        ...


class InMemoryTaskStore(TaskStore):
    """Default in-memory store — Tasks live only for the lifetime of the process."""

    def __init__(self) -> None:
        self._tasks: dict[str, A2ATask] = {}

    async def get(self, id: str) -> Optional[A2ATask]:  # noqa: A002
        return self._tasks.get(id)

    async def save(self, task: A2ATask) -> None:
        self._tasks[task["id"]] = task


class FileTaskStore(TaskStore):
    """File-backed store — one JSON file per Task id, under ``dir``."""

    def __init__(self, dir: str) -> None:  # noqa: A002 — mirrors JS `dir`
        self._dir = dir
        os.makedirs(dir, exist_ok=True)

    def _file(self, id: str) -> str:  # noqa: A002
        return os.path.join(self._dir, f"{sanitize(id)}.json")

    async def get(self, id: str) -> Optional[A2ATask]:  # noqa: A002
        try:
            with open(self._file(id), "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return None

    async def save(self, task: A2ATask) -> None:
        with open(self._file(task["id"]), "w", encoding="utf-8") as f:
            f.write(json.dumps(task))


def resolve_store(store: Union[TaskStore, str, None] = None) -> TaskStore:
    """Map a ``store`` selector to a concrete TaskStore. ``"file:<dir>"`` → FileTaskStore."""
    if store is None or store == "memory":
        return InMemoryTaskStore()
    if isinstance(store, str):
        if store.startswith("file:"):
            return FileTaskStore(store[len("file:") :])
        raise ValueError(f'Unknown A2A store: "{store}" (expected "memory" or "file:<dir>")')
    return store


# ---------------------------------------------------------------------------
# A2A serve config + handle.
# ---------------------------------------------------------------------------

# Opt-in A2A profile for `toolkit.serve` — configures the Agent Card + store.
# A plain dict, mirroring the JS `A2AConfig` interface (and the wire config block):
#   { "name"?, "description"?, "version"?, "provider"?: {organization,url},
#     "skills"?: [str], "store"?: TaskStore | "memory" | "file:<dir>" }
A2AConfig = dict[str, Any]

# onTask(ev) — fires on a Task's terminal state with the RunResult telemetry.
# ev = { "id", "task", "result", "state" }.
OnTask = Callable[[dict[str, Any]], Union[None, Awaitable[None]]]

# runTask(text, contextId?) → RunResult — runs one served Task through the client
# loop. ``contextId`` (from the A2A message) keys the conversation so served turns
# are remembered via the client's ConversationStore.
RunTask = Callable[..., Awaitable[Any]]


class ServeHandle:
    """Handle returned by ``toolkit.serve`` — the base URL plus a stop/close method."""

    def __init__(self, url: str, httpd: http.server.ThreadingHTTPServer) -> None:
        #: Base URL of the server, e.g. ``http://127.0.0.1:PORT``.
        self.url = url
        self._httpd = httpd

    async def stop(self) -> None:
        # shutdown() must run off the serve_forever thread; server_close frees the socket.
        await asyncio.to_thread(self._httpd.shutdown)
        self._httpd.server_close()

    async def close(self) -> None:
        """Alias for :meth:`stop`."""
        await self.stop()


# ---------------------------------------------------------------------------
# Card builder.
# ---------------------------------------------------------------------------

PROTOCOL_VERSION = "0.3.0"
DEFAULT_VERSION = "0.1.0"

CARD_PATH = "/.well-known/agent-card.json"


def _coalesce(value: Any, default: Any) -> Any:
    """JS ``??`` semantics — the default applies only for None (not "" / falsy)."""
    return value if value is not None else default


def build_agent_card(cfg: A2AConfig, skills: list[SkillInfo], url: str) -> dict[str, Any]:
    """Build an Agent Card from the toolkit's skills. ``skills`` are the SkillSource's
    :class:`SkillInfo`s (never raw tools); filtered to ``cfg["skills"]`` when given.
    ``url`` is the JSON-RPC endpoint peers POST to.
    """
    wanted = cfg.get("skills")
    selected = [s for s in skills if s.name in wanted] if wanted is not None else skills
    card: dict[str, Any] = {
        "name": _coalesce(cfg.get("name"), "toolnexus-agent"),
        "description": _coalesce(cfg.get("description"), ""),
        "version": _coalesce(cfg.get("version"), DEFAULT_VERSION),
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {"streaming": False, "pushNotifications": False},
        "defaultInputModes": ["text"],
        "defaultOutputModes": ["text"],
        "skills": [
            {"id": s.name, "name": s.name, "description": _coalesce(s.description, "")}
            for s in selected
        ],
        "url": url,
    }
    if cfg.get("provider"):
        card["provider"] = cfg["provider"]
    return card


# ---------------------------------------------------------------------------
# Server.
# ---------------------------------------------------------------------------


def _message_text(params: Any) -> str:
    """Extract the concatenated text of a JSON-RPC ``SendMessage`` message's parts."""
    msg = params.get("message") if isinstance(params, dict) else None
    parts = msg.get("parts") if isinstance(msg, dict) else None
    if not isinstance(parts, list):
        return ""
    return "".join(
        p["text"]
        for p in parts
        if isinstance(p, dict) and p.get("kind") == "text" and isinstance(p.get("text"), str)
    )


def _message_context_id(params: Any) -> Optional[str]:
    """Extract the A2A ``contextId`` off a JSON-RPC ``SendMessage`` message, if a str."""
    msg = params.get("message") if isinstance(params, dict) else None
    cid = msg.get("contextId") if isinstance(msg, dict) else None
    return cid if isinstance(cid, str) else None


class _ThreadingHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class _A2AHandler(http.server.BaseHTTPRequestHandler):
    # Silence the default stderr access log.
    def log_message(self, *args: Any) -> None:  # noqa: A002
        pass

    # --- low-level writers -------------------------------------------------
    def _send_json(self, obj: Any, status: int = 200) -> None:
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _rpc_res(self, id: Any, payload: dict[str, Any]) -> None:  # noqa: A002
        self._send_json({"jsonrpc": "2.0", "id": id if id is not None else None, **payload})

    def _rpc_ok(self, id: Any, result: Any) -> None:  # noqa: A002
        self._rpc_res(id, {"result": result})

    def _rpc_err(self, id: Any, code: int, message: str) -> None:  # noqa: A002
        self._rpc_res(id, {"error": {"code": code, "message": message}})

    def _not_found(self) -> None:
        self.send_response(404)
        self.end_headers()
        self.wfile.write(b"not found")

    # --- bridges onto the toolkit event loop -------------------------------
    def _await(self, coro: Awaitable[Any]) -> Any:
        return asyncio.run_coroutine_threadsafe(coro, self.server._loop).result()  # type: ignore[attr-defined]

    def _schedule(self, coro: Awaitable[Any]) -> None:
        asyncio.run_coroutine_threadsafe(coro, self.server._loop)  # type: ignore[attr-defined]

    # --- MCP streamable-HTTP -----------------------------------------------
    def _is_mcp_path(self) -> bool:
        return self.path == "/mcp" or self.path.startswith("/mcp?")

    def _handle_mcp(self) -> None:
        """Serve one streamable-HTTP MCP request at ``/mcp`` (independent of A2A).

        A fresh server+transport per request (stateless), mirroring the JS serve.ts
        ``/mcp`` path. Checked before the A2A handlers so ``/mcp`` is never shadowed.
        """
        srv = self.server
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length) if length else b""
        headers = [
            (k.lower().encode("latin-1"), v.encode("latin-1"))
            for k, v in self.headers.items()
        ]
        server = build_mcp_server(
            srv._mcp_tools,  # type: ignore[attr-defined]
            srv._mcp,  # type: ignore[attr-defined]
            srv._on_call,  # type: ignore[attr-defined]
        )
        try:
            status, resp_headers, resp_body = self._await(
                serve_mcp_http_request(server, self.command, self.path, headers, body)
            )
        except Exception:  # noqa: BLE001 — a transport error never crashes the server
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b"mcp error")
            return
        self.send_response(status)
        for hk, hv in resp_headers:
            self.send_header(hk.decode("latin-1"), hv.decode("latin-1"))
        self.end_headers()
        if resp_body:
            self.wfile.write(resp_body)

    # --- routes ------------------------------------------------------------
    def do_GET(self) -> None:  # noqa: N802
        srv = self.server
        if srv._mcp is not None and self._is_mcp_path():  # type: ignore[attr-defined]
            return self._handle_mcp()
        # No A2A profile ⇒ no routes.
        if srv._a2a is None or srv._store is None:  # type: ignore[attr-defined]
            return self._not_found()
        if self.path == CARD_PATH:
            url = srv._base_url + "/"  # type: ignore[attr-defined]
            self._send_json(build_agent_card(srv._a2a, srv._skills, url))  # type: ignore[attr-defined]
            return
        self._not_found()

    def do_POST(self) -> None:  # noqa: N802
        srv = self.server
        if srv._mcp is not None and self._is_mcp_path():  # type: ignore[attr-defined]
            return self._handle_mcp()
        if srv._a2a is None or srv._store is None:  # type: ignore[attr-defined]
            return self._not_found()
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""
        try:
            rpc = json.loads(body)
        except Exception:
            return self._rpc_err(None, -32700, "Parse error")
        method = rpc.get("method") if isinstance(rpc, dict) else None
        rid = rpc.get("id") if isinstance(rpc, dict) else None
        store = srv._store  # type: ignore[attr-defined]

        if method == "SendMessage":
            params = rpc.get("params") if isinstance(rpc, dict) else None
            text = _message_text(params)
            context_id = _message_context_id(params)
            tid = str(uuid.uuid4())
            submitted: A2ATask = {"id": tid, "status": {"state": "submitted"}}
            if context_id is not None:
                submitted["contextId"] = context_id
            # Persist submitted, kick fulfilment async, return the id immediately.
            self._await(store.save(submitted))
            self._schedule(
                _fulfil(tid, text, store, srv._run_task, srv._on_task, context_id)  # type: ignore[attr-defined]
            )
            return self._rpc_ok(rid, submitted)

        if method == "GetTask":
            params = rpc.get("params") if isinstance(rpc, dict) else None
            gid = str((params or {}).get("id") or "") if isinstance(params, dict) else ""
            task = self._await(store.get(gid))
            if task is None:
                return self._rpc_err(rid, -32001, f"Task not found: {gid}")
            return self._rpc_ok(rid, task)

        return self._rpc_err(rid, -32601, f"Method not found: {method}")


async def start_a2a_server(
    addr: str,
    a2a: Optional[A2AConfig],
    skills: list[SkillInfo],
    run_task: RunTask,
    on_task: Optional[OnTask] = None,
    loop: Optional[asyncio.AbstractEventLoop] = None,
    mcp: Optional[MCPServeConfig] = None,
    mcp_tools: Optional[list[Tool]] = None,
    on_call: Optional[OnCall] = None,
) -> ServeHandle:
    """Start the HTTP server. Delegated to by :meth:`Toolkit.serve`. When ``a2a`` is
    absent, the server answers 404 to everything (except ``/mcp`` when the ``mcp``
    profile is present). When ``mcp`` is present, a streamable-HTTP MCP server is
    co-mounted at ``POST /mcp``.
    """
    host, port = _split_addr(addr)
    store = resolve_store(a2a.get("store")) if a2a else None

    httpd = _ThreadingHTTPServer((host, int(port)), _A2AHandler)
    httpd._a2a = a2a  # type: ignore[attr-defined]
    httpd._store = store  # type: ignore[attr-defined]
    httpd._skills = skills  # type: ignore[attr-defined]
    httpd._run_task = run_task  # type: ignore[attr-defined]
    httpd._on_task = on_task  # type: ignore[attr-defined]
    # MCP inbound profile (independent of A2A): the exposed tool subset is computed
    # once; a fresh MCP server is built per request from it.
    httpd._mcp = mcp  # type: ignore[attr-defined]
    httpd._mcp_tools = exposed_mcp_tools(mcp_tools or [], mcp) if mcp else []  # type: ignore[attr-defined]
    httpd._on_call = on_call  # type: ignore[attr-defined]
    httpd._loop = loop or asyncio.get_running_loop()  # type: ignore[attr-defined]
    httpd._base_url = _base_url(host, httpd)  # type: ignore[attr-defined]

    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return ServeHandle(httpd._base_url, httpd)  # type: ignore[attr-defined]


async def _fulfil(
    id: str,  # noqa: A002
    text: str,
    store: TaskStore,
    run_task: RunTask,
    on_task: Optional[OnTask],
    context_id: Optional[str] = None,
) -> None:
    """Background fulfilment: submitted → working → (completed | failed). Never
    throws — a fulfilment error becomes a ``failed`` Task, keeping the server alive.
    """
    result: Any = None
    try:
        await store.save({"id": id, "status": {"state": "working"}})
        # contextId groups a peer's turns into one conversation — thread it so the
        # client's ConversationStore remembers across tasks in the same context.
        result = await run_task(text, context_id)
        artifact = {"artifactId": str(uuid.uuid4()), "parts": [{"kind": "text", "text": result.text}]}
        task: A2ATask = {"id": id, "status": {"state": "completed"}, "artifacts": [artifact]}
        state = "completed"
    except Exception as e:  # noqa: BLE001 — a fulfilment error becomes a failed Task
        detail = str(e)
        task = {
            "id": id,
            "status": {"state": "failed", "message": {"role": "agent", "parts": [{"kind": "text", "text": detail}]}},
        }
        state = "failed"
    try:
        await store.save(task)
    except Exception:  # noqa: BLE001 — a store write failure must not crash the server
        pass
    if on_task is not None:
        try:
            ev = on_task({"id": id, "task": task, "result": result, "state": state})
            if inspect.isawaitable(ev):
                await ev
        except Exception:  # noqa: BLE001 — host callback errors are isolated
            pass


# ---------------------------------------------------------------------------
# Address helpers.
# ---------------------------------------------------------------------------


def _split_addr(addr: str) -> tuple[str, str]:
    """Split ``host:port`` on the last colon. Missing host ⇒ 127.0.0.1."""
    idx = addr.rfind(":")
    if idx == -1:
        return "127.0.0.1", addr
    host = addr[:idx] or "127.0.0.1"
    return host, addr[idx + 1 :]


def _base_url(host: str, httpd: http.server.ThreadingHTTPServer) -> str:
    """Base URL for a listening server; 0.0.0.0 is reported as 127.0.0.1 for callers."""
    port = httpd.server_address[1]
    h = "127.0.0.1" if host in ("0.0.0.0", "::") else host
    return f"http://{h}:{port}"
