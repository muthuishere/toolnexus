"""Shared helpers for the §7D agent-runtime tests.

The LLM is a scripted in-process :class:`HttpTransport` (the same injection seam
the resilience tests use), keyed by ``body["model"]`` — the Python twin of the
scripts pinned in ``examples/subagent-*/fixture.json`` (``mockLLM.scripts``).
Zero network, zero cost. A :class:`VirtualClock` drives every runtime timer for
deterministic timing fixtures.
"""
from __future__ import annotations

import asyncio
import heapq
import json
import re
import threading
import time
from dataclasses import replace
from pathlib import Path
from typing import Any, Optional

from toolnexus import Tool, ToolResult, pending
from toolnexus.agents import AgentDef, Budget

EXAMPLES = Path(__file__).resolve().parents[2] / "examples"

# The §7D transition vocabulary used by fixture ``transitions`` expectations.
_ARROWS = (
    "idle→running",
    "running→suspended",
    "suspended→running",
    "running→idle",
    "suspended→idle",
    "idle→closed",
    "running→closed",
    "suspended→closed",
)


def load_fixture(name: str) -> dict[str, Any]:
    """Load a shared ``examples/<name>/fixture.json``."""
    return json.loads((EXAMPLES / name / "fixture.json").read_text(encoding="utf-8"))


def transitions(trace: list[str], handle_id: str) -> list[str]:
    """Extract one handle's state-transition sequence from the runtime trace."""
    out: list[str] = []
    prefix = f"{handle_id}: "
    for line in trace:
        if not line.startswith(prefix):
            continue
        rest = line[len(prefix):]
        for a in _ARROWS:
            if rest.startswith(a):
                out.append(a)
                break
        else:
            if rest.startswith("→closed"):
                out.append("→closed")
    return out


def _arrow_eq(got: str, expected: str) -> bool:
    """SPEC §7D trace parity: entries may use the full ``state→state`` form or the
    ``→state`` suffix form — conformance matches by suffix; the forms are equivalent."""
    return (
        got == expected
        or (expected.startswith("→") and got.endswith(expected))
        or (got.startswith("→") and expected.endswith(got))
    )


def transitions_match(trace: list[str], handle_id: str, expected: list[str]) -> tuple[bool, list[str]]:
    """Compare a handle's extracted transitions against a fixture's expected list
    with suffix-form equivalence. Returns (matched, got) for assert messages."""
    got = transitions(trace, handle_id)
    ok = len(got) == len(expected) and all(_arrow_eq(g, e) for g, e in zip(got, expected))
    return ok, got


def openai_body(message: dict[str, Any], tokens: Optional[dict[str, int]] = None) -> dict[str, Any]:
    return {
        "choices": [{"message": {"role": "assistant", **message}}],
        "usage": tokens or {"prompt_tokens": 30, "completion_tokens": 10, "total_tokens": 40},
    }


