## Why

toolnexus ships the **agent loop** (`client.run/ask/stream`, §8): call the model → run tools →
feed back → repeat, with hooks, parallel tool calls, retries, memory, telemetry, and §10
suspend/resume. What's missing is a **composition layer above the loop** — a way to build bigger
agents out of smaller ones (research → write → verify → ask → publish) without hand-wiring the
control flow, and without a heavyweight orchestrator.

Live practitioner signal (HN/Reddit/X, last 30 days) and six framework studies (Claude Code,
Spring Embabel, LangGraph, CrewAI, AutoGen, OpenAI/Google ADK) converge on the same conclusion,
matching Anthropic's *Building Effective Agents*: **the best harness uses the LLM the least — thin,
composable patterns, not frameworks.** Four of six frameworks reduce delegation to *a tool call*.
The recurring pains are concrete: runaway loops that burn tokens, framework churn/lock-in, context
bloat, silent failures, and checkpointing that **replays** rather than truly resumes.

This change adds that layer **as pure composition over the existing loop** — extend, don't fork.

## What Changes

A lazy, composable **agent-pipeline algebra**. Every combinator returns a pipeline; a pipeline **is
a `Tool`** (`source:"custom"`, already in `ToolSource`); nothing runs until a terminal trigger. The
whole thing rides shipped contracts — no new core types.

- **`Agent`** — a first-class unit that owns its own toolkit (its MCP servers · skills · http ·
  builtins = its allowlist), is itself a `Tool`, and is A2A-servable. The existing `agent({card})`
  factory (remote) gains a local form `agent(name, {does, uses})`; both yield an `Agent`.
- **`AgentPipeline` built with an `AgentPipelineConfig`** — the config carries `client` (the loop),
  `ask` (= §10 `waitFor`, the suspension resolver), `checkpoints` (= the `store`,
  `ConversationStore` adapter), and `guard` (= `Hooks.beforeTool` policy). **The config is what
  makes a pipeline resumable.** Only the root pipeline takes a config; sub-pipelines inherit it via
  `ctx`. Specify once, at the root.
- **Compose combinators** (lazy, each returns a pipeline): `pipe` · `map` (parallel fan-out) ·
  `filter` · `reduce` · `branch(cond, x, y)` (choose pipeline X or Y — no nested DSL) · `vote` ·
  `validate` (deterministic gate) · `verify` (adversarial agent gate) · `retry` (circuit-breaker
  default) · `orchestrate` (dynamic split→delegate→reduce) · `plan` (GOAP, optional).
- **Trigger combinators** (terminal — fire the loop): `run(input)` · `loop(until)` ·
  `schedule(cron)` · `on(event)` · `serve(addr)`. Same pipeline, five activation modes.
- **`ask` is not special** — it's `pending()` (§10). A human question suspends; `config.ask`
  (`waitFor`) delivers it and resumes. One global ask path for every question at any depth.
- **Universal resumability** — every combinator preserves suspend/resume. A checkpoint =
  blackboard (typed keyed stage outputs) + cursor + generation stamp, persisted to
  `config.checkpoints`, keyed by `runId`+`request.id`. Every resume passes one gate:
  **expiry → freshness (re-validate) → idempotency**. A stale answer re-asks; never acts blindly.
- **Validation has three homes**: inline `validate`/`verify` stages (flow gates), `config.guard`
  (cross-cutting tool policy), and automatic `inputSchema` checks (structural). Stage gates double
  as the resume freshness check.
- **Safety defaults, not options**: `retry`/`loop` ship a circuit-breaker keyed on
  `hash(tool+args)` (kills runaway loops); `request.expiresAt` bounds stale answers; side-effecting
  stages carry idempotency keys.
- **Two surfaces, one behavior**: a fluent `AgentPipeline` (idiomatic in Java/C#/TS) over a
  byte-identical free-function core (`pipe(map(...), branch(...))`) that Go/Python map onto. The
  free monad (program vs. handler) is the semantic model; plain names are the surface — no monad
  ceremony exposed.
- Applies across **all five ports** (js/python/golang/java/csharp). `SPEC.md` gains an
  agent-pipeline section (§11 proposed).

**Reused, not re-implemented**: the agent loop (§8), §10 `Pending`/`waitFor`, `Hooks`
(before/after LLM+tool), `ConversationStore`/`TaskStore` adapters, `serve()` (§7B) as the `on`/A2A
edge, `RunResult` telemetry, the `agent()` factory, provider adapters.

## Non-goals (explicit — do not overreach)

- **Memory intelligence** ("what NOT to remember"). We persist/retrieve via `store`; deciding what
  to keep/forget/summarize is a separate problem, out of scope.
- **Eval / prompt-versioning / safe-rollout ops.** We emit telemetry; we don't own the eval/CI
  layer.
- **Temporal-grade distributed durability.** Resume is single-checkpoint durable (store may be a
  DB); cluster-wide exactly-once is a workflow engine, deeper than this layer.
- **Model quality / hallucination.** Not this layer.

## Capabilities

### New Capabilities
- `agent-pipeline`: the lazy composition algebra — `Agent` + `AgentPipeline`/`AgentPipelineConfig`,
  the compose combinators (pipe/map/filter/reduce/branch/vote/validate/verify/retry/orchestrate/
  plan), the trigger combinators (run/loop/schedule/on/serve), universal resumability
  (checkpoint + expiry/freshness/idempotency gate), and the three validation homes — all expressed
  as `source:"custom"` Tools over the shipped loop and §10.

### Modified Capabilities
<!-- No archived capability spec owns the loop yet; §8/§10 are pinned in SPEC.md and referenced.
     `client-observability` (RunResult/onMetric) is reused unchanged. -->

## Impact

- **SPEC.md** — new §11 (agent-pipeline): the `Agent`/`AgentPipeline`/`AgentPipelineConfig`
  contracts, the combinator set + exact semantics, the terminal triggers, the checkpoint shape and
  the expiry→freshness→idempotency resume gate, and the circuit-breaker default. Pins behavior so
  the five ports stay identical against shared `examples/`.
- **js/python/golang/java/csharp** — one agent-pipeline module per port (combinators as `custom`
  Tools + local `agent()` + config + triggers), fluent `AgentPipeline` where idiomatic over the
  free-function core, per-port tests (compose, branch, resume-across-suspend, circuit-breaker,
  staleness gate) using in-process fixtures (no network, no live LLM).
- **examples/** — a shared agent-pipeline fixture (e.g. an interview: fetch → ask → branch → finish)
  every port round-trips identically.
- **Dependencies** — none new. Rides existing loop + §10 + hooks + stores.
- **Security** — per-agent `uses:` allowlist scopes each agent's reachable tools; `config.guard`
  gives deterministic allow/deny/require-approval on every tool call; secrets stay `${ENV}`, never
  logged.
