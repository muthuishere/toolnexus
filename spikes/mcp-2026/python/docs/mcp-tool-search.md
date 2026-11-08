# MCP tool search

> **Python port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/python` (package `mcp2026`, `import mcp2026 as mcp`); the Go spike is the reference.
> Every code block is copied verbatim from a program under `examples/` that `tests/test_examples.py` runs and
> checks; `tests/test_docs.py` fails if a block here stops matching its source.

A server with 100 tools costs the model every tool's schema on every turn. With `tool_search` the model gets two
tools instead:

- `search_tools(query, limit?)`: BM25 over tool names (weighted ×2, split on `_` and camelCase) and descriptions;
  returns `name`, `description`, `inputSchema`, `server` (your `description`).
- `call_tool(name, arguments)`: runs a tool it found.

<!-- from: examples/05-tool-search/main.py -->
```python
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
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

The 100-tool test with realistic descriptions and schemas measures **56,421 bytes eager vs 519 lazy** — the same
bytes as the Go spike (`tests/test_toolsearch.py` asserts both numbers).

Notes:

- Search is client-side: the spec has no search method, so the client fetches `tools/list` once and caches it for
  the server's `ttlMs`.
- In tool-search mode the direct tool names are hidden: `invoke("echo", …)` raises, the model must use `call_tool`.
- `invoke(name, args, respond)` is what your agent loop calls for any tool call the model emits; MRTR input
  requests still reach `respond`.
