#!/usr/bin/env python3
"""LangGraph benchmark runner (vs toolnexus-python).

Same mock LLM + same shared stdio MCP server. Uses the documented LangGraph
MCP pattern: MultiServerMCPClient -> get_tools() -> create_react_agent over a
ChatOpenAI pointed at the mock base_url.

  cold init = MultiServerMCPClient + get_tools() (spawn MCP + discover)
              + ChatOpenAI + create_react_agent
  per-request = agent.ainvoke({"messages":[user]})
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

from langchain_mcp_adapters.client import MultiServerMCPClient
from langchain_mcp_adapters.tools import load_mcp_tools
from langchain_openai import ChatOpenAI
from langgraph.prebuilt import create_react_agent

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"
os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")


CONN = {
    "bench-tools": {
        "command": MCP_PY,
        "args": [f"{REPO}/benchmarks/mcp_server.py"],
        "transport": "stdio",
    }
}


def _mk_model():
    return ChatOpenAI(base_url=MOCK_URL, api_key="sk-mock-not-a-real-key",
                      model="mock-model", temperature=0)


async def _bench(agent, runs, warmup):
    payload = {"messages": [{"role": "user", "content": QUESTION}]}
    for _ in range(warmup):
        await agent.ainvoke(payload)
    lat, final_text = [], ""
    for _ in range(runs):
        s = time.perf_counter()
        res = await agent.ainvoke(payload)
        lat.append((time.perf_counter() - s) * 1000.0)
        final_text = res["messages"][-1].content
    return lat, final_text


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", choices=["default", "session"], default="default")
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    client = MultiServerMCPClient(CONN)

    if args.config == "session":
        # Persistent MCP session held open across every request (apples-to-apples
        # with toolnexus, which holds ONE persistent MCP session).
        t0 = time.perf_counter()
        async with client.session("bench-tools") as session:
            tools = await load_mcp_tools(session)
            agent = create_react_agent(_mk_model(), tools)
            init_ms = (time.perf_counter() - t0) * 1000.0
            lat, final_text = await _bench(agent, args.runs, args.warmup)
    else:
        # Documented default pattern: get_tools() (each tool CALL re-opens a fresh
        # stdio session -> re-spawns the MCP subprocess).
        t0 = time.perf_counter()
        tools = await client.get_tools()
        agent = create_react_agent(_mk_model(), tools)
        init_ms = (time.perf_counter() - t0) * 1000.0
        lat, final_text = await _bench(agent, args.runs, args.warmup)

    print(json.dumps({
        "framework": f"langgraph-python-{args.config}",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": len(tools),
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    asyncio.run(main())
