"""§7D Level-1/2 agent surface (S10–S13, 10 checks).

agent() + team wiring + soul file, team-scoped registries, agent_from_dir with a
virtual-clock heartbeat, and the asTool bridge into the classic API.
"""
from __future__ import annotations

import json

from toolnexus import create_client, create_toolkit
from toolnexus.agents import HEARTBEAT_OK, Budget, agent, agent_from_dir, start_agent

from _agent_mocks import MockTransport, VirtualClock, lookup


# --------------------------------------------------------------------------- #
# S10 — Level 1: the twelve-line coding agent
# --------------------------------------------------------------------------- #
async def test_level1_agent_team_and_soul_file(tmp_path):
    soul_path = tmp_path / "AGENTS-test.md"
    soul_path.write_text("You are the CODER. Fix things surgically.", encoding="utf-8")
    explore = agent("explore", does="read-only research", uses={"tools": [lookup]}, model="m-explore-ux")
    coder = agent(
        "coder",
        does="implements changes",
        soul_file=str(soul_path),
        team=[explore],
        model="m-coder",
        budget=Budget(max_tokens=10_000),
    )
    r = await coder.run("fix the failing test", transport=MockTransport())
    # 1. the coding agent completes via its team member's contribution
    assert r.status == "done" and "bug at line 42" in r.text, r.text
    # 2. the soul FILE reached the child's system prompt
    assert "[soul:loaded]" in r.text, r.text


# --------------------------------------------------------------------------- #
# S11 — team scoping: the registry is the reachable graph; task targets = team
# --------------------------------------------------------------------------- #
async def test_team_scoping_registry_is_transitive_closure():
    stranger = agent("stranger", does="should be unreachable", model="m-explore-ux", uses={"tools": [lookup]})
    explore = agent("explore", does="read-only research", uses={"tools": [lookup]}, model="m-explore-ux")
    coder = agent("coder", does="implements", team=[explore], model="m-coder")
    _ = stranger  # in scope but NOT in coder's team graph
    reg = coder.registry()
    # 1. the registry contains only the reachable team graph
    assert ",".join(sorted(reg)) == "coder,explore", sorted(reg)
    # 2. coder's task targets are exactly its team
    assert json.dumps(reg["coder"].task_targets) == '["explore"]'


# --------------------------------------------------------------------------- #
# S12 — Level 2: the directory IS the agent · heartbeat on the virtual clock ·
# HEARTBEAT_OK silence
# --------------------------------------------------------------------------- #
async def test_agent_from_dir_heartbeat_virtual_clock(tmp_path):
    (tmp_path / "SOUL.md").write_text("You are Mia. Warm, brief.", encoding="utf-8")
    (tmp_path / "USER.md").write_text("The user is Muthu.", encoding="utf-8")
    (tmp_path / "MEMORY.md").write_text("- Likes green tea.", encoding="utf-8")
    (tmp_path / "HEARTBEAT.md").write_text(
        "On heartbeat: if it is watering day, remind to water the plants.", encoding="utf-8"
    )
    mia = agent_from_dir(str(tmp_path), model="m-mia")
    direct = await mia.run("hello", transport=MockTransport(sleep=0))
    # 1. bootstrap files discovered + injected as ## sections (in bootstrap order)
    assert direct.text == "soul-sections:[SOUL.md,USER.md,MEMORY.md]", direct.text

    reports: list[str] = []
    clock = VirtualClock()
    started = start_agent(
        mia, every_ms=25, on_report=reports.append, transport=MockTransport(sleep=0), clock=clock
    )
    for _ in range(3):
        await clock.advance(0.025)
    await started.stop()
    hb_turns = sum(1 for l in started.rt.trace if "idle→running" in l)
    # 2. the heartbeat woke the agent repeatedly (driven purely by the virtual clock)
    assert hb_turns >= 2, started.rt.trace
    # 3. HEARTBEAT_OK wakes stay SILENT (only actionable beats report)
    assert all("water" in t and HEARTBEAT_OK not in t for t in reports), reports
    # 4. the agent closed cleanly on stop()
    assert started.handle.state == "closed"


# --------------------------------------------------------------------------- #
# S13 — the bridge: an Agent IS a Tool inside the classic API
# --------------------------------------------------------------------------- #
async def test_as_tool_bridge_into_classic_api():
    explore = agent("explore", does="read-only research", uses={"tools": [lookup]}, model="m-explore-ux")
    toolkit = await create_toolkit(
        builtins=False, extra_tools=[explore.as_tool(transport=MockTransport(sleep=0))]
    )
    client = create_client(
        base_url="http://mock.local",
        style="openai",
        model="m-old-api",
        api_key="unused",
        http_transport=MockTransport(sleep=0),
    )
    r = await client.run("summarize", toolkit)
    # 1. the classic API called the agent like any tool; its loop ran in isolation
    assert "old-api summary:" in r.text and "bug at line 42" in r.text, r.text
    # 2. agent metadata surfaced through the tool result
    meta = (r.tool_calls[0].get("metadata") or {}) if r.tool_calls else {}
    assert meta.get("agent") == "explore"
