"""Dynamic MCP source. Mirrors the JS reference (``js/src/mcp.ts``), built on the
official MCP Python SDK (the ``mcp`` package): connect to every configured
server, list tools (paginated), and convert each into a uniform ``Tool``.

The SDK is async and its transports are async context managers, so this module
uses an :class:`contextlib.AsyncExitStack` to keep every client/session alive for
the lifetime of the source (released by :meth:`McpSource.close`).
"""
from __future__ import annotations

import asyncio
import inspect
import itertools
import json
import os
import re
import sys
import time
from contextlib import AsyncExitStack
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any, Callable, Optional

import httpx
from mcp import ClientSession
from mcp.client.sse import sse_client
from mcp.client.stdio import StdioServerParameters, stdio_client
from mcp.client.streamable_http import streamablehttp_client
from mcp.shared.exceptions import McpError
from mcp.types import CallToolResult, ElicitResult, ErrorData
from mcp.types import Tool as McpToolDef

from .content import describe_part
from .mcp2026.auth import AuthPending, OAuth, OpenUrl, TokenStore
from .mcp2026.session import STDIO_PROBE_MAX, Session2026, merged_env, probe_http, probe_stdio
from .mcp2026.stdio import StdioConn, sdk_streams
from .mcp2026.wire import bm25_rank
from .skill import SkillDef, _parse_frontmatter
from .types import (
    Answer,
    JSONSchema,
    McpStatus,
    Request,
    Tool,
    ToolContext,
    ToolHints,
    ToolResult,
    sanitize,
)

DEFAULT_TIMEOUT = 30.0  # seconds (JS uses 30_000 ms)
MAX_LIST_PAGES = 1_000


# ---------------------------------------------------------------------------
# MCP elicitation bridge (§10). A server can ask US for input mid-``tools/call``
# (a reverse-request). We map it onto the one ``wait_for``: form mode → kind:"input",
# URL mode → kind:"authorization". The two mappings are pure + byte-parity-tested.
# ---------------------------------------------------------------------------

_elc_seq = itertools.count(1)


def elicitation_to_request(params: Any) -> Request:
    """Map an MCP ``elicitation/create`` request onto a §10 :class:`Request`.

    Mirrors the JS ``elicitationToRequest`` byte-for-byte: URL mode →
    ``kind="authorization"`` carrying the ``url``; otherwise ``kind="input"``
    carrying the ``requestedSchema`` in ``data["schema"]``.
    """
    is_url = getattr(params, "mode", None) == "url"
    message = getattr(params, "message", None)
    req = Request(
        id=f"elc-{int(time.time() * 1000):x}-{next(_elc_seq)}",
        kind="authorization" if is_url else "input",
        prompt=str(message) if message is not None else "",
    )
    url = getattr(params, "url", None)
    if is_url and url:
        req.url = url
    schema = getattr(params, "requestedSchema", None)
    if not is_url and schema:
        req.data = {"schema": schema}
    return req


def answer_to_elicit_result(answer: Answer) -> ElicitResult:
    """Map a resolved §10 :class:`Answer` back onto an MCP ``ElicitResult``.

    Mirrors the JS ``answerToElicitResult``: ``ok`` → ``accept`` (with ``data`` or
    ``{}``); ``reason == "declined"`` → ``decline``; everything else → ``cancel``.
    """
    if answer.ok:
        return ElicitResult(action="accept", content=answer.data or {})
    return ElicitResult(action="decline" if answer.reason == "declined" else "cancel")

_ENV_RE = re.compile(r"\$\{([A-Za-z0-9_]+)\}")


def expand_env_headers(
    headers: Optional[dict[str, str]],
) -> Optional[dict[str, str]]:
    """Expand ``${ENV_VAR}`` in header *values* from ``os.environ`` so tokens live
    in the environment, not the committed config. Returns ``None`` if input is
    ``None``. Values are never logged. Mirrors the JS ``expandEnvHeaders``.
    """
    if headers is None:
        return None
    return {
        k: _ENV_RE.sub(lambda m: os.environ.get(m.group(1), ""), v)
        for k, v in headers.items()
    }

# Config is a dict keyed by server name. Each value is a server config dict.
ServerConfig = dict[str, Any]
McpConfig = dict[str, ServerConfig]


def _to_seconds(value: Any) -> float:
    """Config timeouts follow the SPEC/JS convention (ms). Convert to seconds."""
    try:
        return float(value) / 1000.0
    except (TypeError, ValueError):
        return DEFAULT_TIMEOUT


def normalize_command(name: str, cfg: ServerConfig) -> list[str]:
    """Normalize a local server's ``command`` (+ optional ``args``) into an argv list.

    ``command`` may be a string (the executable) or a non-empty list of strings;
    ``args`` (a list of strings, default ``[]``) is appended. No shell is involved.
    Raises :class:`ValueError` naming the server and field for any invalid shape
    (SPEC.md §2; design D1 of ``add-host-engine-needs``).
    """
    command = cfg.get("command")
    if isinstance(command, str):
        if not command:
            raise ValueError(f'mcp server "{name}": invalid command (empty string)')
        argv = [command]
    elif isinstance(command, list):
        if not command:
            raise ValueError(f'mcp server "{name}": invalid command (empty array)')
        if not all(isinstance(c, str) for c in command):
            raise ValueError(
                f'mcp server "{name}": invalid command (every element must be a string)'
            )
        argv = list(command)
    else:
        raise ValueError(
            f'mcp server "{name}": invalid command (expected a string or array of strings)'
        )
    args = cfg.get("args", [])
    if args is None:
        args = []
    if not isinstance(args, list) or not all(isinstance(a, str) for a in args):
        raise ValueError(f'mcp server "{name}": invalid args (expected an array of strings)')
    return argv + list(args)


def is_remote(cfg: ServerConfig) -> bool:
    t = cfg.get("type")
    if t == "remote":
        return True
    if t == "local":
        return False
    return isinstance(cfg.get("url"), str)


def is_enabled(cfg: ServerConfig) -> bool:
    if cfg.get("disabled") is True:
        return False
    if cfg.get("enabled") is False:
        return False
    return True


