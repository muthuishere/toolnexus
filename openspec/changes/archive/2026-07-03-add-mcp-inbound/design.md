## Context

toolnexus already speaks MCP as a **client** (§2): each port uses its MCP SDK to connect to configured
servers and adapt their tools into the uniform `Tool` registry. The same SDKs all ship a **server**
API. This change reuses that server API to publish the toolkit's own `Tool[]` back out as an MCP
server — the inbound mirror of §7B (A2A inbound). Because MCP's unit of exchange is a *tool call* (not
an LLM task), inbound MCP is materially simpler than inbound A2A: no client loop, no Task lifecycle, no
persistence. We add one profile to the existing `serve` surface. (Only the streamable-HTTP transport
is in scope; a stdio entrypoint is deferred — see Non-Goals.)

## Goals / Non-Goals

**Goals:**
- Expose a toolkit as a real, interoperable MCP server over **streamable-HTTP**, built on each port's
  existing MCP SDK server mode.
- `tools/list` = the toolkit's unified tools (all sources), name **verbatim**, `parameters` →
  `inputSchema`; optional `mcp.tools` name filter. `tools/call` = `Tool.execute` → `ToolResult` →
  `CallToolResult`.
- Opt-in (`mcp` profile absent ⇒ no MCP surface). 5-port byte-parity; hermetic tests via an
  in-process MCP client↔server pair (no external process, no sockets where avoidable).

**Non-Goals (deliberately deferred):**
- A **stdio** transport (the process as the MCP server over stdin/stdout, for local clients like
  Claude Desktop). This change ships streamable-HTTP only; stdio is addable later without breaking the
  HTTP surface.
- MCP **resources**, **prompts**, **sampling**, **completion**, and notifications/subscriptions — v1
  is `tools/*` only. (Skills are already exposed *as the `skill` tool*, so they reach MCP clients
  without a separate `prompts` surface.)
