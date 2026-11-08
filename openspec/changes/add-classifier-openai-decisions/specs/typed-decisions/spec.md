## ADDED Requirements

### Requirement: OpenAI Decisions backend

The classifier SHALL offer a backend `openai` whose row is baseUrl `https://api.openai.com/v1`, model `gpt-6-luna`, apiKeyEnv `OPENAI_API_KEY`, wire `decisions`, images supported, and no image header. It SHALL reuse every shared `ClassifierOptions` field. The backend's own key SHALL be sent only to `api.openai.com` over https. The unknown-backend error SHALL read `classifier: unknown backend "<b>" (want "typesafe", "openrouter", "cloudflare", "vercel", "openai" or "custom")`.

#### Scenario: preset row
- **WHEN** a classifier is created with backend `openai` and no other options
- **THEN** it posts to `https://api.openai.com/v1/decisions` with model `gpt-6-luna`

#### Scenario: key not sent to another host
- **WHEN** `OPENAI_API_KEY` is set and an `openai` classifier's baseUrl points at another host
- **THEN** the request carries no `Authorization` header

### Requirement: Decisions wire request

On the `decisions` wire the classifier SHALL `POST {baseUrl}/decisions` with `{input, model, questions}`. `questions` SHALL be a list in ASCII caller-key order, and caller keys SHALL NOT be sent. A noul question SHALL become `{type:"predicate", instructions}`, with criteria appended as `"\n\nTrue means: <true>\nFalse means: <false>"`. A choice SHALL become `{type:"choice", instructions, choices:[{value, description?}]}` in ASCII id order, with an empty description omitted. A score SHALL become `{type:"score", instructions, levels:[{label}]}` in rubric order. A string state SHALL be sent as `input` verbatim, and any other state as its canonical JSON text. A state with images SHALL be sent as one user message: an `input_text` part, then `input_image` data URLs in collection order. Each of the following SHALL fail before any request: a raw question (`ClassifierUnsupported`, `classifier: <backend> (<model>) does not support raw question type "<t>"`), a choice with fewer than 2 options, and more than 400 questions.

#### Scenario: keys are not sent
- **WHEN** questions `damaged`, `team` and `upset` are evaluated
- **THEN** the body lists three questions in that order, none with a `name`

#### Scenario: one-option choice
- **WHEN** a choice question has one option
- **THEN** the call fails with `classifier: question "<key>": the decisions wire needs 2..255 choice options, got 1` and nothing is sent

### Requirement: Decisions wire reply

The classifier SHALL read the reply's `answers` list by position into the one `Decision` type, with `calibrated: false`. A predicate's `probability` SHALL become `noul`. A choice SHALL keep `choice`, `confidence` and `probabilities` keyed by value, with booleans rendered `"true"`/`"false"`. A score SHALL keep `score` and `confidence`, with `probabilities` keyed by the 0-based level index and `legend` taken from the question. A `refusal` answer SHALL be an error `classifier: decisions: question "<key>" was refused`, never a "no". A count mismatch, an answer of the wrong type, or a reply with no `answers` list SHALL be an error. Validation, cache and metrics SHALL then run unchanged.

#### Scenario: refusal
- **WHEN** the answer for `team` is `{"type":"refusal"}`
- **THEN** the evaluate fails with `classifier: decisions: question "team" was refused`

#### Scenario: no native batch
- **WHEN** `states(a, b).evaluate(...)` runs on the `openai` backend
- **THEN** one request is sent per state, as on every other backend
