"""CLI client styles (SPEC §8C, ADR 0037): an agent CLI the user already has
installed — and pays for — becomes the model behind an ordinary client, selected by
a closed enum, never by a string.

Two transports, one constructor:

* **ACP** (primary, ``cli_acp.py``): when the style declares an ACP launch and the
  binary speaks it, the user's whole toolkit is served as a local MCP server and
  handed to the agent in ``session/new`` mcpServers. The agent calls the tools.
* **one-shot CLI** (fallback, this module): the CLI runs once per model turn with
  the conversation rendered under a fixed preamble; its reply is parsed as an
  OpenAI-shaped envelope and tool calls go back to the toolnexus loop.

A style is data (``clistyles/*.json``, byte-identical to ``conformance/cli-styles/``)
and holds no binary: ``command`` is always the caller's.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import threading
from dataclasses import dataclass, field
from enum import Enum
from importlib import resources
from typing import Any, Callable, Optional

from .client import Client, InProcessTransport, _IN_PROCESS_BASE_URL

__all__ = [
    "CLI_PREAMBLE",
    "CliACPLaunch",
    "CliClient",
    "CliError",
    "CliStyleDef",
    "CliStyles",
    "CliTransports",
    "cli_style_def",
    "create_cli_client",
    "load_cli_style",
    "parse_cli_reply",
    "render_cli_prompt",
]

# Bounds one CLI invocation / one ACP prompt (owner decision 2026-11-04).
CLI_DEFAULT_CALL_TIMEOUT_MS = 300000

CLI_PREAMBLE: str = (resources.files(__package__) / "clistyles" / "preamble.txt").read_text(encoding="utf-8")


class CliError(RuntimeError):
    """A CLI client failure. ``str(e)`` is the pinned SPEC §8C message."""


class CliStyles(Enum):
    """The closed set of CLI families. Each is a shape (argv, prompt mode, answer
    source, ACP launch), never a binary. The value is the shared style file name; it
    is never accepted as input."""

    CLAUDE = "claude"
    CODEX = "codex"
    COPILOT = "copilot"
    OPENCODE = "opencode"
    DEVIN = "devin"
    GEMINI = "gemini"
    GOOSE = "goose"
    CUSTOM = "custom"


class CliTransports(Enum):
    """``AUTO`` (default) uses ACP when the style has an ACP launch and the binary
    answers ``initialize``, else the one-shot CLI; ``ACP`` and ``CLI`` force one."""

    AUTO = "auto"
    ACP = "acp"
    CLI = "cli"


@dataclass
class CliACPLaunch:
    """A style's ACP launch: args appended to ``command``; for agents that ignore
    ``session/new`` mcpServers, the project MCP file written into a fresh temp cwd;
    and an optional hint appended to every ACP turn's system prompt."""

    args: list[str] = field(default_factory=list)
    project_mcp_file: str = ""
    system_hint: str = ""
    verified: str = ""
    notes: str = ""


@dataclass
class CliStyleDef:
    """One CLI's invocation recipe — data shared by every port. ``args`` elements are
    strings or optional groups (lists of strings, kept only when a model is set).
    ``prompt``: stdin | arg | file. ``answer``: stdout | out."""

    name: str
    args: Optional[list[Any]] = None
    prompt: str = ""
    answer: str = ""
    timeout_ms: int = 0
    env: dict[str, str] = field(default_factory=dict)
    verified: str = ""
    notes: str = ""
    acp: Optional[CliACPLaunch] = None

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "CliStyleDef":
        acp = d.get("acp")
        return cls(
            name=str(d.get("name") or ""),
            args=d.get("args"),
            prompt=str(d.get("prompt") or ""),
            answer=str(d.get("answer") or ""),
            timeout_ms=int(d.get("timeoutMs") or 0),
            env=dict(d.get("env") or {}),
            verified=str(d.get("verified") or ""),
            notes=str(d.get("notes") or ""),
            acp=None
            if not isinstance(acp, dict)
            else CliACPLaunch(
                args=list(acp.get("args") or []),
                project_mcp_file=str(acp.get("projectMcpFile") or ""),
                system_hint=str(acp.get("systemHint") or ""),
                verified=str(acp.get("verified") or ""),
                notes=str(acp.get("notes") or ""),
            ),
        )


def cli_style_def(style: CliStyles) -> Optional[CliStyleDef]:
    """The built-in definition of a style (``None`` for ``CUSTOM``)."""
    if not isinstance(style, CliStyles) or style is CliStyles.CUSTOM:
        return None
    text = (resources.files(__package__) / "clistyles" / f"{style.value}.json").read_text(encoding="utf-8")
    return CliStyleDef.from_dict(json.loads(text))


