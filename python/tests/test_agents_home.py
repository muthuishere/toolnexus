"""§7E agent home — persona surface (H1–H7, 15 checks).

The directory IS the agent: ordered bootstrap files compose the soul; the memory
builtin persists to disk with frozen-snapshot semantics (loads next session, never
mutates the live prompt); the heartbeat speaks only when HEARTBEAT.md is due, else
stays silent on HEARTBEAT_OK.

Driven by the shared, language-neutral fixture ``examples/persona-agent/fixture.json``
(the §0 conformance artifact) with a scripted, zero-cost mock LLM — the Python twin
of ``js/spike/home-demo.ts``. The behavior reference is ``js/spike/home.ts``.
"""
from __future__ import annotations

import json
import re
import threading
from typing import Any, Optional

from toolnexus.agents import (
    BOOTSTRAP_ORDER,
    HEARTBEAT_OK,
    agent_from_dir,
    compose_soul,
    memory_tool,
    start_agent,
)

from _agent_mocks import EXAMPLES, VirtualClock, openai_body, tool_call

FIXTURE = json.loads((EXAMPLES / "persona-agent" / "fixture.json").read_text(encoding="utf-8"))
BOOTSTRAP_DIR: dict[str, str] = FIXTURE["bootstrapDir"]


# --------------------------------------------------------------------------- #
# Scripted mock LLM — mirrors fixture.mockLLM.scripts / home-demo.ts mockFetch
# --------------------------------------------------------------------------- #
class MockHomeTransport:
    """Zero-cost scripted LLM keyed by ``model``, implementing the fixture's four
    scripts (soul-echo, memory write, next-session recall, heartbeat due/not-due)."""

    def __init__(self, sleep: float = 0.0, gate: Optional[threading.Event] = None) -> None:
        self.sleep = sleep
        # Optional gate: when set, m-heartbeat blocks a turn IN-FLIGHT (in its worker
        # thread) so the beat loop keeps posting ticks that coalesce (pin #3).
        self.gate = gate
        # Max number of timer ticks the runtime coalesced into a single drained turn.
        self.max_coalesced = 0

    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        model = payload["model"]
        msgs: list[dict[str, Any]] = payload["messages"]
        tool_msgs = [m for m in msgs if m.get("role") == "tool"]
        sys = next((str(m.get("content") or "") for m in msgs if m.get("role") == "system"), "")
        last = str(msgs[-1].get("content") or "") if msgs else ""

        if model == "m-echo-soul":
            seen = [f for f in BOOTSTRAP_ORDER if f"## {f}" in sys]
            return openai_body({"content": f'sections:[{",".join(seen)}]'})
        if model == "m-remember":
            if not tool_msgs:
                return openai_body(
                    tool_call("memory", {"action": "add", "target": "user", "text": "Prefers dark roast"}, "w1")
                )
            return openai_body({"content": f'saved: {tool_msgs[0]["content"]}'})
        if model == "m-recall":
            return openai_body({"content": "I recall: dark roast" if "Prefers dark roast" in sys else "no memory"})
        if model == "m-heartbeat":
            m = re.search(r"tick \(x(\d+) coalesced\)", last)
            if m:
                self.max_coalesced = max(self.max_coalesced, int(m.group(1)))
            if self.gate is not None:
                self.gate.wait(timeout=5)  # hold this turn in-flight until released
            due = "remind about the 3pm sync" in sys and "Heartbeat" in last
            return openai_body({"content": "Reminder: 3pm sync \U0001f514" if due else HEARTBEAT_OK})
        return openai_body({"content": "ok"})

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):  # noqa: A003
        raise NotImplementedError("mock is non-streaming")


def _write_dir(base, files: dict[str, str]):
    base.mkdir(parents=True, exist_ok=True)
    for name, body in files.items():
        (base / name).write_text(body, encoding="utf-8")
    return str(base)


def _tool_names(a) -> list[str]:
    return [t.name for t in (a.uses.get("tools") or [])]


async def _exec(tool, args):
    return await tool.execute(args)


