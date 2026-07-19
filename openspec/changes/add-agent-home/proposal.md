# Proposal — add-agent-home

## Why

`add-subagents` shipped the agent runtime (the coding-agent axis). The other archetype —
a **persona / long-lived assistant** (openclaw / hermes style) — needs three things the
runtime doesn't yet spec: an identity that lives in **files** (not one string), **durable
memory** it can update, and a **heartbeat** so it can act without being prompted. All three
ride seams the shipped runtime already exposes (`onSpawn` injection, the six verbs, the
runtime-wide store). A js spike proves it: **15/15** on the unmodified `§7D` runtime, with a
real support-assistant example. A secondary goal: **reconcile drift** — `agentFromDir`/
heartbeat landed unevenly across ports during `add-subagents` (python/java shipped them,
others kept them test-local); this makes them one spec'd surface in all six.

## What Changes

- **Bootstrap files — the directory IS the agent.** `fromDir(dir)` discovers ordered files
  `AGENTS.md · SOUL.md · IDENTITY.md · USER.md · TOOLS.md · HEARTBEAT.md · MEMORY.md`
  (2 MB/file cap), composes present ones into the soul as `## <file>` sections at session
  start. Convention over configuration; the identity is a folder you can `ls` and `git`.
- **`memory` builtin — durable, frozen-snapshot.** One tool, three actions
  (`add`/`replace`/`remove`) over `MEMORY.md` (self) and `USER.md` (user model). Writes go
  to **disk**; the live prompt's snapshot is **not** mutated mid-session (cache doctrine) —
  it refreshes on the next run. A missing substring on replace/remove is a loud `isError`.
  Opt-out per persona (read-only agents).
- **Heartbeat — `startAgent`.** An interval posts a tick to the agent's own inbox and wakes
  it (unsolicited rail; ticks coalesce) with a "read HEARTBEAT.md and act, else
  `HEARTBEAT_OK`" prompt. A `HEARTBEAT_OK` reply is **silent** — no-op beats never message
  anyone. Channels (Telegram/etc.) stay the host's job: wire them to `post()`.
- **Reconcile drift**: where `fromDir`/heartbeat already shipped (python/java), align them to
  this spec; where they were test-local (js/go/csharp/elixir), promote to the real surface.
- **Real deliverables, not toys** (per owner steer): a runnable `examples/persona-agent/`
  (soul + user model + heartbeat), a **"when to use"** decision guide (`agent()` vs
  `fromDir` vs the raw verbs), and recipes — **dream/consolidation** (a scheduled agent that
  folds notes into `MEMORY.md`) and a **channel-driven assistant** — all as composition over
  the primitives, no new API.
- **SPEC.md gains §7E** (agent home: bootstrap contract, memory builtin, heartbeat).

**Explicitly OUT of scope**: channel adapters / a gateway (that is the `messenger` product,
not the library); compaction; semantic memory / embeddings recall (daily-notes + search is a
later change); the consolidation *policy* itself (shipped only as a documented recipe).

## Capabilities

### New Capabilities
- `agent-home`: the bootstrap-file contract, the `memory` builtin (frozen-snapshot
  semantics), and the heartbeat trigger — the persona surface over the §7D runtime.

### Modified Capabilities
- `builtin-tools`: adds the optional `memory` tool (file-backed, off unless a home dir is
  wired) to the built-in inventory.

## Impact

- **Code**: a small `agents/home` surface per port (`fromDir`, `memoryTool`, `startAgent`,
  `HEARTBEAT_OK`, `BOOTSTRAP_ORDER`), built on the shipped runtime — extracted from
  `spike-agent-home` (`js/spike/home.ts`), rewritten to shipped quality; drift reconciled.
- **SPEC.md**: new §7E; a `builtin-tools` note for the file-backed `memory` tool.
- **Fixtures**: `examples/persona-agent/` (a real bootstrap dir + scripted mock turns +
  expected behavior) as the shared conformance artifact.
- **Docs**: a `persona-agents` site page + a "when to use which surface" guide + the two
  recipes; README mention.
- **No behavior change for existing users**: entirely additive; the `memory` tool only
  appears when a persona wires a home directory.
