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
- [ ] 3.5 legacy SSE fallback for remote servers (Go falls back; rmcp has streamable HTTP only)

## Phase 4 — core types + native + adapters (§0 1, 7–8)
- [x] 4.1 Tool / ToolResult / ToolContext(CancellationToken); define_tool
- [x] 4.2 OpenAI / Anthropic / Gemini schema adapters

## Phase 5 — rest of §0 (reaches `core` tier) — ON HOLD 2026-11-01 (spotlight moved to Go; owner decision O1)
- [ ] 5.1 Toolkit + http tools + builtins (§4A)
- [ ] 5.2 unified client loop, streaming, hooks, retries, conversation store
- [ ] 5.3 suspension (§10)
- [ ] 5.4 add `rust` to conformance/options_manifest.json at `core` tier; checker green
- [ ] 5.5 crates.io leg in release.yml (ENABLE_CRATES, Trusted Publishing) + preflight Cargo.toml version
- [ ] 5.6 SPEC.md / CLAUDE.md language lists → eight

## Later
- [ ] A2A, translate (§11), classifier, agents runtime → `full` tier
