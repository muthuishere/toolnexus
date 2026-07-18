"""§7D agent-runtime substrate conformance (S1–S9, 36 checks).

Each scenario asserts the observable contract of SPEC.md §7D against the shared
``examples/subagent-*`` fixtures: transition traces, deterministic ids, budget
ledger values, gate behavior, and the durable-resume cascade. The LLM is the
scripted transport in ``_agent_mocks`` (mirrors the fixtures' ``mockLLM``).
"""
from __future__ import annotations

import asyncio
import threading

from toolnexus import Answer
from toolnexus.agents import AgentRuntime, Budget, Handle, InboxItem, SpawnError

from _agent_mocks import MockTransport, load_fixture, registry, transitions, transitions_match


def _spawn(rt: AgentRuntime, name: str, budget: Budget | None = None, parent: Handle | None = None) -> Handle:
    h = rt.spawn(parent or rt.root, name, budget)
    assert isinstance(h, Handle), getattr(h, "error", h)
    return h


# --------------------------------------------------------------------------- #
# S1/S2 — task fan-out · context isolation · parallelism · usage roll-up
# (examples/subagent-fanout)
# --------------------------------------------------------------------------- #
async def test_fanout_isolation_parallelism_rollup():
    fx = load_fixture("subagent-fanout")["expect"]
    rt = AgentRuntime(transport=MockTransport(), registry=registry())
    coord = _spawn(rt, "coordinator")
    rt.wake(coord, "answer using two lookups")
    r = await rt.wait(coord)

    # 1. parent reaches done
    assert r.status == fx["status"], r.text
    # 2. parent got BOTH child answers (context isolation: only final texts)
    assert all(s in r.text for s in fx["textContains"])
    assert r.text.count("found:") == fx["textOccurrences"]["found:"]
    # 3. parent ran 2 turns only (children's turns never enter the parent)
    assert r.turns == fx["parentTurns"]
    # 4. two children spawned with deterministic parent-scoped ids
    for cid, expected in fx["transitions"].items():
        ok, got = transitions_match(rt.trace, cid, expected)
        assert ok, (cid, got)
    assert [c.id for c in coord.children] == fx["childIds"]
    # 5. children ran CONCURRENTLY (≥2 LLM turns observed in flight)
    assert rt.max_observed_concurrent_turns >= fx["concurrentTurnsObservedAtLeast"]
    # 6. usage rolls up to the parent (the roll-up IS the ledger)
    assert coord.usage_total == fx["parentUsageTotal"]
    # 7. children auto-closed after task
    assert fx["childrenClosedAfterTask"]
    assert all(v.id == coord.id or v.state == "closed" for v in rt.list())


# --------------------------------------------------------------------------- #
# S3 — suspension escalated INLINE: nearest interpreter wins
# (examples/subagent-escalation)
# --------------------------------------------------------------------------- #
async def test_escalation_nearest_interpreter():
    fx = load_fixture("subagent-escalation")["expect"]

    async def approve(req):
        return Answer(id=req.id, ok=True)

    rt = AgentRuntime(
        transport=MockTransport(), registry=registry({"approverParent": {"wait_for": approve}})
    )
    p = _spawn(rt, "approverParent")
    rt.wake(p, "do the secret thing")
    r = await rt.wait(p)

    # 1. run completed inline (no durable pending)
    assert r.status == fx["status"], r.status
    # 2. the child's approval flowed through the PARENT's authority
    assert all(s in r.text for s in fx["textContains"]), r.text
    # 3. child trace shows the suspended→running round trip
    child_id = "root/approverParent.1/asker.1"
    child_tr = transitions(rt.trace, child_id)
    for arrow in fx["escalation"]["childTrace"]:
        assert arrow in child_tr, child_tr
    ok, got = transitions_match(rt.trace, child_id, fx["transitions"][child_id])
    assert ok, got
    ok, got = transitions_match(rt.trace, "root/approverParent.1", fx["transitions"]["root/approverParent.1"])
    assert ok, got
    # 4. escalation chose an ANCESTOR (not self)
    answered_by = fx["escalation"]["answeredBy"]
    assert any(f"escalate → {answered_by} answers" in l for l in rt.trace)


