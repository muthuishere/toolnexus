"""Level-1/2 agent surface — SPEC.md §7D ("Level-1 surface").

One new noun: :func:`agent`. Everything compiles down to the six runtime verbs.

    explore = agent("explore", does="read-only research", uses={"tools": [lookup]})
    coder = agent("coder", does="implements changes", soul_file="./AGENTS.md", team=[explore])
    r = await coder.run("fix the bug", transport=..., llm={...})

The axiom's other direction: ``coder.as_tool(...)`` yields a uniform
:class:`~toolnexus.types.Tool` for the classic API's ``extra_tools`` — an Agent IS
a Tool. Level 2 (:func:`agent_from_dir` + :func:`start_agent`) makes a directory
the agent: discovered bootstrap files become the soul, and an optional heartbeat
posts timer ticks onto the unsolicited rail (silent when the model answers
``HEARTBEAT_OK``).

Naming note: the package namespace disambiguates from the outbound-A2A
``toolnexus.agent`` (a remote Agent-Card descriptor) — import this surface as
``from toolnexus.agents import agent``.
"""
from __future__ import annotations

import asyncio
import dataclasses
import os
import re
import secrets
import stat
import threading
from typing import Any, Callable, Optional

from ..client import ConversationStore, HttpTransport
from ..transcript_search import transcript_search_tool
from ..types import Tool, ToolResult
from .loop import Loop, guarded_hooks
from .runtime import (
    TASK_STATUS_ERROR,
    AgentDef,
    AgentRuntime,
    Budget,
    Clock,
    Handle,
    InboxItem,
    TaskResult,
)

# Bootstrap discovery order for agent_from_dir (all files optional). Each found
# file is injected as a named ``## <file>`` section at session start (§7E).
BOOTSTRAP_ORDER = ["AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"]
MAX_BOOTSTRAP_FILE_BYTES = 2 * 1024 * 1024
_TRUNCATION_NOTICE = "\n[truncated: exceeds 2 MB bootstrap cap]"

# The heartbeat sentinel — a wake that answers this produces no outbound report.
HEARTBEAT_OK = "HEARTBEAT_OK"

HEARTBEAT_PROMPT = (
    "Heartbeat. Read your HEARTBEAT.md section and follow it. "
    "If nothing needs attention, reply HEARTBEAT_OK."
)


def _read_capped(path: str) -> Optional[str]:
    """Read a bootstrap file with the 2 MB cap; ``None`` if absent. The cap is
    measured in **bytes** (not code points): a file whose UTF-8 encoding exceeds
    :data:`MAX_BOOTSTRAP_FILE_BYTES` is truncated at that byte boundary and given an
    explicit notice — the on-disk file is left untouched."""
    if not os.path.exists(path):
        return None
    with open(path, "rb") as fh:
        raw = fh.read()
    if len(raw) > MAX_BOOTSTRAP_FILE_BYTES:
        # Truncate on the byte boundary; drop a partial trailing multibyte char.
        return raw[:MAX_BOOTSTRAP_FILE_BYTES].decode("utf-8", "ignore") + _TRUNCATION_NOTICE
    return raw.decode("utf-8")


# The preset index cap (§7E "Memory hierarchy"): 200 lines / 25 KB.
MEMORY_INDEX_CAP_PRESET = {"max_lines": 200, "max_bytes": 25600}


def _resolve_index_cap(cap: Any) -> Optional[tuple[int, int]]:
    """``True`` ⇒ the preset; a dict's missing field ⇒ the preset's (keys
    ``max_lines``/``max_bytes``, camelCase also accepted); a value < 1 or non-integer
    raises."""
    if cap is None or cap is False:
        return None
    v: dict[str, Any] = {} if cap is True else dict(cap)
    max_lines = v.get("max_lines", v.get("maxLines", MEMORY_INDEX_CAP_PRESET["max_lines"]))
    max_bytes = v.get("max_bytes", v.get("maxBytes", MEMORY_INDEX_CAP_PRESET["max_bytes"]))
    for x in (max_lines, max_bytes):
        if isinstance(x, bool) or not isinstance(x, int) or x < 1:
            raise ValueError("memoryIndexCap: maxLines and maxBytes must be integers >= 1")
    return max_lines, max_bytes


