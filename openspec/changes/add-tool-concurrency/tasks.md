## 1. Spec
- [x] 1.1 SPEC §8 "Tool scheduling" and §7D "Background runs"
- [x] 1.2 Spec deltas (client-request-shaping, agent-runtime); `openspec validate`

## 2. Per-port parity checklist
Each port: option + env + default; Tool predicate; hints rule; call_tool/tool_search; relay and
`task` always safe; scheduler at every tool-dispatch site (run/stream × styles, resume, agents);
tests: writes serialize, reads overlap, cap, mixed order, env override, safety rule, background recipe.

- [ ] 2.1 golang
- [ ] 2.2 js
- [ ] 2.3 python
- [ ] 2.4 java
- [ ] 2.5 csharp
- [ ] 2.6 elixir
- [ ] 2.7 clojure
- [ ] 2.8 rust

## 3. Surface
- [ ] 3.1 conformance/options_manifest.json: maxToolConcurrency in every port
- [ ] 3.2 Docs site page with eight tabs (snippets pass run-all.sh)
- [ ] 3.3 CHANGELOG `## Unreleased`
