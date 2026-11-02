## 1. Contract
- [x] 1.1 ADR 0036 accepted; spike `spikes/media-a2e/` live-verified
- [x] 1.2 SPEC §0 item 14, §7G, §8 `RunResult.files` / `toolCalls[].parts`
- [x] 1.3 `conformance/media/` verbs + cases + schemas; `examples/media-styles/a2e.json`
- [x] 1.4 CHANGELOG `## Unreleased` entry naming the v1 gaps
- [x] 1.5 `conformance/options_manifest.json`: toolkit option `media`

## 2. Per-port (engine, verbs, tools, `createToolkit({media})`, source "media", RunResult.files, conformance replay)
- [x] 2.1 golang
- [x] 2.2 js
- [x] 2.3 python
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir
- [x] 2.7 clojure
- [x] 2.8 rust

## 3. Docs
- [x] 3.1 Per-port READMEs: media section
- [x] 3.2 Docs site: media guide + cookbook recipe, all 8 language tabs
- [x] 3.3 Root README: one line in the pitch

## 4. Verify
- [x] 4.1 Every port's suite green incl. all `cases.json` + `schemas.json`
- [x] 4.2 `python3 conformance/check_options_parity.py`
- [x] 4.3 Live smoke of `examples/media-styles/a2e.json` from Go (owner key, manual)