def memory_index_notice(max_lines: int, max_bytes: int, notes: bool) -> str:
    """The exact notice appended to a capped ``MEMORY.md`` (§7E)."""
    tail = '; move detail into notes (memory target "note:<slug>").' if notes else " (keep entries short)."
    return f"\n\n[MEMORY.md index truncated at {max_lines} lines / {max_bytes} bytes. Keep MEMORY.md a short index{tail}]"


def _utf8_len(s: str) -> int:
    return len(s.encode("utf-8"))


def apply_index_cap(content: str, max_lines: int, max_bytes: int, notes: bool = False) -> str:
    """Apply the §7E index cap to ``MEMORY.md``'s (already 2 MB-capped) content: whole
    lines; within both bounds ⇒ unchanged; else the first ``max_lines`` lines, trailing
    lines dropped until the "\\n"-joined UTF-8 length fits (a lone over-long line is cut
    at a code-point boundary), plus :func:`memory_index_notice`."""
    lines = content.split("\n")
    if content.endswith("\n"):
        lines.pop()
    if len(lines) <= max_lines and _utf8_len("\n".join(lines)) <= max_bytes:
        return content
    kept = lines[:max_lines]
    while len(kept) > 1 and _utf8_len("\n".join(kept)) > max_bytes:
        kept.pop()
    text = "\n".join(kept)
    if _utf8_len(text) > max_bytes:
        # cut at the last whole code point whose UTF-8 end is <= max_bytes
        text = text.encode("utf-8")[:max_bytes].decode("utf-8", "ignore")
    return text + memory_index_notice(max_lines, max_bytes, notes)


def compose_soul(  # noqa: A002
    dir: str, *, memory_index_cap: Any = None, notes: bool = False
) -> tuple[str, list[str]]:
    """Compose the discovered :data:`BOOTSTRAP_ORDER` files into one soul string
    (the frozen snapshot). Returns ``(soul, found)`` — ``found`` is the ordered
    list of present filenames. Each file becomes a ``## <file>`` section; absent
    files are skipped; oversized files are truncated with a notice.
    ``memory_index_cap`` (opt-in) caps the injected ``MEMORY.md`` only (§7E)."""
    cap = _resolve_index_cap(memory_index_cap)
    sections: list[str] = []
    found: list[str] = []
    for f in BOOTSTRAP_ORDER:
        body = _read_capped(os.path.join(dir, f))
        if body is not None and cap is not None and f == "MEMORY.md":
            body = apply_index_cap(body, cap[0], cap[1], notes)
        if body is not None:
            sections.append(f"## {f}\n\n{body.strip()}")
            found.append(f)
    return "\n\n".join(sections), found


# A valid note slug (§7E): lowercase, no path separators, no "..".
NOTE_SLUG_RE = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")


def _valid_slug(slug: str) -> bool:
    return NOTE_SLUG_RE.fullmatch(slug) is not None and ".." not in slug


MEMORY_DESCRIPTION = (
    "Persist durable memory. action=add appends an entry; replace swaps an "
    "existing substring (with); remove deletes one. target=self (MEMORY.md, "
    "default) or user (USER.md). Writes persist to disk and load at the START of "
    "your NEXT session — they do NOT change your current context."
)

MEMORY_NOTES_DESCRIPTION = (
    "Persist durable memory. MEMORY.md is a short index loaded at the start of every "
    "session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never "
    "loaded automatically) and point to them from the index. Record what will change a "
    "future decision. action=add appends an entry; replace swaps the first occurrence of "
    "an existing substring (with); remove deletes it; read returns a file; list lists the "
    "notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: "
    "lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the "
    "START of your NEXT session — they do NOT change your current context."
)

_TEXT_DESC = "For add: the entry. For replace/remove: the existing text."
_WITH_DESC = "For replace: the replacement text."

