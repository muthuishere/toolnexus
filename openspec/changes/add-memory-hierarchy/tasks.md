# Tasks — add-memory-hierarchy

## Spec

- [ ] Owner resolves D1–D8 (design.md)
- [ ] Spec deltas `specs/agent-home/spec.md`, `specs/conversation-store/spec.md`
- [ ] `openspec validate add-memory-hierarchy --strict`
- [ ] `SPEC.md §7E` — add "Memory hierarchy" (index cap + exact notice, notes tier + slug rule,
      `read`/`list` output format, write safety) and transcript-search paragraph; update the
      "Composition, not API" dream line to point at the recipe
- [ ] Shared fixtures under `examples/` (one persona home with an oversized `MEMORY.md`, a notes
      dir, a canned transcript store) — changed once, used by every port

## Per-language parity checklist

Each port: G1 cap + notice, G2 notes (`note:<slug>`, `read`, `list`, slug rejection), G3
`transcriptSearchTool`, G4 atomic + per-path serialized RMW over the full file, plus a
byte-identity test with all options absent.

- [ ] `js/`
- [ ] `python/`
- [ ] `golang/`
- [ ] `java/`
- [ ] `csharp/`
- [ ] `elixir/` (keep coverage ≥ 95%)
- [ ] `clojure/` (`.cljc`, JVM + cljgo)
- [ ] `rust/`

## Conformance

- [ ] `conformance/options_manifest.json` — `memoryIndexCap`, `notes`, `transcriptSearchTool`
- [ ] Cross-port fixture: identical composed soul, `list` output and search output in all 8 ports
- [ ] Concurrency test: N parallel `memory add` on one file in one runtime ⇒ N entries
- [ ] > 2 MB `MEMORY.md` + `add` ⇒ on-disk file grows, nothing truncated (regression for the
      separate atomic-write bug fix)

## Docs

- [ ] Cookbook recipe "Dreaming" (per language), per design.md "Dreaming": Karpathy-style layout
      (`MEMORY.md` index / `memory/<slug>.md` pages with `[[links]]` / `memory/log.md`), shipped
      `HEARTBEAT.md` doing verify → demote → merge → repair links → log, the "when worth running"
      table, cadence guidance, and a "Further reading" link to Anthropic's "The components of agent
      memory" (2026-11-06) and Karpathy's LLM-wiki pattern
- [ ] Extend site `scenarios/self-improving-agent.mdx` Part 4 (consolidator) to link the Dreaming
      recipe as its transcript-verified successor
- [ ] Per-port README memory section

## Changelog

- [ ] `CHANGELOG.md` `## Unreleased` — what a user gets; name cross-process locking and semantic
      search as not done

## Found while fixing the over-2MB truncation bug (2026-11-08)

- [ ] Parity drift: `replace`/`remove` swap EVERY occurrence in python (`str.replace`) and java (`String.replace`), but only the FIRST in js/golang/rust/elixir/csharp/clojure. Pin "first occurrence" in SPEC §7E + a fixture case (text appearing twice), fix python + java.
- [ ] clojure: atomic memory write (needs a rename/move in koine.fs first; the port forbids interop/reader conditionals).
