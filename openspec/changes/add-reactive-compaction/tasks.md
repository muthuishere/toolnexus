## 1. Contract

- [x] 1.1 SPEC.md §7F.1 + §8 Resilience pointer.
- [x] 1.2 Shared fixture `conformance/reactive_compaction/cases.json` (detection + loop cases).
- [x] 1.3 `conformance/options_manifest.json` row `compactOnOverflow`.
- [x] 1.4 `openspec validate add-reactive-compaction`.

## 2. Ports (run + stream, both styles, resume paths; tests replay the shared fixture)

- [x] 2.1 js
- [x] 2.2 python
- [x] 2.3 golang
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir (coverage ≥ 95%)
- [x] 2.7 clojure (JVM + cljgo where CI runs it)
- [x] 2.8 rust

## 3. Ship

- [x] 3.1 CHANGELOG `## Unreleased` entry.
- [ ] 3.2 All eight suites green in CI.
