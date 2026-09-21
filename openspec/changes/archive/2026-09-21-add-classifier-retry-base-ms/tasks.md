## 1. Pin the contract

- [x] 1.1 Spec delta — the requirement, its default, its units, and that `Retry-After` still wins.
- [x] 1.2 `SPEC.md §8B` — add `retryBaseMs` to the `ClassifierOptions` table, marked against its
      §8 counterpart. (§8B's canonical-request rules are untouched.)
- [x] 1.3 `conformance/options_manifest.json` — a `retryBaseMs` row under `classifierOptions`
      (15 → 16 options), with the per-port aliases.

## 2. The seven ports

- [x] 2.1 `js/` — already present (`ClassifierOptions.retryBaseMs`, default 500). Reference semantics.
- [x] 2.2 `python/` — `retry_base_ms: int = 500` on `Classifier` and the `create_classifier` helper.
- [x] 2.3 `golang/` — `ClassifierOptions.RetryBaseMs int`, `0 ⇒ 500`.
- [x] 2.4 `java/` — `Classifier.Options.retryBaseMs` (`Integer` + builder setter), `null/≤0 ⇒ 500`.
- [x] 2.5 `csharp/` — `ClassifierOptions.RetryBaseMs` (`int?` + `WithRetryBaseMs`), `null/≤0 ⇒ 500`.
- [x] 2.6 `elixir/` — `:retry_base_ms` on the classifier struct, default 500.
- [x] 2.7 `clojure/` — `:retry-base-ms` on the classifier map, default 500.

## 3. Prove it

- [x] 3.1 Each port: a test that a short base shortens the classifier's retry path.
- [x] 3.2 Each port: a test that an unset base still yields 500 ms (default behaviour unchanged).
- [x] 3.3 `conformance/check_options_parity.py` green at 16 classifier options across 7 ports.
- [x] 3.4 `./openspec/changes/add-judge/verify-all.sh` — all ports green.

## 4. Document it

- [x] 4.1 `site/src/content/docs/api/*/client/resilience.mdx` — the classifier's base alongside the client's.
- [x] 4.2 `CHANGELOG.md` under `## Unreleased`, written from the user's side.