def parse_mcp_config(input: str | dict[str, Any]) -> McpConfig:
    """Accept a path, a raw dict, or a dict wrapped under mcpServers/servers/mcp."""
    if isinstance(input, str):
        with open(input, "r", encoding="utf-8") as f:
            raw: dict[str, Any] = json.load(f)
    else:
        raw = dict(input)
    if "mcpServers" in raw:
        return _validate_commands(raw["mcpServers"])
    if "servers" in raw:
        return _validate_commands(raw["servers"])
    if "mcp" in raw:
        return _validate_commands(raw["mcp"])
    # Bare map of servers — but strip sibling top-level config keys (builtins/agents/
    # a2a/mcpServer) so they are not mistaken for MCP servers when no wrapper key is
    # present. `mcpServer` (singular) is the inbound MCP-serve profile (§7C), distinct
    # from the `mcpServers` (plural) client wrapper stripped above.
    return _validate_commands({
        k: v
        for k, v in raw.items()
        if k not in ("builtins", "agents", "a2a", "mcpServer")
    })


def _validate_commands(servers: Any) -> McpConfig:
    """Fail the parse, naming the server, on a malformed local ``command``/``args`` (SPEC.md §2)."""
    if isinstance(servers, dict):
        for name, cfg in servers.items():
            if isinstance(cfg, dict) and "command" in cfg and not is_remote(cfg):
                normalize_command(name, cfg)
    return servers  # type: ignore[return-value]


def _is_text_content(item: Any) -> bool:
    return getattr(item, "type", None) == "text" and isinstance(
        getattr(item, "text", None), str
    )


def _format_tool_error_content(content: Any) -> str:
    if not isinstance(content, list):
        return "MCP tool returned an error"
    texts = [item.text for item in content if _is_text_content(item) and item.text.strip()]
    return "\n\n".join(texts) or "MCP tool returned an error"


def _join_text_content(content: Any) -> str:
    if not isinstance(content, list):
        return ""
    texts = [item.text for item in content if _is_text_content(item)]
    # An embedded resource carrying TEXT is appended to `output` (§0.4) — it is text the
    # server meant the model to read, not a binary attachment.
    texts.extend(_embedded_texts(content))
    return "\n".join(texts)


def _embedded_texts(content: list[Any]) -> list[str]:
    out: list[str] = []
    for item in content:
        if getattr(item, "type", None) != "resource":
            continue
        res = getattr(item, "resource", None)
        text = getattr(res, "text", None)
        if isinstance(text, str):
            out.append(text)
    return out


def _collect_parts(content: Any) -> Optional[list[dict[str, Any]]]:
    """Map an MCP ``content[]`` to §1B parts — image⇒image, audio⇒audio,
    ``resource_link``⇒``file{url}``, ``resource`` with a blob⇒``file{data}``. A
    ``resource`` carrying text went to ``output`` instead, and text entries are already
    ``output``. Nothing is ever dropped silently. ``data``/``blob`` are base64 STRINGS
    from the SDK and are passed through verbatim, so no encoder can drift the bytes.

    Called on EVERY result branch — the ``structuredContent`` short-circuit and the
    error path included — because a short-circuit that skips it reintroduces the very
    silent drop this mapping exists to remove.
    """
    if not isinstance(content, list):
        return None
    parts: list[dict[str, Any]] = []
    for item in content:
        kind = getattr(item, "type", None)
        if kind == "image" or kind == "audio":
            data = getattr(item, "data", None)
            mime = getattr(item, "mimeType", None)
            if isinstance(data, str) and isinstance(mime, str):
                parts.append({"type": kind, "mimeType": mime, "data": data})
        elif kind == "resource_link":
            uri = getattr(item, "uri", None)
            if uri is not None:
                part: dict[str, Any] = {
                    "type": "file",
                    "mimeType": getattr(item, "mimeType", None) or "application/octet-stream",
                    "url": str(uri),
                }
                name = getattr(item, "name", None)
                if isinstance(name, str) and name:
                    part["name"] = name
                parts.append(part)
        elif kind == "resource":
            res = getattr(item, "resource", None)
            blob = getattr(res, "blob", None)
            if isinstance(blob, str):
                part = {
                    "type": "file",
                    "mimeType": getattr(res, "mimeType", None) or "application/octet-stream",
                    "data": blob,
                }
                uri = getattr(res, "uri", None)
                if uri is not None:
                    part["name"] = str(uri)
                parts.append(part)
    return parts or None


def _describe_parts(parts: list[dict[str, Any]]) -> str:
    """§8A: what `output` says when a server returned only non-text content — one line
    per part in the canonical ``<type> (<mimeType>, <bytes> bytes)`` form, rather than
    hand the model an empty string."""
    return "\n".join(describe_part(p) for p in parts)


async def _paginate_tools(session: ClientSession) -> list[McpToolDef]:
    result: list[McpToolDef] = []
    cursors: set[str] = set()
    cursor: Optional[str] = None
    for _ in range(MAX_LIST_PAGES):
        res = await session.list_tools(cursor=cursor)
        result.extend(res.tools)
        next_cursor = res.nextCursor
        if next_cursor is None:
            return result
        if next_cursor in cursors:
            raise RuntimeError(f"MCP list returned duplicate cursor: {next_cursor}")
        cursors.add(next_cursor)
        cursor = next_cursor
    raise RuntimeError(f"MCP list exceeded {MAX_LIST_PAGES} pages")


def _build_input_schema(defn: McpToolDef) -> JSONSchema:
    """Convert a listed MCP tool def's schema: spread the server's schema, then force
    type:"object", default properties to {}, and additionalProperties:false. Shared by
    tool conversion (§2) and the list-only inventory (Gap 6)."""
    src_schema = defn.inputSchema or {}
    return {
        **src_schema,
        "type": "object",
        "properties": src_schema.get("properties", {}) or {},
        "additionalProperties": False,
    }


