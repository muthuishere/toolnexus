# Tasks — add-mcp-serve-handler (#139, ADR 0039)

## 0. Design
- [x] 0.1 ADR 0039 (PROPOSED) with the toolnexus/host split
- [x] 0.2 SPEC §7C.1
- [x] 0.3 Owner accepts ADR 0039

## 1. golang (reference)
- [x] 1.1 `MCPHandler(cfg) *MCPHTTPHandler` (http.Handler) — own JSON-RPC transport, no new dependency
- [x] 1.2 Authenticate + WWWAuthenticate + IdentityFrom/RequestFrom/MCPSessionIDFrom
- [x] 1.3 ListTools per request, not-found never executes, NotifyToolsChanged
- [x] 1.4 BeforeCall allow/deny/confirm, elicitation on the POST stream, shaped Result fallback
- [x] 1.5 Tool.Annotations passthrough (Hints-derived fallback)
- [x] 1.6 OnCall MCPCallEvent; ServerInfo(ctx); resources/prompts hooks; SessionIDs; Stateless
- [x] 1.7 Tests (mcphandler_test.go, mcp-go client interop + raw protocol 2025-06-18 checks) and examples/mcphandler
- [x] 1.7b Review follow-ups (PR #140): ToolResult.Structured/Meta + Tool.OutputSchema (SPEC §1, also on the §7C Serve path), request `_meta` → MCPRequestMetaFrom, per-handler ProtocolVersions (2025-06-18 default, 2025-11-25 opt-in with the isError validation rule), StatelessSessionID, serverInfo.title — tests in mcphandler_test.go
- [x] 1.8 Shared cross-port conformance fixture `conformance/mcp_handler/cases.json`, replayed by every port's suite

## 2. Other ports (parity)
Each port carries BOTH halves: (a) the SPEC §1 core-type additions — `ToolResult.structured`/`meta`,
`Tool.outputSchema` — mapped on its existing §7C serve path (absent ⇒ byte-identical), and (b) the
§7C.1 handler, including `_meta`, `protocolVersions`, `statelessSessionId`, `title`.
- [x] 2.1 js
- [x] 2.2 python
- [x] 2.3 java
- [x] 2.4 csharp
- [x] 2.5 elixir
- [x] 2.6 clojure
- [x] 2.7 rust

## 3. Docs
- [x] 3.1 CHANGELOG ## Unreleased (all eight ports)
- [x] 3.2 Docs site page for the embeddable handler (`api/<lang>/serve/mcp-handler`, all eight)
