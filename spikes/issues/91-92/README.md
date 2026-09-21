# Spike — issues #91 / #92: what the library hands back when it fails

Backs [ADR 0027](../../../docs/adr/0027-what-the-library-hands-back-when-it-fails.md).

Two ports (Go and JS) run the **same three failure scenarios** against the **same local stub
provider**, so a difference in the output is a real cross-port divergence and not a difference in
the experiment.

**Nothing here touches the network or a credential.** The stub returns an *OpenRouter-shaped* 400
body carrying an obviously **fake** account id, `user_2FAKEFAKEFAKEFAKEFAKEFAKE`. No real
`user_id` was ever captured, so there is nothing in this directory to scrub.

```
go -C go run .      # Go port
node js/spike.mjs   # JS port  (needs js/dist — `cd js && npm run build`)
```

One opt-in live reproduction lives in `go/live/` and is **not** part of the above; see the bottom
of this file.

## What each scenario shows

| | scenario | claim under test |
|---|---|---|
| A | provider 400 with `Retries: 4` | #92 part 2 (body leaks `user_id`) **and** the fail-fast-on-4xx behaviour #92 praises |
| B | `TimeoutMs: 1` against a slow provider | #92 part 1 (zero-value `RunResult`, empty `Status`) |
| C | the same deadline on the stream path | does streaming diverge from the blocking loop? |
| D | zero-value `ClassifierOptions` | #91 — the defaults, pointed at the stub |

## Captured output — Go

```text
=== A. provider 400 with Retries: 4 (issue #92 part 2, and the praised fail-fast) ===
elapsed      : 1ms
HTTP attempts: 1   (Retries: 4 -> fail-fast on 4xx is WORKING if this is 1)
err          : LLM 400: {"error":{"message":"not a valid model ID","code":400},"user_id":"user_2FAKEFAKEFAKEFAKEFAKEFAKE"}
err carries account id? true
Status="" Turns=0 Usage={PromptTokens:0 CompletionTokens:0 TotalTokens:0}

=== B. TimeoutMs: 1 against a slow provider (issue #92 part 1) ===
err          : context deadline exceeded
Status       : ""   <- documented vocabulary is done|pending|incomplete|...|timeout|error
Turns=0 ToolCallCount=0 Usage={PromptTokens:0 CompletionTokens:0 TotalTokens:0} Model="" Text=""
RunResult is the zero value? true

=== C. TimeoutMs: 1 on Stream (does the streaming path differ?) ===
  event 1: type="error" err=context deadline exceeded

=== D. classifier zero-value defaults (issue #91), pointed at the stub ===
DefaultClassifierBaseURL  = "https://api.typesafe.ai/v1"
DefaultClassifierModel    = "jev-latest"
DefaultClassifierAPIKeyEnv= "TYPESAFE_API_KEY"
err             : classifier: POST http://127.0.0.1:62502/systemone: HTTP 400: {"error":{"message":"not a valid model ID","code":400},"user_id":"user_2FAKEFAKEFAKEFAKEFAKEFAKE"}
err carries account id? true
```

## Captured output — JS

```text
=== A. provider 400 with retries: 4 (issue #92 part 2 + the praised fail-fast) ===
elapsed      : 19 ms
HTTP attempts: 1  (retries: 4 -> fail-fast on 4xx is WORKING if this is 1)
err          : LLM 400: {"error":{"message":"not a valid model ID","code":400},"user_id":"user_2FAKEFAKEFAKEFAKEFAKEFAKE"}
err carries account id? true
partial RunResult attached to the error? no — plain Error

=== B. timeoutMs: 1 against a slow provider (issue #92 part 1) ===
THREW, no RunResult at all: Error - run timeout after 1ms
partial result on the error? no

=== C. timeoutMs: 1 on the stream path ===
  stream THREW: Error - run timeout after 1ms
```

## What reproduced

| claim | Go | JS |
|---|---|---|
| a 4xx costs **one** attempt despite `Retries: 4` | ✅ 1 hit, 1 ms | ✅ 1 hit, 16 ms |
| the provider body rides out **verbatim**, account id intact | ✅ | ✅ |
| a timeout yields a **zero-value `RunResult` with `Status: ""`** | ✅ | ❌ — JS *throws*, so there is no result to misread |
| the timeout message says what happened | ❌ `context deadline exceeded` | ✅ `run timeout after 1ms` |
| the classifier wrap also carries the account id | ✅ (the 200-char cap does not help: the body is 96 bytes) | — |

**The headline is the divergence, not the bug.** Six of seven ports throw on a run-level deadline;
Go alone returns a result object, and it is the zero value. And Go alone reports a deadline in
words that are indistinguishable from caller cancellation. See ADR 0027 for the full seven-port
table.

Note also that the stream path (C) differs *within* Go: it surfaces a typed `error` event rather
than an empty result, so the blocking loop is the only place the empty `Status` can be observed.

## The one live call (`go/live/`, opt-in)

Issue #91 says the zero-value classifier construction returns `HTTP 400 — Unknown model`.
**It does not.** One live call on 2026-09-21 with every option left at its default:

```text
base=https://api.typesafe.ai/v1 model=jev-latest
err=<nil>
```

`jev-latest` is served by the default base. What the reporter hit was TypeSafe's model id sent to
OpenRouter's endpoint — the defaults are only jointly valid, and nothing says so. The runner reads
`TYPESAFE_API_KEY` **by name**, through the library's own `apiKeyEnv`; the value never enters the
program, its output, or this file. It no-ops when the variable is unset.
