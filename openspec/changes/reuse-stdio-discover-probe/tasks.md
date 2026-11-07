# Tasks — reuse-stdio-discover-probe

## Spec

- [x] Spec delta `specs/mcp-load-lifecycle/spec.md`
- [x] `SPEC.md §2.2a` amended (reuse on fast reply; respawn only on silence)
- [x] Shared fixture `examples/mcp-2026/legacy-stdio/server.mjs` (+ README)
- [x] `CHANGELOG.md` `## Unreleased` entry
- [x] `openspec validate reuse-stdio-discover-probe --strict`

## Per-language parity checklist

Each port: implementation + test against the shared fixture asserting `error` ⇒ 1 start and
`silent` ⇒ 2 starts, both with `echo` ⇒ `echo:hi`.

- [x] `js/` — adapter `Transport` over the probe child's pipes for `@modelcontextprotocol/sdk`
- [x] `python/` — `mcp.ClientSession` over streams fed by the probe child's pipes
- [x] `golang/` — `mark3labs/mcp-go` `transport.NewIO` over the probe child's pipes
- [x] `java/` — `McpClientTransport` over the probe child's Process streams
- [x] `csharp/` — `StreamClientTransport` over the probe child's Process streams
- [x] `elixir/` — in-house client continues on the same Port
- [x] `clojure/` — in-house client continues on the same process
- [x] `rust/` — `rmcp` transport over the existing child's stdio

## Verification

- [x] Narrowest suite per port green
- [x] `interop/mcp2026/matrix.sh` (8 clients x 8 servers) green
- [ ] Before/after init timing (java, csharp, clojure) — NOT RUN: 1-min load was 11–21 during the work (parallel agents); the benchmark gate (< 4) was never met. Fixture spawn counts (2 ⇒ 1) are the evidence so far.

## Known gaps (from the per-port passes)

- python: the stdio connect budget is now `timeout + min(timeout, 5 s)` (it was one `timeout` covering probe + init, so a silent server could never reach the frozen path inside it). A dead stdio server can take up to ~2× `timeout` to report `failed`.
- java / js: the SDK's own stdio transport always spawns, so each has a small adapter transport (`ProcessStdioTransport`, `AdoptedStdioTransport`) used only for the reused child.
- rust / elixir / java: a reused child keeps the probe's stderr (inherited); a freshly spawned frozen child had stderr nulled (elixir: `stderr_path` is ignored for a reused child).
- Not all full suites ran: js (2 files, 125 tests), golang (filtered `-race`), java (MCP classes) ran narrowed; python (901), elixir (992, cov 95.1 %), clojure (743, JVM only; cljgo not run) ran full.
