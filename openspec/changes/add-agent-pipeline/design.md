# Design — agent-pipeline

> Status: **design-stage** (not implemented). Captured after a multi-source study
> (Anthropic *Building Effective Agents*; Claude Code / Agent SDK; Spring Embabel; LangGraph;
> CrewAI; AutoGen; Google ADK; live HN/Reddit/X sentiment, 2026-07). Pick up here to build.

## Guiding principles

1. **Extend, don't fork.** The layer is *composition over the shipped agent loop* (§8) and §10
   suspend/resume. No new core types — it rides `source:"custom"` (already in `ToolSource`),
   `metadata.pending` (already in `ToolResult`), `waitFor` + `store` + `RunResult.status:"pending"`
   (already shipped), and `Hooks`.
2. **Lazy.** Building a pipeline produces a value (a description); nothing runs until a terminal
   trigger. Like Java Streams: intermediate ops compose, the terminal op fires.
3. **Closed under composition.** Every combinator returns a pipeline; a pipeline is a `Tool`. You
   never leave the algebra.
4. **Thin, not a framework.** No orchestration engine. A combinator is a `Tool` that calls child
   `.execute()`. Transparency over abstraction (the recurring complaint about LangChain/CrewAI).
5. **Simple, per Hickey.** Un-braid concepts. Default is a flat linear `pipe(step, step, …)` of
   **named** sub-pipelines; `branch`/`loop` are named nodes referenced by name, never deep-nested
   lambda soup and never a fluent `.when().end()` builder. Name your pipelines, compose by
   reference.
6. **Safety is default, not opt-in.** Circuit-breaker loops, bounded stale answers, idempotent
   replay — you opt *out* of safety, not into it.

## Core model

- **`Agent`** = a suspendable `Tool` that owns its own `Toolkit`. `agent(name, {does, uses})`
  (local) or `agent({card})` (remote A2A). `does` = the routing description; `uses` = the toolkit =
  the allowlist.
- **`AgentPipeline`** = a lazy tree of stages, itself a `Tool`. Built from an
  **`AgentPipelineConfig`**:

  ```
  AgentPipelineConfig {
    client:      Client            // the shipped agent loop (§8)
    ask:         waitFor           // §10 host slot — resolves every Pending (human/OAuth/approval)
    checkpoints: ConversationStore // where suspended state persists (in-mem | file | db)
    guard:       Hooks.beforeTool  // cross-cutting tool policy (allow/deny/require-approval)
  }
  ```

  Only the **root** pipeline takes a config; sub-pipelines inherit it through `ctx` (like the abort
  signal today). Resumability is a property of the config: because it holds the store + ask, the
  pipeline knows how to persist and reload itself.

- **Semantic model = a free monad with one interpreter.** The pure pipeline *describes* effects
  (`Ask`, `Login`, `Approve`); the config's handler *runs* them. This is why the same pipeline runs
  under an interactive / durable / test / replay handler unchanged. The monad is the proof of
  lawful composition + resumability; the surface exposes only plain names.

## Combinators (compose — lazy, each returns a pipeline)

| Combinator | Semantics | = Anthropic pattern |
|---|---|---|
| `pipe(cfg?, a, b, …)` | sequential; output→next; gates between | prompt chaining |
| `map(agent)` | fan-out over a collection, concurrent | parallelization (sectioning) |
| `filter(pred)` | drop items failing a predicate | guardrail |
| `reduce(agent)` | fold many results into one | orchestrator synthesis |
| `branch(cond, x, y)` | run **pipeline X or Y** by `cond`; arms are named pipelines | routing |
| `vote(n, agent, quorum)` | run N, take consensus | parallelization (voting) |
| `validate(pred)` | deterministic gate; `isError` on fail | gate |
| `verify(agent)` | adversarial second-agent gate | evaluator-optimizer |
| `retry(n)` | re-run on fail; **circuit-breaker on `hash(tool+args)`** | resilience |
| `orchestrate(coord, workers)` | coordinator dynamically splits→delegates→reduces | orchestrator-workers |
| `plan(goal, tools)` | GOAP: derive path from `requires`/`produces`, replan each step (optional) | — |

