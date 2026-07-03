## Why

toolnexus is already an MCP **client**: point it at an `mcp.json`, connect to N servers, and every
server's tools become uniform `Tool`s in one toolkit (§2). It also unifies skills · native · http ·
builtin · A2A behind that same `Tool` (§0). The missing edge is the **inbound** direction: let a
toolnexus toolkit **be an MCP server** so *any* MCP client — Claude Desktop, an IDE, another agent —
can call its tools. This turns toolnexus into a **universal MCP gateway**: aggregate many servers +
skills + your own functions behind one toolkit, then re-expose the union as a single MCP server. It
is the symmetric counterpart to the A2A inbound `serve` (§7B) — same `serve` entrypoint, a new opt-in
profile. Extend, don't fork.

## What Changes

Add an **`mcp` profile** to `toolkit.serve(...)`, mirroring the existing `a2a` profile. When present,
the toolkit is exposed as a standard **MCP server** speaking the same protocol toolnexus already
consumes as a client, built on **each port's existing MCP SDK in server mode** (no new dependency).

- **Expose the full unified `Tool[]` (gateway).** `tools/list` advertises **every** tool in the
  toolkit — regardless of source (`mcp` · `skill` · `native` · `http` · `builtin` · `a2a`) — as MCP
  tools. Each entry maps a `Tool`'s existing `name` (already sanitized at registration, §0.2 — used
  **verbatim**, no re-sanitize), `description`, and `parameters` (JSON Schema → MCP `inputSchema`).
  An optional `mcp.tools?` subset (list of tool names; omit ⇒ all) filters the surface, mirroring
  `a2a.skills?`.
- **`tools/call` → `Tool.execute`.** A call dispatches by name to the toolkit's `Tool.execute(args,
  ctx)` and maps the returned `ToolResult` to an MCP `CallToolResult`: `output` → a text content part,
  `isError` → MCP `isError`. Unknown tool name ⇒ the SDK's standard method/tool error. Unlike A2A
  inbound, there is **no client loop, no Task lifecycle, and no TaskStore** — the calling MCP client
  *is* the LLM host; toolnexus only exposes callables. The `mcp` profile therefore needs **no
  `client`**.
- **Transport: streamable-HTTP.** Remote MCP over HTTP, **co-mounted on the existing `serve(addr, …)`
  HTTP server** at an `/mcp` endpoint alongside the A2A routes. Symmetric with the A2A HTTP serve model
  and the streamable-HTTP MCP client toolnexus already speaks (§2). A **stdio** transport (for local
  clients like Claude Desktop) is intentionally out of scope for this change — deferred, addable later
  without breaking the HTTP surface.
- **Config (declarative + inline).** An `mcp` **serve** profile is configurable inline via the
  `serve` option or a top-level **`mcpServer` block in the config file** (a reserved sibling key
  alongside `a2a`/`agents`/`builtins`, §2 — named `mcpServer` to avoid colliding with the existing
  `mcpServers` **client** block). Fields: `name`, `version?`, `tools?` (name subset; omit ⇒ all).
  Inline wins on merge.
- **Observability reuse (no new system).** Because served calls run the ordinary `Tool.execute`, the
  same execution path and `Context` (cancel/timeout) apply. A `serve` **`onCall`** callback surfaces
  each inbound tool call (tool name, source, ms, isError) — the inbound analog of A2A's `onTask`.
- Applies across **all five ports** (js/python/golang/java/csharp), runs against the shared
  `examples/` fixtures, and `SPEC.md` gains a new inbound section **§7C** alongside §7B (A2A inbound).

Already-present, reused (not re-implemented): each port's MCP SDK (client mode today, server mode now),
the toolkit `Tool[]` registry + `execute` + adapter schema emission (§4), the `Context`
cancel/timeout, and the `serve` HTTP server + handle/`stop()` machinery (§7B).

## Capabilities

### New Capabilities
- `mcp-inbound`: expose a toolkit as an MCP server — the `mcp` `serve` profile mounting a
  streamable-HTTP MCP server at the `/mcp` endpoint on `serve(addr, …)`.
  `tools/list` advertises the toolkit's unified tools (optionally filtered by `mcp.tools`);
  `tools/call` dispatches to `Tool.execute` and maps `ToolResult` → MCP `CallToolResult`. Built on
  each port's existing MCP SDK in server mode; opt-in (absent ⇒ no MCP surface); an `onCall` callback
  surfaces each inbound call.

### Modified Capabilities
<!-- No archived capability spec is modified. SPEC.md §2 (config parsing) gains the reserved
     `mcpServer` serve-profile key; §4/§7B are referenced. -->

## Impact

- **SPEC.md** — new **§7C (MCP inbound / `serve` MCP profile)**: the `mcp` profile shape, stdio vs
  streamable-HTTP entrypoints, `tools/list` (unified tools, name verbatim, `parameters`→`inputSchema`,
  `mcp.tools` filter), `tools/call`→`execute`→`ToolResult`→`CallToolResult` mapping, `onCall`, and the
  no-client/no-Task simplification vs §7B. §2 gains the reserved `mcpServer` serve-profile key note.
- **js/python/golang/java/csharp** — one MCP-server module per port using the port's MCP SDK server
  API (JS `McpServer`, Python `mcp.server`, Go `mark3labs/mcp-go` server, Java official SDK server, C#
  `ModelContextProtocol` server); the `/mcp` streamable-HTTP endpoint on `serve`; config parsing
  for `mcpServer`; per-port tests that connect an **in-process MCP client** (the port's own client
  mode) to the served toolkit and assert `tools/list`/`tools/call` round-trips — no external process.
- **Go CLI** — optional `toolnexus serve --mcp` subcommand (follow-up; not required for v1).
- **Dependencies** — none new; each port's MCP SDK already ships (client mode) and is reused in server
  mode.
- **Security** — the gateway re-exposes exactly the toolkit's tools; a host that wants to keep
  `bash`/fs builtins private uses `mcp.tools` to filter (or omits builtins from the toolkit). Secrets
  in `http`/`mcp` client `headers` continue to expand from `${ENV}` and are never logged.
