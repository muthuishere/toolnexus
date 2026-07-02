## 1. Contract

- [x] 1.1 SPEC §8: `id` on `stream`, `on_text` on `ask`, and the Observability subsection (`on_metric`
      semantic events + `client.metrics()` Prometheus text, fixed histogram buckets).

## 2. Implement across all five ports (reference JS first, then parity)

- [ ] 2.1 js (reference): `stream({id})` (history in, save on done); `ask({on_text})`; `on_metric` option
      + internal metric registry + `metrics()` Prometheus renderer; tests
- [ ] 2.2 python: port from js
- [ ] 2.3 golang: port from js
- [ ] 2.4 java: port from js
- [ ] 2.5 csharp: port from js
- [ ] 2.6 Tests (all 5): stream remembers by id; ask on_text streams + still returns RunResult;
      on_metric fires llm/tool/run events; metrics() renders Prometheus text with the documented series

## 3. Verify & release

- [ ] 3.1 All five suites green; parity spot-check (stream id, on_text, on_metric, metrics() in each)
- [ ] 3.2 `openspec validate add-streaming-memory-and-observability` passes
- [ ] 3.3 READMEs: add streaming-with-memory + observability (on_metric + /metrics) sections
- [ ] 3.4 Bump 0.4.0, PR, CI green, merge, archive, release
