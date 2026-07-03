## 1. Contract (SPEC.md)

- [x] 1.1 SPEC §7C (MCP inbound): the `mcp` `serve` profile; `serverInfo` from the profile;
      `capabilities.tools`; opt-in (absent ⇒ no MCP surface); the `/mcp` streamable-HTTP endpoint
      co-mounted on `serve(addr,…)`. stdio deferred (out of scope).
- [x] 1.2 SPEC §7C: `tools/list` = unified tools across all sources, `Tool.name` **verbatim**,
      `description`, `parameters`→`inputSchema`; `mcp.tools` name filter (unknown ignored).
- [x] 1.3 SPEC §7C: `tools/call`→`Tool.execute`→`ToolResult`→`CallToolResult` (`output`→text,
      `isError` propagates); no client/Task/store; `execute` throw ⇒ `isError`, server survives;
      unknown tool ⇒ SDK error; `onCall` callback.
- [x] 1.4 SPEC §2: reserved `mcpServer` (singular) serve-profile key, distinct from the client-side
      `mcpServers` block; note alongside `a2a`/`agents`/`builtins`.

## 2. MCP-server module — reference port (JS) then parity

- [x] 2.1 js (reference): `/mcp` on `serve(addr,{mcp})` using `@modelcontextprotocol/sdk` `Server` +
      StreamableHTTP server transport; `tools/list` from the toolkit registry; `tools/call`→`execute`;
      `mcp.tools` filter; `onCall`.
- [x] 2.2 python: ported (`mcp` server + streamable-HTTP ASGI).
- [x] 2.3 golang: ported (`mark3labs/mcp-go` `server` + streamable-HTTP handler).
- [x] 2.4 java: ported (official `io.modelcontextprotocol.sdk` server + streamable-HTTP transport).
- [x] 2.5 csharp: ported (`ModelContextProtocol` server + streamable-HTTP transport).
- [x] 2.6 Config parsing (all 5): `mcpServer` serve-profile block; inline `serve` option wins on merge.

## 3. Tests (hermetic, per port) — port's own MCP client as the peer

- [x] 3.1 js: in-process client↔server round-trip — `initialize`→serverInfo; `tools/list` (all
      sources, `inputSchema`=`parameters`); `mcp.tools` narrows; `tools/call` success text
      `isError:false`; erroring tool `isError:true` + survives; `mcp` absent ⇒ no surface.
- [x] 3.2 python: same suite.
- [x] 3.3 golang: same suite (`go test -race`).
- [x] 3.4 java: same suite.
- [x] 3.5 csharp: same suite.
- [x] 3.6 In-process transport proven per port (in-memory/linked pair where the SDK offers one — JS, C# —
      else an ephemeral streamable-HTTP port — Go, Java, Python).

## 4. Round-trip, examples & parity

- [x] 4.1 Gateway round-trip proven per port against the shared `examples/` toolkit (`mcp.json` +
      `skills/hello-world`): serve it as MCP, connect the port's own client, list + call.
- [x] 4.2 Ran all 5 suites independently — green; `tools/list`/`tools/call` shapes spot-checked for
      cross-port parity (name verbatim, `inputSchema`=`parameters`, content/isError mapping).
- [x] 4.3 Updated all 6 READMEs (+ root/SPEC): the `/mcp` streamable-HTTP endpoint on `serve`; `mcpServer`
      config block; `mcp.tools` filter; `onCall`. (stdio dropped from scope.)

## 5. Wrap-up

- [x] 5.1 `openspec validate add-mcp-inbound` passes.
- [x] 5.2 Deferred and called out: a stdio transport (local clients / Claude Desktop), MCP
      resources/prompts/sampling, auth in core, Go CLI `serve --mcp`. Secrets in `headers` still `${ENV}`, never logged.
- [ ] 5.3 Open the PR (or fold into the release) with the change folder + code in one diff.
