## 1. Spec
- [x] 1.1 SPEC §8 "Tool scheduling" and §7D "Background runs"
- [x] 1.2 Spec deltas (client-request-shaping, agent-runtime); `openspec validate`

## 2. Per-port parity checklist
Each port: option + env + default; Tool predicate; hints rule; call_tool/tool_search; relay and
`task` always safe; scheduler at every tool-dispatch site (run/stream × styles, resume, agents);
tests: writes serialize, reads overlap, cap, mixed order, env override, safety rule, background recipe.

- [x] 2.1 golang
- [x] 2.2 js
- [x] 2.3 python
- [x] 2.4 java
- [x] 2.5 csharp
- [x] 2.6 elixir
- [x] 2.7 clojure
- [x] 2.8 rust

## 3. Surface
- [x] 3.1 conformance/options_manifest.json: maxToolConcurrency in every port
- [x] 3.2 Docs site page with eight tabs (snippets pass run-all.sh)
- [x] 3.3 CHANGELOG `## Unreleased`