# Per resolved-absolute-path write locks (§7E write safety): read-modify-write is
# serialized per file within this process, across every memory tool instance.
_PATH_LOCKS: dict[str, threading.Lock] = {}
_PATH_LOCKS_GUARD = threading.Lock()


def _lock_for(file: str) -> threading.Lock:
    key = os.path.realpath(os.path.abspath(file))
    with _PATH_LOCKS_GUARD:
        lock = _PATH_LOCKS.get(key)
        if lock is None:
            lock = _PATH_LOCKS[key] = threading.Lock()
        return lock


def _atomic_write(file: str, data: str) -> None:
    """Temp file in the same dir, flushed (fsync), renamed over the target; an
    existing file's permission bits are kept."""
    d = os.path.dirname(file) or "."
    os.makedirs(d, exist_ok=True)
    tmp = os.path.join(d, f".memory-{secrets.token_hex(8)}.tmp")
    # os.open with 0o666 (not mkstemp's 0o600) so a new file gets 0666 & ~umask.
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o666)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        if os.name == "posix" and os.path.exists(file):
            os.chmod(tmp, os.stat(file).st_mode & 0o777)
        os.replace(tmp, file)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def _rmw(file: str, action: Any, text: str, with_text: Any) -> Optional[ToolResult]:
    """Read-modify-write over the FULL file (never the capped view), under the
    per-path lock. ``replace``/``remove`` touch the FIRST occurrence only."""
    with _lock_for(file):
        cur = ""
        if os.path.exists(file):
            with open(file, encoding="utf-8", newline="") as fh:
                cur = fh.read()
        if action == "add":
            nxt = (cur.rstrip() + f"\n- {text}\n").lstrip()
        elif action in ("replace", "remove"):
            if text not in cur:
                return ToolResult(output=f"not found: {text}", is_error=True)
            repl = ("" if with_text is None else str(with_text)) if action == "replace" else ""
            nxt = cur.replace(text, repl, 1)
        else:
            return ToolResult(output=f"unknown action: {action}", is_error=True)
        _atomic_write(file, nxt)
        return None


def memory_tool(dir: str, *, notes: bool = False) -> Tool:  # noqa: A002
    """The ``memory`` builtin (§7E) — one tool, three actions over ``MEMORY.md``
    (the agent's own notes) and ``USER.md`` (its model of the user). All actions
    write to DISK; the live session prompt is intentionally NOT mutated (the
    frozen-snapshot rule — the next session re-reads). A ``replace``/``remove``
    whose substring is absent is a loud ``is_error``. ``notes=True`` adds
    ``note:<slug>`` targets plus ``read``/``list``."""
    if notes:
        return _notes_memory_tool(dir)

    def _file_for(target: str) -> str:
        return os.path.join(dir, "USER.md" if target == "user" else "MEMORY.md")

    async def execute(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
        a = args or {}
        action = a.get("action")
        file = _file_for(str(a.get("target") or "self"))
        err = _rmw(file, action, str(a.get("text", "")), a.get("with"))
        if err is not None:
            return err
        return ToolResult(output=f"ok ({action} → {os.path.basename(file)}); loads next session", is_error=False)

    return Tool(
        name="memory",
        description=MEMORY_DESCRIPTION,
        input_schema={
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["add", "replace", "remove"]},
                "target": {"type": "string", "enum": ["self", "user"], "description": "self=MEMORY.md (default), user=USER.md"},
                "text": {"type": "string", "description": _TEXT_DESC},
                "with": {"type": "string", "description": _WITH_DESC},
            },
            "required": ["action", "text"],
        },
        source="native",
        execute=execute,
    )


def _list_notes(notes_dir: str) -> str:
    try:
        names = os.listdir(notes_dir)
    except OSError:
        names = []
    rows: list[tuple[str, int]] = []
    for n in names:
        if not n.endswith(".md"):
            continue
        slug = n[:-3]
        if not _valid_slug(slug):
            continue
        try:
            st = os.lstat(os.path.join(notes_dir, n))  # never follow symlinks
        except OSError:
            continue
        if stat.S_ISREG(st.st_mode):
            rows.append((slug, st.st_size))
    rows.sort(key=lambda r: r[0])  # explicit sort: code-point order, never dir order
    return "\n".join(f"{s}\t{b}" for s, b in rows) if rows else "(no notes)"


