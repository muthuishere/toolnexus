# add-classifier-backends

## Why

ADR 0038 (draft PR #137, branch `spike/jevx-on-toolnexus`) decides that jevx becomes a stateful
CLI over a stateless toolnexus classifier, and that toolnexus speaks System One to every route
that serves it, with images. The spikes (`spikes/classifier-ux`, `spikes/jevx-core`,
`spikes/classifier-backends/STYLES.md`) proved the shape live on TypeSafe, OpenRouter and
Cloudflare, but every piece of it lived outside the library:

- the backend enum has two values (`typesafe | openrouter`); Cloudflare's `/ai/run` envelope and
  Vercel's gateway are not reachable without a hand-written `custom` evaluator;
- a state cannot carry an image: there is no `assets` field and no danger-zone header;
- there is no fail-closed reply validation (V1), no inclusive jevx-style thresholds (T1), no
  chunking helper (C1) and no question count/hash on the metric event (L1);
- there is no client-level answer cache, so every host writes its own;
- `Question` is closed, so a new System One question type (Jev 1.14 `box`) needs a release;
- the OpenRouter default model is `typesafe/jev-1.13`, one version behind.

Owner, 2026-11-04: *"make all of them complete, we will discuss."* So this change implements
every ADR 0038 decision with the proposed defaults, in all eight ports, and records each default
in `design.md` as **proposed, owner to confirm**.

## What changes

- **Backend enum** `typesafe | openrouter | cloudflare | vercel | custom`, each a row of data
  (base URL, model, key env, wire, image support, image header). `custom` requires `baseUrl` and
  `supports`. `${ENV}` in `baseUrl` expands at call time.
- **Cloudflare wire**: `{model, input:{state, questions[, images]}}` → `{success, result}` with
  the nested `state: "Completed"` unwrap.
- **Images in the state** (systemone style): every image `ContentPart` is lifted into a top-level
  `assets` map under a reference that replaces it in the state; the
  `TypeSafe-Danger-Zone-Images: true` header is sent only by backends whose wire needs it.
  PNG/JPEG only, 16 MiB per image, 280–1568 px per side, 32 MiB per request, all checked
  client-side before any request.
- **`ClassifierUnsupported`** — a typed error raised before the network when the backend's
  (overridable) support table says it cannot take images.
- **`state(v).evaluate(qs…)` / `states(…).evaluate(qs…)`** value builders. The single form
  validates the reply fail-closed (V1); the batch form returns a per-item result-or-error list in
  order and never fails the whole batch for one item.
- **Opt-in injected cache** `{get, set}` plus a shipped `MemoryCache`. Off by default. Caches only
  V1-valid replies, keys on the served model, per request (not per question), with the key bytes
  specified so a cache persisted by one port is readable by another.
- **Pure helpers**: `validateDecision` (V1), `Thresholds` (T1, inclusive, alongside `Bands`),
  `chunkQuestions` (C1).
- **Metric event** `classifier.evaluate` gains `questions`, `questionsHash`, `cached` (L1).
- **Open question union**: a raw question `{type, instructions, criteria?}` goes on the wire as
  written; an answer of an unknown type decodes to a raw answer instead of failing.
- **`style: "llm"` is deprecated** — kept, working and byte-identical, as an alias for at least one
  minor release.
- **OpenRouter default model** moves to `typesafe/jev-1.14`.

## Impact

- `SPEC.md` §8B gains "Backends, images, cache and validation".
- All eight ports: classifier source, tests (hermetic, fake System One endpoint), README/docs.
- Shared fixtures: `examples/judge/backends/`.
- **Behaviour changes** (deliberate, CHANGELOG'd): OpenRouter default model; an unknown answer
  type no longer fails decoding; an image `ContentPart` in a systemone state now becomes an asset;
  the unknown-backend error lists five values; `classifier.evaluate` events carry three more
  fields. Everything else is byte-identical when the new options are absent.
- Not in this change: the Decisions API (second wire), multipart image upload, a native batch
  wire, per-question cache splitting, the jevx cutover itself (ADR 0038 D8).
