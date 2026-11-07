#!/usr/bin/env python3
"""smolagents benchmark runner (vs toolnexus-python). Mock LLM only, ZERO cost.

Same mock LLM + same shared stdio MCP server. smolagents' MCP path is
`ToolCollection.from_mcp` (mcpadapt under the hood), which holds ONE
persistent stdio session for the whole run. The agent is a
`ToolCallingAgent` over `OpenAIServerModel` (Chat Completions) pointed at the
mock via api_base.

  cold init   = ToolCollection.from_mcp(...) enter (spawn + initialize + tools/list)
                + OpenAIServerModel + ToolCallingAgent
  per-request = agent.run(QUESTION)  (fresh memory each run: reset=True default)

KNOWN INCOMPATIBILITY with the shared mock: smolagents sends tool results back
as role "user" ("tool-response" -> user in models.py), never role "tool", and a
ToolCallingAgent only terminates on a `final_answer` tool call. The mock keys
its final turn on a role "tool" message, so it keeps returning tool calls and
the agent hits max_steps. The runner exits non-zero instead of reporting numbers.
Deps: smolagents 1.26 + mcpadapt 0.1.19 need mcp<2 (mcpadapt imports
`streamablehttp_client`, removed in mcp 2.x) and `websockets`.
"""
from __future__ import annotations

import argparse
import json
import os
import time

os.environ.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")

from mcp import StdioServerParameters
from smolagents import OpenAIServerModel, ToolCallingAgent, ToolCollection

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
QUESTION = "What's the weather in Paris and what is 2+2?"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=3)
    args = ap.parse_args()

    params = StdioServerParameters(command=MCP_PY, args=[f"{REPO}/benchmarks/mcp_server.py"])
    t0 = time.perf_counter()
    with ToolCollection.from_mcp(params, trust_remote_code=True) as tc:
        tools = list(tc.tools)
        model = OpenAIServerModel(model_id="mock-model", api_base=MOCK_URL,
                                  api_key="sk-mock-not-a-real-key", temperature=0)
        agent = ToolCallingAgent(tools=tools, model=model, max_steps=6, verbosity_level=0)
        init_ms = (time.perf_counter() - t0) * 1000.0

        for _ in range(args.warmup):
            agent.run(QUESTION)
        lat = []
        final_text = ""
        for _ in range(args.runs):
            s = time.perf_counter()
            res = agent.run(QUESTION)
            lat.append((time.perf_counter() - s) * 1000.0)
            final_text = str(res)

    if not final_text.startswith("The weather in Paris"):
        # Known: smolagents maps tool results to role "user" (models.py
        # MessageRole.TOOL_RESPONSE -> USER) and only stops on a `final_answer`
        # tool call, so against the shared mock it loops to max_steps.
        raise SystemExit(f"smolagents: no valid final answer (got {final_text!r}); see docstring")

    print(json.dumps({
        "framework": "smolagents",
        "language": "python",
        "init_ms": round(init_ms, 2),
        "tool_count": len(tools),
        "latencies_ms": [round(x, 3) for x in lat],
        "final_text": final_text,
    }))


if __name__ == "__main__":
    main()