def apply_tools_filter(
    server: str, defs: list[McpToolDef], filter: Optional[dict[str, bool]]
) -> list[McpToolDef]:
    """Per-server tool allowlist (§2 Gap 7), keyed on the ORIGINAL tool name. Same
    semantics as the skills/builtins filter: nil/empty ⇒ all; ≥1 True ⇒ allowlist;
    only-False ⇒ drop-list over all-on; unknown names ignored + warned once. Applied
    to the listed defs before sanitize/prefix. Mirrors Go ``applyToolsFilter``."""
    if not filter:
        return defs
    has_true = any(v is True for v in filter.values())
    present = {d.name for d in defs}
    for k in filter:
        if k not in present:
            print(
                f'[toolnexus] server "{server}" tools filter name "{k}" matched no tool',
                file=sys.stderr,
            )
    out: list[McpToolDef] = []
    for d in defs:
        keep = (filter.get(d.name) is True) if has_true else (filter.get(d.name) is not False)
        if keep:
            out.append(d)
    return out


# The SDK's own per-request deadline is disabled for tools/call (it cannot be
# extended); our watchdog below enforces the idle budget instead.
_NO_SDK_DEADLINE = timedelta(days=3650)


async def _call_with_idle_timeout(
    session: ClientSession, name: str, arguments: dict[str, Any], timeout: float
) -> Any:
    """``tools/call`` bounded by an IDLE timeout (SPEC.md §2, add-mcp-progress-timeout).

    The SDK attaches a progress token (the request id) when a ``progress_callback``
    is given and routes only matching ``notifications/progress`` to it, so a
    foreign token never resets the budget. Each matching notification pushes the
    deadline out to ``now + timeout``; silence for ``timeout`` cancels the call.
    Caller cancellation propagates unchanged (the task is awaited, not shielded).
    """
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout

    async def _on_progress(progress: float, total: Optional[float], message: Optional[str]) -> None:
        nonlocal deadline
        deadline = loop.time() + timeout

    task = asyncio.ensure_future(
        session.call_tool(
            name,
            arguments=arguments,
            read_timeout_seconds=_NO_SDK_DEADLINE,
            progress_callback=_on_progress,
        )
    )
    try:
        while True:
            remaining = deadline - loop.time()
            if remaining <= 0:
                task.cancel()
                try:
                    await task
                except BaseException:  # noqa: BLE001 - we are discarding it
                    pass
                # Same error, same text as the SDK's own deadline used to raise, so a
                # silent server fails exactly as before this change.
                raise McpError(
                    ErrorData(
                        code=408,
                        message=(
                            "Timed out while waiting for response to ClientRequest. "
                            f"Waited {timeout} seconds."
                        ),
                    )
                )
            done, _ = await asyncio.wait({task}, timeout=remaining)
            if done:
                return task.result()
    except asyncio.CancelledError:
        task.cancel()
        raise


def mcp_hints(annotations: Any) -> Optional[ToolHints]:
    """Map MCP tool annotations to :class:`ToolHints` (§4C). No hint fields at all
    ⇒ ``None`` (unknown). MCP's defaults apply to absent fields: readOnlyHint
    False, destructiveHint True, openWorldHint True. Read-only is never destructive."""
    if annotations is None:
        return None
    ro = getattr(annotations, "readOnlyHint", None)
    de = getattr(annotations, "destructiveHint", None)
    ow = getattr(annotations, "openWorldHint", None)
    if ro is None and de is None and ow is None:
        return None
    h = ToolHints(
        read_only=bool(ro) if ro is not None else False,
        destructive=bool(de) if de is not None else True,
        open_world=bool(ow) if ow is not None else True,
    )
    if h.read_only:
        h.destructive = False
    return h


def _map_call_result(server: str, result: Any) -> ToolResult:
    """Map an MCP ``CallToolResult`` onto a :class:`ToolResult` (§2.F *Behaviour*) — shared
    by the frozen SDK path and the 2026 path so both map byte-identically."""
    # Parts are collected BEFORE any branch returns — the error path and the
    # structuredContent short-circuit both keep their non-text content (§0.4).
    parts = _collect_parts(result.content)
    if result.isError:
        return ToolResult(
            output=_format_tool_error_content(result.content),
            is_error=True,
            metadata={"server": server},
            parts=parts,
        )
    if result.structuredContent is not None:
        return ToolResult(
            # §2: JSON.stringify — compact separators, non-ASCII kept (byte-identical across ports).
            output=json.dumps(result.structuredContent, separators=(",", ":"), ensure_ascii=False),
            is_error=False,
            metadata={"server": server},
            parts=parts,
        )
    output = _join_text_content(result.content)
    if not output and parts:
        output = _describe_parts(parts)
    return ToolResult(
        output=output,
        is_error=False,
        metadata={"server": server},
        parts=parts,
    )


def _convert_tool_2026(server: str, defn: McpToolDef, session: Session2026) -> Tool:
    """A 2026-path server tool: same name/schema/hints/result mapping as :func:`_convert_tool`;
    ``tools/call`` runs MRTR rounds through §10 (inline ``wait_for`` or a resumable pending)."""

    def map_result(res: dict[str, Any]) -> ToolResult:
        return _map_call_result(server, CallToolResult.model_validate(res))

    async def execute(
        args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None
    ) -> ToolResult:
        return await session.call_tool(defn.name, args or {}, ctx, map_result, server)

    return Tool(
        name=sanitize(server) + "_" + sanitize(defn.name),
        description=defn.description or "",
        input_schema=_build_input_schema(defn),
        source="mcp",
        execute=execute,
        hints=mcp_hints(getattr(defn, "annotations", None)),
    )


def _convert_tool(
    server: str, defn: McpToolDef, session: ClientSession, timeout: float
) -> Tool:
    input_schema: JSONSchema = _build_input_schema(defn)

    async def execute(
        args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None
    ) -> ToolResult:
        eff_timeout = ctx.timeout if (ctx and ctx.timeout is not None) else timeout
        try:
            result = await _call_with_idle_timeout(session, defn.name, args or {}, eff_timeout)
            return _map_call_result(server, result)
        except Exception as e:  # noqa: BLE001 - failures isolated per call
            return ToolResult(output=str(e), is_error=True, metadata={"server": server})

    return Tool(
        name=sanitize(server) + "_" + sanitize(defn.name),
        description=defn.description or "",
        input_schema=input_schema,
        source="mcp",
        execute=execute,
        hints=mcp_hints(getattr(defn, "annotations", None)),
    )


