# usage: python main.py npx -y @agentclientprotocol/claude-agent-acp@0.100.0
import asyncio
import json
import os
import sys

from toolnexus import ACPOptions, create_in_process_client, create_toolkit, define_tool, load_acp


def add(a: float, b: float) -> str:
    """Add two numbers."""
    return str(int(a + b))


def allow_only(names):
    """Permission policy: allow tool calls whose title names one of your tools, reject the rest.
    Return an optionId to pick it; return None to answer with the first reject option."""
    def decide(params):
        title = (params.get("toolCall") or {}).get("title") or ""
        ok = any(n in title for n in names)
        return next((o["optionId"] for o in params["options"] if o["kind"].startswith("allow" if ok else "reject")), None)
    return decide


def log_usage(params):
    if params.get("update", {}).get("sessionUpdate") == "usage_update":
        print("[usage]", json.dumps(params["update"]), file=sys.stderr)


async def main():
    toolkit = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add")])
    server = toolkit.as_mcp_server()
    agent = load_acp(ACPOptions(
        command=sys.argv[1], args=sys.argv[2:],
        mcp_servers=[{"type": "http", "name": "toolnexus", "url": server.url,
                      "headers": [{"name": "Authorization", "value": "Bearer " + server.token}]}],
        # Agent-specific knobs are opaque data: this one isolates Claude Code (see the per-agent notes).
        session_meta={"claudeCode": {"options": {"settingSources": [], "strictMcpConfig": True,
                                                 "plugins": [], "skills": [], "tools": []}}},
        model=os.environ.get("ACP_MODEL"),  # e.g. "haiku"; passed through, never validated
        on_permission=allow_only([t.name for t in toolkit.tools()]),
        on_update=log_usage,
        permission_timeout=300,
    ))
    client = create_in_process_client(model=sys.argv[1], generate=agent.generate)
    try:
        result = await client.run("Use the add tool to add 17 and 25. Reply with just the sum.",
                                  await create_toolkit(builtins=False))
        print(result.text)
    finally:
        agent.close()
        server.close()


asyncio.run(main())
