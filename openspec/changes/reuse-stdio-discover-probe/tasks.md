# Tasks — reuse-stdio-discover-probe

## Spec

- [x] Spec delta `specs/mcp-load-lifecycle/spec.md`
- [x] `SPEC.md §2.2a` amended (reuse on fast reply; respawn only on silence)
- [x] Shared fixture `examples/mcp-2026/legacy-stdio/server.mjs` (+ README)
- [x] `CHANGELOG.md` `## Unreleased` entry
- [ ] `openspec validate reuse-stdio-discover-probe --strict`

## Per-language parity checklist

Each port: implementation + test against the shared fixture asserting `error` ⇒ 1 start and
`silent` ⇒ 2 starts, both with `echo` ⇒ `echo:hi`.

- [ ] `js/` — adapter `Transport` over the probe child's pipes for `@modelcontextprotocol/sdk`
- [ ] `python/` — `mcp.ClientSession` over streams fed by the probe child's pipes
- [ ] `golang/` — `mark3labs/mcp-go` `transport.NewIO` over the probe child's pipes
- [ ] `java/` — `McpClientTransport` over the probe child's Process streams
- [ ] `csharp/` — `StreamClientTransport` over the probe child's Process streams
- [ ] `elixir/` — in-house client continues on the same Port
- [ ] `clojure/` — in-house client continues on the same process
- [ ] `rust/` — `rmcp` transport over the existing child's stdio

## Verification

- [ ] Narrowest suite per port green
- [ ] `interop/mcp2026/matrix.sh` (8 clients x 8 servers) green
- [ ] Before/after init timing (java, csharp, clojure) — only when 1-min load < 4