# --------------------------------------------------------------------------- #
# S4 — no interpreter anywhere → durable pending; resume() cascades from the
# checkpoint (examples/subagent-durable-resume)
# --------------------------------------------------------------------------- #
async def test_durable_pending_and_resume_cascade():
    fx = load_fixture("subagent-durable-resume")
    rt = AgentRuntime(transport=MockTransport(), registry=registry())
    p = _spawn(rt, "approverParent")
    rt.wake(p, "do the secret thing")
    r1 = await rt.wait(p)

    # 1. root run returned status=pending
    assert r1.status == fx["phase1_expect"]["status"], r1.status
    assert r1.pending is not None and r1.pending.kind == fx["phase1_expect"]["pendingKind"]
    # 2. the Request carries the handle PATH in data.path (§10 addendum)
    path = (r1.pending.data or {}).get("path", [])
    assert "/".join(path) == fx["phase1_expect"]["pendingDataPathEndsWithin"], path
    # 3. both levels parked (suspended) — zero tokens burning
    assert all(v.state == "suspended" for v in rt.list())

    before = next(v for v in rt.list() if "asker" in v.id).tokens
    await rt.resume(Answer(id=r1.pending.id, ok=True))
    await asyncio.sleep(0.05)
    tr = "\n".join(rt.trace)

    # 4. leaf resumed AT its checkpoint (prior turns preserved)
    assert "resume with Answer(ok=true) at checkpoint" in tr
    # 5. the parent cascade REATTACHED to the finished child (no re-execution)
    for expected in fx["phase2_expect"]["traceContains"]:
        assert expected in tr
    # 6. parent reached done after the cascade; transitions match the fixture
    assert p.state == fx["phase2_expect"]["finalParentState"], p.state
    for hid, expected in fx["phase2_expect"]["transitions"].items():
        # Late fixture pin (82778d6 + 62d285e): the durable-resume trace shape is
        # suspended→idle then idle→running, and asker.1 ends with a terminal
        # idle→closed — matched IN FULL (suffix-form equivalent).
        ok, got = transitions_match(rt.trace, hid, expected)
        assert ok, (hid, got)
    # no duplicate child ids (reattachment, not respawn)
    assert fx["phase2_expect"]["noDuplicateChildIds"]
    assert [c.id for c in p.children] == ["root/approverParent.1/asker.1"]
    # 7. the child did NOT restart from scratch (usage grew, never reset)
    assert next(v for v in rt.list() if "asker" in v.id).tokens > before


# --------------------------------------------------------------------------- #
# S5 — unsolicited rail: posts coalesce into ONE turn, provenance, tick dedupe
# (examples/subagent-lifecycle · coalescedDrain)
# --------------------------------------------------------------------------- #
async def test_coalesced_drain_provenance_tick_dedupe():
    fx = load_fixture("subagent-lifecycle")["scenarios"]["coalescedDrain"]["expect"]
    rt = AgentRuntime(transport=MockTransport(), registry=registry())
    peer = _spawn(rt, "peer")
    rt.post(peer, InboxItem(from_="root/coordinator.1", channel="peer", text="update 1"))
    rt.post(peer, InboxItem(from_="external", channel="external", text="webhook payload"))
    for _ in range(3):
        rt.post(peer, InboxItem(from_="clock", channel="timer", text="tick"))
    rt.wake(peer)
    r = await rt.wait(peer)
    # 1. one wake = ONE turn for all items
    assert r.turns == fx["turns"], r.turns
    # 2. 2 messages + 3 ticks coalesced to 3 rendered items
    assert r.text == fx["text"], r.text


