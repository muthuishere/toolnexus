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
- [ ] 4.2 Live joint-calibration A/B (design §9) before any per-question cache or automatic chunking
- [ ] 4.3 Live probe: Vercel text + images, Cloudflare-Jev images
- [ ] 4.4 ADR 0038 D8 gate (20–30 real items, no cache)
