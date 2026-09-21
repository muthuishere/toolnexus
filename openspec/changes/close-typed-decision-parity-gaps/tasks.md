## 1. Pin the contract

- [x] 1.1 Spec delta — `decisions` gated at core tier; `calibrated` absent ⇒ true; option-id
      coercion to the plain name.
- [x] 1.2 Spec delta — the client's backoff shape (`base * 2^attempt + jitter[0,100)`, default 500)
      as a cross-port requirement.
- [x] 1.3 `SPEC.md §8B` — a `decisions` row in the `ClassifierOptions` table; the absent-`calibrated`
      rule under "Calibration travels with the decision"; the option-id rule before
      "Degenerate criteria".
- [x] 1.4 `conformance/options_manifest.json` — a core-tier `decisions` row (16 → 17 classifier
      options).

## 2. `decisions` (manifest only — every port already has it)

- [x] 2.1 Verified present: js `decisions`, python `decisions`, go `Decisions`, java `decisions`,
      csharp `Decisions`, elixir `:decisions`, clojure `:decisions`. No port code changes.
- [x] 2.2 `check_options_parity.py` green at 17 classifier options across 7 ports.

## 3. `calibrated` absent ⇒ true — a test per port

- [x] 3.1 `js/` — all four cases (absent / null / true / false) over an injected `fetch`.
- [x] 3.2 `python/` — all four over `RecordingTransport`.
- [x] 3.3 `golang/` — all four over `httptest`, as subtests.
- [x] 3.4 `java/` — all four over the stub `HttpServer`.
- [x] 3.5 `csharp/` — all four as a `[Theory]` over `FixedBodyHandler`.
- [x] 3.6 `elixir/` — already present (`decode_decision`); left as is.
- [x] 3.7 `clojure/` — all four over an injected `:http-client`.

## 4. Option ids travel as their plain name

- [x] 4.1 `clojure/` — `choice-over` coerces keyword/symbol keys via a private `option-id`;
      namespaces kept. Was `str`, which kept the `:` sigil.
- [x] 4.2 `elixir/` — already correct (`to_string/1`); gains the atom-key test that pins it.
- [x] 4.3 `js/` / `python/` / `java/` — typed to string-keyed maps; nothing to coerce.
- [x] 4.4 `golang/` / `csharp/` — no `choiceOver` helper; `map[string]string` only.
- [x] 4.5 Tests: keyword keys lose the sigil; a keyword roster and its string equivalent produce
      identical canonical request bytes; a qualified key keeps its qualifier.

## 5. Clojure's client backoff

- [x] 5.1 `:retry-base-ms` default `250` → `500`.
- [x] 5.2 Backoff gains `+ (rand-int 100)`, matching the other six. Verified `rand-int` runs on
      BOTH hosts (JVM and cljgo) before use.
- [x] 5.3 Tests: an unset base waits ≥480ms; a short base still shortens it; six samples at a
      fixed base are not all identical, and none is shorter than the un-jittered backoff.
- [x] 5.4 Docs: `site/.../clojure/client/resilience.mdx` (four places stating 250) and
      `.../clojure/judge/classifier.mdx` (the note that the client differs).

## 6. The Clojure `Classifier` example was JVM-only

- [x] 6.1 `examples.judge` was listed in `examples/clj/run.sh` but absent from
      `examples/cljgo/run.sh` and from `cljgo/build.cljgo` — no `ex-judge` target, no
      `run_judge.cljc` entry. The newest subsystem was the one the two-host claim never covered.
- [x] 6.2 Added `examples/src/run_judge.cljc` (the two-line interpreted entry — `cljgo run` never
      calls `-main`), the `judge` build target, and the runner entries.
- [x] 6.3 `EXAMPLES.md` and `examples/README.md` corrected: six → seven (the README was already
      stale at "five", omitting multimodal).
- [x] 6.4 Verified: `./examples/clj/run.sh` 7/7, `./examples/cljgo/run.sh` 7/7 AOT AND
      interpreted. CI already runs both scripts, so it now covers this too.

## 7. Verify

- [x] 7.1 `./openspec/changes/add-judge/verify-all.sh` — all ports green.
- [x] 7.2 `openspec validate close-typed-decision-parity-gaps --strict`.
- [x] 7.3 `CHANGELOG.md` under `## Unreleased`, including the two Clojure behaviour changes.
