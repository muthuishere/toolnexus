"""Lifecycle hooks: before_llm / after_llm / before_tool / after_tool — observe,
mutate, and short-circuit. Mirrors js/examples/hooks.ts.

Run from a venv where the package is installed (`uv pip install -e .`):
    python examples/hooks.py                       # import + setup only (no key)
    OPENROUTER_API_KEY=... python examples/hooks.py   # runs the live loop

The API key is read from the environment and is NEVER printed.
"""
from __future__ import annotations

import asyncio
import json
import os

from toolnexus import ToolResult, create_client, create_toolkit, define_tool, http_tool


async def main() -> None:
    tk = await create_toolkit()

    def add(a: float, b: float) -> str:
        """Add two numbers and return the sum."""
        return str(a + b)

    tk.register(
        define_tool(add, name="add"),
        http_tool(
            name="get_post",
            description="Fetch a placeholder blog post by id.",
            method="GET",
            url="https://jsonplaceholder.typicode.com/posts/{id}",
            input_schema={
                "type": "object",
                "properties": {"id": {"type": "number", "description": "post id 1-100"}},
                "required": ["id"],
                "additionalProperties": False,
            },
        ),
    )

    # Spy on whether get_post's real execute ever runs (it must NOT — we deny it
    # in a before_tool hook before it can reach the network).
    real = tk.get("get_post")
    assert real is not None
    http_hits = {"n": 0}
    orig = real.execute

    async def spy(args, ctx=None):
        http_hits["n"] += 1
        return await orig(args, ctx)

    real.execute = spy  # type: ignore[assignment]

    counts = {"before_llm": 0, "after_llm": 0, "before_tool": 0, "after_tool": 0, "denied": 0}

    # Hooks as a plain dict of snake_case callables (sync here; async is also fine).
    def before_llm(ev):
        counts["before_llm"] += 1
        print(f"  [before_llm] turn {ev['turn']}")

    def after_llm(ev):
        counts["after_llm"] += 1
        print(f"  [after_llm] turn {ev['turn']} usage {ev['response'].get('usage')}")

    def before_tool(ev):
        counts["before_tool"] += 1
        print(f"  [before_tool] {ev['name']}({json.dumps(ev['args'])})")
        if ev["name"] == "get_post":
            counts["denied"] += 1
            # SHORT-CIRCUIT: deny the tool. The model never sees real data and the
            # real http execute never runs.
            return {"result": ToolResult(output="DENIED: get_post is blocked by policy", is_error=True)}

    def after_tool(ev):
        counts["after_tool"] += 1
        print(f"  [after_tool] {ev['name']} -> {ev['result'].output[:40]}")

    hooks = {
        "before_llm": before_llm,
        "after_llm": after_llm,
        "before_tool": before_tool,
        "after_tool": after_tool,
    }

    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        print("(no OPENROUTER_API_KEY — import + setup OK, skipping live loop)")
        await tk.close()
        return

    agent = create_client(
        base_url="https://openrouter.ai/api/v1",
        style="openai",
        model=os.environ.get("OPENROUTER_MODEL", "openai/gpt-4o-mini"),
        api_key=key,
        system_prompt="You are an agent. Use tools when asked.",
        hooks=hooks,
    )

    out = await agent.run(
        "Add 2 and 3 with the add tool, then fetch post id 1 with get_post.", tk
    )
    await tk.close()

    print("\nFINAL:", out.text.replace("\n", " ")[:100])
    print(
        "hook counts:", counts,
        "| http actually hit:", http_hits["n"],
        "| usage:", out.usage,
    )

    ok = (
        counts["before_llm"] >= 1
        and counts["after_llm"] >= 1
        and counts["before_tool"] >= 2
        and counts["after_tool"] >= 1
        and counts["denied"] >= 1
        and http_hits["n"] == 0  # deny short-circuited the real http execute
    )
    if not ok:
        raise SystemExit("hooks did not all fire / short-circuit failed")
    print("\nall four hooks fired; before_tool short-circuited get_post (0 real http hits)")


if __name__ == "__main__":
    asyncio.run(main())
