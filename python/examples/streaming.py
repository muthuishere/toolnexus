"""Streaming: live text deltas + tool_call / tool_result / usage / done events.

Mirrors js/examples/streaming.ts. Reads OPENROUTER_API_KEY from the environment
(never hardcoded, never printed); skips the live call if it is unset.

Run from a venv where the package is installed:
    OPENROUTER_API_KEY=... python examples/streaming.py
"""
from __future__ import annotations

import asyncio
import json
import os
import sys

from toolnexus import create_client, create_toolkit, define_tool


async def main() -> None:
    tk = await create_toolkit()

    def add(a: float, b: float) -> str:
        """Add two numbers and return the sum."""
        return str(int(a) + int(b)) if float(a).is_integer() and float(b).is_integer() else str(a + b)

    tk.register(
        define_tool(
            add,
            name="add",
            description="Add two numbers.",
            input_schema={
                "type": "object",
                "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                "required": ["a", "b"],
            },
        )
    )

    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        print("no OPENROUTER_API_KEY — skipping (imports OK)")
        await tk.close()
        return

    agent = create_client(
        base_url="https://openrouter.ai/api/v1",
        style="openai",
        model=os.environ.get("OPENROUTER_MODEL", "openai/gpt-4o-mini"),
        api_key=key,
    )

    text_deltas = 0
    streamed_text = ""
    tool_call_ev = 0
    tool_result_ev = 0
    done = None

    sys.stdout.write("stream: ")
    sys.stdout.flush()
    async for ev in agent.stream(
        "Use the add tool to compute 21 + 21, then say the result in one short sentence.",
        tk,
    ):
        t = ev["type"]
        if t == "text":
            text_deltas += 1
            streamed_text += ev["delta"]
            sys.stdout.write(ev["delta"])
            sys.stdout.flush()
        elif t == "tool_call":
            tool_call_ev += 1
            print(f"\n[tool_call] {ev['name']}({json.dumps(ev['args'])})")
        elif t == "tool_result":
            tool_result_ev += 1
            print(f"[tool_result] {ev['name']} -> {ev['output']}")
        elif t == "usage":
            print(f"[usage] {json.dumps(ev['usage'])}")
        elif t == "done":
            done = ev["result"]

    await tk.close()

    print(
        f"\n\nsummary: text_deltas={text_deltas} tool_call={tool_call_ev} "
        f"tool_result={tool_result_ev} | done.tool_call_count="
        f"{getattr(done, 'tool_call_count', None)} turns={getattr(done, 'turns', None)} "
        f"usage={json.dumps(getattr(done, 'usage', {}))}"
    )
    ok = (
        text_deltas > 1
        and tool_call_ev >= 1
        and tool_result_ev >= 1
        and done is not None
        and done.usage["total_tokens"] > 0
        and "42" in done.text
    )
    if not ok:
        print("FAILED streaming did not produce deltas + tool events + final usage/answer")
        raise SystemExit(1)
    print("OK streaming verified: incremental text deltas, tool_call/tool_result events, final done with usage")


if __name__ == "__main__":
    asyncio.run(main())
