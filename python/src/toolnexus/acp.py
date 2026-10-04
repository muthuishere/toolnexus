"""ACP (Agent Client Protocol) as a model source — ADR 0031, issue #96.

ACP is to *agents* what MCP is to *tools*: JSON-RPC 2.0, one object per line, over a
child process's stdin/stdout — the exact framing MCP local stdio already uses
(``mcp_source.py``). `devin acp`, Gemini CLI and Zed's agents speak it. This module
ships ACP as the *smallest* possible framing per ADR 0031: a ``generate(request) ->
dict`` function with the shape ``create_in_process_client(generate=...)`` already
wants (``client.py`` around ``InProcessTransport``/``create_in_process_client``), so
the tool-calling loop, skills, MCP tools, adapters and sub-agents are untouched. ACP
is a model source, not a new tool source and not a new client.

The agent is a real **tool-calling model** (``openspec/changes/add-acp-tool-calling``,
SPEC §8 "ACP model source"): each prompt carries the OpenAI-shaped request — messages,
including earlier tool calls and their results, plus the tool schemas — and the
agent's JSON reply is parsed back into content or tool calls (``_parse_reply``), which
the loop executes through the toolkit.

The wire protocol is mechanical (``initialize`` -> ``session/new`` ->
``session/prompt``, demultiplexed by JSON-RPC id because ``session/update``
notifications interleave with responses). Two behaviors the spike at ``spikes/acp/``
proved matter:

* **Only ``agent_message_chunk`` forms the reply.** ``agent_thought_chunk`` and tool
  narration must be dropped, or they corrupt structured output the host expects to
  parse.
* **A permission request is answered, never awaited.** ``session/request_permission``
  left unanswered hangs a turn forever, even in an agent's "bypass" mode — so this
  client answers inline, from the same thread that demultiplexes the wire: the first
  option whose ``kind`` starts with ``reject`` by default (toolnexus executes the
  tools, the agent must not), or the first ``allow``-kind option with
  ``ACPOptions.allow_agent_tools``.

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
import subprocess
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

__all__ = [
    "ACPOptions",
    "ACPClient",
    "ACPError",
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

    ``allow_agent_tools`` lets the agent run tools of its OWN: a
    ``session/request_permission`` is then answered with the first ``allow``-kind
    option. Default ``False`` — the first ``reject``-kind option — because toolnexus
    is the tool executor (SPEC §8, add-acp-tool-calling): an agent that runs ``bash``
    itself has escaped every hook and any builtin execution seam (ADR 0033).
    """

    command: str
    args: list[str] = field(default_factory=list)
    cwd: Optional[str] = None
    env: Optional[dict[str, str]] = None
    protocol_version: int = 1
    permission_timeout: float = 5.0
    mode: Optional[str] = None
    allow_agent_tools: bool = False


# Byte-pinned by SPEC §8 "ACP model source" — identical in all seven ports.
_PREAMBLE = (
    "You are the language model behind a tool-calling client. The client executes tools; you never do.\n"
    "Do not run commands, read or edit files, or use any tool of your own.\n"
    'The REQUEST below is the complete conversation in OpenAI chat-completions format: "messages" holds every message so far, including earlier tool calls and their results; "tools" lists the only tools you may call.\n'
    "Reply with exactly one JSON object and nothing else: no prose, no markdown fences.\n"
    'To give the final answer: {"content": "<answer>"}\n'
    'To call tools: {"tool_calls": [{"id": "<unique id>", "type": "function", "function": {"name": "<tool name>", "arguments": "<JSON-encoded arguments>"}}]}\n'
    'Never both. Use tool results already in "messages" instead of calling the same tool again.\n'
)


