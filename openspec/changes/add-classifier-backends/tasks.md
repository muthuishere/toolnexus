# Tasks — add-classifier-backends

## 1. Contract
- [x] 1.1 proposal.md, design.md (proposed defaults PD-1…PD-15), spec delta
- [x] 1.2 SPEC.md §8B "Backends, images, cache and validation"
- [x] 1.3 Shared fixtures `examples/judge/backends/` (presets, cache keys, validate, thresholds, images, cloudflare)
- [x] 1.4 CHANGELOG `## Unreleased`

## 2. Per-language parity (backend enum + cloudflare wire + images + ClassifierUnsupported + state/states + cache/MemoryCache + validateDecision + Thresholds + chunkQuestions + L1 metric fields + raw question/answer + llm deprecation + openrouter jev-1.14 + all backends fixtures, hermetic fake endpoint)
- [x] 2.1 golang
- [x] 2.2 js
- [x] 2.3 python
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir (coverage gate ≥ 95%)
- [x] 2.7 clojure (JVM; cljgo in CI)
- [x] 2.8 rust

## 3. Verify
- [x] 3.1 Every port's CI command green locally
- [ ] 3.2 CI green on the PR
- [x] 3.3 Docs: classifier backends section in each port README (docs site page: follow-up)

## 4. Owner-gated (not in this change)
- [ ] 4.1 Confirm PD-1 … PD-15
- [x] 4.2 Live joint-calibration A/B (design §9) run 2026-11-05 (`validation/README.md`): myjev independent (Δ 0); hosted Jev NOT proven (max |Δ| 0.08 > 0.05 floor, but identical repeats move 0.07)
  - [ ] 4.2b Per-question cache / automatic chunking stay blocked until a split-vs-repeat test with more samples shows independence on hosted Jev
- [ ] 4.3 Live probe: Vercel text + images, Cloudflare-Jev images
- [x] 4.4 ADR 0038 D8 gate run 2026-11-05 on 28 real items (`validation/README.md`): wire 24/24 byte-identical; myjev 24/24; hosted Jev 27/28 (is 11/11, pick 7/7, images 4/4, rate 5/6)
  - [ ] 4.4b `rate` is blocked under D8's strict rule: one band-edge miss (confidence 0.62 vs 0.58 at 0.6), shown to be endpoint noise by a 6x repeat; owner to accept or amend D8
