# Tasks — add-memory-hierarchy

## Spec

- [x] Owner resolves D1–D10 (design.md) — recommended defaults for D1–D8, D10; D9 = option C
- [x] Spec deltas `specs/agent-home/spec.md`, `specs/conversation-store/spec.md`
- [x] `openspec validate add-memory-hierarchy --strict`
- [x] `SPEC.md §7E` — add "Memory hierarchy" (index cap + exact notice, notes tier + slug rule,
      `read`/`list` output format, write safety) and transcript-search paragraph; update the
      "Composition, not API" dream line to point at the recipe
- [x] Shared fixtures under `examples/` (one persona home with an oversized `MEMORY.md`, a notes
      dir, a canned transcript store) — changed once, used by every port

## G5 dreaming

- [x] SPEC §7E: `DREAM_PROMPT` constant (exact text), `dreamer()` + `fromDir` `dream` option,
      `memory/log.md` entry format; amend "Composition, not API"
- [x] Fixture `examples/dreaming/` — written + passing in all 8 ports (home with one contradicted line + a transcript store + a
      scripted model) asserting the four "Built-in dreamer preset" scenarios (Mt. Moon = build gate) in all 8 ports
- [x] `conformance/options_manifest.json` — `dreamer`, `fromDir.dream`

## Per-language parity checklist

Each port: G1 cap + notice, G2 notes (`note:<slug>`, `read`, `list`, slug rejection), G3
`transcriptSearchTool`, G4 atomic + per-path serialized RMW over the full file, plus a
byte-identity test with all options absent.

- [x] `js/`
- [x] `python/`
- [x] `golang/`
- [x] `java/`
- [x] `csharp/`
- [x] `elixir/` (keep coverage ≥ 95%)
- [x] `clojure/` (`.cljc`, JVM + cljgo)
- [x] `rust/`

## Conformance

- [x] `conformance/options_manifest.json` — `memoryIndexCap`, `notes`, `transcriptSearchTool`
- [x] Cross-port fixture: identical composed soul, `list` output and search output in all 8 ports
- [x] Concurrency test: N parallel `memory add` on one file in one runtime ⇒ N entries
- [x] > 2 MB `MEMORY.md` + `add` ⇒ on-disk file grows, nothing truncated (regression for the
      separate atomic-write bug fix)

## Docs

- [x] Docs page "Dreaming" (per language) — leads with the `fromDir(dir, { dream })` one-liner, then customisation (`instructions`, `DREAM.md`), per design.md "Dreaming": Karpathy-style layout
      (`MEMORY.md` index / `memory/<slug>.md` pages with `[[links]]` / `memory/log.md`), shipped
      `HEARTBEAT.md` doing verify → demote → merge → repair links → log, the "when worth running"
      table, cadence guidance, and a "Further reading" link to Anthropic's "The components of agent
      memory" (2026-11-06) and Karpathy's LLM-wiki pattern
- [x] Extend site `scenarios/self-improving-agent.mdx` Part 4 (consolidator) to link the Dreaming
      recipe as its transcript-verified successor
- [x] Per-port README memory section (READMEs have no persona section; a Dreaming link was added to each README's docs row)

## Changelog

- [x] `CHANGELOG.md` `## Unreleased` — what a user gets; name cross-process locking and semantic
      search as not done

## Found while fixing the over-2MB truncation bug (2026-11-08)

- [x] Parity drift — python + java fixed (SPEC §7E pinned + fixture cases `replace-first-occurrence-only` / `remove-first-occurrence-only` added; python + java fixes still pending): `replace`/`remove` swap EVERY occurrence in python (`str.replace`) and java (`String.replace`), but only the FIRST in js/golang/rust/elixir/csharp/clojure. Pin "first occurrence" in SPEC §7E + a fixture case (text appearing twice), fix python + java.
- [ ] (deferred, named in CHANGELOG) clojure: atomic memory write (needs a rename/move in koine.fs first; the port forbids interop/reader conditionals).

## PR #184 review follow-ups (2026-11-08)

- [x] SPEC §7E + delta: DREAM_PROMPT removes only what the transcripts contradict ("contradicted by")
- [x] SPEC §7E + delta: dream guard — dreamer's `memory` refuses `user` and `replace`/`remove` on `note:log`; fixture `D7_dreamGuard` + `D8` in all 8 ports (js, python, golang, java, csharp, elixir, clojure, rust)
- [x] SPEC §7E: clojure's in-place write + in-process lock listed as a permitted per-port difference
- [x] Fixture: ASCII-only case folding (`ÉLITE` vs `élite`); catches a JS `toLowerCase()` mutation
- [x] elixir: random temp name + exclusive open; java: no silent non-atomic move fallback
- [ ] (deferred) snapshot before each dream pass
