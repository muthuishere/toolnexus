"""Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.

GitHub supports neither DCR nor CIMD, so you register an OAuth App once (github.com/settings/developers
-> New OAuth App, callback URL http://127.0.0.1/callback, enable "expiring user tokens" if you want
refresh) and pass its client id + secret.

    sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- python examples/10-github/main.py

Credentials are read from the environment and never printed; neither is any token.
BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
otherwise the system browser opens.
"""
import os
import subprocess
import sys

import mcp2026 as mcp

client_id, secret = os.environ.get("GITHUB_MCP_CLIENT_ID", ""), os.environ.get("GITHUB_MCP_CLIENT_SECRET", "")
if not client_id or not secret:
    print("skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...")
    sys.exit(0)

login = mcp.LoopbackAuthorizer()  # system browser + http://127.0.0.1:<random>/callback
if os.environ.get("BROWSER") == "chrome-agent":
    login.open = lambda url: subprocess.run(["chrome-agent", "goto", url], check=True, capture_output=True)

c = mcp.Client("https://api.githubcopilot.com/mcp/")
c.auth = mcp.OAuth(
    client_id=client_id,
    client_secret=secret,  # GitHub OAuth Apps are confidential clients (client_secret_basic)
    authorizer=login,
)

tools = c.tools()
print("tools listed:", str(len(tools) > 0).lower())

res = c.call_tool("get_me", {})  # read-only: who am I
print("get_me:", res["resultType"], "| isError:", str(res.get("isError") is True).lower())  # content withheld on purpose
