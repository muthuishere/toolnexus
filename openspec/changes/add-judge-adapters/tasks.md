# Tasks — add-judge-adapters

## 1. Contract
- [ ] 1.1 SPEC.md §8B "Simple judgments — ask / gate" subsection (done in this change)
- [ ] 1.2 Promote `spikes/judge-adapters/shared/{state,gate}-cases.json` to `examples/judge/`
- [ ] 1.3 CHANGELOG `## Unreleased` entry (done in this change)

## 2. Library fixes the spikes hit
- [ ] 2.1 One-line static classifier from recorded decisions (all ports)
- [ ] 2.2 Public question → wire conversion (all ports)
- [ ] 2.3 python: export `NoulCriteria`, `Request` from `toolnexus`
- [ ] 2.4 csharp: make `Decision.FromJson` public
- [ ] 2.5 golang: answer type marshals flat (no `DecisionAnswer` nesting)

## 3. Per-language parity (builders + ask + Bands + gate + both fixtures)
- [ ] 3.1 js
- [ ] 3.2 python
- [ ] 3.3 golang
- [ ] 3.4 java
- [ ] 3.5 csharp
- [ ] 3.6 elixir (coverage gate ≥ 95%)
- [ ] 3.7 clojure (both hosts)

## 4. Verify
- [ ] 4.1 Every port passes all 16 shared cases
- [ ] 4.2 Byte-identity test: builder vs hand-written request body, every port
- [ ] 4.3 Docs: Cookbook "Typed decisions" rewritten on `ask` / `gate`

## Follow-ups (not this change)
- `add-judge-batteries` — ToolGuard / ToolRelevance / SkillRelevance / ToolResultFilter /
  IsComplete / AgentRouter / ContentGuard (ADR 0035)
- ModelRouter — owner decision
