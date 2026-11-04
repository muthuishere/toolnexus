"""CLI client, ACP transport (SPEC §8C, ADR 0037 "ACP primary").

On the first model call the user's toolkit is served as a streamable-HTTP MCP server
on ``127.0.0.1:<ephemeral>/mcp``, guarded by a per-session bearer token, and handed to
the agent in ``session/new`` mcpServers (http when the agent advertises
``mcpCapabilities.http``, else a stdio bridge; a project MCP file in a temp cwd for
agents that ignore session servers). The agent calls the tools natively; toolnexus
hooks run at the server boundary. Every permission request is decided once per
``toolCallId``: tools served by our server are allowed, everything else (the agent's
own shell, edit, fetch …) is rejected.

``python -m toolnexus.cli_acp mcp-bridge <url>`` runs :func:`run_mcp_bridge` (the stdio
side of a stdio mcpServers entry) with the token from ``TOOLNEXUS_MCP_TOKEN``.
"""
from __future__ import annotations

import asyncio
import copy
import dataclasses
import hmac
import json
import os
import secrets
import shutil
import sys
import tempfile
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import TYPE_CHECKING, Any, Callable, Optional, TextIO

from .acp import ACPClient, ACPError, ACPLaunchError, ACPMCPServer, ACPOptions, ACPPermissionTimeoutError, load_acp
from .client import _call_hook, _get_hook
from .mcp_serve import build_mcp_server, serve_mcp_http_request
from .types import Tool, ToolResult

if TYPE_CHECKING:  # pragma: no cover
    from .cli import CliClient

__all__ = [
    "CLI_MCP_TOKEN_ENV",
    "CLI_SERVED_SKILL_TOOL_NAME",
    "cli_is_served_call",
    "cli_with_system_hint",
    "run_mcp_bridge",
]

# The name the toolkit's `skill` tool is served under to an ACP agent: copilot and
# devin ship their own `skill`, and a model asked to use "skill" picks the agent's.
CLI_SERVED_SKILL_TOOL_NAME = "load_skill"
# The MCP server name the agent sees.
_SERVER_NAME = "toolnexus"
# Carries the bearer token to a stdio bridge.
CLI_MCP_TOKEN_ENV = "TOOLNEXUS_MCP_TOKEN"


def cli_is_served_call(title: str, served: set[str] | dict[str, Any]) -> bool:
    """Match a permission title against the exact spellings agents use for a tool of
    OUR server: ``mcp__toolnexus__<tool>`` (claude), ``toolnexus/<tool>``, ``<tool>
    (toolnexus MCP Server)`` (gemini), ``Calling <tool> from toolnexus`` (devin),
    ``<tool>(…)``, or the bare name (copilot). No substring matching."""
    if not title:
        return False
    for n in served:
        if title in (
            n,
            f"mcp__{_SERVER_NAME}__{n}",
            f"{_SERVER_NAME}/{n}",
            f"{n} ({_SERVER_NAME} MCP Server)",
            f"Calling {n} from {_SERVER_NAME}",
        ):
            return True
        if title.startswith(n + "(") and title.endswith(")"):
            return True
    return False


def cli_with_system_hint(messages: list[Any], hint: str) -> list[Any]:
    """Append a style's ACP system hint to the first system message (a copy — the
    caller's messages are not mutated), or prepend a system message holding only the
    hint when there is none."""
    if not hint:
        return messages
    if messages and isinstance(messages[0], dict) and messages[0].get("role") == "system":
        content = messages[0].get("content")
        if isinstance(content, str):
            cp = dict(messages[0])
            cp["content"] = hint if content == "" else content + "\n\n" + hint
            return [cp, *messages[1:]]
    return [{"role": "system", "content": hint}, *messages]


