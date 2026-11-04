# ADR 0038 — jevx on toolnexus: toolnexus is the classifier core, jevx is a thin CLI

- **Status:** **Proposed.** This ADR changes no library code. The evidence is a spike
  (`spikes/jevx-core/`) and an 11-item live gate run.
- **Date:** 2026-11-04
- **Driver:** the owner, 2026-11-04: *"jevx is actually a cli, so it should use toolnexus
  classifier and all stuff … find out [how to make] it similar to jevx with toolnexus being core."*
  The owner also wants several classifiers with named profiles, and image items, in toolnexus.
  Jev is our own product, so a Jev (`systemone`) default is allowed. The CEO set a gate: before
  any cutover, run the current judge and the new toolnexus path on the same 20–30 real items, and
  cut over only where the decisions match.
- **Related:** ADR 0020/0021 (Classifier seam, encoding carries the judgment), ADR 0035 (judge
  adapters + batteries), SPEC §8B (Classifier), §8A (content parts), ADR 0027 (backend pairing).

## Context

Three codebases call System One today. Each one makes the same decisions in its own way.

| implementation | where | builds the request | thresholds | on toolnexus? |
|---|---|---|---|---|
| **jevx** | `~/muthu/gitworkspace/jevcli` (Go, v0.12.13) | its own: `core/core.go:750` `AskRaw` | inclusive 0.8 / 0.2, min-confidence 0.6 (`cmd/jevx/ask.go:109`) | **no** |
| **wfnexus** `wfx judge` / `POST /judge` / `decide:` step | `wfnexus-main/apps/api/internal/judge/judge.go`, `engine/decide.go:116` | toolnexus `CreateClassifier` | its own `Bands` copy, 0.30 / 0.70 (`judge.go:46–115`) | **yes** (v0.19.0, `apps/api/go.mod:13`) |
| **toolnexus** judge layer | `golang/judge.go`, `judge_batteries.go` | `classifier.go:620` | `Bands` exclusive 0.30 / 0.70 (`judge.go:89`) | it is the library |

There are also smaller Python callers that post to `/systemone` directly, in
`model-training-workspace/myjev/scripts/` and `jev-converters/scripts/`. They are training and
labelling tools, not product paths, so this ADR does not cover them.

`spikes/jevx-core/MAP.md` sorts each jevx function into "core" (a call into toolnexus) or "CLI"
(stays in jevx), with file:line on both sides. In short, about 60% of jevx's `core/core.go` is
decision plumbing that toolnexus already has or should have. The rest (hooks/plugins, skill
install, `## Jev` context reading, transcript turns, memory, exit codes, config file) belongs to
the CLI.

## Decisions

**D1. toolnexus owns the decision core.** That means request building, the wire, retries
(including Retry-After, which jevx ignores today), key handling, batching, metric events, and the
answer types. jevx stops building System One requests itself. `AskRaw`, `post` and the retry loop
are replaced by `Classifier.Evaluate` / `EvaluateBatch`.

**D2. Named profiles and a registry of classifiers go into toolnexus (gap R1).** A profile is a
`ClassifierOptions` plus a name, optional thresholds, and an optional `Fallback` profile. A
registry resolves a name, or the default, to one built `*Classifier`. jevx and wfnexus each
rebuild this map today. The spike's `Registry` (`spikes/jevx-core/core.go`) is the shape to
standardise.

**D3. The default profile is Jev with an llm fallback.** `jev` = `systemone`, base
`https://api.typesafe.ai/v1`, `jev-latest`, `TYPESAFE_API_KEY`. When that key is unset **and** the
host supplied a §8 client, the registry falls back to an `llm` profile, which reports
`Calibrated=false`. With no client, the call fails with need-key and is never silently skipped.
A threshold tuned on one backend does not carry over to the other (§8B). So a fallback answer is
labelled with the profile that answered it.

**D4. Keys come from the environment only.** Profiles store the env var's name (`APIKeyEnv`) and
`${ENV}` header templates. They never store a value. This matches jevx (`core.go:76`) and
toolnexus (`classifier.go:968`). Error text never contains the key; a spike test checks this.

**D5. Image items reuse §8A content parts (gap I1).** An item is text or a `ContentPart`. Until
the wire can carry them, image items fail loudly (`ErrImageUnsupported`) on every style. They are
never turned into a text description. Two steps:
1. `StyleLLM` sends the state through `Client.RunParts` (`client.go:846`) instead of the text-only
   `Run` (`classifier.go:997`). This is possible today.
2. `systemone` needs TypeSafe to confirm that the wire accepts image parts. That is an open
   question.

