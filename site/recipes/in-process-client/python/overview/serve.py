import asyncio
import json
import urllib.request

from toolnexus import create_toolkit, define_tool


def add(a: float, b: float) -> str:
    """Add two numbers."""
    return str(int(a + b))


async def main():
    # Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
    toolkit = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add")])
    server = toolkit.as_mcp_server()  # MCPServeConfig(tools=[...]) narrows what is exposed
    print("MCP endpoint:", server.url)  # http://127.0.0.1:<port>/mcp, POST only

    # Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
    entry = {"type": "http", "name": "toolnexus", "url": server.url,
             "headers": [{"name": "Authorization", "value": "Bearer " + server.token}]}
    req = urllib.request.Request(
        entry["url"],
        json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}).encode(),
        {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
         "Authorization": entry["headers"][0]["value"]})
    with urllib.request.urlopen(req) as res:
        print("tools/list:", res.status)
    server.close()


asyncio.run(main())
