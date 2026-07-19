# Changelog

All six ports (js / python / golang / java / csharp / elixir) are versioned and released
together; entries here apply to every port unless a port is named. Releases are cut as
GitHub Releases `vX.Y.Z` via `release.yml` (see `PUBLISHING.md`).

## Unreleased

## 0.10.0 — 2026-07-19

The agent release: both agent archetypes — coding (sub-agents) and persona (agent home) —
ship on a shared actor-model runtime, with compaction to keep either alive over long
sessions. All six ports, byte-parity against the shared `examples/` fixtures.

### Added

- **Context compaction in all six ports** (`SPEC.md §7F`, OpenSpec change `add-compaction`).
  An opt-in `beforeLLM` helper that keeps a long-lived or high-tool-volume agent under its
  context window — **additive, no core loop change**. In the `agents` surface:
  `compactor({ maxTokens, keepTail, summarize, countTokens, flushToMemory })` returns a
  `beforeLLM` hook that, once the transcript estimate exceeds `maxTokens`, replaces the older
  body with one summary system message and keeps a recent tail; below budget it is a **no-op,
  byte-identical** to no compactor. Two invariants: the retained tail begins at a `user` turn
  (**tool-pair safety** — a `tool` result is never orphaned from its `tool_call_id`), and a
  leading `system` prompt is preserved verbatim. `summarize(older)` is pluggable and MAY call an
  LLM; `countTokens` defaults to `ceil(chars/4)` (`estimateTokens`, an estimator not a
  tokenizer); `flushToMemory` injects a pre-compact reminder to persist durable facts via the
  §7E `memory` tool before summarizing. Ships a shared `examples/compaction/` conformance
  fixture and a "keep a persona alive for weeks" recipe (compactor + `flushToMemory` + the
  memory builtin) on the persona-agents docs page.

- **Persona agents (agent home) in all six ports** (`SPEC.md §7E`, OpenSpec change
  `add-agent-home`). The persona archetype over the §7D runtime — additive and opt-in, no
  runtime change. In the `agents` namespace: `fromDir(dir)` (Python `agent_from_dir`, Java
  `agentFromDir`) composes the bootstrap files
  `AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md` (in that order, 2 MB/file cap) into a
  frozen soul snapshot at session start; a file-backed `memory` builtin (`memoryTool(dir)`,
  actions `add`/`replace`/`remove` over `MEMORY.md`/`USER.md`) that writes to **disk** and loads
  at the START of the next session — never mutating the live prompt, keeping a long-lived persona
  cache-stable (a missing substring is a loud `isError`; opt out with `memory: false`); and
  `startAgent(agent, …, { everyMs })` — a heartbeat that posts a coalescing tick to the agent's
  own inbox and wakes it to read `HEARTBEAT.md`, where a `HEARTBEAT_OK` reply stays silent.
  Channels stay the host's job (wire inbound to `post`/`wake`). Ships with a runnable
  `examples/persona-agent/` ("Ava") + JS/Python/Go entrypoints, a "when to use which surface"
  guide, and dream/consolidation + channel-assistant recipes (composition, no new API).

- **Agent runtime + sub-agents in all six ports** (`SPEC.md §7D`, OpenSpec change
  `add-subagents`). A new `agents` namespace per port (never colliding with the A2A
  `Agent`): `agent(name, { does, uses, soul/soulFile, team, budget, model, waitFor,
  onSpawn, onClose })` with `.run(prompt)` and `.asTool()` — an Agent IS a Tool. Delegation
  runs through a built-in `task { agent, prompt }` tool (team-scoped, opt-in per
  definition): isolated child transcript, one tool message back, usage roll-up, parallel
  task calls. Underneath: a Handle state machine with six host verbs
  (`spawn/post/wake/wait/interrupt/close`), two delivery rails, three loud backpressure
  gates, hierarchical live-enforced budgets, §10 suspension escalation with durable resume
  by task-key reattachment, and a per-port cancellation contract.

### Changed — action may be required

- **`RunResult.status` gains `"incomplete"`** (QG5). A `maxTurns` stop that still had tool
  calls in flight — on the plain client `run`/`ask`/`stream` loops as well as agent runs —
  now returns `status: "incomplete"` plus `limit: "maxTurns"` (idiomatic casing per port)
  instead of a silent `"done"`. Any limit stop (turns, tokens, tool calls, wall clock) is
  loud and names its limit; partial work and the transcript are preserved. **Code that
  matches `status === "done"` after hitting `maxTurns` must update** to handle
  `"incomplete"`. The full closed status vocabulary is now
  `"done" | "pending" | "incomplete" | "interrupted" | "closed" | "timeout" | "error"`,
  identical strings in all six ports.
