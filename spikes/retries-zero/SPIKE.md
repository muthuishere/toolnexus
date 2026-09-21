# Spike: ADR-0029 gate — "Retries must be able to mean zero"

Attacking the gate in `docs/adr/0029-retries-must-be-able-to-mean-zero.md`, not
confirming it. Everything below was **run**, not just read.

## TL;DR verdict

| Gate item | Verdict | One line |
|---|---|---|
| 1. `-1` is the only additive spelling, in every port | **FALSIFIED for 6/7 ports** | Only **Go** has the "0 collides with unset" bug. JS/Python/Java/C#/Elixir already let `retries: 0` mean zero, by construction, with **zero code changes needed**. Clojure is a separate story (see below). |
| 2. `-1` never leaks into backoff arithmetic (Go) | **HOLDS**, once implemented correctly | Implemented for real in `golang/client.go:521` + `golang/classifier.go:781`; proved no negative sleep, no skipped first attempt, no regression — see "Go implementation" below. |
| 3. `CreateInProcessClient` becomes a caller of the new spelling | **HOLDS**, once implemented | Rewrote `golang/inprocess.go:184-219` to pass `Retries: -1` and drop the private `OnError` workaround; full existing test suite still green. |
| 4. manifest / `check_options_parity.py` | **HOLDS, no-op** | The manifest only tracks option **names/aliases**, not semantics or nullability — `python3 conformance/check_options_parity.py` passes **before any change**, because "retries" doesn't get a new name. Recommended manifest note below is optional, not required for the gate to pass. |

**Recommended final shape:** the ADR's Decision is right for **Go only**.
`-1 ⇒ no retries` in `golang/client.go` (and its lone Go sibling `classifier.go`),
plus the `CreateInProcessClient` cleanup. **Do not touch JS, Python, Java, C#, or
Elixir** — they need no code change, and adding a `-1` sentinel to them would be
pure noise (or, worse, a second way to spell the same thing next to the one that
already works). Clojure needs a *different* fix entirely (see below) — filing
that as a separate, non-ADR-0029 issue is the honest move.

---

## Gate item 1 — per-port table, with file:line and RUN output

### JS — already correct, no `-1` needed

- Declaration: `js/src/client.ts:36` — `retries?: number` (optional, `undefined` when omitted).
- Defaulting: `js/src/client.ts:674` — `const retries = this.opts.retries ?? 2`.
  `??` only falls through on `null`/`undefined`; `0 ?? 2` is `0`.
- Ran `spikes/retries-zero/js-check.mjs` against a built `js/dist` and a fake
  `fetch` that always 500s:

```
fetch calls with retries:0 -> 1
fetch calls with retries UNSET -> 3
JS VERDICT: retries:0 !== unset. `??` already distinguishes them. No -1 sentinel needed.
```

Explicit `0` = 1 total attempt (no retry). Unset = 3 total attempts (documented
`0 ⇒ 2`). Different code paths, different outcomes, **today, with no ADR change**.

### Python — already correct, no `-1` needed (and not even via `None`)

- Declaration: `python/src/toolnexus/client.py:2126` — `retries: int = 2` (the
  `create_client` keyword default). `Client.__init__` at `:545` has the same
  `retries: int = 2`; storage is `self.retries = retries` (`:567`), used directly
  at `:1043` — `range(self.retries + 1)`.
- The ADR's own gate text suggested `Python None` as the escape hatch — that's
  not even what's happening here. Python's keyword-argument default is a
  **literal value distinct from 0**, not a 0-as-sentinel; there is no "0 means
  unset" comparison anywhere to be wrong. Passing `retries=0` just sets `0`.
- Ran `spikes/retries-zero/python_check.py` in a fresh venv (`pip install -e .`)
  against a fake `HttpTransport` that always raises the retryable 500:

```
calls with retries=0 -> 1
calls with retries UNSET -> 3
PYTHON VERDICT: retries=0 != unset. Keyword default (not a 0-sentinel) already distinguishes them. No -1 sentinel needed.
```

### Java — already correct (boxed `Integer`, exactly the ADR's own escape hatch)

- Declaration: `java/src/main/java/io/github/muthuishere/toolnexus/LlmClient.java:92`
  — `public Integer retries;` (boxed, nullable — the ADR names this case explicitly).
- Defaulting: `LlmClient.java:2166` — `private int retries() { return opts.retries != null ? opts.retries : 2; }`.
  A `null` check, not a truthiness/`>0` check.
- Ran `spikes/retries-zero/JavaCheck.java`, compiled against the real
  `./gradlew jar` runtime classpath, against a real local `com.sun.net.httpserver.HttpServer`
  that always answers 500:

