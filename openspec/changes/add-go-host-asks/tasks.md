## Design note — no `Permit` option (ask #5)
go-agent-cli asked for `ClientOptions.Permit → Allow|Deny|Ask`. `Hooks.BeforeTool` already
has exactly those three outcomes (nil / error result / `Pending` path B through WaitFor),
fires before every tool in all loops, and is in all 7 ports. A second gate would need its
own ordering rules against BeforeTool. So the change adds only what was missing: hints on
the event. Revisit if a port cannot express it.

## 1. golang (done 2026-11-01)
- [x] 1.1 Toolkit RWMutex; Register/Tools/Get/Execute
- [x] 1.2 LoadMcpWithOptions + McpServerResult; disabled-status race fixed
- [x] 1.3 ClientOptions.WaitForCtx (+ in-process passthrough)
- [x] 1.4 CompactorOptions.OnCompact + CompactInfo
- [x] 1.5 Tool.Hints, builtinHints, mcpHints, BeforeToolEvent.Hints
- [x] 1.5b Compactor fixes (ask #6): keep sole user prompt, no-op when not smaller, CompactInfo.Folded
- [x] 1.5c Opt-in builtins (background shells, websearch, notebook_edit), MCP resource tools, grep context, truncation metadata (ask #7)
- [x] 1.5d AsTool ctx cancel, AsToolWith, runtime Options.WaitFor (ask #7 follow-up)
- [x] 1.5e ToolContext.CallID on every loop execution (Karya)
- [x] 1.5f SECURITY: resolveKey by BaseURL host + APIKeyEnv, no cross-provider fallback (ask #8)
- [x] 1.5g ListModels (ask #9)
- [x] 1.6 Tests: host_engine_asks_test.go, agents TestCompactorOnCompact; `go test -race ./...` green

## 2. Other ports (pending — before the next release)
- [x] 2.1 js (waitFor gets `{signal}` as a 2nd arg instead of a separate waitForCtx; Toolkit.register needs no lock and the disabled-status race does not exist on a single thread)
- [ ] 2.2 python
- [ ] 2.3 java
- [ ] 2.4 csharp
- [ ] 2.5 elixir
- [ ] 2.6 clojure
- [ ] 2.6b Re-run every port against the extended examples/mcp-progress-server.mjs (now also offers a resource)
- [ ] 2.6c SECURITY: port host-scoped key resolution to every port FIRST (all still fall back across providers)
- [ ] 2.7 Fix the disabled-status race wherever the same pattern exists
