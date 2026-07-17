"""SPIKE demo — 9 acceptance scenarios for the agent-runtime substrate v2 (Python).

Zero network, zero cost: the LLM is a scripted in-process HttpTransport (same
injection seam the resilience tests exercise; keyed by body["model"], the same
scripts as the JS mockFetch). Each scenario asserts observable state transitions.

Run: cd python && python spike/demo.py
"""
from __future__ import annotations

import asyncio
import json
import re
import sys
import threading
import time
from typing import Any, Optional

from toolnexus import ToolResult, Tool, pending

from runtime import AgentDef, AgentRuntime, Budget, Handle, InboxItem, SpawnError

# ---------- scripted mock LLM (openai style, keyed by body["model"]) ----------
slow_gate = threading.Event()


def openai_body(message: dict[str, Any], tokens: Optional[dict[str, int]] = None) -> dict[str, Any]:
    return {
        "choices": [{"message": {"role": "assistant", **message}}],
        "usage": tokens or {"prompt_tokens": 30, "completion_tokens": 10, "total_tokens": 40},
    }


def call(name: str, args: dict[str, Any], id: str) -> dict[str, Any]:  # noqa: A002
    return {
        "content": None,
        "tool_calls": [{"id": id, "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}],
    }


class MockTransport:
    """Scripted LLM keyed by model — the Python twin of the JS mockFetch.

    The small sleep makes overlapping HTTP calls observable to the turn-gate
    counters (the JS mock gets this for free from promise scheduling).
    """

    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        time.sleep(0.01)
        model = payload["model"]
        msgs: list[dict[str, Any]] = payload["messages"]
        tool_msgs = [m for m in msgs if m.get("role") == "tool"]

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
                return openai_body(call("lookup", {"q": "x"}, "e1"))
            return openai_body({"content": f'found:{tool_msgs[0]["content"]}'})
        if model == "m-approver-parent":
            if not tool_msgs:
                return openai_body(call("task", {"agent": "asker", "prompt": "get the secret"}, "p1"))
            return openai_body({"content": f'parent-final: {tool_msgs[-1]["content"]}'})
        if model == "m-asker":
            approved = any("secret-token" in str(m["content"]) for m in tool_msgs)
            if not approved:
                return openai_body(call("check_secret", {}, "a1"))
            return openai_body({"content": "asker-done: secret-token"})
        if model == "m-peer":
            last = str(msgs[-1].get("content") or "") if msgs else ""
            items = len(re.findall(r"^\d+\.", last, re.M))
            return openai_body({"content": f"processed {items} items"})
        if model == "m-loop":  # never finishes: always another tool call → maxTurns/incomplete
            return openai_body(call("lookup", {"q": "again"}, f"l{len(tool_msgs)}"))
        if model == "m-slow":
            slow_gate.wait(timeout=10)
            return openai_body({"content": "slow-done"})
        return openai_body({"content": "ok"})

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):  # noqa: A003
        raise NotImplementedError("mock is non-streaming")


# ---------- scoped tools ----------
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


# ---------- registry ----------
def registry(overrides: Optional[dict[str, dict[str, Any]]] = None) -> dict[str, AgentDef]:
    base = {
        "coordinator": AgentDef(name="coordinator", description="splits and delegates", system_prompt="coordinate", model="m-coordinator"),
        "explore": AgentDef(name="explore", description="read-only research", system_prompt="explore", model="m-explore", tools=[lookup]),
        "approverParent": AgentDef(name="approverParent", description="delegates, holds approval authority", system_prompt="", model="m-approver-parent"),
        "asker": AgentDef(name="asker", description="needs approvals", system_prompt="", model="m-asker", tools=[check_secret]),
        "peer": AgentDef(name="peer", description="team member", system_prompt="", model="m-peer"),
        "looper": AgentDef(name="looper", description="never finishes", system_prompt="", model="m-loop", tools=[lookup]),
        "slow": AgentDef(name="slow", description="slow worker", system_prompt="", model="m-slow"),
    }
    from dataclasses import replace

    for k, v in (overrides or {}).items():
        base[k] = replace(base[k], **v)
    return base


# ---------- harness ----------
passed = 0
failed = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global passed, failed
    if cond:
        passed += 1
        print(f"  ✅ {name}")
    else:
        failed += 1
        print(f"  ❌ {name} {detail}")


def section(s: str) -> None:
    print(f"\n━━ {s}")


async def main() -> None:
    # S1+S2 — fan-out, isolation, parallelism, usage roll-up ------------------
    section("S1/S2 task fan-out · context isolation · parallel · usage roll-up")
    if True:
        rt = AgentRuntime(transport=MockTransport(), registry=registry())
        coord = rt.spawn(rt.root, "coordinator")
        assert isinstance(coord, Handle)
        rt.wake(coord, "answer using two lookups")
        r = await rt.wait(coord)
        check("parent reaches done", r.status == "done", r.text)
        check("parent got BOTH child answers", "found:data(x)" in r.text and len(r.text.split("found:")) == 3, r.text)
        check("parent ran 2 turns only (children's turns not in parent)", r.turns == 2, f"turns={r.turns}")
        check(
            "two children spawned with deterministic ids",
            any("/coordinator.1/explore.1:" in l for l in rt.trace) and any("/explore.2:" in l for l in rt.trace),
        )
        check(
            "children ran CONCURRENTLY (observed ≥2 turns in flight)",
            rt.max_observed_concurrent_turns >= 2,
            f"max={rt.max_observed_concurrent_turns}",
        )
        # roll-up: coord usage = own 2 turns×40 + children 2×(2 turns×40) = 240
        check("usage rolls up to parent (G3 ledger)", coord.usage_total == 240, f"usage={coord.usage_total}")
        check("children auto-closed after task", all(h.id == coord.id or h.state == "closed" for h in rt.list()))

    # S3 — suspension escalated INLINE: nearest interpreter wins --------------
    section("S3 child suspends → parent's waitFor answers (nearest interpreter)")
    if True:

        async def approve(req):
            from toolnexus import Answer

            return Answer(id=req.id, ok=True)

        rt = AgentRuntime(transport=MockTransport(), registry=registry({"approverParent": {"wait_for": approve}}))
        p = rt.spawn(rt.root, "approverParent")
        assert isinstance(p, Handle)
        rt.wake(p, "do the secret thing")
        r = await rt.wait(p)
        check("run completed (no durable pending)", r.status == "done", r.status)
        check("child's approval flowed through parent authority", "asker-done: secret-token" in r.text, r.text)
        check(
            "trace shows suspended→running round-trip",
            any("running→suspended" in l for l in rt.trace) and any("suspended→running" in l for l in rt.trace),
        )
        check(
            "escalation chose an ANCESTOR (not self)",
            any("escalate → root/approverParent.1 answers" in l for l in rt.trace),
        )

    # S4 — durable pending at root + resume cascade from checkpoint -----------
    section("S4 no interpreter anywhere → durable pending; resume() cascades from checkpoint")
    if True:
        rt = AgentRuntime(transport=MockTransport(), registry=registry())
        p = rt.spawn(rt.root, "approverParent")
        assert isinstance(p, Handle)
        rt.wake(p, "do the secret thing")
        r1 = await rt.wait(p)
        check("root run returned status=pending", r1.status == "pending", r1.status)
        path = getattr(r1.pending, "path", []) if r1.pending else []
        check("Request carries the handle PATH to the leaf", "approverParent.1" in json.dumps(path), json.dumps(path))
        check("both levels parked (suspended), zero tokens burning", all(h.state == "suspended" for h in rt.list()))
        before = next(h for h in rt.list() if "asker" in h.id).tokens
        from toolnexus import Answer

        assert r1.pending is not None
        await rt.resume(Answer(id=r1.pending.id, ok=True))
        await asyncio.sleep(0.05)
        tr = "\n".join(rt.trace)
        check("leaf resumed AT checkpoint (prior turns preserved)", "resume with Answer(ok=true) at checkpoint" in tr)
        check("parent cascade REATTACHED to the finished child (no re-execution)", "task replay → REATTACH" in tr)
        check("parent reached done after cascade", p.state == "idle", p.state)
        check(
            "child did not restart from scratch (usage grew, not reset)",
            next(h for h in rt.list() if "asker" in h.id).tokens > before,
        )

    # S5 — team peer: coalesced drain + provenance + timer dedupe -------------
    section("S5 unsolicited rail: posts coalesce into ONE turn, provenance marked, ticks dedupe")
    if True:
        rt = AgentRuntime(transport=MockTransport(), registry=registry())
        peer = rt.spawn(rt.root, "peer")
        assert isinstance(peer, Handle)
        rt.post(peer, InboxItem(from_="root/coordinator.1", channel="peer", text="update 1"))
        rt.post(peer, InboxItem(from_="external", channel="external", text="webhook payload"))
        rt.post(peer, InboxItem(from_="clock", channel="timer", text="tick"))
        rt.post(peer, InboxItem(from_="clock", channel="timer", text="tick"))
        rt.post(peer, InboxItem(from_="clock", channel="timer", text="tick"))
        rt.wake(peer)
        r = await rt.wait(peer)
        check("one wake = ONE turn for all items", r.turns == 1, f"turns={r.turns}")
        check("2 messages + 3 ticks coalesced to 3 items", r.text == "processed 3 items", r.text)

    # S6 — backpressure: inbox gate, concurrency gate, global turn gate -------
    section("S6 backpressure: loud inbox reject · per-parent concurrency queue · global turn gate")
    if True:
        rt = AgentRuntime(transport=MockTransport(), registry=registry(), inbox_cap=2)
        peer = rt.spawn(rt.root, "peer")
        assert isinstance(peer, Handle)
        rt.post(peer, InboxItem(from_="a", channel="peer", text="1"))
        rt.post(peer, InboxItem(from_="a", channel="peer", text="2"))
        third = rt.post(peer, InboxItem(from_="a", channel="peer", text="3"))
        check("inbox gate: third post REJECTED loudly to sender", not third.ok and "inbox full" in (third.error or ""))

        rt2 = AgentRuntime(
            transport=MockTransport(),
            registry=registry({"coordinator": {"budget": Budget(max_concurrent=1)}}),
        )
        c2 = rt2.spawn(rt2.root, "coordinator")
        assert isinstance(c2, Handle)
        rt2.wake(c2, "go")
        r2 = await rt2.wait(c2)
        check(
            "concurrency gate: 2nd child QUEUED then DEQUEUED, still completes",
            r2.status == "done" and any("wake QUEUED" in l for l in rt2.trace) and any("DEQUEUED wake" in l for l in rt2.trace),
            r2.text,
        )
        check("concurrency gate: never >1 child turn in flight… but parent turn allowed", rt2.max_observed_concurrent_turns <= 2)

        rt3 = AgentRuntime(transport=MockTransport(), registry=registry(), max_concurrent_turns=1)
        c3 = rt3.spawn(rt3.root, "coordinator")
        assert isinstance(c3, Handle)
        rt3.wake(c3, "go")
        await rt3.wait(c3)
        check(
            "global turn gate: with cap 1, max observed concurrent turns = 1",
            rt3.max_observed_concurrent_turns == 1,
            f"max={rt3.max_observed_concurrent_turns}",
        )

    # S7 — budgets: carve, exhaustion=incomplete, maxTurns visible ------------
    section("S7 budgets: hierarchical carve · exhaustion = incomplete (never crash) · maxTurns loud")
    if True:
        rt = AgentRuntime(transport=MockTransport(), registry=registry())
        c = rt.spawn(rt.root, "coordinator", Budget(max_tokens=100, max_children=2))
        assert isinstance(c, Handle)
        kid = rt.spawn(c, "explore", Budget(max_tokens=500))
        assert isinstance(kid, Handle)
        check("carve: child asked 500, parent pool 100 → effective 100", kid.pool.tokens == 100, str(kid.pool.tokens))
        kid2 = rt.spawn(c, "explore")
        assert isinstance(kid2, Handle)
        kid3 = rt.spawn(c, "explore")
        check("maxChildren enforced at spawn", isinstance(kid3, SpawnError) and "maxChildren" in kid3.error)
        rt.wake(kid, "go")
        await rt.wait(kid)  # burns 80 tokens (2 turns×40) from both kid and parent pools
        check("roll-up drains the PARENT pool too", c.pool.tokens == 20, str(c.pool.tokens))
        rt.wake(kid2, "go")
        r2 = await rt.wait(kid2)
        check("pool nearly gone: sibling still ran (20 left > 0)", r2.status == "done")
        r3 = await rt.run_turn(kid2, "again")
        check(
            "pool exhausted → status=incomplete, partial work preserved, NO crash",
            r3.status == "incomplete" and "budget exhausted" in r3.text,
            r3.status,
        )

        rt4 = AgentRuntime(transport=MockTransport(), registry=registry({"looper": {"budget": Budget(max_turns=3)}}))
        lp = rt4.spawn(rt4.root, "looper")
        assert isinstance(lp, Handle)
        rt4.wake(lp, "loop forever")
        rl = await rt4.wait(lp)
        check("maxTurns cap is LOUD: status=incomplete (not silent done)", rl.status == "incomplete", rl.status)

    # S8 — interrupt: aborts the TURN, not the agent --------------------------
    section("S8 interrupt = turn-abort → idle (inbox intact); NOT a kill")
    if True:
        slow_gate.clear()
        rt = AgentRuntime(transport=MockTransport(), registry=registry())
        s = rt.spawn(rt.root, "slow")
        assert isinstance(s, Handle)
        rt.post(s, InboxItem(from_="root", channel="peer", text="for later"))
        rt.wake(s, "work slowly")
        await asyncio.sleep(0.03)
        check("agent is mid-turn (running)", s.state == "running")
        w = asyncio.ensure_future(rt.wait(s))
        await asyncio.sleep(0)  # register the waiter before interrupting
        rt.interrupt(s)
        r = await w
        check("turn aborted → isError result, status=interrupted", r.is_error and r.status == "interrupted", r.status)
        check("agent is IDLE (alive), not closed", s.state == "idle", s.state)
        check("inbox survived the interrupt", len(s.inbox) == 1)
        slow_gate.set()

    # S9 — close cascade: leaf-first; post-after-close rejected ---------------
    section("S9 stop-all: close(root) cascades leaf-first; closed handles reject posts")
    if True:
        rt = AgentRuntime(transport=MockTransport(), registry=registry())
        c = rt.spawn(rt.root, "coordinator")
        assert isinstance(c, Handle)
        k1 = rt.spawn(c, "peer")
        assert isinstance(k1, Handle)
        k2 = rt.spawn(k1, "peer")  # grandchild
        assert isinstance(k2, Handle)
        close_order: list[str] = []
        for h in (c, k1, k2):
            h.defn.on_close = lambda hh, reason: close_order.append(hh.id)
        await rt.close(rt.root)
        check(
            "leaf-first order (grandchild → child → parent)",
            close_order[:3] == [k2.id, k1.id, c.id],
            " → ".join(close_order),
        )
        pr = rt.post(k1, InboxItem(from_="x", channel="peer", text="late"))
        check("post after close = loud isError", not pr.ok and "closed" in (pr.error or ""))

    # ---------- summary ----------
    print("\n" + "═" * 60)
    print(f'  {passed} passed · {failed} failed  {"— ALL SCENARIOS PASS ✅" if failed == 0 else "❌"}')
    print("═" * 60)
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except SystemExit:
        raise
    except BaseException as e:  # noqa: BLE001 — demo harness
        print(f"demo crashed: {e!r}")
        sys.exit(1)