SEARCH_TOOLS_SCHEMA: JSONSchema = {
    "type": "object",
    "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}},
    "required": ["query"],
}
CALL_TOOL_SCHEMA: JSONSchema = {
    "type": "object",
    "properties": {"name": {"type": "string"}, "arguments": {"type": "object"}},
    "required": ["name"],
}


class _ToolSearch:
    """Lazy exposure (§2.8): ``search_tools`` + ``call_tool`` instead of every server tool.
    ``tools/list`` is cached for its ``ttlMs`` (no hint ⇒ fetched once per load)."""

    def __init__(self, server: str, cfg: ServerConfig, session: Any, defs: list[McpToolDef], timeout: float):
        self.server = server
        self.cfg = cfg
        self.session = session
        self.timeout = timeout
        desc = cfg.get("description")
        self.description = desc if isinstance(desc, str) else ""
        self._lock = asyncio.Lock()
        self._set(defs)

    def _set(self, defs: list[McpToolDef]) -> None:
        self.defs = defs
        self.by_name = {d.name: _server_tool(self.server, d, self.session, self.timeout) for d in defs}
        ttl = getattr(self.session, "tools_ttl_ms", None)
        self.expiry = time.monotonic() + ttl / 1000.0 if ttl else None

    async def _current(self) -> list[McpToolDef]:
        async with self._lock:
            if self.expiry is not None and time.monotonic() >= self.expiry:
                defs = await _paginate_tools(self.session)
                dis = set(self.cfg.get("_disable_tools") or ())
                defs = apply_tools_filter(self.server, defs, self.cfg.get("tools"))
                self._set([d for d in defs if sanitize(self.server) + "_" + sanitize(d.name) not in dis])
            return self.defs

    def tools(self) -> list[Tool]:
        about = self.description or self.server

        async def search(args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None) -> ToolResult:
            args = args or {}
            try:
                defs = await self._current()
            except Exception as e:  # noqa: BLE001
                return ToolResult(output=str(e), is_error=True, metadata={"server": self.server})
            limit = args.get("limit")
            n = int(limit) if isinstance(limit, (int, float)) and not isinstance(limit, bool) and limit > 0 else 5
            order = bm25_rank([(d.name, d.description or "") for d in defs], str(args.get("query") or ""))
            hits = [
                {"name": defs[i].name, "description": defs[i].description, "inputSchema": defs[i].inputSchema,
                 "server": self.description}
                for i in order[:n]
            ]
            return ToolResult(output=json.dumps(hits, separators=(",", ":"), ensure_ascii=False), is_error=False, metadata={"server": self.server})

        async def call(args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None) -> ToolResult:
            args = args or {}
            name = str(args.get("name") or "")
            try:
                await self._current()
            except Exception as e:  # noqa: BLE001
                return ToolResult(output=str(e), is_error=True, metadata={"server": self.server})
            t = self.by_name.get(name)
            if t is None:
                return ToolResult(
                    output=f'unknown tool "{name}" on {self.server}', is_error=True, metadata={"server": self.server}
                )
            a = args.get("arguments")
            return await t.execute(a if isinstance(a, dict) else {}, ctx)

        prefix = sanitize(self.server) + "_"
        return [
            Tool(
                name=prefix + "search_tools",
                description=(
                    f"Search the tools of {about} by keywords. Returns name, description and inputSchema "
                    "of the best matches; run one with call_tool."
                ),
                input_schema=SEARCH_TOOLS_SCHEMA,
                source="mcp",
                execute=search,
            ),
            Tool(
                name=prefix + "call_tool",
                description="Run a tool found with search_tools.",
                input_schema=CALL_TOOL_SCHEMA,
                source="mcp",
                execute=call,
            ),
        ]


def _server_tool(server: str, defn: McpToolDef, session: Any, timeout: float) -> Tool:
    if isinstance(session, Session2026):
        return _convert_tool_2026(server, defn, session)
    return _convert_tool(server, defn, session, timeout)


def _server_tools(
    server: str, cfg: ServerConfig, session: Any, defs: list[McpToolDef], timeout: float
) -> list[Tool]:
    """The Tools one connected server contributes: every (filtered) server tool, or — with
    ``toolSearch: true`` — the two lazy meta-tools (§2.8)."""
    if cfg.get("toolSearch") is True:
        # Disable lists apply BEFORE search (by the final name the tool would have had).
        dis = set(cfg.get("_disable_tools") or ())
        defs = [d for d in defs if sanitize(server) + "_" + sanitize(d.name) not in dis]
        return _ToolSearch(server, cfg, session, defs, timeout).tools()
    return [_server_tool(server, d, session, timeout) for d in defs]


async def _remote_skills(name: str, session: Any) -> list[SkillDef]:
    """Remote skills (§2.7) of a 2026-path server as logical :class:`SkillDef` s (base
    ``skill://<name>/``) — they join the same ``skill`` tool inventory as local skills. A
    failure is isolated: warned, and the server's tools stay connected."""
    if not isinstance(session, Session2026) or not session.has_skills():
        return []
    try:
        fetched = await session.fetch_skills()
    except Exception as e:  # noqa: BLE001
        print(f'[toolnexus] MCP server "{name}" skills failed: {e}', file=sys.stderr)
        return []
    out: list[SkillDef] = []
    for skill_name, text, resources in fetched:
        data, content, malformed, _ = _parse_frontmatter(text)
        if malformed or data.get("name") != skill_name:
            print(f'[toolnexus] MCP server "{name}" skill "{skill_name}" skipped: bad frontmatter', file=sys.stderr)
            continue
        out.append(
            SkillDef(
                name=skill_name,
                content=content,
                description=data.get("description"),
                resources=resources,
                base=f"skill://{skill_name}/",
            )
        )
    return out


@dataclass
class McpServerResult:
    """One server's outcome, reported by ``load_mcp(..., on_server=...)`` the moment
    that server finishes — never waiting for a slower server (§4C)."""

    name: str
    status: McpStatus
    # This server's converted tools (empty unless connected). They stay usable
    # until the returned McpSource is closed.
    tools: list[Tool] = field(default_factory=list)
    # Why the server failed (None when connected or disabled).
    error: Optional[BaseException] = None


