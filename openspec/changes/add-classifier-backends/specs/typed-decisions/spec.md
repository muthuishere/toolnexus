## ADDED Requirements

### Requirement: Backends are one enum of data rows

The classifier SHALL accept `backend` as one of `typesafe`, `openrouter`, `cloudflare`, `vercel` or `custom`, each preset supplying base URL, model, key env name, wire, image support and whether the image header is sent, exposed by every port as data. An explicit `baseUrl`, `model` or `apiKeyEnv` SHALL win over the preset, and a `supports` option SHALL override the preset's image support. `custom` SHALL require `baseUrl` and `supports` at construction. `${NAME}` in `baseUrl` SHALL expand from the environment at call time, and error text SHALL name the endpoint unexpanded.

#### Scenario: OpenRouter default model
- **WHEN** a classifier is created with `backend: openrouter` and no model
- **THEN** requests carry model `typesafe/jev-1.14` to `https://openrouter.ai/api/v1/systemone`

#### Scenario: custom needs its declarations
- **WHEN** a classifier is created with `backend: custom` and no `supports`
- **THEN** construction fails naming `baseUrl` and `supports`, and nothing is sent

#### Scenario: Account id stays out of errors
- **WHEN** a cloudflare request fails with HTTP 500 and `CLOUDFLARE_ACCOUNT_ID` is set
- **THEN** the error names the endpoint with `${CLOUDFLARE_ACCOUNT_ID}` unexpanded

### Requirement: Cloudflare envelope

With `backend: cloudflare` the classifier SHALL post `{"input":{"questions","state","images"?},"model"}` to `baseUrl` with no suffix, and SHALL decode the reply from `result`, unwrapping a nested `{"state":"Completed","result":…}`. A reply whose `success` is not `true`, or whose nested state is not `Completed`, SHALL be an error that does not echo the envelope's error list.

#### Scenario: Nested completed reply
- **WHEN** the endpoint answers `{"success":true,"result":{"state":"Completed","result":<System One reply>}}`
- **THEN** the decision is decoded from the inner reply

#### Scenario: Unsuccessful envelope
- **WHEN** the endpoint answers `{"success":false,"errors":[…]}`
- **THEN** evaluate fails with `classifier: cloudflare: envelope not successful`

### Requirement: Images travel as assets

On style `systemone`, an image content part anywhere in the state SHALL be replaced by a reference string and sent in a top-level `assets` map as `{"type":"image","format","data_base64"}` (cloudflare: data URLs in `input.images`), with reference names and walk order as `SPEC.md §8B` defines. A state with no content part SHALL be sent verbatim. The `TypeSafe-Danger-Zone-Images: true` header SHALL be sent only when assets are present and the backend's row requires it. Format, per-image size, side length and request size SHALL be checked before any request, and an image SHALL never be resized or described as text.

#### Scenario: Image lifted under its key
- **WHEN** the state is `{"note":"listing","photo":<png 300x300>}` on `typesafe`
- **THEN** the body's state is `{"note":"listing","photo":"photo"}`, `assets.photo.format` is `png`, and the image header is sent

#### Scenario: Image too small
- **WHEN** the state holds a 100x100 PNG
- **THEN** evaluate fails with an error starting `classifier: image "` and nothing is sent

#### Scenario: Cloudflare never sends the header
- **WHEN** a clef model on `cloudflare` receives an image state
- **THEN** the image goes in `input.images` and no `TypeSafe-Danger-Zone-Images` header is sent

### Requirement: Unsupported features fail before the network

When the state carries an image and the classifier's support says no images, evaluate SHALL fail with a typed `ClassifierUnsupported` error whose message is exactly `classifier: <backend> (<model>) does not support images`, before any request.

#### Scenario: Images on vercel
- **WHEN** an image state is evaluated on `backend: vercel` with no `supports` override
- **THEN** a `ClassifierUnsupported` error is returned and the fake endpoint receives no request

### Requirement: State value builders and per-item batch results

The classifier SHALL offer `state(v).evaluate(questions…)`, which behaves as `evaluate` plus fail-closed reply validation, and `states(…).evaluate(questions…)`, which returns one result-or-error per state in state order with at most 16 in flight. One state's failure SHALL NOT fail the batch. A duplicate question name SHALL be an error before any request.

#### Scenario: Batch keeps the successes
- **WHEN** three states are evaluated and the second one's request returns HTTP 400
- **THEN** results 0 and 2 carry decisions and result 1 carries the error

#### Scenario: Invalid reply on the builder path
- **WHEN** `state(v).evaluate` receives a reply missing one asked question
- **THEN** it fails with `classifier: invalid decision: no answer "<key>"`

### Requirement: Opt-in cache keyed on the served model

The classifier SHALL accept an optional cache with `get`/`set` over string keys and values, consulted only by style `systemone`, and SHALL ship an in-process `MemoryCache` with TTL and size cap. With no cache nothing SHALL be cached. Only replies that pass validation SHALL be stored. Keys SHALL be computed per request exactly as `SPEC.md §8B` specifies (model key and decision key on the served model). A hit SHALL send nothing and SHALL mark the decision and its metric event `cached`.

#### Scenario: Second identical call is served from cache
- **WHEN** the same state and questions are evaluated twice with a `MemoryCache`
- **THEN** the fake endpoint receives one request and the second decision has `cached` true

#### Scenario: Invalid replies are not cached
- **WHEN** the endpoint's reply fails validation
- **THEN** a repeated evaluate sends a second request

#### Scenario: Cross-port key bytes
- **WHEN** a port computes the keys for each case in `examples/judge/backends/cache-keys.json`
- **THEN** both hex digests equal the fixture's

### Requirement: Stateless verdict helpers

Every port SHALL export `validateDecision` (fail-closed, first failure in the `SPEC.md §8B` order, messages prefixed `classifier: invalid decision: `), `Thresholds` with default `0.8/0.2/0.6` applied inclusively, and `chunkQuestions` splitting keys in ASCII order. `Bands` SHALL be unchanged.

#### Scenario: Inclusive threshold boundary
- **WHEN** a noul of exactly `0.8` is banded with default `Thresholds`
- **THEN** the band is `yes`, while default `Bands` gives `yes` and a noul of exactly `0.7` gives `uncertain` under `Bands`

#### Scenario: Shared validation cases
- **WHEN** a port runs `examples/judge/backends/validate-cases.json`
- **THEN** every case's validity and named key match

### Requirement: Evaluate metric carries question count, hash and cache flag

Every `classifier.evaluate` event SHALL carry `questions` (count), `questionsHash` (sha256 hex of the canonical request) and `cached`.

#### Scenario: Hash matches the fixture
- **WHEN** the `base.json` request is evaluated on a static classifier
- **THEN** the event's `questionsHash` equals the fixture's `canonicalSha256` and `questions` is 3

### Requirement: Questions are an open union

A port SHALL provide a raw question `{type, instructions, criteria?}` sent on the wire as given, and SHALL decode an answer of an unknown type into a raw answer carrying its type and object rather than failing the decision.

#### Scenario: Box question passes through
- **WHEN** a raw question of type `box` is evaluated and the reply answers it with `{"type":"box",…}`
- **THEN** the request carries the question verbatim and the decision holds a raw answer of type `box`

### Requirement: The llm style is deprecated, not removed

`style: "llm"` SHALL keep its behaviour byte-identical, SHALL be documented as deprecated in every port, and SHALL NOT emit a runtime warning.

#### Scenario: llm still works
- **WHEN** a classifier with `style: "llm"` evaluates against a fake client
- **THEN** the decision is returned exactly as before this change
