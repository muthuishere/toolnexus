"""MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C).

The inbound mirror of the A2A ``serve`` (§7B). Where A2A advertises the toolkit's
**skills** and fulfils a Task through the whole client loop, MCP advertises the
toolkit's **unified tools** (every source — mcp · skill · native · http · builtin
· a2a) and dispatches each ``tools/call`` straight to ``Tool.execute``. The calling
MCP client *is* the LLM host, so there is **no client, no Task, and no TaskStore** —
just ``tools/list`` + ``tools/call`` over the toolkit registry.

The server is exposed over **streamable-HTTP**, mounted at ``POST /mcp`` on the
``serve(addr, …)`` server, alongside the A2A routes (see serve.py). Built on the
same ``mcp`` SDK the client side already uses.

Mirrors the JS reference (``js/src/mcpserve.ts``).
"""
from __future__ import annotations

import asyncio
import hmac
import inspect
import json
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Awaitable, Callable, Optional, Union

import anyio
from mcp.server.lowlevel import Server
from mcp.server.streamable_http import StreamableHTTPServerTransport
from mcp.shared.exceptions import McpError
from mcp.types import (
    INVALID_PARAMS,
    AudioContent,
    BlobResourceContents,
    CallToolResult,
    EmbeddedResource,
    ErrorData,
    ImageContent,
    ResourceLink,
    TextContent,
)
from mcp.types import Tool as McpToolDef

from .types import Tool, ToolResult

DEFAULT_NAME = "toolnexus"
DEFAULT_VERSION = "0.1.0"

# Opt-in MCP serve profile — the toolkit is exposed as an MCP server. A plain dict,
# mirroring the JS `MCPServeConfig` interface (and the wire config block):
#   { "name"?, "version"?, "tools"?: [str] }
#   name default "toolnexus", version default "0.1.0"; tools omit ⇒ all, unknown
#   names in the filter are ignored (never an error).
MCPServeConfig = dict[str, Any]

# on_call(ev) — fires per inbound tools/call. ev = { "name", "source", "ms",
# "is_error" } (snake_case, matching the port's other event dicts).
OnCall = Callable[[dict[str, Any]], Union[None, Awaitable[None]]]


def _coalesce(value: Any, default: Any) -> Any:
    """JS ``??`` semantics — the default applies only for None (not "" / falsy)."""
    return value if value is not None else default


def exposed_mcp_tools(
    tools: list[Tool], cfg: Optional[MCPServeConfig] = None
) -> list[Tool]:
    """The tools this profile exposes: every toolkit tool, filtered to ``cfg["tools"]``
    when set (unknown names in the filter are ignored, never an error).
    """
    wanted = cfg.get("tools") if cfg else None
    if wanted is None:
        return tools
    wanted_set = set(wanted)
    return [t for t in tools if t.name in wanted_set]


def build_mcp_server(
    tools: list[Tool],
    cfg: Optional[MCPServeConfig] = None,
    on_call: Optional[OnCall] = None,
) -> Server:
    """Build a low-level MCP :class:`Server` exposing ``tools`` as MCP tools.

    ``tools/list`` maps each Tool's name (verbatim — already sanitized at
    registration), description, and input schema; ``tools/call`` dispatches to
    ``Tool.execute`` and maps the :class:`ToolResult` to a ``CallToolResult``
    (``output`` → text, ``is_error`` propagates). A fresh server is cheap to build,
    so the HTTP path makes one per request (stateless).
    """
    by_name = {t.name: t for t in tools}
    server: Server = Server(
        name=_coalesce(cfg.get("name") if cfg else None, DEFAULT_NAME),
        version=_coalesce(cfg.get("version") if cfg else None, DEFAULT_VERSION),
    )

    @server.list_tools()
    async def _list_tools() -> list[McpToolDef]:
        return [_mcp_tool_def(t) for t in tools]

    # validate_input=False: no input validation — execute() is called directly,
    # matching the JS reference (which does not validate against inputSchema).
    @server.call_tool(validate_input=False)
    async def _call_tool(name: str, arguments: dict[str, Any]) -> CallToolResult:
        tool = by_name.get(name)
        if tool is None:
            # Unknown tool name → the SDK's standard error (InvalidParams / -32602).
            raise McpError(ErrorData(code=INVALID_PARAMS, message=f"Unknown tool: {name}"))
        started = asyncio.get_running_loop().time()
        # A single tool invocation. An execute() throw becomes an is_error result —
        # never a crash.
        try:
            result = await tool.execute(arguments or {})
        except Exception as e:  # noqa: BLE001 — surfaced as an is_error result
            result = ToolResult(output=str(e), is_error=True)
        if on_call is not None:
            try:
                ev = on_call(
                    {
                        "name": tool.name,
                        "source": tool.source,
                        "ms": (asyncio.get_running_loop().time() - started) * 1000.0,
                        "is_error": result.is_error,
                    }
                )
                if inspect.isawaitable(ev):
                    await ev
            except Exception:  # noqa: BLE001 — host callback errors are isolated
                pass
        return tool_result_to_call_result(result)

    return server


