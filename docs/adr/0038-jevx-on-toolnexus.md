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

**D3. One backend enum covers every decision source.** `ClassifierBackend` already has `typesafe`
and `openrouter` (`classifier.go:469`). It grows to:

| enum | wire | default base / model / key env |
|---|---|---|
| `typesafe` | System One `POST {base}/systemone` | `api.typesafe.ai/v1`, `jev-latest`, `TYPESAFE_API_KEY` (existing) |
| `openrouter` | System One `POST {base}/systemone` | `openrouter.ai/api/v1`, `typesafe/jev-1.13`, `OPENROUTER_API_KEY` (existing) |
| `cloudflare` | System One inside Cloudflare's `/ai/run` envelope (`{model, input}` → `{result, success}`, with the nested `state: Completed` unwrap) | account URL from `${CLOUDFLARE_ACCOUNT_ID}`, `typesafe/jev`, `CLOUDFLARE_JEV_API_KEY`. This ports jevx `core/core.go:770–815` |
| `openai` | **decisions from an OpenAI-compatible chat model**: the existing `StyleLLM` structured-output rendering over a §8 client, `Calibrated=false` | `api.openai.com/v1`, the host's model, `OPENAI_API_KEY` |

An explicit `BaseURL`, `Model` or `APIKeyEnv` still wins over the preset. This replaces the
earlier "full-URL endpoint" gap (U1). jevx's custom profiles keep working as `BaseURL` overrides
on `typesafe`.

*Assumption:* "openai decisions" means a chat model answering through structured output, not a
System One endpoint hosted by OpenAI. If OpenAI should instead be a System One-shaped wire, it
becomes a fifth row.

**D4. Image items go into the System One wire (gap I1).** The latest TypeSafe API accepts
images. An item that holds a §8A `ContentPart` is encoded into the System One request for
`typesafe` and `openrouter`, once the gateway is confirmed to pass images through. `openai` sends
it through `Client.RunParts` (`client.go:846`) instead of the text-only `Run`
(`classifier.go:997`). A backend that cannot carry an image fails loudly; it never turns the
image into a description.

The exact image wire shape, and whether `cloudflare` accepts images, is taken from TypeSafe's
current API reference when the change is written. It is not guessed here.

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
  `add-classifier-backends`) covers D3 (`cloudflare` and `openai` enum values), D4 (images) and
  D5 (T1, V1, C1, L1) across all eight ports.
- The spike still uses `ErrImageUnsupported`, because toolnexus cannot send images yet.
- jevx's repo was not modified. The full 20–30-item gate has not run.

## Open questions for the owner

1. **Gate items.** Which real items should the gate use: Stop-hook turns you pick, wfnexus
   `wfx judge` item files, or the myjev question pack?
2. **`openai` meaning.** Is "openai decisions" a chat model answering through structured output
   (assumed above), or a System One endpoint?
3. **Images on OpenRouter and Cloudflare.** Do these gateways pass the image wire through, or is
   it TypeSafe-only at first?
4. **Thresholds.** Should toolnexus ship jevx's inclusive `Thresholds` alongside `Bands`
   (proposed), or should jevx migrate its users to `Bands`?
