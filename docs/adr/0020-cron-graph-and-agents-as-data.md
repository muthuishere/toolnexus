# ADR 0020 — Cron, graph, and the thing that makes both worth building: **agents as data**

- **Status:** **Proposed** (2026-09-14) — five runnable, asserting spikes. Evidence:
  `docs/spikes/0012-schedule-seam-and-cron.mjs`, `0013-bounded-graph-primitives.mjs`,
  `0014-agents-as-data.mjs`, `0015-dynamic-construction-and-capability-narrowing.mjs`,
  `0016-combined-stress.mjs`, plus a hardened re-run of `0004`–`0010`. `node
  docs/spikes/run-all.mjs` runs all eleven hermetically and exits non-zero on any failure.
- **Date:** 2026-09-14
- **Supersedes in part:** ADR 0017 (which deferred `graph()` entirely — this ADR proposes a
  *bounded* form of it and explains what changed). Closes out ADR 0016's open question about a
  serializable harness.
- **Driver:** the owner's ask, in four parts: make the **agent loop** and **agent graph**
  first-class, add **cron**, and allow **dynamic creation** of graphs and loops — "that way we
  are not just programmable but also build it."
- **Honesty note:** every spike runs against the built **`js/dist` only**. §7D is normative for
  all seven ports, so I expect the results to hold everywhere, but "this works in seven
  languages" remains an argument from the contract, not seven measurements. **My weakest claim
  is the cron subset**: the fixture table below is six rows plus three rejection cases, which is
  enough to show the approach and nowhere near enough to pin a parser. **Nothing here is
  measured against a real workload** — the stress runs use a mocked `fetch`, so they measure the
  runtime's bookkeeping and the containment rules, not throughput.

---

## Context

### First, a vocabulary problem: "loop" means three things

This caused real confusion while scoping the work, and the API will inherit it if we are not
careful. There are three loops, and only one of them is missing:

