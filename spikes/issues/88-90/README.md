# spike: issues #88 and #90 — what the runtime path actually reports

Reproduces all three claims from [#88](https://github.com/muthuishere/toolnexus/issues/88)
and [#90](https://github.com/muthuishere/toolnexus/issues/90) against `golang/agents`,
with a **scripted in-process mock LLM** (`mock.go`): no network, no API key, no cost.

```
cd spikes/issues/88-90 && go run .
```

Nothing outside this directory is touched. The mock prices each model differently
(`m-parent` 100 tok/turn, `m-child` 400, `m-loop` 70, `m-sus` 50) so every number below
is checkable by eye.

Written up in [ADR 0025](../../../docs/adr/0025-the-runtime-path-must-be-as-legible-as-the-loop.md).

---

## A — `res.TotalTokens` is the root's own run, not the tree (#88): **REPRODUCES**

A coordinator with one team member, one delegation:

```
res.Status                : "done"
res.Turns                 : 2
res.TotalTokens           : 200   <- what Agent.Run hands you
rt.UsageTokens(rt.Root)   : 600   <- the tree ledger the docs promise
per-handle (rt.List()):
  root/coordinator.1           state=closed    turns=2 tokens=600
  root/coordinator.1/explore.1 state=closed    turns=1 tokens=400

under-report: 400 of 600 tokens missing (67%) with ONE child
```

The rollup is correct (`rollupLocked`, `runtime.go:1236`) and reaches every ancestor —
it is only the *returned result* that carries `r.Usage.TotalTokens` instead
(`runtime.go:1206 / 1222 / 1230`).

A second, unreported consequence of the same line: `TotalTokens` is **this run's** usage,
not the handle's cumulative usage, so a second `Wake` on the same handle reports a number
*smaller* than the previous one. Scenario C shows it: `res.TotalTokens` is 100 at the
suspension while the handle already holds 100, and after the resume the handle holds 300.

The error/closed/timeout paths (`runtime.go:1182`, `:731`, `:953`, `:628`) already return
`h.usageTokens` — the tree total. So today the meaning of `TotalTokens` depends on which
status you got back.

## B — no machine-readable stop reason on `TaskResult` (#90.1): **REPRODUCES**

`Budget{MaxTurns: 3}`, a model that never stops calling tools:

```
res.Status  : "incomplete"
res.IsError : true
res.Text    : "hit maxTurns without a final answer"   <- the ONLY carrier of the reason: prose

TaskResult's full field set (reflect):
  Text         string
  IsError      bool
  Status       string
  Pending      *toolnexus.Request
  Turns        int
  TotalTokens  int
```

`tn.RunResult.Limit` exists (`golang/client.go:344-346`) and `runtime.go:1218-1221`
*reads* it — to pick a sentence — and then drops it. Three different stops
(`maxTurns`, `completion`, budget exhaustion at `runtime.go:955`) all arrive as
`status:"incomplete"` distinguishable only by string-matching `Text`.

## C — the resumed turn re-runs side-effecting tools (#90.2): **REPRODUCES**

Plan: `counter("stage")` → `approve` (suspends) → `counter("commit")`. Two side effects
intended.

```
BEFORE RESUME
  res.Status        : "pending"
  res.Pending.Kind  : "approval"  prompt="may I commit?"  data.path=[root worker.1]
  res.Turns         : 2
  res.TotalTokens   : 100
  counter invocations: 1   [#1 stage]
  stored transcript for root/worker.1: 0 message(s)  <- THE DROP POINT

rt.Resume(...) returned: <nil>   (signature: func(tn.Answer) error — no TaskResult)

AFTER RESUME
  counter invocations: 3   [#1 stage #2 stage #3 commit]
  replayed the side effect 1 extra time(s)
  handles after resume:
    root/worker.1 state=idle      turns=6 tokens=300

  tokens 100 -> 300, turns 2 -> 6 (they DO grow, as documented)

  intended plan was 2 side effects (stage, commit); actual: 3
```

Three invocations of an irreversible tool where the plan called for two — the consumer's
"ran three times" reproduces exactly. Turns went 2 → 6: the two pre-suspension turns were
paid for a second time.

### where the transcript goes

`execute()` loads history at `runtime.go:1156` and commits it back only on
`incomplete` (`:1213`) and `done` (`:1228`). The **`pending` branch (`:1193-1206`) never
calls `store.Save`** — so the store is left holding the *pre-turn* transcript, which for a
first-turn suspension is **zero messages**. `Resume` (`:782`) then replays
`leaf.lastInput` (`:795`) — the original prompt — against that.

This is not an accident: `SPEC.md:912-914` pins it as **rewind-to-checkpoint**, because a
persisted placeholder would make a resumed *parent* skip re-invoking `task`, and
reattachment-by-task-key is the declared idempotency mechanism. The hole is that
reattachment protects `task` calls only. A leaf's own tools have nothing.

**A checkpointed transcript does not exist anywhere.** `r.Messages` at the moment of
suspension is the complete pre-suspension transcript and it is discarded with the
`RunResult`. Replaying it would require storing it somewhere the rewind does not reach.

### the missing resumed result

`Resume` returns `error`. `Agent.Run` returns `(TaskResult, *Runtime)` and never the
`*Handle`, so after a resume the host cannot call `rt.Wait`; `rt.List()` yields
`HandleView`, which has no `Text`. The final answer is unreachable through the public API.
