#!/usr/bin/env python3
"""Google ADK benchmark runner (vs toolnexus).

Same mock LLM (via LiteLlm openai-compatible) + same shared stdio MCP server
(via McpToolset). A fresh session per measured run so each run performs the full
one-tool-calling-turn scenario.

  cold init = McpToolset (spawn MCP + discover) + LiteLlm + LlmAgent + Runner
  per-request = create session + runner.run_async(...) to completion
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")

from google.adk.agents import LlmAgent
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.adk.tools.mcp_tool.mcp_toolset import McpToolset
from google.adk.tools.mcp_tool.mcp_session_manager import StdioConnectionParams
from google.genai import types
from mcp import StdioServerParameters

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"
APP = "bench"
USER = "u"


async def run_once(runner, session_service):
    session = await session_service.create_session(app_name=APP, user_id=USER)
    content = types.Content(role="user", parts=[types.Part(text=QUESTION)])
    final_text = ""
    async for event in runner.run_async(user_id=USER, session_id=session.id,
                                         new_message=content):
        if event.content and event.content.parts:
            for p in event.content.parts:
                if getattr(p, "text", None):
                    final_text = p.text
    return final_text


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    t0 = time.perf_counter()
    toolset = McpToolset(
        connection_params=StdioConnectionParams(
            server_params=StdioServerParameters(
                command=MCP_PY, args=[f"{REPO}/benchmarks/mcp_server.py"]),
        )
    )
    model = LiteLlm(model="openai/mock-model", api_base=MOCK_URL,
                    api_key="sk-mock-not-a-real-key")
    agent = LlmAgent(name="bench_agent", model=model, instruction="You are a helpful assistant.",
                     tools=[toolset])
    session_service = InMemorySessionService()
    runner = Runner(app_name=APP, agent=agent, session_service=session_service)
    # force tool discovery now (spawn MCP + list tools) so init cost is captured
    tools = await toolset.get_tools()
    init_ms = (time.perf_counter() - t0) * 1000.0

    for _ in range(args.warmup):
        await run_once(runner, session_service)

    lat = []
    final_text = ""
    for _ in range(args.runs):
        s = time.perf_counter()
        final_text = await run_once(runner, session_service)
        lat.append((time.perf_counter() - s) * 1000.0)

    await toolset.close()

    print(json.dumps({
        "framework": "google-adk-python",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": len(tools),
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    asyncio.run(main())
