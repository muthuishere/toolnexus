"""SPIKE demo 2 — the Level-1/2/bridge UX (S10–S13) on the same mock LLM (Python).

Run: cd python && python spike/demo_ux.py
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import time
from typing import Any, Optional

from toolnexus import Tool, ToolResult, create_client, create_toolkit

from agents import HEARTBEAT_OK, agent, agent_from_dir, start_agent
from runtime import Budget


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
    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        time.sleep(0.01)
        model = payload["model"]
        msgs: list[dict[str, Any]] = payload["messages"]
        tool_msgs = [m for m in msgs if m.get("role") == "tool"]
        sys_prompt = next((str(m.get("content") or "") for m in msgs if m.get("role") == "system"), "")

        if model == "m-coder":
            if not tool_msgs:
                return openai_body(call("task", {"agent": "explore", "prompt": "find the bug"}, "t1"))
            soul = "loaded" if "You are the CODER" in sys_prompt else "missing"
            return openai_body({"content": f'fixed using: {tool_msgs[0]["content"]} [soul:{soul}]'})
        if model == "m-explore":
            if not tool_msgs:
                return openai_body(call("lookup", {"q": "bug"}, "e1"))
            return openai_body({"content": f'bug at line 42 ({tool_msgs[0]["content"]})'})
        if model == "m-mia":
            # persona: soul sections visible? heartbeat with nothing to do → HEARTBEAT_OK
            last = str(msgs[-1].get("content") or "") if msgs else ""
            if "Heartbeat" in last:
                has_ticks = "channel=timer" in last
                if "water the plants" in sys_prompt and has_ticks:
                    return openai_body({"content": "Reminder: water the plants! 🌱"})
                return openai_body({"content": HEARTBEAT_OK})
            found = [f for f in ("SOUL.md", "USER.md", "MEMORY.md") if f"## {f}" in sys_prompt]
            return openai_body({"content": f'soul-sections:[{",".join(found)}]'})
        if model == "m-old-api":
            if not tool_msgs:
                return openai_body(call("explore", {"prompt": "scan the repo"}, "b1"))
            return openai_body({"content": f'old-api summary: {tool_msgs[0]["content"]}'})
        return openai_body({"content": "ok"})

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):  # noqa: A003
        raise NotImplementedError("mock is non-streaming")


async def _lookup_exec(args: Optional[dict[str, Any]] = None, ctx: Any = None) -> ToolResult:
    return ToolResult(output=f'data({(args or {}).get("q")})', is_error=False)


lookup = Tool(
    name="lookup",
    description="look something up",
    input_schema={"type": "object", "properties": {"q": {"type": "string"}}},
    source="native",
    execute=_lookup_exec,
)

passed = 0
failed = 0


def check(n: str, c: bool, d: str = "") -> None:
    global passed, failed
    if c:
        passed += 1
        print(f"  ✅ {n} ")
    else:
        failed += 1
        print(f"  ❌ {n} {d}")


def section(s: str) -> None:
    print(f"\n━━ {s}")


async def main() -> None:
    # S10 — Level 1: the 12-line coding agent -------------------------------
    section("S10 Level-1 UX: agent() + team wiring + soul file")
    if True:
        soul_path = os.path.join(tempfile.gettempdir(), "AGENTS-spike.md")
        with open(soul_path, "w", encoding="utf-8") as f:
            f.write("You are the CODER. Fix things surgically.")
        explore = agent("explore", does="read-only research", uses={"tools": [lookup]}, model="m-explore")
        coder = agent(
            "coder",
            does="implements changes",
            soul_file=soul_path,
            team=[explore],
            model="m-coder",
            budget=Budget(max_tokens=10_000),
        )
        r = await coder.run({"transport": MockTransport()}, "fix the failing test")
        check("coding agent completes via its team", r.status == "done" and "bug at line 42" in r.text, r.text)
        check("soul FILE reached the system prompt", "[soul:loaded]" in r.text, r.text)

    # S11 — team scoping: task cannot reach outside the declared team ---------
    section("S11 team scoping: task targets = the team, nothing else")
    if True:
        stranger = agent("stranger", does="should be unreachable", model="m-explore", uses={"tools": [lookup]})
        explore = agent("explore", does="read-only research", uses={"tools": [lookup]}, model="m-explore")
        coder = agent("coder", does="implements", team=[explore], model="m-coder")
        _ = stranger
        reg = coder.registry()
        check("registry contains only the reachable graph", ",".join(sorted(reg)) == "coder,explore", ",".join(reg))
        check("coder's task targets are exactly its team", json.dumps(reg["coder"].task_targets) == '["explore"]')

    # S12 — Level 2: agentFromDir + heartbeat + HEARTBEAT_OK silence ----------
    section("S12 Level-2 UX: the directory IS the agent · heartbeat · silent OK")
    if True:
        d = tempfile.mkdtemp(prefix="mia-")
        with open(os.path.join(d, "SOUL.md"), "w", encoding="utf-8") as f:
            f.write("You are Mia. Warm, brief.")
        with open(os.path.join(d, "USER.md"), "w", encoding="utf-8") as f:
            f.write("The user is Muthu.")
        with open(os.path.join(d, "MEMORY.md"), "w", encoding="utf-8") as f:
            f.write("- Likes green tea.")
        with open(os.path.join(d, "HEARTBEAT.md"), "w", encoding="utf-8") as f:
            f.write("On heartbeat: if it is watering day, remind to water the plants.")
        mia = agent_from_dir(d, model="m-mia")
        direct = await mia.run({"transport": MockTransport()}, "hello")
        check(
            "bootstrap files discovered + injected as ## sections (openclaw order)",
            direct.text == "soul-sections:[SOUL.md,USER.md,MEMORY.md]",
            direct.text,
        )

        reports: list[str] = []
        started = start_agent(mia, {"transport": MockTransport()}, every_ms=25, on_report=reports.append)
        await asyncio.sleep(0.14)
        await started.stop()
        hb_turns = sum(1 for l in started.rt.trace if "idle→running" in l)
        check("heartbeat woke the agent repeatedly", hb_turns >= 2, f"turns={hb_turns}")
        check(
            "HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats)",
            all("water" in t for t in reports),
            json.dumps(reports, ensure_ascii=False),
        )
        check("agent closed cleanly on stop()", started.handle.state == "closed")

    # S13 — the bridge: agent.asTool() inside the OLD API ---------------------
    section("S13 bridge: an Agent IS a Tool in the classic createToolkit/createClient API")
    if True:
        explore = agent("explore", does="read-only research", uses={"tools": [lookup]}, model="m-explore")
        toolkit = await create_toolkit(builtins=False, extra_tools=[explore.as_tool({"transport": MockTransport()})])
        client = create_client(
            base_url="http://mock.local",
            style="openai",
            model="m-old-api",
            api_key="spike",
            http_transport=MockTransport(),
        )
        r = await client.run("summarize", toolkit)
        check(
            "old API called the agent like any tool",
            "old-api summary:" in r.text and "bug at line 42" in r.text,
            r.text,
        )
        meta = (r.tool_calls[0].get("metadata") or {}) if r.tool_calls else {}
        check("agent metadata surfaced through the tool result", meta.get("agent") == "explore")

    print("\n" + "═" * 60)
    print(f'  {passed} passed · {failed} failed  {"— UX LAYER PASSES ✅" if failed == 0 else "❌"}')
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