def load_cli_style(path_or_json: str) -> CliStyleDef:
    """A user style from a file path, or from JSON text starting with ``{``. Pass the
    result as ``create_cli_client(style_def=...)``."""
    try:
        if path_or_json.strip().startswith("{"):
            data = json.loads(path_or_json)
        else:
            with open(path_or_json, encoding="utf-8") as f:
                data = json.load(f)
    except (OSError, ValueError) as e:
        raise CliError(f"cli style: {e}") from e
    if not isinstance(data, dict):
        raise CliError("cli style: not a JSON object")
    return CliStyleDef.from_dict(data)


# --------------------------------------------------------------------------- #
# Argv templating and validation.
# --------------------------------------------------------------------------- #

_PLACEHOLDER_RE = re.compile(r"\{\{([^{}]*)\}\}")


@dataclass
class _Elem:
    group: bool
    parts: list[str]


def _normalize_args(args: Optional[list[Any]]) -> list[_Elem]:
    out: list[_Elem] = []
    for e in args or []:
        if isinstance(e, str):
            out.append(_Elem(False, [e]))
        elif isinstance(e, (list, tuple)):
            if not all(isinstance(x, str) for x in e):
                raise CliError("cli: a group may only hold strings")
            out.append(_Elem(True, list(e)))
        else:
            raise CliError("cli: an arg must be a string or a group of strings")
    return out


def _validate(d: CliStyleDef, elems: list[_Elem], model: str) -> None:
    if d.prompt not in ("stdin", "arg", "file"):
        raise CliError(f"cli: prompt must be stdin, arg or file, not {json.dumps(d.prompt)}")
    if d.answer not in ("stdout", "out"):
        raise CliError(f"cli: answer must be stdout or out, not {json.dumps(d.answer)}")
    seen: set[str] = set()
    for e in elems:
        for p in e.parts:
            for m in _PLACEHOLDER_RE.finditer(p):
                name = m.group(1)
                if name == "model":
                    if not e.group and not model:
                        raise CliError("cli: the style needs a model: {{model}} is outside an optional group")
                elif name not in ("prompt", "file", "out"):
                    raise CliError(f"cli: unknown placeholder {{{{{name}}}}}")
                seen.add(name)
    if model and "model" not in seen:
        raise CliError(f"cli: style {json.dumps(d.name)} takes no model")
    if d.prompt == "arg" and "prompt" not in seen:
        raise CliError('cli: prompt "arg" needs {{prompt}} in args')
    if d.prompt == "file" and "file" not in seen:
        raise CliError('cli: prompt "file" needs {{file}} in args')
    if d.answer == "out" and "out" not in seen:
        raise CliError('cli: answer "out" needs {{out}} in args')


# --------------------------------------------------------------------------- #
# The envelope: render and tolerant parse.
# --------------------------------------------------------------------------- #


def render_cli_prompt(messages: Optional[list[Any]], tools: Optional[list[Any]]) -> str:
    """``PREAMBLE + "\\nREQUEST:\\n" + {"messages","tools"}``: the OpenAI-shaped
    request, unmodified, as compact JSON (non-ASCII kept; Python never HTML-escapes)."""
    payload = json.dumps(
        {"messages": messages or [], "tools": tools or []}, separators=(",", ":"), ensure_ascii=False
    )
    return f"{CLI_PREAMBLE}\nREQUEST:\n{payload}"


def _parse_object(s: str) -> Optional[dict[str, Any]]:
    # json.loads rejects trailing text after a complete value: never ignored.
    try:
        v = json.loads(s)
    except ValueError:
        return None
    return v if isinstance(v, dict) else None