def _compact_json(value: Any) -> str:
    # Compact, and never escaping non-ASCII; Python's json never HTML-escapes.
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def _content_to_text(content: Any) -> str:
    """Render a message's ``content`` for the supersedes line: a string as is; a
    list of parts -> the ``text`` of its ``type: "text"`` parts joined by one space;
    anything else -> ``""``."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(
            p["text"] for p in content
            if isinstance(p, dict) and p.get("type") == "text" and isinstance(p.get("text"), str)
        )
    return ""


def _render_prompt(request: dict[str, Any]) -> str:
    """``PREAMBLE + "\\nREQUEST:\\n" + JSON + "\\n\\n" + marker + " " + latest user text``.

    The FULL request goes every turn (ADR 0031: an ACP session is stateful, and a
    delta would make the client a shadow copy of conversation state), and the
    supersedes marker keeps a stateful agent off an earlier near-duplicate in its own
    history. This is deliberately the library's job, not the caller's.
    """
    messages = request.get("messages") or []
    tools = request.get("tools") or []
    # A dict literal keeps insertion order, so the JSON leads with "messages".
    payload = _compact_json({"messages": messages, "tools": tools})

    latest = None
    for message in reversed(messages):
        if isinstance(message, dict) and message.get("role") == "user":
            latest = _content_to_text(message.get("content"))
            break
    if latest is None:
        last = messages[-1] if messages else None
        latest = _content_to_text(last.get("content")) if isinstance(last, dict) else ""
    return f"{_PREAMBLE}\nREQUEST:\n{payload}\n\n{_SUPERSEDES_MARKER}: {latest}"


def _parse_object(s: str) -> Optional[dict[str, Any]]:
    try:
        value = json.loads(s)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def _parse_reply(text: str) -> dict[str, Any]:
    """Turn the agent's reply text into one assistant message, by the algorithm SPEC
    §8 pins: strip fences, parse (or the first-``{``..last-``}`` slice), unwrap
    ``choices[0].message`` / ``message``, then ``tool_calls`` -> ``{"tool_calls": ...}``,
    ``content`` -> ``{"content": ...}``, anything else -> the original text untouched
    (it is not an envelope — most often structured output the host asked for).
    """
    s = text.strip()
    if s.startswith("```"):
        nl = s.find("\n")
        s = s[nl + 1:].strip() if nl >= 0 else ""
        if s.endswith("```"):
            s = s[:-3]
        s = s.strip()

    obj = _parse_object(s)
    if obj is None:
        i, j = s.find("{"), s.rfind("}")
        if i >= 0 and j > i:
            obj = _parse_object(s[i:j + 1])
    if obj is None:
        return {"content": text}

    choices = obj.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0]
        if isinstance(first, dict) and isinstance(first.get("message"), dict):
            obj = first["message"]
    elif isinstance(obj.get("message"), dict):
        obj = obj["message"]

    raw_calls = obj.get("tool_calls")
    if isinstance(raw_calls, list):
        calls: list[dict[str, Any]] = []
        for el in raw_calls:
            if not isinstance(el, dict):
                continue
            fn = el["function"] if isinstance(el.get("function"), dict) else el
            name = fn.get("name")
            if not isinstance(name, str) or not name:
                continue
            args = fn.get("arguments")
            # A string passes through as pre-encoded; anything else is structured and
            # encoded by the in-process layer.
            call: dict[str, Any] = {"name": name, "arguments": {} if args is None else args}
            if isinstance(el.get("id"), str) and el["id"]:
                call["id"] = el["id"]
            calls.append(call)
        if calls:
            return {"tool_calls": calls}

    if "content" in obj:
        content = obj["content"]
        if isinstance(content, str):
            return {"content": content}
        return {"content": "" if content is None else _compact_json(content)}
    return {"content": text}


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
        self._session_id: Optional[str] = None
        self._closed = False

        self._accum: list[str] = []

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

    def _call(self, method: str, params: dict[str, Any], timeout: float = _HANDSHAKE_TIMEOUT) -> dict[str, Any]:
        req_id = self._next_id()
        reply_q: "queue.Queue[dict[str, Any]]" = queue.Queue(maxsize=1)
        with self._state_lock:
            self._pending[req_id] = reply_q
        self._write({"jsonrpc": "2.0", "id": req_id, "method": method, "params": params})
        try:
            msg = reply_q.get(timeout=timeout)
        except queue.Empty:
            with self._state_lock:
                self._pending.pop(req_id, None)
            raise ACPError(f"acp: {method} timed out after {timeout}s") from None
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
        with self._state_lock:
            pending = list(self._pending.items())
            self._pending.clear()
        eof_error = {"error": {"code": -1, "message": "acp: child process closed the connection"}}
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
            if q is not None:
                try:
                    q.put_nowait(msg)
                except queue.Full:  # pragma: no cover - maxsize=1, always empty here
                    pass
            return

        if method == "session/update":
            self._handle_update(msg.get("params") or {})
            return

        if method == "session/request_permission" and has_id:
            self._answer_permission(msg)
            return

        # Any other server-initiated request/notification is outside this client's
        # scope (e.g. fs/read_text_file — this client declares fs capabilities as
        # false, so a conformant agent won't send one). Reply with an empty result
        # to any unknown *request* so a strict peer doesn't itself hang on us.
        if has_id:
            self._write({"jsonrpc": "2.0", "id": msg["id"], "result": {}})

    def _handle_update(self, params: dict[str, Any]) -> None:
        update = params.get("update") or {}
        if update.get("sessionUpdate") != "agent_message_chunk":
            # agent_thought_chunk and tool_call/tool_call_update narration are
            # dropped here, on purpose — mixing them into the reply is exactly the
            # corruption ADR 0031 gate item 3 proves against structured output.
            return
        content = update.get("content") or {}
        text = content.get("text")
        if text:
            with self._state_lock:
                self._accum.append(str(text))

    def _answer_permission(self, request: dict[str, Any]) -> None:
        # The first reject-kind option by default (the client executes tools, the
        # agent must not), the first allow-kind one with allow_agent_tools; no
        # matching option -> cancelled.
        want = "allow" if self._opts.allow_agent_tools else "reject"
        params = request.get("params") or {}
        options = params.get("options") or []
        chosen: Optional[str] = None
        for option in options:
            if not isinstance(option, dict):
                continue
            kind = option.get("kind") or ""
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
        self._call(
            "initialize",
            {
                "protocolVersion": self._opts.protocol_version,
                "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}},
            },
        )
        cwd = self._opts.cwd or os.getcwd()
        if not os.path.isabs(cwd):
            cwd = os.path.abspath(cwd)
        result = self._call("session/new", {"cwd": cwd, "mcpServers": []})
        self._session_id = result.get("sessionId")
        if self._opts.mode:
            self._call("session/set_mode", {"sessionId": self._session_id, "modeId": self._opts.mode})

    # -- public API --------------------------------------------------------- #

    def generate(self, request: dict[str, Any]) -> dict[str, Any]:
        """``Callable[[dict], dict]`` — the exact shape ``create_in_process_client``
        wants. Sends the assembled OpenAI-shaped request (messages + tools) as exactly
        one ``session/prompt`` on the warm session, accumulates only
        ``agent_message_chunk`` text, and blocks (synchronously) until the reader
        thread delivers the reply or ``permission_timeout`` elapses. The reply is
        parsed into one assistant message: ``{"tool_calls": [...]}`` or
        ``{"content": ...}`` (SPEC §8 "ACP model source").
        """
        with self._turn_lock:
            prompt_text = _render_prompt(request)
            with self._state_lock:
                self._accum = []

            req_id = self._next_id()
            reply_q: "queue.Queue[dict[str, Any]]" = queue.Queue(maxsize=1)
            with self._state_lock:
                self._pending[req_id] = reply_q

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

            error = msg.get("error")
            if error:
                raise ACPError(f"acp error {error.get('code')}: {error.get('message')}")

            with self._state_lock:
                text = "".join(self._accum)
            return _parse_reply(text)

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
        try:
            self._proc.terminate()
        except OSError:
            pass
        try:
            self._proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                self._proc.kill()
                self._proc.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):  # pragma: no cover - best effort
                pass

        self._drain_pending_on_eof()


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

    proc = subprocess.Popen(
        [opts.command, *opts.args],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        cwd=cwd,
        env=env,
        text=True,
        bufsize=1,
    )

    client = ACPClient(opts, proc)
    try:
        client._connect()
    except Exception:
        client.close()
        raise
    return client
