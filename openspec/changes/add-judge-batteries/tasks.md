# Tasks — add-judge-batteries

## 1. Contract
- [ ] 1.1 ADR 0035 D6 records the owner decision (ModelRouter opt-in)
- [ ] 1.2 SPEC §8 *Hooks* + *Right-size routing* amended (beforeLLM `model` override; default verbatim)
- [ ] 1.3 SPEC §8B *Batteries* subsection + idiom table
- [ ] 1.4 Shared fixtures `examples/judge/batteries/` (8 batteries + user-text cases)
- [ ] 1.5 CHANGELOG `## Unreleased` entry; Cookbook judge page

## 2. Per-language parity (8 batteries + beforeLLM model override + all fixtures + hook tests + mutation check)
- [ ] 2.1 golang
- [ ] 2.2 js
- [ ] 2.3 python
- [ ] 2.4 java
- [ ] 2.5 csharp
- [ ] 2.6 elixir (coverage gate ≥ 95%)
- [ ] 2.7 clojure

## 3. Verify
- [ ] 3.1 Existing verbatim-model conformance tests still pass in every port
- [ ] 3.2 New test per port: override `model` is transmitted for that turn; absent ⇒ configured
- [ ] 3.3 Every port's full suite green
- [ ] 3.4 Adversarial review of the diff
