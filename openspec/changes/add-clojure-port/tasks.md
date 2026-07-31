# Tasks

Per-language parity is not applicable: this change adds a port and alters no
contract, so the other six ports are untouched by design.

## 1. Foundation
- [ ] 1.1 `clojure/deps.edn` + `clojure/build.cljgo`, both resolving koine from Clojars
- [ ] 1.2 `toolnexus.tool` — Tool / ToolResult / Context, `sanitize`, the toolkit
- [ ] 1.3 `toolnexus.frontmatter` — the strict subset parser, throwing outside it

## 2. Tool sources
- [ ] 2.1 `toolnexus.mcp` — transport as data, stdio + streamable-HTTP, §2 lifecycle, failure isolation
- [ ] 2.2 `toolnexus.skill` — discovery, byte-exact `skill` output, `skillsPrompt`
- [ ] 2.3 `toolnexus.native` + `toolnexus.http` — §0.8 / §0.9
- [ ] 2.4 `toolnexus.builtin` — the §4A toolset and the §0.11 toggles

## 3. Consumption
- [ ] 3.1 `toolnexus.adapter` — OpenAI / Anthropic / Gemini
- [ ] 3.2 `toolnexus.client` — the loop, parallel calls, §10 suspension
- [ ] 3.3 `toolnexus.a2a` — §7A outbound
- [ ] 3.4 `toolnexus.serve` — §7B + §7C inbound
- [ ] 3.5 `toolnexus.core` — the public API

## 4. Verification
- [ ] 4.1 Dual-host test suite behind the count gate
- [ ] 4.2 Conformance run against the shared `examples/` on all three modes, byte-diffed
- [ ] 4.3 `clojure/README.md`
