## Why

The `unify-request-interaction` change made a common, model-initiated builtin (`question`) suspend.
That promoted two pre-existing §10-suspension-layer gaps from rare-edge (they already affect
`authorization`/`approval`) to routine. Both were confirmed in code during that change's huddle and
deferred with a KNOWN-LIMITATIONS note; this change closes them, informed by how **opencode** (the
reference implementation) handles the same situation.

- **G2 — a suspension is miscounted as a tool error.** `runTool` emits the `tool` metric with
  `isError: result.isError` (true for a suspension) and runs the `afterTool` hook *before* the loop's
  `pendingOf` check. So every suspended tool call increments the `is_error` counter and is handed to
  any `afterTool`/circuit-breaker as a failure. opencode never does this: an *awaiting* tool stays in
  a distinct pending state and is never marked errored; only a genuine failure counts
  (`permission/index.ts` parks on a Deferred; `processor.ts:241` classifies a rejection via
  `instanceof RejectedError` as a control signal, not a tool error).

- **G3 — concurrent suspensions in one turn drop all but one.** In the non-streaming parallel tool
  loop, the halt request is last-write-wins (`client.ts:472,480`): two tools suspending in the same
  turn with no `waitFor` surface only one on `RunResult.pending`, non-deterministically. opencode holds
  N simultaneous asks in a per-id `pending: Map<ID, PendingEntry>` and resolves each independently
  (`permission/index.ts`), losing none. (The streaming path already halts deterministically on the
  first suspension — only the non-streaming path has the last-write-wins bug.)

These are correctness/observability defects in the shared §10 mechanism, so they belong in one
focused, spec-driven, five-port change rather than scattered fixes.

## What Changes

- **G2 — suspensions are not tool errors.** `runTool` detects a suspension (`pendingOf(result)`)
  before emitting the `tool` metric and before `afterTool`. A suspended result is emitted with a
  distinct `pending: true` marker (and `isError: false`) so it is counted separately from real tool
  errors, and `afterTool`'s failure-path is not run on it (the resolved result still flows through
  `afterTool` in `resolvePending`, unchanged). Applies to **both** emit sites — the normal path and the
  `beforeTool` short-circuit (path B, a guard-raised pending).
- **G3 — deterministic, no-loss halt.** The non-streaming loop stops on the **first suspension in
  tool-call order** (matching the streaming path and opencode's deterministic handling) instead of
  the last-write-wins request, and does not push the other concurrent suspensions' placeholder results
  into the transcript as errors — they re-suspend on resume. `RunResult.pending` is thus deterministic.
- **Cross-language contract:** create the `suspension` capability spec (the first openspec home for
  §10, which today lives only in `SPEC.md`) pinning both behaviors. Add a one-line note to `SPEC.md`
  §10 that a suspension is never a tool error and that concurrent durable suspensions surface the
  first deterministically.
- **Parity across all five ports** (js / python / golang / java / csharp), each with a regression test:
  a suspended tool call does not increment the error metric / trigger `afterTool`-as-failure (G2), and
  the dedicated **path-B lock test** (a `beforeTool` guard-raised pending is honored) that was deferred
  from the prior change.

## Impact

- **New capability spec:** `suspension` (G2 + G3 requirements). `SPEC.md` §10 note. Touches
  `client-observability` semantics (the `tool` metric gains a `pending` marker) without breaking it.
- **Affected code:** the client loop in all five ports — `runTool` (G2) and the non-streaming tool
  dispatch (G3) — plus the `MetricEvent`/`tool` event shape (additive `pending` field). No change to
  `Request`/`Answer`/`waitFor`.
- **Non-breaking:** the `tool` metric gains an optional `pending` marker; existing consumers that read
  `isError` see suspensions flip from `true`→`false` (correctly, they were never failures). No public
  API signature changes.
- **Deliberately excluded (own change):** R1 (`Answer.reason` three-state), R2 (`Request.schema`) —
  those are additive contract fields the **MCP elicitation bridge** needs, and land with it.
