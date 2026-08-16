# ADR 0016 — Harness as an option, and a loop with verifiable properties (revised)

- **Status:** **SUPERSEDED IN PART by the owner (2026-08-16)** — and the correction is on me,
  not on the proposal. This ADR argued Harness/AgentLoop should not be built because they
  duplicate `AgentDef` and the shipped status vocabularies. The owner's actual ask is narrower and
  survives that argument: **`harness` as an ADDITIVE OPTION on `agent`, and a loop with VERIFIABLE
  PROPERTIES.** Neither is the rename I rejected. See "What the owner asked for, and why this ADR
  was answering the wrong question" below; the original argument is kept intact underneath because
  it still governs what must NOT be built. Spike:
  `docs/spikes/0009-harness-option-and-loop-invariants.mjs`.
- **Original status:** Proposed (2026-08-15) — recommending that Phases 1–3 of the loop/graph
  architecture proposal **not** be built. Everything they name already exists in `SPEC.md §7D`
  and `§8`; building them adds a second vocabulary for one runtime across seven ports. Graph
  (Phases 4–5) is a different question and is ADR 0017's.
- **Date:** 2026-08-15
- **Driver:** an architecture document proposing `Model + Harness = Agent`, `Agent → AgentLoop →
  Run`, and `Graph` as a coordination layer, with a five-phase implementation order. Its own
  design rule 9 says "Graph reuses AgentLoop rather than creating a second agent runtime", and
  rule 2 says "Harness must expose what ToolNexus already has rather than duplicating it". This
  ADR takes both rules seriously and finds they argue against Phases 1–3.
