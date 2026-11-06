"""Example 01 — connect to an MCP server over Streamable HTTP and call a tool.

Run: python examples/01-connect-http/main.py

The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
Every request carries its own protocol version, so "connecting" is just Client(url).
"""
import mcp2026 as mcp

# A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
# In real use this is someone else's server, e.g. "https://api.example.com/mcp".
with mcp.serve(mcp.fixture_server()) as ts:
    # 1. Point a client at the server's MCP endpoint. No handshake happens here.
    c = mcp.Client(ts.url + "/mcp")

    # 2. Optional: server/discover tells you which versions and capabilities the server has.
    info = c.call("server/discover")
    print("supported versions:", "[" + " ".join(info["supportedVersions"]) + "]")

    # 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
    tools = c.tools()
    print("tools:", ", ".join(t["name"] for t in tools))

    # 4. Call a tool. mcp.text returns the first text content of the result.
    res = c.call_tool("echo", {"text": "hello over HTTP"})
    print("echo:", mcp.text(res))
