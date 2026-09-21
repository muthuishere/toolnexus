"""ACP (Agent Client Protocol) as the model behind the client loop — issue #96,
ADR 0025 (openspec/changes/add-acp-model-source).

HONEST HEADER: the "warm session" win this example prints is the ACP agent
CLI's process-startup cost amortised across turns, NOT a protocol-level
speedup — session/prompt itself is not faster than any other wire. See
ADR 0025's measurements.

Spawns a real ACP agent CLI (devin or opencode), registers one trivial local
tool, and drives it through the ordinary toolnexus tool-calling loop for two
turns — proving MCP, skills and native tools work unchanged when the model
behind the loop is an ACP agent instead of an HTTP LLM. Requires the agent
CLI installed and authenticated on PATH; it is NOT hermetic and is NOT run
by CI.

Run from a venv where the package is installed (`uv pip install -e .`):
    python examples/acp.py                                   # spawns `devin acp` (default)
    TOOLNEXUS_ACP_CMD="opencode acp" python examples/acp.py   # or opencode instead
"""
from __future__ import annotations

import asyncio
import datetime
import os
import shlex
import time

from toolnexus import ACPOptions, create_in_process_client, create_toolkit, define_tool, load_acp

_HERE = os.path.dirname(os.path.abspath(__file__))
_EXAMPLES = os.path.normpath(os.path.join(_HERE, "..", "..", "examples"))


async def main() -> None:
    # Agent command is selectable: TOOLNEXUS_ACP_CMD (default "devin acp"). Both
    # devin and opencode speak ACP live -- `devin acp` and `opencode acp` both
    # answer `initialize` with protocolVersion 1.
    acp_cmd = os.environ.get("TOOLNEXUS_ACP_CMD", "devin acp")
    parts = shlex.split(acp_cmd)
    command, args = parts[0], parts[1:]

    tk = await create_toolkit(
        mcp_config=os.path.join(_EXAMPLES, "mcp.json"),
        skills_dir=os.path.join(_EXAMPLES, "skills"),
    )

    # a trivial native tool -- proves tool-calling works unchanged through ACP
    def clock() -> str:
        """Return the current UTC time."""
        return datetime.datetime.now(datetime.timezone.utc).isoformat()

    tk.register(define_tool(clock, name="clock"))

    print(f"Spawning ACP agent: {command} {' '.join(args)}")
    try:
        acp = load_acp(ACPOptions(command=command, args=args, cwd=os.getcwd()))
    except Exception as exc:  # noqa: BLE001 - example-level diagnostic
        print(f"acp connect failed (is the CLI installed + authenticated?): {exc}")
        await tk.close()
        raise SystemExit(1)

    agent = create_in_process_client(
        model=command,
        generate=acp.generate,
        system_prompt="You are a precise agent. Use tools to compute and fetch facts.",
    )

    turns = [
        "What time is it right now? Use the clock tool.",
        "What did the clock tool just return, verbatim?",
    ]

    # Two turns on the SAME warm ACP session/process -- this is the whole
    # point: the process-startup cost was paid once by load_acp, not per turn.
    for i, prompt in enumerate(turns, start=1):
        start = time.monotonic()
        res = await agent.run(prompt, tk)
        elapsed = time.monotonic() - start
        print(f"\n--- turn {i} ({elapsed:.2f}s) ---")
        print("prompt:", prompt)
        if res.tool_calls:
            print("tool calls:", [c["name"] for c in res.tool_calls])
        print("answer:", res.text.strip())

    acp.close()
    await tk.close()
    print(f"\nPython ACP example OK — warm session across {len(turns)} turns")


if __name__ == "__main__":
    asyncio.run(main())