def tool_call(name: str, args: dict[str, Any], id: str) -> dict[str, Any]:  # noqa: A002
    return {
        "content": None,
        "tool_calls": [{"id": id, "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}],
    }


class MockTransport:
    """Scripted LLM keyed by model — mirrors ``mockLLM.scripts`` in the shared
    fixtures. The small sleep makes overlapping HTTP calls observable to the
    turn-gate counters; pass ``sleep=0`` where timing is irrelevant."""

    def __init__(self, sleep: float = 0.01, slow_gate: Optional[threading.Event] = None) -> None:
        self.sleep = sleep
        self.slow_gate = slow_gate if slow_gate is not None else threading.Event()

    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        if self.sleep:
            time.sleep(self.sleep)
        model = payload["model"]
        msgs: list[dict[str, Any]] = payload["messages"]
        tool_msgs = [m for m in msgs if m.get("role") == "tool"]
        sys_prompt = next((str(m.get("content") or "") for m in msgs if m.get("role") == "system"), "")

        if model == "m-coordinator":
            if not tool_msgs:
                return openai_body(
                    {
                        "content": None,
                        "tool_calls": [
                            {"id": "c1", "type": "function", "function": {"name": "task", "arguments": json.dumps({"agent": "explore", "prompt": "find A"})}},
                            {"id": "c2", "type": "function", "function": {"name": "task", "arguments": json.dumps({"agent": "explore", "prompt": "find B"})}},
                        ],
                    }
                )
            return openai_body({"content": "synthesis: " + " + ".join(str(m["content"]) for m in tool_msgs)})
        if model == "m-explore":
            if not tool_msgs:
                return openai_body(tool_call("lookup", {"q": "x"}, "e1"))
            return openai_body({"content": f'found:{tool_msgs[0]["content"]}'})
        if model == "m-explore-ux":  # the surface demos' explore flavor
            if not tool_msgs:
                return openai_body(tool_call("lookup", {"q": "bug"}, "e1"))
            return openai_body({"content": f'bug at line 42 ({tool_msgs[0]["content"]})'})
        if model == "m-approver-parent":
            if not tool_msgs:
                return openai_body(tool_call("task", {"agent": "asker", "prompt": "get the secret"}, "p1"))
            return openai_body({"content": f'parent-final: {tool_msgs[-1]["content"]}'})
        if model == "m-asker":
            approved = any("secret-token" in str(m["content"]) for m in tool_msgs)
            if not approved:
                return openai_body(tool_call("check_secret", {}, "a1"))
            return openai_body({"content": "asker-done: secret-token"})
        if model == "m-peer":
            last = str(msgs[-1].get("content") or "") if msgs else ""
            items = len(re.findall(r"^\d+\.", last, re.M))
            return openai_body({"content": f"processed {items} items"})
        if model == "m-loop":  # never finishes: always another tool call → maxTurns/incomplete
            return openai_body(tool_call("lookup", {"q": "again"}, f"l{len(tool_msgs)}"))
        if model == "m-slow":
            self.slow_gate.wait(timeout=10)
            return openai_body({"content": "slow-done"})
        if model == "m-coder":
            if not tool_msgs:
                return openai_body(tool_call("task", {"agent": "explore", "prompt": "find the bug"}, "t1"))
            soul = "loaded" if "You are the CODER" in sys_prompt else "missing"
            return openai_body({"content": f'fixed using: {tool_msgs[0]["content"]} [soul:{soul}]'})
        if model == "m-mia":
            last = str(msgs[-1].get("content") or "") if msgs else ""
            if "Heartbeat" in last:
                has_ticks = "channel=timer" in last
                if "water the plants" in sys_prompt and has_ticks:
                    return openai_body({"content": "Reminder: water the plants!"})
                return openai_body({"content": "HEARTBEAT_OK"})
            found = [f for f in ("SOUL.md", "USER.md", "MEMORY.md") if f"## {f}" in sys_prompt]
            return openai_body({"content": f'soul-sections:[{",".join(found)}]'})
        if model == "m-tool-lister":  # reports the tool schema it was offered
            names = ",".join(t["function"]["name"] for t in (payload.get("tools") or []))
            return openai_body({"content": f"tools:[{names}]"})
        if model == "m-old-api":
            if not tool_msgs:
                return openai_body(tool_call("explore", {"prompt": "scan the repo"}, "b1"))
            return openai_body({"content": f'old-api summary: {tool_msgs[0]["content"]}'})
        return openai_body({"content": "ok"})

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):  # noqa: A003
        raise NotImplementedError("mock is non-streaming")


# ---------------------------------------------------------------------------- #
# Scoped tools (as pinned by the fixtures' `tools` blocks)
# ---------------------------------------------------------------------------- #
async def _lookup_exec(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
    return ToolResult(output=f'data({(args or {}).get("q")})', is_error=False)


lookup = Tool(
    name="lookup",
    description="look something up",
    input_schema={"type": "object", "properties": {"q": {"type": "string"}}},
    source="native",
    execute=_lookup_exec,
)


async def _check_secret_exec(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
    if ctx is not None and getattr(ctx, "answer", None) is not None and ctx.answer.ok:
        return ToolResult(output="secret-token", is_error=False)
    return pending(kind="approval", prompt="approve secret access?")


check_secret = Tool(
    name="check_secret",
    description="needs human approval",
    input_schema={"type": "object", "properties": {}},
    source="native",
    execute=_check_secret_exec,
)


def registry(overrides: Optional[dict[str, dict[str, Any]]] = None) -> dict[str, AgentDef]:
    """The shared-fixture registry (fresh AgentDef instances per call)."""
    base = {
        "coordinator": AgentDef(
            name="coordinator", description="splits and delegates", system_prompt="coordinate",
            model="m-coordinator", task_targets=["explore"],
        ),
        "explore": AgentDef(
            name="explore", description="read-only research", system_prompt="explore",
            model="m-explore", tools=[lookup],
        ),
        "approverParent": AgentDef(
            name="approverParent", description="delegates, holds approval authority",
            system_prompt="", model="m-approver-parent", task_targets=["asker"],
        ),
        "asker": AgentDef(
            name="asker", description="needs approvals", system_prompt="", model="m-asker",
            tools=[check_secret],
        ),
        "peer": AgentDef(name="peer", description="team member", system_prompt="", model="m-peer"),
        "looper": AgentDef(
            name="looper", description="never finishes", system_prompt="", model="m-loop", tools=[lookup]
        ),
        "slow": AgentDef(name="slow", description="slow worker", system_prompt="", model="m-slow"),
    }
    for k, v in (overrides or {}).items():
        base[k] = replace(base[k], **v)
    return base


# ---------------------------------------------------------------------------- #
# Virtual clock (§7D injectable clock — fixtures run virtual)
# ---------------------------------------------------------------------------- #
class _Timer:
    __slots__ = ("when", "seq", "callback", "cancelled")

    def __init__(self, when: float, seq: int, callback) -> None:
        self.when = when
        self.seq = seq
        self.callback = callback
        self.cancelled = False

    def cancel(self) -> None:
        self.cancelled = True

    def __lt__(self, other: "_Timer") -> bool:
        return (self.when, self.seq) < (other.when, other.seq)


class VirtualClock:
    """Deterministic clock: time moves only via :meth:`advance`."""

    def __init__(self) -> None:
        self._t = 0.0
        self._seq = 0
        self._sched: list[_Timer] = []

    def now(self) -> float:
        return self._t

    def call_later(self, seconds: float, callback) -> _Timer:
        self._seq += 1
        timer = _Timer(self._t + seconds, self._seq, callback)
        heapq.heappush(self._sched, timer)
        return timer

    async def sleep(self, seconds: float) -> None:
        fut = asyncio.get_running_loop().create_future()
        self.call_later(seconds, lambda: fut.done() or fut.set_result(None))
        await fut

    async def advance(self, seconds: float, settle: float = 0.05) -> None:
        """Advance virtual time, firing due timers; ``settle`` yields real time so
        woken coroutines (whose LLM mock runs in a worker thread) can finish."""
        target = self._t + seconds
        while self._sched and self._sched[0].when <= target:
            timer = heapq.heappop(self._sched)
            self._t = max(self._t, timer.when)
            if not timer.cancelled:
                timer.callback()
            await asyncio.sleep(settle)
        self._t = target
        await asyncio.sleep(settle)


__all__ = [
    "Budget",
    "EXAMPLES",
    "MockTransport",
    "VirtualClock",
    "check_secret",
    "load_fixture",
    "lookup",
    "openai_body",
    "registry",
    "tool_call",
    "transitions",
]
