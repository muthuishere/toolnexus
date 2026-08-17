# Tasks — fix-retry-after-parity

The parsing rule, once, so each port implements the same sentence:

> Honour `Retry-After` iff `trim(value)` matches `^[0-9]+$` **and** is in `0 … 2147483647`. Delay = that many seconds, including 0. Otherwise fall back to exponential backoff.

## Spec

- [x] Spec delta at `specs/resilience-policy/spec.md` (7 scenarios)
- [x] `openspec validate fix-retry-after-parity --strict`
- [x] `SPEC.md` §Resilience — one clarifying sentence on what `Retry-After` accepts

## Per-language parity checklist

Each port needs the parse change **and** a regression test that fails without it.

- [x] `js/` — `client.ts:646` `Number(...)` accepts fractional/negative; `ra ?` treats `0` as absent
- [x] `python/` — `client.py:207` `float(ra)` accepts fractional/negative; `if e.retry_after` treats `0` as absent; unbounded ints sleep forever
- [x] `golang/` — `client.go:670` `strconv.Atoi` accepts `+5`
- [x] `java/` — `LlmClient.java:1917` `\d+` is right, but `Long.parseLong` throws on overflow
- [x] `csharp/` — `LlmClient.cs:1421` `long.TryParse` accepts `-5` → immediate retry
- [x] `elixir/` — `client.ex:925` `Integer.parse` truncates `5.9` → 5; `n > 0` rejects a valid `0`
- [x] `clojure/` — `client.cljc:301` `#"\d+"` is right; confirm overflow via `parse-long` → nil, and add the test

## Verification

- [x] Each port's suite green (`npm test` / `pytest -q` / `go test -race ./...` / `./gradlew test` / `dotnet test` / `mix test` / clojure all-modes)
- [x] `conformance/check_options_parity.py` still passes — 18 client + 12 toolkit options, 7 ports at tier full
- [x] `CHANGELOG.md` entry under `## Unreleased`
