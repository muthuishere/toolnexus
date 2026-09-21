# Tasks — fix-retries-zero

## Spec

- [x] Spec delta at `specs/resilience-policy/spec.md`
- [x] `openspec validate fix-retries-zero --strict`
- [x] `SPEC.md` — the retry contract states that zero is expressible (added a paragraph under
      "Resilience (retries + timeout/cancel)" naming each port's spelling)

## Per-language parity checklist

Each port is not done without BOTH tests: zero ⇒ exactly one backend invocation, and
default ⇒ three attempts. Six ports need only the tests; two need a code fix as well.

- [x] `golang/` — code fix (`-1` sentinel, `client.go:521-526`, `classifier.go:779-785`) + tests + `CreateInProcessClient` drops its private `OnError` workaround
- [x] `clojure/` — code fix (default 0 ⇒ 2, `client.cljc:137,520`) + tests
- [x] `js/` — tests only
- [x] `python/` — tests only
- [x] `java/` — tests only
- [x] `csharp/` — tests only
- [x] `elixir/` — tests only

## Conformance

- [x] `conformance/options_manifest.json` — checked; no change needed. The manifest schema
      only carries `name`/`aliases`/`tier`, no field for per-port semantic spelling (`-1` vs
      `0`), and `retries` is not renamed in any port, so its entry is unchanged. The spelling
      is documented in prose in `SPEC.md` instead, per the spike's recommendation.
- [x] `check_options_parity.py` passes

## Changelog

- [x] `CHANGELOG.md` `## Unreleased` — what a user gets, naming the Clojure default fix as a
      behaviour change in that port
