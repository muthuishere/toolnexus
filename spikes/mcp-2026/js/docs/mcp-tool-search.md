# MCP tool search

> **JS port of the Go reference spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/js` (`import * as mcp from "./src/index.ts"`; Node 22.18+, no dependencies, no MCP SDK). Every
> code block is copied verbatim from a program under `examples/` that `test/examples.test.ts` runs and checks;
> `test/docs.test.ts` fails if a block here stops matching its source.

A server with 100 tools costs the model every tool's schema on every turn. With `toolSearch: true` the model gets
two tools instead:

- `search_tools(query, limit?)`: BM25 over tool names (weighted ×2, split on `_` and camelCase) and descriptions;
  returns `name`, `description`, `inputSchema`, `server` (your `description`).
- `call_tool(name, arguments)`: runs a tool it found.

<!-- from: examples/05-tool-search/main.ts -->
```ts
// 1. Turn on tool search; description tells the model what this server is for.
const c = new mcp.Client(ts.url, { toolSearch: true, description: "SaaS workspace (GitHub, Slack, Jira, ...)" })
const meta = await c.tools()
console.log(`eager: ${all.length} tools, ${size(all)} bytes of schema`)
console.log(`lazy:  ${meta.length} tools, ${size(meta)} bytes of schema`)

// 2. The model calls search_tools ...
const hits = await c.searchTools("send a slack message create", 3)
console.log("top hit:", hits[0].name)

// 3. ... then call_tool with the name it found. invoke routes both meta-tools.
const res = await c.invoke("call_tool", { name: hits[0].name, arguments: { text: "hi team" } })
console.log("result:", mcp.text(res))
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

The 100-tool test with realistic descriptions and schemas (`test/toolsearch.test.ts`) measures **56,421 bytes
eager vs 519 lazy**, byte-identical to the Go spike.

Notes:

- Search is client-side: the spec has no search method, so the client fetches `tools/list` once and caches it for
  the server's `ttlMs` (concurrent callers share one request).
- In tool-search mode the direct tool names are hidden: `invoke("echo", …)` rejects, the model must use `call_tool`.
- `invoke(name, args, respond)` is what your agent loop calls for any tool call the model emits; MRTR input
  requests still reach `respond`.