def _served_tools(
    toolkit: Any,
    hooks: Any,
    ran: Callable[[str], None],
    loop_of: Callable[[], Optional[asyncio.AbstractEventLoop]],
) -> list[Tool]:
    """The toolkit as served: ``skill`` renamed to ``load_skill`` with the skills
    prompt appended to its description; every execute runs through the
    before_tool / after_tool hooks, on the caller's event loop when there is one."""
    if toolkit is None:
        return []
    out: list[Tool] = []
    for t in toolkit.tools():
        name = t.name
        served_name = name
        desc = t.description
        if t.source == "skill" and t.name == "skill":
            served_name = CLI_SERVED_SKILL_TOOL_NAME
            sp = toolkit.skills_prompt()
            if sp:
                desc = desc + "\n\n" + sp

        def make(tool: Tool, name: str, served_name: str):
            async def body(args: dict[str, Any]) -> ToolResult:
                before = _get_hook(hooks, "before_tool")
                if before is not None:
                    try:
                        ov = await _call_hook(before, {"name": name, "args": args, "hints": tool.hints})
                    except Exception as e:  # noqa: BLE001
                        return ToolResult(output=str(e), is_error=True)
                    if ov and ov.get("result") is not None:
                        return ov["result"]
                    if ov and ov.get("args") is not None:
                        args = ov["args"]
                ran(served_name)
                try:
                    res = await tool.execute(args, None)
                except Exception as e:  # noqa: BLE001
                    res = ToolResult(output=str(e), is_error=True)
                after = _get_hook(hooks, "after_tool")
                if after is not None:
                    try:
                        ov = await _call_hook(after, {"name": name, "args": args, "result": res})
                    except Exception as e:  # noqa: BLE001
                        return ToolResult(output=str(e), is_error=True)
                    if ov and ov.get("result") is not None:
                        res = ov["result"]
                return res

            async def execute(args: dict[str, Any], ctx: Any = None) -> ToolResult:
                loop = loop_of()
                here = asyncio.get_running_loop()
                if loop is not None and loop is not here and loop.is_running():
                    return await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(body(args), loop))
                return await body(args)

            return execute

        out.append(dataclasses.replace(t, name=served_name, description=desc, execute=make(t, name, served_name)))
    return out


class _LoopThread:
    """A private event loop on a daemon thread: runs the MCP request handling."""

    def __init__(self) -> None:
        self.loop = asyncio.new_event_loop()
        self._t = threading.Thread(target=self.loop.run_forever, name="toolnexus-cli-mcp", daemon=True)
        self._t.start()

    def run(self, coro: Any, timeout: Optional[float] = None) -> Any:
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    def stop(self) -> None:
        self.loop.call_soon_threadsafe(self.loop.stop)
        self._t.join(timeout=3)