**D6. Verdict thresholds become per profile and per question, with jevx's inclusive rule
available (gap T1).** jevx says yes at `P >= yes`. toolnexus `Bands` says yes at `P > High`, and
also demotes a near-uniform choice. With the same numbers, the two disagree at the boundary
(spike test `TestBoundaryDiffersFromToolnexusBands`). jevx users' saved thresholds must keep their
meaning, so toolnexus gets `Thresholds{Yes, No, MinConfidence}`, inclusive. Each profile and each
question can carry its own. `Bands` stays as it is.

**D7. Fail-closed reply validation moves into toolnexus (gap V1).** A reply is rejected if:
- an answer is missing, or there is an extra one;
- a probability is outside [0,1];
- a distribution does not sum to 1 ± 0.02;
- a choice is not one of the offered keys.

jevx does this (`core.go:865`) and toolnexus does not. This is a bug class for every port, not
just jevx.

**D8. Opt-in cache and pre-send state transform (gaps K1, P1), as library hooks.**
- `Cache`: the key is a hash of (url, model, state, question wire), with a TTL, off by default.
  Headers are not part of the key, so rotating a key does not empty the cache.
- `StateTransform func(state) (state, n)`: toolnexus provides the hook. jevx keeps its hosted-only
  scrub rules (`core/redact.go`), because what counts as a secret is the host's policy.
- The content-free ledger stays a jevx sink on `OnMetric`. toolnexus adds a question hash and
  count to the `classifier.evaluate` event (gap L1).

**D9. jevx stays a thin CLI.** These stay in jevx: commands and compact question syntax, exit
codes 0/1/3/4, skill install, hooks and plugins (they *call* the core), the `## Jev` context from
AGENTS.md / CLAUDE.md, transcript turns, memory, the question pack *wording* (a Jev model is
trained on that wording), and the config file. These go to toolnexus: the full-URL / Cloudflare
envelope style (gap U1) and question chunking (gap C1). Until U1 lands, jevx can keep the
Cloudflare profile as a `StyleCustom` profile.

**D10. wfnexus moves the same way.** It already calls toolnexus. It drops its copied `Bands` /
`Read` (`internal/judge/judge.go:46–115`) for the registry plus `Thresholds`. `wfx judge`'s
`--backend/--url/--classifier` flags become profile names, and its `POST /judge` resolves a
profile from the same registry. That is one decision path for all three.

**D11. Cutover gate (CEO).** jevx switches a verb to the toolnexus path only after `spikes/jevx-core/cmd/gate`
shows that `jevx` and the toolnexus path give the **same verdict** on **20–30 real items** for that verb,
with the same profile, `--fresh` and `--no-context`. Any mismatch blocks that verb and is investigated as a
threshold-semantics (T1) or validation (V1) difference first.

## Evidence

- Spike `spikes/jevx-core`: `go vet` passes and `go test ./...` passes, hermetic, against a fake
  System One endpoint (thresholds, profile selection, 429/503 retries, fail-closed checks, no key
  in errors, Jev→llm fallback, loud image failure, gate parsing).
- Live gate, 2026-11-04, hosted Jev, key brokered by `sec run`: **11/11 verdicts matched** on the
  jevx README quickstart items (`items/readme-quickstart.jsonl`, source jevcli
  `README.md:81-108`). That covers 9 `is`, 1 `pick` and 1 `rate`. It is **not** a cutover
  decision: it is below the 20–30-item gate, and the items are documentation examples, not work
  traffic.

## Not done

- No toolnexus library change and no OpenSpec change yet. Gaps R1, T1, V1, K1, P1, I1, U1, C1
  and L1 need one change across all eight ports (`add-classifier-profiles`, to be proposed after
  the owner answers below).
- jevx's repo was not modified.
- The full 20–30-item gate has not run.

## Open questions for the owner

1. **Item source for the gate.** jevx's ledger has no content by design. Which real items should
   we use: Stop-hook turns you pick, wfnexus `wfx judge` item files, or the myjev question pack?
2. **Threshold semantics.** Should toolnexus adopt jevx's inclusive `Thresholds` alongside
   `Bands` (proposed), or should jevx migrate users to `Bands`? Migrating changes saved
   thresholds' meaning at the boundary.
3. **Fallback.** Should the Jev→llm fallback be automatic when `TYPESAFE_API_KEY` is unset, or
   opt-in per profile? Automatic fallback changes calibration silently, apart from the profile
   label.
4. **Images on `systemone`.** Will the TypeSafe wire accept image parts? If not, image items are
   llm-only.
5. **Cache in the library.** Is a cache a toolnexus option (K1), or does it stay jevx-only
   (persistence is host policy)?
6. **Profile file.** Should toolnexus read a profile file (shared by jevx and wfnexus), or take
   profiles only in code and leave the file to each host?
