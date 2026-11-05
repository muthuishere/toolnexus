"""ACP (Agent Client Protocol) as a model source — ADR 0031, issue #96.

ACP is to *agents* what MCP is to *tools*: JSON-RPC 2.0, one object per line, over a
child process's stdin/stdout — the exact framing MCP local stdio already uses
(``mcp_source.py``). `devin acp`, Gemini CLI and Zed's agents speak it. This module
ships ACP as the *smallest* possible framing per ADR 0031: a ``generate(request) ->
dict`` function with the shape ``create_in_process_client(generate=...)`` already
wants (``client.py`` around ``InProcessTransport``/``create_in_process_client``), so
the tool-calling loop, skills, MCP tools, adapters and sub-agents are untouched. ACP
is a model source, not a new tool source and not a new client.

The hard part isn't the wire protocol (mechanical: ``initialize`` -> ``session/new``
-> ``session/prompt``, demultiplexed by JSON-RPC id because ``session/update``
notifications interleave with responses). It's two behaviors the spike at
``spikes/acp/`` proved matter:

* **Only ``agent_message_chunk`` forms the reply.** ``agent_thought_chunk`` and tool
  narration must be kept out of the reply, or they corrupt structured output the host
  expects to parse. ``generate_stream`` reports them to its emitter instead, as they
  arrive (``thought`` / ``tool_call`` / ``tool_result`` events).
* **A permission request is answered, never awaited.** ``session/request_permission``
  left unanswered hangs a turn forever, even in an agent's "bypass" mode — so this
  client answers inline, from the same thread that demultiplexes the wire, with the
  first option whose ``kind`` starts with ``allow``.

toolnexus assembles a *complete* request every turn (the full message array), but an
ACP session is *stateful* — it already holds the transcript. Sending the whole thing
every turn makes a session accumulate near-duplicate history, and ADR 0031's reporter
observed the agent answering a stale copy. The mitigation (kept as the *library's*
responsibility, not left to the caller) is an explicit marker naming the current turn
as superseding everything earlier — see ``_SUPERSEDES_MARKER`` and
``_render_prompt``.

Sync-bridge design (the reason this file doesn't need ``asyncio``, and why that's the
*right* choice here, not a workaround): ``InProcessTransport.post`` (``client.py``)
rejects an awaitable answer from ``generate`` — it MUST be a plain synchronous
callable, because it runs on the client's own worker thread. This client honors that
by construction rather than fighting it: ``load_acp`` starts one background
**reader thread** that owns the child's stdout loop end to end — parsing
newline-delimited JSON-RPC, resolving replies to *our* requests by id into a
``queue.Queue`` slot, routing ``session/update`` notifications into whichever turn is
currently active, and answering ``session/request_permission`` inline, all from that
one thread. The public ``ACPClient.generate`` stays a plain synchronous function: it
writes the ``session/prompt`` request, then blocks on ``queue.Queue.get(timeout=...)``
until the reader thread delivers the turn's reply. Nothing async ever crosses the
``generate`` boundary, so ``client.py``'s ``inspect.isawaitable`` guard never fires —
and the session is still warm and asynchronous *underneath*. This mirrors
``spikes/portability/SPIKE.md``'s own recommendation for Python specifically
("a background reader thread demultiplexing JSON-RPC notifications from responses
while `generate` blocks synchronously waiting for its turn's answer — doable with
`threading` + a `queue.Queue`, no new dependency") — the seam is widened nowhere;
``client.py`` is not touched.

Turns on one session are one conversation, so one ``threading.Lock`` held for the
duration of a single ``generate()`` call serialises them — idiomatic, and the
simplest thing that satisfies "one session = one conversation, concurrent prompts
would interleave into one transcript."
"""
from __future__ import annotations

import json
import os
import queue
import signal
import subprocess
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

__all__ = [
    "ACPOptions",
    "ACPClient",
    "ACPError",
    "ACPExitedError",
    "ACPLaunchError",
    "ACPPermissionTimeoutError",
    "load_acp",
]

# Marker convention shared with the Go spike (spikes/acp/fakeagent/main.go,
# spikes/acp/client/client.go) for cross-port consistency, not because the exact
# wording is part of any protocol contract — any explicit, unambiguous marker works.
_SUPERSEDES_MARKER = "SUPERSEDES-ALL-PRIOR"

