"""Verify parallel tool calling (many calls in one turn) and chained tool calling
(a later call depends on an earlier result, across turns). Mirrors
js/examples/advanced.ts.

Run from a venv where the package is installed (`uv pip install -e .`):
    python examples/advanced.py                          # skips live section, exits 0
    OPENROUTER_API_KEY=... python examples/advanced.py   # runs the live checks

The API key is read from the environment and is NEVER printed.
"""
from __future__ import annotations

import asyncio
import json
import os
from typing import Any

from toolnexus import create_client, create_toolkit, define_tool, http_tool


def _max_parallel(messages: list[dict[str, Any]]) -> int:
    """Largest number of tool calls the model emitted in a single assistant turn."""
    m = 0
    for msg in messages:
        if msg.get("role") == "assistant" and isinstance(msg.get("tool_calls"), list):
            m = max(m, len(msg["tool_calls"]))
    return m


def _tool_turns(messages: list[dict[str, Any]]) -> int:
    """Number of assistant turns that issued tool calls (chain depth)."""
    return sum(
        1
        for m in messages
        if m.get("role") == "assistant"
        and isinstance(m.get("tool_calls"), list)
        and len(m["tool_calls"])
    )


async def main() -> None:
    tk = await create_toolkit({})

    # a native (annotation) tool
    def add(a: float, b: float) -> str:
        """Add two numbers and return the sum."""
        return str(a + b)

    tk.register(define_tool(add, name="add"))

    # an HTTP tool (public test API, no auth)
    tk.register(
        http_tool(
            name="get_post",
            description="Fetch a placeholder blog post by id.",
            method="GET",
            url="https://jsonplaceholder.typicode.com/posts/{id}",
            input_schema={
                "type": "object",
                "properties": {"id": {"type": "number"}},
                "required": ["id"],
                "additionalProperties": False,
            },
        )
    )

    # opaque: the model CANNOT guess this value, so it must call it first and wait
    def todays_post_id() -> str:
        """Returns the server-chosen blog post id to read today. Cannot be guessed; you must call it."""
        return "3"

    tk.register(define_tool(todays_post_id, name="todays_post_id"))

    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        print("no OPENROUTER_API_KEY — skipping")
        await tk.close()
        return

    agent = create_client(
        base_url="https://openrouter.ai/api/v1",
        style="openai",
        model=os.environ.get("OPENROUTER_MODEL", "openai/gpt-4o-mini"),
        api_key=key,
        system_prompt="You are a precise agent. Prefer issuing independent tool calls together in one step.",
    )

    # ---- A) PARALLEL: two independent adds, ideally in one turn ----
    a = await agent.run(
        "In a single step, call the add tool twice: add 2 and 3, and add 100 and 200. Then report both sums.",
        tk,
    )
    print(
        "A) parallel — tool calls:",
        [f"{c['name']}({json.dumps(c['args'])})={c['output']}" for c in a.tool_calls],
    )
    print(
        "A) max calls in one turn:",
        _max_parallel(a.messages),
        "| answer:",
        a.text.replace("\n", " ")[:60],
    )
    print(
        "A) usage:",
        a.usage,
        "| tool_call_count:",
        a.tool_call_count,
        "| turns:",
        a.turns,
        "| model:",
        a.model,
    )
    print("A) first tool result metadata:", a.tool_calls[0]["metadata"] if a.tool_calls else None)

    # ---- B) CHAIN: second call depends on an OPAQUE first result (forces a 2nd turn) ----
    b = await agent.run(
        "Call todays_post_id to find which post to read, then use get_post to fetch that exact id and tell me its title.",
        tk,
    )
    print(
        "\nB) chain — tool calls:",
        [f"{c['name']}({json.dumps(c['args'])})" for c in b.tool_calls],
    )
    print("B) tool-calling turns (chain depth):", _tool_turns(b.messages))
    print("B) answer:", b.text.replace("\n", " ")[:100])

    await tk.close()

    # ---- assertions ----
    parallel_ok = _max_parallel(a.messages) >= 2
    called_add = sum(1 for c in a.tool_calls if c["name"] == "add") >= 2
    # true chain: todays_post_id THEN get_post(id=3) across ≥2 turns (id=3 is unguessable)
    chain_ok = (
        _tool_turns(b.messages) >= 2
        and any(c["name"] == "todays_post_id" for c in b.tool_calls)
        and any(
            c["name"] == "get_post" and int(c["args"].get("id", 0)) == 3
            for c in b.tool_calls
        )
    )
    print(
        f"\nparallel: {'✅ multiple calls in one turn' if parallel_ok else '⚠️ model serialized'}"
        f" | ran {sum(1 for c in a.tool_calls if c['name'] == 'add')} adds"
    )
    print(
        f"chain:    {'✅ get_post(id=3) used the opaque result across turns' if chain_ok else '❌ chain not observed'}"
    )
    if not called_add or not parallel_ok or not chain_ok:
        raise SystemExit(
            "❌ expected ≥2 parallel adds AND a real cross-turn chain "
            "(todays_post_id → get_post(id=3))"
        )
    print("\n✅ parallel + chained tool calling verified")


if __name__ == "__main__":
    asyncio.run(main())
