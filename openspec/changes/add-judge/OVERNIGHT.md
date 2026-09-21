# add-judge — overnight build report, 2026-09-20/21

**Status: complete and independently verified. Nothing committed.**

## Independent verification

I did not take the build agents' word for it. `openspec/changes/add-judge/verify-all.sh` runs
every port's own suite, the parity gate, and `openspec validate`, and reports per port:

```
golang PASS · js PASS · python PASS · java PASS · csharp PASS · elixir PASS · clojure PASS
parity PASS · openspec PASS        ALL GREEN
```

Two apparent failures on the first pass were **my harness, not the build** — the python suite
needs `pip install -e ".[test]"` and Homebrew python refuses that outside a venv. The script now
makes its own venv. One elixir test failed once (506/507) and then passed 507/507 on four
consecutive runs; my first script truncated the output before the test name, so **I cannot say
which test flaked.**

I then hunted it: **10 further runs with randomised `--seed`, zero failures** (14 runs total since).
I checked the detector would have caught one — ExUnit prints `Result: 506/507 passed` on failure
against `Result: 507 passed` on success, and the pattern matches only the former.

**So: one unexplained failure, not reproducible in 14 runs, and its identity is lost.** That is
where it stands. It is not evidence the suite is healthy, and I am not going to call it fixed
because it stopped happening. If it recurs, capture the full output — the name is the whole
problem.

## What shipped

`Classifier` in all seven ports, plus §8B, the options manifest, seven shared fixtures, two docs
pages, and a CHANGELOG entry. ~1,240 insertions across 28 modified files plus the new port files.

Tasks ticked: 1.1–1.8, 2.1–2.8 (Phase 1), 3.1–3.7 (Go), 4.1–4.8 (six ports), 5.1, 5.3–5.7,
6.1–6.5.

**Not ticked, honestly: 5.2.** `harness/judge-live` exists and every number on it is real, but the
task says *generated from the harness, not hand-written* and there is no runner — the page is a
transcription with its source named per table. Marked PARTIAL, gap named in the CHANGELOG.

## The parity-gate hole is closed

The temporary `"landing": true` flag — which let CI stay green while ports were missing — is gone
from both `options_manifest.json` and `check_options_parity.py`. The build lead proved it rather
than asserting it: moving `golang/classifier.go` aside makes the check exit 1 with 14 MISSING
lines. Restored, exit 0, all seven ports real passes.

## Three things for your judgment — I did not act on these

**1. `elixir/lib/toolnexus/client.ex` shows 363+/70− and it is almost entirely a `mix format`
pass.** I checked: the only genuinely new public function is `parse_retry_after/1`;
`render_histogram`, `llm_call_json` and `run/4` are all still there, just rewrapped. The original
file was **not** format-clean, so the reformat is a fix rather than a whim — but it is a drive-by
that buries a one-line change in a 433-line diff. I left it alone because reverting a formatter is
also churn. Your call whether to keep it or split it into its own commit.

**2. Public API widened in five ports** to let `Classifier` reuse the §8 retry policy instead of
duplicating it: `retryAfterMs`/`isRetryableStatus` exported (js), `parse_retry_after` public
(elixir), `retryAfterDelayMs` split out (java), `RetryAfterMs` private→internal (csharp), three
client fns public (clojure). Reuse over duplication is right, but this is new public surface that
the proposal did not scope, and none of it is in the 14 parity-checked options.

**3. `MetricEvent` gained a `Question` field** (Go, C#, and as new union members in js/java) to
carry the degenerate-criteria warning's question key. Prometheus output is unchanged and a test
pins that — but it is an observable change to a shipped type.

## Deliberately not done

No commit, no branch, no PR, no push, no release. Your rule is commit only when asked, and that
holds double unattended. Everything is in the working tree. Your pre-existing uncommitted work
(`CHANGELOG.md`, `SPEC.md`, the port loop files, `spikes/game/`) was not touched, reverted or
stashed.

## To pick up

- `./openspec/changes/add-judge/verify-all.sh` — re-run the whole gate any time.
- `HANDOFF.md` — build state and the constants, still accurate.
- Decide on the three judgment items above, then commit however you prefer to slice it.
