# MCP tool search

> **Rust port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/rust` (crate `mcp2026`; reference: `spikes/mcp-2026/golang`). Every code block is
> copied verbatim from a program under `examples/` that `tests/examples.rs` runs and checks; `tests/docs.rs`
> fails if a block here stops matching its source.

A server with 100 tools costs the model every tool's schema on every turn. With `tool_search` the model gets two
tools instead:

- `search_tools(query, limit?)`: BM25 over tool names (weighted ×2, split on `_` and camelCase) and descriptions;
  returns `name`, `description`, `inputSchema`, `server` (your `description`).
- `call_tool(name, arguments)`: runs a tool it found.

<!-- from: examples/05-tool-search/main.rs -->
```rust
    // 1. Turn on tool search; description tells the model what this server is for.
    let mut c = Client::new(&url);
    c.tool_search = true;
    c.description = "SaaS workspace (GitHub, Slack, Jira, ...)".into();
    let meta = c.tools().await?;
    println!("eager: {} tools, {} bytes of schema", all.len(), size(&all));
    println!(
        "lazy:  {} tools, {} bytes of schema",
        meta.len(),
        size(&meta)
    );

    // 2. The model calls search_tools ...
    let hits = c.search_tools("send a slack message create", 3).await?;
    println!("top hit: {}", hits[0]["name"].as_str().unwrap_or_default());

    // 3. ... then call_tool with the name it found. invoke routes both meta-tools.
    let res = c
        .invoke(
            "call_tool",
            json!({"name": hits[0]["name"], "arguments": {"text": "hi team"}}),
            None,
        )
        .await?;
    println!("result: {}", mcp::text(&res));
```

```text
eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team
```

The byte counts are identical to the Go spike's (same tools, same JSON).

Notes:

- Search is client-side: the spec has no search method, so the client fetches `tools/list` once and caches it for
  the server's `ttlMs`.
- In tool-search mode the direct tool names are hidden: `invoke("echo", …)` fails, the model must use `call_tool`.
- `invoke(name, args, respond)` is what your agent loop calls for any tool call the model emits; MRTR input
  requests still reach `respond`.
