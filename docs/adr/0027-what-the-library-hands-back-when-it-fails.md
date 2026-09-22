# ADR 0027 — What the library hands back when it fails is part of the contract

- **Status:** **Proposed — 2026-09-21.** Two of the three reported defects reproduce offline and
  are recorded in `spikes/issues/91-92/`. The third — issue #91's headline claim — **does not
  reproduce**, and the correction is more interesting than the report.
- **Date:** 2026-09-21
- **Driver:** Issues [#91](https://github.com/muthuishere/toolnexus/issues/91) and
  [#92](https://github.com/muthuishere/toolnexus/issues/92), both filed by a host that persists
  and renders every LLM error to an event log and a web UI. Three complaints, one shape: *a
  default that cannot work, a status outside its own vocabulary, and an error string carrying an
  account identifier are all the same defect — the failure path was never held to the contract the
  success path is held to.*
- **Evidence:** `spikes/issues/91-92/` — a stub provider returning an OpenRouter-shaped 400 with a
  FAKE `user_2FAKE…` id, run against the shipped Go and JS ports; plus one live call against the
  default classifier base, 2026-09-21; plus a seven-port read of the timeout and error-wrap sites,
  cited inline below.
- **Related:** ADR 0020 (the `Classifier` seam), ADR 0021 (what the caller owes the encoding),
  ADR 0022 (cost is always reported — the same "absence must be expressible" argument, one field
  over), `SPEC.md` §7D (the agent status vocabulary), §8 (client), §8B (classifier).

## Corrections to the issues as filed

Two claims need correcting before anything is decided on them, because both reports name the
wrong defect and a fix aimed at the named defect would miss.

**#91 — `jev-latest` is not a dead alias. It serves.** One live call on 2026-09-21 with
*entirely* zero-value options —

```go
c, _ := tn.CreateClassifier(tn.ClassifierOptions{})   // base, model, apiKeyEnv all default
_, err := c.Evaluate(ctx, state, questions)
// base=https://api.typesafe.ai/v1  model=jev-latest  err=<nil>
```

— returned no error (`spikes/issues/91-92/go/live/main.go`). The documented pairing at
`site/src/content/docs/judge/backends.mdx:49` (`jev-latest` on TypeSafe's own API, "answers as
`jev-1.13.0`") is correct.

What the reporter actually hit is a **cross-base model-id mismatch**: `jev-latest` is TypeSafe's
spelling, `typesafe/jev-1.13` is the gateway's, and the three defaults do not travel as a unit.
Override `baseUrl` to OpenRouter — which the reporter did, and which the docs invite by presenting
the two routes side by side — and you keep TypeSafe's model id against a gateway that has never
heard of it. The tell is in the error body they quoted in #92: `"not a valid model ID"` plus a
`user_id`, which is OpenRouter's shape, not TypeSafe's. Worth noting alongside: neither
`jev-latest` nor `typesafe/jev-1.13` appears in OpenRouter's public `/api/v1/models` catalogue
(440 entries, checked 2026-09-21), so a reader cannot discover the gateway spelling by listing
either.

So the defect is real and the suggested fix is wrong. **Do not re-point `DefaultClassifierModel`;
it is the only id that works with `DefaultClassifierBaseURL`.** The defect is that `baseUrl`,
`model` and `apiKeyEnv` are three independent options that are only valid in two specific
combinations, and nothing in the type, the defaults or the error says so.

**#92 part 1 — `"timeout"` is not in the vocabulary the reporter is reading.** There are **two**
status vocabularies in this library and they share a field name:

| | where | values |
|---|---|---|
| agent `TaskResult.status` (§7D) | `SPEC.md:919-920` | `done` `pending` `incomplete` `interrupted` `closed` `timeout` `error` |
| client `RunResult.status` (§8) | `SPEC.md:1908` | `done` `pending` `incomplete` |

`"timeout"` belongs exclusively to the agent runtime's `wait(handle, timeoutMs)` — a *wait*
deadline where the child keeps running (js `agents/runtime.ts:443`, go `agents/runtime.go:626`,
java `AgentRuntime.java:260`, csharp `AgentRuntime.cs:176`, python `agents/runtime.py:490`, elixir
`agents/handle.ex:566`, clojure `agents/runtime.cljc:1173`). It has never been a `RunResult`
status in any port. Only Clojure holds the seven-value set as a first-class value
(`clojure/src/toolnexus/agents/runtime.cljc:153`); the other six carry it as prose plus inline
literals — which is precisely how a reader ends up applying the wrong one.

The reporter read the documented closed set, saw `timeout` in it, and branched on `res.Status`.
That is not a careless reading. **Two different closed vocabularies on two fields both called
`status` is the defect**, and it is upstream of everything #92 asks for.

## Context: the divergence the issues did not find

Chasing the timeout claim across all seven ports turned up something larger than the bug reported.

| port | on the run-level deadline | `RunResult` handed back | message | Turns/Usage |
|---|---|---|---|---|
| **golang** | `context.WithTimeout` (`client.go:535-542`, armed `:680-685`) | **returns `(RunResult{}, err)` — the zero value, `Status == ""`** | `context deadline exceeded` | **lost** (fed only to `emitRunError`, `client.go:936-940`) |
| js | `AbortController` (`src/client.ts:658-663`, rethrown unretried `:691`) | never constructed — **throws** | `run timeout after 1ms` | lost |
| python | monotonic deadline (`client.py:947-951`) | **raises** `RunTimeout` (`client.py:126`) | `run timeout after <n>ms` | lost |
| java | `Deadline` record (`LlmClient.java:2175-2204`) | **throws** `LlmClient.TimeoutException` (`:271-274`) | `run timeout after <n>ms` | lost |
| csharp | `Deadline` + linked CTS (`LlmClient.cs:1562-1576`) | **throws** `RunTimeoutException` (`:301-303`) | `run timeout after <n>ms` | lost |
| elixir | monotonic ms (`client.ex:1067-1074`) | **raises a bare `RuntimeError`** — untyped string (`:1073`) | `run timeout after <n>ms` | lost |
| clojure | **no run-level deadline at all** — `:timeout-ms` bounds one HTTP call (`client.cljc:486-490`) | throws `ex-info "LLM transport timeout"` (`:544-546`) after **retrying** it | `LLM transport timeout` | lost |

Three findings, in ascending order of severity:

1. **Go is the only port that hands back a result object on a timeout, and it is the zero value.**
   Six ports make the failure unignorable by throwing; Go's `(RunResult{}, err)` is idiomatic, but
   it means a caller who branches on `Status` first — the reporter's reading — falls through every
   case on an empty string. The reported bug is *only* a bug in Go, and it is a bug about Go's
   two-value return meeting a vocabulary that has no slot for "nothing happened".
2. **Go is also the only port whose message does not say what went wrong.** Five ports say
   `run timeout after 1ms`; Go says `context deadline exceeded`, which is indistinguishable from
   caller cancellation. Confirmed side by side in the spike.
3. **Clojure has no run-level deadline**, so a slow multi-turn loop can exceed `:timeout-ms`
   arbitrarily, and it *retries* a timeout where every other port refuses to. This is a genuine
   §8 parity break that no issue has reported.

And the third complaint, which all seven ports share equally:

| port | LLM non-2xx wrap | body |
|---|---|---|
| golang | `client.go:905`, `:1564`, `:1828` — `fmt.Errorf("LLM %d: %s", ...)` | verbatim, full, unbounded `io.ReadAll` |
| python | `client.py:244` (`_HttpError`) | verbatim, full — but the type carries `.status`/`.text` as fields |
| js | `src/client.ts:740`, `:959`, `:1053` | verbatim, full |
| java | `LlmClient.java:2334`, `:1659`, `:1852` | verbatim, full |
| csharp | `LlmClient.cs:1702`, `:1714` | verbatim, full |
| elixir | `client.ex:1225`, `:1249` | verbatim, full |
| clojure | `client.cljc:547` | verbatim in the message; ex-data carries `:status` only |

**No port truncates or redacts the LLM body.** Reproduced: a 400 from the stub provider surfaces as
`LLM 400: {"error":{...},"user_id":"user_2FAKE…"}` in both Go and JS, verbatim, with the account
id intact — and a plain `Error`/`error` with no structure a host could strip it from.

**The library already knows better, one file over.** The classifier path wraps a non-2xx through
`cause()`, which (a) returns `""` for 401/403 because an auth body routinely echoes the credential
or the header that was sent, and (b) caps the body at 200 characters — in all seven ports
(`golang/classifier.go:844-856`, `python/.../classifier.py:863-872`, `js/src/classifier.ts:779-784`,
`Classifier.java:827-833`, `Classifier.cs:988-995`, `elixir/.../classifier.ex:782-790`,
`clojure/.../classifier.cljc:520-534`). The §8 client path has none of it.

That policy is also not sufficient on its own: the spike's fake OpenRouter body is 96 bytes, so the
200-char cap passes it through untouched, `user_2FAKE…` and all. **A length cap is not redaction.**

## The principle

Every one of these is the same failure of care, and it is worth naming because it will recur:

> **The success path is held to a contract the failure path is not.** A `Decision` is validated
> against a closed schema; the error that replaces it is a `printf`. `RunResult.status` is a
> documented closed set; the value on the deadline path is whatever the zero value happens to be.
> `apiKeyEnv` takes a *name* so a credential cannot be logged; the error string next to it carries
> an account identifier to the same log. Absent cost is `nil` rather than `0` because absence must
> be expressible (ADR 0022) — and then an absent run is `Status: ""`, which is the exact mistake
> that decision was made to avoid, one struct over.

A failure is a return value. It gets the same treatment: a **closed vocabulary**, a **stated
absence**, and **no credential-adjacent data**.

## Decision

### D1 — Classifier defaults: make the *pairing* the unit, and make the error self-explaining

Keep `DefaultClassifierModel = "jev-latest"`. It works. Instead:

1. **Ship the gateway as a named pairing, not three options a reader assembles.** `baseUrl`,
   `model` and `apiKeyEnv` are only jointly valid; expose that — a `ClassifierBackend` preset
   (`typesafe` | `openrouter`) that sets all three together, with the individual options still
   available for a self-hosted origin. This is the same shape as `style`: a named choice of
   *whose endpoint answers*, which `backends.mdx` already describes in prose and does not offer.
2. **Detect the known mismatch at construction.** If `baseUrl` is the gateway and `model` is an
   unqualified `jev-*`, fail before the wire with `model "jev-latest" is TypeSafe's spelling; on
   openrouter.ai use "typesafe/jev-1.13"`. A wrong-endpoint 400 that arrives 700 ms later as
   "Unknown model" is the worst possible form of this news.
3. **Name the pairing in the docs table**, so the two rows of `backends.mdx:47-52` read as two
   configurations rather than six independently-choosable cells.

Rejected: re-pointing the default model (it is correct); dropping the floating alias (it is the
only id TypeSafe's own API serves, and `Decision.model` already echoes what actually answered).

### D2 — Status on timeout: close the vocabulary, then fill it

Three parts, in order — the first is the actual bug:

1. **Rename the client field or the agent field so two closed sets do not share a name**, and make
   both sets first-class values in every port, following Clojure's
   `agents/runtime.cljc:153`. Prose plus inline literals is how the wrong vocabulary gets read.
   This is a `SPEC.md` change (§7D and §8) and it is the one thing here that must land in all seven
   ports together.
2. **Go: never return a zero-value `RunResult` beside a non-nil error.** Populate `Status` — and
   the `"error"` slot is the honest one, not `"timeout"`: the run did not *complete* at a deadline,
   it *failed* at one. Preserve `Turns`, `Usage`, `Messages` and `ToolCalls` accumulated before the
   deadline; they already exist at the return sites (`client.go:960, 975, 996, 1003, 1235, 1253,
   1267, 1274`) and are currently discarded into `RunResult{}`. Adding `"error"` to the §8 set is a
   vocabulary change, so it goes through §8 first.
3. **Go and Elixir: say what happened.** Go's `context deadline exceeded` becomes
   `run timeout after <n>ms`, matching the other five and distinguishing a deadline from caller
   cancellation. Elixir's bare `raise "…"` becomes a named exception so a host can `rescue` a
   timeout distinctly.
4. **Clojure: arm a run-level deadline**, and stop retrying it. Tracked separately — it is a §8
   parity break, not part of this issue.

Rejected: "document that `err` must be checked before `Status`". It is true of Go and vacuous
everywhere else, and it asks every host to remember what the type could enforce.

### D3 — Error bodies: structure first, redact second, cap third

1. **Carry the provider failure as a value, not a sentence.** A typed error with `status`, `body`
   and (where present) `retryAfter` as fields, in every port. Python already has this
   (`_HttpError`, `client.py:239-248`) and it is private; Clojure half has it
   (`ex-data` carries `:status` but not the body). A host that must decide *what to log* cannot do
   it by parsing `err.Error()`, and it is the only thing that makes the rest of this optional
   rather than lossy.
2. **Redact known account-identifier keys before interpolation** — `user_id`, `account_id`,
   `organization`, `org_id` — replaced with `«redacted»`, not dropped, so the shape survives. One
   shared key list, `SPEC.md`-pinned, seven ports. The value stays reachable on the structured
   error for a host that genuinely wants it.
3. **Lift the classifier's `cause()` policy to the §8 client path**: 200-character cap, and
   `""` for 401/403. It is already written seven times; it should not be written an eighth
   differently.
4. **Extend the credentials guarantee in `SPEC.md`** to say what an error message may contain.
   Today the guarantee covers headers and `apiKeyEnv` and stops at the point the failure begins.

Rejected as a *sole* measure: documenting it. The reporter's own words — "we scrub it on our side,
but every host has to know to" — are the argument. A guarantee that each host must re-implement is
not a guarantee.

## What must not regress

The issues praise two behaviours by name. Both are load-bearing and both sit directly in the code
these decisions touch:

1. **Fail-fast on 4xx despite `Retries`.** A 400 fails after exactly one attempt — reproduced in
   the spike: 1 HTTP hit with `Retries: 4`, 1 ms in Go, 16 ms in JS. The mechanism is an
   *enumerated* retryable set `{429, 500, 502, 503, 504, 529}` — never "any 5xx" — plus
   `tier == fail ⇒ surface immediately` (`golang/client.go:219, 849-853`; `python/client.py:90-99,
   1048-1052`; `js/src/client.ts:684-694`; `LlmClient.java:48, 2241-2243`; `LlmClient.cs:71,
   1620-1622`; `elixir/client.ex:268, 1088-1089`; `clojure/client.cljc:408-441, 536-556`). D3 adds
   a type and a redaction *around* the error; it must not touch the enumerated set or the
   fail branch. A spike assertion on attempt-count guards this.
2. **`ClassifierUsage.Cost` as `*float64` — absent is not zero.** ADR 0022 records why; the
   reporter independently confirms it stopped them printing `$0.00` for "this backend does not
   say". Any "simplification" to a plain `float64`/`double` is a regression, and D2's
   preserve-partial-`Usage` work runs through neighbouring code. It is the same principle this ADR
   generalises, so regressing it here would be particularly poor.

## Consequences

- One `SPEC.md` change of substance (the two status vocabularies) that must land in all seven ports
  at once, and two that are additive (the error type, the redaction policy).
- D3's typed error is a **breaking change for anyone matching on message text**, which is the
  current interface precisely because there is no other one. It needs a changelog entry that says
  so plainly.
- D1 is the only decision that touches no port's run loop, and the only one that closes its issue
  outright. It is the cheapest thing here and should not wait for the rest.
- `backends.mdx` claims a latency A/B across both routes but
  `site/scripts/generate-judge-live.mjs:38-44` pins the generator to the gateway; the TypeSafe
  column is not regenerable from the committed runner. Noted, not fixed here.

## Open questions

1. Which field gets renamed in D2.1 — the agent `status` or the client `status`? The agent
   vocabulary is the one §7D calls closed and the one a host sees less often; the client one has
   more callers. Neither is free.
2. Does `"error"` enter the §8 `RunResult` set (D2.2), or does Go instead return `"incomplete"`
   with a `Limit` of `"timeout"`, reusing the mechanism that already exists for `MaxTurns`
   (`golang/client.go:475`)? The second invents nothing and may be the better answer.
3. Should redaction (D3.2) apply to *tool* results too? A tool that proxies a provider can carry
   the same identifier into a transcript, which is a wider blast radius than an error string.

## Gate

Before this moves past Proposed:

- The spike asserts, per port, that a 4xx costs exactly one attempt under `Retries: 4`.
- A fixture body containing `user_id` is shown to survive the 200-char cap and be caught by the
  key redaction — the two are independent and both are needed.
- Q2 is answered, because D2.2 cannot be written until it is.
