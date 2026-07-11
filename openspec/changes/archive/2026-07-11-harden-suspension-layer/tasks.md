# Tasks — harden the §10 suspension layer (G2 + G3)

## 0. Contract
- [x] 0.1 `SPEC.md` §10: a suspension is never a tool error (emitted `pending`, not `isError`);
      concurrent durable suspensions surface the **first** in tool-call order deterministically.
- [x] 0.2 Prometheus shape LOCKED: `pending` label on the existing tool counter (order
      `tool,source,is_error,pending`), `is_error=false` for suspensions. Identical in all five.

## 1. JS (`js/`) — reference port
- [ ] 1.1 G2: in `runTool` (`src/client.ts:397`), compute `pendingOf(result)` before the metric +
      `afterTool`; skip `afterTool` on a suspension; emit `tool` with `isError:false, pending:true`.
      Apply the same to the `beforeTool` short-circuit emit. Add `pending?:boolean` to the `tool`
      `MetricEvent` + the Prometheus counter label.
- [ ] 1.2 G3: non-streaming loop (`src/client.ts:471`): halt on the first suspension in call order
      (not last-write-wins) and don't append the other suspensions' placeholder results.
- [ ] 1.3 Tests: (a) a suspended tool call emits `pending:true`/`isError:false` and does not increment
      the error counter, and `afterTool` runs only on the resolved result; (b) path-B lock test
      (guard-raised pending honored, `pending:true`); (c) two concurrent question suspensions, no
      waitFor → `RunResult.pending` is the first in order, second not in the transcript.
- [ ] 1.4 `npm test` green.

## 2. Python (`python/`)
- [ ] 2.1 G2 in the Python client loop (mirror runTool). 2.2 G3 non-streaming path. 2.3 tests. 2.4 `pytest -q` green.

## 3. Go (`golang/`)
- [ ] 3.1 G2. 3.2 G3. 3.3 tests. 3.4 `go test -race ./...` green.

## 4. Java (`java/`)
- [ ] 4.1 G2. 4.2 G3. 4.3 tests. 4.4 `./gradlew test --no-daemon` green.

## 5. C# (`csharp/`)
- [ ] 5.1 G2. 5.2 G3. 5.3 tests. 5.4 `dotnet test` green.

## 6. Parity + validate
- [ ] 6.1 The `tool` event `pending` marker + counter behave identically across ports.
- [ ] 6.2 `openspec validate harden-suspension-layer --strict`.

## Status (2026-07-11)
- G2 + G3 implemented in **both** non-streaming loops (OpenAI + Anthropic) in JS (ref, commits
  6f19705 + e3df17f), Go, Java, C# — each independently green. Python: OpenAI loop done + G2/tests
  green; Anthropic loop (`_run_anthropic`) fix in progress (the initial JS ref only had OpenAI; the
  port agents surfaced the gap and it was back-ported to JS).

## Notes
- With a `waitFor`, concurrent suspensions already resolve inline (no G3 loss) — the fix targets the
  no-`waitFor` durable halt only.
- R1 (`Answer.reason`) and R2 (`Request.schema`) are intentionally NOT here — they land with
  `add-mcp-elicitation-bridge`, which needs them.

## Follow-up (pre-existing, OUT OF SCOPE here) — streaming-suspension parity
Independently flagged by the Go and C# port agents: **the streaming loops do NOT all halt on the
first suspension.** JS streaming (`streamOpenAI`/`streamAnthropic`) returns inside the loop on the
first halt, but the Go and C# streaming loops accumulate a last-write-wins `halted` and push every
suspension's placeholder — the same G3 bug this change fixes for the non-streaming paths, still
present in some ports' streaming paths, and divergent from JS. This is a pre-existing cross-port
parity gap (not introduced here; this change deliberately scoped to non-streaming). → file
`harden-suspension-layer-streaming` (or fold into a streaming-parity pass): bring every port's
streaming loop to JS's halt-on-first behavior, with an Anthropic + OpenAI streaming G3 test each.