# --------------------------------------------------------------------------- #
# H1 — the directory IS the agent: ordered bootstrap files → soul (3 checks)
# --------------------------------------------------------------------------- #
async def test_h1_bootstrap_discovery_and_ordered_injection(tmp_path):
    dir = _write_dir(tmp_path, BOOTSTRAP_DIR)  # SOUL, USER, HEARTBEAT, MEMORY
    soul, found = compose_soul(dir)
    # 1. discovers only present files, in canonical bootstrap order
    assert found == FIXTURE["expect"]["H1_composedSoulSectionsInOrder"], found
    # 2. injected as ## sections, SOUL before USER before MEMORY
    assert soul.index("## SOUL.md") < soul.index("## USER.md") < soul.index("## MEMORY.md")
    # 3. the composed soul reaches the child system prompt
    ava = agent_from_dir(dir, model="m-echo-soul")
    r = await ava.run("who are you?", transport=MockHomeTransport())
    assert r.text == "sections:[SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md]", r.text


# --------------------------------------------------------------------------- #
# H2 — per-file 2 MB cap (1 check)
# --------------------------------------------------------------------------- #
async def test_h2_per_file_byte_cap(tmp_path):
    dir = _write_dir(tmp_path, {"SOUL.md": "x" * (3 * 1024 * 1024)})
    soul, _ = compose_soul(dir)
    # a >2 MB file is truncated with a notice; the on-disk file is untouched
    assert "[truncated: exceeds 2 MB bootstrap cap]" in soul and len(soul) < int(2.1 * 1024 * 1024)
    assert len((tmp_path / "SOUL.md").read_text(encoding="utf-8")) == 3 * 1024 * 1024


# --------------------------------------------------------------------------- #
# H3 — memory tool: add/replace/remove persist to disk (5 checks)
# --------------------------------------------------------------------------- #
async def test_h3_memory_tool_persists_to_disk(tmp_path):
    dir = _write_dir(tmp_path, {"MEMORY.md": "- Likes green tea."})
    tool = memory_tool(dir)
    await _exec(tool, {"action": "add", "text": "Likes hiking"})
    # 1. add appends an entry
    assert "- Likes hiking" in (tmp_path / "MEMORY.md").read_text(encoding="utf-8")
    await _exec(tool, {"action": "replace", "text": "green tea", "with": "oolong"})
    # 2. replace swaps a substring
    assert "oolong" in (tmp_path / "MEMORY.md").read_text(encoding="utf-8")
    await _exec(tool, {"action": "remove", "text": "- Likes hiking\n"})
    # 3. remove deletes an entry
    assert "hiking" not in (tmp_path / "MEMORY.md").read_text(encoding="utf-8")
    miss = await _exec(tool, {"action": "replace", "text": "nonexistent", "with": "x"})
    # 4. replace of a missing substring is a loud is_error
    assert miss.is_error
    user_write = await _exec(tool, {"action": "add", "target": "user", "text": "Speaks Tamil"})
    # 5. target=user writes USER.md, not MEMORY.md
    assert not user_write.is_error and "Speaks Tamil" in (tmp_path / "USER.md").read_text(encoding="utf-8")


# --------------------------------------------------------------------------- #
# H4 — frozen-snapshot: a mid-session write does NOT change the live prompt,
# but the NEXT session's snapshot carries it (2 checks)
# --------------------------------------------------------------------------- #
async def test_h4_frozen_snapshot_injection(tmp_path):
    dir = _write_dir(tmp_path, {"USER.md": "The user is new."})
    ava = agent_from_dir(dir, model="m-remember")
    await ava.run("note my coffee preference", transport=MockHomeTransport())
    exp_disk = FIXTURE["expect"]["H4_writeLandsOnDisk"]
    # 1. the write landed on disk
    assert exp_disk["contains"] in (tmp_path / exp_disk["file"]).read_text(encoding="utf-8")
    # 2. a SECOND, fresh run re-reads the file → the snapshot now carries the note
    exp_next = FIXTURE["expect"]["H4_nextSessionSnapshotCarriesMemory"]
    ava2 = agent_from_dir(dir, model=exp_next["model"])
    r2 = await ava2.run("what do you know about me?", transport=MockHomeTransport())
    assert r2.text == exp_next["text"], r2.text


