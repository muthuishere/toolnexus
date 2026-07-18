# Design — add-subagents

## Context

Six-port spike complete on branch `spike-agent-runtime`: js (reference,
`js/spike/{runtime,agents}.ts` + two demos), python, golang, java, csharp, elixir —
**276/276 identical acceptance checks** on hermetic mock LLMs, shipped suites
unregressed. The substrate design (with research provenance) lives in
`docs/references/agent-fundamentals-audit-2026-07-17.md` ("The runtime substrate v2",
"Backpressure", "The UX", "Spike 1 results"). This design.md records only the
*decisions* and the deltas between spike and shipped implementation. The spike is the
reference for behavior; it is NOT merged as-is — each port rewrites to shipped quality
inside its normal source tree with its normal test suite.

## Goals / Non-Goals

**Goals:**
- One new noun (`agent`) and six host verbs; the classic API byte-untouched.
- All behavior expressible as observable state transitions (SPEC pins transitions,
  never scheduling) so §0 conformance works: identical transition traces against
  shared fixtures on a virtual clock.
- Every subsystem rides shipped machinery: §8 loop, §10 pending/waitFor,
  ConversationStore, hooks, uniform ToolResult.

**Non-Goals:**
- agent-home (bootstrap files, heartbeat, memory builtin) — next change.
- Compaction, virtual-tool grouping, CSV fan-out, unified exec — parked.
- A pipeline/combinator DSL — `pipe/map/orchestrate` remain docs-level recipes over
  `task` + parallel tool calls (the add-agent-pipeline combinators are NOT shipped as
  API in this change; the substrate makes them writable in userland).

## Decisions

1. **Agent-is-a-Tool** (from add-agent-pipeline, survived the spike): local completion
   of §7A/§7B symmetry. `asTool()` is the bridge; `serve(agent)` is §7B unchanged.
2. **Model surface v1 = `task` only** (D1): team tools (spawn/send/wait as model tools)
   are opt-in per AgentDef, default off (opencode deny-by-default precedent).
3. **Inbox is agent state, not runtime mailbox** — capped, loud-reject, transactional
   drain (restored on abort), checkpointable. Validated as load-bearing by the Elixir
   port (BEAM mailbox can do none of those).
4. **Suspension = §10 verbatim**: suspending agent ≡ suspending tool; nearest
   interpreter wins; strict one-hop; path in `Request.data.path` (four ports forced
   this shape; JS's spread was the outlier). Durable resume **reattaches by task-key**
   — never respawns, never relies on a completion cache (the §10 transcript does not
   reliably carry the halted tool's result across ports; task 0 verifies and pins the
   per-port truth).
5. **Budgets**: carve at spawn + **live ancestor-chain check before each turn/spawn**
   (carve-only misses sibling spend — spike finding). Roll-up (G3) is the ledger.
   Money excluded (vendor data). Exhaustion = `"incomplete"`, never a throw.
6. **Concurrency rules pinned in SPEC §7D** (the cross-port rediscovery list — every
   truly-concurrent port hit all of these):
   - wake admission (check + slot take + state flip) is **atomic with the verb**;
     dequeue transfers the slot;
   - `wait` resolves with the **next result or the last result** (settled-idle answers
     immediately);
   - the global turn gate wraps **only the LLM HTTP call** and **releases on acquirer
     death** (monitor/finally-equivalent at the runtime, not inside the Run);
   - handle→handle blocking calls go strictly **rootward**; parent→child is
     non-blocking; downward traversal runs from outside the tree;
   - budget reads between turn boundaries are eventually consistent (Law 1 implies it;
     stated);
   - registry iterates **sorted by name** everywhere prose or traces are composed.
7. **Cancellation contract per port** (documented in §8, implemented where missing):
   js `signal` (exists) · go `context` (exists) · elixir kill-the-Run (structural) ·
   csharp CancellationToken (exists; interrupt classified by token, not exception
   type) · **python + java gain a cooperative cancel seam** (minimum: between-attempts
   + transport-level abort where the HTTP stack allows; contract documents what each
   port guarantees). Interrupt at the runtime = abort the turn → `idle`, inbox
   restored; never a kill.
8. **Runtime-wide ConversationStore**: the runtime owns one store; each handle's
   conversation id = its deterministic path id. Fixes the spike honesty item (fresh
   client-per-turn transcripts). Client instances may be pooled per handle — decided
   per port, unobservable.
9. **Elixir client gains a first-class transport seam** (function taking the request,
   returning the response — the `http_options: plug` trick only works in-process).
   Same seam name/semantics as §8 Gap 2 in the other five.
10. **Injectable clock** on the runtime (timers, timeouts, wait deadlines) — virtual
    clock in fixtures; defaults to the real clock.
11. **Supersessions**: `docs/adr/0005` gets a superseded-by header; the
    `propose-agent-pipeline` branch is closed with a pointer note (its `orchestrate`
    combinator ships later, if ever, as a recipe).

## Risks / Trade-offs

- **`"incomplete"` status** is the one observable change for existing users matching
  `status === "done"` after a maxTurns stop (ADR-0005 QG5): accepted as a
  minor-version change; release notes call it out.
- **Substrate size**: ~400 lines/port + tests. Mitigated by the spike being a working
  line-by-line reference and the checklist enumerating every known trap.
- **Per-port cancellation asymmetry** (python/java weaker than js/go/elixir): the
  contract documents the guarantee per port instead of pretending parity where the
  runtime cannot deliver it; the observable state machine stays identical (interrupt
  always yields `idle` + restored inbox — only abort *latency* differs).
- **Deterministic trace parity across six concurrent runtimes** is the hard §0
  obligation — mitigated by pinning transitions-not-scheduling and running fixtures on
  the virtual clock.
