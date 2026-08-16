# Harness / Loop / Graph — API options, stress-tested

Design options for naming the three layers, judged against **our** sensibilities and the seven-port
constraint. Goal: someone choosing a harness, a loop, or a graph picks toolnexus **because it is
simpler**, not because it has more features.

Status: options for a decision. No code implements any of this.

## What "ours" means — the sensibilities any option must keep

Taken from the shipped surface, not invented here:

1. **Everything is a Tool.** `SPEC.md §7D`: "an Agent is a Tool". MCP tools, skills, functions,
   HTTP endpoints and A2A agents are already one interface — a named, described, schema'd callable.
   Any new layer that is *not* a Tool adds a second composition mechanism.
2. **Factory + options object.** `createClient({…})`, `createToolkit({…})`, `defineTool({…})`. No
   fluent chains, no builders, no DSL.
3. **Absent option ⇒ byte-identical.** Every capability is opt-in and free when unused.
4. **Closed vocabularies, pinned across ports** (`TaskStatus`, `HandleState`).
5. **Config over code where it can be**: `mcp.json`, a `skills/` folder.
6. **Idiomatic per language, identical in behavior.** Not a transliteration.

## The law all options must encode

| question | answer lives in |
|---|---|
| **may it?** — capability, permission, limits | **harness** |
| **did it / is it?** — state, mechanics, budget outcome | **loop** |
| **is it correct?** — domain truth | **neither** — a tool, skill, or agent |

Model belongs to the **loop**, not the harness: `Model + Harness = Agent` collapses otherwise, and
`AgentDef.model` already documents `"inherit"` (`js/src/agents/runtime.ts:80-81`), so model is
already a resolvable value rather than a fixed property of a capability set.

---

## The stress tests

Every option is run against these. **Test 1 is the one that kills things.**

**S1 — Seven-language shape.** Does it read naturally in Go, Java, C#, Python, Elixir, Clojure *and*
TS? Fluent chains and decorator DSLs die here: Go has no method chaining idiom for config, Clojure is
`.cljc` on four hosts with **no host interop** (ADR 0009), and Elixir has no objects. A shape that
needs seven different spellings is not one API.

**S2 — Suspension through every layer.** A §10 pending raised inside a tool, inside an agent, inside
a graph node must surface and resume. Already proven possible on shipped verbs
(`docs/spikes/0005-graph-fanout-and-suspension.mjs`).

**S3 — Budget carves, never escapes.** A loop-level budget must carve *within* the harness/parent
ceiling. §7D does a live ancestor-chain walk; an option that lets a loop widen its own budget breaks
the containment the harness exists to provide.

**S4 — Guardrail denies, and a later stage cannot widen it.** First-deny-wins.

**S5 — Absent-option byte-identity.** Using none of this must produce today's bytes.

**S6 — Simplicity.** Two numbers: **new concepts** a reader must learn, and **lines to first working
agent**.

---

## Option A — Three factories, one law

The most conservative reading of our own conventions.

```js
const reviewHarness = harness({
  soul: "You review code.",
  tools: [readFile, runTests],
  guardrails: [noWrites],
  canSuspend: askHuman,
  budget: { maxTokens: 200_000 },      // CEILING
})

const reviewer = agent("reviewer", { harness: reviewHarness })   // no model yet

const loop = reviewer.loop({ model: "sonnet", budget: { maxTurns: 8 } })
const run  = await loop.run("review PR 42")
run.status            // done | pending | incomplete | …  (the SHIPPED vocabulary)
loop.verify()         // named invariants over the trace

const delivery = graph("delivery", {
  nodes: { research, code, test: reviewer },
  edges: { research: "code", code: "test", test: (r) => r.ok ? END : "code" },
})
await delivery.run("ship the ring buffer")
```

| test | verdict |
|---|---|
| S1 | **Pass.** Factory + map is the one shape that spells identically everywhere: Go struct literal, Clojure map, Elixir keyword list, Python kwargs. |
| S2 | Pass — nodes are agents; suspension already escalates (§7D one-hop). |
| S3 | Pass if `loop.budget` is spec'd as a carve. **Must be written down.** |
| S4 | Pass — guardrails compile to one `beforeTool`, first-deny-wins. |
| S5 | Pass — all three are additive. |
| S6 | **3 new concepts** (harness, loop, graph). ~4 lines to a working agent. |

**Cost:** three factories × seven ports. **Risk:** `harness` and `AgentDef` coexist, so "which do I
use" needs a clear answer in the docs or it becomes the mix-and-match problem.

---

## Option B — Everything is a Tool, harness is the only new noun

Leans hardest on our actual axiom. There is no `graph` factory and no `loop` factory: an agent *is* a
Tool, a graph is *an agent whose tools are agents*, and the loop is what `run` returns.

