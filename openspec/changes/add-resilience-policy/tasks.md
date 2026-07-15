# Tasks: add-resilience-policy

JS is the reference. Each behavior change carries a per-language parity checklist.
Scope: LLM-failure retry/fail classification only. No suspend tier (§10 untouched).

## 0. Reconcile before code
- [x] 0.1 Confirm with `add-agent-pipeline` that its `retry` combinator delegates to this
      `onError` seam (design D4) — do not apply until settled

## 1. Spec
- [x] 1.1 `SPEC.md §8` Resilience — add the `onError` classifier + two tiers (retry/fail);
      state the default classifier equals today's behavior. **§10 not touched.**

## 2. Reference implementation (JS)
- [x] 2.1 `ClientOptions.onError` + `ErrorInfo` type; default classifier = retry-transient/fail
- [x] 2.2 One branch in `fetchWithRetry`: resolved classifier → retry (bounded) | fail
- [x] 2.3 Resilience matrix tests (400/401/402/429/500/network + absent-onError parity),
      hermetic; assert bounded time + no crash; assert no `status:"pending"` on fail

## 3. Port parity — implement + matrix in each
- [x] 3.1 Python — `on_error`, retry-helper branch, matrix
- [x] 3.2 Go — `OnError`/`Tier`, retry-helper branch, matrix
- [x] 3.3 Java — `Options.onError`, retry-helper branch, matrix
- [x] 3.4 C# — `OnError`, retry-helper branch, matrix
- [x] 3.5 Elixir — `on_error`, retry-helper branch, matrix

## 4. Cross-cutting
- [x] 4.1 Default-parity assertion per port: with no `onError`, retry/fail behavior is
      byte-identical to pre-change (429-then-200 retries; 400 fails)
- [x] 4.2 Docs: Cookbook recipe "Fail fast or retry" (six languages) + a line on the
      Observability/Streaming pages; note suspend stays user-action only
- [x] 4.3 `openspec validate add-resilience-policy`

## Parity checklist
- [x] js (reference) · [x] python · [x] golang · [x] java · [x] csharp · [x] elixir
