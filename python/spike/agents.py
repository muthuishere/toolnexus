"""SPIKE — Level-1/2 UX sugar over the substrate (see audit doc "The UX").

One new noun: agent(). Everything compiles to the six verbs in runtime.py.

    explore = agent("explore", does="...", uses={"tools": [lookup]})
    coder   = agent("coder", does="...", soul_file="./AGENTS.md", team=[explore])
    r = await coder.run({"transport": mock}, "fix the bug")

    mia = agent_from_dir("~/mia")            # the directory IS the agent
    started = start_agent(mia, {...})        # Level 3 lives underneath

Mirrors js/spike/agents.ts.
"""
from __future__ import annotations

import asyncio
import os
from typing import Any, Callable, Optional

from toolnexus import Tool, ToolResult

from runtime import AgentDef, AgentRuntime, Budget, Handle, InboxItem, TaskResult

# Discovery order (all optional, openclaw order), injected as `## <file>` sections
# at session start (cache doctrine: on_spawn reads memory in).
BOOTSTRAP_ORDER = ["AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"]
MAX_BOOTSTRAP_FILE_BYTES = 2 * 1024 * 1024

# The HEARTBEAT_OK sentinel — a silent wake produces no outbound message.
HEARTBEAT_OK = "HEARTBEAT_OK"


class Agent:
    def __init__(
        self,
        name: str,
        *,
        does: str,  # what the delegating model sees — the routing description
        uses: Optional[dict[str, Any]] = None,  # {"tools": [...]} — the toolkit VIEW (scoping)
        soul: Optional[str] = None,  # identity: inline text
        soul_file: Optional[str] = None,  # path form of soul — read at build time
        team: Optional[list["Agent"]] = None,  # task-tool targets: listing agents IS the wiring
        budget: Optional[Budget] = None,
        model: Optional[str] = None,  # "inherit" = runtime default
        wait_for: Optional[Callable[..., Any]] = None,  # §10 interpreter authority for the subtree
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
        """Collect this agent + its whole team graph into a runtime registry."""
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
            # team scoping: task targets = ONLY this agent's team (not the whole registry)
            task_targets=[a.name for a in self.team],
        )
        for t in self.team:
            t.registry(acc)
        return acc

    async def run(self, rt_opts: dict[str, Any], prompt: str) -> TaskResult:
        """Level 1: one-shot — build a runtime, run to completion, tear down."""
        rt = AgentRuntime(registry=self.registry(), **rt_opts)
        h = rt.spawn(rt.root, self.name)
        if not isinstance(h, Handle):
            return TaskResult(text=h.error, is_error=True, status="done")
        rt.wake(h, prompt)
        r = await rt.wait(h)
        if r.status != "pending":
            await rt.close(rt.root)
        r.rt = rt  # type: ignore[attr-defined] — exposed for durable resume
        return r

    def as_tool(self, rt_opts: dict[str, Any]) -> Tool:
        """Bridge: an Agent IS a Tool — drop it into the OLD API's extra_tools."""
        agent_self = self

        async def execute(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
            r = await agent_self.run(rt_opts, str((args or {}).get("prompt")))
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
    """The one new noun."""
    return Agent(name, **spec)


def agent_from_dir(dir: str, *, name: Optional[str] = None, does: Optional[str] = None, **opts: Any) -> Agent:  # noqa: A002
    """Level 2: the directory IS the agent. Discovers the BOOTSTRAP_ORDER files
    (all optional) and injects them as named `## <file>` sections at session start."""
    sections: list[str] = []
    for f in BOOTSTRAP_ORDER:
        p = os.path.join(dir, f)
        if os.path.exists(p):
            size = os.stat(p).st_size
            with open(p, encoding="utf-8") as fh:
                body = fh.read()
            if size > MAX_BOOTSTRAP_FILE_BYTES:
                body = body[:MAX_BOOTSTRAP_FILE_BYTES] + "\n[truncated: file exceeds bootstrap cap]"
            sections.append(f"## {f}\n\n{body.strip()}")
    return Agent(
        name or os.path.basename(dir.rstrip("/")),
        does=does or f"persona agent from {dir}",
        soul="\n\n".join(sections),
        **opts,
    )


class StartedAgent:
    def __init__(self, handle: Handle, rt: AgentRuntime, stop: Callable[[], Any]) -> None:
        self.handle = handle
        self.rt = rt
        self._stop = stop

    async def stop(self) -> None:
        await self._stop()


def start_agent(
    a: Agent,
    rt_opts: dict[str, Any],
    *,
    every_ms: Optional[float] = None,
    on_report: Optional[Callable[[str], None]] = None,
) -> StartedAgent:
    """Level 2: start a long-lived persona — the heartbeat posts a tick to its own
    inbox (unsolicited rail; ticks coalesce) and wakes it; HEARTBEAT_OK results stay
    silent. NOTE (parity): JS uses setInterval fire-and-forget; here the heartbeat
    loop awaits each turn, so beats serialize instead of overlapping — the wake/
    coalesce semantics are identical, only the tick pacing under a slow turn differs.
    """
    rt = AgentRuntime(registry=a.registry(), **rt_opts)
    spawned = rt.spawn(rt.root, a.name)
    if not isinstance(spawned, Handle):
        raise RuntimeError(spawned.error)
    handle = spawned
    beat_task: Optional[asyncio.Task] = None
    if every_ms:

        async def beat() -> None:
            while True:
                await asyncio.sleep(every_ms / 1000.0)
                rt.post(handle, InboxItem(from_="clock", channel="timer", text="tick"))
                if handle.state == "idle":
                    r = await rt.run_turn(
                        handle,
                        "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.",
                    )
                    if not r.is_error and HEARTBEAT_OK not in r.text and on_report is not None:
                        on_report(r.text)

        beat_task = asyncio.create_task(beat())

    async def stop() -> None:
        if beat_task is not None:
            beat_task.cancel()
            try:
                await beat_task
            except asyncio.CancelledError:
                pass
        await rt.close(rt.root)

    return StartedAgent(handle, rt, stop)