class _Session:
    """One live ACP session: the agent, the served toolkit, the temp cwd."""

    def __init__(self, timeout_ms: int, hint: str) -> None:
        self.token = secrets.token_hex(32)
        self.timeout_ms = timeout_ms
        self.hint = hint
        self.url = ""
        self.temp_dir = ""
        self.client: Optional[ACPClient] = None
        self.served: set[str] = set()
        self._lock = threading.Lock()
        self._calls: dict[str, str] = {}
        self._decisions: dict[str, bool] = {}
        self._log: list[dict[str, Any]] = []
        self._ran: list[str] = []
        self._httpd: Optional[ThreadingHTTPServer] = None
        self._loop: Optional[_LoopThread] = None

    # -- served toolkit ------------------------------------------------------ #

    def ran(self, name: str) -> None:
        with self._lock:
            self._ran.append(name)

    def serve(self, tools: list[Tool]) -> None:
        self._loop = _LoopThread()
        loop = self._loop
        want = ("Bearer " + self.token).encode("latin-1")
        cfg = {"name": _SERVER_NAME}

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a: Any) -> None:  # never log (the token is in headers)
                pass

            def _handle(self) -> None:
                if self.path != "/mcp" and not self.path.startswith("/mcp?"):
                    self.send_response(404)
                    self.end_headers()
                    return
                got = (self.headers.get("Authorization") or "").encode("latin-1", errors="replace")
                if not hmac.compare_digest(got, want):
                    body = b"unauthorized\n"
                    self.send_response(401)
                    self.send_header("WWW-Authenticate", "Bearer")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                if self.command != "POST":
                    # Stateless: no standalone SSE stream (GET) and no session to
                    # DELETE. 405 tells a streamable-HTTP client to go on without one;
                    # an opened GET stream would never end and stall the client.
                    self.send_response(405)
                    self.send_header("Allow", "POST")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                length = int(self.headers.get("Content-Length", 0) or 0)
                data = self.rfile.read(length) if length else b""
                headers = [(k.lower().encode("latin-1"), v.encode("latin-1")) for k, v in self.headers.items()]
                try:
                    status, rh, rb = loop.run(
                        serve_mcp_http_request(build_mcp_server(tools, cfg), self.command, self.path, headers, data)
                    )
                except Exception:  # noqa: BLE001
                    self.send_response(500)
                    self.end_headers()
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

        httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        httpd.daemon_threads = True
        self._httpd = httpd
        self.url = f"http://127.0.0.1:{httpd.server_address[1]}/mcp"
        threading.Thread(target=httpd.serve_forever, name="toolnexus-cli-http", daemon=True).start()

    def stop_server(self) -> None:
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
            self._httpd = None
        if self._loop is not None:
            self._loop.stop()
            self._loop = None

    def close(self) -> None:
        if self.client is not None:
            self.client.close()
        self.stop_server()
        if self.temp_dir:
            shutil.rmtree(self.temp_dir, ignore_errors=True)

    # -- ACP callbacks ------------------------------------------------------- #

    def update(self, params: dict[str, Any]) -> None:
        """Remember the first non-empty title of each toolCallId (devin puts the name
        only on the tool_call update and sends an empty permission title)."""
        u = params.get("update") if isinstance(params, dict) else None
        if not isinstance(u, dict) or u.get("sessionUpdate") not in ("tool_call", "tool_call_update"):
            return
        cid, title = u.get("toolCallId"), u.get("title")
        if isinstance(cid, str) and cid and isinstance(title, str) and title:
            with self._lock:
                self._calls.setdefault(cid, title)

    def permission(self, params: dict[str, Any]) -> Optional[str]:
        """Decide one session/request_permission, once per toolCallId. Allowed => an
        allow option; otherwise None so the ACP client answers with a reject option."""
        tc = params.get("toolCall") or {}
        cid = tc.get("toolCallId") if isinstance(tc.get("toolCallId"), str) else ""
        title = tc.get("title") if isinstance(tc.get("title"), str) else ""
        with self._lock:
            if not title:
                title = self._calls.get(cid, "")
            if cid in self._decisions:
                allowed = self._decisions[cid]
            else:
                allowed = bool(cid) and cli_is_served_call(title, self.served)
                if cid:
                    self._decisions[cid] = allowed
            self._log.append({"tool_call_id": cid, "title": title, "allowed": allowed})
        if not allowed:
            return None
        options = [o for o in (params.get("options") or []) if isinstance(o, dict)]
        for o in options:
            if o.get("kind") == "allow_once":
                return o.get("optionId")
        for o in options:
            if str(o.get("kind") or "").startswith("allow"):
                return o.get("optionId")
        return None

    # -- turns --------------------------------------------------------------- #

    def generate(self, request: dict[str, Any]) -> dict[str, Any]:
        assert self.client is not None
        req = dict(request)
        req["messages"] = cli_with_system_hint(list(request.get("messages") or []), self.hint)
        try:
            return self.client.generate(req)
        except ACPPermissionTimeoutError:
            from .cli import CliError

            raise CliError(f"cli: timed out after {self.timeout_ms}ms") from None
        except ACPError as e:
            from .cli import CliError

            raise CliError(f"cli: {e}") from e

    def info(self) -> dict[str, Any]:
        sent = copy.deepcopy(self.client.mcp_servers_sent if self.client is not None else [])
        for e in sent:
            for k in ("headers", "env"):
                for nv in e.get(k) or []:
                    if isinstance(nv, dict):
                        nv["value"] = "[redacted]"
        with self._lock:
            return {
                "url": self.url,
                "mcp_servers_sent": sent,
                "permissions": list(self._log),
                "served_calls": list(self._ran),
                "session_cwd": self.temp_dir,
            }


