# Proposal — add-subagents

## Why

toolnexus unifies every tool *source* behind one `Tool` interface, but has no way for one
agent to delegate to another in-process: no subagent spawn, no fan-out with context
isolation, no agent registry. Every reference coding agent (opencode, Codex, Copilot) and
persona agent (openclaw, hermes) ships this as core. The design is de-risked: a six-port
spike on branch `spike-agent-runtime` passes **276/276** acceptance checks (46 × 6
languages) on the shipped, unmodified client loop — this change extracts that working
code into the spec and real implementation. It supersedes both the `add-agent-pipeline`
branch design and ADR-0005 (their overlap is resolved by the spike's surviving surface).

## What Changes

- **Agent-is-a-Tool axiom**: an Agent = soul × toolkit-view × loop, invocable as a
  `Tool` (name + `does` description + prompt schema + execute). Local completion of the
  symmetry §7A/§7B already have for remote agents.
- **Agent-runtime substrate** (new module per port, zero changes to loop semantics):
  `Handle` state machine (`idle → running → idle|suspended|closed`; `suspended → running`
  only via Answer) + six host verbs `spawn/post/wake/wait/interrupt/close` + read-only
  `list/inspect`.
- **Two delivery rails**: solicited (tool results, live turn) vs unsolicited (inbox —
  agent state, not runtime mailbox — coalesced batch-per-turn drain with provenance
  labels and timer-tick dedupe).
- **`task` built-in** (model surface v1; team tools opt-in per agent, default off):
  spawn→wake→wait→close fused; isolated child transcript; final-text-only return; usage
  roll-up; **durable resume by task-key reattachment**.
- **Nearest-interpreter suspension escalation**: a suspending child agent presents to
  its parent exactly as a suspending tool (§10 unchanged); strict one-hop propagation;
  handle path rides `Request.data.path`; resume cascades leaf→root.
- **Hierarchical budgets**: `Budget{maxTurns, maxTokens, maxToolCalls, maxWallMs,
  maxChildren, maxConcurrent, maxDepth}`; carve at spawn + **live-ancestor-chain
  enforcement before each turn/spawn**; exhaustion = `status:"incomplete"` (new
  RunStatus value — the one back-compat note: a maxTurns stop is no longer silently
  `"done"`), optional `onBudget → stop|extend|suspend`.
- **Error boundary rule**: failures cross handle boundaries as uniform `isError`
  results, never exceptions; existing §8 retries/onError unchanged underneath.
- **Three backpressure gates**: bounded inbox with loud reject to the sender;
  per-parent concurrency queue (admission atomic with `wake`); global turn gate
  wrapping **only** the LLM HTTP call, releasing on acquirer death.
- **Lifecycle**: `onSpawn` (session-start injection point) / `onClose(reason)` (flush
  point) on the agent definition; graceful close cascades leaf-first bounded by
  `shutdownMs`; interrupt aborts the turn (agent survives, drained inbox restored).
- **Level-1 UX**: `agent(name, {does, uses, soul?, team?, budget?})`, team-scoped task
  targets, `run()`, `asTool()` bridge into the classic API.
- **SPEC.md gains §7D** (sub-agents & agent runtime) including the concurrency rules
  the spike's cross-port pass surfaced (admission atomicity, wait next-or-last,
  gate-release-on-death, rootward-call discipline, eventual-consistency of budget
  reads between turn boundaries, registry sorted by name).
- **Port seam fixes required by the spike**: Elixir client gains a first-class
  transport seam; a per-port cancellation contract is documented (JS signal / Go ctx /
  Elixir process-kill; Python and Java gain a cooperative cancel or a documented
  between-attempts contract); runtime uses one runtime-wide ConversationStore (child
  transcripts really survive turns); injectable clock for conformance fixtures.

**Explicitly OUT of scope** (later changes): `agentFromDir`/bootstrap files, heartbeat/
triggers, `memory` builtin (all → `add-agent-home`); compaction; CSV fan-out jobs;
virtual-tool grouping.

## Capabilities

### New Capabilities
- `agent-runtime`: the substrate — Handle state machine, six verbs, two rails,
  backpressure gates, budgets, lifecycle, shutdown cascade, runtime obligations
  (deterministic ids, handle-table views, clock seam, provenance).
- `subagents`: the model/consumer surface — Agent-is-a-Tool, the `task` builtin, agent
  registry + team scoping, suspension escalation + durable reattach-resume, error
  boundary, usage roll-up, Level-1 UX (`agent()`, `asTool()`).

### Modified Capabilities
- `suspension`: escalation across agent boundaries (a suspending agent presents as a
  suspending tool; nearest interpreter; strict one-hop) and the handle path riding
  `Request.data.path`. (`RunResult.status` gaining `"incomplete"` + the per-port
  cancellation contract live in the new `agent-runtime` spec, as no loop capability
  spec exists yet.)

## Impact

- **Code**: new `agents`/`runtime` module in each of the six ports (js/python/golang/
  java/csharp/elixir), extracted from the `spike-agent-runtime` branch (throwaway-priced
  spike code is rewritten to shipped quality, not merged as-is); small client additions
  (`incomplete` status, cancellation seam where missing, Elixir transport seam).
- **SPEC.md**: new §7D; §8 addendum (`incomplete`, cancellation contract); §10 addendum
  (`Request.data.path`, escalation, reattachment; verify + pin the halted-tool
  transcript rule per port — the spike found ports disagree today).
- **Fixtures**: new shared `examples/` fixtures for subagent fan-out, escalation,
  durable resume, budgets — conformance = identical transition traces on a virtual
  clock (§0 method).
- **Docs**: supersedes `openspec/changes/add-agent-pipeline` (branch) and
  `docs/adr/0005`; both get superseded-by notes. Reference evidence:
  `docs/references/agent-fundamentals-audit-2026-07-17.md` (substrate v2 + 14-item
  checklist) and `docs/references/coding-agent-research-2026-07-17.md`.
- **No behavior change for existing users**: the classic toolkit/client API is
  untouched; all new surface is additive (the one caveat is the `"incomplete"` status
  for code matching on `status === "done"` after a limit stop — flagged as the QG5
  minor-version note).