# --------------------------------------------------------------------------- #
# H5 — heartbeat: speaks only when due, HEARTBEAT_OK silent; ticks coalesce
# while a turn is in-flight (2 checks)
# --------------------------------------------------------------------------- #
async def test_h5_heartbeat_speaks_when_due_ok_silent(tmp_path):
    # DUE dir: HEARTBEAT.md rule is live → every beat surfaces a real reminder.
    due_dir = _write_dir(tmp_path / "due", {"SOUL.md": BOOTSTRAP_DIR["SOUL.md"], "HEARTBEAT.md": BOOTSTRAP_DIR["HEARTBEAT.md"]})
    due_reports: list[str] = []
    clock = VirtualClock()
    due = start_agent(
        agent_from_dir(due_dir, model="m-heartbeat"),
        every_ms=20, on_report=due_reports.append, transport=MockHomeTransport(), clock=clock,
    )
    for _ in range(3):
        await clock.advance(0.020)
    await due.stop()

    # NOT-DUE dir: no HEARTBEAT.md rule in the soul → the model replies HEARTBEAT_OK,
    # which must stay SILENT (no report emitted to the host).
    quiet_dir = _write_dir(tmp_path / "quiet", {"SOUL.md": BOOTSTRAP_DIR["SOUL.md"]})
    quiet_reports: list[str] = []
    clock2 = VirtualClock()
    quiet = start_agent(
        agent_from_dir(quiet_dir, model="m-heartbeat"),
        every_ms=20, on_report=quiet_reports.append, transport=MockHomeTransport(), clock=clock2,
    )
    for _ in range(3):
        await clock2.advance(0.020)
    quiet_woke = sum(1 for l in quiet.rt.trace if "idle→running" in l)
    await quiet.stop()

    # a due heartbeat surfaces the reminder; a HEARTBEAT_OK beat stays silent
    assert due_reports and all("3pm sync" in b and HEARTBEAT_OK not in b for b in due_reports), due_reports
    assert quiet_woke >= 2 and quiet_reports == [], (quiet_woke, quiet_reports)
    assert due.handle.state == "closed" and quiet.handle.state == "closed"


async def test_h5_ticks_coalesce_while_turn_in_flight(tmp_path):
    # A real coalesce test (pin #3): keep the FIRST beat's turn genuinely in-flight
    # (gate the model in its worker thread) while later intervals post more ticks;
    # the next idle wake must drain the whole backlog as ONE coalesced turn.
    dir = _write_dir(tmp_path, {"SOUL.md": BOOTSTRAP_DIR["SOUL.md"], "HEARTBEAT.md": BOOTSTRAP_DIR["HEARTBEAT.md"]})
    gate = threading.Event()
    mock = MockHomeTransport(gate=gate)
    clock = VirtualClock()
    started = start_agent(agent_from_dir(dir, model="m-heartbeat"), every_ms=20, transport=mock, clock=clock)

    # The first beat wakes a turn that blocks on the gate; every later interval only
    # POSTS a tick (handle is running → no new wake), so ticks pile up in the inbox
    # rather than each spawning its own turn.
    for _ in range(5):
        await clock.advance(0.020, settle=0.06)
    assert len(started.handle.inbox) >= 2, started.handle.inbox  # accumulated, not one-turn-per-tick

    gate.set()  # release the in-flight turn → it completes → handle returns idle
    await clock.advance(0.0, settle=0.12)
    await clock.advance(0.020, settle=0.12)  # next idle wake drains the WHOLE backlog as one turn
    await clock.advance(0.0, settle=0.12)
    await started.stop()

    # the runtime coalesced the queued ticks into a SINGLE drained turn (not N turns)
    assert mock.max_coalesced >= 2, mock.max_coalesced


# --------------------------------------------------------------------------- #
# H6 — memory=False → no memory tool (1 check)
# --------------------------------------------------------------------------- #
async def test_h6_memory_false_omits_memory_tool(tmp_path):
    dir = _write_dir(tmp_path, {"SOUL.md": "Read-only Ava."})
    ava = agent_from_dir(dir, memory=False)
    assert "memory" not in _tool_names(ava)


# --------------------------------------------------------------------------- #
# H7 — recipe: dream/consolidation is fromDir + memory, zero new API (1 check)
# --------------------------------------------------------------------------- #
async def test_h7_dream_is_composition_not_new_surface(tmp_path):
    dir = _write_dir(
        tmp_path,
        {"SOUL.md": "Nightly consolidator.", "HEARTBEAT.md": "Consolidate: merge duplicate notes into MEMORY.md."},
    )
    dream = agent_from_dir(dir, model="m-echo-soul")
    # a dream agent is just fromDir + the memory tool (composition, not new surface)
    assert "memory" in _tool_names(dream)