def _notes_memory_tool(dir: str) -> Tool:  # noqa: A002
    notes_dir = os.path.join(dir, "memory")

    async def execute(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
        a = args or {}
        action = a.get("action")
        if action == "list":
            return ToolResult(output=_list_notes(notes_dir), is_error=False)
        target = "self" if a.get("target") is None else str(a.get("target"))
        if target in ("self", "user"):
            file = os.path.join(dir, "USER.md" if target == "user" else "MEMORY.md")
            label = os.path.basename(file)
        elif target.startswith("note:"):
            slug = target[5:]
            if not _valid_slug(slug):
                return ToolResult(output=f"invalid note slug: {slug}", is_error=True)
            file = os.path.join(notes_dir, f"{slug}.md")
            label = f"memory/{slug}.md"
        else:
            return ToolResult(output=f"unknown target: {target}", is_error=True)
        if action == "read":
            body = _read_capped(file)
            if body is None:
                return ToolResult(output=f"not found: {target}", is_error=True)
            return ToolResult(output=body, is_error=False)
        if action not in ("add", "replace", "remove"):
            return ToolResult(output=f"unknown action: {action}", is_error=True)
        text = a.get("text")
        if not isinstance(text, str):
            return ToolResult(output="text is required", is_error=True)
        err = _rmw(file, action, text, a.get("with"))
        if err is not None:
            return err
        if label.startswith("memory/"):
            return ToolResult(output=f"ok ({action} → {label})", is_error=False)
        return ToolResult(output=f"ok ({action} → {label}); loads next session", is_error=False)

    return Tool(
        name="memory",
        description=MEMORY_NOTES_DESCRIPTION,
        input_schema={
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["add", "replace", "remove", "read", "list"]},
                "target": {"type": "string", "description": "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md"},
                "text": {"type": "string", "description": _TEXT_DESC},
                "with": {"type": "string", "description": _WITH_DESC},
            },
            "required": ["action"],
        },
        source="native",
        execute=execute,
    )


# What a dream pass is FOR and the order it works in (§7E "Dreaming"). Byte-identical
# in every port; it becomes the dreamer's ``## HEARTBEAT.md`` section.
DREAM_PROMPT = "\n".join(
    [
        'You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target "note:<slug>") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened.',
        "Work in this order:",
        "1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix or remove a claim only when it is contradicted by the transcripts; a claim they do not mention stays (transcripts may be pruned).",
        "2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short.",
        "3. Merge: fold duplicate or conflicting notes into one.",
        "4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line.",
        '5. Log: if you changed anything, append exactly one entry with memory add, target "note:log", text starting "dream: " and naming every change and why.',
        "If nothing needs changing, write nothing and reply HEARTBEAT_OK.",
    ]
)


def _runtime_options(kw: dict[str, Any]) -> dict[str, Any]:
    """Filter the run/as_tool keyword surface down to AgentRuntime kwargs."""
    return {k: v for k, v in kw.items() if v is not None}


class Agent:
    """An agent definition at the UX level: identity × toolkit view × team.

    Spec fields (§7D): ``does`` (required routing description), ``uses`` (the
    toolkit view — ``{"tools": [...]}``), ``soul`` (inline) or ``soul_file``
    (path, read at registry-build time), ``team`` (task-tool targets — listing
    agents IS the wiring), ``budget``, ``model`` (default ``"inherit"``),
    optional ``wait_for`` / ``on_spawn`` / ``on_close``.
    """

    def __init__(
        self,
        name: str,
        *,
        does: str,
        uses: Optional[dict[str, Any]] = None,
        soul: Optional[str] = None,
        soul_file: Optional[str] = None,
        team: Optional[list["Agent"]] = None,
        budget: Optional[Budget] = None,
        model: Optional[str] = None,
        wait_for: Optional[Callable[..., Any]] = None,
        on_spawn: Optional[Callable[..., Any]] = None,
        on_close: Optional[Callable[..., Any]] = None,
        hooks: Optional[Any] = None,
        on_metric: Optional[Callable[[dict[str, Any]], None]] = None,
        guardrails: Optional[list[Any]] = None,
        completion: Optional[Any] = None,
    ) -> None:
        self.name = name
        self.does = does
        self.uses = uses or {}
        self.soul = soul
        self.soul_file = soul_file
        self.team = team or []
        self.budget = budget
        self.model = model
        self.wait_for = wait_for
        self.on_spawn = on_spawn
        self.on_close = on_close
        # §8 seams for THIS agent (§7D) — set ⇒ replaces the runtime-wide value.
        self.hooks = hooks
        self.on_metric = on_metric
        # POLICY checks on tool calls — "may it?", never "is it right?". Compiled
        # into one before_tool with FIRST-DENY-WINS. Empty ⇒ byte-identical.
        self.guardrails = guardrails or []
        # The gate that stops this agent claiming `done` before its work verifies.
        # It travels WITH the agent, so delegation via `task` inherits it — which a
        # host-side retry loop cannot do. None ⇒ byte-identical.
        self.completion = completion
        # fromDir(..., dream=...) attaches (dreamer, every_ms); read by start_agent.
        self._dream: Optional[tuple["Agent", float]] = None

    def loop(self, options: dict[str, Any], toolkit: Any) -> Loop:
        """Open a LIVE EXECUTION of this agent. Takes client OPTIONS rather than a
        built client, because a per-call ``model`` override must be able to change
        the model — which is fixed when a client is constructed."""
        return Loop(self, options, toolkit)

    def registry(self, acc: Optional[dict[str, AgentDef]] = None) -> dict[str, AgentDef]:
        """The runtime registry = the TRANSITIVE CLOSURE of this agent's team graph
        (§7D team scoping) — unreachable agents are not present."""
        acc = acc if acc is not None else {}
        if self.name in acc:
            return acc
        soul = self.soul or ""
        if self.soul_file:
            with open(self.soul_file, encoding="utf-8") as f:
                soul = f.read()
        acc[self.name] = AgentDef(
            name=self.name,
            description=self.does,
            system_prompt=soul,
            model=self.model or "inherit",
            tools=self.uses.get("tools"),
            budget=self.budget,
            wait_for=self.wait_for,
            on_spawn=self.on_spawn,
            on_close=self.on_close,
            # Guardrails are compiled HERE, at the registry boundary, so a delegated
            # child is governed by them too — not only a directly-driven one.
            hooks=guarded_hooks(self.guardrails, self.hooks),
            on_metric=self.on_metric,
            completion=self.completion,
            # task targets = ONLY this agent's team, never the whole registry.
            task_targets=[a.name for a in self.team],
        )
        for t in self.team:
            t.registry(acc)
        return acc

    def _runtime(
        self,
        *,
        transport: Optional[HttpTransport] = None,
        llm: Optional[dict[str, Any]] = None,
        inbox_cap: Optional[int] = None,
        max_concurrent_turns: Optional[int] = None,
        shutdown_ms: Optional[float] = None,
        store: Optional[ConversationStore] = None,
        clock: Optional[Clock] = None,
        hooks: Optional[Any] = None,
        on_metric: Optional[Callable[[dict[str, Any]], None]] = None,
        wait_for: Optional[Callable[..., Any]] = None,
        _registry: Optional[dict[str, AgentDef]] = None,
    ) -> AgentRuntime:
        return AgentRuntime(
            registry=_registry if _registry is not None else self.registry(),
            **_runtime_options(
                {
                    "wait_for": wait_for,
                    "transport": transport,
                    "llm": llm,
                    "inbox_cap": inbox_cap,
                    "max_concurrent_turns": max_concurrent_turns,
                    "shutdown_ms": shutdown_ms,
                    "store": store,
                    "clock": clock,
                    "hooks": hooks,
                    "on_metric": on_metric,
                }
            ),
        )

    async def run(self, prompt: str, **rt_opts: Any) -> TaskResult:
        """Level 1: one-shot — build a runtime, run to completion, tear down.

        A durable suspension leaves the tree parked: the returned result carries
        ``status="pending"`` plus ``result.runtime`` for
        ``await result.runtime.resume(answer)``.
        """
        rt = self._runtime(**rt_opts)
        h = rt.spawn(rt.root, self.name)
        if not isinstance(h, Handle):
            return TaskResult(text=h.error, is_error=True, status=TASK_STATUS_ERROR, runtime=rt)
        rt.wake(h, prompt)
        r = await rt.wait(h)
        if r.status != "pending":
            await rt.close(rt.root)
        r.runtime = rt
        return r

    async def _run_cancellable(self, prompt: str, signal: Any, rt_opts: dict[str, Any]) -> TaskResult:
        """:meth:`run` that interrupts the child when ``signal`` (an asyncio.Event
        cancel token) fires. No signal ⇒ :meth:`run`."""
        if signal is None or not hasattr(signal, "wait"):
            return await self.run(prompt, **rt_opts)
        if signal.is_set():
            return TaskResult(text="run cancelled", is_error=True, status="cancelled")
        rt = self._runtime(**rt_opts)
        h = rt.spawn(rt.root, self.name)
        if not isinstance(h, Handle):
            return TaskResult(text=h.error, is_error=True, status=TASK_STATUS_ERROR, runtime=rt)
        rt.wake(h, prompt)
        waiter = asyncio.ensure_future(rt.wait(h))
        watcher = asyncio.ensure_future(signal.wait())
        try:
            await asyncio.wait({waiter, watcher}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            watcher.cancel()
        if signal.is_set():
            await rt.close(rt.root, force=True, reason="cancelled")
            r = await waiter
            return TaskResult(
                text="run cancelled", is_error=True, status="cancelled", turns=r.turns,
                total_tokens=r.total_tokens, runtime=rt,
            )
        r = waiter.result()
        if r.status != "pending":
            await rt.close(rt.root)
        r.runtime = rt
        return r

    def as_tool(self, **rt_opts: Any) -> Tool:
        """The bridge (§7D axiom): an Agent IS a Tool — drop it into the classic
        API's ``extra_tools``. Executes the agent's own loop in isolation and
        returns only its final text + ``{agent, turns, totalTokens}`` metadata.
        The calling turn's cancel token (``ctx.signal``) interrupts the child."""
        return self.as_tool_with(lambda _ctx: rt_opts)

    def as_tool_with(self, per_call: Callable[[Any], dict[str, Any]]) -> Tool:
        """:meth:`as_tool` with the runtime options built per call from the calling
        tool's :class:`ToolContext`, so the child runs under the parent turn's gates
        (§4C): pass ``hooks`` (the same ``before_tool`` permission gate — it also sees
        the child's tool calls), ``wait_for`` (the same ask bridge) and ``on_metric``.
        An agent's own ``hooks`` replaces ``hooks`` for that agent, so leave it unset
        when the gate must hold. When the calling turn is cancelled the child is
        interrupted and the tool returns a ``cancelled: ...`` error result."""
        agent_self = self

        async def execute(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
            opts = per_call(ctx) or {}
            signal = getattr(ctx, "signal", None) if ctx is not None else None
            r = await agent_self._run_cancellable(str((args or {}).get("prompt")), signal, opts)
            meta = {"agent": agent_self.name, "turns": r.turns, "totalTokens": r.total_tokens}
            if r.status == "cancelled":
                return ToolResult(output=f"cancelled: {r.text}", is_error=True, metadata=meta)
            return ToolResult(output=r.text, is_error=r.is_error, metadata=meta)

        return Tool(
            name=self.name,
            description=self.does,
            input_schema={"type": "object", "properties": {"prompt": {"type": "string"}}, "required": ["prompt"]},
            source="native",
            execute=execute,
        )


def agent(name: str, **spec: Any) -> Agent:
    """The one new noun (§7D Level-1 surface)."""
    return Agent(name, **spec)


def agent_from_dir(  # noqa: A002
    dir: str,
    *,
    name: Optional[str] = None,
    does: Optional[str] = None,
    memory: bool = True,
    tools: Optional[list[Tool]] = None,
    memory_index_cap: Any = None,
    notes: bool = False,
    dream: Optional[dict[str, Any]] = None,
    **opts: Any,
) -> Agent:
    """Level 2: the directory IS the agent (§7E). Discovers the
    :data:`BOOTSTRAP_ORDER` files (all optional), injects them as named
    ``## <file>`` sections at session start (frozen snapshot; oversized files are
    truncated at the 2 MB cap), and — unless ``memory=False`` — wires a
    :func:`memory_tool` over the same directory so the persona can edit its own
    durable memory. Extra ``tools`` are appended to the toolkit view.

    Memory hierarchy (all opt-in): ``memory_index_cap`` caps the injected
    ``MEMORY.md``; ``notes=True`` turns on the notes tier of the memory tool;
    ``dream={"every_ms": ..., "store": ..., "instructions": ...}`` makes
    :func:`start_agent` run a :func:`dreamer` over this dir on the same runtime."""
    soul, _found = compose_soul(dir, memory_index_cap=memory_index_cap, notes=notes)
    uses = dict(opts.pop("uses", None) or {})
    extra: list[Tool] = list(tools or []) + list(uses.get("tools") or [])
    if memory:
        extra.append(memory_tool(dir, notes=notes))
    uses["tools"] = extra
    agent_name = name or os.path.basename(dir.rstrip("/"))
    persona = Agent(
        agent_name,
        does=does or f"persona agent from {dir}",
        soul=soul,
        uses=uses,
        **opts,
    )
    if dream is not None:
        every_ms = dream.get("every_ms", dream.get("everyMs"))
        if isinstance(every_ms, bool) or not isinstance(every_ms, (int, float)) or not every_ms > 0 or every_ms == float("inf"):
            raise ValueError("fromDir: dream.everyMs must be > 0")
        d = dreamer(dir, store=dream.get("store"), instructions=dream.get("instructions"), name=f"{agent_name}-dreamer")
        persona._dream = (d, every_ms)
    return persona


def _dream_guard(mem: Tool) -> Tool:
    """The dream guard (§7E "Dreaming"): the dreamer's ``memory`` tool refuses every action
    on ``USER.md`` and ``replace``/``remove`` on ``note:log`` before anything is read or
    written, so a dream never touches the user model and its audit trail is append-only."""
    inner = mem.execute

    async def execute(args: dict[str, Any], ctx: Any = None) -> ToolResult:
        args = args or {}
        if args.get("target") == "user":
            return ToolResult(output="dreamer: USER.md is off-limits", is_error=True)
        if args.get("target") == "note:log" and args.get("action") in ("replace", "remove"):
            return ToolResult(output="dreamer: memory/log.md is append-only", is_error=True)
        return await inner(args, ctx)

    return dataclasses.replace(mem, execute=execute)


def dreamer(  # noqa: A002
    dir: str,
    *,
    store: Any = None,
    instructions: Optional[str] = None,
    name: Optional[str] = None,
    model: Optional[str] = None,
) -> Agent:
    """The built-in dreamer preset (§7E "Dreaming"): a plain :class:`Agent` whose tools
    are EXACTLY ``memory`` (notes on, over ``dir``) and ``transcript_search`` (over
    ``store``). Its soul is one ``## HEARTBEAT.md`` section holding :data:`DREAM_PROMPT`,
    then ``DREAM.md`` (trimmed, if non-empty), then ``instructions`` (trimmed, if
    non-empty), joined by blank lines. Schedule it with :func:`start_agent` or run one
    pass with ``run(HEARTBEAT_PROMPT, ...)``."""
    if store is None:
        raise ValueError("dreamer: store is required")
    search = transcript_search_tool(store)
    parts = [DREAM_PROMPT]
    dream_md = (_read_capped(os.path.join(dir, "DREAM.md")) or "").strip()
    if dream_md:
        parts.append(dream_md)
    extra = (instructions or "").strip()
    if extra:
        parts.append(extra)
    return Agent(
        name or f"{os.path.basename(dir.rstrip('/'))}-dreamer",
        does=f"dreams over the memory in {dir}",
        soul="## HEARTBEAT.md\n\n" + "\n\n".join(parts),
        model=model or "inherit",
        uses={"tools": [_dream_guard(memory_tool(dir, notes=True)), search]},
    )


class StartedAgent:
    """A long-lived persona started by :func:`start_agent`."""

    def __init__(self, handle: Handle, rt: AgentRuntime, stop: Callable[[], Any]) -> None:
        self.handle = handle
        self.rt = rt
        self._stop = stop

    async def stop(self) -> None:
        await self._stop()


def start_agent(
    a: Agent,
    *,
    every_ms: Optional[float] = None,
    on_report: Optional[Callable[[str], None]] = None,
    **rt_opts: Any,
) -> StartedAgent:
    """Level 2: start a long-lived persona. Each interval the heartbeat ``post``s a
    timer tick onto the agent's own inbox (the unsolicited rail) and, **only when
    idle**, ``wake``s it; a :data:`HEARTBEAT_OK` answer stays silent. The interval
    is FIRE-AND-FORGET — it never blocks on the turn (the ``wait`` runs detached), so
    ticks that arrive while a beat's turn is still running pile up in the inbox and
    the next idle wake drains them as ONE coalesced turn (the runtime's timer-tick
    dedupe). All timing runs on the runtime's injectable clock, so fixtures drive the
    heartbeat on a virtual clock.
    """
    dream = a._dream
    if dream is not None:
        own = a.registry()
        if dream[0].name in own:
            raise RuntimeError(f"startAgent: dreamer name collision: {dream[0].name} is already registered")
        reg = dream[0].registry()
        reg.update(own)
        rt = a._runtime(_registry=reg, **rt_opts)
    else:
        rt = a._runtime(**rt_opts)
    spawned = rt.spawn(rt.root, a.name)
    if not isinstance(spawned, Handle):
        raise RuntimeError(spawned.error)
    handle = spawned
    beat_tasks: list[asyncio.Task] = []
    collectors: set[asyncio.Task] = set()

    def heartbeat(h: Handle, interval_ms: float, report: Optional[Callable[[str], None]]) -> asyncio.Task:
        async def collect() -> None:
            r = await rt.wait(h)
            if not r.is_error and HEARTBEAT_OK not in r.text and report is not None:
                report(r.text)

        def spawn_collect() -> None:
            t = asyncio.ensure_future(collect())
            collectors.add(t)
            t.add_done_callback(collectors.discard)

        async def beat() -> None:
            while True:
                await rt.clock.sleep(interval_ms / 1000.0)
                # unsolicited rail: the tick lands in the inbox (coalesces); an idle
                # wake drains the whole backlog as one turn. A running/suspended turn
                # is left alone — its inbox keeps accumulating ticks.
                rt.post(h, InboxItem(from_="clock", channel="timer", text="tick"))
                if h.state == "idle":
                    woke = rt.wake(h, HEARTBEAT_PROMPT)
                    if woke.ok:
                        spawn_collect()  # detached — the loop keeps posting ticks

        return asyncio.ensure_future(beat())

    if every_ms:
        beat_tasks.append(heartbeat(handle, every_ms, on_report))
    if dream is not None:
        # The dreamer runs on the SAME runtime on its own interval. Its replies are
        # housekeeping — they never reach on_report; what it did is in memory/log.md.
        dh = rt.spawn(rt.root, dream[0].name)
        if not isinstance(dh, Handle):
            # never leak the persona heartbeat or the runtime on a failed dreamer spawn
            for bt in beat_tasks:
                bt.cancel()
            asyncio.ensure_future(rt.close(rt.root))
            raise RuntimeError(dh.error)
        beat_tasks.append(heartbeat(dh, dream[1], None))

    async def stop() -> None:
        for bt in beat_tasks:
            bt.cancel()
            try:
                await bt
            except asyncio.CancelledError:
                pass
        for t in list(collectors):
            t.cancel()
        await rt.close(rt.root)

    return StartedAgent(handle, rt, stop)
