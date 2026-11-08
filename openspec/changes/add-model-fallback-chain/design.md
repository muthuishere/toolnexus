# Design — model fallback chain

## Shape

```
for model in chain(turnModel, fallbackModels):     # outer: fallback (skips dupes/empties)
    res = fetchWithRetry(request(model))            # inner: today's retry loop, unchanged
    if ok(res) or killSwitch or not overload(res) or last: return res
    emit stream {type:"fallback", from: model, to: next}
```

`overload(res)` ⇔ the final response of an exhausted budget has status `503` or `529`. An `onError`
`"fail"` returns the response before the budget is exhausted — implementations detect this by the
fact that the inner loop returned early; the simplest faithful implementation threads a flag
(`exhausted`) out of the retry loop, or checks the status after the loop and relies on `onError`
having said `"retry"` on every attempt. Ports MUST NOT fall back when `onError` returned `"fail"`.

## Decisions

- **D1 overload = {503, 529}, exact.** Deterministic, independent of `retryableStatuses`.
- **D2 per call.** No cross-call memory; `beforeLLM` runs once per turn, before the chain.
- **D3 reporting** reuses "the model transmitted" rule of §8 Hooks, so no new field.
- **D4 kill switch** read per call: `TOOLNEXUS_DISABLE_MODEL_FALLBACK` ∈ {`1`,`true`} (case-insens.).
- **D5 in-process / ACP ignore it** — they have no HTTP status.
- **D6 stream event** `{type:"fallback", from, to}` is independent of #189's `{type:"retry"}` event;
  if both exist, retry events for the first model precede the fallback event.
