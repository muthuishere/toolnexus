# usage: python main.py npx -y @agentclientprotocol/claude-agent-acp@0.100.0
import asyncio
import sys

from toolnexus import ACPOptions, create_in_process_client, create_toolkit, define_tool, load_acp


def add(a: float, b: float) -> str:
    """Add two numbers."""
    print(f"\n[tool] add({a:g}, {b:g})")
    return str(int(a + b))


async def main():
    # 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
    toolkit = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add")])
    server = toolkit.as_mcp_server()

    # 2. Start the agent; mcp_servers is sent to session/new exactly as written.
    agent = load_acp(ACPOptions(
        command=sys.argv[1], args=sys.argv[2:],
        mcp_servers=[{"type": "http", "name": "toolnexus", "url": server.url,
                      "headers": [{"name": "Authorization", "value": "Bearer " + server.token}]}],
        permission_timeout=300,  # bounds one turn; a real agent can take minutes
    ))

    # 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
    client = create_in_process_client(model=sys.argv[1], generate_stream=agent.generate_stream)
    empty = await create_toolkit(builtins=False)
    try:
        async for ev in client.stream("Use the add tool to add 17 and 25. Reply with just the sum.", empty):
            if ev["type"] == "text":
                print(ev["delta"], end="", flush=True)  # live text as the agent writes it
            elif ev["type"] == "tool_call" and ev.get("via") == "acp":
                print(f"\n[agent tool] {ev['name']}")
            elif ev["type"] == "done":
                print("\nfinal:", ev["result"].text)
    finally:
        agent.close()  # ends the agent's whole process group
        server.close()


asyncio.run(main())
