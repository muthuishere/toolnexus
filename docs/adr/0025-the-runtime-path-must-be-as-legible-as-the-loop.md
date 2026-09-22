# ADR 0025 — The runtime path must be as legible as the loop: tokens, stop reasons, and what a resume actually replays

- **Status:** **Proposed.** Every claim below is a number from `spikes/issues/88-90` or a cited
  line, not an argument from priors. Two predictions I made before reading the code did not
  survive; they are in *Corrections* rather than quietly dropped.
- **Date:** 2026-09-21
- **Driver:** Issues [#88](https://github.com/muthuishere/toolnexus/issues/88) and
  [#90](https://github.com/muthuishere/toolnexus/issues/90), both from one consumer building a
  durable, human-gated workflow platform on `golang/agents` at v0.18.1. They needed teams and
  guardrails. Teams and guardrails exist only on the runtime path. Having been pushed there, they
  lost the token number they bill from, the structured stop reason they branch on, and any result
  from a resume — and a resumed turn re-ran an irreversible tool. Their conclusion, in their own
  words, was *"the main reason we ended up not using `Resume` at all, re-running our own step
  instead."*
- **Evidence:** `spikes/issues/88-90/` — a scripted in-process mock LLM, no network, no key, three
  scenarios, `go run .`. Per-port survey of all seven `agents` runtimes with file:line, summarised
  below. Doc quotes are verbatim with paths.
- **Related:** ADR 0005 (composable subagents), ADR 0022 (cost is always reported), SPEC §7D,
  SPEC §10.

## Context: the runtime path is not an advanced option, it is a one-way door

`Loop` and `Agent.Run` are not two tiers of ambition. They are two different *feature sets*. A
host that wants a team, a guardrail, a per-agent budget, or a completion gate that travels with
the agent has no `Loop` shape to express it — the moment those are requirements, `Agent.Run` is
the only surface, and `Outcome`/`RunResult` are gone.

That would be fine if `TaskResult` were `Outcome`'s equal. It is not:

| what a host needs | `Loop` | `Agent.Run` |
|---|---|---|
| what did this cost | `result.usage.totalTokens` | `TotalTokens` — *this run's* spend, not the tree's, not the handle's cumulative |
| why did it stop | `result.limit` — `"maxTurns"` \| `"completion"` | prose in `Text` |
| what did it produce after a resume | n/a (the loop never parks) | **nothing** — `Resume` returns `error` |

**The principle this ADR argues:** *a capability may not be sold at the price of observability.*
When one path is the only way to get a feature, that path is not allowed to be the dimmer one.
Every gap below is a place where reaching for teams silently cost the host a number they had
before.

## The measurements

All three from `spikes/issues/88-90`, reproducible offline.

### 1. `TotalTokens` under-reports by the entire delegated tree — and its meaning depends on status

One coordinator, one team member, one delegation:

```
res.TotalTokens           : 200   <- what Agent.Run hands you
rt.UsageTokens(rt.Root)   : 600   <- the tree ledger the docs promise
  root/coordinator.1           turns=2 tokens=600
  root/coordinator.1/explore.1 turns=1 tokens=400
```

67% of the bill is invisible with **one** child. The rollup itself is correct and reaches every
ancestor (`golang/agents/runtime.go:1236-1242`); only the *returned result* carries
`r.Usage.TotalTokens` instead (`runtime.go:1206`, `:1222`, `:1230`).

The docs promise the opposite, repeatedly and specifically:

> "**Usage roll-up.** The child's tokens/turns/tool calls roll up into the parent's usage —
> `totalTokens` on the root result is the whole tree's ledger."
> — `site/src/content/docs/subagents.mdx:267`

> "the root result's `totalTokens` is the ledger for the **whole tree** — coordinator plus every
> worker plus anything they delegated to."
> — `site/src/content/docs/scenarios/research-orchestrator.mdx:99`

> `console.log(r.totalTokens)          // the WHOLE tree — this is your bill`
> — `site/src/content/docs/scenarios/research-orchestrator.mdx:110`

…and again at `subagents.mdx:53`, `cookbook/subagents.mdx:20`,
`scenarios/coding-agent.mdx:676` and `:802`, `scenarios/verify-gate.mdx:576`,
`scenarios/self-improving-agent.mdx:647`, and seven per-language code comments in
`research-orchestrator.mdx`. `verify-gate.mdx:576` goes further and instructs the reader to
*measure the gate's overhead* by comparing root `totalTokens` with and without it — a measurement
that today returns a number blind to the delegated work the gate causes.

A second defect the issue did not report, visible in scenario C: `TotalTokens` is **this run's**
usage, not the handle's cumulative usage. At the suspension it reads 100 while the handle already
holds 100; after the resume the handle holds 300 and the host is never shown a number at all. So
a second `Wake` on the same handle can report *less* than the first.

And the field is already inconsistent with itself. The error, closed, timeout and settled-view
paths return `h.usageTokens` — the rolled-up tree total (`runtime.go:1182`, `:731`, `:953`,
`:628`). Only `done`/`pending`/`incomplete` return the per-run figure. **Today, what
`TotalTokens` means depends on which status came back.** That is worse than either candidate
meaning, and it is the fact that makes this a bug rather than a preference.

### 2. Three different stops are indistinguishable without string-matching

`Budget{MaxTurns: 3}` against a model that never stops calling tools:

```
res.Status  : "incomplete"
res.Text    : "hit maxTurns without a final answer"   <- the ONLY carrier of the reason

TaskResult's full field set (reflect):
  Text string · IsError bool · Status string · Pending *tn.Request · Turns int · TotalTokens int
```

`tn.RunResult.Limit` exists (`golang/client.go:344-346`), and `runtime.go:1218-1221` reads it —
to choose a sentence — then drops it. A turn-cap stop, a completion-gate stop
(`runtime.go:1219`) and a budget-pool stop (`runtime.go:955`) all arrive as
`status:"incomplete"`, separable only by matching prose the runtime is free to reword.

The consumer is **half right** about the docs here, and the correction matters. The sentence they
quote is scoped to the harness, not to agents:

> "`result.limit` says which limit, as a value you can branch on (`"maxTurns"`, `"completion"`).
> A stop you cannot explain to a user is a bug, not a state."
> — `site/src/content/docs/harness/index.mdx:236`

The agents docs never promise a field; they promise *"a loud `status: "incomplete"`, never a
silent success"* (`cookbook/subagents.mdx:22`) and SPEC §7D says *"Any limit stop ⇒
`status:"incomplete"` **with the limit named**"* (`SPEC.md:884`). So this is not a broken promise
— it is `harness/index.mdx:236`'s own standard applied to the path a host is forced onto. *"A
stop you cannot explain to a user is a bug, not a state"* is the argument for this ADR, written
by us, about the other path.

### 3. A resumed turn re-ran an irreversible tool three times where the plan called for two

Plan: `counter("stage")` → `approve` (suspends, no `WaitFor` anywhere ⇒ durable) →
`counter("commit")`.

```
BEFORE RESUME
  res.Status        : "pending"
  counter invocations: 1   [#1 stage]
  stored transcript for root/worker.1: 0 message(s)

rt.Resume(...) returned: <nil>   (signature: func(tn.Answer) error — no TaskResult)

AFTER RESUME
  counter invocations: 3   [#1 stage #2 stage #3 commit]
  tokens 100 -> 300, turns 2 -> 6
  intended plan was 2 side effects (stage, commit); actual: 3
```

The consumer's "ran three times" reproduces exactly, and so does "paid twice": turns went 2 → 6,
because the two pre-suspension turns were bought again.

**Where the transcript goes.** `execute()` loads history at `runtime.go:1156` and commits it back
on `incomplete` (`:1213`) and `done` (`:1228`) only. The **`pending` branch (`:1193-1206`) never
calls `store.Save`**, so the store is left holding the pre-turn transcript — which, for a
first-turn suspension, is zero messages. `Resume` (`:782`) then replays `leaf.lastInput`
(`:795`), the original prompt, against that. The model, reading only what it is handed, honestly
redoes the work it cannot see.

**A checkpointed transcript does not exist anywhere.** `r.Messages` at the moment of suspension
is the complete pre-suspension transcript, and it is discarded with the `RunResult`. There is
nothing in the store, the handle, or the request to replay from.

**And this is deliberate.** SPEC §7D pins it:

> "(1) **rewind-to-checkpoint** — on a durable pending the runtime restores the handle's PERSISTED
> transcript to its pre-turn snapshot (the §10 placeholder-append rule is scoped to bare-client
> runs; a persisted placeholder would make the resumed parent skip re-invoking `task`)"
> — `SPEC.md:912-914`

> "Reattachment (not transcript inspection, not a completion cache) is the required idempotency
> mechanism."
> — `SPEC.md:908`

So the empty history is the pin working as designed. **The hole is that the declared idempotency
mechanism covers `task` calls and nothing else.** Reattachment-by-task-key makes a re-run
*parent* idempotent. A leaf's own `git push` has no equivalent, and no document anywhere tells
the reader that. The docs say the reassuring half and stop:

> "On resume the deepest handle continues from its checkpoint (turns and usage grow, never reset)"
> — `site/src/content/docs/subagents.mdx:300`, and near-verbatim at
> `scenarios/research-orchestrator.mdx:164`, `api/go/suspension/resume.mdx:26`, `SPEC.md:905`

> "the resumed turn replays from its checkpoint" — `api/python/runtime/handle.mdx:275`

A reader takes "continues from its checkpoint" to mean the work so far is carried. It means the
opposite: the work so far is *rewound*.

**No resumed result.** `Resume` returns `error`. `Agent.Run` returns `(TaskResult, *Runtime)` and
never the `*Handle`, so `rt.Wait` is unreachable; `rt.List()` yields `HandleView`, which has no
`Text`. After a successful resume the agent's final answer cannot be read through the public API
at all. The docs never mention this because there is nothing to mention — the gap is an absence.

### 4. Six of seven ports, plus two defects the issue did not find

| port | `TotalTokens` on done/pending/incomplete | `limit` on TaskResult | `resume` returns | replays |
|---|---|---|---|---|
| golang | run's usage `runtime.go:1206/1222/1230` | **no** | `error` `:782` | `lastInput` `:795` |
| js | run's usage `runtime.ts:804/813/819` | **no** | `Promise<void>` `:530` | `checkpoint.input` `:583` |
| python | run's usage `runtime.py:833/844/852` | **no** | `None` `:600` | `_pending_input` `:621` |
| java | run's usage `AgentRuntime.java:579/584/590` | **no** | `void` `:357` | **literal `"continue"`** `:369` |
| csharp | run's usage `AgentRuntime.cs:538/545/553` | **no** | `Task` `:304` | `PendingInput` `:313` |
| elixir | run's usage `handle.ex:507/520/532` | **yes** `:517` | `:ok` `runtime.ex:231` | `turn_input` `:658` |
| clojure | run's usage `runtime.cljc:839/847/855` | **no** | `nil` `:1350` | `checkpoint :input` `:1324` |

Two findings the consumer could not have seen from Go, both worse than what they reported:

- **java replays the literal string `"continue"`** (`AgentRuntime.java:369`, `:378`). There is no
  checkpointed-input field on its Handle at all, so every resumed turn loses the actual prompt
  *and* any drained inbox text. That is a straight §0 conformance break, not a design tension.
- **csharp (`AgentRuntime.cs:544`) and clojure (`runtime.cljc:847`) hardcode the maxTurns
  wording** and never read `r.Limit`, so a completion-gate stop is reported to the host as a
  turn-cap stop. Those two ports do not merely lack the structured reason — they print the wrong
  one, which is exactly the failure `harness/index.mdx:236` names.
- **elixir already ships `limit` on its TaskResult.** Six ports lack a field the seventh has. By
  the prime directive that is drift regardless of which way this ADR decides.

## Decision

### D1 — `TotalTokens` becomes the tree total; add `OwnTokens`. Do not correct the docs.

`TaskResult.TotalTokens` is the **handle's rolled-up, cumulative subtree usage** (`h.usageTokens`)
on every status, and a new `OwnTokens` carries that handle's own accumulated spend excluding
children. Same for `Turns`: cumulative, not per-run.

Three reasons, in order of weight. (a) The field is already the tree total on four of the seven
status branches, so today it has no single meaning — this unifies it rather than changing it.
(b) Fourteen documented promises, one of which instructs readers to *measure with it*, all say
tree. (c) Budgets are enforced hierarchically; the tree number is the one the runtime itself
reasons about, so the host's number and the runtime's number should be the same number.

Correcting the docs instead was the cheaper option and is rejected: it would leave the field's
meaning status-dependent, which no doc sentence can rescue.

Implementation note: `h.usageTokens` is the rolled-up figure, so `OwnTokens` needs a new
per-handle counter incremented outside the ancestor walk in `rollupLocked`.

### D2 — add `Limit` to `TaskResult` in the six ports that lack it

Populated from `RunResult.Limit`, and **set for budget stops too** — `admitLocked`
(`runtime.go:955`) already computes the exhausted pool name and discards it. Vocabulary:
`"maxTurns"`, `"completion"`, `"maxTokens"`, `"maxToolCalls"`, `"maxWall"`; empty otherwise.
Strings identical in all seven ports, like the status vocabulary they sit beside.

Fix the two mislabels in the same change: csharp and clojure must read `r.Limit` before choosing
their message.

Additive everywhere. Elixir is already correct and only joins the budget-stop half.

### D3 — `Resume` returns the resumed result

`Resume(answer) -> (TaskResult, error)` in Go; the result-returning equivalent in the other six.
The value is the settled result of the **topmost handle the cascade re-ran** — i.e. what the host
would have got from `Agent.Run` had the suspension never happened — so a host can write
`res, err := rt.Resume(ans)` and be done.

Go is the only port where this breaks a caller, because only Go's `err := rt.Resume(...)` stops
compiling on an arity change. In js, python, java, csharp, elixir and clojure, returning a value
where nothing was returned is source-compatible. One port, one-line call-site fixes, pre-1.0 —
that is cheaper than shipping `ResumeWithResult` and living with two spellings forever, which is
the alternative and is rejected on ADR 0019's grounds.

This also removes the reason the `*Handle` is currently unreachable, so `Agent.Run` does **not**
need to start returning one.

### D4 — keep rewind-to-checkpoint. Make the contract explicit, loudly. Spin the transcript replay out.

**The contract is: a durable resume replays the suspended turn from its pre-turn checkpoint. Every
tool that ran in that turn runs again. Tools reachable in a suspendable turn must be idempotent.**

This is what the code does, what SPEC §912 pins, and what four ports go out of their way to
arrange (js `runtime.ts:797`, python `:821`, java `:571`, clojure `:856` all explicitly write the
rewound snapshot back; Go and csharp and elixir reach the same state by skipping the save). It is
not an accident to be repaired in an afternoon.

What ships now, and it is all documentation and comments:

1. Correct the four "continues from its checkpoint" passages (`subagents.mdx:300`,
   `research-orchestrator.mdx:164`, `api/go/suspension/resume.mdx:26`, `SPEC.md:905`) to say what
   is rewound as plainly as they currently say what grows.
2. State the idempotency requirement at the point of use — the `WaitFor` doc comment
   (`golang/client.go:76-85` and its six siblings) and the `Pending` helper — not only in a spec
   paragraph a host reads once.
3. Say, in `SPEC.md:908`, that reattachment covers **`task` calls**; a leaf agent's own
   side-effecting tools are the host's responsibility.

Replaying the leaf's stored transcript is the alternative and is **not rejected — deferred to its
own OpenSpec change**, because its cost is not in the plumbing:

- it needs two transcript policies in one runtime (persist for the suspended leaf, rewind for
  cascading parents), which is coherent — §912's reason is about *parents* skipping `task` — but
  is a new invariant, not a tweak;
- the leaf's pending placeholder has to be written into the transcript, and §10 currently scopes
  the placeholder-append rule to bare-client runs, so the placeholder's shape becomes a §0
  byte-identical obligation across seven ports;
- **it does not remove the requirement anyway.** The suspended tool is re-executed with
  `ToolContext.Answer` set — that is §10's resolution mechanism, not a bug — so at least one tool
  is always called twice across a resume.

Sized **L** and carrying a conformance change, against a **S** that stops hosts being surprised
today. The honest sequencing is: tell the truth now, change the behaviour deliberately later.

One thing the deferred change should carry with it: a tool today has no stable key to dedupe on.
`tn.Pending` mints a fresh `Request.ID` on every call (`golang/types.go:90-92`), so a tool cannot
tell its own replay from a new call. A per-turn replay token on `ToolContext` would make the
idempotency contract *satisfiable* rather than merely stated. That is new API surface and is not
decided here.

## Corrections — two predictions that did not survive

**I predicted the empty-history replay was an oversight: a missing `store.Save` in the `pending`
branch.** It is not. `SPEC.md:912-914` pins rewind-to-checkpoint by name and gives the reason, and
four of the seven ports *explicitly write the rewound snapshot back* rather than merely omitting a
save — which is the opposite of an oversight. The Go omission reaches the pinned state by
accident of ordering, but the state is the intended one. The defect is narrower and less
flattering than "we forgot": the pin's stated idempotency mechanism protects `task` calls only,
and we documented the half that reassures.

**I predicted these were Go-only drift, since the report came from a Go consumer.** Wrong on all
three. The token behaviour is identical in all seven ports; six of seven lack `limit` while elixir
has it; and the survey turned up two defects worse than anything reported — java replaying the
literal `"continue"` and losing the prompt, and csharp and clojure telling a host "maxTurns" when
a completion gate stopped the run. The consumer found the symptom that hurt them; the parity
sweep found the ones that would have.

## Consequences

- **Backward compatibility.** D1 changes a *number*, not a type: correct hosts start billing
  correctly, hosts that had compensated by adding `rt.UsageTokens(rt.Root)` themselves will
  double-count until they stop. It goes in the changelog as a behaviour change with the
  before/after figures, not as a fix. D2 is purely additive. D3 breaks compilation for Go callers
  of `Resume` and nobody else. D4 changes no behaviour at all.
- **A test that would have caught #88** — `parent.TotalTokens >= child.TotalTokens` after one
  delegation, in all seven suites. The consumer proposed it; it is the right assertion and its
  absence is why fourteen doc sentences drifted from the code unchallenged.
- **Blast radius.** D1: seven ports, ~2 lines each plus a counter. D2: six ports for the field,
  two of those also fix a wrong message, seven for the budget-stop half. D3: seven ports, one of
  which breaks callers. D4: docs, one spec paragraph, seven doc comments. Java's `"continue"` bug
  is one port and should not wait for this ADR.
- **Sizing.** D1 **S**. D2 **S**. D3 **S/M** (M only because of the Go break and the "topmost
  re-run handle" semantics needing a pinned scenario). D4-as-decided **S**. The deferred transcript
  replay **L**, with a §0 conformance change inside it.
- **What this does not do.** It does not make `Resume` survive a process restart —
  `research-orchestrator.mdx:183` is already honest about that. It does not give a leaf's tools
  the reattachment that `task` calls get. And it does not close the general question of whether
  `Loop` and `Agent.Run` should converge on one result type, which is a larger conversation than
  four fields.