def parse_cli_reply(text: str) -> dict[str, Any]:
    """The tolerant six-step envelope parser: (1) trim; (2) strip one markdown fence;
    (3) parse the whole text as one JSON object, else (4) the span from the first
    ``{`` to the last ``}``; (5) unwrap ``choices[0].message`` or ``message``; (6)
    ``tool_calls`` (flat or ``{function:{}}``, string or object arguments) win, else
    ``content``. Anything that is not an envelope is returned verbatim as content.

    Returns the in-process answer shape: ``{"content": ...}`` or
    ``{"tool_calls": [{"name", "arguments", "id"?}]}``.
    """
    s = text.strip()
    if s.startswith("```"):
        nl = s.find("\n")
        s = s[nl + 1 :] if nl >= 0 else ""
        s = s.strip()
        if s.endswith("```"):
            s = s[:-3]
        s = s.strip()
    obj = _parse_object(s)
    if obj is None:
        i, j = s.find("{"), s.rfind("}")
        if i >= 0 and j > i:
            obj = _parse_object(s[i : j + 1])
    if obj is None:
        return {"content": text}
    choices = obj.get("choices")
    if isinstance(choices, list):
        if choices and isinstance(choices[0], dict) and isinstance(choices[0].get("message"), dict):
            obj = choices[0]["message"]
    elif isinstance(obj.get("message"), dict):
        obj = obj["message"]
    raw = obj.get("tool_calls")
    if isinstance(raw, list):
        calls: list[dict[str, Any]] = []
        for el in raw:
            if not isinstance(el, dict):
                continue
            fn = el["function"] if isinstance(el.get("function"), dict) else el
            name = fn.get("name")
            if not isinstance(name, str) or not name:
                continue
            args = fn.get("arguments")
            call: dict[str, Any] = {"name": name, "arguments": {} if args is None else args}
            if isinstance(el.get("id"), str) and el["id"]:
                call["id"] = el["id"]
            calls.append(call)
        if calls:
            return {"tool_calls": calls}
    if "content" in obj:
        c = obj["content"]
        if isinstance(c, str):
            return {"content": c}
        if c is None:
            return {"content": ""}
        return {"content": json.dumps(c, separators=(",", ":"), ensure_ascii=False, sort_keys=True)}
    return {"content": text}


# --------------------------------------------------------------------------- #
# One-shot invocation.
# --------------------------------------------------------------------------- #

_ENV_REF_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _expand_env(env: dict[str, str]) -> dict[str, str]:
    """``${VAR}`` expanded from this process's environment at call time. Values are
    never logged."""
    return {k: _ENV_REF_RE.sub(lambda m: os.environ.get(m.group(1), ""), v) for k, v in env.items()}


def _truncate(msg: str, n: int = 1000) -> str:
    return msg if len(msg) <= n else msg[:n] + "…"


def _kill_group(proc: "subprocess.Popen[Any]") -> None:
    try:
        if os.name == "posix":
            os.killpg(proc.pid, signal.SIGKILL)
        else:
            proc.kill()
    except (OSError, ProcessLookupError):
        pass


class _CliTransport(InProcessTransport):
    """The in-process seam, with streaming refused per transport."""

    def __init__(self, owner: "CliClient") -> None:
        super().__init__(owner._generate)
        self._owner = owner

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):  # noqa: A003
        session = self._owner._session()
        if session is None:
            raise CliError("cli: streaming is not supported by the one-shot CLI transport; use Run")
        raise CliError(
            "cli: streaming is not supported by the ACP transport in the Python port "
            "(the in-process seam does not stream); use run"
        )


