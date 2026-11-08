## Context

The wire is recorded from OpenAI's API reference
(developers.openai.com/api/reference/resources/decisions/methods/create) and the Decisions guide
(developers.openai.com/api/docs/guides/decisions), read 2026-11-08. Request: `{model, input,
questions}`. Each question is `{type, instructions, name?}` plus `choices` (2–255, `{value,
description?}`) or `levels` (2–10, `{label, description?}`, lowest first). The request takes 1–400
questions. `input` is a string or a message array; images go as `input_image` parts with a data
URL. Reply: `{model, answers:[…], usage}` with answers in question order. A predicate answer has
`probability`. A choice answer has `choice`, `confidence` and `probabilities:[{value,
probability}]`. A score answer has `score`, `confidence` and `probabilities:[{value (0-based),
label, probability}]`. A refusal answer is `{type:"refusal", name}`. The API has no batch endpoint.

## Decisions

- **D1 Keys stay addressing.** §8B says caller keys are never transmitted. The optional `name` is
  therefore omitted, questions are sent in ASCII key order, and answers are mapped back by position.
  A count mismatch is an error.
- **D2 Noul criteria fold into instructions** as `"\n\nTrue means: <true>\nFalse means: <false>"`,
  because `predicate` has no criteria field and `ask`/`gate` build criteria. The text is pinned by the
  fixture.
- **D3 Fail before the wire** where the Decisions API is narrower than System One. That covers a
  one-option choice (System One allows 1), raw questions such as `box` (`ClassifierUnsupported`),
  and more than 400 questions. An empty choice description is omitted, not sent as `""`.
- **D4 State as text.** A string state is sent verbatim. Any other state is sent as its canonical
  JSON text. With images, the input is one user message: the lifted state text, then the images in
  collection order. Image lifting and checks are the same as on the other wires.
- **D5 Normalise, then share the path.** The reply is rewritten into the System One shape
  (canonical JSON) right after the POST, so decode, `validateDecision`, the cache and metrics are
  unchanged. Choice values are rendered as strings (booleans as `"true"`/`"false"`). Level `value`s
  become `"0"…"n-1"`. The score legend comes from the question.
- **D6 `calibrated: false`.** Decisions models are not System One models, and §8B says thresholds do
  not transfer. A calibration claim needs its own evidence (issue #157 *Out of scope*).
- **D7 No native batch.** The API takes one `input` per request, so the existing fan-out stays.
- **D8 Key to host.** The generic #155 helper applies unchanged: `OPENAI_API_KEY` is sent only when
  the dialled host is `api.openai.com` over https.

## Risks

The wire is taken from the docs. It was checked live on 2026-11-08 from Go, Python and Rust
(`gpt-6-luna`, `calibrated: false`): yes/no, pick-one and score all decoded. The fixture records the documented shapes, so a drift will appear as a decode
error, not as a silent wrong answer.
