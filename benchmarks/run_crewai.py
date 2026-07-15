#!/usr/bin/env python3
"""CrewAI benchmark runner (vs toolnexus-python).

Same mock LLM + same shared stdio MCP server. Uses CrewAI's documented MCP
integration (`crewai_tools.MCPServerAdapter` over stdio) and CrewAI's `LLM`
(LiteLLM) pointed at the mock `base_url`. ONE persistent MCP session (the
adapter) is held open across every request.

  cold init = MCPServerAdapter.start() (spawn MCP + connect + discover)
              + LLM + Agent + Task + Crew
  per-request = crew.kickoff()
"""
from __future__ import annotations

import argparse
import json
import os
import time

os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")
# keep CrewAI quiet / offline
os.environ.setdefault("CREWAI_DISABLE_TELEMETRY", "true")
os.environ.setdefault("OTEL_SDK_DISABLED", "true")

from crewai import Agent, Task, Crew, LLM, Process
from crewai_tools import MCPServerAdapter
from mcp import StdioServerParameters

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    server_params = StdioServerParameters(
        command=MCP_PY, args=[f"{REPO}/benchmarks/mcp_server.py"])

    # ---- cold init: spawn MCP + connect + discover + build crew ----
    t0 = time.perf_counter()
    adapter = MCPServerAdapter(server_params)  # spawns MCP + discovers on init
    tools = adapter.tools  # discovered MCP tools; the session stays held open
    tool_count = len(tools)

    # Model name is "gpt-4o-mini" only so LiteLLM advertises native OpenAI
    # function-calling (which the mock speaks); the mock echoes the name and its
    # behavior is model-agnostic. No real OpenAI endpoint is contacted -
    # base_url points at the local mock.
    llm = LLM(model="gpt-4o-mini", base_url=MOCK_URL,
              api_key="sk-mock-not-a-real-key", temperature=0)
    agent = Agent(
        role="Assistant",
        goal="Answer the user's question using the available tools.",
        backstory="A helpful assistant with weather and math tools.",
        tools=tools,
        llm=llm,
        verbose=False,
        max_iter=5,
    )
    task = Task(
        description=QUESTION,
        expected_output="A short sentence with the weather and the sum.",
        agent=agent,
    )
    crew = Crew(agents=[agent], tasks=[task], process=Process.sequential,
                verbose=False, memory=False, telemetry=False)
    init_ms = (time.perf_counter() - t0) * 1000.0

    try:
        # ---- warmup ----
        for _ in range(args.warmup):
            crew.kickoff()

        # ---- measured runs ----
        lat = []
        final_text = ""
        for _ in range(args.runs):
            s = time.perf_counter()
            res = crew.kickoff()
            lat.append((time.perf_counter() - s) * 1000.0)
            final_text = str(res)
    finally:
        adapter.stop()

    print(json.dumps({
        "framework": "crewai-python",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": tool_count,
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    main()
