# ADR 0038 — jevx on toolnexus: a stateless classifier library under a stateful CLI

- **Status:** **Proposed.** This ADR changes no library code. The evidence is a spike
  (`spikes/jevx-core/`) and an 11-item live gate run.
- **Date:** 2026-11-04
- **Driver:** the owner, 2026-11-04:
  - *"jevx is actually a cli, so it should use toolnexus classifier and all stuff … with toolnexus
    being core."*
  - Then: *"jevx is frontend, they will use ours, and it is jevx's job to maintain state and all
    stuff. Profile belongs to jevx; ours is a stateless library."*
  - And: the latest TypeSafe API accepts images. toolnexus should support Cloudflare, OpenAI
    decisions and OpenRouter System One, all selected by one enum.

  The CEO set a gate: before any cutover, run the current judge and the toolnexus path on the
  same 20–30 real items, and cut over only where the decisions match.
- **Related:** ADR 0020/0021 (Classifier seam), ADR 0027 (backend pairing), ADR 0035 (judge
  adapters), SPEC §8B (Classifier), §8A (content parts).

## Context

Three codebases call System One today:

| implementation | where | builds the request | thresholds | on toolnexus? |
|---|---|---|---|---|
| **jevx** | `~/muthu/gitworkspace/jevcli` (Go, v0.12.13) | its own: `core/core.go:750` `AskRaw` | inclusive 0.8 / 0.2, min-confidence 0.6 (`cmd/jevx/ask.go:109`) | **no** |
| **wfnexus** `wfx judge` / `POST /judge` / `decide:` | `wfnexus-main/apps/api/internal/judge/judge.go`, `engine/decide.go:116` | toolnexus `CreateClassifier` | its own `Bands` copy, 0.30 / 0.70 (`judge.go:46–115`) | **yes** (v0.19.0) |
| **toolnexus** judge layer | `golang/judge.go`, `judge_batteries.go` | `classifier.go:620` | `Bands`, exclusive (`judge.go:89`) | it is the library |

`spikes/jevx-core/MAP.md` sorts each jevx function into toolnexus, jevx, or a gap, with file:line
on both sides.

## Decisions

**D1. The split is stateless library vs stateful host.** toolnexus does one thing: given
`ClassifierOptions`, a state and questions, it returns a `Decision`. That covers:
- building the request and encoding it for each backend;
- retries, including `Retry-After`, which jevx ignores today;
- reading the key from the env var named in the options, at call time;
- batches and metric events.

toolnexus keeps **no** state: no profiles, no default profile, no fallback, no cache, no config
file, no log file.

**D2. jevx owns profiles and every other piece of state.** That includes:
- named profiles, the default profile and the Jev→llm fallback;
- the config file and saved questions, with their thresholds;
- the answer cache and the content-free log file;
- scrubbing the state before a hosted call (`core/redact.go`);
- the `## Jev` context from AGENTS.md / CLAUDE.md, transcript turns, memory;
- hooks and plugins, skill install, the commands, and exit codes 0/1/3/4.

A jevx profile *resolves to* a `ClassifierOptions`, and that is the only thing that reaches
toolnexus. The spike's `Registry` (`spikes/jevx-core/core.go`) is jevx-side code.

**D3. One classifier, one style, one enum.** The owner, 2026-11-04: *"all should be one single
style classifier and also should support images."* A host makes one `Classifier`, and the
`ClassifierBackend` enum is the only thing that picks where it goes. Every backend:
- takes the same System One questions (`noul`, `choice`, `score`);
- returns the same `Decision`;
- accepts the same text **or image** items.

There is no per-backend style for a host to choose.

| enum | how the classifier reaches it | default base / model / key env |
|---|---|---|
| `typesafe` | System One `POST {base}/systemone` | `api.typesafe.ai/v1`, `jev-latest`, `TYPESAFE_API_KEY` (exists) |
| `openrouter` | System One `POST {base}/systemone` | `openrouter.ai/api/v1`, `typesafe/jev-1.13`, `OPENROUTER_API_KEY` (exists) |
| `cloudflare` | the System One body inside Cloudflare's `/ai/run` wrapper (`{model, input}` → `{result, success}`, plus the nested `state: Completed` unwrap). This ports jevx `core/core.go:770–815` | account URL from `${CLOUDFLARE_ACCOUNT_ID}`, `typesafe/jev`, `CLOUDFLARE_JEV_API_KEY` |
| `openai` | the same System One questions, answered by an OpenAI-compatible chat model through structured output. The classifier does the translation internally, so the host sees the same `Decision`, with `Calibrated=false` | `api.openai.com/v1`, the host's model, `OPENAI_API_KEY` |

An explicit `BaseURL`, `Model` or `APIKeyEnv` still overrides the preset, and that covers
self-hosted endpoints. The current `Style` option (`systemone | llm | custom | static`) is folded
in:
- `llm` becomes the `openai` backend;
- `static` and `custom` stay as test and host-supplied seams, not product backends.