def _open_session(c: "CliClient") -> _Session:
    """Serve the toolkit, write the project MCP file if the style needs one, and start
    the agent. Raises CliError; a start/initialize failure has an ACPLaunchError
    cause (the only failures Auto falls back on)."""
    from .cli import CliError, _expand_env

    argv = c._acp_argv or []
    bin_path = shutil.which(argv[0]) if argv else None
    if bin_path is None:
        try:
            raise ACPLaunchError("acp: start")
        except ACPLaunchError as cause:
            raise CliError(f"cli: command not found: {argv[0] if argv else ''}") from cause
    d = c._style
    s = _Session(c._timeout_ms, d.acp.system_hint if d.acp is not None else "")
    tools = _served_tools(c._toolkit, c._hooks, s.ran, lambda: c._loop)
    s.served = {t.name for t in tools}
    s.serve(tools)

    cwd = os.path.abspath(c._cwd or os.getcwd())
    servers: list[ACPMCPServer] = []
    try:
        if d.acp is not None and d.acp.project_mcp_file and not c._acp_command:
            s.temp_dir = tempfile.mkdtemp(prefix="toolnexus-acp-")
            cwd = s.temp_dir
            f = os.path.join(s.temp_dir, d.acp.project_mcp_file)
            os.makedirs(os.path.dirname(f), mode=0o700, exist_ok=True)
            body = json.dumps(
                {
                    "mcpServers": {
                        _SERVER_NAME: {
                            "type": "http",
                            "url": s.url,
                            "headers": {"Authorization": "Bearer " + s.token},
                        }
                    }
                },
                separators=(",", ":"),
            )
            fd = os.open(f, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(body)
        else:
            entry = ACPMCPServer(name=_SERVER_NAME, url=s.url, headers={"Authorization": "Bearer " + s.token})
            bridge = list(c._bridge_command or [])
            if not bridge:
                p = shutil.which("toolnexus")
                if p:
                    bridge = [p, "mcp-bridge"]
            if bridge:
                entry.command = bridge[0]
                entry.args = [*bridge[1:], s.url]
                entry.env = {CLI_MCP_TOKEN_ENV: s.token}
            servers = [entry]
    except OSError as e:
        s.close()
        raise CliError(f"cli: acp: {e}") from e

    stderr = _CappedText()

    def on_update(params: dict[str, Any]) -> None:
        s.update(params)
        if c._on_acp_update is not None:
            c._on_acp_update(params)

    timeout = c._timeout_ms / 1000.0
    try:
        s.client = load_acp(
            ACPOptions(
                command=bin_path,
                args=argv[1:],
                cwd=cwd,
                env=_expand_env(c._env) if c._env else None,
                permission_timeout=timeout,
                request_timeout=timeout,
                mcp_servers=servers,
                model=c._model_opt or None,
                stderr=stderr,
                on_permission=s.permission,
                on_update=on_update,
            )
        )
    except ACPError as e:
        s.close()
        msg = str(e)
        tail = stderr.text().strip()
        if tail:
            msg += ": " + tail
        raise CliError(f"cli: {msg}") from e
    return s


class _CappedText:
    """Keeps the last 4 KiB of the agent's stderr for error messages."""

    def __init__(self) -> None:
        self._buf = ""
        self._lock = threading.Lock()

    def write(self, s: str) -> int:
        with self._lock:
            self._buf = (self._buf + s)[-4096:]
        return len(s)

    def text(self) -> str:
        with self._lock:
            return self._buf


def run_mcp_bridge(url: str, token: str, inp: TextIO, out: TextIO) -> None:
    """The stdio side of a stdio mcpServers entry: each JSON-RPC line read from
    ``inp`` is POSTed to the toolkit's streamable-HTTP endpoint (with the bearer token
    when set), and each reply — plain JSON or SSE ``data:`` frames — is written to
    ``out`` as one line."""
    session = ""
    for raw in inp:
        line = raw.strip()
        if not line:
            continue
        req = urllib.request.Request(url, data=line.encode("utf-8"), method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json, text/event-stream")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        if session:
            req.add_header("Mcp-Session-Id", session)
        try:
            resp = urllib.request.urlopen(req)  # noqa: S310 — loopback URL handed by the client
        except urllib.error.HTTPError as e:
            if e.code == 401:
                raise RuntimeError(f"mcp-bridge: unauthorized (missing or wrong {CLI_MCP_TOKEN_ENV})") from None
            resp = e
        with resp:
            sid = resp.headers.get("Mcp-Session-Id")
            if sid:
                session = sid
            body = resp.read().decode("utf-8", errors="replace")
            ctype = resp.headers.get("Content-Type") or ""
        if ctype.startswith("text/event-stream"):
            for ln in body.split("\n"):
                ln = ln.rstrip("\r")
                if ln.startswith("data:"):
                    out.write(ln[5:].strip() + "\n")
            out.flush()
            continue
        b = body.strip()
        if b.startswith("{"):
            out.write(b + "\n")
            out.flush()


def _main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "mcp-bridge":
        try:
            run_mcp_bridge(argv[1], os.environ.get(CLI_MCP_TOKEN_ENV, ""), sys.stdin, sys.stdout)
        except Exception as e:  # noqa: BLE001
            print(e, file=sys.stderr)
            return 1
        return 0
    print("usage: python -m toolnexus.cli_acp mcp-bridge <url>", file=sys.stderr)
    return 2


if __name__ == "__main__":  # pragma: no cover
    sys.exit(_main(sys.argv[1:]))
