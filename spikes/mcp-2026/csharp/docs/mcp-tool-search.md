# MCP tool search

> **C# port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/csharp` (project `src/Mcp2026`, namespace `Mcp2026`). Every code block is copied verbatim from a
> program under `examples/` that `ExamplesTests` runs and checks; `DocsTests` fails if a block here stops matching
> its source. Behaviour and example output are identical to the Go reference spike.

A server with 100 tools costs the model every tool's schema on every turn. With `ToolSearch` the model gets two
tools instead:

- `search_tools(query, limit?)`: BM25 over tool names (weighted ×2, split on `_` and camelCase) and descriptions;
  returns `name`, `description`, `inputSchema`, `server` (your `Description`).
- `call_tool(name, arguments)`: runs a tool it found.

<!-- from: examples/05-tool-search/Program.cs -->
```csharp
// 1. Turn on tool search; Description tells the model what this server is for.
var c = new McpClient(ts.Url + "/mcp") { ToolSearch = true, Description = "SaaS workspace (GitHub, Slack, Jira, ...)" };
var meta = await c.ToolsAsync();
Console.WriteLine($"eager: {all.Count} tools, {Size(all)} bytes of schema");
Console.WriteLine($"lazy:  {meta.Count} tools, {Size(meta)} bytes of schema");

// 2. The model calls search_tools ...
var hits = await c.SearchToolsAsync("send a slack message create", 3);
Console.WriteLine("top hit: " + hits[0]["name"]);

// 3. ... then call_tool with the name it found. InvokeAsync routes both meta-tools.
var res = await c.InvokeAsync("call_tool", new JsonObject { ["name"] = hits[0]["name"]!.DeepClone(), ["arguments"] = new JsonObject { ["text"] = "hi team" } });
Console.WriteLine("result: " + Mcp.Text(res));
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

Notes:

- Search is client-side: the spec has no search method, so the client fetches `tools/list` once and caches it for
  the server's `ttlMs`.
- In tool-search mode the direct tool names are hidden: `InvokeAsync("echo", …)` throws; the model must use `call_tool`.
- `InvokeAsync(name, args, respond)` is what your agent loop calls for any tool call the model emits; MRTR input
  requests still reach `respond`.
