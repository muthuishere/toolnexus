# Proposal — add-memory-hierarchy

## Why

Anthropic's research note "The components of agent memory" (2026-11-06,
anthropic.com/research/components-of-agent-memory) measured what makes agent memory help or
hurt. Four findings map directly onto gaps in `SPEC.md §7E` (agent home) and `§7F`:

1. **The injected layer must stay small.** Claude Code loads only the first 200 lines / 25 KB
   of `MEMORY.md` and treats it as an *index* that points at detail files. Today §7E injects
   `MEMORY.md` with the same 2 MB cap as every bootstrap file, so a persona that diligently
   records everything grows a soul that costs tokens on every turn.
2. **Bloat is not neutral, it hurts.** In the article's game run, Sonnet 3.7 accumulated 413
   memory files and earned 1 badge; Fable 5 kept 10 files and earned 4. More memory was worse.
3. **Dreaming must check notes against ground truth — the transcripts.** The consolidation pass
   that fixed a wrong belief (the Mt. Moon case) did so by re-reading what actually happened,
   not by re-reading its own notes. §7E's "dream" recipe today has no read path into the
   `ConversationStore`, so a dreaming agent can only launder its own notes.
4. **Concurrent writers to one shared page lose facts** (41/57 retained) while one page per
   writer kept all of them (57/57). §7E's `memory` tool is a read-modify-write on a single
   `MEMORY.md` with no serialization.
5. **Tell the model what memory is for, not rigid rules** — a "record only failures" rule cost
   Opus 5 −0.234. This shapes tool *descriptions* and the recipe, not new options.

## What changes

All opt-in. **A persona built without the new options is byte-identical to today** (same soul,
same `memory` tool schema and description, same files on disk).

- **G1 — Index cap.** A new `fromDir` option `memoryIndexCap` (`{maxLines, maxBytes}`, preset
  `200` / `25600`) applies a second, smaller injection cap to `MEMORY.md` only. On truncation the
  injected copy ends with a fixed notice telling the agent the index is over budget and to move
  detail into notes. Disk is untouched. Off by default (see design D1).
- **G2 — Notes tier.** When `notes` is enabled, the `memory` tool accepts `target: "note:<slug>"`
  writing `<home>/memory/<slug>.md`, plus two read actions `read` and `list`. Notes are **never**
  injected into the soul. Slugs are validated (`^[a-z0-9][a-z0-9._-]{0,63}$`, no `..`), so a note
  cannot escape `<home>/memory/`.
- **G3 — Transcript search.** `transcriptSearchTool(store, opts?)` — a read-only tool over a
  `ConversationStore` that implements `list()`: `query` → matching messages with
  `{conversationId, index, role, snippet}`. The only new top-level surface; justified against
  ADR 0016/0017 in design.md.
- **G4 — Safe concurrent writes.** Every `memory` write is atomic (temp + fsync + rename, the
  `FileConversationStore` rule) and read-modify-write is serialized per resolved file path within
  a runtime. Atomic write is landing separately as part of the bug fix for the memory tool
  writing its 2 MB-capped read back to disk (truncating > 2 MB files); this change pins the
  per-file serialization and the contract both rely on.
- **Docs.** A Cookbook recipe "Dreaming": a heartbeat persona given transcript search + notes
  that checks `MEMORY.md` claims against transcripts, prunes, and keeps the index small.

## Out of scope

- **A wiki builder as library surface.** The article's wiki grew to 1.77x the corpus and paid off
  only on multi-document questions. A host can build one from notes + transcript search.
- Embedding / semantic search. G3 is substring matching; ranking is the host's.
- Cross-process write locking. Within one runtime only (same stance as `FileConversationStore`).
- Changing the default 2 MB cap for other bootstrap files.

## Impact

- Capabilities: `agent-home` (MODIFIED soul composition + memory tool, ADDED notes, ADDED
  serialized writes), `conversation-store` (ADDED transcript search tool).
- `SPEC.md §7E` gains "Memory hierarchy" (index cap, notes, write safety) and a transcript-search
  paragraph; §7E "Composition, not API" dream line points at the recipe.
- All eight ports: js, python, golang, java, csharp, elixir, clojure, rust.
- `conformance/options_manifest.json`: `memoryIndexCap`, `notes`, `transcriptSearchTool`.
- `CHANGELOG.md` `## Unreleased`.
