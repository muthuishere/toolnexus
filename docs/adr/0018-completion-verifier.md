# ADR 0018 — Completion verifier: an agent cannot claim `done` until a check passes

- **Status:** **Proposed** (2026-08-16) — spike-backed, deliberately narrow. Scoped to ONE thing at
  the owner's direction: no `harness`, no `loop`, no `graph`. Those remain ADR 0016/0017's and the
  API-options note.
- **Date:** 2026-08-16
- **Driver:** the loop declares a run `done` when the model stops asking for tools. Nothing checks
  whether the work is actually finished. "Don't tell me it's done until the tests pass" is the most
  requested guarantee a harness can give, and toolnexus cannot express it.
- **Evidence:** `docs/spikes/0010-completion-verifier.mjs`, run against the shipped client and §7D
  runtime.
- **Honesty note:** the spike ran in `js` only. The delegation finding (below) rests on §7D, which
  is normative for all seven ports, so I expect it to hold everywhere — but it is an argument from
  the contract, not seven measurements. **My weakest claim is the failure-feedback shape**: the
  spike feeds the reason back as a plain `user` message, which is the obvious choice and not
  necessarily the right one (see Open questions).

## Context

### The seam does not exist

`Hooks` has four members (`js/src/client.ts:128-137`). `beforeLLM` can rewrite messages and tools;
`beforeTool` can short-circuit a tool; `afterTool` can replace a result. **`afterLLM` is
observe-only** — its return type is `void | Promise<void>` (`:132`). There is no way for a host to
say "this run is not finished, keep going". The done-decision is closed.

### A host CAN build it — so possibility is not the argument

Spike Q1: a host wraps `run()` in its own retry loop, threading `history` and appending the failure
reason. It works:

```
works: true | verified on attempt 3
```

Any proposal must therefore justify itself on something other than "otherwise impossible".

### The argument is delegation

Spike Q2. A host-side retry loop lives at the **call site**. §7D's `task` tool fuses
spawn→wake→wait→close inside a *parent's* turn — the host is not there. So when a parent delegates
to an agent whose completion the host was guarding:

```
child run status: done | text: "patch v0"
did ANY verification happen inside the delegated run? NO
```

The child claims `done` on its first, unverified attempt, and the parent believes it. **A guarantee
that evaporates under delegation is not a guarantee** — and delegation is the case toolnexus exists
to make cheap. Declared on the agent, the gate travels with it: every caller gets it, including
`task`, including an A2A peer calling in through `serve`.

### What it looks like as an agent property

Spike Q3, both directions:

```
passing case : status=done       attempts=3  stoppedBy=null
failing case : status=incomplete attempts=3
               stoppedBy="completion.verify failed 3×: still red"
```

## Decision

Add one optional member to the agent/client options. Nothing else moves.

```
completion?: {
  verify: (run: RunResult) => { ok: boolean, reason?: string } | Promise<…>
  maxAttempts: number          // REQUIRED — no default; an unbounded gate is a runaway loop
}
```

Six rules, and they are the whole contract:

1. **Absent ⇒ byte-identical.** No verifier, no behavior change, no cost. The same guarantee §7F
   gives for an absent compactor.
2. **The gate runs only where the loop would have returned `done`.** Never on `pending`,
   `incomplete`, `interrupted`, `closed`, `timeout` or `error` — those already have a reason, and
   re-gating them would let a verifier override a budget stop or a suspension.
3. **The loop never interprets the verifier.** It receives `{ok, reason}` and does exactly two
   things: continue, or stop. It has no idea what "verified" means, so the loop stays
   domain-neutral — the property that keeps it portable across seven languages.
4. **A failure is fed back as an observation** carrying `reason`, and the run continues. The agent
   gets to fix its own work; that is the entire value over a host-side assertion.
5. **Exhaustion is loud and uses the SHIPPED vocabulary**: `status:"incomplete"` with the reason
   named. **No new status string** — `SPEC.md:785-787` pins `TaskStatus` as identical across ports,
   and a seventh value would need the same pinning and the same fixtures for no gain.
