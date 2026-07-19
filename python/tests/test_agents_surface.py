"""§7D Level-1 agent surface (S10, S11, S13).

agent() + team wiring + soul file, team-scoped registries, and the asTool bridge
into the classic API. The Level-2 persona surface (agent_from_dir + memory
builtin + heartbeat, §7E) is covered by ``test_agents_home.py`` against the shared
``examples/persona-agent`` fixture (H1–H7).
"""
from __future__ import annotations

import json

from toolnexus import create_client, create_toolkit
from toolnexus.agents import Budget, agent

from _agent_mocks import MockTransport, lookup


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
