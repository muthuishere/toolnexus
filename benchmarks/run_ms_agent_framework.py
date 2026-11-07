#!/usr/bin/env python3
"""Microsoft Agent Framework (python `agent-framework`) runner. Mock LLM only, ZERO cost.

Same mock LLM + same shared stdio MCP server. MCP via the framework's native
`MCPStdioTool` (ONE persistent session, entered once). Model is
`OpenAIChatCompletionClient` (Chat Completions; `OpenAIChatClient` is the
Responses API, which the mock does not speak) pointed at the mock via base_url.

  cold init   = MCPStdioTool enter (spawn + initialize + tools/list) + client + Agent
  per-request = agent.run(QUESTION)
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")

from agent_framework import Agent, MCPStdioTool
from agent_framework.openai import OpenAIChatCompletionClient

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    t0 = time.perf_counter()
    mcp = MCPStdioTool(name="bench", command=MCP_PY, args=[f"{REPO}/benchmarks/mcp_server.py"],
                       load_prompts=False)
    await mcp.__aenter__()
    tool_count = len(mcp.functions)
    client = OpenAIChatCompletionClient(model="mock-model", base_url=MOCK_URL, api_key="sk-mock-not-a-real-key")
    agent = Agent(client=client, instructions="You are a helpful assistant.", tools=[mcp])
    init_ms = (time.perf_counter() - t0) * 1000.0

    try:
        for _ in range(args.warmup):
            await agent.run(QUESTION)
        lat = []
        final_text = ""
        for _ in range(args.runs):
            s = time.perf_counter()
            res = await agent.run(QUESTION)
            lat.append((time.perf_counter() - s) * 1000.0)
            final_text = res.text or ""
    finally:
        await mcp.__aexit__(None, None, None)

    print(json.dumps({
        "framework": "ms-agent-framework",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": tool_count,
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    asyncio.run(main())
