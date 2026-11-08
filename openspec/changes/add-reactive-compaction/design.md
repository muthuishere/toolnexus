## Decisions

**D1 — The option is a hook, not a new helper.** `compactOnOverflow` takes the `beforeLLM`
signature, so the existing `compactor(...)` plugs in directly and a host can supply any
`messages → messages` rewrite. No new compaction code, no forced mode. A host passing the same
compactor it uses proactively may see it no-op (the estimate said "fits"); that is reported as
"no progress" immediately rather than looping — the docs say to give it a tighter budget.

**D2 — Detection is status AND marker, lowercase substring, on the redacted body.** 400/413 alone
is too broad (every malformed request is a 400); a marker alone is too broad (a 429 can mention
the context length). Markers: `context_length_exceeded`, `maximum context length`,
`prompt is too long`, `prompt_too_long`, `request_too_large`, `context window`. Redaction only
touches account-id keys, so markers survive it. Detection runs after the retry layer; 400/413 are
not in the retryable set, so no retry is spent first.

**D3 — "Rapid refill" = re-overflow within fewer than 2 successful LLM calls of the last reactive
compaction.** Counters `okSince`, `streak`. A non-rapid overflow resets the streak. The 4th
overflow of a rapid chain fails (3 compactions spent). Window 2 means the retry succeeding once is
not enough evidence of progress; one full further turn must also fit. Deterministic and
fixture-tested.

**D4 — The retry is the same turn.** No extra `beforeLLM` call, no turn consumed; `RunResult.turns`
still counts turns. The compacted transcript replaces the working transcript exactly as a
`beforeLLM` rewrite does.

**D5 — Typed error, pinned message.** `ContextOverflowError{compactions, status, body}`,
message `context overflow: <n> reactive compaction(s) did not make the prompt fit`. Never a §10
pending.

## Not done

No proactive change; no per-agent (§7D AgentDef) option — an agent run uses the client's option.