def _mcp_tool_def(t: Tool) -> McpToolDef:
    """The wire tool for ``t`` on the §7C serve path: name verbatim, description,
    inputSchema, and ``outputSchema`` only when the tool declares one."""
    kwargs: dict[str, Any] = {}
    if t.output_schema is not None:
        kwargs["outputSchema"] = t.output_schema
    return McpToolDef(name=t.name, description=t.description, inputSchema=t.input_schema, **kwargs)


def structured_text(result: ToolResult) -> str:
    """The text block for a result: ``output``, or — when it is empty and ``structured``
    is set — the compact JSON of ``structured`` (the MCP spec asks a structured result
    to carry equivalent text). Keys are sorted to match the Go reference's encoding."""
    if result.structured is not None and result.output == "":
        return json.dumps(result.structured, separators=(",", ":"), sort_keys=True, ensure_ascii=False)
    return result.output


def tool_result_to_call_result(result: ToolResult) -> CallToolResult:
    """Map a :class:`ToolResult` to the SDK ``CallToolResult`` (§7C): text block first,
    §1B parts after it, ``isError`` propagates, ``structured`` ⇒ ``structuredContent``,
    ``meta`` ⇒ ``_meta``. Absent ⇒ byte-identical to the pre-§7C.1 result."""
    kwargs: dict[str, Any] = {}
    if result.structured is not None:
        kwargs["structuredContent"] = result.structured
    if result.meta is not None:
        kwargs["_meta"] = result.meta
    return CallToolResult(
        content=[TextContent(type="text", text=structured_text(result)), *_parts_to_content(result.parts)],
        isError=result.is_error,
        **kwargs,
    )


def _parts_to_content(parts: Optional[list[dict[str, Any]]]) -> list[Any]:
    """A served tool's §1B non-text parts as MCP content blocks, appended after the text
    part in order: ``image`` ⇒ image content, ``audio`` ⇒ audio content, ``file`` ⇒ an
    embedded resource. Base64 is passed through verbatim."""
    out: list[Any] = []
    for p in parts or []:
        kind = p.get("type")
        mime = p.get("mimeType") or "application/octet-stream"
        data = p.get("data")
        if kind == "text":
            out.append(TextContent(type="text", text=p.get("text") or ""))
        elif kind == "image" and data:
            out.append(ImageContent(type="image", data=data, mimeType=mime))
        elif kind == "audio" and data:
            out.append(AudioContent(type="audio", data=data, mimeType=mime))
        elif kind == "file":
            uri = p.get("url") or f"file:///{p.get('name') or 'part'}"
            if data:
                out.append(
                    EmbeddedResource(
                        type="resource",
                        resource=BlobResourceContents(uri=uri, mimeType=mime, blob=data),
                    )
                )
            else:
                # No bytes to embed — the link is the content, which is exactly what an
                # inbound resource_link maps back to (§2).
                out.append(
                    ResourceLink(
                        type="resource_link",
                        uri=uri,
                        name=p.get("name") or str(uri),
                        mimeType=mime,
                    )
                )
    return out


# ---------------------------------------------------------------------------
# streamable-HTTP — a single stateless request handled through the MCP transport.
# ---------------------------------------------------------------------------


async def _run_stateless(
    server: Server,
    transport: StreamableHTTPServerTransport,
    *,
    task_status: Any = anyio.TASK_STATUS_IGNORED,
) -> None:
    async with transport.connect() as (read_stream, write_stream):
        task_status.started()
        try:
            await server.run(
                read_stream,
                write_stream,
                server.create_initialization_options(),
                stateless=True,
            )
        except Exception:  # noqa: BLE001 — a crashed session never crashes the host
            pass


