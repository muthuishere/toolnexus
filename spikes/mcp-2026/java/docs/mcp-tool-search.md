# MCP tool search

> **Java port of the spike — the Go spike is the reference (openspec `upgrade-mcp-2026-07-28`).** The API below is
> `spikes/mcp-2026/java` (package `io.github.muthuishere.toolnexus.mcp2026`). Every code block is copied verbatim
> from a program under `examples/` that `ExamplesTest` runs and checks; `DocsTest` fails if a block here stops
> matching its source.

A server with 100 tools costs the model every tool's schema on every turn. With `toolSearch(true)` the model
gets two tools instead:

- `search_tools(query, limit?)`: BM25 over tool names (weighted ×2, split on `_` and camelCase) and descriptions;
- `call_tool(name, arguments)`: runs a tool it found.

<!-- from: examples/05-tool-search/Main.java -->
```java
            // 1. Turn on tool search; the description tells the model what this server is for.
            Client c = new Client(ts.url() + "/mcp")
                    .toolSearch(true)
                    .description("SaaS workspace (GitHub, Slack, Jira, ...)");
            List<Map<String, Object>> meta = c.tools();
            System.out.println("eager: " + all.size() + " tools, " + size(all) + " bytes of schema");
            System.out.println("lazy:  " + meta.size() + " tools, " + size(meta) + " bytes of schema");

            // 2. The model calls search_tools ...
            List<Map<String, Object>> hits = c.searchTools("send a slack message create", 3);
            System.out.println("top hit: " + hits.get(0).get("name"));

            // 3. ... then call_tool with the name it found. invoke routes both meta-tools.
            Map<String, Object> res = c.invoke("call_tool", Mcp.obj("name", hits.get(0).get("name"), "arguments", Mcp.obj("text", "hi team")), null);
            System.out.println("result: " + Mcp.text(res));
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

The byte counts equal the Go port's: JSON is written with sorted keys, like Go's `encoding/json`.
`ToolSearchTest` (100 tools with realistic descriptions and schemas) measures **56,421 bytes eager vs 519 lazy** (+3,532 bytes per 5-hit search result), the same bytes as Go.

Notes:

- Search is client-side: the spec has no search method, so the client fetches `tools/list` once and caches it for
  the server's `ttlMs`.
- In tool-search mode the direct tool names are hidden: `invoke("echo", …)` fails, the model must use `call_tool`.
- `invoke(name, args, respond)` is what your agent loop calls for any tool call the model emits; MRTR input
  requests still reach `respond`.
