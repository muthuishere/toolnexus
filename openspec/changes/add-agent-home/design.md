# Design — add-agent-home

## Context

Built entirely on the shipped `§7D` runtime — no runtime changes. The js spike
(`js/spike/home.ts` + `home-demo.ts`, 15/15) is the behavioral reference. Design provenance:
`docs/references/agent-fundamentals-audit-2026-07-17.md` (openclaw bootstrap order + memory
layers + heartbeat; hermes frozen-snapshot + MEMORY.md/USER.md). This records decisions and
the spike→shipped deltas only.

## Goals / Non-Goals

**Goals:**
- The persona archetype buildable in ~10 lines: `fromDir("~/ava").run(...)` or
  `startAgent(...)`.
- Everything rides shipped seams: `onSpawn` (frozen-snapshot injection point at session
  start), the six verbs (`post`/`wake` drive the heartbeat), the runtime-wide store.
- One spec'd surface across six ports — reconcile the `add-subagents` drift.

**Non-Goals:**
- Channels/gateway (messenger's job); compaction; embeddings/semantic recall; a built-in
  consolidation policy (recipe only).

## Decisions

1. **Soul composition is frozen at session start.** `fromDir` reads the bootstrap files once
   and composes them into the `soul` (system prompt). Because the runtime injects `soul` at
   `onSpawn` and never mutates past context, memory is a frozen snapshot for free — the
   cache-stability doctrine holds with no new mechanism.
2. **`memory` writes go to disk, never to the live prompt** (hermes). The tool description
   says so explicitly to the model ("loads at the START of your next session"). This is the
   whole reason a long-lived persona stays cache-stable; H4 proves the next session re-reads.
3. **Bootstrap order is fixed and identity-first**: `AGENTS, SOUL, IDENTITY, USER, TOOLS,
   HEARTBEAT, MEMORY` (openclaw). Absent files are skipped; present ones become `## <file>`
   sections. 2 MB/file cap with a truncation notice.
4. **Heartbeat = the unsolicited rail.** A timer `post`s a tick then `wake`s; ticks coalesce
   (§7D drain), so a slow beat can't pile up. `HEARTBEAT_OK` is stripped so silence is the
   default; only a substantive reply surfaces. The clock is the runtime's injectable clock —
   fixtures run on the virtual clock.
5. **`memory` is file-backed and opt-in**, not a default builtin: it only exists when a home
   dir is wired (via `fromDir`, or `memoryTool(dir)` added to `uses.tools`). The §4A builtins
   are untouched — this is why the modified `builtin-tools` note is small.
6. **Drift reconciliation**: ports that shipped `agentFromDir`/`startAgent` for the
   `add-subagents` S12 checks (python, java) refactor to this spec's names/semantics; ports
   that kept them test-local (js, go, csharp, elixir) promote them. One shared
   `examples/persona-agent/` fixture proves parity.
7. **Recipes carry the "composable" load, not an API** (owner: composition is if/loop, not a
   framework). `dream`/consolidation = `startAgent` whose HEARTBEAT.md says "fold notes into
   MEMORY.md via the memory tool" (H7). A channel assistant = the host's inbound handler
   calling `post()`/`wake()`. Both are docs, zero surface.

## Risks / Trade-offs

- **`memory` file writes are host-filesystem side effects** — sandboxed hosts must point the
  home dir somewhere writable; documented, and `memory:false` disables it.
- **Frozen snapshot means a persona won't "see" its own write until next session** — this is
  intentional (cache economics) and the tool text tells the model; the alternative (live
  re-inject) breaks prompt caching, which is the exact failure openclaw/hermes engineer
  around.
- **Drift reconciliation touches two already-shipped ports** — kept surgical: rename/realign
  to the spec, same observable behavior; their existing tests move to the shared fixture.
- **Heartbeat is a library timer, not a scheduler** — deliberately minimal (one interval +
  the silent sentinel). Cron/durable scheduling stays the host's job; the library only owns
  the trigger→turn seam.
