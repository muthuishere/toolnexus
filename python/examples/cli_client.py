"""A CLI client end to end: your installed agent CLI is the model, and your own
toolkit (an MCP server from mcp config, a skill, a native tool) is what it calls.
Also the live check for SPEC §8C (mirrors golang/examples/cliclient).

    python examples/cli_client.py claude opencode               # Auto (ACP when available)
    python examples/cli_client.py --transport cli claude        # force the one-shot CLI
    CLAUDE_BIN=/path/to/claude python examples/cli_client.py claude

claude has no ACP mode of its own, so it is launched through the ACP adapter
(acp_command). Each run makes three fresh random tokens; a check passes only when
the toolkit itself executed the tool AND the answer contains the token. Not
hermetic, not run by CI: it needs the CLIs installed and logged in.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import secrets
import sys
import tempfile
import time

from toolnexus import CliStyles, CliTransports, create_cli_client, create_toolkit, define_tool

STYLES = {
    "claude": dict(
        style=CliStyles.CLAUDE,
        command=os.environ.get("CLAUDE_BIN", "claude"),
        acp_command=["npx", "-y", "@agentclientprotocol/claude-agent-acp@0.100.0"],
    ),
    "opencode": dict(style=CliStyles.OPENCODE, command="opencode"),
    "copilot": dict(style=CliStyles.COPILOT, command="copilot"),
    "devin": dict(style=CliStyles.DEVIN, command="devin"),
    "codex": dict(style=CliStyles.CODEX, command="codex"),
    "gemini": dict(style=CliStyles.GEMINI, command="gemini"),
    "goose": dict(style=CliStyles.GOOSE, command="goose"),
}


def token(prefix: str) -> str:
    return f"{prefix}-{secrets.token_hex(4).upper()}"


def fixture_mcp() -> None:
    """A stdio MCP server with one tool, vault_get_code."""
    from mcp.server.fastmcp import FastMCP

    srv = FastMCP("vault")

    @srv.tool(description="Returns the vault code.")
    def get_code() -> str:
        return os.environ.get("VAULT_CODE", "")

    srv.run()


async def run_style(name: str, opts: dict, suffix: str) -> list[dict]:
    codes = {"mcp": token("VAULT"), "skill": token("RECIPE"), "native": token("BADGE")}
    out: list[dict] = []
    with tempfile.TemporaryDirectory(prefix="cliclient-") as d:
        sd = os.path.join(d, "skills", "secret-recipe")
        os.makedirs(sd)
        with open(os.path.join(sd, "SKILL.md"), "w") as f:
            f.write(
                "---\nname: secret-recipe\ndescription: The secret recipe code.\n---\n"
                f"The secret recipe code is {codes['skill']}.\n"
            )
        badge = define_tool(
            lambda: codes["native"],
            name="get_badge_number",
            description="Returns the user's badge number.",
            input_schema={"type": "object", "properties": {}},
        )
        cfg = {
            "mcpServers": {
                "vault": {
                    "type": "local",
                    "command": [sys.executable, os.path.abspath(__file__), "fixture-mcp"],
                    "environment": {"VAULT_CODE": codes["mcp"]},
                }
            }
        }
        tk = await create_toolkit(
            mcp_config=cfg, skills_dir=[os.path.join(d, "skills")], extra_tools=[badge], builtins=False
        )
        checks = [
            ("mcp", "What is the vault code? Use the vault_get_code tool.", "vault_get_code"),
            ("skill", "Load the secret-recipe skill and tell me the secret recipe code.", "load_skill"),
            ("native", "What is my badge number? Use the get_badge_number tool.", "get_badge_number"),
        ]
        try:
            for check, prompt, tool in checks:
                client = create_cli_client(
                    **opts,
                    toolkit=tk,
                    system_prompt="You are a terse assistant. Answer in one short sentence and include any code verbatim.",
                )
                start = time.monotonic()
                err, text, calls = None, "", []
                try:
                    res = await client.run(f"{prompt} {suffix}".strip(), tk)
                    text, calls = res.text, res.tool_calls
                except Exception as e:  # noqa: BLE001
                    err = str(e)
                row: dict = {
                    "style": name,
                    "check": check,
                    "transport": client.transport(),
                    "ms": int((time.monotonic() - start) * 1000),
                }
                info = client.acp_session()
                ran = False
                if info is not None:
                    ran = tool in info["served_calls"]
                    row["servedCalls"] = info["served_calls"]
                    row["permissions"] = info["permissions"]
                for c in calls:
                    n = "load_skill" if c["name"] == "skill" else c["name"]
                    ran = ran or n == tool
                if client.fallback_reason():
                    row["fallback"] = client.fallback_reason()
                if err:
                    row["error"] = err
                row["text"] = text
                row["pass"] = err is None and ran and codes[check] in text
                client.close()
                out.append(row)
                print(f"{name} {check} {row['transport']} pass={row['pass']} {row['ms']}ms", file=sys.stderr)
        finally:
            await tk.close()
    return out


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--transport", default="auto", choices=["auto", "acp", "cli"])
    ap.add_argument("--model", default="")
    ap.add_argument("--suffix", default="", help="text appended to every prompt")
    ap.add_argument("styles", nargs="+", choices=sorted(STYLES))
    a = ap.parse_args()
    transport = {"auto": CliTransports.AUTO, "acp": CliTransports.ACP, "cli": CliTransports.CLI}[a.transport]
    results = []
    for name in a.styles:
        opts = dict(STYLES[name], transport=transport, model=a.model or None)
        results += await run_style(name, opts, a.suffix)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    if sys.argv[1:2] == ["fixture-mcp"]:
        fixture_mcp()
    else:
        asyncio.run(main())