OnServer = Callable[[McpServerResult], Any]


@dataclass
class McpSource:
    tools: list[Tool]
    status: dict[str, McpStatus]
    _stack: AsyncExitStack = field(repr=False)
    # Connected sessions by server name, with each server's timeout (resource_tools).
    _sessions: dict[str, tuple[ClientSession, float]] = field(default_factory=dict, repr=False)
    # Remote skills (§2.7) from servers that offer the skills extension; the toolkit merges
    # them into the ``skill`` tool inventory. Empty for every pre-2026 server.
    skills: list[SkillDef] = field(default_factory=list)

    async def close(self) -> None:
        await self._stack.aclose()

    def resource_tools(self) -> list[Tool]:
        """``list_mcp_resources`` and ``read_mcp_resource`` over this source's
        connected servers (§4C). Opt-in: register them yourself. Both are
        read-only and stop working when the source is closed."""
        return _resource_tools(self)


def _resource_tools(src: "McpSource") -> list[Tool]:
    ro = ToolHints(read_only=True)

    def names() -> list[str]:
        return sorted(src._sessions)

    def conn(server: str) -> tuple[ClientSession, float]:
        c = src._sessions.get(server)
        if c is None:
            raise LookupError(
                f'no connected MCP server "{server}" (connected: {", ".join(names())})'
            )
        return c

    async def list_run(
        args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None
    ) -> ToolResult:
        args = args or {}
        servers = names()
        only = args.get("server")
        if isinstance(only, str) and only:
            try:
                conn(only)
            except LookupError as e:
                return ToolResult(output=f"list_mcp_resources: {e}", is_error=True)
            servers = [only]
        lines: list[str] = []
        items: list[dict[str, Any]] = []
        for name in servers:
            session, timeout = conn(name)
            try:
                async with asyncio.timeout(timeout):
                    res = await session.list_resources()
            except Exception as e:  # noqa: BLE001 - one server never breaks the list
                lines.append(f"{name}: error: {e}")
                continue
            for r in res.resources:
                uri = str(r.uri)
                line = f"{name} {uri}"
                if r.name:
                    line += " — " + r.name
                if r.mimeType:
                    line += f" ({r.mimeType})"
                if r.description:
                    line += ": " + r.description
                lines.append(line)
                items.append(
                    {
                        "server": name,
                        "uri": uri,
                        "name": r.name or "",
                        "mimeType": r.mimeType or "",
                        "description": r.description or "",
                    }
                )
        if not lines:
            return ToolResult(output="No resources.", is_error=False, metadata={"count": 0})
        return ToolResult(
            output="\n".join(lines),
            is_error=False,
            metadata={"count": len(items), "resources": items},
        )

    async def read_run(
        args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None
    ) -> ToolResult:
        args = args or {}
        server = str(args.get("server") or "")
        uri = str(args.get("uri") or "")
        try:
            session, timeout = conn(server)
        except LookupError as e:
            return ToolResult(output=f"read_mcp_resource: {e}", is_error=True)
        if not uri:
            return ToolResult(output="read_mcp_resource: uri is required", is_error=True)
        try:
            async with asyncio.timeout(timeout):
                res = await session.read_resource(uri)  # type: ignore[arg-type]
        except Exception as e:  # noqa: BLE001
            return ToolResult(output=f"read_mcp_resource: {e}", is_error=True)
        parts: list[str] = []
        for rc in res.contents:
            text = getattr(rc, "text", None)
            if text is not None:
                parts.append(text)
                continue
            blob = getattr(rc, "blob", None)
            if blob is not None:
                parts.append(f"[binary {rc.mimeType or ''}, {len(blob)} base64 chars]")
        return ToolResult(
            output="\n".join(parts), is_error=False, metadata={"server": server, "uri": uri}
        )

    return [
        Tool(
            name="list_mcp_resources",
            description=(
                "List the resources MCP servers offer (uri, name, description, mimeType). "
                "Optionally for one server."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "server": {"type": "string", "description": "Only this server (default: all)"}
                },
                "additionalProperties": False,
            },
            source="mcp",
            execute=list_run,
            hints=ro,
        ),
        Tool(
            name="read_mcp_resource",
            description=(
                "Read one MCP resource by server and uri. Text comes back as text; "
                "binary content is described, not inlined."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "server": {"type": "string", "description": "The MCP server name"},
                    "uri": {"type": "string", "description": "The resource uri"},
                },
                "required": ["server", "uri"],
                "additionalProperties": False,
            },
            source="mcp",
            execute=read_run,
            hints=ro,
        ),
    ]


def _make_elicitation_callback(wait_for: Any) -> Any:
    """Build the SDK ``elicitation_callback`` that bridges a server elicitation onto
    ``wait_for`` (§10). Passing it advertises the elicitation capability; the SDK
    gates that capability on a non-default callback (see ``mcp.client.session``)."""

    async def _callback(context: Any, params: Any) -> ElicitResult:
        r = wait_for(elicitation_to_request(params))
        if inspect.isawaitable(r):
            r = await r
        return answer_to_elicit_result(r)

    return _callback


@dataclass
class _LoadOpts:
    """Load options for the OAuth client (§2A.1a): one token store per load, and how
    ``auto`` mode opens the browser."""

    token_store: Optional[TokenStore] = None
    open_url: Optional[OpenUrl] = None

    def __post_init__(self) -> None:
        if self.token_store is None:
            from .mcp2026.auth import MemoryTokenStore

            self.token_store = MemoryTokenStore()

    def oauth(self, cfg: ServerConfig) -> OAuth:
        auth = cfg.get("auth")
        return OAuth(auth if isinstance(auth, dict) else None, store=self.token_store, open_url=self.open_url)


