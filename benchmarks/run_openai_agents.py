#!/usr/bin/env python3
"""OpenAI Agents SDK benchmark runner (vs toolnexus-python).

Same mock LLM + same shared stdio MCP server. Uses the Agents SDK's native MCP
client (`MCPServerStdio`) and its OpenAI Chat Completions model pointed at the
mock via a custom `AsyncOpenAI` client (base_url). ONE persistent MCP session
is held open across every request.

  cold init = MCPServerStdio.connect() (spawn MCP + connect + discover)
              + AsyncOpenAI + OpenAIChatCompletionsModel + Agent
  per-request = Runner.run(agent, QUESTION)
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")

from agents import Agent, Runner, ModelSettings, set_tracing_disabled
from agents.mcp import MCPServerStdio
from agents.models.openai_chatcompletions import OpenAIChatCompletionsModel
from openai import AsyncOpenAI

set_tracing_disabled(True)  # no OpenAI tracing backend calls

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"


def _mk_model():
    client = AsyncOpenAI(base_url=MOCK_URL, api_key="sk-mock-not-a-real-key")
    return OpenAIChatCompletionsModel(model="mock-model", openai_client=client)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    # ---- cold init: spawn MCP + connect + discover + build agent ----
    t0 = time.perf_counter()
    server = MCPServerStdio(
        params={"command": MCP_PY, "args": [f"{REPO}/benchmarks/mcp_server.py"]},
        cache_tools_list=True,
        client_session_timeout_seconds=30,
    )
    await server.connect()  # spawn MCP + initialize the persistent session
    tools = await server.list_tools()  # tools/list over the live session
    tool_count = len(tools)
    agent = Agent(
        name="Assistant",
        instructions="You are a helpful assistant.",
        model=_mk_model(),
        mcp_servers=[server],
        model_settings=ModelSettings(temperature=0),
    )
    init_ms = (time.perf_counter() - t0) * 1000.0

    try:
        # ---- warmup ----
        for _ in range(args.warmup):
            await Runner.run(agent, QUESTION)

        # ---- measured runs ----
        lat = []
        final_text = ""
        for _ in range(args.runs):
            s = time.perf_counter()
            res = await Runner.run(agent, QUESTION)
            lat.append((time.perf_counter() - s) * 1000.0)
            final_text = res.final_output
    finally:
        await server.cleanup()

    print(json.dumps({
        "framework": "openai-agents-python",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": tool_count,
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    asyncio.run(main())
