# Tasks — add-tool-relay-mode

Per the prime directive, a behavior change lands in **all six ports** or it is not done.
Go is the reference port (D7); the other five port its tests and match its behavior.

## 0. Contract first

- [ ] `SPEC.md` §10: pin `kind: "tool_call"` and the `data.calls` entry shape
      (`{id, name, input}`, tool-call order), byte-identical across ports
- [ ] `SPEC.md` §10: specify the answer-carrying resume entry point — applies the `Answer`
      by id, fills **every** outstanding `tool_result` slot of the halted turn (D4), errors
      on an `Answer` matching no outstanding suspension
- [ ] `SPEC.md` §8: add the resume entry point to the client API surface table
- [ ] `SPEC.md` §0: add relay to the one-page conformance contract
- [ ] `SPEC.md` §10: state explicitly that the first-in-order halt rule and the
      not-a-tool-error rule are **unchanged** and inherited by relay
- [ ] Resolve design open question: do `data.calls` entries also carry the raw arguments
      JSON string alongside the parsed `input`? Decide before the first port lands.

## 1. Reference port — `golang/`

- [ ] `RelayTool(name, description, schema)` constructor (D1)
- [ ] Relay suspension carries all of the turn's relay calls in `Request.data.calls` (D2),
      non-streaming loop
- [ ] Same on the streaming loop — spike S12 measured the identical gap there
- [ ] Same on the Anthropic-native loop — spike S11 measured the identical gap there
- [ ] `RunWithAnswer` / `Ask(..., answer)` resume entry point (D3)
- [ ] Resume fills **every** outstanding `tool_result` slot of the halted turn (D4)
- [ ] Resume errors when the `Answer` matches no outstanding suspension
- [ ] `Answer.data` carries `output` + error flag; caller-side failure → error
      `tool_result`, `ok:false` → declined error result (D6)
- [ ] Unconditional builtin-name collision guard at toolkit construction, enforced even
      when builtins are off (D5)
- [ ] Fold `golang/relay_spike_test.go` into the real suite (or delete it) — it is
      explicitly temporary scaffolding
- [ ] Update the three baseline tests (S4, S11 durable, S12 durable) that assert *today's*
      pre-change numbers — they are designed to fail once `data.calls` exists
- [ ] Verify the pre-existing `TestConcurrentSuspensionsSurfaceFirst` and its streaming
      twin still pass **unmodified** — this is the non-regression gate
- [ ] `go build ./... && go vet ./... && go test -race ./...` green

## 2. Test cases every port must carry (from spike 0002)

Port all fourteen; S4's measured numbers are the cross-port oracle (D7).

- [ ] Single relay call, in-process — host's output becomes the `tool_result` **and reaches
      the provider's next request body**
- [ ] Caller-side tool failure → error `tool_result`, run completes
- [ ] Caller declines (`ok:false`) → error `tool_result`, run completes, no run error
- [ ] Three parallel relay calls, in-process — all three surface, transcript balanced
- [ ] Three parallel relay calls, durable — all three in `data.calls`; after resume, one
      `tool_result` per `tool_use` (the post-change assertion)
- [ ] Relay + a REAL executing tool in one turn — the real tool still runs, on both the
      in-process and durable paths, with the real tool ordered first
- [ ] Declared-but-uncalled relay tool is inert — same text, status, turns; zero tool calls
- [ ] Relay suspension is not a tool error (`isError:false`, `pending:true`)
- [ ] Multi-round relay — three successive rounds, no lockout
- [ ] `ConversationStore` round trip — ids and values replay structurally to the provider
- [ ] Anthropic-native loop — `tool_result` references the original `tool_use` id
- [ ] Streaming loop — emits `pending` carrying the relay `Request`; resolves inline
- [ ] Collision guard rejects a builtin-colliding relay name, builtins on **and** off
- [ ] Resume across a process boundary — persist request + transcript, new client resumes
- [ ] **New, not in spike 0002:** a relay call and an auth-required MCP suspension in the
      same turn (design risk item — currently unmeasured)

## 3. Per-language parity checklist

If a pass covers only a subset, the rest stay unchecked — never let parity drift silently.

- [ ] `js/` — implementation + all cases · `npm test`
- [ ] `python/` — implementation + all cases · `python -m pytest -q`
- [ ] `golang/` — implementation + all cases · `go test -race ./...`
- [ ] `java/` — implementation + all cases · `./gradlew test --no-daemon`
- [ ] `csharp/` — implementation + all cases · `dotnet test`
- [ ] `elixir/` — implementation + all cases · `mix test` + `mix coveralls` (≥95%)

## 4. Docs

- [ ] Per-port README: relay tools section (proxy/translator use case)
- [ ] Docs site: cookbook recipe — "use toolnexus as a translator with client-executed
      tools", per language
- [ ] API-reference entries for `RelayTool` and the resume entry point, all six ports

## 5. Close the loop

- [ ] Mark spikes 0001 + 0002 superseded-by-implementation; keep them as the evidence trail
- [ ] Comment on issue #37 with the shipped API and close it
- [ ] Notify routsi: ADR-010 unblocked; its item 4 (memory round-trip) was already
      satisfied and can be struck
- [ ] PR with the change folder + code in one diff
- [ ] After merge only: `/opsx:archive add-tool-relay-mode`