# Bound for the handshake calls (initialize / session/new / session/set_mode). These
# are cheap control-plane calls even against a slow-starting real agent binary, so a
# generous fixed timeout (independent of ACPOptions.permission_timeout, which bounds
# a *turn*) is appropriate here.
_HANDSHAKE_TIMEOUT = 30.0


class ACPError(RuntimeError):
    """An ACP protocol-level failure — a JSON-RPC error reply, or a call that timed
    out waiting on the wire."""


class ACPLaunchError(ACPError):
    """The agent failed to start, or to answer ``initialize``."""


class ACPExitedError(ACPError):
    """The agent process ended (its stdout closed) while a request was waiting for
    its reply — raised at once instead of waiting out a timeout (SPEC §8C)."""

    def __init__(self, method: str) -> None:
        super().__init__(f"acp: agent exited before replying to {method}")
        self.method = method


class ACPPermissionTimeoutError(ACPError):
    """A turn did not receive its ``session/prompt`` reply within
    ``ACPOptions.permission_timeout``.

    This is the safety net for the trap ADR 0031 names explicitly: an unanswered
    ``session/request_permission`` hangs a turn forever. This client always answers
    permission requests itself (see module docstring), so in the normal path this
    timeout never fires — it exists only so a broken or non-conformant agent cannot
    hang a caller (or a test suite) indefinitely.
    """


@dataclass
class ACPOptions:
    """Configuration for one ACP session.

    ``cwd`` defaults to the current process's absolute working directory — real ACP
    agents (``devin acp`` confirmed live in the spike) reject ``session/new`` with
    ``-32602 Invalid params`` without an *absolute* ``cwd``, so this is resolved to an
    absolute path unconditionally, never left relative.
    """

    command: str
    args: list[str] = field(default_factory=list)
    cwd: Optional[str] = None
    env: Optional[dict[str, str]] = None
    protocol_version: int = 1
    permission_timeout: float = 5.0
    mode: Optional[str] = None
    # Bounds initialize / session/new / session/set_mode / model calls (seconds).
    # None => 30s.
    request_timeout: Optional[float] = None
    # session/new mcpServers (SPEC §8C): raw JSON objects sent VERBATIM and in
    # order — never validated, renamed or reshaped. None => [].
    mcp_servers: Optional[list[dict[str, Any]]] = None
    # Sent as session/new _meta (opaque); the key is absent when None.
    session_meta: Optional[dict[str, Any]] = None
    # Sent as initialize clientCapabilities._meta (vendor flags). Opaque.
    client_caps_meta: Optional[dict[str, Any]] = None
    # Passed through unchanged after session/new: session/set_config_option
    # {configId:"model"}, then session/set_model; both failing is a load error.
    model: Optional[str] = None
    # Receives the agent's stderr (anything with .write(str)); None => discarded.
    stderr: Any = None
    # Decides every session/request_permission: given the request params, return an
    # optionId; None/"" (or raising) answers with the first reject-kind option, else
    # "cancelled". None => the first allow-kind option (the historical default).
    on_permission: Optional[Callable[[dict[str, Any]], Optional[str]]] = None
    # Observes every raw session/update params (runs on the reader thread: must not
    # block).
    on_update: Optional[Callable[[dict[str, Any]], None]] = None


def _safe_emit(emit: Optional[Callable[[dict[str, Any]], None]], ev: dict[str, Any]) -> None:
    if emit is None:
        return
    try:
        emit(ev)
    except Exception:  # noqa: BLE001 — a consumer never breaks the wire
        pass


def _tool_content_text(content: Any) -> str:
    """The text parts of a tool_call_update's content array
    (``[{type: "content", content: {type: "text", text}}, ...]``), joined."""
    if not isinstance(content, list):
        return ""
    out: list[str] = []
    for part in content:
        inner = (part or {}).get("content") if isinstance(part, dict) else None
        if isinstance(inner, dict) and isinstance(inner.get("text"), str):
            out.append(inner["text"])
    return "".join(out)


