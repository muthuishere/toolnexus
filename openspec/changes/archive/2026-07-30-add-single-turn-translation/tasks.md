# Tasks — add-single-turn-translation

Per the prime directive this lands in **all six ports** or it is not done. `golang/` is the
reference port (D7); the other five port its tests and match its behavior.

## 0. Contract first

- [x] `SPEC.md` §11 (new): the `translate` entry point — one provider call, no loop, no
      execution, no state; the request shape (OpenAI messages/tools/toolChoice verbatim, plus
      an optional toolkit); the result shape (`arguments` as a JSON string)
- [x] `SPEC.md` §11: inbound translation rules — assistant `tool_calls` → tool-use blocks
      with arguments re-parsed to objects, `tool` results → tool-result blocks keyed by
      `tool_call_id` and **merged into one user turn** when consecutive, `system` hoisted
- [x] `SPEC.md` §11: `finishReason` mapping, with tool calls winning
- [x] `SPEC.md` §0: add translation to the one-page conformance contract
- [x] `SPEC.md` §11: state that tool hooks do NOT fire and `beforeLLM`/`afterLLM` fire once

## 1. Reference port — `golang/`

- [x] `Translate(ctx, TranslateRequest) (TranslateResult, error)`
- [x] OpenAI messages/tools/toolChoice accepted verbatim; struct messages accepted
- [x] `Toolkit` input declared but never executed; composes with the OpenAI array
- [x] Inbound: tool_calls → tool_use (arguments re-parsed), tool results → tool_result
      keyed by `tool_call_id`, consecutive results merged into one user turn, system hoisted
- [x] Outbound: `arguments` as a JSON string, provider order preserved, nothing dropped
- [x] `finishReason` mapping with tool calls winning
- [x] Anthropic-style upstream (real translation) + OpenAI-style upstream (passthrough)
- [x] Reuses retries/backoff, request-param merging, the LLM metric; `beforeLLM`/`afterLLM`
      fire once
- [x] `ToolCallsJSON()` envelope helper
- [x] `examples/translator` — a stateless OpenAI-compatible proxy
- [x] `go build ./... && go vet ./... && go test -race ./...` green

## 2. Test cases every port must carry

Go's assertions are the cross-port oracle; D4 is the most likely divergence.

- [x] One provider call per `translate`; three repeated calls accumulate no state
- [x] A toolkit tool is DECLARED but its handler never runs
- [x] A toolkit and an OpenAI `tools` array compose
- [x] OpenAI declarations reach an Anthropic upstream as `input_schema`, with no
      `parameters` key leaking
- [x] Multi-turn exchange survives: system hoisted, `tool_use` with object arguments,
      `tool_result` keyed by the same `tool_call_id`
- [x] Three consecutive tool results → ONE user turn carrying three `tool_result` blocks,
      and one assistant turn carrying three `tool_use` blocks
- [x] Parallel calls: text + three `tool_use` → text + three tool calls in provider order
- [x] `arguments` returned as a JSON string that parses back to the original object
- [x] `arguments` ACCEPTED as either a JSON string or an object on the way in
- [x] `tool_choice` mapping: absent/auto omitted; required/none/specific mapped
- [x] `finishReason`: stop / length / content_filter, and tool calls winning
- [x] Content-parts array flattened to text
- [x] OpenAI-style upstream passes `arguments` through byte-for-byte
- [x] `beforeLLM`/`afterLLM` fire exactly once; no tool hook fires

## 3. Per-language parity checklist

If a pass covers only a subset, the rest stay unchecked — never let parity drift silently.

- [x] `js/` — implementation + all cases · `npm test` (13 tests; suite 130/130)
- [x] `python/` — implementation + all cases · `python -m pytest -q` (20 tests; suite 169 passed)
- [x] `golang/` — implementation + all cases · `go test -race ./...` (12 tests green)
- [x] `java/` — implementation + all cases · `./gradlew test --no-daemon` (13 tests; suite green)
- [x] `csharp/` — implementation + all cases · `dotnet test` (20 tests; suite 194 passed)
- [x] `elixir/` — implementation + all cases · `mix test` + `mix coveralls` (≥95%) (27 tests; suite 370 passed; coverage 96.9%, translate.ex 96.5%)

## 4. Docs

- [ ] Per-port README: a "use it as a translator" section
- [ ] Docs site cookbook recipe: provider-portable tool calling where the CALLER executes
- [ ] API-reference entries for `translate`, the request and the result, all six ports
- [ ] A runnable translator example per port (Go has one)

## 5. Follow-ups this change does NOT do

- [ ] **Streaming translation** (SSE in, indexed tool-call deltas out). Wanted; correctness
      first. Own change.
- [ ] Gemini-style upstream support (inbound/outbound mapping; `ToGemini` is declarations
      only today).
- [ ] Unifying the parallel provider-assembly logic with the loop's — would touch the loop,
      which this change deliberately avoids. Revisit with streaming.

## 6. Close the loop

- [ ] Notify the consumer (routsi) for review; they asked to review as the consumer
- [ ] PR with the change folder + code in one diff
- [ ] After merge only: `/opsx:archive add-single-turn-translation`
