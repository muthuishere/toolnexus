## 1. Contract
- [x] 1.1 ADR 0036 accepted; spike `spikes/media-a2e/` live-verified
- [x] 1.2 SPEC §0 item 14, §7G, §8 `RunResult.files` / `toolCalls[].parts`
- [x] 1.3 `conformance/media/` verbs + cases + schemas; `examples/media-styles/a2e.json`
- [ ] 1.4 CHANGELOG `## Unreleased` entry naming the v1 gaps
- [ ] 1.5 `conformance/options_manifest.json`: toolkit option `media`

## 2. Per-port (engine, verbs, tools, `createToolkit({media})`, source "media", RunResult.files, conformance replay)
- [ ] 2.1 golang
- [ ] 2.2 js
- [ ] 2.3 python
- [ ] 2.4 java
- [ ] 2.5 csharp
- [ ] 2.6 elixir
- [ ] 2.7 clojure
- [ ] 2.8 rust

## 3. Docs
- [ ] 3.1 Per-port READMEs: media section
- [ ] 3.2 Docs site: media guide + cookbook recipe, all 8 language tabs
- [ ] 3.3 Root README: one line in the pitch

## 4. Verify
- [ ] 4.1 Every port's suite green incl. all `cases.json` + `schemas.json`
- [ ] 4.2 `python3 conformance/check_options_parity.py`
- [ ] 4.3 Live smoke of `examples/media-styles/a2e.json` from Go (owner key, manual)