```
calls with retries=0 -> 1
calls with retries UNSET -> 3
JAVA VERDICT: retries=0 != unset. Boxed Integer null-check already distinguishes them. No -1 sentinel needed.
```

### C# — already correct (nullable `int?`, the ADR's other named escape hatch)

- Declaration: `csharp/src/Toolnexus/LlmClient.cs:184` — `public int? Retries { get; set; }`.
- Defaulting: `LlmClient.cs:1554` — `private int Retries() => _opts.Retries ?? 2;`.
- Ran a real `dotnet run` console app (`spikes/retries-zero/csharp-check/`,
  project-references the real `csharp/src/Toolnexus/Toolnexus.csproj`) against a
  fake `HttpMessageHandler` that always answers 500:

```
calls with Retries=0 -> 1
calls with Retries UNSET -> 3
CSHARP VERDICT: Retries=0 != unset. Nullable int `?? 2` already distinguishes them. No -1 sentinel needed.
```

### Elixir — already correct, but for a subtler reason than "keyword lists can see unset"

- Struct default: `elixir/lib/toolnexus/client.ex:278` — `defstruct ... retries: 2, ...`.
- Normalization: `client.ex:356` — `retries: client.retries || 2`.
- The ADR gate text says "if a port can see unset... the sentinel is wrong
  there" and names keyword lists as the mechanism. That's *almost* right but
  not quite *why* it works: the struct itself is built via
  `struct!(__MODULE__, Keyword.take(opts, ...))` (`client.ex:348`), so an
  **omitted** `:retries` key keeps the `defstruct` default of `2`, while a
  **present** `retries: 0` overwrites the struct field to `0`. The
  `client.retries || 2` line then only matters for `nil`/`false` — and in
  Elixir, **`0` is truthy**, so `0 || 2` stays `0`. Two mechanisms stacked
  (struct-default-on-absence + truthy-0 `||`), not "keyword lists remember
  absence" on its own — but the net effect the gate cares about holds.
- Ran `mix run` (real compile, real `Toolnexus.Client.create/1` +
  `Toolnexus.Client.run/3`, injected `:transport` fn that always returns a 500):

```
calls with retries: 0 -> 1
calls with retries UNSET -> 3
ELIXIR VERDICT: retries: 0 != unset. Keyword-list presence + truthy-0 `||` already distinguishes them. No -1 sentinel needed.
```

### Clojure — a DIFFERENT, pre-existing bug the ADR does not name

- `clojure/src/toolnexus/client.cljc` never normalizes `:retries` in
  `create-client` (~line 126-178) at all. The only place a default is ever
  applied is `post-with-retry` at **line 520**: `(let [budget (or (:retries client) 0)] ...)`.
- That means **this port's actual default is already 0, not 2** — its own
  docstring says so outright, at `client.cljc:137`: `":retries transient-failure
  budget (default 0)"`. Every other port (including Go) documents `0 ⇒ 2`.
- Because Clojure's `or` treats only `nil`/`false` as falsy (`0` is truthy,
  same story as Elixir), "explicit 0" and "unset" are **already
  indistinguishable in effect here** — but not because the sentinel problem
  was solved; it's because there's no `0 ⇒ 2` promotion to be wrong about in
  the first place. Ran `clojure -M -e "(load-file ...)"` against a real
  `create-client` + injected `:http-client` fn returning a 500:

```
calls with :retries 0 -> 1
calls with :retries UNSET -> 1
CLOJURE VERDICT: explicit 0 and unset are indistinguishable in EFFECT here only because this port's shipped default is already 0 (not the documented 2) -- a pre-existing parity deviation, unrelated to ADR-0029's `0 => 2` premise. `(or 0 0)` is truthy-safe either way; no -1 sentinel is needed to make retries=0 work.
```

**This is a real, separate parity bug** — Clojure silently ships a materially
different default (0 retries) from the other six ports (2 retries), which
means an unset `:retries` client in Clojure makes 1/3 the LLM calls on a
transient failure that every other port makes. It is NOT what ADR-0029 is
about (ADR-0029 is about `0` colliding with `2`; Clojure's problem is that its
`2` never existed). Recommend filing it separately rather than folding it into
this ADR's scope.

### Go — the one port where the ADR's premise HOLDS

- Declaration: `golang/client.go:52` — `Retries int` (bare primitive; Go's zero
  value for `int` is `0`, indistinguishable from "not set").
- Defaulting: `golang/client.go:521-526`:
  ```go
  func (c *Client) retries() int {
      if c.opts.Retries > 0 {
          return c.opts.Retries
      }
      return 2
  }
  ```
  `> 0`, not a null check — `0` (set or unset, Go cannot tell) always becomes `2`.
