# Tasks

## 1. Surface manifest (done)

- [x] Reflect the JS public surface from `js/dist/index.d.ts` (`site/scripts/inventory/javascript.mjs`)
- [x] Harvest the top-level public names of the other five ports
- [x] Author `site/src/data/api-surface.json` — 53 entry points from `SPEC.md` §1–§11, each with its six per-language symbols
- [x] Write `site/scripts/verify-symbols.mjs` — proves every named symbol exists in the port it claims
- [x] Verify clean: 304/304 symbol claims resolved, 14 declared parity gaps

## 2. Parity gaps the manifest surfaced

These are real, recorded in `parityNote` on the entry. Each needs an owner decision — document as
a gap, or close the gap in code. **Not** blockers for the docs work.

- [ ] `suspension/relay` — Go only (`golang/relay.go`); js/python/java/csharp/elixir do not ship it
- [ ] `translate/inbound` — Go keeps `openAIMessagesToAnthropic` unexported (`golang/translate.go:283`)
- [ ] `native/collect` — no equivalent in python/golang/elixir
- [ ] `runtime/task-tool` — only JS exposes a constructible symbol; elsewhere it is the `task` builtin

## 3. Page generation

- [ ] Page template: signature, when to use, why / what instead, 3 examples, cross-language equivalents, parity note
- [ ] Generator writing `site/src/content/docs/api/<lang>/<group>/<member>.mdx` from the manifest
- [ ] Sidebar generated from the manifest (grouped by SPEC section), replacing the six hand-listed entries
- [ ] `api/index.mdx` reduced to a cross-language directory of links
- [ ] Redirects from the six old per-port pages

## 4. Coverage gate

- [ ] Fail the build when a manifest entry lacks a page in any of the six ports
- [ ] Fail the build on an orphaned page with no manifest entry
- [ ] Fail the build when a page omits when-to-use, why, or has fewer than 3 examples

## 5. Tested examples (hermetic)

- [ ] Snippet extractor: pull tagged fences out of the MDX into per-language projects
- [ ] Six runners (js/python/golang/java/csharp/elixir) that compile **and execute** each snippet
- [ ] Wire to the shared `examples/` fixtures + a mock LLM (reuse `benchmarks/mock_llm.py`) — no network, no live LLM
- [ ] New `docs-examples` job in `.github/workflows/ci.yml`

## 6. Content — 53 entries × 6 ports = 318 pages

Examples sourced from real repo code (`examples/`, per-port `examples/`, port test suites).

- [ ] §1 core types (3 entries) · [ ] §2 MCP (5) · [ ] §3 skills (3) · [ ] §4 toolkit + adapters (4)
- [ ] §4A builtins (2) · [ ] §6 native (3) · [ ] §7 HTTP (1) · [ ] §7A A2A outbound (3)
- [ ] §7B/§7C serve (4) · [ ] §7D runtime (5) · [ ] §7E persona (3) · [ ] §7F compaction (1)
- [ ] §8 client (8) · [ ] §10 suspension (6) · [ ] §11 translate (2)

## 7. Verify

- [ ] `cd site && npm run build` clean
- [ ] Coverage gate green
- [ ] `docs-examples` CI job green across all six ports