# --------------------------------------------------------------------------- #
# S6 — backpressure: inbox gate · per-parent concurrency queue · global turn gate
# (examples/subagent-lifecycle · inboxGate / concurrencyGate / turnGate)
# --------------------------------------------------------------------------- #
async def test_backpressure_three_gates():
    scenarios = load_fixture("subagent-lifecycle")["scenarios"]

    # 1. inbox gate: third post REJECTED loudly to the sender
    cap = scenarios["inboxGate"]["runtime"]["inboxCap"]
    rt = AgentRuntime(transport=MockTransport(), registry=registry(), inbox_cap=cap)
    peer = _spawn(rt, "peer")
    assert rt.post(peer, InboxItem(from_="a", channel="peer", text="1")).ok
    assert rt.post(peer, InboxItem(from_="a", channel="peer", text="2")).ok
    third = rt.post(peer, InboxItem(from_="a", channel="peer", text="3"))
    assert not third.ok and scenarios["inboxGate"]["steps"][2]["expect"]["errorContains"] in (third.error or "")

    # 2. concurrency gate: 2nd child QUEUED then DEQUEUED (slot transfer), completes
    cg = scenarios["concurrencyGate"]
    rt2 = AgentRuntime(
        transport=MockTransport(),
        registry=registry({"coordinator": {"budget": Budget(max_concurrent=1)}}),
    )
    c2 = _spawn(rt2, "coordinator")
    rt2.wake(c2, cg["run"]["prompt"])
    r2 = await rt2.wait(c2)
    assert r2.status == cg["expect"]["status"], r2.text
    for line in cg["expect"]["traceContains"]:
        assert any(line in l for l in rt2.trace), line
    # 3. never >1 child turn in flight (parent turn still allowed alongside)
    assert rt2.max_observed_concurrent_turns <= 2

    # 4. global turn gate wraps ONLY the LLM HTTP call: cap 1 cannot deadlock the tree
    tg = scenarios["turnGate"]
    rt3 = AgentRuntime(
        transport=MockTransport(),
        registry=registry(),
        max_concurrent_turns=tg["runtime"]["maxConcurrentTurns"],
    )
    c3 = _spawn(rt3, "coordinator")
    rt3.wake(c3, tg["run"]["prompt"])
    r3 = await rt3.wait(c3)
    assert r3.status == tg["expect"]["status"]
    assert rt3.max_observed_concurrent_turns == tg["expect"]["maxObservedConcurrentTurns"]


# --------------------------------------------------------------------------- #
# S7 — budgets: hierarchical carve · live-ancestor exhaustion = incomplete ·
# maxTurns loud (examples/subagent-budgets)
# --------------------------------------------------------------------------- #
async def test_budgets_carve_live_walk_loud_limits():
    steps = load_fixture("subagent-budgets")["steps"]
    rt = AgentRuntime(transport=MockTransport(), registry=registry())
    c = _spawn(rt, "coordinator", Budget(max_tokens=100, max_children=2))
    kid = _spawn(rt, "explore", Budget(max_tokens=500), parent=c)
    # 1. carve: child asked 500, parent pool 100 → effective 100
    assert kid.pool.tokens == steps[1]["expect"]["effectiveTokens"]
    kid2 = _spawn(rt, "explore", parent=c)
    kid3 = rt.spawn(c, "explore")
    # 2. maxChildren enforced at spawn (uniform error naming the limit)
    assert isinstance(kid3, SpawnError) and steps[3]["expect"]["isError"] in kid3.error
    rt.wake(kid, "go")
    await rt.wait(kid)  # burns 80 (2 turns × 40) from kid AND parent pools
    # 3. the roll-up drains the PARENT pool too
    assert c.pool.tokens == steps[4]["expect"]["parentPoolAfter"]
    rt.wake(kid2, "go")
    r2 = await rt.wait(kid2)
    # 4. pool nearly gone: the sibling still ran (live walk allows 20 > 0)
    assert r2.status == steps[5]["expect"]["status"]
    rt.wake(kid2, "again")
    r3 = await rt.wait(kid2)
    # 5. ancestor pool exhausted → status=incomplete, partial work preserved, NO crash
    assert r3.status == steps[6]["expect"]["status"]
    assert steps[6]["expect"]["errorContains"] in r3.text

    # 6. maxTurns cap is LOUD: status=incomplete, never a silent done
    rt4 = AgentRuntime(transport=MockTransport(), registry=registry({"looper": {"budget": Budget(max_turns=3)}}))
    lp = _spawn(rt4, "looper")
    rt4.wake(lp, "loop forever")
    rl = await rt4.wait(lp)
    assert rl.status == steps[8]["expect"]["status"], rl.status


