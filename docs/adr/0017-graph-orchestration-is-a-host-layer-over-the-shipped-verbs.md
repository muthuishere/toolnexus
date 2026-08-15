# ADR 0017 — Graph orchestration: **the runtime already carries it**; ship a recipe, not a subsystem

- **Status:** **Proposed** (2026-08-15) — spike-and-stress-backed. Runnable evidence:
  `docs/spikes/0004-graph-on-shipped-verbs.mjs`, `0005-graph-fanout-and-suspension.mjs`,
  `0006-graph-stress.mjs`, `0007-graph-budget-caps.mjs` (all run against the built `js/dist`). Four runnable spikes built the
  proposal's own graph examples on the **shipped** §7D verbs with **zero library changes**, and a
  four-case stress run found the substrate holds. The recommendation is therefore a documented
  pattern plus one small gap fix, not a Graph subsystem in seven ports.
- **Date:** 2026-08-15
- **Driver:** the loop/graph architecture document's Phases 4–5 — a `Graph` abstraction with
  nodes/edges/conditions/parallel/join/state/suspension, `GraphRun`, `GraphState`, `GraphRuntime`,
  and both static and dynamic routing. Its design rule 9: "Graph reuses AgentLoop rather than
  creating a second agent runtime." ADR 0016 covers Phases 1–3.
- **Honesty note:** every spike ran against the built **`js/dist` only**. §7D is normative for all
  seven ports (`SPEC.md §7D`, transition traces are the conformance artifact), so I expect the
  result to hold everywhere — but "a host-side graph works in all seven" is an argument from the
  contract, not six more measurements. **My weakest claim is the performance number**: S1/S2 ran
  against a mock `fetch` with no network, so 60 nodes in 4 ms measures the runtime's bookkeeping,
  not anything about real agent work. It says the substrate is not the bottleneck; it says nothing
  about throughput.

## Context

### What the proposal asks for, and what already exists

`SPEC.md §7D` ships six host verbs over a tree of handles — `spawn`, `post`, `wake`, `wait`,
`interrupt`, `close` — plus read-only `list`/`inspect` (`js/src/agents/runtime.ts:343-508`). The
question this ADR set out to answer is whether a Graph needs to be *in* the library, or whether
those verbs already express it.

### Spike 1 — the proposal's own static graph, on shipped verbs

The proposal's §14 example (research → code → test → decision, failure loops back to code, success
proceeds to review → END) was built as a **~25-line host function** over `spawn`/`wake`/`wait`/
`close`, where an edge is just a predicate over the node's `TaskResult`:

```
graph trace: research:done → code:done → test:done → code:done → test:done → review:done
conditional retry edge fired (code ran twice): true
terminated at review: true
```

No library change. The conditional back-edge — the part that makes it a graph rather than a
pipeline — is an ordinary function returning the next node id.

### Spike 2 — the two hard cases

**Dynamic fan-out + join** (proposal §15): a coordinator picks N workers at runtime; `spawn` each,
`wake` each, `Promise.all` the `wait`s. Three workers fanned out and joined, all `done`.

**§10 suspension through a host-driven node** — the case that would have killed this approach. If a
pending could not survive a host-side graph, Graph would genuinely need library support:

```
first wait  → status: pending
pending request: "Approve the deploy?"
handle state: suspended
after resume → status: done  text: "APPROVED after human said yes"
handle state: idle
```

Suspension survives. The host answers via the shipped `runtime.resume(answer)` path and the node
completes. (Note for anyone repeating this: `resume` returns `void`
(`js/src/agents/runtime.ts:524`) — the result arrives from a subsequent `wait`. My first attempt
read the return value and wrongly concluded suspension was broken.)

### Stress — where the substrate actually stands

| case | result |
|---|---|
| **S1** 60-way concurrent fan-out | 60 spawned, 0 rejected, 60 `done`, all results distinct |
| **S2** 200-hop sequential chain | 200 hops, no cap hit, no leak |
| **S3** node failure mid-graph | **no throw to host**; `status:"error"`, `isError:true`; graph continues afterwards |
| **S4** budget exhaustion | `done → done → done → incomplete(30)` — loud, never a silent `done` |
| **S4b** `maxChildren` cap | 2 spawned, 2 rejected, loud: `"maxChildren 2 exceeded"` |