- Auth in core (the HTTP endpoint layers auth via the same hook story as §7B; stdio inherits the
  process's trust boundary).
- The Go CLI `serve --mcp` subcommand (follow-up).

## The wire subset (pinned)

- **Protocol:** MCP (JSON-RPC 2.0), the same version each port's SDK negotiates as a client today.
  `initialize` advertises `serverInfo:{name, version}` (from the `mcp` profile; defaults
  `name:"toolnexus"`, `version:"0.1.0"`) and `capabilities:{tools:{}}`.
- **`tools/list`** → `{ tools: [{ name, description, inputSchema }] }`, one entry per exposed toolkit
  tool. `name` is the toolkit `Tool.name` **verbatim** (already sanitized at registration, §0.2 — no
  re-sanitize, unlike A2A which sanitizes skill ids). `description` is `Tool.description`.
  `inputSchema` is `Tool.parameters` (already a JSON Schema object). When `mcp.tools` is set, the list
  is filtered to exactly those names (unknown names are ignored, not an error).
- **`tools/call`** (params `{ name, arguments }`) → dispatch to the toolkit's `Tool.execute(arguments,
  ctx)`. Map the `ToolResult`: `content: [{ type:"text", text: output }]`; `isError: result.isError`.
  A missing tool name ⇒ the SDK's standard "unknown tool" error. An `execute` throw ⇒ `isError:true`
  with the error text (never crashes the server).
- **Transports:** `stdio` (SDK stdio server transport bound to the process stdin/stdout) and
  `streamable-http` (SDK streamable-HTTP server transport mounted at `POST/GET /mcp` on the `serve`
  HTTP server).

## Decisions

**1. One `serve`, two HTTP profiles.** `serve(addr, { a2a?, mcp? })` mounts HTTP profiles (A2A routes
and/or the `/mcp` streamable-HTTP endpoint) on one server and returns the existing `ServeHandle`
(`stop()`/`close()`). A stdio transport is out of scope for this change (deferred); if added later it
would be a *different process model* (owns stdin/stdout, blocks) and would get its own entrypoint
rather than being forced into the `addr`-based HTTP `serve`.

**2. Full unified tools, name verbatim.** The gateway exposes *every* toolkit tool (the whole point —
"useful for all"), so `tools/list` iterates the same registry `client.run` sees. Names are used as-is
because toolkit tool names are already MCP-safe (sanitized at registration). This is the deliberate
difference from A2A inbound, which advertises **skills** (coarse, sanitized ids) rather than raw
tools — MCP's native granularity *is* the tool, so raw tools are correct here. A host that wants a
narrower surface sets `mcp.tools` (name subset) or omits tools from the toolkit.

**3. No client, no Task, no store.** An MCP `tools/call` is a single synchronous callable invocation,
not an agent turn. So the `mcp` profile does **not** take a `client`, does **not** create Tasks, and
needs **no `TaskStore`**. `Tool.execute` runs directly under the request, honoring the `Context`
cancel/timeout. This is the core simplification vs §7B and keeps the surface tiny.

**4. Map `ToolResult` → `CallToolResult`.** `output` (string; if a port's `ToolResult.output` is
structured, its canonical string form per §1) becomes a single `text` content part; `isError`
propagates. `metadata` is not part of the MCP `CallToolResult` contract, so it is dropped on the wire
(still available to `onCall`). Rationale: match the MCP SDK's result shape exactly so real MCP clients
parse it unchanged.

**5. Config key `mcpServer` (singular).** The client-side block is `mcpServers` (map of upstream
servers to *consume*). The inbound serve profile is `mcpServer` (singular) — one server we *are*.
Distinct key avoids ambiguity; it is a reserved sibling alongside `a2a`/`agents`/`builtins` (§2).

**6. Reuse each port's MCP SDK server mode.** No hand-rolled JSON-RPC. JS `@modelcontextprotocol/sdk`
`Server` + `StreamableHTTPServerTransport`; Python `mcp` `Server` + streamable-HTTP ASGI app; Go
`mark3labs/mcp-go` `server.NewMCPServer` + streamable-HTTP handler; Java official
`io.modelcontextprotocol.sdk` server + HTTP transport; C# `ModelContextProtocol` server + HTTP
transport. Each registers one tool handler that closes over the toolkit and dispatches by name.

## Risks / Trade-offs

- [SDK server-API variance across ports] → keep the exposed surface tiny (`tools/list` + `tools/call`,
  `serverInfo`) so every SDK covers it; a parity test drives the same round-trip in each port.
- [Re-exposing dangerous builtins to an MCP client] → the gateway is explicit and opt-in; document
  that `mcp.tools` filters the surface and that hosts control which sources the toolkit contains.
- [Loopback risk: a toolkit that *consumes* an MCP server and also *serves* one] → allowed and useful
  (that is the gateway), but tests must avoid a self-referential config that recurses.

## Testing (hermetic, per port)

Each port already has an MCP **client**; use it as the test peer. Stand up the toolkit's MCP **server**
in-process (an in-memory / linked transport pair where the SDK offers one, else streamable-HTTP via an
ephemeral-port server) and connect the port's own MCP client to it. Assert: `initialize` →
`serverInfo`; `tools/list` lists the
toolkit's unified tools (all sources) with `inputSchema` = `parameters`; `mcp.tools` narrows the list;
`tools/call` on a native/echo tool returns the `ToolResult` text with `isError:false`; an erroring tool
→ `isError:true`, server survives; `mcp` absent ⇒ no MCP surface. A shared `examples/`-based toolkit
(the existing `mcp.json` + `skills/hello-world`) is the fixture so every port serves the same tools.

## Open Questions

- **in-process test transport** — which ports' SDKs expose an in-memory/linked pair (JS, C# do) vs
  need an ephemeral HTTP port (Go, Java)? Resolved per port during apply; prefer in-memory, fall back
  to an ephemeral streamable-HTTP port.
- **Structured `ToolResult.output`** — most tools return text; confirm the canonical string mapping per
  port (§1) for tools whose output is a structured object, so the single `text` content part is
  consistent across ports.
- **Skills over MCP** — v1 exposes skills only via the `skill` tool (already in the toolkit). Whether
  to also surface them as MCP `prompts`/`resources` is deferred; note it as forward-compatible.