# --------------------------------------------------------------------------- #
# S8 — interrupt = turn-abort → idle (inbox intact via transactional drain);
# NEVER a kill (examples/subagent-lifecycle · interrupt)
# --------------------------------------------------------------------------- #
async def test_interrupt_aborts_turn_not_agent():
    fx = load_fixture("subagent-lifecycle")["scenarios"]["interrupt"]["expect"]
    gate = threading.Event()
    rt = AgentRuntime(transport=MockTransport(slow_gate=gate), registry=registry())
    s = _spawn(rt, "slow")
    try:
        rt.post(s, InboxItem(from_="root", channel="peer", text="for later"))
        rt.wake(s, "work slowly")
        await asyncio.sleep(0.03)
        # 1. the agent is mid-turn
        assert s.state == "running"
        w = asyncio.ensure_future(rt.wait(s))
        await asyncio.sleep(0)  # register the waiter before interrupting
        rt.interrupt(s)
        r = await w
        # 2. the turn aborted → uniform isError result with interrupted status
        assert r.is_error and r.status == fx["waiterStatus"], r.status
        # 3. the agent is IDLE (alive), not closed
        assert s.state == fx["finalState"], s.state
        # 4. the inbox survived (transactional drain restored the item)
        assert len(s.inbox) == fx["inboxLen"]
    finally:
        gate.set()


# --------------------------------------------------------------------------- #
# S9 — stop-all: close(root) cascades LEAF-FIRST; closed handles reject posts
# (examples/subagent-lifecycle · closeCascade)
# --------------------------------------------------------------------------- #
async def test_close_cascade_leaf_first_and_post_after_close():
    fx = load_fixture("subagent-lifecycle")["scenarios"]["closeCascade"]
    rt = AgentRuntime(transport=MockTransport(), registry=registry())
    c = _spawn(rt, "coordinator")
    k1 = _spawn(rt, "peer", parent=c)
    k2 = _spawn(rt, "peer", parent=k1)  # grandchild
    close_order: list[str] = []
    for h in (c, k1, k2):
        h.defn.on_close = lambda hh, reason: close_order.append(hh.id)
    await rt.close(rt.root)
    # 1. leaf-first order (grandchild → child → parent) and every handle closed
    assert close_order[:3] == [k2.id, k1.id, c.id], close_order
    assert all(v.state == "closed" for v in rt.list())
    # 2. post after close = loud isError
    pr = rt.post(k1, InboxItem(from_="x", channel="peer", text="late"))
    assert not pr.ok and fx["steps"][1]["expect"]["errorContains"] in (pr.error or "")


# --------------------------------------------------------------------------- #
# Runtime obligations beyond the spike's 46: ONE ConversationStore — transcripts
# genuinely survive turns (conversation id = handle id).
# --------------------------------------------------------------------------- #
async def test_transcript_survives_turns_in_runtime_store():
    rt = AgentRuntime(transport=MockTransport(sleep=0), registry=registry())
    peer = _spawn(rt, "peer")
    await rt.run_turn(peer, "first message")
    await rt.run_turn(peer, "second message")
    stored = await rt.conversation_store.get(peer.id)
    assert stored is not None
    users = [m for m in stored if m.get("role") == "user"]
    assert any("first message" in str(m.get("content")) for m in users)
    assert any("second message" in str(m.get("content")) for m in users)
    assert sum(1 for m in stored if m.get("role") == "assistant") >= 2


async def test_transcript_rewound_on_durable_pending():
    """A Run that ends suspended REWINDS its stored transcript to the pre-turn
    checkpoint: the §10 halted-tool placeholder must NOT persist, or the resumed
    parent would see it as a resolved tool result and never re-invoke `task`
    (breaking task-key reattachment). Cross-port pin from the Java port."""
    rt = AgentRuntime(transport=MockTransport(sleep=0), registry=registry())
    p = _spawn(rt, "approverParent")
    rt.wake(p, "do the secret thing")
    r1 = await rt.wait(p)
    assert r1.status == "pending"
    # Both suspended handles' stored transcripts are back at the pre-turn checkpoint
    # (here: empty — no completed turn has committed anything).
    for hid in (p.id, "root/approverParent.1/asker.1"):
        stored = await rt.conversation_store.get(hid)
        assert not stored, (hid, stored)
    # ...so the resume cascade re-invokes task and REATTACHES (no duplicate child).
    assert r1.pending is not None
    await rt.resume(Answer(id=r1.pending.id, ok=True))
    assert any("task replay → REATTACH" in l for l in rt.trace)
    assert [c.id for c in p.children] == ["root/approverParent.1/asker.1"]
    # Completed turns DO commit: the parent's final transcript survives.
    stored = await rt.conversation_store.get(p.id)
    assert stored and any(m.get("role") == "assistant" for m in stored)


