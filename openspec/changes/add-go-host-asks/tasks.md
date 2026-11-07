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
- [x] 1.5h ACP host hooks: ACPOptions.OnPermission + OnUpdate, ACPClient.GenerateCtx (ctx cancel -> session/cancel) + GenerateStream, InProcessOptions.GenerateStream (in-process client streams) — acp_hooks_test.go
- [x] 1.6 Tests: host_engine_asks_test.go, agents TestCompactorOnCompact; `go test -race ./...` green

## 2. Other ports (done 2026-11-01 except 2.6e)
- [x] 2.1 js (waitFor gets `{signal}` as a 2nd arg instead of a separate waitForCtx; Toolkit.register needs no lock and the disabled-status race does not exist on a single thread)
- [x] 2.2 python (2026-11-01: all of 1.1–1.5g; concurrent MCP load only with on_server; disabled-status race N/A — asyncio single loop)
- [x] 2.3 java (2026-11-01: all of 1.1-1.5g; HostEngineAsksTest, HostBuiltinExtrasTest, CompactorOnCompactTest, agents/AsToolCtxTest; mcp-progress-server resource exercised; disabled-status write was already under the lock)
- [x] 2.4 csharp (2026-11-01: all of 1.1–1.5g + prompt cache + tool tiers; C# MCP load already locked status, no race; Compactor OnCompact takes no token — the C# BeforeLLM hook carries none)
- [x] 2.5 elixir (2026-11-01; Toolkit register concurrency and the disabled-status race are N/A: immutable toolkit, no shared state)
- [x] 2.6 clojure (2026-11-01: all asks + hints; no Register race or disabled-status race — toolkit is an immutable value, statuses built after the load; cooperative :cancel; :call-id needs :context? true)
- [x] 2.6b Re-run every port against the extended examples/mcp-progress-server.mjs (now also offers a resource) — 2026-11-01: every port's MCP resource-tool test runs against it
- [x] 2.6c SECURITY: host-scoped key resolution (BaseURL host + apiKeyEnv, no cross-provider fallback) — 2026-11-01: in all 7 ports
- [x] 2.6d ACP host hooks onPermission, onUpdate and streaming generate in every port that ships an ACP source — 2026-11-08: present in js/python/java/csharp/elixir/clojure/rust; the permission timeout bounds the whole turn in every port (rust fixed to match, `rust/tests/acp.rs` permission_timeout_bounds_the_whole_turn). Vendor presets are out of scope (ADR 0041).
- [ ] 2.6e ACP ctx cancel (`session/cancel` on a cancelled generate) — golang and rust only; js, python, java, csharp, elixir and clojure have no cancellation input on the in-process generate seam yet (#124)
- [x] 2.7 Fix the disabled-status race wherever the same pattern exists — 2026-11-01: fixed in Go; java/csharp already locked; js/python/elixir/clojure N/A (single thread / immutable toolkit)