6. **The verifier may not suspend.** It returns pass/fail. If a human must be asked, that is a tool
   the agent calls. Otherwise `pending` (blocked on a human) and `incomplete` (blocked on a check)
   collapse into one status, and a caller can no longer tell whether it owes an `Answer` or a code
   change.

### Scope: deliberately excluded

**Excluded — `harness`, `loop`, `graph`.** This ADR adds an option, not a vocabulary. The placement
rule those discussions produced ("put each thing at the scope where it varies") is what puts
`completion` on the agent rather than on a call: it varies with the *problem*, not the invocation.

**Excluded — a protocol/invariant verifier.** ADR 0016's revision proposed `loop.verify()` over the
transition trace. On reflection it has no user I can name: port implementers already get it from the
276 conformance traces, and no host has asked. The discipline it borrowed — properties are named,
and an absence carries its reason — belongs in the conformance suite, not on a public API.

**Deferred — verification as a graded score** rather than a boolean. Real review is not binary. But
a threshold is a domain decision, and encoding one in the loop is exactly the domain-awareness rule
3 exists to prevent. A host that wants grading returns `ok: score > 0.8` today.

## Consequences

- **A guarantee that survives delegation**, which is the differentiator. No harness I have looked at
  offers "cannot claim done until X" as an agent property rather than a caller's discipline.
- **Bounded by construction.** `maxAttempts` is required, so the pathological case — a verifier that
  never passes — costs a known number of turns and stops with its reason named.
- **Budget is unaffected in kind, larger in degree.** Verify attempts are ordinary turns and consume
  the existing budget through the same live ancestor-chain walk. A run that would have used 4 turns
  may now use 12; the ceiling still holds, and hitting it still produces `incomplete`. Worth stating
  in the spec so nobody is surprised by the interaction between two limits.
- **`stoppedBy` becomes worth having.** §7D already requires a limit stop to be loud and named, but
  the reason is prose in `text`. A field is the small, useful version — and it serves budget stops
  as much as this.
- **If rejected:** then `SPEC.md §8` should say plainly that the done-decision is closed and a host
  wanting a completion guarantee must own the loop itself — and that such a guarantee **does not
  survive `task` delegation**. That last sentence is the one worth writing regardless of the
  outcome, because the failure mode is silent.

## Open questions

**Q1 — What does the verifier see? — ANSWERED (owner, 2026-08-16): plan/todo/goal state.**
The spike passes the whole `RunResult`, which is enough for "did it answer" but not for "is the work
finished". The resolution is not to widen the input but to give the agent **structured task state**
and verify against it: *not done until every todo is checked off*.

This is better than a workspace handle for the reason that governs this whole design — it is a
**structural** check, not a domain one. The loop counts unchecked items; it never learns what a todo
means, so rule 3 (the loop never interprets the verifier) holds by construction. It also means the
library ships a *useful default verifier* instead of a gate that every host must supply domain code
to make meaningful.

Consequence: plan/todo/goal stop being speculative primitives and become the substrate this ADR
depends on. A host may still close over anything it likes for a domain check — that path is
unchanged and needs no defined input.

**Q2 — How is the failure fed back?** The spike appends a `user` message. That is the obvious shape
and it is load-bearing for parity, since seven ports must produce identical bytes. It also interacts
with the canonical-transcript change (`openspec/changes/add-canonical-transcript`), which is
re-plumbing exactly this representation — so this should land after it, or agree with it.

**Q3 — Does the gate re-run after a durable resume?** A run that suspended, was answered, and now
completes: does it verify? I think yes, since it is reaching `done` for the first time — but §10's
rewind-to-checkpoint interacts here and it needs pinning.

## Next gate

Per the prime directive this ADR is the discussion, not the change. If accepted, the work is a small
OpenSpec change against a new `completion-gate` capability (or `agent-runtime`), with the six rules
as requirements, the absent-option byte-identity as the first conformance scenario, a shared fixture
whose verifier fails twice then passes, and a seven-port parity checklist.
