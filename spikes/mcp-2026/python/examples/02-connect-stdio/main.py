"""Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).

Run: python examples/02-connect-stdio/main.py

stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is passed through
(or captured via stderr=) and never parsed. There are no HTTP headers and no OAuth over stdio: a stdio
server takes its credentials from its own environment.
"""
import os
import sys

import mcp2026 as mcp

# Demo harness: this same script is the stdio server when started with SERVE_STDIO=1.
# In real use the command is your server, e.g. ["npx", "-y", "some-mcp-server"].
if os.environ.get("SERVE_STDIO") == "1":
    mcp.serve_stdio(mcp.fixture_server())
    sys.exit(0)

# 1. Describe the process to spawn (argv, env, cwd: plain subprocess).
argv = [sys.executable, __file__]
env = {**os.environ, "SERVE_STDIO": "1"}

# 2. Start it and get a client. Same API as the HTTP client: call, tools, call_tool.
with mcp.Client.stdio(argv, env=env) as c:  # leaving the block stops the server process
    # 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
    tools = c.tools()
    print("tool count:", len(tools))

    res = c.call_tool("echo", {"text": "hello over stdio"})
    print("echo:", mcp.text(res))