| | what repeats | bounded by | status |
|---|---|---|---|
| **turn loop** (§8 client loop) | model → tools → model, inside one Run | `budget.maxTurns` (default 6) | **shipped**, 7 ports |
| **attempt loop** (`runGated`) | the whole run, when the completion gate rejects | `completion.maxAttempts` (required) | **shipped**, 7 ports |
| **iteration loop** (ADK's `LoopAgent`) | a *composite of agents*, until a condition | `maxIterations` | **not built** |

`Loop` the class is none of these — it is the *observer* over the first two. The third is what
people mean by "loop" when they are talking about graphs, and it cannot be called `loop()`
because `agent.loop()` is taken. This ADR calls it **`until`**.

### Second: three of the four asks are already most of the way there

- **The agent loop is done.** `harness()`, `agent.loop()`, `Outcome`, the completion gate and
  guardrails shipped in 0.15.0 across all seven ports.
- **Cron is the §7E heartbeat minus a calendar.** `startAgent(agent, run, { everyMs })` already
  ships in all seven ports with the injectable clock, tick coalescing, wake-only-when-idle
  overlap safety, the silent no-op contract and graceful stop. What it lacks is a schedule
  *expression*.
- **The graph substrate is §7D.** ADR 0017 measured this: the six verbs already carry nodes,
  edges, conditions, fan-out, join, shared state and suspension, with the hard parts
  (deterministic ids, hierarchical budgets, failure-as-result, durable resume) already
  guaranteed.

What is genuinely absent is the fourth ask — **dynamic creation** — and it turns out to be the
one that makes the other three worth doing.

### Third: what the field settled on, and where we actually differ

The 2026 landscape splits three ways on *who decides what happens next*: **model-decided**
(OpenAI Agents SDK handoffs, Claude Code subagent delegation — no graph at all),
**bounded-composition** (Google ADK's `SequentialAgent`/`ParallelAgent`/`LoopAgent`; Microsoft
Agent Framework's named patterns), and **free-form graph** (LangGraph's `StateGraph`; MAF's
executor/edge layer). Microsoft Agent Framework 1.0 shipped an **"Agent Harness"** concept at
Build 2026 — the same word we picked independently, which is mild evidence the vocabulary is
converging rather than idiosyncratic.

Chasing LangGraph on graph depth is a losing race and `site/src/content/docs/comparison.md`
already says so. But every one of those frameworks defines its graphs **in code** — Python, or
TypeScript, or C#. None of them ships a *portable definition*. That is the gap a seven-port
library is uniquely positioned to fill, and it is the same move that already made `mcp.json`
valuable.

---

## Decision

**Four decisions. `D3` is the spine; `D4` is the security rule that makes `D3` safe to ship.**

### D1 — Cron is a `Schedule` seam over the shipped heartbeat, not a scheduler

One interface, one method:

```
Schedule { nextAfter(t) -> t | null }
```

`every(duration)` is today's `everyMs` expressed as a Schedule. `cron(expr)` is a **deliberately
narrow** subset: five fields, no Quartz extensions (`L`/`W`/`#`), day-of-month and day-of-week
**OR**'d as classic cron does, UTC unless an explicit IANA `tz` is given. A host may supply its
own `Schedule` — then its parser is its problem, not our parity problem.

**Parity comes from a fixture table, not from a parser.** Seven ports each wrapping their local
cron library (robfig, Quartz, Cronos, croniter, cron-parser, crontab — and nothing standard in
Clojure) would inherit seven different answers on exactly the edge cases that matter. Spike 0012
carries the seed table, including the case real libraries most often disagree on:

```
*/15 * * * *  → 2026-09-14T10:45:00Z    0 9 * * 1-5  → 2026-09-15T09:00:00Z
0 12 29 2 *   → 2028-02-29T12:00:00Z    (leap day: skips 2027 entirely)
0 0 13 * 5    → Fri 2026-09-18          (dom AND dow both set ⇒ OR, not AND)
```

**`misfire` is required, never defaulted** — `"skip"` or `"catchUp"`. Silently choosing between
"replay nothing" and "replay 300 missed fires" is a decision about the caller's bill, which is
the same rule `completion.maxAttempts` already follows. Spike 0012 D asserts the omission is a
loud error, and that a 60-minute outage produces `missed=0` under `skip` and `missed=59` under
`catchUp`.

**Overlap safety is inherited, not reinvented.** The heartbeat's wake-only-when-idle rule
already handles a run slower than its interval: five elapsed instants, exactly one LLM call,
handle `running` rather than queued five deep (0012 C). This composes — it holds for a schedule
driving a whole composite too (0016 T2).

This crosses a line `SPEC.md` §7E currently draws ("the library owns the trigger→turn seam, not
a scheduler or gateway"), so §7E changes in the same change. It is defensible — `everyMs` is
already a scheduler, just a degenerate one — but it is a contract move, not a quiet one.

### D2 — Graph ships as **bounded composition primitives**, not a free-form graph

```
sequence(name, { steps })                    — ordered, acyclic by construction
parallel(name, { branches, join })           — fan-out + join
until(name, { body, done, maxIterations })   — the ONLY cycle, and it is bounded
```

Each returns a node exposing `{ name, does, run, asTool }` — the shape an `Agent` already has.
So agents and composites are the same currency, nest freely, and a composite **is a Tool**
(0013 E). That is the §7D axiom holding rather than a second composition mechanism appearing.

`until`'s `maxIterations` is **required**. A non-converging loop stops with
`status:"incomplete"` and a structured `limit:"iterations"` — the shipped vocabulary, no new
enum (0013 C), which is the condition ADR 0016 set for anything loop-shaped.

**This is where ADR 0017 is superseded, and the reason is new evidence, not a change of taste.**
0017 rejected a `graph()` API because a declarative DSL is "a public API in seven languages for
something a host writes in ~25 lines," and it set the revival bar at "a consumer produces a real
case where the host-side version failed them." The new case is D3: **once a model writes the
graph, the host-side version's honest limitation stops being acceptable.** 0017 named that
limitation itself — "the host owns hop limits and cycle detection… an undocumented graph will
livelock on a back-edge whose condition never flips," and its own spike carried
`if (++hops > 20) throw`. A hop counter the author must remember is fine when a reviewer writes
the graph. It is not fine when the graph arrives as JSON from a model that just read a web page.

The bounded grammar makes the bound **structural**: there is no `goto`, no free edge, no
back-reference, so there is no livelock to detect, and worst-case work is computable *before*
running (0013 D, 0015 H). That property is unavailable in a free-form graph by construction.

0017's other two decisions stand unchanged: `waitAll`/`waitAny` is still the one real gap in the
verb set (spike 0013's `parallel` reaches for `Promise.all`, which is exactly the JS-only idiom
0017 flagged), and a cookbook recipe is still owed for the routing cases the primitives do not
cover.

### D3 — An agent spec becomes **data**, resolved against host-supplied bindings

```
resolve(document, bindings) -> AgentSpec
```

ADR 0016 identified the blocker precisely — `tools`, `waitFor`, `onSpawn`, `onClose`, `hooks`
and `onMetric` are live function values — and it is narrow. Everything executable becomes a
**name resolved against a table the host owns**:

- tools by name (the toolkit already has one flat final-name namespace across MCP / skills /
  native / http / a2a / builtins — it is what `disableTools` operates on)
- teammates by name (`registry()` is already `Record<string, AgentDef>`)
- guardrails, verifiers, hooks by name from a host-supplied binding table

**The rule that makes this safe: documents carry names of code the host registered, never code.**
No `eval`, no dynamic import, no expression language. Spike 0014 D asserts that a live function
in a document is rejected outright and that a code-shaped *string* stays an inert string.

Two supporting rules the spikes forced:

1. **Unknown keys are rejected, never dropped.** An allowlist of keys is what turns "did not
   happen to pollute `Object.prototype`" into "cannot reach the resolver at all" (0016 T3).
   It also prevents the class of bug spike 0006 found in our own stress file, where a misplaced
   `budget` was silently ignored and read as "budgets don't work."
2. **Errors say what IS available.** `unknown tool "x" — host registered: a, b, c`. An unknown
   name is a typo far more often than an attack, and a resolver that just says "no" is unusable.

### D4 — **Capability narrows, never widens** (the invariant that makes D3 shippable)

§7D already enforces this asymmetry twice: budget carves `min(own, parent remaining)`, and
guardrails are first-deny-wins so a later one cannot widen an earlier denial. **Tools do not yet**
— because until specs were data, nobody could author one at runtime.

The pin: **a dynamically-created agent's tools resolve from its creator's view, never from the
global toolkit.** `uses.tools` already says "scoping is the security model"; this is what that
sentence has to mean once specs become data. It belongs in `SPEC.md` as a hard rule with a
conformance fixture, not left to seven ports' good judgement.

Spike 0015 asserts it and then attacks it:

| attack | result |
|---|---|
| child names a tool the creator lacks | **denied**, names the offending capability |
| the denial message | lists only the **creator's** tools — a probe cannot enumerate the host's table |
| A(search+write) → B(search) → C(write) | **denied at depth 2** — privilege does not leak back in |
| child asks `maxTurns: 999` under a ceiling of 4 | **clamped to 4**; an under-ask of 50 tokens is respected |
| recursive self-construction | stops at `maxDepth`, loudly |
| 50 generations deep, 200 siblings wide | grants monotonically non-increasing, **0 reacquisitions** (0016 T1) |

Once a spec is data, **dynamic construction is not a subsystem — it is a Tool** that takes a
document. Which means every containment already shipped applies unchanged: guardrails gate the
constructor (first-deny-wins), budgets contain it, `waitFor` gates it behind a human as an
ordinary §10 pending, and a malformed document crosses as an `isError` result the model can
correct rather than an exception that unwinds the host. That is the same move that made `task` a
tool over `spawn`/`wake`/`wait`/`close`.

**Recommendation: §10 approval on the constructor is the DEFAULT for model-authored specs,
opt-out rather than opt-in.**

---

## What the spikes found that the design did not anticipate

Four things, all from things actually breaking rather than from reasoning:

1. **A nesting cap is required, not optional.** A 10 000-deep `until` document is a trivial
   stack-overflow DoS, and because the static bound is the *product* of the nested bounds, deep
   nesting is also an exponential work bomb. 0016 T3 caps at 64. Without this, D2's
   "bounded by construction" is false advertising.
2. **Narrowing must narrow the error message too.** The first draft's denial listed every tool
   the host had registered — a capability check that doubles as an enumeration oracle.
3. **`inspect(h).children` is a COUNT, not a list.** Waiting on
   `inspect(root).children?.[0]` silently falls back to the root handle and hangs forever with
   no error. Same family as ADR 0017's "`resume()` returns void" trap, and the cookbook owes both.
4. **A §10 pending surfaces at the raw Tool boundary as `{isError:true, metadata.pending}`**, not
   as a top-level `pending` field. Asserting `result.pending` looks right and is always
   `undefined`.

And one finding about our own evidence, recorded because it is the more embarrassing kind:
**the existing spikes could not fail.** Every one hardcoded `/Users/muthuishere/…`, so nobody
else could run them, and none carried an assertion — `0006` had been printing
`stopped LOUDLY: false` for a month because its S4 put `budget` on `RuntimeOptions`, which has no
such field. ADR 0017 documented that exact correction and the file was never fixed. All eleven
spikes now resolve `js/dist` relatively, assert, and exit non-zero on failure; `run-all.mjs` runs
the suite.

---

## Sequencing

| | what | depends on |
|---|---|---|
| **0** | Accept/close ADR 0016 and 0017; supersede `add-agent-pipeline` and ADR 0005 in writing; land `SPEC.md` §7D's completion-gate section (the one still-open task on `add-harness-and-loop`) | — |
| **1** | `waitAll` / `waitAny` | — |
| **2** | **Agent spec as data** — resolve/binding seam, key allowlist, D4 capability narrowing | the spine |
| **3** | `Schedule` + cron subset + fixture corpus; §7E contract change | 2 (a schedule is a field in the document) |
| **4** | `sequence` / `parallel` / `until` | 2 (composites are documents too) |
| **5** | The constructor tool + §10-approval default | 2, 4 |
| **6** | Durable cursor — cron last-fire **and** graph position | ADR 0015's atomic-per-id store pin |

3 and 4 can swap. Each is its own OpenSpec change with a seven-port parity checklist; nothing
here lands in one port and waits.

---

## Consequences

- **Absent ⇒ byte-identical throughout.** No document, no schedule, no composite means today's
  code path. Every addition is opt-in.
- **`SPEC.md` gains one security rule** (D4) and one contract change (§7E, D1). D4 is the only
  genuinely new normative constraint in this ADR, and it is the one a port could silently get
  wrong, so it needs a fixture and not just prose.
- **Two new cross-port parity surfaces**, both pinned by fixture corpora under `examples/`
  rather than by prose: the cron table and the document JSON schema. This is the same discipline
  `mcp.json` already lives under.
- **`comparison.md` moves off "❌ (linear loop only)"** — to a *bounded subset*, and the docs
  must say bounded. No arbitrary back-edges, no time travel, no graph-level checkpointing before
  step 6.
- **Prompt injection now reaches topology.** A model that reads a poisoned page can author a
  graph. D4 bounds the blast radius to what the creator already held — it does not eliminate it,
  and spike 0015 I is explicit that an agent legitimately holding `deploy_prod` can pass it on.
  Narrowing is necessary, not sufficient.
- **If rejected** — if a free-form graph DSL ships instead — then two things must be pinned or
  the seven-port guarantee degrades: graph execution must be expressible as, and verified
  against, per-handle transition traces on the virtual clock (0017's condition), and a hop/nesting
  bound must be mandatory rather than advisory, because finding #1 above makes it a DoS surface
  the moment a model can author the document.

## What I might be wrong about

**The bounded grammar may be too bounded.** `sequence`/`parallel`/`until` covers the ADK
topologies, but it has no conditional routing — no "if the tests failed, go back to code,
otherwise proceed to review." That is ADR 0017's spike 0004 case, and it is genuinely common. My
position is that it stays a host function over the verbs and gets a cookbook recipe, because a
`route` predicate is the thin end of the free-form wedge. If that proves wrong in practice, the
smallest honest extension is a `route` option on `sequence` returning the next step's *index*,
which keeps the forward-only property and therefore keeps the static bound. I have not spiked it.

**The cron subset may be too narrow to be useful.** No seconds field, no `@daily` macros, no
timezone in the spiked version. If most real schedules need `tz`, then DST is load-bearing on day
one and the fixture table has to pin spring-forward before this ships, not after.

## Next gate

Per the prime directive this ADR is the discussion, not the change. If accepted, step 0 is
documentation plus one decision record; steps 1–5 are five OpenSpec changes, each with its own
seven-port parity checklist and fixtures. No code in any port has been written for any of this —
the spikes are host-side prototypes against the built JS bundle, and every proposed primitive
above exists only inside a `docs/spikes/` file.
