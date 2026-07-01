"""Minimal live agent: DeepSeek via OpenRouter + the built-in tools — no
mcp.json or skills folder needed.

This is the shortest "give my LLM real tools and let it act" path: the 10
built-in tools (bash, read, write, edit, grep, glob, webfetch, ...) are on by
default, so the model can actually do things (list files, read a file, fetch a
URL) with zero wiring.

Run from a venv where the package is installed (`pip install -e .`):
    OPENROUTER_API_KEY=... python examples/openrouter_deepseek.py

The key is read from OPENROUTER_API_KEY and never printed. Any OpenRouter model
id works — swap `deepseek/deepseek-chat` for another.
"""
from __future__ import annotations

import asyncio

from toolnexus import create_client, create_toolkit


async def main() -> None:
    # Built-in tools are on by default — nothing else to configure.
    tk = await create_toolkit()
    print("tools:", ", ".join(t.name for t in tk.tools()))

    agent = create_client(
        base_url="https://openrouter.ai/api/v1",
        style="openai",  # OpenRouter is OpenAI-compatible
        model="deepseek/deepseek-chat",
    )

    res = await agent.run(
        "List the files in the current directory, then tell me how many there are.",
        tk,
    )
    print("\n--- answer ---\n", res.text)
    print("\ntool calls:", [c["name"] for c in res.tool_calls])
    print("usage:", res.usage)

    await tk.close()


if __name__ == "__main__":
    asyncio.run(main())