async def serve_mcp_http_request(
    server: Server,
    method: str,
    path: str,
    req_headers: list[tuple[bytes, bytes]],
    body: bytes,
) -> tuple[int, list[tuple[bytes, bytes]], bytes]:
    """Handle ONE streamable-HTTP MCP request through a fresh stateless transport,
    returning ``(status, headers, body)`` for the caller to write to the socket.

    Mirrors the JS serve.ts ``/mcp`` path: a fresh server+transport per request
    (stateless — no session id). Built on top of the MCP SDK's ASGI transport via
    a minimal in-memory ASGI bridge, so the sync HTTP server in serve.py can drive it.
    """
    captured: dict[str, Any] = {"status": 500, "headers": [], "body": bytearray()}

    async def receive() -> dict[str, Any]:
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message: dict[str, Any]) -> None:
        if message["type"] == "http.response.start":
            captured["status"] = message["status"]
            captured["headers"] = message.get("headers", [])
        elif message["type"] == "http.response.body":
            captured["body"].extend(message.get("body", b""))

    scope: dict[str, Any] = {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": method,
        "scheme": "http",
        "path": path,
        "raw_path": path.encode("ascii"),
        "query_string": b"",
        "root_path": "",
        "headers": req_headers,
        "server": ("127.0.0.1", 0),
        "client": ("127.0.0.1", 0),
    }

    transport = StreamableHTTPServerTransport(
        mcp_session_id=None, is_json_response_enabled=True
    )
    async with anyio.create_task_group() as tg:
        await tg.start(_run_stateless, server, transport)
        await transport.handle_request(scope, receive, send)
        await transport.terminate()
        tg.cancel_scope.cancel()

    return captured["status"], captured["headers"], bytes(captured["body"])


# ---------------------------------------------------------------------------
# Loopback + bearer — the toolkit as an MCP server for a local agent (SPEC §8C).
# ---------------------------------------------------------------------------


class _LoopThread:
    """A private event loop on a daemon thread: runs the MCP request handling."""

    def __init__(self) -> None:
        self.loop = asyncio.new_event_loop()
        self._t = threading.Thread(target=self.loop.run_forever, name="toolnexus-mcp-loopback", daemon=True)
        self._t.start()

    def run(self, coro: Any) -> Any:
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result()

    def stop(self) -> None:
        self.loop.call_soon_threadsafe(self.loop.stop)
        self._t.join(timeout=3)


class LoopbackMCPServer:
    """The handle :meth:`Toolkit.as_mcp_server` returns: ``url``
    (``http://127.0.0.1:<port>/mcp``), ``token`` (64 lowercase hex chars — never
    logged) and an idempotent :meth:`close`."""

    def __init__(self, tools: Callable[[], list[Tool]], cfg: Optional[MCPServeConfig] = None) -> None:
        self.token: str = secrets.token_hex(32)
        loop = _LoopThread()
        self._loop: Optional[_LoopThread] = loop
        want = ("Bearer " + self.token).encode("latin-1")
        cfg = dict(cfg or {})

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a: Any) -> None:  # never log (the token is in headers)
                pass

            def _empty(self, status: int, extra: Optional[dict[str, str]] = None) -> None:
                self.send_response(status)
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def _handle(self) -> None:
                if self.path != "/mcp" and not self.path.startswith("/mcp?"):
                    self._empty(404)
                    return
                got = (self.headers.get("Authorization") or "").encode("latin-1", errors="replace")
                if not hmac.compare_digest(got, want):
                    self._empty(401, {"WWW-Authenticate": "Bearer"})
                    return
                if self.command != "POST":
                    # Stateless: no server-sent stream to hold open, no session to DELETE.
                    self._empty(405, {"Allow": "POST"})
                    return
                length = int(self.headers.get("Content-Length", 0) or 0)
                data = self.rfile.read(length) if length else b""
                headers = [(k.lower().encode("latin-1"), v.encode("latin-1")) for k, v in self.headers.items()]
                try:
                    server = build_mcp_server(exposed_mcp_tools(tools(), cfg), cfg)
                    status, rh, rb = loop.run(serve_mcp_http_request(server, "POST", "/mcp", headers, data))
                except Exception:  # noqa: BLE001
                    self._empty(500)
                    return
                self.send_response(status)
                for hk, hv in rh:
                    if hk.lower() == b"content-length":
                        continue
                    self.send_header(hk.decode("latin-1"), hv.decode("latin-1"))
                self.send_header("Content-Length", str(len(rb)))
                self.end_headers()
                if rb:
                    self.wfile.write(rb)

            do_POST = _handle  # noqa: N815
            do_GET = _handle  # noqa: N815
            do_DELETE = _handle  # noqa: N815
            do_PUT = _handle  # noqa: N815
            do_PATCH = _handle  # noqa: N815

        httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        httpd.daemon_threads = True
        self._httpd: Optional[ThreadingHTTPServer] = httpd
        self.url: str = f"http://127.0.0.1:{httpd.server_address[1]}/mcp"
        self._lock = threading.Lock()
        threading.Thread(target=httpd.serve_forever, name="toolnexus-mcp-loopback-http", daemon=True).start()

    def close(self) -> None:
        """Stop the listener. Idempotent."""
        with self._lock:
            httpd, loop = self._httpd, self._loop
            self._httpd, self._loop = None, None
        if httpd is not None:
            httpd.shutdown()
            httpd.server_close()
        if loop is not None:
            loop.stop()

    def __repr__(self) -> str:  # never show the token
        return f"LoopbackMCPServer(url={self.url!r})"
