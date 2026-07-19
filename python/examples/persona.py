"""Persona agent (SPEC §7E): the directory IS the agent. Mirrors js/examples/persona.ts.

`agent_from_dir` composes a folder of bootstrap files (SOUL/USER/HEARTBEAT/MEMORY.md)
into a frozen soul and wires a file-backed `memory` tool, so Ava can edit her own durable
memory. This proves the two things that make a persona different from a one-shot worker:

  1. a memory write lands on DISK (not just in the transcript), and
  2. it loads at the START of the NEXT session (frozen-snapshot — the cache-stable rule).

The committed bootstrap dir (examples/persona-agent/ava) is copied to a writable temp dir
first, so a run never dirties the repo — the real pattern for a sandboxed host.

Run from a venv where the package is installed (`pip install -e .`):
    OPENROUTER_API_KEY=... python examples/persona.py
"""
from __future__ import annotations

import asyncio
import os
import re
import shutil
import tempfile

from toolnexus.agents import agent_from_dir

_HERE = os.path.dirname(os.path.abspath(__file__))
_AVA = os.path.normpath(os.path.join(_HERE, "..", "..", "examples", "persona-agent", "ava"))


async def main() -> None:
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        print("no OPENROUTER_API_KEY — skipping live persona loop")
        return

    llm = {
        "base_url": "https://openrouter.ai/api/v1",
        "style": "openai",
        "model": os.environ.get("OPENROUTER_MODEL", "openai/gpt-4o-mini"),
        "api_key": key,
    }

    # Copy the committed bootstrap dir to a writable temp home so the run never dirties the repo.
    home = tempfile.mkdtemp(prefix="ava-")
    shutil.copytree(_AVA, home, dirs_exist_ok=True)

    # Session 1 — Ava learns a stable preference and records it with her memory tool.
    ava = agent_from_dir(home, name="ava")
    s1 = await ava.run(
        "I take my coffee as a dark roast, no sugar. This is a stable preference — "
        "call the memory tool now (action=add, target=user) to save it to my profile, "
        "then reply 'noted'.",
        llm=llm,
    )
    print("session 1:", s1.status, "·", s1.text.replace("\n", " ")[:80])

    with open(os.path.join(home, "USER.md"), encoding="utf-8") as f:
        persisted = bool(re.search("dark roast", f.read(), re.I))
    print("USER.md on disk now contains 'dark roast':", persisted)

    # Session 2 — a FRESH agent_from_dir re-reads the folder: the snapshot now carries the write.
    ava2 = agent_from_dir(home, name="ava")
    s2 = await ava2.run("How do I take my coffee? Answer from what you already know.", llm=llm)
    print("session 2:", s2.status, "·", s2.text.replace("\n", " ")[:80])

    shutil.rmtree(home, ignore_errors=True)

    recalled = bool(re.search("dark roast", s2.text, re.I))
    if not persisted or not recalled:
        raise SystemExit("persona did not persist + recall memory across sessions")
    print("\nPersona memory verified — written to disk in session 1, recalled in session 2")


if __name__ == "__main__":
    asyncio.run(main())
