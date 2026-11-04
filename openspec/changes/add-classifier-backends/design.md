# Design — add-classifier-backends

Source: ADR 0038 (PR #137) D3, D4, D5, D9 and the owner review on #137. Every decision marked
**PD** is a *proposed default, owner to confirm*; the code implements it so it can be reviewed
running, not on paper.

This file is the normative detail every port implements. `SPEC.md §8B` carries the same contract
in shorter form; fixtures in `examples/judge/backends/` pin the bytes.

## 1. Backends are data (PD-1, PD-2, PD-4)

| backend | `baseUrl` | `model` | `apiKeyEnv` | wire | `supports.images` | image header |
|---|---|---|---|---|---|---|
| `typesafe` (default) | `https://api.typesafe.ai/v1` | `jev-latest` | `TYPESAFE_API_KEY` | systemone | true | yes |
| `openrouter` | `https://openrouter.ai/api/v1` | `typesafe/jev-1.14` | `OPENROUTER_API_KEY` | systemone | true | yes |
| `cloudflare` | `https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run` | `typesafe/jev` | `CLOUDFLARE_JEV_API_KEY` | cloudflare | true iff the model contains `clef` | no |
| `vercel` | `https://ai-gateway.vercel.sh/typesafe/v1` | `typesafe-ai/jev` | `AI_GATEWAY_API_KEY` | systemone | false | yes |
| `custom` | **required** | `jev-latest` | `TYPESAFE_API_KEY` | systemone | **required** (`supports`) | yes |

- **PD-1 System One only.** The Decisions API (OpenRouter/Vercel alpha, ADR Q2) is a second wire
  and is not added. Any decision model a backend serves under System One is just a `model` value.
- **PD-2** Each port exposes the table as data (a constant map/list), and a `supports` option
  overrides the image column per classifier. The table is a fail-fast for *what the table says*,
  never a claim about the vendor (review point 4). Rows were proven live on 2026-11-04 except
  Vercel (no key) and Cloudflare-Jev images (not proven, so false).
- **PD-4** `openrouter`'s default model moves from `typesafe/jev-1.13` to `typesafe/jev-1.14`;
  the pairing check's message names the new default.
- An explicit `baseUrl` / `model` / `apiKeyEnv` still wins over the preset (unchanged).
- `custom` without both `baseUrl` and `supports` is a construction error naming both
  (`classifier: backend "custom" requires baseUrl and supports`). The check applies to style
  `systemone` only; `static`/`custom`/`llm` styles never touch the wire.
- An unknown backend's error lists all five values.
- **`${NAME}` in `baseUrl` expands from the environment at call time** (unset ⇒ empty), like
  headers. Error text names the endpoint **as configured, unexpanded**, so an account id never
  reaches an error. A URL with no `${` is unchanged.

`Backend` (whose endpoint answers) and `style` (`systemone | llm | custom | static`) stay two
options. `backend: custom` is a self-hosted *endpoint*; `style: custom` is a host *function*.

## 2. Wires

**systemone** (unchanged when there are no images): `POST {baseUrl}/systemone`, body
`{model, questions, state}` + `assets` when the state carried images.

**cloudflare**: `POST {baseUrl}` (no suffix). Body:

```
{"input":{"images":[…]?,"questions":{…},"state":…},"model":"…"}
```

`images` is present only when the state carried images: a list of data URLs
`data:image/<format>;base64,<data>` in **asset collection order** (§3). Assets do not go on this
wire as a map. `requestParams` / `bodyTransform` apply to the outer body, as on systemone.

Reply: a JSON object. `success` not literally `true` ⇒ error
`classifier: cloudflare: envelope not successful`. Then `result`; if `result` is an object with a
string `state`, then `state != "Completed"` ⇒ error `classifier: cloudflare: run state "<s>"`,
else `result = result.result`. `result` then decodes as a System One reply. HTTP status, retry and
redaction rules are the systemone ones (§8B), unchanged. The envelope's own error list is never
echoed (it can carry an account id).

## 3. Images (PD-3)

Applies to style `systemone` only (every backend). `static`, `custom` and `llm` receive the state
verbatim, exactly as before.

**Lifting.** If the state contains no content part at all, it is sent **verbatim** (byte-identical
to before). Otherwise walk it depth-first: object keys in ASCII order, arrays in order.

- an **image** part with inline data becomes a reference string, and the image becomes an asset
  under that reference;
- a **text** part becomes its text;
- any other part (audio, file, an image with only a URL) is an error naming its type, raised
  before any request;
- every other value is kept as is.

**Reference names.** An image that is directly the value of an object key takes that key as its
reference when the reference is not already used in this state. Otherwise it takes `image<n>`,
the smallest `n ≥ 1` not already used. (A top-level image state is `image1`.)

**Asset.** `{"type":"image","format":"png"|"jpeg","data_base64":"<standard base64, no data: prefix>"}`.
MIME `image/png` ⇒ `png`; `image/jpeg` or `image/jpg` ⇒ `jpeg`; anything else is an error.

**Order of checks**, all before the network:
1. at least one image and `supports.images` false ⇒ **`ClassifierUnsupported`** (feature
   `images`). Nothing is sent.
2. per image, in collection order: format; decoded size ≤ 16 MiB (16·1024·1024 bytes); width and
   height each in 280..1568 px, read from the header (PNG: IHDR big-endian u32 width at byte 16,
   height at 20, after the 8-byte signature; JPEG: walk segments from byte 2, the first SOF
   marker `C0–CF` except `C4`, `C8`, `CC` holds height at segment+5 and width at segment+7, both
   big-endian u16). Bytes that are not a readable header of the declared format are an error.
3. the sum of decoded image bytes ≤ 32 MiB.

Limit errors are ordinary errors whose message starts `classifier: image "<ref>": `. They are not
`ClassifierUnsupported`: an image outside the limits is the caller's input, not a backend gap.
**Images are never resized and never turned into text.**

**Header.** `TypeSafe-Danger-Zone-Images: true` is sent iff the request carries at least one asset
**and** the backend's row says "yes" (typesafe, openrouter, vercel, custom). Cloudflare never
sends it (review point 6). Host `headers` are applied after it and win.

## 4. `ClassifierUnsupported` (PD-5)

A typed error (Go `*ErrClassifierUnsupported`, review point 6: not `ErrUnsupported`, which
shadows `errors.ErrUnsupported`) carrying `backend`, `model`, `feature`. Message, exact:

```
classifier: <backend> (<model>) does not support <feature>
```

`<backend>` is the backend name (`typesafe` when unset). It is raised before any network call
and is reported on `classifier.evaluate` as an error event.

## 5. Value builders (PD-6, PD-7)

```
classifier.state(v).evaluate(questions…)   -> Decision          // one request
classifier.states(v1, v2, …).evaluate(qs…) -> [BatchResult]      // fan-out, in order
BatchResult { decision?, error? }                                  // exactly one is set
```

- `state(v)` is a **value**, not a session: nothing is sent or stored until `evaluate`.
- `questions` are the ordered builders (`noul`, `choice`, `score`, `raw`); a duplicate name is an
  error before any request (same rule as `ask`).
- `state(v).evaluate` runs the same path as `evaluate` **plus V1**: an invalid reply is an error
  (and is reported on the metric event). Plain `evaluate` keeps its old behaviour and does not
  fail on V1.
- `states(…).evaluate` runs `state(v).evaluate` per state, at most 16 in flight, results in state
  order. **Batch is never an error for one item** (review point 5): each item carries its own
  decision or error. A question-set error (duplicate name) is raised once, before any request. No
  states ⇒ an empty list and no request. `evaluateBatch` (fail-closed, lowest index) is unchanged.

## 6. Cache (PD-8, review points 1–3)

- **Off by default.** Option `cache: {get(key) -> string | absent, set(key, value: string)}`.
  Absent ⇒ no caching and nothing global. A port may make the methods async where its classifier
  is async.
- Consulted by style `systemone` only (every backend).
- **Per request, not per question (PD-9).** Splitting a request into cached and uncached questions
  would change answers if System One calibrates questions jointly; that is unproven (§9). The key
  covers the whole question set.
- **Only V1-valid replies are cached.** Errors, unsupported, invalid replies are never stored.
- **Keyed on the served model.** Two entries per request:

  ```
  modelKey    = sha256hex(canon({"endpoint":E,"kind":"model","model":M}))
  decisionKey = sha256hex(canon({"assets":A,"endpoint":E,"kind":"decision","model":S,"questions":Q,"state":T}))
  ```

  `canon` is the §8B canonical JSON (ASCII key order, arrays kept, compact, raw `<>&'"` and
  non-ASCII). `E` = the configured `baseUrl` **unexpanded** (after the preset), `M` = the requested
  model, `S` = the served model (`decision.model`, or `M` when the reply has none), `Q` = caller
  key → question wire, `T` = the state as sent (after lifting), `A` = the assets map — the
  `"assets"` member is **omitted** when there are none. The value under `modelKey` is `S` as plain
  text; the value under `decisionKey` is the System One reply body as received (for cloudflare,
  the canonical JSON of the unwrapped `result`).

  Lookup: `get(modelKey)` → `S`; `get(decisionKey(S))` → reply; it is decoded and V1-checked; a
  corrupt or invalid entry is a miss. Store (after a live, V1-valid reply): `set(modelKey, S)`,
  `set(decisionKey(S), body)`. So when `jev-latest` starts serving another model, the next live
  call re-points `modelKey`, and old answers stop being found; until a live call happens, the TTL
  is the guard. A hit returns the decision with `cached = true`, sends nothing, and emits
  `classifier.evaluate` with `cached = true`.
- Key bytes are specified so a cache written by one port reads in another
  (`examples/judge/backends/cache-keys.json`). Equality across ports holds for states whose
  numbers render identically (§8B: state is outside the byte claim).
- Question **keys are in the key**: renaming a question is a miss (it changes the reply's keys).
- `MemoryCache(ttl, max)`: in-process, safe for concurrent use; `ttl` 0 ⇒ no expiry; `max` 0 ⇒
  unbounded; over `max` the oldest-inserted entries go first. toolnexus persists nothing itself;
  a host persists by implementing `get`/`set` (jevx's file cache becomes one).
- **The D8 cutover gate runs with no cache** (`--fresh`), review point 2.

## 7. Pure helpers (PD-10 … PD-13)

**V1 `validateDecision(questions, decision) -> error | none`**, fail-closed, first failure wins,
checks in this order:
1. per question key (ASCII order): no answer ⇒ `no answer "<key>"`;
2. per answer key not asked (ASCII order) ⇒ `unexpected answer "<key>"`;
3. per key (ASCII order): answer type ≠ question type ⇒ mismatch (a raw question matches only an
   answer whose type equals its own `type`, and gets no further checks); `noul` in `[0,1]`;
   `choice`: the choice is an offered option, every probability key is offered, every
   probability in `[0,1]`, a non-empty map sums to `1 ± 0.02` (inclusive), confidence in `[0,1]`;
   `score`: score in `[0, levels−1]`, probabilities as for choice, confidence in `[0,1]`. NaN fails
   every range.

Every message starts `classifier: invalid decision: ` and names the key as `"<key>"` verbatim.

**T1 `Thresholds{yes, no, minConfidence}`**, default `0.8 / 0.2 / 0.6` (jevx's), **inclusive**:
noul `p ≥ yes` ⇒ `yes`, else `p ≤ no` ⇒ `no`, else `uncertain`; choice/score
`confidence ≥ minConfidence` ⇒ `yes`, else `uncertain` (no near-uniform demotion — that is
`Bands`' rule). A raw answer is `uncertain`. `Bands` is unchanged and stays the default of
`ask`/`gate` (PD-10: jevx keeps its saved thresholds' meaning).

**C1 `chunkQuestions(questions, size) -> [questions]`**: keys in ASCII order, consecutive chunks
of `size`; `size < 1` is an error. A helper, not automatic: the host decides to split and merges,
because joint calibration across a split is unproven (§9) (PD-11).

**L1** every `classifier.evaluate` event (ok and error, every style) carries `questions` (the
count), `questionsHash` (sha256 hex of `canonicalRequest(model, questions)` — the fixture
`canonicalSha256`; empty when the questions could not be encoded) and `cached` (PD-12).

## 8. Open question union and `llm` deprecation (PD-14, PD-15)

- **`raw(name, type, instructions, criteria?)`** / a raw question value: wire
  `{"criteria"?, "instructions", "type"}` as given; `type` must be non-empty. No client-side
  limits (the library does not know the type). An answer whose `type` is not `noul|choice|score`
  decodes to a **raw answer** `{type, value: <the answer object>}` instead of failing the whole
  decision. This is how `box` (Jev 1.14) is reachable without a release.
- **`style: "llm"` is deprecated** (owner's "single style"). It keeps working byte-identically, as
  an alias, for at least one minor release; each port marks it with its native deprecation
  marker where that does not break builds, and the docs say so. No runtime warning (PD-15).

## 9. Not proven, so not built

- **Joint calibration.** Whether System One calibrates questions jointly inside one request is
  unknown. Test plan (owner-run, keys via `sec run TYPESAFE_API_KEY -- …`): 10 states × 3
  questions; ask each state once with all three and three times with one each; compare answers
  with the live noise floor (σ ≈ 0.015, spread 0.05, `spikes/classifier/reports/00-live-backend.md`
  F2). Independence ⇒ max |Δ| within the floor. Only then may per-question caching or automatic
  chunking be added.
- Multipart image upload; the Decisions API; a native batch wire; Cloudflare async batch;
  Cloudflare-Jev and Vercel images.

## Proposed defaults, owner to confirm

| id | default |
|---|---|
| PD-1 | System One only; no Decisions API |
| PD-2 | backend support table is data; `supports` overrides per classifier |
| PD-3 | images via `assets`; `TypeSafe-Danger-Zone-Images` only on typesafe/openrouter/vercel/custom; limits checked client-side; never resized |
| PD-4 | openrouter default model `typesafe/jev-1.14` |
| PD-5 | `ClassifierUnsupported` raised before the network, for the table only |
| PD-6 | `state(v).evaluate` / `states(…).evaluate` value builders; V1 on the single form |
| PD-7 | batch returns per-item results + errors, in order |
| PD-8 | opt-in injected `{get,set}` cache + `MemoryCache`; nil = off; only valid replies; served-model key; canonical key spec'd |
| PD-9 | per-request caching (not per question) until independence is shown |
| PD-10 | `Thresholds` (inclusive, 0.8/0.2/0.6) alongside `Bands`; `ask`/`gate` keep `Bands` |
| PD-11 | V1 as a helper + on the builder path; C1 as a helper, not automatic |
| PD-12 | L1 fields on every `classifier.evaluate` event |
| PD-13 | custom backend sends the image header (it speaks the TypeSafe wire) |
| PD-14 | `Question` is an open union via `raw`; unknown answer types decode to raw |
| PD-15 | `llm` deprecated, kept as an alias, no runtime warning |
