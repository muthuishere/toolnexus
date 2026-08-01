# Tasks

Per-language parity: this change touches ONLY the Clojure port. The other six
ports already ship every option below; nothing here alters their behaviour.

Each capability is written TEST-FIRST, with expected values taken from the
capability spec or a shipped port — never snapshotted from this port's own
output. Each must be green in all five execution modes before it is ticked.

## 1. Client — request shaping (`client-request-shaping`)
- [x] 1.1 `:request-params` — shallow-merged after the client's own keys, params win
- [x] 1.2 `:request-params` strips `messages` / `tools` / `stream` with a warning
- [x] 1.3 `:body-transform` — runs last, its output is what is sent
- [x] 1.4 `:http-client` — injectable transport (proxy / credentials)
- [x] 1.5 absent options leave the body byte-identical

## 2. Client — resilience (`resilience-policy`)
- [x] 2.1 `:retries` + `:retry-base-ms`
- [x] 2.2 `:timeout-ms` (passed through; koine does not BOUND it on cljgo — upstream)
- [x] 2.3 `:on-error` — retry | fail | suspend, routing LLM failures through §10

## 3. Client — observability and memory
- [x] 3.1 `:hooks` (`client-observability`)
- [x] 3.2 `:on-metric` (`client-observability`)
- [x] 3.3 `:store` — conversation memory (`conversation-store`)

## 4. Toolkit — skill sources (`skill-discovery`)
- [ ] 4.1 `:skill-provider`
- [ ] 4.2 `:skills-filter` (allowlist)
- [ ] 4.3 `:skill-sample-limit`

## 5. Toolkit — composition and toggles
- [ ] 5.1 `:agents` (`subagents`)
- [ ] 5.2 toolkit-level `:wait-for`
- [ ] 5.3 `:disable-tools` / `:disable-skills`

## 6. Promotion
- [ ] 6.1 Flip `clojure` to tier `full` in `conformance/options_manifest.json`
- [ ] 6.2 `check_options_parity.py` exits 0 with zero debt rows
- [ ] 6.3 All five execution modes green; README parity-debt section removed
- [ ] 6.4 Update `clojure/README.md` — tier `full`, no permitted absences
