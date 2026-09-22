# Tasks

> **Status note (2026-09-22):** this change was originally scoped and partly built before the
> Clojure port shipped. A later pass (this entry) found the manifest, generator, sidebar and all
> 441 per-symbol pages already existed and were mostly filled — the checkboxes below just hadn't
> been updated to say so. This pass closed the remaining real gaps (see each section) rather than
> rebuilding what was already there. Sections 4 and 5 (the coverage gate and the hermetic
> tested-examples CI pipeline) are the genuine remaining work — nothing here builds those.

## 1. Surface manifest (done)

- [x] Reflect the JS public surface from `js/dist/index.d.ts` (`site/scripts/inventory/javascript.mjs`)
- [x] Harvest the top-level public names of the other six ports
- [x] Author `site/src/data/api-surface.json` — 63 entry points from `SPEC.md` §1–§11, each with its seven per-language symbols (57 original + 6 added 2026-09-22: `client/provider-error`, `suspension/answer-output`, `suspension/answer-declined`, `runtime/vocabulary`, `runtime/loop`, `types/attach` — closing the 0.19.0-cycle documentation gap)
- [x] Write `site/scripts/verify-symbols.mjs` — proves every named symbol exists in the port it claims
- [x] Verify clean: 413/413 symbol claims resolved, 28 declared parity gaps (`node site/scripts/verify-symbols.mjs --strict` exits 0)
- [x] **Correction (2026-09-22):** 5 entries (`runtime/agent`, `runtime/runtime`, `runtime/handle`, `runtime/budgets`, `runtime/task-tool`) wrongly declared Clojure as a parity gap ("no §7D sub-agent runtime") — verified false against `clojure/src/toolnexus/agents/runtime.cljc` (a complete, tested runtime: `create-runtime`/`spawn`/`wait`/`interrupt`/`close`/`resume`/`handles`/`inspect`/`task-tool`/`agent-tool`, hierarchical budgets). Manifest corrected, stale `parityNote`s cleared, 5 pages rewritten with real content.

## 2. Parity gaps the manifest surfaced

These are real, recorded in `parityNote` on the entry. Each needs an owner decision — document as
a gap, or close the gap in code. **Not** blockers for the docs work.

- [ ] `suspension/relay` — Go only (`golang/relay.go`); js/python/java/csharp/elixir/clojure do not ship it
- [ ] `translate/inbound` — Go keeps `openAIMessagesToAnthropic` unexported (`golang/translate.go:283`)
- [ ] `native/collect` — no equivalent in python/golang/elixir
- [ ] `runtime/task-tool` — only JS exposes a constructible symbol; elsewhere it is the `task` builtin (Clojure now ships a real `task-tool` fn — no longer a gap for that port; python/golang/java/csharp/elixir remain the gap)
- [ ] `mcp/parse-config` — Java's parser is package-private (`McpSource.java:141`), unlike C#'s public `McpSource.ParseConfig`; found while filling the pre-existing stub 2026-09-22, recorded rather than fixed (out of scope for a docs pass)
- [ ] Cross-port behavioral drift, found while writing docs, NOT a docs bug — needs an owner ruling: `ProviderError.retryAfter` carries the raw `Retry-After` header string/seconds in six ports, but C#'s `ProviderException.RetryAfterMs` is **milliseconds**. Documented plainly on each port's `client/provider-error` page; not normalized away.

## 3. Page generation (done, one item descoped)

- [x] Page template: signature, when to use, why / what instead, 3 examples, cross-language equivalents, parity note (`site/scripts/generate-pages.mjs`)
- [x] Generator writing `site/src/content/docs/api/<lang>/<group>/<member>.mdx` from the manifest
- [x] Sidebar generated from the manifest (grouped by SPEC section) via `starlight-sidebar-topics`, one topic per port (`site/scripts/lib/sidebar.mjs`)
- [x] **Descoped, not done as originally written:** `api/index.mdx` was NOT reduced to a bare directory of links. It stays a deliberate ~950-line "seven-tab tour" (toolkit, skills, MCP, adapters, client, native/HTTP tools, builtins, sub-agents, A2A, suspension) that cross-links out to the per-symbol pages for depth — a 2026-09-22 pass added the missing cross-links (resilience, streaming, memory, hooks, observability, multimodal, provider-error, classifier, harness/loop) rather than stripping the page down. Revisit only if the tour page's size becomes its own maintenance problem.
- [ ] Redirects from the six old per-port `.mdx` stubs (`api/javascript.mdx` etc.) — these still exist as freestanding pages, not redirects; not done, not blocking

## 4. Coverage gate — genuinely not built

- [ ] Fail the build when a manifest entry lacks a page in any of the seven ports
- [ ] Fail the build on an orphaned page with no manifest entry
- [ ] Fail the build when a page omits when-to-use, why, or has fewer than 3 examples

`site/scripts/verify-symbols.mjs` checks symbol *names* exist in source — it does not check page
*completeness*, and nothing runs it in CI today. This is the real remaining gap: a future page
could regress to a TODO stub (as 15 pre-existing pages had, silently, until a 2026-09-22 pass
found and filled them) with nothing catching it.

## 5. Tested examples (hermetic) — genuinely not built

- [ ] Snippet extractor: pull tagged fences out of the MDX into per-language projects
- [ ] Seven runners (js/python/golang/java/csharp/elixir/clojure) that compile **and execute** each snippet
- [ ] Wire to the shared `examples/` fixtures + a mock LLM (reuse `benchmarks/mock_llm.py`) — no network, no live LLM
- [ ] New `docs-examples` job in `.github/workflows/ci.yml`

Note from the 2026-09-22 pass: doing this by hand once (the Go agent extracted and ran all 18 of
its new snippets against the real module) caught 3 real bugs the prose would otherwise have
shipped — a `Retries: 0` semantics trap, an incomplete file-extension whitelist, and a `Name`
field population quirk. That is the argument for building this properly rather than relying on
an author doing it manually per page.

## 6. Content — 63 entries × 7 ports = 441 pages (done)

Examples sourced from real repo code (`examples/`, per-port `examples/` and test suites, per port).

- [x] §1 core types (4 entries, incl. `types/attach`) · [x] §2 MCP (5) · [x] §3 skills (3)
- [x] §4 toolkit + adapters (4) · [x] §4A builtins (2) · [x] §6 native (3) · [x] §7 HTTP (1)
- [x] §7A A2A outbound (3) · [x] §7B/§7C serve (4) · [x] §7D runtime (8, incl. `runtime/vocabulary`
      and `runtime/loop`) · [x] §7E persona (3) · [x] §7F compaction (1)
- [x] §8 client (10, incl. `client/provider-error`) · [x] §8B judge (3)
- [x] §10 suspension (8, incl. `answer-output`/`answer-declined`) · [x] §11 translate (2)

All 441 pages confirmed with zero `{/* TODO */}` markers remaining (`grep -rl "{/\* TODO" site/src/content/docs/api/` → empty), 2026-09-22.

## 7. Verify

- [x] `cd site && npm run build` clean — 497 pages built, 0 errors (fixed 2 real MDX defects found
      by this: an unescaped `{...}` in prose parsed as a JS expression, and a stray `</content>`
      closing tag with no opener, both leftover artifacts from automated content generation)
- [x] Internal link sweep — 0 broken links across the whole site (accounting for the `/toolnexus`
      base path)
- [ ] Coverage gate green — not built yet, see §4
- [ ] `docs-examples` CI job green across all seven ports — not built yet, see §5
