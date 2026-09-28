# Tasks — add-judge-batteries

## 1. Contract
- [x] 1.1 ADR 0035 D6 records the owner decision (ModelRouter opt-in)
- [x] 1.2 SPEC §8 *Hooks* + *Right-size routing* amended (beforeLLM `model` override; default verbatim)
- [x] 1.3 SPEC §8B *Batteries* subsection + idiom table
- [x] 1.4 Shared fixtures `examples/judge/batteries/` (8 batteries + user-text cases)
- [x] 1.5 CHANGELOG `## Unreleased` entry; Cookbook judge page

## 2. Per-language parity (8 batteries + beforeLLM model override + all fixtures + hook tests + mutation check)
- [x] 2.1 golang
- [x] 2.2 js
- [x] 2.3 python
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir (coverage gate ≥ 95%)
- [x] 2.7 clojure

## 3. Verify
- [x] 3.1 Existing verbatim-model conformance tests still pass in every port
- [x] 3.2 New test per port: override `model` is transmitted for that turn; absent ⇒ configured
- [x] 3.3 Every port's full suite green
- [x] 3.4 Adversarial review of the diff
