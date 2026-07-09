# Tasks — add-agent-pipeline

> Status: **design-stage.** Nothing implemented yet. `1.x` (contract) comes first, then reference
> port (JS), then per-language parity. Do NOT check a box until it is true and tested.

## 0. Decide scope (open questions in design.md)

- [ ] 0.1 Pick first-cut scope (core algebra only vs. + GOAP `plan` vs. everything). Record in proposal.
- [ ] 0.2 Fix the minimal generating set (primitive vs derived combinators).
- [ ] 0.3 Decide config-inheritance mechanism (ctx-threading vs construction-bind).
- [ ] 0.4 Decide `branch` shape (binary only vs + keyed) and blackboard vs messages/store.

## 1. Contract (SPEC.md §11)

- [ ] 1.1 `Agent` (local `agent(name,{does,uses})` + remote `agent({card})`), `AgentPipeline`,
      `AgentPipelineConfig{client, ask, checkpoints, guard}`; pipeline is `source:"custom"` Tool.
- [ ] 1.2 Compose combinators + exact semantics: pipe, map, filter, reduce, branch(cond,x,y), vote,
      validate, verify, retry (circuit-breaker on hash(tool+args)), orchestrate, plan (optional).
- [ ] 1.3 Trigger combinators: run, loop(until), schedule(cron), on(event), serve(addr) — all over `client.run`.
- [ ] 1.4 Resumability: checkpoint shape (blackboard+cursor+gen), keyed by runId+request.id;
      per-combinator preservation incl. partial resume for map/parallel.
- [ ] 1.5 Staleness gate: expiry → freshness → idempotency, run on every resume.
- [ ] 1.6 Validation homes: inline validate/verify, config.guard, automatic inputSchema.
- [ ] 1.7 Confirm no new core types (rides source:"custom", metadata.pending, waitFor, store, Hooks).

## 2. Reference port (JS) then parity

- [ ] 2.1 js (reference): combinators as custom Tools + local agent() + AgentPipelineConfig + triggers; fluent AgentPipeline over free-function core
- [ ] 2.2 python: ported
- [ ] 2.3 golang: ported (struct-literal config, free-function combinators — idiomatic)
- [ ] 2.4 java: ported (fluent AgentPipeline idiomatic)
- [ ] 2.5 csharp: ported (fluent idiomatic)
- [ ] 2.6 Tests (all 5): compose pipe/map/branch; validate/verify gates; suspend at ask → resume at exact stage; partial resume of map; circuit-breaker breaks on repeated tool+args; expiry/freshness re-ask on stale

## 3. Shared fixture & parity

- [ ] 3.1 `examples/` agent-pipeline fixture (interview: fetch → ask → branch → finish) round-trips identically in all 5 ports
- [ ] 3.2 Ran all 5 suites independently — green; source parity spot-checked
- [ ] 3.3 Updated all 6 READMEs (+ golang/GUIDE.md): agent(), AgentPipeline, config, combinators, triggers, resume

## 4. Wrap-up

- [ ] 4.1 `openspec validate add-agent-pipeline` passes
- [ ] 4.2 SPEC.md §11 cross-checked against the shipped §8/§10 contracts (no drift)
