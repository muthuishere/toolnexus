# Tasks

> **Status note (2026-09-22):** this change was originally scoped and partly built before the
> Clojure port shipped. A later pass (this entry) found the manifest, generator, sidebar, all
> 441 per-symbol pages, AND the hermetic tested-examples CI pipeline (§5) already existed — the
> checkboxes below just hadn't been updated to say so, and an earlier revision of this very file
> wrongly claimed §5 wasn't built (see §5's own correction note for what that cost: a merge to
> `main` with 16 new + 33 untagged-and-broken example failures nobody had run). §4, the coverage
> gate that would catch a page regressing to an unfilled stub, was then built the same day — its
> structural half blocks in CI now; a 59-item content-depth backlog is reported and open, and
> needs one owner ruling before it can be cleared (see §4).

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
- [x] `mcp/parse-config` — CLOSED 2026-09-22: Java's `McpSource.parseConfig` is now `public static` (it was package-private at `McpSource.java:141`), with Javadoc, a test against the shared `examples/mcp.json` fixture, and a filled docs page whose three compiled examples prove it is reachable from outside the package. The manifest no longer records a null for this entry. What remains is only a naming difference: C# and Java hang the parser off the type (`McpSource.ParseConfig` / `McpSource.parseConfig`) where the other five ports expose a free `parseMcpConfig`-style function.
- [ ] Cross-port behavioral drift, found while writing docs, NOT a docs bug — needs an owner ruling: `ProviderError.retryAfter` carries the raw `Retry-After` header string/seconds in six ports, but C#'s `ProviderException.RetryAfterMs` is **milliseconds**. Documented plainly on each port's `client/provider-error` page; not normalized away.

## 3. Page generation (done, one item descoped)

- [x] Page template: signature, when to use, why / what instead, 3 examples, cross-language equivalents, parity note (`site/scripts/generate-pages.mjs`)
- [x] Generator writing `site/src/content/docs/api/<lang>/<group>/<member>.mdx` from the manifest
- [x] Sidebar generated from the manifest (grouped by SPEC section) via `starlight-sidebar-topics`, one topic per port (`site/scripts/lib/sidebar.mjs`)
- [x] **Descoped, not done as originally written:** `api/index.mdx` was NOT reduced to a bare directory of links. It stays a deliberate ~950-line "seven-tab tour" (toolkit, skills, MCP, adapters, client, native/HTTP tools, builtins, sub-agents, A2A, suspension) that cross-links out to the per-symbol pages for depth — a 2026-09-22 pass added the missing cross-links (resilience, streaming, memory, hooks, observability, multimodal, provider-error, classifier, harness/loop) rather than stripping the page down. Revisit only if the tour page's size becomes its own maintenance problem.
- [ ] Redirects from the six old per-port `.mdx` stubs (`api/javascript.mdx` etc.) — these still exist as freestanding pages, not redirects; not done, not blocking

## 4. Coverage gate — built 2026-09-22 (`site/scripts/coverage-gate.mjs`)

Structural checks, BLOCKING in CI (`docs-coverage` job, `.github/workflows/ci.yml`):

- [x] Fail the build when a manifest entry lacks a page in any of the seven ports
- [x] Fail the build on an orphaned page with no manifest entry
- [x] Fail the build when a page is still an unfilled generator scaffold (a `{/* TODO */}` marker)
- [x] Fail the build when a declared parity gap (`"lang": null`) has no caution aside marking the
      absence, or says nothing about what to reach for instead
- [x] Run `verify-symbols.mjs --strict` in CI at all — it already existed but ran **nowhere**, so a
      renamed export would have surfaced as a broken page rather than a failed build

Content-depth checks, REPORTED but not yet blocking (`--strict-content` opts in):

- [ ] Fail the build when a shipped page omits when-to-use, why, or has fewer than 3 examples —
      **59 outstanding**: 29 `no-why`, 29 `too-few-examples`, 1 `no-when`, concentrated in four
      entries written before that contract settled (`types/tool-result`, `types/context`,
      `mcp/parse-config`, `adapters/openai` — ~6 ports each). The gate prints the count every run
      so it cannot quietly grow. Clearing it needs an owner ruling first: **should a *type* page
      owe a "Why this and not the alternative" section at all?** For a data shape there is often no
      alternative to name, and forcing the heading produces filler — which is worse than its
      absence. Narrow the rule by entry kind, or write the 59; do not split the difference.