class CliClient(Client):
    """An ordinary :class:`Client` whose model is an agent CLI, plus :meth:`close`
    (ends the ACP agent and the served toolkit), :meth:`transport` and
    :meth:`fallback_reason`. Build with :func:`create_cli_client`."""

    def __init__(self, d: CliStyleDef, opts: dict[str, Any], client_options: dict[str, Any]) -> None:
        self._style = d
        self._command: str = opts["command"]
        self._model_opt: str = opts["model"] or ""
        self._toolkit = opts["toolkit"]
        self._acp_command: Optional[list[str]] = opts["acp_command"]
        self._bridge_command: Optional[list[str]] = opts["bridge_command"]
        self._on_acp_update: Optional[Callable[[dict[str, Any]], None]] = opts["on_acp_update"]
        self._cwd: Optional[str] = opts["cwd"]
        self._hooks = client_options.get("hooks")
        self._lock = threading.Lock()
        self._acp: Any = None
        self._closed = False
        self._fallback = ""
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._elems: list[_Elem] = []
        self._has_cli = False

        transport: CliTransports = opts["transport"]
        if self._acp_command:
            self._acp_argv: Optional[list[str]] = list(self._acp_command)
        elif d.acp is not None:
            self._acp_argv = [self._command, *d.acp.args]
        else:
            self._acp_argv = None
        if transport is CliTransports.ACP and self._acp_argv is None:
            raise CliError(
                f"cli: style {json.dumps(d.name)} has no ACP launch; set ACPCommand or use CliTransports.CLI"
            )
        if transport is not CliTransports.ACP:
            acp_only = (
                d.args is None and not d.prompt and not d.answer
                and self._acp_argv is not None and transport is CliTransports.AUTO
            )
            if not acp_only:
                elems = _normalize_args(d.args)
                _validate(d, elems, self._model_opt)
                self._elems, self._has_cli = elems, True

        self._env = {**d.env, **(opts["env"] or {})}
        self._timeout_ms = opts["call_timeout_ms"] or d.timeout_ms or CLI_DEFAULT_CALL_TIMEOUT_MS
        if transport is CliTransports.CLI or self._acp_argv is None:
            self._chosen = CliTransports.CLI
        elif transport is CliTransports.ACP:
            self._chosen = CliTransports.ACP
        else:
            self._chosen = CliTransports.AUTO

        client_options.setdefault("retries", 0)
        super().__init__(
            base_url=_IN_PROCESS_BASE_URL,
            style="openai",
            api_key="in-process",
            model=self._model_opt or d.name,
            http_transport=_CliTransport(self),
            **client_options,
        )

    # -- public surface ------------------------------------------------------ #

    def transport(self) -> str:
        """``"acp"``, ``"cli"``, or ``"auto"`` before the first model call decided."""
        with self._lock:
            return self._chosen.value

    def fallback_reason(self) -> str:
        """Why Auto fell back from ACP to the one-shot CLI (``""`` when it did not)."""
        with self._lock:
            return self._fallback

    def acp_session(self) -> Optional[dict[str, Any]]:
        """A read-only view of the live ACP session (``None`` when none is open):
        ``url``, ``mcp_servers_sent`` (header/env values redacted), ``permissions``,
        ``served_calls``, ``session_cwd``. The bearer token is never exposed."""
        with self._lock:
            s = self._acp
        return s.info() if s is not None else None

    def close(self) -> None:
        """End the ACP agent's process group, stop the served toolkit and remove any
        temp session dir. Idempotent; a no-op for the one-shot CLI."""
        with self._lock:
            s, self._acp, self._closed = self._acp, None, True
        if s is not None:
            s.close()

    async def aclose(self) -> None:
        await asyncio.to_thread(self.close)

    def __enter__(self) -> "CliClient":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    async def __aenter__(self) -> "CliClient":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()

    # The served toolkit's tools run on the caller's loop (MCP stdio sessions are
    # bound to it); remember it on every entry point.
    async def run(self, *args: Any, **kwargs: Any):  # type: ignore[override]
        self._loop = asyncio.get_running_loop()
        return await super().run(*args, **kwargs)

    async def ask(self, *args: Any, **kwargs: Any):  # type: ignore[override]
        self._loop = asyncio.get_running_loop()
        return await super().ask(*args, **kwargs)

    async def stream(self, *args: Any, **kwargs: Any):  # type: ignore[override]
        self._loop = asyncio.get_running_loop()
        async for ev in super().stream(*args, **kwargs):
            yield ev

    # -- the in-process model ------------------------------------------------ #

    def _generate(self, request: dict[str, Any]) -> dict[str, Any]:
        s = self._session()
        if s is not None:
            return s.generate(request)
        return parse_cli_reply(self._invoke(render_cli_prompt(request.get("messages"), request.get("tools"))))

    def _session(self) -> Any:
        """The live ACP session, opened on first use; ``None`` = one-shot CLI."""
        from .acp import ACPLaunchError
        from .cli_acp import _open_session

        with self._lock:
            if self._closed:
                raise CliError("cli: client is closed")
            if self._chosen is CliTransports.CLI:
                return None
            if self._acp is not None:
                return self._acp
            try:
                s = _open_session(self)
            except CliError as e:
                if self._chosen is CliTransports.AUTO and isinstance(e.__cause__, ACPLaunchError) and self._has_cli:
                    self._chosen = CliTransports.CLI
                    self._fallback = str(e)
                    return None
                raise
            self._acp, self._chosen = s, CliTransports.ACP
            return s

    def _argv(self, prompt: str) -> tuple[list[str], str, list[str]]:
        """The argv for one invocation, the out path ("" if none) and the temp files
        created (the caller removes them on every path)."""
        model = self._model_opt
        tmp: list[str] = []
        state = {"file": "", "out": ""}

        def temp(content: Optional[str]) -> str:
            fd, name = tempfile.mkstemp(prefix="toolnexus-cli-")
            tmp.append(name)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                if content is not None:
                    f.write(content)
            if content is None:
                os.remove(name)  # the CLI creates it; absence => "no output file"
            return name

        def sub(m: "re.Match[str]") -> str:
            key = m.group(1)
            if key == "prompt":
                return prompt
            if key == "model":
                return model
            if key == "file":
                if not state["file"]:
                    state["file"] = temp(prompt)
                return state["file"]
            if key == "out":
                if not state["out"]:
                    state["out"] = temp(None)
                return state["out"]
            return m.group(0)

        argv = [self._command]
        try:
            for e in self._elems:
                if e.group and not model:
                    continue
                argv.extend(_PLACEHOLDER_RE.sub(sub, p) for p in e.parts)
        except OSError as e:
            for f in tmp:
                _remove(f)
            raise CliError(f"cli: {e}") from e
        return argv, state["out"], tmp

    def _invoke(self, prompt: str) -> str:
        argv, out_path, tmp = self._argv(prompt)
        try:
            return self._run_child(argv, out_path, prompt)
        finally:
            for f in tmp:
                _remove(f)

    def _run_child(self, argv: list[str], out_path: str, prompt: str) -> str:
        bin_path = shutil.which(argv[0])
        if bin_path is None:
            raise CliError(f"cli: command not found: {argv[0]}")
        env = {**os.environ, **_expand_env(self._env)} if self._env else None
        try:
            proc = subprocess.Popen(
                [bin_path, *argv[1:]],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=self._cwd or None,
                env=env,
                start_new_session=os.name == "posix",
            )
        except OSError:
            raise CliError(f"cli: command not found: {argv[0]}") from None
        data = prompt.encode("utf-8") if self._style.prompt == "stdin" else b""
        try:
            stdout, stderr = proc.communicate(data, timeout=self._timeout_ms / 1000.0)
        except subprocess.TimeoutExpired:
            _kill_group(proc)
            proc.communicate()
            raise CliError(f"cli: timed out after {self._timeout_ms}ms") from None
        if proc.returncode != 0:
            msg = stderr.decode("utf-8", errors="replace").strip()
            code = proc.returncode
            if not msg:
                raise CliError(f"cli: exit {code}")
            raise CliError(f"cli: exit {code}: {_truncate(msg)}")
        text = stdout.decode("utf-8", errors="replace")
        if self._style.answer == "out":
            try:
                with open(out_path, encoding="utf-8") as f:
                    text = f.read()
            except OSError:
                raise CliError("cli: no output file") from None
        if not text.strip():
            raise CliError("cli: empty reply")
        return text