def _content_to_text(content: Any) -> str:
    """Flatten a message's ``content`` — a plain string, or a §1B content-part list
    — to text. Non-text parts (image/audio/file) are silently skipped: ACP's prompt
    turn is text-only in this client, matching the ``session/prompt`` shape used
    throughout the spike and the ADR."""
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text")
                if text is None and item.get("type") == "text":
                    text = item.get("value")
                if text is not None:
                    parts.append(str(text))
            elif isinstance(item, str):
                parts.append(item)
        return "".join(parts)
    return str(content)


def _render_prompt(request: dict[str, Any]) -> str:
    """Render the full accumulated transcript as one prompt string, per ADR 0031's
    chosen default (full request every turn, not a delta), and append the
    supersedes marker naming the latest user turn.

    This is deliberately the library's job, not the caller's — ADR 0031 rejected
    leaving the marker to be bolted on by hand ("ship the supersedes marker as part
    of the default `Generate`'s prompt assembly (not left to the caller)",
    ``spikes/acp/SPIKE.md``).
    """
    messages = request.get("messages") or []
    lines: list[str] = []
    last_user_text = ""
    for message in messages:
        if not isinstance(message, dict):
            continue
        role = message.get("role", "")
        text = _content_to_text(message.get("content"))
        if text:
            lines.append(f"{role}: {text}")
            if role == "user":
                last_user_text = text
    if not last_user_text and lines:
        last_user_text = lines[-1]
    lines.append(f"{_SUPERSEDES_MARKER}: {last_user_text}")
    return "\n".join(lines)


