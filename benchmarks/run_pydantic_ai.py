#!/usr/bin/env python3
"""Pydantic AI benchmark runner (vs toolnexus-python).

Same mock LLM + same shared stdio MCP server. Uses Pydantic AI's native MCP
client (`MCPToolset` over a stdio transport) and its OpenAI-compatible chat
model pointed at the mock `base_url`. ONE persistent MCP session is held open
across every request (apples-to-apples with toolnexus).

  cold init = MCPToolset(StdioTransport) enter (spawn MCP + connect + discover)
              + OpenAIChatModel + Agent
  per-request = agent.run(QUESTION)
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")

from pydantic_ai import Agent
from pydantic_ai.mcp import MCPToolset, StdioTransport
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.providers.openai import OpenAIProvider

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"


def _mk_model():
    provider = OpenAIProvider(base_url=MOCK_URL, api_key="sk-mock-not-a-real-key")
    return OpenAIChatModel("mock-model", provider=provider)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    transport = StdioTransport(command=MCP_PY,
                               args=[f"{REPO}/benchmarks/mcp_server.py"])

    # ---- cold init: spawn MCP + connect + discover + build agent ----
    t0 = time.perf_counter()
    toolset = MCPToolset(transport, id="bench-tools")
    async with toolset:  # opens the persistent stdio MCP session
        agent = Agent(_mk_model(), toolsets=[toolset])
        tools = await toolset.list_tools()  # tools/list over the live session
        tool_count = len(tools)
        init_ms = (time.perf_counter() - t0) * 1000.0

        # ---- warmup ----
        for _ in range(args.warmup):
            await agent.run(QUESTION)

        # ---- measured runs ----
        lat = []
        final_text = ""
        for _ in range(args.runs):
            s = time.perf_counter()
            res = await agent.run(QUESTION)
            lat.append((time.perf_counter() - s) * 1000.0)
            final_text = res.output

    print(json.dumps({
        "framework": "pydantic-ai-python",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": tool_count,
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    asyncio.run(main())