def _remove(path: str) -> None:
    try:
        os.remove(path)
    except OSError:
        pass


def create_cli_client(
    *,
    command: Optional[str] = None,
    style: Optional[CliStyles] = None,
    style_def: Optional[CliStyleDef] = None,
    model: Optional[str] = None,
    transport: CliTransports = CliTransports.AUTO,
    toolkit: Any = None,
    acp_command: Optional[list[str]] = None,
    bridge_command: Optional[list[str]] = None,
    args: Optional[list[Any]] = None,
    prompt: Optional[str] = None,
    answer: Optional[str] = None,
    env: Optional[dict[str, str]] = None,
    call_timeout_ms: Optional[int] = None,
    cwd: Optional[str] = None,
    on_acp_update: Optional[Callable[[dict[str, Any]], None]] = None,
    **client_options: Any,
) -> CliClient:
    """A client whose model is an installed agent CLI (SPEC §8C).

    ``command`` (path or name) is REQUIRED; ``style`` is a :class:`CliStyles` member
    (never a string), or pass a user ``style_def`` (:func:`load_cli_style`). ``model``
    is passed to the CLI unchanged (``None`` => the CLI's own default). Every other
    keyword is an ordinary client option (``system_prompt``, ``hooks``, ``max_turns``,
    ``on_metric``, ``wait_for`` …). Everything that can be validated is validated
    here; the transport is decided on the first model call.
    """
    for reserved in ("base_url", "api_key", "http_transport"):
        if reserved in client_options:
            raise TypeError(f"toolnexus: create_cli_client does not take `{reserved}`")
    if not command:
        raise CliError("cli: Command is required (path or name of the CLI binary)")
    if style is not None and not isinstance(style, CliStyles):
        raise TypeError("toolnexus: style must be a CliStyles member, never a string")
    if not isinstance(transport, CliTransports):
        raise TypeError("toolnexus: transport must be a CliTransports member, never a string")
    if style_def is not None:
        d = style_def
    elif style is CliStyles.CUSTOM:
        d = CliStyleDef(name="custom", args=args, prompt=prompt or "", answer=answer or "")
    else:
        found = cli_style_def(style) if style is not None else None
        if found is None:
            raise CliError("cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)")
        d = found
    opts = {
        "command": command,
        "model": model,
        "toolkit": toolkit,
        "acp_command": acp_command,
        "bridge_command": bridge_command,
        "env": env,
        "call_timeout_ms": call_timeout_ms,
        "cwd": cwd,
        "transport": transport,
        "on_acp_update": on_acp_update,
    }
    return CliClient(d, opts, dict(client_options))

