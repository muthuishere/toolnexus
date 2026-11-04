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

**D3. One classifier, one style (System One), one backend enum.** Owner, 2026-11-04: *"all
should be one single style classifier and also should support images"*; *"typesafe, openrouter,
cloudflare and whatever available."*

A host makes one `Classifier`. It always sends the System One body
(`{model, state, questions}` → `{model, answers, usage}`). The `ClassifierBackend` enum is the
only choice the host makes. Research (2026-11-04, sources below) found these routes that serve
the System One wire:

| enum | endpoint | default model | key env | wire differences | images |
|---|---|---|---|---|---|
| `typesafe` (exists) | `https://api.typesafe.ai/v1/systemone` | `jev-latest` (pin `jev-1.14.0` for images and box) | `TYPESAFE_API_KEY` | none | **yes**: Jev 1.13 and 1.14, behind a required header (D4) |
| `openrouter` (exists) | `https://openrouter.ai/api/v1/systemone` | `typesafe/jev-1.14` (`~typesafe/jev-latest` alias; 1.13 still served) | `OPENROUTER_API_KEY` | the reply adds `id`, `provider`, `usage.cost` | Jev listed as text+image. The image wire through `/systemone` is unverified |
| `cloudflare` (new) | `https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run/<model>` | `typesafe/jev`; also `@cf/cloudflare/clef`, `@cf/cloudflare/clef-flash` | `CLOUDFLARE_JEV_API_KEY` (jevx's name) | request is `{model, input:{state,questions}}`; reply is `{success, result}`, with a nested `state:"Completed"` unwrap (jevx `core/core.go:770–815`). Clef returns 422 on any unknown field | Clef: **yes**, through its own `images` field (base64). Jev on Cloudflare: not documented |
| `vercel` (new) | `https://ai-gateway.vercel.sh/typesafe/v1/systemone` | `typesafe-ai/jev` | `AI_GATEWAY_API_KEY` | the reply adds `provider_metadata.gateway`. Optional gateway fallbacks go in `providerOptions.gateway` | not documented |

**Left out on purpose:**
- Generic gateways that put their own host in front of these same routes (TrueFoundry, Zuplo,
  LiteLLM pass-through). They are reachable with the `typesafe` preset and a `BaseURL` override,
  and they add no preset value.
- The **Decisions API** (`openrouter.ai/api/alpha/decisions`, Vercel's Decision API). It serves 22
  decision models, among them `openai/gpt-6-luna-decisions`, Perplexity, Clef and Liquid d1. Its
  body is close to System One but is a different, **alpha** wire, and the docs I found don't
  confirm it is byte-identical. It would be a second wire, so it is an open question (Q2), not a
  fifth preset. Inside one wire, the *model* is free: any decision model that a backend serves
  under `/systemone` is just a `Model` value.

Within a backend, an explicit `BaseURL`, `Model` or `APIKeyEnv` overrides the preset; that covers
self-hosted endpoints such as openjevx. The current `Style` option
(`systemone | llm | custom | static`) folds away:
- `systemone` is the only product wire;
- `static` and `custom` stay as test and host-supplied seams;
- `llm` (a chat model imitating System One, uncalibrated) is deprecated. That is the owner's
  "single style". Whether it is removed or kept as an alias is decided in the OpenSpec change.

**D4. Images are part of the System One contract (gap I1).** An item is text or a §8A
`ContentPart`. The TypeSafe images reference (docs.typesafe.ai/images) defines the wire:
- a top-level `assets` map, with one entry per image, keyed by a plain-text reference:
  `"<ref>": {"type":"image","format":"png|jpg|jpeg","data_base64":"<no data: prefix>"}`;
- each reference must appear in `state`, `instructions` or `criteria`. A string that is exactly
  the reference *becomes* that image;
- the header `TypeSafe-Danger-Zone-Images: true` is **required** on any request with an image or a
  box question; without it the request is rejected;
- multipart (JSON in the part named `request`, each file in its own part referenced by `part`) is
  recommended. Base64 JSON is about a third larger. toolnexus starts with base64 JSON, which is one
  body shape for every port, and leaves multipart as a follow-up;
- limits: PNG or JPEG still images only, 16 MiB per image, 280–1568 px per side, 32 MiB per
  request. Images outside these limits are **rejected, not resized**, so toolnexus checks them
  client-side before sending, as it already does for choice and score limits.

Per backend:
- `typesafe` uses the wire above.
- `cloudflare` maps the images to Clef's `images` field when the model is Clef.
- `openrouter` and `vercel` send the same `assets` body. Whether a gateway actually forwards it
  (and the header) is checked live before the change ships.

A backend that rejects the image fails loudly. The image is never turned into a text
description. TypeSafe calls images and box questions "danger zone" (no production guarantees), so
an image call must be explicit: the host passes an image item. The library never sends an image
on its own.

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
- add `cloudflare` and `vercel` to the enum;
- deprecate `Style` `llm`;
- add image items (`assets` + the danger-zone header + client-side limits);
- add the D5 helpers.

The `openrouter` model default should also move from `typesafe/jev-1.13` to the 1.14 or latest
alias.

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
  `add-classifier-backends`) covers D3 (one System One style; `cloudflare` and `vercel` enum values), D4 (images via `assets`) and
  D5 (T1, V1, C1, L1) across all eight ports.
- The spike still uses `ErrImageUnsupported`, because toolnexus cannot send images yet.
- jevx's repo was not modified. The full 20–30-item gate has not run.

## Open questions for the owner

1. **Gate items.** Which real items should the gate use: Stop-hook turns you pick, wfnexus
   `wfx judge` item files, or the myjev question pack?
2. **The Decisions API.** OpenRouter's and Vercel's alpha Decisions API serves 22 decision models,
   including `openai/gpt-6-luna-decisions`. Should toolnexus support it as a second wire, or stay
   System One only (proposed)?
3. **Images on the gateways.** Do OpenRouter, Vercel and Cloudflare-Jev pass the image wire through, or is
   it TypeSafe-only at first?
4. **Thresholds.** Should toolnexus ship jevx's inclusive `Thresholds` alongside `Bands`
   (proposed), or should jevx migrate its users to `Bands`?
5. **`llm` style.** Should it be removed in the next breaking release, or kept as a deprecated
   alias?

## Sources (researched 2026-11-04)

- TypeSafe images reference: https://docs.typesafe.ai/images
- OpenRouter, Jev on OpenRouter: https://openrouter.ai/docs/guides/community/jev
- OpenRouter, TypeSafe SDK (System One at `/api/v1/systemone`): https://openrouter.ai/docs/guides/community/typesafe-sdk
- Vercel AI Gateway, TypeSafe API: https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe
- TrueFoundry gateway (Cloudflare `/ai/run` envelope, Clef `images` field): https://www.truefoundry.com/docs/ai-gateway/typesafe
- Jev 1.14 release coverage (images, box, danger-zone header): https://pasqualepillitteri.it/en/news/26048/jev-1-14-image-input-box-typesafe-warns
- Clef overview (System One-compatible, `images` field): https://dev.classmethod.jp/en/articles/cloudflare-clef-overview/
