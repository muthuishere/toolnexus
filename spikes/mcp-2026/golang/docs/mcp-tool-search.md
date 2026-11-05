# MCP tool search

> **Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/golang` (import path `toolnexus/spikes/mcp2026`). Every code block is copied
> verbatim from a program under `examples/` that `examples_test.go` runs and checks; `docs_test.go` fails if a
> block here stops matching its source.

A server with 100 tools costs the model every tool's schema on every turn. With `ToolSearch` the model gets two
tools instead:

- `search_tools(query, limit?)`: BM25 over tool names (weighted ×2, split on `_` and camelCase) and descriptions;
  returns `name`, `description`, `inputSchema`, `server` (your `Description`).
- `call_tool(name, arguments)`: runs a tool it found.

<!-- from: examples/05-tool-search/main.go -->
```go
	// 1. Turn on tool search; Description tells the model what this server is for.
	c := mcp.NewClient(ts.URL + "/mcp")
	c.ToolSearch = true
	c.Description = "SaaS workspace (GitHub, Slack, Jira, ...)"
	meta, err := c.Tools()
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("eager: %d tools, %d bytes of schema\n", len(all), size(all))
	fmt.Printf("lazy:  %d tools, %d bytes of schema\n", len(meta), size(meta))

	// 2. The model calls search_tools ...
	hits, err := c.SearchTools("send a slack message create", 3)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("top hit:", hits[0]["name"])

	// 3. ... then call_tool with the name it found. Invoke routes both meta-tools.
	res, err := c.Invoke("call_tool", mcp.Obj{"name": hits[0]["name"], "arguments": mcp.Obj{"text": "hi team"}}, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("result:", mcp.Text(res))
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

The spike's own 100-tool test, with realistic descriptions and schemas, measures **56,421 bytes eager vs 519 lazy**
(+3,532 bytes per 5-hit search result).

Notes:

- Search is client-side: the spec has no search method, so the client fetches `tools/list` once and caches it for
  the server's `ttlMs`.
- In tool-search mode the direct tool names are hidden: `Invoke("echo", …)` fails, the model must use `call_tool`.
- `Invoke(name, args, respond)` is what your agent loop calls for any tool call the model emits; MRTR input
  requests still reach `respond`.
