# Tasks — add-retry-stream-event

## Spec

- [x] `SPEC.md` § Streaming (event) + § Resilience (`x-should-retry`, retry event)
- [x] Spec delta `specs/resilience-policy/spec.md`
- [x] `openspec validate add-retry-stream-event --strict`

## Per-language parity checklist

Each port: retry event in the streaming path, `x-should-retry` override in the shared retry
loop, and tests (event fields + order before the retried answer; true overrides a 400; false
overrides a 503; no event from `run`).

- [x] `js/`
- [x] `python/`
- [x] `golang/`
- [x] `java/`
- [x] `csharp/`
- [x] `elixir/`
- [x] `clojure/`
- [x] `rust/`

## Verification

- [x] Each port's suite green
- [x] `CHANGELOG.md` entry under `## Unreleased`
