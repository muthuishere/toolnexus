## Phase 0 — scaffold
- [x] 0.1 `rust/` crate `toolnexus` (edition 2024, tokio, serde_json, thiserror), `cargo test` hermetic
- [x] 0.2 CI job `rust` in ci.yml (no network)
- [x] 0.3 `rust/README.md`; CHANGELOG Unreleased entry

## Phase 1 — ACP client (consumer priority 1)
- [x] 1.1 JSON-RPC stdio framing, id routing, reader task
- [x] 1.2 initialize / session/new / session/prompt
- [x] 1.3 streamed session/update (mpsc)
- [x] 1.4 session/request_permission → host callback; default first-allow; typed timeout error
- [x] 1.5 process-group kill on close — POSIX killpg TERM→grace→KILL (tested); Windows Job Object via process-wrap (compile-checked only, not run); AgentExited carries the exit code
- [x] 1.6 tests against scripted fake agent (12): update kinds, default/handler/timeout permission, slow init, exit mid-turn, cancel, fs/* -32601, cwd, serialization, generate render, group kill

## Phase 2 — skills (§0 5–6, §3)
- [x] 2.1 discovery + frontmatter + first-name-wins (sorted, never traversal order)
- [x] 2.2 `skill` tool + skillsPrompt() byte-exact vs examples/skills — AND cross-checked against Go's actual output over an edge corpus; now a CI job `skills-parity` (conformance/skills_parity/run.sh), mutation-checked
- [x] 2.3 S1–S5 (data/provider, allowlist, inventory, logical base, sample cap)

## Phase 3 — MCP client (§0 2–4, §2) on rmcp
- [x] 3.1 config parsing (mcpServers|servers|mcp, command string+args, enabled/disabled, tools allowlist)
- [x] 3.2 stdio + streamable HTTP; ${ENV} headers never logged; per-server isolation; 30s default
- [x] 3.3 result mapping (isError / structuredContent / joined text + embedded text resource); progress-reset idle timeout via rmcp reset_timeout_on_progress (tested). GAP: non-text content parts (images, §1B) — Rust ToolResult has no parts yet
- [x] 3.4 tests on examples/mcp.json (parse) + examples/mcp-progress-server.mjs (4 idle-timeout assertions, isolation, allowlist)
- [x] 3.5 legacy SSE fallback for remote servers — rmcp has no SSE client, so an in-house one (`mcp/sse.rs`, `mcp-http` feature), tested against an in-process legacy server
- [x] 3.6 §1B non-text content → `ToolResult.parts` on every branch; annotation hints; `resource_tools()`; `load_mcp_with(on_server)`
- [x] 3.7 MCP elicitation bridge (server → §10 `waitFor`) — `ToolkitOptions.wait_for` / `McpLoadOptions.wait_for`, rmcp client handler advertises elicitation only with a `wait_for`; pure mappings mirror Go's tests, live tests on `rust/tests/fixtures/mcp-elicit-server.mjs`

## Phase 4 — core types + native + adapters (§0 1, 7–8)
- [x] 4.1 Tool / ToolResult / ToolContext(CancellationToken); define_tool
- [x] 4.2 OpenAI / Anthropic / Gemini schema adapters

## Phase 5 — rest of §0 (reaches `core` tier) — resumed 2026-11-02 (owner: complete and publish)
- [x] 5.1 Toolkit + http tools + builtins (§4A) — all ten builtins, host boundary (shell / baseDir / confineToBaseDir / killGraceMs, process-group kill), hints, grep context, read/grep truncation metadata, edit diffs; opt-in extras background shells / websearch / notebook_edit
- [x] 5.2 unified client loop (openai + anthropic styles; Gemini is schema-only as in Go), parallel + chained tool calls with `call_id`, hooks, streaming, retries + Retry-After + onError, conversation store (memory + file), metrics, request shaping, API-key-by-host (`resolve_key_test` mirrored), Usage cache tokens, prompt cache, tool tiers, list_models, in-process models
- [x] 5.3 suspension (§10) — wait_for / wait_for_ctx, path A + path B, first-in-order, durable `pending`, streamed `pending` event
- [x] 5.4 `rust` in conformance/options_manifest.json at `core` tier; checker green (client group complete; toolkit `agents`/`waitFor` and the classifier group are printed debt via the new `unported` key)
- [x] 5.5 crates.io leg in release.yml (ENABLE_CRATES + CARGO_REGISTRY_TOKEN in `prod`; first publish cannot use Trusted Publishing — switch after), idempotent version check, preflight + readiness script read rust/Cargo.toml; `cargo publish --dry-run` passes
- [x] 5.6 SPEC.md / CLAUDE.md language lists → eight; rust/README.md; CHANGELOG
- [x] 5.7 ACP host hooks (0.22.0): on_update, reject fallback, cancellable generate, generate_stream; ACP as an in-process client model
- [x] 5.8 runnable examples (basic, agent, native_http, streaming, pending, acp)
- [x] 5.9 docs-site pages (install tab, api/rust 63 pages incl. n/a gap pages, 8 cookbook tabs); site build not run locally (no node_modules)

## Later (→ `full` tier)
- [ ] A2A outbound + `serve` (§7A/§7B), MCP server profile (§7C)
- [ ] translate (§11)
- [ ] classifier + judge + batteries (§8B)
- [x] agents runtime, personas, compaction (§7D–§7F) — `toolnexus::agents`, incl. 0.22.0 `on_compact`/`CompactInfo`, `as_tool_with`, runtime `wait_for`; golang/agents tests mirrored (92). The toolkit `agents` option is A2A (remote agents) in Go and stays with the A2A item above
- [x] relay / resume path (relay tools, `run_with_answer` / `ask_with_answer`, collision guard); MCP elicitation (3.7)
- [ ] the remaining golang relay tests not mirrored: streaming relay, parked-wait_for-across-turns, run-deadline-breaks-parking
- [ ] run the Windows code paths on a Windows host