The tiering is deliberate. The structural checks catch an **absence** — nothing on the page tells a
reader, or a reviewer, that anything is wrong — which is exactly how 15 pages sat as unfilled
scaffolds (including `client/create` and `toolkit/create` in five ports) and how 33 Clojure examples
sat untagged and uncompiled until 2026-09-22. Each structural check was mutation-tested when it
landed: delete a page ⇒ `missing-page`; reinstate a TODO ⇒ `unfilled-stub`; add an unreferenced
page ⇒ `orphan-page`; each exits 1 under `--strict`.

## 5. Tested examples (hermetic) — already built, this pass just made it pass

**Correction, same class of error as the manifest one above:** this was marked "not built" earlier
in this same pass, which was wrong — checked without reading `.github/workflows/ci.yml` first.
It already existed, complete:

- [x] Snippet extractor: `site/scripts/extract-snippets.mjs` pulls every ` ```<fence> test ` block
      out of the MDX into `site/tests/snippets/<lang>/`
- [x] Seven runners (`site/tests/runners/{javascript,python,golang,java,csharp,elixir,clojure}.sh`)
      that compile **and execute** each snippet — Clojure's runs FOUR ways (JVM `-M`, JVM REPL,
      `cljgo run`, `cljgo build` AOT) on both hosts, because that port's whole claim is one source
      tree behaving identically on two runtimes
- [x] Hermetic — no network, no live LLM; each snippet builds its own stub HTTP server or scripted
      transport inline
- [x] `docs-examples` job in `.github/workflows/ci.yml` ("Docs examples (seven ports)")

**What this pass actually did:** merged PR #103 without first running this job locally, and it
came back red on `main` — 3 pre-existing failures (unrelated: `skills/list`, `suspension/resume`,
`client/resilience` — none of them touched this session) plus **16 new failures**, all in pages
this session wrote, none of them caught before merge because they were never executed. Root
causes, for the record: a missing import, two examples that assumed `pytest` was available in a
plain-script runner, a `ToolResult.parts` item passed as a raw object instead of `to_dict(...)`,
a scenario whose audio MIME type was accidentally one the target style already supports (so
nothing was actually unsupported), a Java constant that isn't public outside its package, a
lambda parameter shadowing an enclosing method parameter, a missing test fixture file, a C#
namespace ambiguity (`Toolnexus.Agent` vs `Toolnexus.Agents.Agent`) needing full qualification,
and two `Completion`-gate scenarios missing the second scripted turn that would have actually
closed the gate. Separately, **all 33 of the new Clojure examples** (11 pages) had never been
tagged ` test` at all — they used an `ns`-declaration, transliterated-from-Java style that
doesn't match how a Clojure docs snippet has to be written (flat top-level forms, real function
names like `client/create-client` not `client/create`, a real local `koine.server` mock instead
of an invented transport). Tagged and rewritten properly; also fixed a REPL-mode stdout-ordering
bug that would have made any example whose first output was exactly `(println "OK")` fail under
`clojure -r` specifically.
- [x] Verified: `bash site/tests/run-all.sh <lang>` clean for js/python/java/csharp/elixir/clojure;
      golang matches its pre-existing baseline exactly (no new failures, none introduced)

The lesson, stated plainly so it doesn't repeat: **an example nobody has run is not verified, no
matter how carefully it was written or reviewed** — this is the same "vacuous fixture" failure
mode from the `fix-consumer-issues-86-93` batch, just in docs instead of code. The fix there was
"no ordering task is done without stating what was mutated and what failed"; the fix here is the
same shape — no docs page is done without having actually run `bash site/tests/run-all.sh <lang>`
against it, not just eyeballing the code.

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
- [ ] Coverage gate — not built yet, see §4
- [x] `docs-examples` CI job — exists (see §5's correction), and green across all seven ports after
      this pass fixed the 16 new + 33 untagged failures it (correctly) caught. Not yet re-verified
      by an actual GitHub Actions run at the time of writing — verified locally via
      `bash site/tests/run-all.sh <lang>` per language; the next push confirms it in CI.
