# Tasks — add-in-process-client

The surface, once, so every port implements the same thing:

- `createInProcessClient({ model, generate, ...every other client option })`
  — idiomatic name per port. **No** `baseUrl`, `apiKey` or `style`.
- `generate(request) -> { content } | { toolCalls: [{id?, name, arguments}] }` (+ optional `usage`)
- The library derives `finish_reason`, builds `choices`, and encodes tool arguments when they are
  not already a string.
- Streaming raises, naming the limitation. Never a single-chunk fake.
- Built ON the shipped transport seam, which is unchanged.

## Spec

- [x] Spec delta at `specs/client-request-shaping/spec.md`
- [x] `openspec validate add-in-process-client --strict`
- [ ] `SPEC.md` §8 — the in-process constructor alongside Gap 2
- [ ] `docs/adr/0019` — amend: the semantic callback is un-rejected AS A LAYER, not as a replacement

## Per-language parity checklist

- [x] `js/` — `createInProcessClient`; 7 tests
- [x] `python/` — `create_in_process_client`; 8 tests
- [x] `golang/` — `CreateInProcessClient`; 6 tests
- [ ] `java/`
- [ ] `csharp/`
- [ ] `elixir/`
- [ ] `clojure/` (must pass all five execution modes)

Each port is not done without tests for: no wire config required · generate sees messages/tools/model ·
tool calls loop back with the result · arguments structured OR pre-encoded · usage optional and
derived · streaming refused loudly · generate required.

## Conformance & docs

- [ ] `conformance/options_manifest.json` — a row for the in-process constructor
- [ ] Rewrite `cookbook/local-and-in-process-models` around it (seven tabs, all executed)
- [ ] `CHANGELOG.md`