- Same shape at `golang/classifier.go:779-785` (`c.post`, System One classifier calls).
- **This is the only port where the gate's premise is real**, and the only
  port that needs `-1`.

---

## Gate item 2 — Go implementation, run for real

Edited (then reverted — see "repo hygiene" below) `golang/client.go` and
`golang/classifier.go` directly, not a copy, because the copy would need its
own `go.mod`/module path and would not exercise the real `llmFetch` attempt
loop or `CreateToolkit`. Full diff preserved at
`spikes/retries-zero/golang-retries-sentinel.patch`.

```go
// golang/client.go:521
func (c *Client) retries() int {
	// -1 is the "explicit zero" sentinel. It must resolve to the ACTUAL
	// retry budget (0), never be handed to the loop bound as -1 --
	// `for attempt := 0; attempt <= -1; attempt++` would never execute even
	// the FIRST attempt, which is a worse bug than the one being fixed.
	if c.opts.Retries == -1 {
		return 0
	}
	if c.opts.Retries > 0 {
		return c.opts.Retries
	}
	return 2
}
```

```go
// golang/classifier.go:781
retries := c.opts.Retries
if retries == -1 {
	retries = 0
} else if retries <= 0 {
	retries = 2
}
```

Test file (kept at `spikes/retries-zero/golang_spike_retries_zero_test.go.txt`,
was compiled/run in place as `golang/zzz_spike_retries_zero_test.go`, then
removed) drives a real `httptest.Server` that always 500s, through
`CreateClient` + `CreateToolkit` + `Client.Run` — the actual attempt loop at
`client.go:818`, not a mock of it.

```
$ go test -run TestSpikeRetries -v .
=== RUN   TestSpikeRetriesExplicitZero
    zzz_spike_retries_zero_test.go:48: calls with Retries=-1 -> 1, elapsed=1.28425ms
--- PASS: TestSpikeRetriesExplicitZero (0.00s)
=== RUN   TestSpikeRetriesUnsetStillDefaultsToTwo
    zzz_spike_retries_zero_test.go:79: calls with Retries UNSET (zero value) -> 3
--- PASS: TestSpikeRetriesUnsetStillDefaultsToTwo (0.00s)
=== RUN   TestSpikeRetriesPositiveStillWorks
    zzz_spike_retries_zero_test.go:105: calls with Retries=3 -> 4
--- PASS: TestSpikeRetriesPositiveStillWorks (0.00s)
=== RUN   TestSpikeRetriesResolverNeverNegative
    zzz_spike_retries_zero_test.go:126: c.retries() with ClientOptions.Retries=-1 -> 0
--- PASS: TestSpikeRetriesResolverNeverNegative (0.00s)
PASS
ok  	github.com/muthuishere/toolnexus/golang	0.802s
```

`TestSpikeRetriesResolverNeverNegative` is the direct falsification attempt for
gate item 2: it asserts `c.retries()` is never negative when
`ClientOptions.Retries == -1`, and that `backoff(0, "")` never returns a
negative `time.Duration`. Both pass. `TestSpikeRetriesExplicitZero` further
proves the *first* attempt is never skipped (1 call, not 0) — the actual
failure mode a naive `retries := c.opts.Retries; if retries == -1 {
retries = -1 /* forgot to normalize */ }` would produce: the loop
`for attempt := 0; attempt <= retries; attempt++` with `retries == -1` would
run **zero times**, silently eating the request the caller asked for.

Also ran, with the real edits still in place:

```
$ go build ./...      # clean
$ go vet ./...         # clean
$ go test -race .      # ok, 9.9s — no regression to the existing suite
```

**Verdict: HOLDS.** The `-1` sentinel is implementable in Go without touching
the backoff/sleep path, without a negative loop bound, and without regressing
any existing test — provided the resolver special-cases `-1 → 0` explicitly
rather than folding it into the `> 0` check (a subtle trap: `<= 0` would also
catch `-1` and wrongly promote it to `2`, the opposite of the intent).

`gofmt -l` flagged `golang/inprocess.go` after the edit (a struct-literal
alignment issue from the shorter `Retries:` key) — cosmetic, would be
auto-fixed by `gofmt -w` in a real PR, noted here only for completeness.

---

## Gate item 3 — `CreateInProcessClient` as a caller of the new spelling

Before (current, `golang/inprocess.go:184-219`): forces
`OnError: func(ErrorInfo) Tier { return TierFail }` when the host doesn't
supply its own `OnError`, specifically because `Retries: 0 ⇒ 2` couldn't be
told to mean zero. This also means today, if a host *does* pass their own
`OnError` that returns `TierRetry` for some transient in-process failure, the
retry BUDGET is still whatever `ClientOptions.Retries` defaults to (`2`,
since `InProcessOptions` has no `Retries` field to override it) —
non-configurable and not obviously 0 or 2 from the call site.

