# Tasks: add-resilience-policy

JS is the reference. Each behavior change carries a per-language parity checklist.

## 1. Spec
- [ ] 1.1 `SPEC.md §8` Resilience — add the `onError` classifier + three tiers; state the
      default classifier equals today's retry/fail behavior
- [ ] 1.2 `SPEC.md §10` — "a suspension may originate from the LLM call; resume re-issues it";
      add `Request` `kind: "error"` with `data: { status?, attempt, retryable }`

## 2. Reference implementation (JS)
- [ ] 2.1 `ClientOptions.onError` + `ErrorInfo` type; default classifier = retry-transient/fail
- [ ] 2.2 Hook into `fetchWithRetry`: retry / fail / suspend branches
- [ ] 2.3 Suspend → §10: build `Request{kind:"error"}`, `waitFor` (ok→re-issue, !ok→original
      error), no-`waitFor` → `status:"pending"`; streaming emits `{type:"pending"}`
- [ ] 2.4 Resilience matrix tests (402/401/429/500/network + absent-onError parity), hermetic

## 3. Port parity — implement + matrix in each
- [ ] 3.1 Python — `on_error`, suspend→§10, matrix
- [ ] 3.2 Go — `OnError`/`Tier`, suspend→§10, matrix
- [ ] 3.3 Java — `Options.onError`, suspend→§10, matrix
- [ ] 3.4 C# — `OnError`, suspend→§10, matrix
- [ ] 3.5 Elixir — `on_error`, suspend→§10, matrix

## 4. Cross-cutting
- [ ] 4.1 Default-parity assertion per port: with no `onError`, captured retry/fail behavior is
      byte-identical to pre-change (429-then-200 retries; 400 fails)
- [ ] 4.2 Docs: a Cookbook recipe "Fail fast, retry, or resume" (six languages) + a note on the
      Suspension and Observability pages
- [ ] 4.3 `openspec validate add-resilience-policy`

## Parity checklist
- [ ] js (reference) · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir
