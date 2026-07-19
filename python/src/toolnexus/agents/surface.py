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
import os
from typing import Any, Callable, Optional

from ..client import ConversationStore, HttpTransport
from ..types import Tool, ToolResult
from .runtime import AgentDef, AgentRuntime, Budget, Clock, Handle, InboxItem, TaskResult

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
    """Read a bootstrap file with the 2 MB cap; ``None`` if absent. A larger file
    is truncated with an explicit notice — the on-disk file is left untouched."""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        body = fh.read()
    if len(body) > MAX_BOOTSTRAP_FILE_BYTES:
        return body[:MAX_BOOTSTRAP_FILE_BYTES] + _TRUNCATION_NOTICE
    return body


def compose_soul(dir: str) -> tuple[str, list[str]]:  # noqa: A002
    """Compose the discovered :data:`BOOTSTRAP_ORDER` files into one soul string
    (the frozen snapshot). Returns ``(soul, found)`` — ``found`` is the ordered
    list of present filenames. Each file becomes a ``## <file>`` section; absent
    files are skipped; oversized files are truncated with a notice."""
    sections: list[str] = []
    found: list[str] = []
    for f in BOOTSTRAP_ORDER:
        body = _read_capped(os.path.join(dir, f))
        if body is not None:
            sections.append(f"## {f}\n\n{body.strip()}")
            found.append(f)
    return "\n\n".join(sections), found


def memory_tool(dir: str) -> Tool:  # noqa: A002
    """The ``memory`` builtin (§7E) — one tool, three actions over ``MEMORY.md``
    (the agent's own notes) and ``USER.md`` (its model of the user). All actions
    write to DISK; the live session prompt is intentionally NOT mutated (the
    frozen-snapshot rule — the next session re-reads). A ``replace``/``remove``
    whose substring is absent is a loud ``is_error``."""

    def _file_for(target: str) -> str:
        return os.path.join(dir, "USER.md" if target == "user" else "MEMORY.md")

    async def execute(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
        a = args or {}
        action = a.get("action")
        target = str(a.get("target") or "self")
        text = str(a.get("text", ""))
        file = _file_for(target)
        cur = ""
        if os.path.exists(file):
            with open(file, encoding="utf-8") as fh:
                cur = fh.read()
        if action == "add":
            nxt = (cur.rstrip() + f"\n- {text}\n").lstrip()
        elif action in ("replace", "remove"):
            if text not in cur:
                return ToolResult(output=f"not found: {text}", is_error=True)
            nxt = cur.replace(text, str(a.get("with", "")) if action == "replace" else "")
        else:
            return ToolResult(output=f"unknown action: {action}", is_error=True)
        os.makedirs(os.path.dirname(file) or ".", exist_ok=True)
        with open(file, "w", encoding="utf-8") as fh:
            fh.write(nxt)
        return ToolResult(output=f"ok ({action} → {os.path.basename(file)}); loads next session", is_error=False)

    return Tool(
        name="memory",
        description=(
            "Persist durable memory. action=add appends an entry; replace swaps an existing "
            "substring; remove deletes one. target=self (MEMORY.md, default) or user (USER.md). "
            "Writes persist to disk and load at the START of your next session — they do NOT "
            "change your current context."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["add", "replace", "remove"]},
                "target": {"type": "string", "enum": ["self", "user"]},
                "text": {"type": "string", "description": "For add: the entry. For replace/remove: the existing text."},
                "with": {"type": "string", "description": "For replace: the replacement text."},
            },
            "required": ["action", "text"],
        },
        source="native",
        execute=execute,
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
    ) -> AgentRuntime:
        return AgentRuntime(
            registry=self.registry(),
            **_runtime_options(
                {
                    "transport": transport,
                    "llm": llm,
                    "inbox_cap": inbox_cap,
                    "max_concurrent_turns": max_concurrent_turns,
                    "shutdown_ms": shutdown_ms,
                    "store": store,
                    "clock": clock,
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
            return TaskResult(text=h.error, is_error=True, status="error", runtime=rt)
        rt.wake(h, prompt)
        r = await rt.wait(h)
        if r.status != "pending":
            await rt.close(rt.root)
        r.runtime = rt
        return r

    def as_tool(self, **rt_opts: Any) -> Tool:
        """The bridge (§7D axiom): an Agent IS a Tool — drop it into the classic
        API's ``extra_tools``. Executes the agent's own loop in isolation and
        returns only its final text + ``{agent, turns, totalTokens}`` metadata."""
        agent_self = self

        async def execute(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
            r = await agent_self.run(str((args or {}).get("prompt")), **rt_opts)
            return ToolResult(
                output=r.text,
                is_error=r.is_error,
                metadata={"agent": agent_self.name, "turns": r.turns, "totalTokens": r.total_tokens},
            )

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
    **opts: Any,
) -> Agent:
    """Level 2: the directory IS the agent (§7E). Discovers the
    :data:`BOOTSTRAP_ORDER` files (all optional), injects them as named
    ``## <file>`` sections at session start (frozen snapshot; oversized files are
    truncated at the 2 MB cap), and — unless ``memory=False`` — wires a
    :func:`memory_tool` over the same directory so the persona can edit its own
    durable memory. Extra ``tools`` are appended to the toolkit view."""
    soul, _found = compose_soul(dir)
    uses = dict(opts.pop("uses", None) or {})
    extra: list[Tool] = list(tools or []) + list(uses.get("tools") or [])
    if memory:
        extra.append(memory_tool(dir))
    uses["tools"] = extra
    return Agent(
        name or os.path.basename(dir.rstrip("/")),
        does=does or f"persona agent from {dir}",
        soul=soul,
        uses=uses,
        **opts,
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
    """Level 2: start a long-lived persona. The heartbeat posts a timer tick onto
    the agent's own inbox (unsolicited rail — ticks coalesce) and wakes it; a
    :data:`HEARTBEAT_OK` answer stays silent. All timing runs on the runtime's
    injectable clock, so fixtures drive the heartbeat virtually. Beats serialize
    (each awaits its turn) — wake/coalesce semantics are unchanged under a slow
    turn, only tick pacing differs.
    """
    rt = a._runtime(**rt_opts)
    spawned = rt.spawn(rt.root, a.name)
    if not isinstance(spawned, Handle):
        raise RuntimeError(spawned.error)
    handle = spawned
    beat_task: Optional[asyncio.Task] = None
    if every_ms:

        async def beat() -> None:
            while True:
                await rt.clock.sleep(every_ms / 1000.0)
                rt.post(handle, InboxItem(from_="clock", channel="timer", text="tick"))
                if handle.state == "idle":
                    r = await rt.run_turn(handle, HEARTBEAT_PROMPT)
                    if not r.is_error and HEARTBEAT_OK not in r.text and on_report is not None:
                        on_report(r.text)

        beat_task = asyncio.ensure_future(beat())

    async def stop() -> None:
        if beat_task is not None:
            beat_task.cancel()
            try:
                await beat_task
            except asyncio.CancelledError:
                pass
        await rt.close(rt.root)

    return StartedAgent(handle, rt, stop)
