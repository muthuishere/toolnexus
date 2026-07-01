"""A2A (agent-to-agent), both directions, in one process — no network, no LLM.

1. Serve a toolkit as an A2A agent: its SKILL.md skills become the Agent Card at
   /.well-known/agent-card.json, and JSON-RPC SendMessage/GetTask are fulfilled by
   the client loop (here a tiny stub client so the example is hermetic).
2. Call it back with the outbound `agent(...)` factory — each advertised skill
   becomes a `<agent>_<skill>` tool that does submit -> poll.

Run from a venv where the package is installed (`pip install -e .`):
    python examples/a2a_agents.py

For a REAL served agent, pass a real `create_client(...)` instead of the stub so
the model actually fulfils incoming tasks.
"""
from __future__ import annotations

import asyncio
import os

from toolnexus import agent, create_toolkit

_HERE = os.path.dirname(os.path.abspath(__file__))
_SKILLS = os.path.normpath(os.path.join(_HERE, "..", "..", "examples", "skills"))


class StubClient:
    """Stand-in for create_client(...): answers every task with fixed text so the
    example runs with no API key. Swap for a real client to fulfil with an LLM."""

    async def run(self, prompt: str, toolkit):  # noqa: ANN001
        class R:
            text = f"handled: {prompt}"
        return R()


async def main() -> None:
    # --- inbound: serve this toolkit's skills as an A2A agent ---
    served = await create_toolkit(skills_dir=_SKILLS, builtins=False)
    handle = await served.serve(
        "127.0.0.1:0",
        client=StubClient(),
        a2a={"name": "hello-desk", "description": "demo A2A agent", "store": "memory"},
    )
    print("serving A2A agent at:", handle.url)

    # --- outbound: call it as a tool from another toolkit ---
    caller = await create_toolkit(
        agents=[agent(handle.url + "/.well-known/agent-card.json")],
        builtins=False,
    )
    tools = [t.name for t in caller.tools()]
    print("remote skills became tools:", tools)

    # execute one of the discovered agent tools (submit -> poll -> result)
    if tools:
        res = await caller.execute(tools[0], {"task": "say hello"})
        print(f"\n{tools[0]} -> {res.output!r}  (is_error={res.is_error})")

    await caller.close()
    await served.close()
    await handle.stop()


if __name__ == "__main__":
    asyncio.run(main())