class _SdkBearerAuth(httpx.Auth):
    """Carries the OAuth bearer on the frozen fallback's SDK HTTP transport (§2A.1a): the
    token is attached per request, and a 401/403 challenge runs the same flow once."""

    def __init__(self, oauth: OAuth, resource: str, wait_for: Any) -> None:
        self.oauth = oauth
        self.resource = resource
        self.wait_for = wait_for

    async def async_auth_flow(self, request: httpx.Request):  # type: ignore[override]
        tok = await asyncio.to_thread(self.oauth.bearer, self.resource)
        if tok:
            request.headers["Authorization"] = "Bearer " + tok
        response = yield request
        www = response.headers.get("WWW-Authenticate", "")
        if response.status_code not in (401, 403) or not www:
            return
        try:
            await asyncio.to_thread(self.oauth.challenge, self.resource, response.status_code, www, tok)
        except AuthPending as ap:
            if self.wait_for is None:
                return
            ans = self.wait_for(ap.request)
            if inspect.isawaitable(ans):
                ans = await ans
            cb = (ans.data or {}).get("callbackUrl") if getattr(ans, "ok", False) else None
            if not isinstance(cb, str):
                return
            try:
                await asyncio.to_thread(self.oauth.complete, ap.request.id, cb)
            except Exception:  # noqa: BLE001 - the 401 stands
                return
        except Exception:  # noqa: BLE001 - the 401 stands
            return
        tok = await asyncio.to_thread(self.oauth.bearer, self.resource)
        if tok:
            request.headers["Authorization"] = "Bearer " + tok
            yield request


def _connect_budget(cfg: ServerConfig, timeout: float) -> float:
    """The bound on connect + init + list. A stdio server whose discover goes silent spends up to
    ``min(timeout, 5 s)`` in the probe before the frozen path even starts (§2.2a); that probe window is
    bounded on its own, so it is added rather than eating the frozen path's ``timeout``."""
    return timeout if is_remote(cfg) else timeout + min(timeout, STDIO_PROBE_MAX)


async def _connect_session(
    stack: AsyncExitStack,
    name: str,
    cfg: ServerConfig,
    wait_for: Any = None,
    opts: Optional[_LoadOpts] = None,
) -> Any:
    """Discover first (§2.2): a server answering ``server/discover`` with 2026-07-28 gets a
    :class:`Session2026`; anything else falls back to the frozen SDK path (§2.F), which
    opens the right transport and an initialized ClientSession on `stack`."""
    timeout = _to_seconds(cfg.get("timeout", DEFAULT_TIMEOUT * 1000))
    opts = opts or _LoadOpts()

    if is_remote(cfg):
        url = cfg["url"]
        headers = expand_env_headers(cfg.get("headers"))
        oauth = opts.oauth(cfg)
        s26 = await probe_http(name, url, headers, timeout, oauth, wait_for)
        if s26 is not None:
            stack.callback(s26.close)
            return s26
        bearer = _SdkBearerAuth(oauth, url, wait_for)
        try:
            read, write, _ = await stack.enter_async_context(
                streamablehttp_client(url, headers=headers, timeout=timeout, auth=bearer)
            )
        except Exception:
            # fall back to SSE
            read, write = await stack.enter_async_context(
                sse_client(url, headers=headers, auth=bearer)
            )
    else:
        argv = normalize_command(name, cfg)
        s26 = await probe_stdio(
            name, argv, merged_env(cfg.get("environment") or cfg.get("env")), cfg.get("cwd"), timeout, wait_for
        )
        if isinstance(s26, StdioConn):
            # §2.2a reuse: the server replied to discover without 2026 — continue into
            # ``initialize`` on the SAME child (no kill + respawn).
            read, write = await stack.enter_async_context(sdk_streams(s26))
        elif s26 is not None:
            stack.callback(s26.close)
            return s26
        else:  # discover silence: the probe child was killed; spawn afresh
            cmd, args = argv[0], argv[1:]
            env = {**os.environ, **(cfg.get("environment") or cfg.get("env") or {})}
            params = StdioServerParameters(
                command=cmd,
                args=args,
                cwd=cfg.get("cwd"),
                env=env,
            )
            read, write = await stack.enter_async_context(stdio_client(params))

    # Advertise elicitation + register the bridge ONLY when a wait_for exists to
    # satisfy it — a wait_for-less host degrades cleanly (the server won't elicit).
    # (§10 elicitation bridge). The SDK gates the advertised capability on a
    # non-default callback, so passing it only-when-present is enough.
    session = await stack.enter_async_context(
        ClientSession(
            read,
            write,
            read_timeout_seconds=timedelta(seconds=timeout),
            elicitation_callback=(
                _make_elicitation_callback(wait_for) if wait_for else None
            ),
        )
    )
    await session.initialize()
    return session


async def _safe_aclose(stack: AsyncExitStack) -> None:
    """Close a nested server stack, tolerating a self-inflicted timeout cancellation
    (the MCP SDK's anyio transports can leave the task cancelling as they unwind)."""
    try:
        await stack.aclose()
    except BaseException:  # noqa: BLE001 - teardown is best-effort
        pass


async def load_mcp(
    input: str | dict[str, Any],
    wait_for: Any = None,
    *,
    on_server: Optional[OnServer] = None,
    token_store: Optional[TokenStore] = None,
    open_url: Optional[OpenUrl] = None,
) -> McpSource:
    """Connect to every enabled server, list + convert tools. Failures are isolated:
    one bad server records status ``"failed"`` and never breaks the toolkit.

    Delegates to :func:`load_mcp_with_context` (parity with Go's ``LoadMcp`` →
    ``LoadMcpWithContext``); a healthy config behaves byte-identically to before.
    ``on_server`` — see :func:`load_mcp_with_context`.
    """
    return await load_mcp_with_context(
        input, wait_for=wait_for, on_server=on_server, token_store=token_store, open_url=open_url
    )


async def load_mcp_with_context(
    input: str | dict[str, Any],
    wait_for: Any = None,
    *,
    cancel: Optional[asyncio.Event] = None,
    on_server: Optional[OnServer] = None,
    token_store: Optional[TokenStore] = None,
    open_url: Optional[OpenUrl] = None,
    disable_tools: Optional[list[str]] = None,
) -> McpSource:
    """Cancellation/timeout-aware MCP load (§2 Gap 3). Mirrors Go
    ``LoadMcpWithContext``: each server's connect + list is bounded by that server's
    ``timeout`` (the SSE fallback included — previously unbounded), so a hung endpoint
    marks only that server ``failed`` and the build continues. A pre-set / fired
    ``cancel`` :class:`asyncio.Event` (the Python analogue of a parent context being
    cancelled) aborts the WHOLE load, tears down whatever connected so far, and raises
    :class:`asyncio.CancelledError`. Per-server failure isolation is unchanged.

    ``on_server`` (§4C, sync or async ``(McpServerResult) -> None``): called once per
    server the moment it is connected, failed or found disabled. With it set, the
    servers connect CONCURRENTLY (each in its own task, which owns that server's
    transport for its lifetime), so a slow server never holds up a fast one. Calls
    are serialized. The load still returns only after every server finished, and
    ``cancel`` still aborts it and closes every client — including ones whose tools
    were already handed out. Without it the load is byte-identical to before.

    Every server is asked ``server/discover`` first (§2.2): a 2026-07-28 server is driven
    by the in-house client, anything else by the frozen SDK path (§2.F). ``token_store``
    (``load/save/delete`` by server URL; default in-memory per load) and ``open_url`` (how
    ``auto`` OAuth opens the browser; default the OS opener) configure the OAuth client
    (§2A.1a).
    """
    config = parse_mcp_config(input)
    if disable_tools:
        config = {
            k: ({**v, "_disable_tools": list(disable_tools)} if isinstance(v, dict) and v.get("toolSearch") is True else v)
            for k, v in config.items()
        }
    opts = _LoadOpts(token_store=token_store, open_url=open_url)
    if on_server is not None:
        return await _load_mcp_concurrent(config, wait_for, cancel, on_server, opts)
    tools: list[Tool] = []
    remote_skills: list[SkillDef] = []
    status: dict[str, McpStatus] = {}
    sessions: dict[str, tuple[ClientSession, float]] = {}
    stack = AsyncExitStack()

    def _cancelled() -> bool:
        return cancel is not None and cancel.is_set()

    try:
        for name, cfg in config.items():
            if not is_enabled(cfg):
                status[name] = "disabled"
                continue
            # Parent cancellation aborts the whole load (Gap 3, A1).
            if _cancelled():
                raise asyncio.CancelledError()
            timeout = _to_seconds(cfg.get("timeout", DEFAULT_TIMEOUT * 1000))
            # Each server gets its own nested stack so a failure mid-connect is rolled
            # back cleanly without tearing down already-connected servers.
            server_stack = AsyncExitStack()
            try:
                # asyncio.timeout bounds connect + init + list (SSE fallback included)
                # in the CURRENT task, so the anyio transports are entered/exited in the
                # same task (no cross-task cancel-scope error).
                async with asyncio.timeout(_connect_budget(cfg, timeout)):
                    session = await _connect_session(server_stack, name, cfg, wait_for, opts)
                    defs = await _paginate_tools(session)
                    server_skills = await _remote_skills(name, session)
                defs = apply_tools_filter(name, defs, cfg.get("tools"))
                tools.extend(_server_tools(name, cfg, session, defs, timeout))
                remote_skills.extend(server_skills)
                # hand the server's lifetime to the parent stack
                await stack.enter_async_context(server_stack)
                sessions[name] = (session, timeout)
                status[name] = "connected"
            except asyncio.CancelledError:
                # A per-server ``asyncio.timeout`` expiry can surface as a bare
                # CancelledError (leaking from the MCP SDK's anyio cancel scope) instead
                # of TimeoutError. Distinguish a genuine parent cancellation (the cancel
                # Event is set) — which aborts the whole load — from a per-server timeout,
                # which isolates that one server and continues.
                try:
                    await _safe_aclose(server_stack)
                except Exception:  # noqa: BLE001
                    pass
                if _cancelled():
                    raise
                status[name] = "failed"
                print(f'[toolnexus] MCP server "{name}" failed: timed out', file=sys.stderr)
            except (TimeoutError, Exception) as e:  # noqa: BLE001 - isolate failures
                status[name] = "failed"
                await _safe_aclose(server_stack)
                print(
                    f'[toolnexus] MCP server "{name}" failed: {e}',
                    file=sys.stderr,
                )
    except BaseException:
        # Whole-load abort (cancellation): release everything connected so far.
        try:
            await stack.aclose()
        except Exception:  # noqa: BLE001
            pass
        raise

    return McpSource(tools=tools, status=status, _stack=stack, _sessions=sessions, skills=remote_skills)


async def _server_worker(
    name: str,
    cfg: ServerConfig,
    wait_for: Any,
    timeout: float,
    ready: "asyncio.Future[tuple[Any, Any, Optional[BaseException]]]",
    close_evt: asyncio.Event,
    opts: Optional[_LoadOpts] = None,
) -> None:
    """Own one server's transport for its whole life (on_server path). The anyio
    transports must be entered and exited in the same task, so this task connects,
    reports through ``ready``, then holds the connection until ``close_evt``."""
    server_stack = AsyncExitStack()
    try:
        async with asyncio.timeout(_connect_budget(cfg, timeout)):
            session = await _connect_session(server_stack, name, cfg, wait_for, opts)
            defs = await _paginate_tools(session)
            skills = await _remote_skills(name, session)
    except BaseException as e:  # noqa: BLE001 - reported as this server's failure
        await _safe_aclose(server_stack)
        if not ready.done():
            err = TimeoutError("timed out") if isinstance(e, asyncio.CancelledError) else e
            ready.set_result((None, None, err))
        return
    ready.set_result((session, (defs, skills), None))
    try:
        await close_evt.wait()
    finally:
        await _safe_aclose(server_stack)