class ACPClient:
    """One warm ACP session: one child process, one ``sessionId``, alive across many
    ``generate()`` turns. Construct via :func:`load_acp`, not directly.
    """

    def __init__(self, opts: ACPOptions, proc: "subprocess.Popen[str]") -> None:
        self._opts = opts
        self._proc = proc

        self._write_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._turn_lock = threading.Lock()  # serialises generate() calls (one session = one conversation)
        self._close_lock = threading.Lock()

        self._id_counter = 0
        self._pending: dict[str, "queue.Queue[dict[str, Any]]"] = {}
        self._pending_method: dict[str, str] = {}
        self._session_id: Optional[str] = None
        self._closed = False
        self._exited = False
        # The raw initialize result.
        self.initialize_result: dict[str, Any] = {}

        self._accum: list[str] = []
        # The active turn's stream emitter (None outside a streaming turn) and the
        # tool-call titles seen this turn, keyed by toolCallId.
        self._emit: Optional[Callable[[dict[str, Any]], None]] = None
        self._tool_names: dict[str, str] = {}

        self._reader = threading.Thread(target=self._read_loop, name="acp-reader", daemon=True)
        self._reader.start()

    # -- wire plumbing -------------------------------------------------- #

    def _next_id(self) -> str:
        with self._state_lock:
            self._id_counter += 1
            return f"c-{self._id_counter}"

    def _write(self, obj: dict[str, Any]) -> None:
        line = json.dumps(obj) + "\n"
        with self._write_lock:
            stdin = self._proc.stdin
            if stdin is None:
                raise ACPError("acp: child process has no stdin")
            try:
                stdin.write(line)
                stdin.flush()
            except (BrokenPipeError, ValueError, OSError) as exc:
                raise ACPError(f"acp: failed to write to child process: {exc}") from exc

    def _call(self, method: str, params: dict[str, Any], timeout: Optional[float] = None) -> dict[str, Any]:
        if timeout is None:
            timeout = self._opts.request_timeout or _HANDSHAKE_TIMEOUT
        req_id = self._next_id()
        reply_q: "queue.Queue[dict[str, Any]]" = queue.Queue(maxsize=1)
        with self._state_lock:
            if self._exited:
                raise ACPExitedError(method)
            self._pending[req_id] = reply_q
            self._pending_method[req_id] = method
        self._write({"jsonrpc": "2.0", "id": req_id, "method": method, "params": params})
        try:
            msg = reply_q.get(timeout=timeout)
        except queue.Empty:
            with self._state_lock:
                self._pending.pop(req_id, None)
            raise ACPError(f"acp: {method} timed out after {timeout}s") from None
        if msg.get("_exited"):
            raise ACPExitedError(method)
        error = msg.get("error")
        if error:
            raise ACPError(f"acp error {error.get('code')}: {error.get('message')}")
        return msg.get("result") or {}

    def _read_loop(self) -> None:
        stdout = self._proc.stdout
        if stdout is None:
            return
        try:
            for raw_line in stdout:
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    continue
                self._handle_message(msg)
        except (ValueError, OSError):
            pass
        finally:
            self._drain_pending_on_eof()

    def _drain_pending_on_eof(self) -> None:
        # Fail fast: every waiting request learns at once that the agent is gone.
        with self._state_lock:
            self._exited = True
            pending = list(self._pending.items())
            self._pending.clear()
            self._pending_method.clear()
        eof_error = {"_exited": True, "error": {"code": -1, "message": "acp: child process closed the connection"}}
        for _req_id, q in pending:
            try:
                q.put_nowait(eof_error)
            except queue.Full:  # pragma: no cover - maxsize=1, always empty here
                pass

    def _handle_message(self, msg: dict[str, Any]) -> None:
        method = msg.get("method")
        has_id = "id" in msg and msg.get("id") is not None

        if not method and has_id:
            # A reply to one of OUR requests (initialize / session/new /
            # session/set_mode / session/prompt).
            req_id = msg["id"]
            with self._state_lock:
                q = self._pending.pop(req_id, None)
                self._pending_method.pop(req_id, None)
            if q is not None:
                try:
                    q.put_nowait(msg)
                except queue.Full:  # pragma: no cover - maxsize=1, always empty here
                    pass
            return

        if method == "session/update":
            params = msg.get("params") or {}
            if self._opts.on_update is not None:
                try:
                    self._opts.on_update(params)
                except Exception:  # noqa: BLE001 — an observer never breaks the wire
                    pass
            self._handle_update(params)
            return

        if method == "session/request_permission" and has_id:
            self._answer_permission(msg)
            return

        # Any other agent->client REQUEST (fs/*, terminal/* — declared unsupported in
        # clientCapabilities) is answered with -32601, or the agent waits forever.
        if has_id and method:
            self._write(
                {
                    "jsonrpc": "2.0",
                    "id": msg["id"],
                    "error": {"code": -32601, "message": f"method not found: {method}"},
                }
            )

    def _handle_update(self, params: dict[str, Any]) -> None:
        # Runs on the reader thread, as each notification arrives — so a streaming
        # turn's emitter sees every event the moment it is read off the wire.
        update = params.get("update") or {}
        kind = update.get("sessionUpdate")
        with self._state_lock:
            emit = self._emit
        if kind == "agent_message_chunk":
            # Only agent_message_chunk forms the reply: agent_thought_chunk and
            # tool_call/tool_call_update narration never reach the content — mixing
            # them in is exactly the corruption ADR 0031 gate item 3 proves against
            # structured output. They are REPORTED to a streaming emitter instead.
            text = (update.get("content") or {}).get("text")
            if text:
                with self._state_lock:
                    self._accum.append(str(text))
                _safe_emit(emit, {"type": "text", "delta": str(text)})
        elif kind == "agent_thought_chunk":
            text = (update.get("content") or {}).get("text")
            if text:
                _safe_emit(emit, {"type": "thought", "delta": str(text), "via": "acp"})
        elif kind == "tool_call":
            call_id = update.get("toolCallId") or ""
            name = update.get("title") or update.get("kind") or ""
            with self._state_lock:
                self._tool_names[call_id] = name
            _safe_emit(
                emit,
                {"type": "tool_call", "id": call_id, "name": name, "args": update.get("rawInput"), "via": "acp"},
            )
        elif kind == "tool_call_update":
            status = update.get("status")
            if emit is None or status not in ("completed", "failed"):
                return
            call_id = update.get("toolCallId") or ""
            with self._state_lock:
                name = self._tool_names.get(call_id, "")
            if update.get("title"):
                name = update["title"]
            _safe_emit(
                emit,
                {
                    "type": "tool_result",
                    "id": call_id,
                    "name": name,
                    "output": _tool_content_text(update.get("content")),
                    "is_error": status == "failed",
                    "via": "acp",
                },
            )

    def _answer_permission(self, request: dict[str, Any]) -> None:
        params = request.get("params") or {}
        options = params.get("options") or []
        chosen: Optional[str] = None
        want = "allow"
        if self._opts.on_permission is not None:
            try:
                chosen = self._opts.on_permission(params) or None
            except Exception:  # noqa: BLE001 — a failing host decision rejects
                chosen = None
            want = "reject"
        if chosen is None:
            for option in options:
                kind = (option or {}).get("kind") or ""
                if isinstance(kind, str) and kind.startswith(want):
                    chosen = option.get("optionId")
                    break
        if chosen is not None:
            result = {"outcome": {"outcome": "selected", "optionId": chosen}}
        else:
            result = {"outcome": {"outcome": "cancelled"}}
        self._write({"jsonrpc": "2.0", "id": request["id"], "result": result})

    # -- handshake -------------------------------------------------------- #

    def _connect(self) -> None:
        caps: dict[str, Any] = {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}
        if self._opts.client_caps_meta is not None:
            caps["_meta"] = self._opts.client_caps_meta
        try:
            init = self._call("initialize", {"protocolVersion": self._opts.protocol_version, "clientCapabilities": caps})
        except ACPError as exc:
            raise ACPLaunchError(f"acp: initialize: {exc}") from exc
        self.initialize_result = init
        cwd = self._opts.cwd or os.getcwd()
        if not os.path.isabs(cwd):
            cwd = os.path.abspath(cwd)
        new_params: dict[str, Any] = {"cwd": cwd, "mcpServers": list(self._opts.mcp_servers or [])}
        if self._opts.session_meta is not None:
            new_params["_meta"] = self._opts.session_meta
        try:
            result = self._call("session/new", new_params)
        except ACPError as exc:
            raise ACPError(f"acp: session/new: {exc}") from exc
        self._session_id = result.get("sessionId")
        if self._opts.mode:
            self._call("session/set_mode", {"sessionId": self._session_id, "modeId": self._opts.mode})
        model = self._opts.model
        if model:
            # Passed through unchanged: the stable spelling first, then set_model.
            try:
                self._call(
                    "session/set_config_option",
                    {"sessionId": self._session_id, "configId": "model", "value": model},
                )
            except ACPError as e1:
                try:
                    self._call("session/set_model", {"sessionId": self._session_id, "modelId": model})
                except ACPError as e2:
                    raise ACPError(f"acp: set model {json.dumps(model)}: {e1}; {e2}") from e2

    # -- public API --------------------------------------------------------- #

    def generate(self, request: dict[str, Any]) -> dict[str, Any]:
        """``Callable[[dict], dict]`` — the exact shape ``create_in_process_client``
        wants. Sends exactly one ``session/prompt`` on the warm session, accumulates
        only ``agent_message_chunk`` text, and blocks (synchronously) until the
        reader thread delivers the reply or ``permission_timeout`` elapses.
        """
        return self.generate_stream(request, None)

    def generate_stream(
        self, request: dict[str, Any], emit: Optional[Callable[[dict[str, Any]], None]]
    ) -> dict[str, Any]:
        """:meth:`generate` that also reports the turn AS IT HAPPENS — the shape
        ``create_in_process_client(generate_stream=...)`` wants. ``emit`` is called
        from the reader thread as each ``session/update`` is read:
        ``agent_message_chunk`` -> ``{"type": "text", "delta"}``,
        ``agent_thought_chunk`` -> ``{"type": "thought", "delta", "via": "acp"}``
        (never part of the answer), ``tool_call`` -> ``{"type": "tool_call", "id",
        "name": title, "args": rawInput, "via": "acp"}`` and each completed/failed
        ``tool_call_update`` -> ``{"type": "tool_result", "id", "name", "output",
        "is_error", "via": "acp"}``. The return value is :meth:`generate`'s.
        """
        with self._turn_lock:
            try:
                return self._prompt_turn(request, emit)
            finally:
                with self._state_lock:
                    self._emit = None
                    self._tool_names = {}

    def _prompt_turn(
        self, request: dict[str, Any], emit: Optional[Callable[[dict[str, Any]], None]]
    ) -> dict[str, Any]:
        prompt_text = _render_prompt(request)
        with self._state_lock:
            self._accum = []
            self._tool_names = {}
            self._emit = emit

        req_id = self._next_id()
        reply_q: "queue.Queue[dict[str, Any]]" = queue.Queue(maxsize=1)
        with self._state_lock:
            if self._exited:
                raise ACPExitedError("session/prompt")
            self._pending[req_id] = reply_q
            self._pending_method[req_id] = "session/prompt"

        self._write(
            {
                "jsonrpc": "2.0",
                "id": req_id,
                "method": "session/prompt",
                "params": {
                    "sessionId": self._session_id,
                    "prompt": [{"type": "text", "text": prompt_text}],
                },
            }
        )

        try:
            msg = reply_q.get(timeout=self._opts.permission_timeout)
        except queue.Empty:
            with self._state_lock:
                self._pending.pop(req_id, None)
            raise ACPPermissionTimeoutError(
                "acp: turn hung waiting on a reply (possibly an unanswered "
                f"session/request_permission) after {self._opts.permission_timeout}s"
            ) from None

        if msg.get("_exited"):
            raise ACPExitedError("session/prompt")
        error = msg.get("error")
        if error:
            raise ACPError(f"acp error {error.get('code')}: {error.get('message')}")

        with self._state_lock:
            content = "".join(self._accum)
        return {"content": content}

    def is_alive(self) -> bool:
        """True while the child process is still running."""
        return self._proc.poll() is None

    def close(self) -> None:
        """Terminate the child process. Idempotent — safe to call more than once,
        and safe to call regardless of any in-flight turn's outcome (process
        lifetime is independent of any one turn's cancellation, per ADR 0031)."""
        with self._close_lock:
            if self._closed:
                return
            self._closed = True

        stdin = self._proc.stdin
        if stdin is not None:
            try:
                stdin.close()
            except (BrokenPipeError, ValueError, OSError):
                pass
        # The agent runs in its own process group, so an adapter's children (an
        # `npx …` launch is a tree) end with it.
        _signal_group(self._proc, signal.SIGTERM)
        try:
            self._proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass
        _signal_group(self._proc, getattr(signal, "SIGKILL", signal.SIGTERM))
        try:
            self._proc.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired):  # pragma: no cover - best effort
            pass

        self._drain_pending_on_eof()