Rewrote it to:

```go
return CreateClient(ClientOptions{
	OnError:       opts.OnError,   // nil unless the host sets one; moot when budget==0
	Retries:       -1,             // SPIKE: the general "no retries" spelling
	BaseURL:       inProcessBaseURL,
	...
})
```

Ran the full existing in-process suite against this change (unmodified test
file — this is not a new test, it's the port's own regression suite):

```
$ go test -run TestInProcess -v .
=== RUN   TestInProcessNoWireConfiguration
--- PASS: TestInProcessNoWireConfiguration (0.00s)
=== RUN   TestInProcessGenerateSeesTheAssembledRequest
--- PASS: TestInProcessGenerateSeesTheAssembledRequest (0.00s)
=== RUN   TestInProcessToolCallsLoopBack
--- PASS: TestInProcessToolCallsLoopBack (0.00s)
=== RUN   TestInProcessArgumentsStructuredOrPreEncoded
--- PASS: TestInProcessArgumentsStructuredOrPreEncoded (0.00s)
=== RUN   TestInProcessUsageIsOptional
--- PASS: TestInProcessUsageIsOptional (0.00s)
=== RUN   TestInProcessStreamingIsRefusedLoudly
--- PASS: TestInProcessStreamingIsRefusedLoudly (0.00s)
=== RUN   TestInProcessDoesNotRetryByDefault
--- PASS: TestInProcessDoesNotRetryByDefault (0.00s)
PASS
ok  	github.com/muthuishere/toolnexus/golang	0.470s
```

`TestInProcessDoesNotRetryByDefault` (the exact test the ADR's context cites —
"measured at 3.7s for the streaming refusal before retries defaulted to 0")
still passes byte-identically. And `go test -race .` on the whole package
still passes after the change.

**Verdict: HOLDS.** `CreateInProcessClient` becomes a genuine caller of
`Retries: -1` and the private `OnError`-forcing workaround is gone. As a
bonus this also *fixes* a latent gap: a host that supplies their own
`OnError` now gets it honored without secretly still being bounded by a
hardcoded `2`-retry budget they can't see or change (though giving them an
actual way to raise that budget — e.g. an `InProcessOptions.Retries int`
field — is a separate, small follow-up not required by this ADR).

---

## Gate item 4 — `conformance/options_manifest.json` / `check_options_parity.py`

```
$ python3 conformance/options_manifest.json  # entry for "retries":
{
  "name": "retries",
  "aliases": ["retries"],
  "tier": "full"
}

$ python3 conformance/check_options_parity.py
Option parity OK: 19 client, 12 toolkit, 17 classifier options across 7 ports (7 at tier full, 0 at tier core: none).
```

The checker (`conformance/check_options_parity.py:60-80`) only tokenizes each
port's options file and checks whether any of an option's registered
**aliases** appears as an identifier — it has no concept of default value,
nullability, or sentinel semantics. Since this change does not rename, add, or
remove an option (`retries` stays `retries` everywhere), **the parity check
requires no manifest edit and already passes**, both before and after the Go
code change verified above.

**Verdict: HOLDS, and it's a no-op.** Nothing to add to the manifest. If
anything, `SPEC.md`'s `Retries` section should gain a note that Go's `-1`
means "no retries" (the manifest isn't the right place for that kind of prose
— it's a name/alias index, not a semantics doc).

---

## Repo hygiene

- `golang/client.go`, `golang/classifier.go`, `golang/inprocess.go`: edited in
  place, proved with `go build`, `go vet`, `go test -race .`, and the
  dedicated spike tests, then `git checkout --` reverted. Diff preserved at
  `spikes/retries-zero/golang-retries-sentinel.patch`; the test file that
  drove it is preserved (non-`.go`, so it can't accidentally compile again)
  at `spikes/retries-zero/golang_spike_retries_zero_test.go.txt`.
- `java/build.gradle`: a temporary `printRuntimeClasspathSpike` task was
  appended to extract the runtime classpath for `javac`/`java`, then
  `git checkout --` reverted immediately after capture.
- `js/package-lock.json` picked up a stray diff from `npm install`
  (dependency-tree bookkeeping, no `package.json` change); reverted with
  `git checkout --`.
- Everything else touched (`js/dist`, `python`'s venv at `/tmp/tnvenv`,
  `java/build`, `java/.gradle`, `csharp` `bin`/`obj`, `elixir/_build`+`deps`,
  `clojure/.cpcache`) is either already gitignored or outside the repo
  entirely.
- `git status` at the end of this spike shows only `spikes/retries-zero/**`
  (plus the four pre-existing untracked ADRs from before this spike started).