```js
const reviewer = agent("reviewer", {
  harness: harness({ soul, tools, guardrails, canSuspend, budget }),
})

const run = await reviewer.run("review PR 42", { model: "sonnet", maxTurns: 8 })
run.status
run.verify()

// a graph is an agent whose tools are agents — no new concept
const delivery = agent("delivery", {
  harness: harness({
    soul: "Coordinate: research → code → test. On failure loop back to code.",
    tools: [research, coder, reviewer],
  }),
})
```

| test | verdict |
|---|---|
| S1 | **Pass**, and best of all — one factory shape, seven ports. |
| S2 | Pass — this is exactly today's escalation path. |
| S3 | Pass — one budget tree, no second scope to reconcile. |
| S4 | Pass. |
| S5 | Pass. |
| S6 | **1 new concept** (harness). ~3 lines. |

**The catch, and it is real:** routing becomes the *model's* job. Option A's `test: (r) => r.ok ? END
: "code"` is deterministic; here the coordinator decides, so a static graph with a guaranteed
retry-edge is not expressible. That is a genuine capability loss, and for people who want
LangGraph-style determinism it is disqualifying.

**Cost:** one factory × seven ports. Cheapest by far.

---

## Option C — Contract first, one default implementation

`Harness`, `Loop` and `Graph` are **interfaces**; we ship one default of each and the runtime becomes
an implementation. Directly the reference harness's pattern — "`agent-loop` is the one concrete
implementation of the public `Agent` contract … so the loop stays swappable"
(`deepseek-harness-master/docs/subsystems/core.md:20`).

```js
const reviewer = agent("reviewer", { harness: defaultHarness({ … }) })
const loop     = reviewer.loop()                    // default loop
const custom   = reviewer.loop({ driver: myLoop })  // bring your own
```

| test | verdict |
|---|---|
| S1 | **Fail as stated.** "Implement this interface" is idiomatic in Java/C#/Go, awkward in Clojure `.cljc` with no interop and in Elixir. Seven ports would get behaviours we then have to pin identically — the cost we pay to avoid drift, spent on a seam nobody asked for. |
| S2–S5 | Pass. |
| S6 | **4+ concepts** (contract, default impl, driver, plus the three nouns). |

**Verdict:** the *idea* is right and belongs in the design (the runtime should be describable as an
implementation of a contract). Shipping the swappability as public API is not.

---

## DECIDED (owner, 2026-08-16): factory functions, one shape for all three

`harness(...)`, `loop(...)`, `graph(...)` are **factory functions over existing types**, never new
types. This is the option that survives the seven-language filter (S1): every language has
functions, whereas type aliases work in Go/TS/Python and **do not exist in Java or C#** — which
would force a subclass or a duplicate there, i.e. exactly the drift this repo exists to prevent.
It also matches the convention already shipped: `createClient`, `createToolkit`, `defineTool`.

Landed in golang:

```go
func Harness(s Spec) Spec { return s }   // agents/agent.go
```

`Harness` is the word you read; `Spec` is the one type underneath. They cannot drift because they
are the same type — asserted by `TestHarnessIsAFactoryNotASecondType`, which assigns a `Harness`
result straight back to a `Spec`.

**Per-language shape, same behaviour** (the repo's standing rule — idiomatic per language, identical
in behavior):

| | harness | loop |
|---|---|---|
| js / python / elixir / clojure | `harness({…})` | `loop(agent, …)` |
| go | `agents.Harness(Spec{…})` | `a.Loop(client, toolkit)` — a method reads naturally in Go |
| java / csharp | `Harness.of(…)` | `agent.loop(…)` |

Go keeps `Loop` as a method because `type Loop` and `func Loop` cannot coexist in one package, and
`a.Loop(...)` is the idiomatic Go spelling. That is not an inconsistency — it is the rule working.

`graph(...)` follows the same shape when it lands, so the vocabulary is uniform without a uniform
implementation.

## Recommendation (pre-decision, kept for the reasoning)

**Option A for the surface, Option B's axiom underneath, Option C's framing in the spec.**

Concretely:
- Ship `harness(…)` and `agent(name, {harness})`. One new noun.
- `loop` is what you get from `agent.loop(…)` — model, run budget, `status`, `verify()`. It is a
  handle, not a factory you configure at length.
- **Defer `graph`.** ADR 0017 measured it as a ~25-line host layer over shipped verbs with one real
  gap (`waitAll`/`waitAny`). Ship the join primitive and a cookbook recipe; promote `graph()` to API
  only if a consumer shows the host-side version failing them.
- State in `SPEC.md` that §7D **is** an implementation of the loop contract, so the contract is
  documented without shipping a plugin seam.

**Why this wins on simplicity:** one new concept, four lines to a guarded agent with verifiable
properties, and no second composition mechanism — agents remain Tools, so graphs compose with
everything already in the toolkit.

## What would change the recommendation

- A consumer needing **deterministic** routing with guaranteed edges ⇒ `graph()` stops being
  deferrable and Option A's edge functions become load-bearing.
- Per-step control (advance one model/action cycle, inspect, decide) ⇒ the loop needs a real
  contract, not a handle, and Option C's seam earns its cost. This is the one gap ADR 0016 already
  identified.