async def test_forced_close_restores_drained_items():
    """Transactional drain on forced close too (spec: 'interrupt or forced close'):
    the aborted turn consumed nothing, so its items are restored — the closed
    handle's final state stays queryable."""
    gate = threading.Event()
    rt = AgentRuntime(transport=MockTransport(slow_gate=gate), registry=registry())
    s = _spawn(rt, "slow")
    try:
        rt.post(s, InboxItem(from_="root", channel="peer", text="for later"))
        rt.wake(s, "work slowly")
        await asyncio.sleep(0.03)
        assert s.state == "running" and not s.inbox  # item drained into the turn
        await rt.close(s, force=True)
        assert s.state == "closed"
        assert [i.text for i in s.inbox] == ["for later"]
    finally:
        gate.set()


async def test_wait_resolves_immediately_when_already_settled():
    """Wait next-or-last: a settled handle answers immediately (registration order
    relative to completion is unobservable)."""
    rt = AgentRuntime(transport=MockTransport(sleep=0), registry=registry())
    peer = _spawn(rt, "peer")
    first = await rt.run_turn(peer, "hello")
    assert first.status == "done"
    # No turn in flight; wait() must not hang — it returns the LAST result.
    again = await asyncio.wait_for(rt.wait(peer), timeout=1.0)
    assert again is first


async def test_task_tool_only_for_agents_with_a_team():
    """Delegation is opt-in (§7D): the task tool is offered ONLY to agents whose
    definition declares a team — teamless children get none (recursion opt-in)."""
    from toolnexus.agents import AgentDef

    reg = registry()
    reg["teamed-lister"] = AgentDef(
        name="teamed-lister", description="lists tools", system_prompt="",
        model="m-tool-lister", tools=[], task_targets=["explore"],
    )
    reg["teamless-lister"] = AgentDef(
        name="teamless-lister", description="lists tools", system_prompt="",
        model="m-tool-lister", tools=[],
    )
    rt = AgentRuntime(transport=MockTransport(sleep=0), registry=reg)
    teamed = await rt.run_turn(_spawn(rt, "teamed-lister"), "what tools do you have?")
    teamless = await rt.run_turn(_spawn(rt, "teamless-lister"), "what tools do you have?")
    assert teamed.text == "tools:[task]", teamed.text
    assert teamless.text == "tools:[]", teamless.text


async def test_graceful_close_escalates_after_shutdown_ms():
    """close() lets a running turn finish bounded by shutdown_ms, then ESCALATES
    to interrupt — and only returns once the abort has settled (drained items
    restored, no idle resurrection after the closed state)."""
    gate = threading.Event()
    rt = AgentRuntime(transport=MockTransport(slow_gate=gate), registry=registry(), shutdown_ms=50)
    s = _spawn(rt, "slow")
    try:
        rt.post(s, InboxItem(from_="root", channel="peer", text="for later"))
        rt.wake(s, "work slowly")
        await asyncio.sleep(0.03)
        assert s.state == "running"
        await rt.close(s)  # graceful — the turn never finishes, so grace expires
        assert s.state == "closed"
        assert [i.text for i in s.inbox] == ["for later"]  # restored before close returned
        await asyncio.sleep(0.02)
        assert s.state == "closed"  # the settled abort never resurrects the handle
    finally:
        gate.set()


async def test_task_out_of_team_is_uniform_error_listing_team():
    """Out-of-team task targets are refused with the team names (sorted)."""
    fx = load_fixture("subagent-fanout")  # coordinator's team = ["explore"]
    assert fx["registry"]["coordinator"]["team"] == ["explore"]
    rt = AgentRuntime(transport=MockTransport(sleep=0), registry=registry())
    coord = _spawn(rt, "coordinator")
    tool = rt._task_tool(coord)
    out = await tool.execute({"agent": "peer", "prompt": "hi"})
    assert out.is_error and "not in this agent's team" in out.output and "explore" in out.output
    # the tool description advertises ONLY the team (sorted by name)
    assert "explore: read-only research" in tool.description
    assert "peer:" not in tool.description