Whether to deprecate `Style` or keep it as an alias is decided in the OpenSpec change.

**D4. Images are part of the contract on every backend (gap I1).** The latest TypeSafe API
accepts images. An item is text or a §8A `ContentPart`:
- `typesafe`, `openrouter`, `cloudflare`: the image goes on the System One wire. The exact
  encoding is taken from TypeSafe's current API reference when the change is written; it is not
  guessed here.
- `openai`: the image goes as a native image part (the client's `RunParts` path), not through the
  text-only `Run`.

If a gateway turns out to drop images, the call fails loudly. The image is never turned into a
text description.

### Cross-port check (2026-11-04, this branch)

All eight ports are aligned today, and all of them have the same gaps:

| port | backend enum today | styles | images in the classifier | llm path |
|---|---|---|---|---|
| js | `typesafe \| openrouter` (`classifier.ts:34`) | 4 | none | text `client.run` (`:767`) |
| python | same (`classifier.py:126`) | 4 (`:120`) | none | text `client.run` (`:879`) |
| golang | same (`classifier.go:469`) | 4 | none | text `Client.Run` (`:1019`) |
| java | same (`Classifier.java:78`) | 4 | none | text `client.run` (`:949`) |
| csharp | same (`Classifier.cs:582`) | 4 | none | text |
| elixir | same (`classifier.ex:122`) | 4 | none | text `Client.run` (`:979`) |
| clojure | same (`classifier.cljc:60`) | 4 | none | text |
| rust | same (`classifier.rs:90`) | 4 | none | text |

So the change is the same everywhere:
- add `cloudflare` and `openai` to the enum;
- fold `Style` into the backend;
- add image items on all four backends;
- add the D5 helpers.

Every port already has `ContentPart` (§8A) for its chat client, so the image type itself exists
in all eight.

**D5. Stateless verdict helpers come into toolnexus.** These are pure functions, with no state:
- **T1** `Thresholds{Yes, No, MinConfidence}`, inclusive, applied to a `Decision`. jevx's saved
  thresholds must keep their meaning. `Bands` is exclusive and disagrees at the boundary (spike
  test `TestBoundaryDiffersFromToolnexusBands`). `Bands` stays as it is.
- **V1** fail-closed reply validation:
  - an answer is missing, or there is an extra one;
  - a probability is outside [0,1];
  - a distribution does not sum to 1 ± 0.02;
  - a choice is not one of the offered keys.

  jevx does this (`core.go:865`); toolnexus does not, in any port.
- **C1** splitting large question sets into chunks. **L1** a question hash and count in the
  `classifier.evaluate` metric event.

**D6. Keys come from the environment only.** The options carry an env var's name, never a value.
Error text never contains the key (spike test).

**D7. wfnexus moves the same way.** It already calls toolnexus. It drops its copied `Bands` /
`Read` for the T1/V1 helpers. Its backend flags map onto the D3 enum, and it keeps its own
profiles, because those are its state.

**D8. Cutover gate (CEO).** jevx switches a verb to the toolnexus path only after
`spikes/jevx-core/cmd/gate` shows the **same verdict** on **20–30 real items** for that verb,
with the same profile, `--fresh` and `--no-context`. Any mismatch blocks that verb.

## Evidence

- Spike `spikes/jevx-core`: `go vet` passes and `go test ./...` passes, hermetic, against a fake
  System One endpoint (thresholds, profile selection, 429/503 retries, fail-closed checks, no key
  in errors, Jev→llm fallback, loud image failure, gate parsing).
- Live gate, 2026-11-04, hosted Jev, key brokered by `sec run`: **11/11 verdicts matched** on
  jevx's README quickstart items (9 `is`, 1 `pick`, 1 `rate`). It is **not** a cutover decision:
  it is below 20–30 items, and the items are documentation examples.

## Not done

- No library change and no OpenSpec change yet. The follow-up change (e.g.
  `add-classifier-backends`) covers D3 (one style: `cloudflare` and `openai` enum values, `Style` folded in), D4 (images on all four) and
  D5 (T1, V1, C1, L1) across all eight ports.
- The spike still uses `ErrImageUnsupported`, because toolnexus cannot send images yet.
- jevx's repo was not modified. The full 20–30-item gate has not run.

## Open questions for the owner

1. **Gate items.** Which real items should the gate use: Stop-hook turns you pick, wfnexus
   `wfx judge` item files, or the myjev question pack?
2. **`Style` option.** Should it be removed in the next breaking release, or kept as a
   deprecated alias for the enum?
3. **Images on OpenRouter and Cloudflare.** Do these gateways pass the image wire through, or is
   it TypeSafe-only at first?
4. **Thresholds.** Should toolnexus ship jevx's inclusive `Thresholds` alongside `Bands`
   (proposed), or should jevx migrate its users to `Bands`?
