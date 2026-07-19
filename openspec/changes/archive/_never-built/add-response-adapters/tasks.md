# Tasks — add response adapters for `waitFor`

> Design-only proposal. Building is a follow-up change, gated on resolving design.md D1–D5 with
> the owner. Tasks below are the per-port parity breakdown that follow-up will execute.

## 0. Resolve open design decisions (owner)

- [ ] 0.1 Pick D1 (bare function vs object vs disposable), D2 (transport set for v1), D3
      (http-poll wire contract, if D2b+), D4 (name), D5 (durable bridging). Record the picks in
      design.md before any code.

## 1. Shared contract

- [ ] 1.1 Add a "Response adapters" subsection to `SPEC.md` §10: the named `Responder` shape, the
      `stdioResponder` prompt/parse rule (byte-identical), and — per D2 — any shipped transport
      adapter's wire contract. State that `waitFor` accepts function or adapter interchangeably and
      the durable no-`waitFor` path is unchanged.
- [ ] 1.2 Add shared conformance fixtures under `examples/responders/`: the exact stdio prompt
      bytes + parse cases (`y`, `Y`, empty, `n`, an input line), and — per D2 — a request/answer
      round-trip fixture for each shipped transport adapter, so every port asserts the same bytes.

## 2. js — reference implementation

- [ ] 2.1 Introduce the `Responder` shape (per D1) and broaden `waitFor` to accept it; bare
      function path unchanged (assert byte-identical resolution vs. before).
- [ ] 2.2 Implement `stdioResponder()` per the fixture (readline; `[y/N]` prompt; input-line mode).
- [ ] 2.3 Per D2: implement the shipped transport adapter(s) (`httpPollResponder`, …), reusing the
      existing `serve()` HTTP infra where applicable; lifecycle/`close` per D1.
- [ ] 2.4 Tests: function and adapter resolve identically; no-`waitFor` still `pending`; stdio
      prompt/parse matches the shared fixture; transport adapter round-trip matches fixture.

## 3. python — parity

- [ ] 3.1 Same `Responder` shape + `waitFor` broadening (idiomatic Python; bare callable unchanged).
- [ ] 3.2 `stdio_responder()` matching the js fixture bytes exactly.
- [ ] 3.3 Per D2: transport adapter(s) matching the shared wire fixture.
- [ ] 3.4 Tests mirroring 2.4 against the same shared fixtures.

## 4. golang — parity

- [ ] 4.1 Same shape + `WaitFor` broadening (idiomatic Go; func value still valid).
- [ ] 4.2 `StdioResponder()` matching the fixture bytes exactly.
- [ ] 4.3 Per D2: transport adapter(s) matching the shared wire fixture.
- [ ] 4.4 Tests mirroring 2.4 (`go test -race`) against the shared fixtures.

## 5. java — parity

- [ ] 5.1 Same shape + `waitFor` broadening (idiomatic Java; existing lambda still valid).
- [ ] 5.2 `stdioResponder()` matching the fixture bytes exactly.
- [ ] 5.3 Per D2: transport adapter(s) matching the shared wire fixture.
- [ ] 5.4 Tests mirroring 2.4 against the shared fixtures.

## 6. csharp — parity

- [ ] 6.1 Same shape + `WaitFor` broadening (idiomatic C#; existing delegate still valid).
- [ ] 6.2 `StdioResponder()` matching the fixture bytes exactly.
- [ ] 6.3 Per D2: transport adapter(s) matching the shared wire fixture.
- [ ] 6.4 Tests mirroring 2.4 against the shared fixtures.

## 7. Docs

- [ ] 7.1 Update the docs-site Suspension page: replace the hand-written stdio `waitFor` in
      Scenario 1 with `stdioResponder()`, and add the shipped transport adapter(s) as the
      "answer later, in-process" example. Keep the durable no-`waitFor` scenario as-is.
