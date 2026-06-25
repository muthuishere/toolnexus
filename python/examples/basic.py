"""Minimal end-to-end example. Mirrors js/examples/basic.ts.

Run from a venv where the package is installed (`uv pip install -e .`):
    python examples/basic.py

Uses the shared sample fixtures in ../../examples/. Requires `npx` on PATH for
the `@modelcontextprotocol/server-everything` stdio server.
"""
from __future__ import annotations

import asyncio
import json
import os

from toolnexus import create_toolkit

_HERE = os.path.dirname(os.path.abspath(__file__))
_EXAMPLES = os.path.normpath(os.path.join(_HERE, "..", "..", "examples"))


async def main() -> None:
    tk = await create_toolkit(
        mcp_config=os.path.join(_EXAMPLES, "mcp.json"),
        skills_dir=os.path.join(_EXAMPLES, "skills"),
    )

    print("MCP status:", tk.mcp_status())
    print("Tools:", [f"{t.name} ({t.source})" for t in tk.tools()])
    print("\nSystem-prompt skill catalog:\n" + tk.skills_prompt())

    print("\nOpenAI tool schema (first 2):")
    print(json.dumps(tk.to_openai()[:2], indent=2))

    # Load a skill (progressive disclosure) — works with no MCP servers running.
    res = await tk.execute("skill", {"name": "hello-world"})
    print("\nskill(hello-world) ->\n" + res.output)

    await tk.close()


if __name__ == "__main__":
    asyncio.run(main())
