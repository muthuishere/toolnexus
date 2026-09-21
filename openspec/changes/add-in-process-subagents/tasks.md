# Tasks — add-in-process-subagents

The surface, once, so every port implements the same thing:

- the agent/sub-agent runtime options accept a semantic `generate`, idiomatically named
- mutually exclusive with transport/provider config, **validated at construction**
- the private generate-backed adapter becomes **exported**; the in-process client calls it
- the global turn gate applies unchanged on the new path

## Spec

- [x] Spec delta at `specs/subagents/spec.md`
- [x] `openspec validate add-in-process-subagents --strict`
- [ ] `SPEC.md` — the sub-agent section names the in-process option
- [x] `docs/adr/0024` revised to record the falsified parity assumption

## Per-language parity checklist

Each port is not done without: the option · the construction-conflict error · the exported
adapter · a test sharing ONE generate across a top-level client and a sub-agent · a turn-gate
test **with a negative control**.

- [x] `golang/`
- [x] `js/`
- [x] `python/`
- [x] `java/`
- [x] `csharp/`
- [x] `elixir/`
- [x] `clojure/`

## Changelog

- [x] `CHANGELOG.md` `## Unreleased` — what a user gets, and the adapter now being public
