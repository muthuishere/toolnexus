# Never-built proposals (archived 2026-07-19)

Design-only OpenSpec changes that were written but never implemented. Archived here to
keep `openspec/changes/` showing only live work; nothing was deleted — the full
proposal/design/spec-delta text is preserved below and in git history.

| Change | Gist | Why archived |
|---|---|---|
| `add-governed-execution-layer` | policy/approval layer over tool execution | superseded in practice: §7D budgets + hooks + §10 approval suspension cover the need |
| `add-response-adapters` | first-class `Responder` interface + transports for `waitFor` | never resolved past design menu; `waitFor` remains a plain callback |
| `add-right-size-routing` | model routing by task size | out of scope for a vendor-neutral library (host concern) |
| `expand-api-reference-docs` | broader per-language API reference | partly overtaken by the shipped docs site pages |

Revive by moving a folder back to `openspec/changes/` and re-validating.
