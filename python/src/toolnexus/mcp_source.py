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
from typing import Any, Optional

from mcp import ClientSession
from mcp.client.sse import sse_client
from mcp.client.stdio import StdioServerParameters, stdio_client
from mcp.client.streamable_http import streamablehttp_client
from mcp.types import ElicitResult
from mcp.types import Tool as McpToolDef

from .types import (
    Answer,
    JSONSchema,
    McpStatus,
    Request,
    Tool,
    ToolContext,
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
        return raw["mcpServers"]  # type: ignore[return-value]
    if "servers" in raw:
        return raw["servers"]  # type: ignore[return-value]
    if "mcp" in raw:
        return raw["mcp"]  # type: ignore[return-value]
    # Bare map of servers — but strip sibling top-level config keys (builtins/agents/
    # a2a/mcpServer) so they are not mistaken for MCP servers when no wrapper key is
    # present. `mcpServer` (singular) is the inbound MCP-serve profile (§7C), distinct
    # from the `mcpServers` (plural) client wrapper stripped above.
    return {
        k: v
        for k, v in raw.items()
        if k not in ("builtins", "agents", "a2a", "mcpServer")
    }


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
    return "\n".join(item.text for item in content if _is_text_content(item))


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


def _convert_tool(
    server: str, defn: McpToolDef, session: ClientSession, timeout: float
) -> Tool:
    input_schema: JSONSchema = _build_input_schema(defn)

    async def execute(
        args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None
    ) -> ToolResult:
        eff_timeout = ctx.timeout if (ctx and ctx.timeout is not None) else timeout
        try:
            result = await session.call_tool(
                defn.name,
                arguments=args or {},
                read_timeout_seconds=timedelta(seconds=eff_timeout),
            )
            if result.isError:
                return ToolResult(
                    output=_format_tool_error_content(result.content),
                    is_error=True,
                    metadata={"server": server},
                )
            if result.structuredContent is not None:
                return ToolResult(
                    output=json.dumps(result.structuredContent),
                    is_error=False,
                    metadata={"server": server},
                )
            return ToolResult(
                output=_join_text_content(result.content),
                is_error=False,
                metadata={"server": server},
            )
        except Exception as e:  # noqa: BLE001 - failures isolated per call
            return ToolResult(output=str(e), is_error=True, metadata={"server": server})

    return Tool(
        name=sanitize(server) + "_" + sanitize(defn.name),
        description=defn.description or "",
        input_schema=input_schema,
        source="mcp",
        execute=execute,
    )


@dataclass
class McpSource:
    tools: list[Tool]
    status: dict[str, McpStatus]
    _stack: AsyncExitStack = field(repr=False)

    async def close(self) -> None:
        await self._stack.aclose()


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


async def _connect_session(
    stack: AsyncExitStack, name: str, cfg: ServerConfig, wait_for: Any = None
) -> ClientSession:
    """Open the right transport and an initialized ClientSession on `stack`."""
    timeout = _to_seconds(cfg.get("timeout", DEFAULT_TIMEOUT * 1000))

    if is_remote(cfg):
        url = cfg["url"]
        headers = expand_env_headers(cfg.get("headers"))
        try:
            read, write, _ = await stack.enter_async_context(
                streamablehttp_client(url, headers=headers, timeout=timeout)
            )
        except Exception:
            # fall back to SSE
            read, write = await stack.enter_async_context(
                sse_client(url, headers=headers)
            )
    else:
        command = cfg["command"]
        cmd, args = command[0], list(command[1:])
        merged_env = {**os.environ, **(cfg.get("environment") or cfg.get("env") or {})}
        params = StdioServerParameters(
            command=cmd,
            args=args,
            cwd=cfg.get("cwd"),
            env=merged_env,
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


async def load_mcp(input: str | dict[str, Any], wait_for: Any = None) -> McpSource:
    """Connect to every enabled server, list + convert tools. Failures are isolated:
    one bad server records status ``"failed"`` and never breaks the toolkit.

    Delegates to :func:`load_mcp_with_context` (parity with Go's ``LoadMcp`` →
    ``LoadMcpWithContext``); a healthy config behaves byte-identically to before.
    """
    return await load_mcp_with_context(input, wait_for=wait_for)


async def load_mcp_with_context(
    input: str | dict[str, Any],
    wait_for: Any = None,
    *,
    cancel: Optional[asyncio.Event] = None,
) -> McpSource:
    """Cancellation/timeout-aware MCP load (§2 Gap 3). Mirrors Go
    ``LoadMcpWithContext``: each server's connect + list is bounded by that server's
    ``timeout`` (the SSE fallback included — previously unbounded), so a hung endpoint
    marks only that server ``failed`` and the build continues. A pre-set / fired
    ``cancel`` :class:`asyncio.Event` (the Python analogue of a parent context being
    cancelled) aborts the WHOLE load, tears down whatever connected so far, and raises
    :class:`asyncio.CancelledError`. Per-server failure isolation is unchanged.
    """
    config = parse_mcp_config(input)
    tools: list[Tool] = []
    status: dict[str, McpStatus] = {}
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
                async with asyncio.timeout(timeout):
                    session = await _connect_session(server_stack, name, cfg, wait_for)
                    defs = await _paginate_tools(session)
                defs = apply_tools_filter(name, defs, cfg.get("tools"))
                for defn in defs:
                    tools.append(_convert_tool(name, defn, session, timeout))
                # hand the server's lifetime to the parent stack
                await stack.enter_async_context(server_stack)
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

    return McpSource(tools=tools, status=status, _stack=stack)


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
) -> McpInventory:
    """Connect to every enabled server, list tool definitions, and DISCONNECT before
    returning — no toolkit, no skills/builtins/agents, no Execute wiring, nothing left
    running (§2 Gap 6). Cancellation/timeout-aware per Gap 3. Failure isolation as in
    :func:`load_mcp`: a bad server is status ``failed``, never an error for the whole
    call. Returns the UNFILTERED tool set (original names) so it can validate the
    per-server ``tools`` allowlists. Mirrors Go ``ListMcpTools``.
    """
    config = parse_mcp_config(input)
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
            async with asyncio.timeout(timeout):
                session = await _connect_session(server_stack, name, cfg, None)
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
