"""Full agent example: MCP tools + skills + a native tool + an HTTP tool, driven
by the unified client host loop. Mirrors js/examples/agent.ts.

Run from a venv where the package is installed (`uv pip install -e .`):
    python examples/agent.py                     # tools list only, exits cleanly
    OPENROUTER_API_KEY=... python examples/agent.py   # runs the live loop

Requires `npx` on PATH for the @modelcontextprotocol/server-everything stdio
server. The API key is read from the environment and never printed.
"""
from __future__ import annotations

import asyncio
import os

from toolnexus import create_client, create_toolkit, define_tool, http_tool

_HERE = os.path.dirname(os.path.abspath(__file__))
_EXAMPLES = os.path.normpath(os.path.join(_HERE, "..", "..", "examples"))


async def main() -> None:
    tk = await create_toolkit(
        mcp_config=os.path.join(_EXAMPLES, "mcp.json"),
        skills_dir=os.path.join(_EXAMPLES, "skills"),
    )

    # a native (annotation) tool
    def add(a: float, b: float) -> str:
        """Add two numbers and return the sum."""
        return str(a + b)

    tk.register(define_tool(add, name="add"))

    # an HTTP tool (public test API, no auth)
    tk.register(
        http_tool(
            name="get_post",
            description="Fetch a placeholder blog post by id from jsonplaceholder.",
            method="GET",
            url="https://jsonplaceholder.typicode.com/posts/{id}",
            input_schema={
                "type": "object",
                "properties": {"id": {"type": "number", "description": "post id 1-100"}},
                "required": ["id"],
                "additionalProperties": False,
            },
        )
    )

    print("Tools:", [f"{t.name} ({t.source})" for t in tk.tools()])

    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        print("\n(no OPENROUTER_API_KEY — skipping live loop)")
        await tk.close()
        return

    agent = create_client(
        base_url="https://openrouter.ai/api/v1",
        style="openai",
        model=os.environ.get("OPENROUTER_MODEL", "openai/gpt-4o-mini"),
        api_key=key,
        system_prompt="You are a precise agent. Use tools to compute and fetch facts.",
    )

    res = await agent.run(
        "What is 21 + 21? Use the add tool. Then fetch post id 1 and tell me its title.",
        tk,
    )
    print("\nTool calls:", [c["name"] for c in res.tool_calls])
    print("\nFINAL:\n" + res.text)

    await tk.close()
    if "42" not in res.text:
        raise SystemExit("expected 42 in answer")
    print("\nPython agent loop (native + http + mcp + skills) OK")


if __name__ == "__main__":
    asyncio.run(main())
