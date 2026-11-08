## 1. Spec and fixtures
- [x] 1.1 SPEC §8B: backend table row, *Decisions wire*, unknown-backend text; CHANGELOG
- [x] 1.2 `examples/judge/backends/openai-decisions.json`; openai rows in `presets.json` and `key-routing.json`

## 2. Ports (preset row + decisions wire + fixture replay)
- [x] 2.1 golang
- [x] 2.2 js
- [x] 2.3 python
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir
- [x] 2.7 clojure
- [x] 2.8 rust

## 3. Verification
- [x] 3.1 Live wire-shape check against `api.openai.com/v1/decisions` (shape only, never a number)
- [x] 3.2 Mock-kit integration case (`tools/mock-llm` `/v1/decisions`) in every port