def _signal_group(proc: "subprocess.Popen[Any]", sig: int) -> None:
    """Signal the child's whole process group (POSIX), else the child alone."""
    try:
        if os.name == "posix":
            os.killpg(proc.pid, sig)
        elif sig == signal.SIGTERM:
            proc.terminate()
        else:
            proc.kill()
    except (OSError, ProcessLookupError):
        pass


def _pump_stderr(stream: Any, sink: Any) -> None:
    try:
        for line in stream:
            try:
                sink.write(line)
            except Exception:  # noqa: BLE001
                pass
    except (ValueError, OSError):
        pass


def load_acp(opts: ACPOptions) -> ACPClient:
    """Spawn the ACP agent as a child process, complete the handshake
    (``initialize`` -> ``session/new`` -> optional ``session/set_mode``), and return
    a warm :class:`ACPClient` whose bound ``generate`` method is ready to hand
    straight to ``create_in_process_client(generate=client.generate, ...)``.
    """
    env = None
    if opts.env is not None:
        env = {**os.environ, **opts.env}

    cwd = opts.cwd or os.getcwd()
    if not os.path.isabs(cwd):
        cwd = os.path.abspath(cwd)

    try:
        proc = subprocess.Popen(
            [opts.command, *opts.args],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE if opts.stderr is not None else subprocess.DEVNULL,
            cwd=cwd,
            env=env,
            text=True,
            bufsize=1,
            start_new_session=os.name == "posix",
        )
    except OSError as exc:
        raise ACPLaunchError(f"acp: start {json.dumps(opts.command)}: {exc}") from exc
    if opts.stderr is not None and proc.stderr is not None:
        threading.Thread(target=_pump_stderr, args=(proc.stderr, opts.stderr), daemon=True).start()

    client = ACPClient(opts, proc)
    try:
        client._connect()
    except Exception:
        client.close()
        raise
    return client
