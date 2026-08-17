# Harness and loop — name the two halves that already exist, and add the gate

## Why

An agent framework has two things a user must be able to name:

- **The harness** — everything the agent *may* do, fixed per problem: its tools, its
  identity, its team, its ceilings, its policy.
- **The loop** — a *live execution* of that harness: what happened, how many turns it
  spent, whether it finished.

toolnexus already ships both, and names neither. `Spec` **is** the harness — it carries
tools, soul, team, budget, model, hooks. The client's tool-calling loop **is** the loop.
But there is no word for either in the API, so every user invents their own vocabulary,
and the docs cannot explain the shape without a paragraph of throat-clearing.

Naming them is cheap. What is *not* already there, and is the reason this change is worth
making, is the third thing:

**An agent can currently claim `done` while its own declared plan is unfinished.** The
loop stops when the model stops asking for tools. Nothing checks whether the work the
agent said it would do actually got done. A host can bolt a retry loop on the outside, but
a host-side loop **cannot follow a delegation** — when agent A hands work to agent B via
the `task` tool, B runs to completion inside the runtime and A's caller never sees it.

## What changes

Three additions, all optional, all absent-⇒-byte-identical.

**1. `Harness` — a name, not a type.** A factory over the existing agent spec. The word
lands in the API and the docs; the type does not change, so nothing to migrate and nothing
new to learn.

**2. `Loop` — a live execution, with no options.** Opened from an agent, it reports
`status`, `turns`, and an `Outcome` whose stop reason is **always named**. The placement
law it encodes:

| question | answered by | scope |
|---|---|---|
| *MAY it?* — capability, ceilings | the harness | per problem |
| *with WHAT?* — model for this call | `RunOptions` | per call |
| *DID it?* — status, turns | the loop | observed |
| *is it RIGHT?* | a tool, skill or agent | never the loop |

The loop takes **no configuration** — it is read, not set. `model` lives on the per-call
options rather than the loop, so one conversation may change model between turns.

**3. `Completion` — the gate.** `{verify, maxAttempts}` on the harness. When set, the gate
runs exactly where the loop would otherwise return `done`; on failure the loop feeds the
reason back and retries, bounded by a **required** `maxAttempts`. Because it lives on the
harness, **it travels with the agent through delegation** — which is the thing a host-side
retry cannot do.

A built-in verifier, `AllTodosDone`, reads the shipped `todowrite` builtin's result
metadata and requires every declared item to be checked. It is **structural, not domain**:
it counts unchecked boxes and never learns what a todo means, so the loop stays
domain-blind. No plan declared ⇒ nothing to verify ⇒ pass.

## Six rules the gate must obey

Each was found by prototyping, not by design:

1. **The gate judges the loop's ACCUMULATED work**, not one attempt. Otherwise an agent
   escapes it by simply not re-declaring its plan on the retry: the fresh run carries no
   `todowrite`, the verifier sees "no plan", and passes.
2. **A non-`done` run is never re-judged.** A suspension or a budget stop already carries
   its own reason, so the gate can never override a budget stop or turn a `pending` into
   an `incomplete`. `pending` and `incomplete` stay distinct — the caller can always tell
   whether it owes an Answer or a fix.
3. **`maxAttempts` is required, not defaulted.** An unbounded verify loop is a
   denial-of-service on the caller's own bill.
4. **A failed gate stops LOUDLY.** Status becomes `incomplete` with a **structured**
   `limit` of `completion` plus a human reason — never a silent `done`.
5. **When another limit fires mid-verification, the caller learns BOTH.** Otherwise a
   budget stop masks the verification failure and the user never sees why it was looping.
6. **The gate reaches delegated children**, because it is projected into the registry the
   runtime builds.

**Guardrails** ship alongside: policy-only checks on tool calls (*"may it?"*, never *"is
it right?"*) that compile into one `beforeTool` with **first-deny-wins** — a later
guardrail can never widen an earlier denial.

## Impact

- **Affected spec:** new capability `agent-harness-loop`; `SPEC.md` §7D gains the gate's
  effect on `TaskStatus` (no new status strings — `incomplete` is reused).
- **Affected code:** the agents package in every port.
- **Absent ⇒ byte-identical.** No guardrails and no completion means the existing path,
  unchanged. This is additive only.
- **Status vocabulary is NOT extended.** `SPEC.md:785-787` pins `TaskStatus` identical
  across ports; the gate reuses `incomplete` and distinguishes itself via `limit`.

## Port status

Prototyped and proven in **golang** (65 tests, plus four live-model scenarios). The
remaining six ports are tracked in `tasks.md` and are **not done** until ticked — a
capability that exists in one port is exactly the drift this repo exists to prevent, so
the docs must say `golang only` until they are.