async def _load_mcp_concurrent(
    config: McpConfig,
    wait_for: Any,
    cancel: Optional[asyncio.Event],
    on_server: OnServer,
    opts: Optional[_LoadOpts] = None,
) -> McpSource:
    tools: list[Tool] = []
    remote_skills: list[SkillDef] = []
    status: dict[str, McpStatus] = {}
    sessions: dict[str, tuple[ClientSession, float]] = {}
    close_evt = asyncio.Event()
    loop = asyncio.get_running_loop()
    workers: list[tuple[asyncio.Task[None], asyncio.Future[Any]]] = []
    waiting: dict[asyncio.Future[Any], tuple[str, ServerConfig, float]] = {}

    async def report(r: McpServerResult) -> None:
        out = on_server(r)
        if inspect.isawaitable(out):
            await out

    async def shutdown() -> None:
        close_evt.set()
        for task, ready in workers:
            if not ready.done():
                task.cancel()
        for task, _ in workers:
            try:
                await task
            except BaseException:  # noqa: BLE001 - teardown is best-effort
                pass

    stack = AsyncExitStack()
    stack.push_async_callback(shutdown)
    cancel_task: Optional[asyncio.Task[Any]] = (
        asyncio.ensure_future(cancel.wait()) if cancel is not None else None
    )
    try:
        if cancel is not None and cancel.is_set():
            raise asyncio.CancelledError()
        for name, cfg in config.items():
            if not is_enabled(cfg):
                status[name] = "disabled"
                await report(McpServerResult(name=name, status="disabled"))
                continue
            timeout = _to_seconds(cfg.get("timeout", DEFAULT_TIMEOUT * 1000))
            ready: asyncio.Future[Any] = loop.create_future()
            task = asyncio.ensure_future(
                _server_worker(name, cfg, wait_for, timeout, ready, close_evt, opts)
            )
            workers.append((task, ready))
            waiting[ready] = (name, cfg, timeout)
        pending_set: set[Any] = set(waiting)
        while pending_set:
            watch = pending_set | ({cancel_task} if cancel_task is not None else set())
            done, _ = await asyncio.wait(watch, return_when=asyncio.FIRST_COMPLETED)
            if cancel_task is not None and cancel_task in done:
                raise asyncio.CancelledError()
            for fut in done:
                pending_set.discard(fut)
                name, cfg, timeout = waiting[fut]
                session, listed, err = fut.result()
                if err is not None:
                    status[name] = "failed"
                    print(f'[toolnexus] MCP server "{name}" failed: {err}', file=sys.stderr)
                    await report(McpServerResult(name=name, status="failed", error=err))
                    continue
                defs, server_skills = listed
                defs = apply_tools_filter(name, defs, cfg.get("tools"))
                converted = _server_tools(name, cfg, session, defs, timeout)
                remote_skills.extend(server_skills)
                tools.extend(converted)
                sessions[name] = (session, timeout)
                status[name] = "connected"
                await report(McpServerResult(name=name, status="connected", tools=converted))
    except BaseException:
        # Whole-load abort: close every client, including ones already reported.
        try:
            await stack.aclose()
        except Exception:  # noqa: BLE001
            pass
        raise
    finally:
        if cancel_task is not None:
            cancel_task.cancel()
    return McpSource(tools=tools, status=status, _stack=stack, _sessions=sessions, skills=remote_skills)


# ---------------------------------------------------------------------------
# List-only inventory (§2 Gap 6).
# ---------------------------------------------------------------------------


@dataclass
class ToolInfo:
    """One listed tool definition (original, unprefixed name). Mirrors Go ``ToolInfo``."""

    name: str
    description: str
    input_schema: JSONSchema


@dataclass
class McpInventory:
    """Result of a list-only pass over an MCP config (Gap 6). Mirrors Go ``McpInventory``.

    ``tools`` maps server name → its full listed tool defs, UNFILTERED by the per-server
    ``tools`` allowlist (it exists to author/validate those filters). ``status`` is the
    per-server ``connected | disabled | failed``.
    """

    tools: dict[str, list[ToolInfo]]
    status: dict[str, McpStatus]


async def list_mcp_tools(
    input: str | dict[str, Any],
    *,
    cancel: Optional[asyncio.Event] = None,
    token_store: Optional[TokenStore] = None,
    open_url: Optional[OpenUrl] = None,
) -> McpInventory:
    """Connect to every enabled server, list tool definitions, and DISCONNECT before
    returning — no toolkit, no skills/builtins/agents, no Execute wiring, nothing left
    running (§2 Gap 6). Cancellation/timeout-aware per Gap 3. Failure isolation as in
    :func:`load_mcp`: a bad server is status ``failed``, never an error for the whole
    call. Returns the UNFILTERED tool set (original names) so it can validate the
    per-server ``tools`` allowlists. Mirrors Go ``ListMcpTools``.
    """
    config = parse_mcp_config(input)
    opts = _LoadOpts(token_store=token_store, open_url=open_url)
    by_server: dict[str, list[ToolInfo]] = {}
    status: dict[str, McpStatus] = {}

    for name, cfg in config.items():
        if not is_enabled(cfg):
            status[name] = "disabled"
            continue
        if cancel is not None and cancel.is_set():
            raise asyncio.CancelledError()
        timeout = _to_seconds(cfg.get("timeout", DEFAULT_TIMEOUT * 1000))
        server_stack = AsyncExitStack()
        try:
            async with asyncio.timeout(_connect_budget(cfg, timeout)):
                session = await _connect_session(server_stack, name, cfg, None, opts)
                defs = await _paginate_tools(session)
            by_server[name] = [
                ToolInfo(
                    name=d.name,
                    description=d.description or "",
                    input_schema=_build_input_schema(d),
                )
                for d in defs
            ]
            status[name] = "connected"
        except asyncio.CancelledError:
            # Per-server timeout can leak as a bare CancelledError (anyio scope). A set
            # cancel Event ⇒ genuine parent cancellation (abort); else ⇒ this server
            # timed out — isolate and continue.
            await _safe_aclose(server_stack)
            if cancel is not None and cancel.is_set():
                raise
            status[name] = "failed"
            print(f'[toolnexus] MCP server "{name}" failed: timed out', file=sys.stderr)
            continue
        except (TimeoutError, Exception) as e:  # noqa: BLE001 - isolate failures
            status[name] = "failed"
            print(
                f'[toolnexus] MCP server "{name}" failed: {e}',
                file=sys.stderr,
            )
        # DISCONNECT before returning — nothing left running.
        await _safe_aclose(server_stack)

    return McpInventory(tools=by_server, status=status)
