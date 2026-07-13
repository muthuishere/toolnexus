#!/usr/bin/env python3
"""toolnexus (Python port) benchmark runner.

Measures, against the shared mock LLM + shared stdio MCP server:
  * cold init  = build toolkit (spawn MCP, discover tools) + build client
  * per-request wall time for the fixed scenario, N runs
Prints a JSON result line to stdout. Peak RSS is captured by the orchestrator
(`/usr/bin/time -l`) wrapping this process.

Config selected by --config:
  mcp   : only the 3 MCP tools (builtins off)          -> apples-to-apples cell
  full  : 3 MCP tools + 1 agent skill + 1 native tool  -> toolnexus's real shape
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

from toolnexus import create_toolkit, create_client, define_tool

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"

os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")


def mcp_config():
    return {
        "mcpServers": {
            "bench-tools": {
                "type": "local",
                "command": [MCP_PY, f"{REPO}/benchmarks/mcp_server.py"],
                "enabled": True,
                "timeout": 30000,
            }
        }
    }


def local_multiply(a: int, b: int) -> str:
    """Multiply two integers locally (native tool)."""
    return str(a * b)


async def build(config: str):
    if config == "full":
        native = define_tool(local_multiply, name="multiply",
                             description="Multiply two integers locally.")
        tk = await create_toolkit(
            mcp_config=mcp_config(),
            skills_dir=f"{REPO}/examples/skills",
            extra_tools=[native],
            builtins=False,
        )
    else:
        tk = await create_toolkit(mcp_config=mcp_config(), builtins=False)
    return tk


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", choices=["mcp", "full"], default="mcp")
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    # ---- cold init: toolkit build (MCP connect + discovery) + client ----
    t0 = time.perf_counter()
    tk = await build(args.config)
    agent = create_client(base_url=MOCK_URL, style="openai", model="mock-model")
    init_ms = (time.perf_counter() - t0) * 1000.0

    tool_count = len(tk.tools())

    # ---- warmup ----
    for _ in range(args.warmup):
        await agent.run(QUESTION, tk)

    # ---- measured runs ----
    lat = []
    final_text = ""
    for _ in range(args.runs):
        s = time.perf_counter()
        res = await agent.run(QUESTION, tk)
        lat.append((time.perf_counter() - s) * 1000.0)
        final_text = res.text

    await tk.close()

    print(json.dumps({
        "framework": f"toolnexus-python-{args.config}",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": tool_count,
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    asyncio.run(main())