`branch` takes two (or keyed) **sub-pipeline values**, not inline handlers — flat and readable.

## Triggers (terminal — fire the loop)

| Trigger | Fires |
|---|---|
| `run(input)` | once → `RunResult` (or `{status:"pending"}` if suspended with no `ask`) |
| `loop(until)` | repeat until condition / until-dry / budget |
| `schedule(cron)` | time-driven; self-wakes |
| `on(event)` | webhook / A2A message / channel reply — reuses `serve()` (§7B) |
| `serve(addr)` | expose the whole pipeline as an A2A agent (the inbound edge) |

Same pipeline object, five activation modes. Underneath, all call `client.run` (§8).

## Suspend / resume (the durable-continuation lead)

- Any stage may return `Pending` (`ask`, `login`, `approve`). It bubbles up as the pipeline's
  `Pending`; the incoming `Answer` routes back to exactly that stage's cursor.
- **Every combinator preserves resume**: `pipe` checkpoints the cursor; `map`/parallel does
  **partial resume** (completed branches cached, only pending ones wait); `loop`/`schedule`/`on`
  resume by construction; `orchestrate` resumes pending workers.
- **Checkpoint** = blackboard (typed keyed stage outputs) + cursor + generation stamp, persisted to
  `config.checkpoints`, keyed by `runId` + `request.id`.
- **Two drivers, one `step`:** inline (`ask`/`waitFor` present → runtime loops in-process) vs
  durable (no `ask` → `run()` returns `{status:"pending"}`, process may exit; an event later calls
  `resume(runId, answer)`, one step per event). This is the shipped §10 loop rule, unchanged.
- **This leads the field**: incumbents (LangGraph/CrewAI/ADK) re-run the node from its top on
  resume (replay; side effects must be idempotent). Here it's a true continuation: snapshot *at*
  the suspension point, resume *at* it.

## Staleness gate (runs on every resume, at any depth)

1. **expiry** — past `request.expiresAt` → reject the late answer, re-`ask`.
2. **freshness** — re-evaluate the preconditions the paused work relied on (the inline
   `validate`/`verify` stages double as this); if upstream changed, recompute the stale stage
   (Embabel replan-after-step).
3. **idempotency** — side-effecting stages carry a key so a re-run can't double-execute
   ("the problem isn't the retry, it's knowing what's safe to retry").

## Validation — three homes

- **inline stages** `validate(pred)` (deterministic) / `verify(agent)` (adversarial) — flow gates.
- **`config.guard`** — cross-cutting tool policy (allow/deny/require-approval) via
  `Hooks.beforeTool → {result}`; ties to `add-governed-execution-layer`.
- **automatic** — each stage is a `Tool` with `inputSchema`; args validated before it runs.

Stage gates also run as the resume freshness check (above).

## Surfaces & parity

- **Fluent `AgentPipeline`** (idiomatic Java/C#/TS) over a **byte-identical free-function core**
  (`pipe`/`map`/`branch`) that Go/Python express directly (Go/C# use struct-literal configs, per the
  a2a-agents precedent — idiomatic, no builder ceremony).
- Same behavior across js/python/golang/java/csharp, verified against a shared `examples/`
  agent-pipeline fixture.

## Open questions (resolve before/while building)

1. **First-cut scope** — core algebra only (pipe/map/filter/reduce/branch/vote/validate/verify/
   retry/orchestrate) with GOAP `plan` and true-durable-resume as follow-ups? Or everything at once?
2. **Minimal generating set** — which combinators are primitive vs derived (Hickey)? Likely
   `pipe` + a parallel `map` + `ask` are primitive; `filter`/`reduce`/`vote`/`retry`/`verify`
   derive. Pin the small core; express the rest in terms of it.
3. **Config inheritance mechanism** — thread `AgentPipelineConfig` through `ctx` (extend
   `ToolContext`) vs. bind at construction. Ctx-threading keeps sub-pipelines config-free values.
4. **`branch` shape** — binary `branch(cond, x, y)` only, or also keyed `branch(router, {k: p})`?
   Keyed is sugar over nested binary.
5. **Blackboard vs conversation memory** — is the typed keyed blackboard a new structure or a view
   over `RunResult.messages` + `store`?
