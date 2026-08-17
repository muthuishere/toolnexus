# Tasks — add-harness-and-loop

The surface, once, so every port implements the same thing:

- `Harness(spec)` — factory over the existing agent spec; identity, not a new type.
- `agent.Loop(clientOptions, toolkit)` → `Loop` with `run(prompt, {model?})`,
  `status`, `turns`; returns `Outcome{text, status, stoppedBy, attempts, turns, result}`.
- `spec.completion = {verify, maxAttempts}` — required `maxAttempts`; six rules in the
  proposal.
- `spec.guardrails = [fn(beforeToolEvent) -> "allow" | reason]` — first-deny-wins.
- `AllTodosDone` — built-in structural verifier over the `todowrite` builtin.
- Shared `runGated` used by BOTH the standalone loop and the §7D runtime turn, so a
  delegated child gets the same guarantee.

## Spec

- [x] Spec delta at `specs/agent-harness-loop/spec.md`
- [x] `openspec validate add-harness-and-loop --strict`
- [ ] `SPEC.md` §7D — the gate's effect on `TaskStatus` (reuses `incomplete` + `limit`)

## Per-language parity checklist

- [x] `golang/` — prototype: `agents/loop.go`, `agents/agent.go`, `agents/runtime.go`; 8 unit tests + 4 live-model scenarios
- [x] `js/` — `agents/loop.ts`; 13 tests (registered in `package.json`, which lists test files explicitly)
- [x] `python/` — `agents/loop.py`; 13 tests
- [ ] `java/`
- [x] `csharp/` — `Agents/Loop.cs`; 11 tests
- [ ] `elixir/`
- [ ] `clojure/` (must pass all five execution modes)

Each port needs, and is not done without:

1. `guardrails` + `completion` on the agent spec
2. the `Harness` factory
3. `Loop` + `Outcome` + per-call model override
4. `AllTodosDone`
5. `runGated` shared with the runtime turn (delegation!)
6. tests covering: absent-option unchanged · gate blocks then passes · stops loudly ·
   maxAttempts required · no-plan passes · guardrail first-deny-wins · guardrail survives
   the registry projection · gate reaches a delegated child · suspension not re-judged ·
   per-call model reaches the wire · budget stop carries both reasons

## Docs

- [ ] Cookbook page — seven tabs, or an explicit `golang only` banner until then
- [ ] `CHANGELOG.md` entry naming which ports have it and which do not