S3 is the important one: it confirms the §7D boundary rule (`SPEC.md:790-793`) empirically — a
failed node crosses as a **result the graph can branch on**, not an exception that unwinds the
orchestrator. That property is exactly what a graph needs from its substrate, and it is already
guaranteed and now measured.

S4 corrected a mistake of mine worth recording: I first put `budget` on `RuntimeOptions`, which has
no such field (`runtime.ts:262-288`), so it was silently ignored and the test "showed" budgets not
enforced. Budget lives on `AgentDef.budget` and `spawn(parent, def, budget?)`. Placed correctly, it
stops loudly.

### What this means

Every capability Phases 4–5 list — nodes, edges, conditions, parallel, join, shared state,
suspension, dynamic routing — is expressible today. The graph engine is a `while` loop over a
transition function. The library already supplies the hard parts: deterministic ids, the state
machine, hierarchical budgets, three backpressure gates, failure-as-result, and durable resume.

## Decision

**Do not build a Graph subsystem in seven ports. Ship the pattern, and close the one real gap.**

1. **Document graph orchestration as a cookbook recipe**, per language, in the existing Cookbook
   section of the docs site. Static graph, conditional edge, fan-out/join, and — the one nobody
   guesses — suspension through a node, including the `resume`-returns-void trap. The spikes in
   this ADR are the draft.
2. **Fix the one genuine gap: there is no join primitive.** `wait(h)` waits on one handle;
   `Promise.all` is a JS idiom, and the equivalent differs per language (Go `errgroup`, Java
   `CompletableFuture.allOf`, C# `Task.WhenAll`, Elixir `Task.await_many`, Clojure deref-loop,
   Python `asyncio.gather`). A `waitAll(handles, timeout?)` / `waitAny(...)` pair is a small,
   idiomatic-per-port addition that makes fan-out/join expressible **without** the host reaching
   for host-language concurrency. This is the only thing the spikes needed that the verb set does
   not name.
3. **Say so in `SPEC.md §7D`**: the verb set is the orchestration substrate, and coordination
   topologies are host compositions over it. One sentence prevents the fourth proposal.

### Scope: deliberately excluded

**Deferred — a declarative graph DSL** (`graph("x").node(...).edge(...)`). It is a real
convenience and the proposal is right that it reads well. But it is a public API in seven
languages for something a host writes in ~25 lines, and it invites exactly the drift this repo
exists to prevent — seven hand-written schedulers that must produce identical traces. Revisit if a
consumer produces a real case where the host-side version failed them, which is the same bar ADR
0015 set for the append-only transcript.

**Deferred — `GraphState` persistence / durable graph resume.** Per-node suspension already
persists via the handle checkpoint (spike 2). Persisting the *graph's* cursor across a process
restart is a different problem, and it lands on `ConversationStore`, which ADR 0015 is already
asking to pin as atomic-per-id. Sequence it after that.

**Excluded — `GraphRuntime` as a second scheduler.** §7D deliberately leaves scheduling
unobservable ("Scheduling, thread placement, and concurrency level are unobservable; conformance =
identical per-handle transition traces"). A second runtime with its own scheduling would either
duplicate that guarantee or violate it.

## Consequences

- **Zero new surface, so zero new parity obligation** — except decision 2, which is two verbs with
  a seven-port checklist.
- **The proposal's rule 9 is satisfied by construction**: there is no second runtime because there
  is no new runtime at all.
- **A documented recipe is honest about its limits** in a way a subsystem is not: the host owns the
  loop, so the host owns hop limits and cycle detection. My spike carries `if (++hops > 20) throw`
  for exactly this reason, and the recipe must say so — an undocumented graph will livelock on a
  back-edge whose condition never flips.
- **If rejected** — i.e. if a Graph subsystem is built anyway — then the thing to protect is the
  §7D trace guarantee: graph execution must be expressible as, and verified against, per-handle
  transition traces on the virtual clock, using the shared `examples/subagent-*` fixtures. A graph
  scheduler that cannot be replayed deterministically breaks the one property that makes seven
  ports checkable against each other.

## Next gate

Per the prime directive this ADR is the discussion, not the change. If accepted: a docs-site
cookbook page (no OpenSpec change — documentation only), plus one small OpenSpec change for
`waitAll`/`waitAny` against the `agent-runtime` capability with a seven-port parity checklist and
fixtures asserting the join's transition trace.
