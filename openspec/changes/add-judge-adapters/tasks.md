# Tasks — add-judge-adapters

## 1. Contract
- [x] 1.1 SPEC.md §8B "Simple judgments — ask / gate" subsection 
- [x] 1.2 Promote `spikes/judge-adapters/shared/{state,gate}-cases.json` to `examples/judge/`
- [x] 1.3 CHANGELOG `## Unreleased` entry 

## 2. Library fixes the spikes hit
- [x] 2.1 One-line static classifier from recorded decisions (all ports)
- [x] 2.2 Public question → wire conversion (all ports)
- [x] 2.3 python: export `NoulCriteria`, `Request` from `toolnexus` (already exported)
- [x] 2.4 csharp: make `Decision.FromJson` public
- [x] 2.5 golang: answer type marshals flat (no `DecisionAnswer` nesting)

## 3. Per-language parity (builders + State + ask + Bands + Answer.Value + Policy + gate + Tape + evaluateBatch + both fixtures in examples/judge/adapters/)
(golang: evaluateBatch already landed in ec22311)
- [x] 3.1 js
- [x] 3.2 python
- [x] 3.3 golang
- [x] 3.4 java
- [x] 3.5 csharp
- [x] 3.6 elixir (coverage gate ≥ 95%)
- [x] 3.7 clojure (both hosts)

## 4. Verify
- [x] 4.1 Every port passes all 16 shared cases
- [x] 4.2 Byte-identity test: builder vs hand-written request body, every port
- [x] 4.3 Docs: Cookbook "Typed decisions" rewritten on `ask` / `gate`

## 5. Open items (resolved — see design.md O1–O5)
- [x] 5.1 Converge the Tape surface and replay path (O1)
- [x] 5.2 Missing-answer reason names the key in every port (O2)
- [x] 5.3 One Policy entry-point shape (O3), picked-option accessor (O4), static one-liner name (O5) — or record them as idiom

## 6. Parity sweep (design.md O6–O8, parity-matrix.md)
- [x] 6.1 Uncertain-answer reason pinned as `uncertain answer "<key>"` in SPEC §8B + spec delta, all 7 ports
- [x] 6.2 Escalation id/question and check order pinned; golang skip index, default `question`, misfit rules fixed
- [x] 6.3 Shared fixtures extended (reason/requestId/question, wantAnswers, policy, per-case rules, roleState); asserted by all 7 ports
- [x] 6.4 Each port mutation-checked against the new fixtures
- [x] 6.5 clojure suite defaults to the repo `examples/` when `TN_EXAMPLES` is unset

## Follow-ups (not this change)
- `add-judge-batteries` — ToolGuard / ToolRelevance / SkillRelevance / ToolResultFilter /
  IsComplete / AgentRouter / ContentGuard (ADR 0035)
- ModelRouter — owner decision