- **Honesty note:** written from `file:line` in this repository plus four runnable spikes against
  the built `js/dist`. **The weakest claim here is the seven-port cost estimate** — I read
  `js/src/agents/runtime.ts` end to end and sampled the others through `SPEC.md §7D`, which is
  normative for all of them, so "this is a seven-port surface change" is an argument from the
  contract, not a measurement in six of seven ports. The second-weakest is my read of what the
  proposal's author wants from `Harness`: if the goal is a **serializable agent definition**
  rather than a new type, that is a materially different and much cheaper ask (see "What I might
  be wrong about").

## What the owner asked for, and why this ADR was answering the wrong question

I evaluated `Harness` as a **replacement vocabulary** for `AgentDef` and rejected it on churn: a
second name for a shipped type in seven languages, buying nothing. That rejection stands for a
rename. It does not answer the ask, which is two additive things:

**1. `harness` as an option, not a replacement.** `agent(name, {model, harness})` alongside the
existing flat form. Spiked (`0009`, part A) as a **pure value constructor** over the fields
`AgentDef` already has, plus `guardrails`:

```
agent built from harness carries: name, does, model, soul, tools, budget, onMetric, hooks
guardrail compiled into hooks.beforeTool: true
run status: done | guardrail DENIED the tool (never executed): true
```

Zero library change in the spike — `harness()` returns a plain value and `agent()` spreads it. The
cost I priced was the cost of *replacing* `AgentDef`; the cost of a constructor **beside** it is a
factory function per port. That is a real difference and I should have separated them.

The guardrail composition follows the reference harness rather than being invented here:
guardrails compile into one `beforeTool` with **first-deny-wins**, so a later stage cannot widen an
earlier denial (`packages/core/tools/README.md:25` in `deepseek-harness-master`; the same asymmetry
ADR 0014 rule 3 already proposed — pipeline for rewrites, first-deny-wins for vetoes).

**2. A loop with verifiable properties.** This is the part I missed entirely, and the reference
names it precisely. `docs/subsystems/invariants.md` describes a registry where each package
publishes runtime invariant checks, with three rules worth taking:

- Checks assert on **"authoritative event streams or mutable data, never service or method
  presence"** — behavioral, not structural. A check that asserts a method exists proves nothing.
- Failure is **attributed** (`InvariantError` with `code: 'INVARIANT'` and the owning package).
- **Absence must be explained.** `verify-package-invariants` mechanically rejects an unexplained
  empty installer: a package with nothing checkable must export one whose comment starts
  `No runtime invariant:` and say why. An omission that stops being mentioned is indistinguishable
  from something forgotten — the same rule `CHANGELOG.md` already applies to permitted absences.

**toolnexus can do this today with no new substrate**, because `AgentRuntime.trace`
(`js/src/agents/runtime.ts:303`) is already the cross-port conformance artifact — per-handle
transition traces on a virtual clock. Invariants assert over it. Spiked (`0009`, part B):

```
✓ suspended-exits-only-via-answer   ✓ no-transition-from-closed
✓ every-run-starts-idle-to-running  ✓ budget-stops-are-named
— no-invariant: scheduling order  [not-applicable, with reason recorded]
```

The `not-applicable` entry is the load-bearing one: §7D deliberately leaves scheduling
**unobservable**, so asserting an order would pin what the spec refuses to pin. Following the
reference's rule, that absence is *named and justified* rather than silently missing.

**Verified not vacuous.** An invariant that cannot fail is decoration, so each was fed a violating
trace: **4/4 detected** (`suspended→done`, `closed→running`, a run starting from `closed`, and a
silent `incomplete` with empty text).

### What this changes, and what it does not

| ask | verdict |
|---|---|
| `harness(...)` as an additive option | **Build it.** Small, additive, and it gives guardrails a home. |
| Guardrails inside the harness | **Build it**, first-deny-wins. This revives `add-governed-execution-layer` — the reviving change owes an argument against its recorded supersession reason. |
| Loop invariants over `trace` | **Build it.** No new substrate; the trace already exists and is already normative. |
| `Harness` REPLACING `AgentDef` | **Still no.** A rename across seven ports and every fixture, for zero behavior. |
| `LoopState` as a new status enum | **Still no.** `SPEC.md:785-787` pins `TaskStatus` as identical across ports; a second vocabulary needs the same pinning and the same fixtures. Define it as the existing one or not at all. |
| Graph subsystem | Still ADR 0017's: a host layer, one missing join primitive. |

The three-overlapping-designs problem below is **unchanged and still the first thing to resolve** —
`add-agent-pipeline` already proposes an `Agent` with its own scoped toolkit, which is the same
territory as `harness`.

## Context

### The concepts are shipped — under different names

`AgentDef` (`js/src/agents/runtime.ts:72-104`) already carries every field the proposal's
`Harness` lists as its responsibility:

| proposal's Harness | shipped today | where |
|---|---|---|
| Instructions (soul, system) | `soul` | `runtime.ts:79` |
| Capabilities (MCP, skills, native, HTTP, builtins) | `tools` — a Toolkit view; "the toolkit view IS the security model" | `runtime.ts:83-84` |
| Capabilities (agents / A2A) | `team` | `runtime.ts:85-87` |
| Resource policies (tokens, tool calls, children, concurrency) | `budget: Budget` | `runtime.ts:88`, `:51-68` |
| Extensions (hooks) | `hooks` | `runtime.ts:96-99` |
| Extensions (metrics) | `onMetric` | `runtime.ts:101-102` |
| Boundaries (approval) | `waitFor` (§10 interpreter authority) | `runtime.ts:90-92` |
| Lifecycle extensions | `onSpawn` / `onClose` | `runtime.ts:93-95` |
| Context (memory, compaction) | §7E agent home + §7F compactor, attached per agent via `hooks` | `SPEC.md §7E/§7F` |

`Harness` is therefore `AgentDef` minus `name` and `does`. Introducing it means shipping a second
name for a shipped type in seven languages.

The same holds for `AgentLoop` and `LoopState`. The proposal's `LoopState.status` enum
(`created|running|suspended|completed|failed|cancelled|limit_reached`) is a near-rename of two
shipped, **closed** vocabularies:

- `HandleState = "idle" | "running" | "suspended" | "closed"` (`runtime.ts:113`) — the §7D
  transition machine, whose traces are the cross-port conformance artifact.
- `TaskStatus = "done" | "pending" | "incomplete" | "interrupted" | "closed" | "timeout" | "error"`
  (`runtime.ts:115-123`), which `SPEC.md:785-787` pins as "identical strings in all six ports".

`LoopState`'s other fields map to `HandleView` (`runtime.ts:147-154`: id, state, tokens, inbox),
`TaskResult` (`runtime.ts:125-136`: text, isError, status, pending, turns, totalTokens), and the
`Checkpoint` (`runtime.ts:161-173`).

### Two of the proposal's design rules are already the shipped contract

**Rule: mechanical retry vs semantic recovery** (proposal §9). `SPEC.md:790-793` already pins it,
in the same terms: *"Mechanical retry/backoff (§8) runs at the level where the failure occurred. A
failed child Run crosses the handle boundary as a uniform `isError` result — **never an
exception** — for the parent's model to judge (reprompt / respawn / reroute / abandon). Only the
root may throw to the host."* Stress-verified below.

**Rule: loop owns execution state, not memory** (proposal §8). `SPEC.md §7D` already separates
these: the runtime owns "one ConversationStore for all handles (conversation id = handle id)"
(`SPEC.md:801`), readable back but explicitly "a read handle … not a seam for swapping the store"
(`SPEC.md:805-809`), with §7E memory a separate concern.

### Guardrails were proposed once and archived never-built

The proposal makes guardrails (`ALLOW|DENY|REQUIRE_APPROVAL`) first-class inside Harness. That is
`add-governed-execution-layer`, archived at
`openspec/changes/archive/_never-built/README.md` with the reason recorded verbatim: *"superseded
in practice: §7D budgets + hooks + §10 approval suspension cover the need."* Reviving it is
legitimate, but it is a **revival with new evidence**, not a new idea, and the ADR that revives it
owes an argument against that recorded reason. ADR 0014 reaches the same place from the other
direction and defers a tiered permission model to its own ADR.

### This is the third proposal for one layer

Two prior, more developed attempts exist and are unresolved:

- **`add-agent-pipeline`** (branch `propose-agent-pipeline`, commit `ed445bf`, design-only) — a
  lazy composition algebra where "a pipeline **is** a `Tool`", with `pipe`/`map`/`branch`/`vote`/
  `orchestrate` combinators and five terminal triggers (`run`/`loop`/`schedule`/`on`/`serve`). Its
  stated principle is the same one: "pure composition over the existing loop — extend, don't
  fork." It carries a six-framework survey (LangGraph, CrewAI, AutoGen, ADK, Embabel, Claude Code)
  concluding "the best harness uses the LLM the least — thin, composable patterns, not
  frameworks."
- **ADR 0005** (composable sub-agents) — status line says it is superseded into §7D, and it
  already recorded the reconciliation debt: *"Before any promotion, reconcile the two — this ADR
  builds **on top of** the pipeline layer, not beside it."*

So the repository now holds **three overlapping designs** for one layer, none merged. The most
valuable thing this ADR can say is not "here is a fourth shape" but: **pick one and close the
other two in writing.**

## Decision

**Do not build Phases 1–3 (Harness, AgentLoop, loop extension points).** Adopt three cheaper
things instead.

1. **Document the mapping, do not ship the vocabulary.** Add a short conceptual section to
   `SPEC.md §7D` (or the docs site) stating `Agent = Model + Harness` as the *mental model* and
   naming `AgentDef` as the harness, `Handle` + `TaskResult` as the loop state. This buys the
   proposal's entire teaching value at the cost of one documentation page and zero API surface in
   seven languages.
2. **Close the three-proposal problem before writing more design.** One decision record should
   supersede the other two explicitly. On the evidence, `add-agent-pipeline` is the most developed
   and the best-researched; ADR 0005 already concedes it should build on it. This ADR does not
   ask to revive the pipeline — it asks that the choice be recorded, because three live designs
   for one layer is worse than any one of them.
3. **If `agent.loop()` is wanted, scope it to what §7D does not already expose.** The one genuine
   gap I found is **step-level control**: `wake` + `wait` runs a turn to completion; there is no
   public way to advance a handle one model/action cycle, inspect, and decide whether to continue.
   That is a real capability (debuggers, step-through UIs, per-step budget checks) and it is
   small. It does **not** require `Harness`, `LoopState`, or a new status vocabulary — it is one
   verb over the existing Handle.

### Scope: what this ADR deliberately excludes

**Deferred — guardrails as a first-class Harness concern.** Real question, already has a home
(`add-governed-execution-layer`, archived), and ADR 0014 defers a tiered permission model to its
own ADR. Whoever revives it owes an argument against the recorded supersession reason. Not bundled
here.

**Deferred — Graph.** Different abstraction, different evidence, genuinely new. ADR 0017.

**Excluded — renaming `AgentDef` to `Harness`.** A pure rename across seven ports and every
fixture, for zero behavior. If the vocabulary matters more than the churn, it belongs in a major
version, not an ADR.

## Consequences

- **Nothing is built, so nothing breaks.** The proposal's rule 1 ("do not break existing
  `agent(...).run()`") is satisfied trivially by not adding a parallel surface.
- **The teaching value is kept.** The proposal's mental model is genuinely good and the repo has
  no page that states it. Decision 1 captures it.
- **The reconciliation debt becomes explicit** instead of accumulating a fourth design.
- **If rejected** — i.e. if Harness/AgentLoop ship anyway — then two things must be pinned or the
  seven-port parity guarantee degrades: (a) `LoopState.status` must be **defined as** the existing
  `TaskStatus`, not a parallel enum, because `SPEC.md:785-787` pins those strings as identical
  across ports and a second vocabulary would need the same pinning and the same fixtures; and (b)
  `Harness` must be a type alias over `AgentDef`, not a copy, or the two will drift exactly as the
  compaction tool-pair rule drifted from the Anthropic dialect (ADR 0013 / the
  `fix-compaction-tool-pair-dialect` change).

## What I might be wrong about

If the actual want behind `Harness` is a **serializable, transportable agent definition** — ship
an agent's identity + capabilities + boundaries as data, load it elsewhere — then this ADR answers
the wrong question. `AgentDef` is not serializable today: `tools`, `waitFor`, `onSpawn`, `onClose`,
`hooks` and `onMetric` are all live function values (`runtime.ts:83-102`). A data-shaped harness
would be genuinely new, would compose with the existing `mcp.json` + `skills/` config story, and
would be worth its own ADR. Nothing in the proposal document says this is the goal, so I have not
argued it — but it is the reading under which Phase 1 stops being a rename.

## Next gate

Per the prime directive this ADR is the discussion, not the change. If accepted, the work is a
documentation change plus one decision record superseding `add-agent-pipeline` and ADR 0005 — no
OpenSpec change, because there is no behavior delta. If decision 3 is taken up, the step verb is a
small OpenSpec change against the `agent-runtime` capability with a seven-port parity checklist.
