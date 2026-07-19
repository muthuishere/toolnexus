# Tasks — add-compaction

Reference: branch `spike-compaction` (`js/spike/compaction.ts` + `compaction-demo.ts`,
11 checks). NO core client changes — a pure `beforeLLM` helper. Pure-function port.

## 0. Contract + fixture

- [x] 0.1 SPEC.md §7F (context compaction): the `compactor` beforeLLM contract, no-op below
      budget (byte-identical), tail-begins-at-user-turn (tool-pair safety), system-prompt
      preserved, pluggable summarize/countTokens, optional flushToMemory. Match §7A–E style.
- [x] 0.2 Shared fixture `examples/compaction/`: a transcript with tool groups + options
      (maxTokens/keepTail) + expected compacted output (summary present, tail boundary at a
      user turn, tool-pair safety, system preserved) — the §0 conformance artifact.

## 1. js (reference)

- [x] 1.1 `js/src/agents/compaction.ts` (or client-adjacent): `compactor`, `estimateTokens`;
      export under the appropriate namespace. Rewrite the spike to shipped quality.
- [x] 1.2 Port C1–C6 (11 checks) into `js/test/` against `examples/compaction/`; npm test green.

## 2. python
- [ ] 2.1 `toolnexus/…/compaction.py`: compactor/estimate_tokens per §7F.
- [ ] 2.2 11 checks as pytest against the fixture; full suite green.

## 3. golang
- [x] 3.1 `golang/agents/compaction.go`: Compactor/EstimateTokens per §7F.
- [x] 3.2 11 checks as go tests (-race) against the fixture; full suite green.

## 4. java
- [ ] 4.1 `…/Compaction.java`: compactor/estimateTokens per §7F.
- [ ] 4.2 11 checks as JUnit against the fixture; full suite green.

## 5. csharp
- [ ] 5.1 `Toolnexus/…/Compaction.cs`: Compactor/EstimateTokens per §7F.
- [ ] 5.2 11 checks as xUnit against the fixture; full suite green.

## 6. elixir
- [ ] 6.1 `toolnexus/…/compaction.ex`: compactor/estimate_tokens per §7F.
- [ ] 6.2 11 checks as ExUnit against the fixture; full suite green; coverage ≥ 95%.

## 7. Docs + close

- [ ] 7.1 Docs: a compaction section on persona-agents (or its own page) + a "keep a persona
      alive for weeks" recipe wiring compactor + flushToMemory + the memory builtin; 6-lang tabs.
- [ ] 7.2 Cross-port conformance: identical compacted output for the shared fixture; six
      suites green together at HEAD.
- [ ] 7.3 CHANGELOG: context compaction (opt-in beforeLLM helper), additive.
- [ ] 7.4 Remove `js/spike/` once js src + tests green.
