# MCP lazy tool search

> **Elixir port of the Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API
> below is `spikes/mcp-2026/elixir` (modules `Mcp2026.*`). Every code block is copied verbatim from a script under
> `examples/` that `test/examples_test.exs` runs and checks; `test/docs_test.exs` fails if a block here stops
> matching its source.

A server with 100 tools costs the model ~10 KB of schema on every turn. With `tool_search: true` the model sees
two tools instead: `search_tools(query, limit?)` and `call_tool(name, arguments)`. Search is BM25 over the tool
name (counted twice, split on `_` and camelCase) and description; each hit carries `name`, `description`,
`inputSchema` and `server` (your `:description`). `tools/list` is cached for its `ttlMs`.

<!-- from: examples/05-tool-search.exs -->
```elixir
# 1. Turn on tool search; description tells the model what this server is for.
c = Client.new(srv.url, tool_search: true, description: "SaaS workspace (GitHub, Slack, Jira, ...)")
{:ok, meta} = Client.tools(c)
size = fn v -> byte_size(JSON.encode!(v)) end
IO.puts("eager: #{length(all)} tools, #{size.(all)} bytes of schema")
IO.puts("lazy:  #{length(meta)} tools, #{size.(meta)} bytes of schema")

# 2. The model calls search_tools ...
{:ok, [top | _]} = Client.search_tools(c, "send a slack message create", 3)
IO.puts("top hit: " <> top["name"])

# 3. ... then call_tool with the name it found. invoke routes both meta-tools.
{:ok, res} = Client.invoke(c, "call_tool", %{"name" => top["name"], "arguments" => %{"text" => "hi team"}})
IO.puts("result: " <> Mcp2026.text(res))
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

The byte counts are identical to the Go spike's (same tool JSON). Search is client-side only: the spec has no
server-side search method, so the first search fetches the full `tools/list` once.
