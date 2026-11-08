# Tasks — add-stop-reason-recovery

## Spec

- [x] `SPEC.md` §0 item 10 + §8 *Stop-reason recovery* (pinned texts)
- [x] Spec delta `specs/stop-reason-recovery/spec.md`
- [x] `openspec validate add-stop-reason-recovery`
- [x] Fixture `conformance/stop_recovery/cases.json`
- [x] `conformance/options_manifest.json` row `stopRecovery`

## Per-language parity checklist

Each port: option (default off), run + stream, both styles, tests replaying
`conformance/stop_recovery/cases.json`.

- [ ] `js/`
- [ ] `python/`
- [ ] `golang/`
- [ ] `java/`
- [ ] `csharp/`
- [ ] `elixir/`
- [ ] `clojure/`
- [ ] `rust/`

## Changelog

- [x] `CHANGELOG.md` `## Unreleased`
