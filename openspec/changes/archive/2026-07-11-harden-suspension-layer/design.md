# Design — harden the §10 suspension layer (G2 + G3)

## Grounded in opencode

opencode (the reference implementation) already solved both problems; we adapt its approach to §10.

- **G2 lesson (`processor.ts:241`, `permission/index.ts:109-118`):** an *awaiting* tool is a distinct
  state, never an error — nothing is emitted while it parks on a Deferred. A *rejection* is surfaced
  but classified via `instanceof RejectedError` as a loop-control signal, not a tool failure. → A
  suspension must never be `isError` in our metrics/hooks; it is its own thing.
- **G3 lesson (`permission/index.ts` `pending: Map<ID, PendingEntry>`, `processor.ts` unbounded
  concurrency):** N simultaneous asks each get their own id + Deferred and resolve independently; none
  is overwritten or lost. → Our loop must not collapse concurrent suspensions to one via
  last-write-wins; the surfaced request must be deterministic and the others must not be corrupted.

## G2 — a suspension is not a tool error

Today `runTool` (JS `client.ts:397-418`) does, in order: `beforeTool` (may short-circuit with a
result — path B), `execute`, `afterTool`, then `emit({event:"tool", isError: result.isError})`. The
loop's `pendingOf` check happens later, in the caller. So a suspension is already counted as an error
and passed to `afterTool` before anyone knows it is a suspension.

Fix, centralized in `runTool` (covers streaming + non-streaming, both loops call it):

```
result = execute(...)
req = pendingOf(result)
if afterTool and not req:        # a suspension is not a real result — don't run failure-path afterTool
    result = afterTool(...) or result
emit(tool, isError = req ? false : result.isError, pending = req ? true : absent)
```

And the `beforeTool` short-circuit emit (path B) gets the same `pendingOf`-aware treatment: a
guard-raised pending is emitted with `pending:true`, `isError:false`.

- The `tool` `MetricEvent` gains an optional `pending?: boolean`. The Prometheus counter adds a
  `pending` label (or, minimally, relies on `is_error=false` for suspensions) so error-rate dashboards
  and circuit-breakers that key on `is_error` no longer trip on questions/logins/approvals.
- `afterTool` is NOT run on the pending result — the resolved result (after `waitFor`) still flows
  through `afterTool` in `resolvePending` (JS `client.ts:519`), so a host hook still sees exactly one
  real result per tool call, never the intermediate suspension. (Today it wrongly runs on both.)

## G3 — deterministic, no-loss halt (non-streaming path)

The streaming loop (JS `client.ts:661-675`) already iterates tool calls **in order** and halts on the
**first** suspension. The non-streaming loop (`client.ts:471-488`) does not: it collects halts inside a
`Promise.all` map with `halted = r.halted` (last-write-wins) and pushes every result — including the
placeholder results of the *other* suspensions — into `messages`.

Fix: align non-streaming to streaming.

- Resolve each tool call concurrently (unchanged — with a `waitFor`, each suspension resolves inline,
  no loss). Then, over the results **in call order**, find the first whose `resolvePending` halted
  (no `waitFor`), and `pendingRun` with that request — deterministic, independent of scheduling.
- Do not append the other concurrent suspensions' placeholder (`{output: prompt, isError:true}`)
  results to the transcript; they carry no answer and would masquerade as errors. On resume the model
  re-issues those tool calls and they re-suspend, surfacing next — matching the one-request-per-resume
  durable model. (With a `waitFor`, this branch is never taken — everything resolved inline.)

This is the minimal correct fix: it removes the non-determinism (the concrete bug) and the transcript
corruption, without changing the `RunResult.pending: Request?` contract. We deliberately do NOT widen
`RunResult` to a `Request[]`: the durable model is resolve-one-then-`run`-again, and opencode's
multi-entry registry exists because it has a *live interactive* resolver (many Deferreds parked at
once), which is the `waitFor`-configured case — and that case already loses nothing.

## Path-B lock test (deferred from the prior change)

Add, per port, the dedicated regression test that a `beforeTool` hook returning
`{result: pending({kind:"approval"})}` (no tool code runs) is honored by the loop — routed to `waitFor`
or a durable halt. Verified working during the earlier spike + code-trace; this locks it and doubles as
the G2 path-B assertion (the guard-raised pending emits `pending:true`, not `isError`).

## What this does NOT touch

- `Request`/`Answer`/`waitFor` shapes — unchanged. (R1 `Answer.reason`, R2 `Request.schema` land with
  the elicitation bridge, which needs them.)
- The streaming loop's G3 behavior — already correct.
- The durable one-request-per-resume model — preserved.

## Open question

- **Prometheus label vs new counter for pending.** Adding a `pending` label to the existing
  `tool_calls_total` keeps one series family (recommended); a separate `tool_suspensions_total` is
  cleaner but adds surface. Leaning: `pending` label on the existing counter, `is_error=false` for
  suspensions. Confirm before implementing so all five emit identically.
