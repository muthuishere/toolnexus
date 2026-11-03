"""Example 05 — lazy tool search: give the model 2 tools instead of 100.

Run: python examples/05-tool-search/main.py

With c.tool_search = True, c.tools() returns two meta-tools, search_tools(query, limit?) and
call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then calls
what it found. c.invoke runs whatever tool call the model emits.
"""
import json

import mcp2026 as mcp


def size(v) -> int:
    return len(json.dumps(v, separators=(",", ":")))


# A server with 100 tools: 10 services x 10 verbs.
srv = mcp.Server()
for svc in ["github", "slack", "jira", "gmail", "calendar", "drive", "stripe", "postgres", "s3", "notion"]:
    for verb in ["create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"]:
        name = f"{svc}_{verb}"
        srv.tool(name, f"{verb} an item in {svc}.", lambda args, ctx, name=name: f"{name} ran with {args.get('text')}")

with mcp.serve(srv) as ts:
    eager = mcp.Client(ts.url + "/mcp")
    all_tools = eager.tools()

    # 1. Turn on tool search; description tells the model what this server is for.
    c = mcp.Client(ts.url + "/mcp")
    c.tool_search = True
    c.description = "SaaS workspace (GitHub, Slack, Jira, ...)"
    meta = c.tools()
    print(f"eager: {len(all_tools)} tools, {size(all_tools)} bytes of schema")
    print(f"lazy:  {len(meta)} tools, {size(meta)} bytes of schema")

    # 2. The model calls search_tools ...
    hits = c.search_tools("send a slack message create", 3)
    print("top hit:", hits[0]["name"])

    # 3. ... then call_tool with the name it found. invoke routes both meta-tools.
    res = c.invoke("call_tool", {"name": hits[0]["name"], "arguments": {"text": "hi team"}})
    print("result:", mcp.text(res))
