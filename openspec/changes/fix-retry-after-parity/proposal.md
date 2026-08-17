# Fix `Retry-After` parsing parity across the seven ports

## Why

`SPEC.md:1076` says the LLM request retries "with exponential backoff + jitter, honoring
`Retry-After`". Seven ports implement that sentence. **No two of them agree on what a
`Retry-After` value means**, and no test in any port catches it — the divergence was found
while spiking ADR 0019, not by the suite.

Given the same response header, the seven ports wait for different lengths of time:

| header | Go | Python | JS | Java | C# | Elixir | Clojure |
|---|---|---|---|---|---|---|---|
| `2` | 2s | 2s | 2s | 2s | 2s | 2s | 2s |
| `0.5` | backoff | **0.5s** | **0.5s** | backoff | backoff | backoff | backoff |
| `5.9` | backoff | **5.9s** | **5.9s** | backoff | backoff | **5s** *(truncated)* | backoff |
| `0` | **0s** | backoff | backoff | **0s** | **0s** | backoff | **0s** |
| `-5` | backoff | **negative sleep** | **immediate retry** | backoff | **immediate retry** | backoff | backoff |
| `99999999999999999999` | backoff | **3 000-year sleep** | huge | **throws** | backoff | huge | backoff |

Three separate defects are visible in that table:

1. **A three-way split on fractional values.** Python and JS honour them, Elixir silently
   truncates them (`Integer.parse("5.9")` → `{5, ".9"}`), and four ports reject them.
2. **A negative `Retry-After` produces an immediate retry in JS and C#, and a negative sleep in
   Python** — a client hot-looping against a server that just asked it to back off.
3. **An out-of-range value throws in Java** (`Long.parseLong` → `NumberFormatException`, inside a
   `map`, so it propagates out of the retry path and kills the run) and sleeps effectively forever
   in Python, JS and Elixir.

This is precisely the silent cross-port drift this repository exists to prevent, and it is in the
one code path a user cannot observe directly.

## What changes

**One parsing rule, in all seven ports.** `Retry-After` is honoured only when the trimmed value is
a run of ASCII digits in the range `0 … 2147483647` (a signed 32-bit second count, ~68 years — the
widest range all seven languages represent exactly):

- The delay is exactly `n` seconds, **including `n = 0`** (the server is saying "retry now").
- **Everything else falls back to exponential backoff with jitter** — fractional values, signed
  values (`+5`, `-5`), the HTTP-date form, values above that ceiling, empty and garbage.

This follows RFC 9110 §10.2.3, which defines `delay-seconds` as `1*DIGIT` — an unsigned integer.
Four ports are already right; Python, JS and Elixir move to match them, and Go and Java each get
one edge case corrected (`+5` and the overflow throw respectively).

The HTTP-date form stays deliberately unsupported, uniformly. It needs portable date parsing in
seven languages to save one round of backoff, and a server that sends it gets our backoff rather
than a wrong answer — the behaviour Clojure already documents at `client.cljc:290-293`.

## Impact

- **Affected spec:** `resilience-policy` (owns "honoring `Retry-After`"), and one clarifying
  sentence in `SPEC.md` §Resilience.
- **Affected code:** the retry path in all seven ports. No public API changes, no new options.
- **User-visible:** a server sending a fractional `Retry-After` now gets backoff instead of a
  sub-second wait in Python and JS, and a truncated wait in Elixir. A server sending a negative or
  absurd value can no longer cause a hot loop, a negative sleep, or a crash.
- **Not covered:** capping an honoured delay against the run deadline. Java and C# already clamp
  via `sleep(ms, deadline)`; the other five do not. That is a policy question, not a parsing one,
  and is left as a follow-up rather than smuggled in here.
