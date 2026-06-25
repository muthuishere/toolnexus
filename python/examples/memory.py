"""Conversation memory: a Conversation retains history across send() calls.

Mirrors js/examples/memory.ts. Reads OPENROUTER_API_KEY from the environment
(never hardcoded, never printed); skips the live call if it is unset.

Run from a venv where the package is installed:
    OPENROUTER_API_KEY=... python examples/memory.py
"""
from __future__ import annotations

import asyncio
import os
import re

from toolnexus import create_client, create_toolkit


async def main() -> None:
    tk = await create_toolkit()

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

    convo = agent.conversation(tk)

    a = await convo.send("My name is Muthu and my favorite number is 7. Reply with just 'noted'.")
    print("turn 1:", a.text.replace("\n", " ")[:60], "| messages:", len(convo.messages))

    b = await convo.send("What is my name and favorite number?")
    print("turn 2:", b.text.replace("\n", " ")[:80], "| messages:", len(convo.messages))

    await tk.close()
    remembered = bool(re.search(r"muthu", b.text, re.I)) and bool(re.search(r"7|seven", b.text, re.I))
    if not remembered:
        print("FAILED conversation did not retain memory across turns")
        raise SystemExit(1)
    print("\nOK conversation memory verified — turn 2 recalled facts from turn 1")


if __name__ == "__main__":
    asyncio.run(main())
