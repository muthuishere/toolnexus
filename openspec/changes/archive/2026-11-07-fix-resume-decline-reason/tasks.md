## 1. Contract

- [x] 1.1 SPEC §10 durable resume: declined answer resolves every outstanding call with the reason
- [x] 1.2 Fixture `conformance/resume_decline/cases.json`

## 2. Ports

- [x] 2.1 golang — `repairHaltedTurn` + `TestResumeDeclinedCarriesReason`
- [x] 2.2 rust — `repair_halted_turn` + `resume_declined_carries_reason`
- [x] 2.3 js / python / java / csharp / elixir / clojure — N/A: no durable resume entry point
      (inline `ok:false` relay already carries the reason)

## 3. Release notes

- [x] 3.1 CHANGELOG `## Unreleased`
