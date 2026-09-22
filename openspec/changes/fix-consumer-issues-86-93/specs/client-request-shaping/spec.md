## ADDED Requirements

### Requirement: A completion needs no toolkit

Every port SHALL accept a `run`, `ask` and `stream` call that supplies **no toolkit**, in that
port's own idiom, and SHALL treat the absence as a completion: the system message is the system
prompt alone, with no skills section appended.

Building the §0.10 system message SHALL NOT dereference the toolkit. An absent toolkit and a
toolkit built with builtins disabled SHALL be observably identical — the same system message and
byte-identical request bytes — so that a host needs no second way to say "no tools".

The per-port spelling SHALL be idiomatic rather than uniform: a defaulted parameter where the
language has them, a nullable parameter where it has those, and overloads only where it has
neither. A port that spells it with overloads SHALL provide them for the shapes a completion
uses (prompt; prompt plus conversation id; prompt plus a streaming callback) and SHALL NOT
duplicate its entire signature set.

No port SHALL introduce a public empty-toolkit constructor for this purpose.

#### Scenario: A prompt with no toolkit returns text

- **WHEN** a host calls the port's shortest `ask` form with a prompt and no toolkit
- **THEN** the call returns the model's text without raising
- **AND** the system message is the system prompt alone, with no skills section

#### Scenario: No toolkit and an empty toolkit are the same request

- **WHEN** the same prompt is sent once with no toolkit and once with a toolkit built with builtins disabled
- **THEN** the two request bodies are byte-identical

#### Scenario: A toolkit-less request carries neither tool key

- **WHEN** a request is built for a toolkit-less call
- **THEN** the request body has no `tools` key and no `tool_choice` key
- **AND** neither key is present as an empty array or a null

### Requirement: A provider failure is a typed value, redacted before it is a message

A non-2xx response from an LLM endpoint SHALL be surfaced as a typed error carrying the HTTP
`status`, the response `body` and, where the response supplied one, `retryAfter`, as fields a
host can read without parsing a message string.

The values of the account-identifier keys `user_id`, `account_id`, `org_id` and `organization`
SHALL be replaced with `«redacted»` — not dropped, so the body's shape survives — in **both** the
typed `body` field and the message. The unredacted body SHALL NOT be reachable through either.

The 200-character cap applies to the **message only**. The typed `body` field SHALL carry the
whole redacted body, because a host that reached for the typed error asked for the whole thing.
For `401` and `403` the message SHALL carry no body at all, the policy the classifier path
already applies.

The cap and the redaction are independent and both are required: an account identifier in a body
shorter than the cap is not protected by the cap, and a redacted body longer than the cap is
still truncated in the message.

#### Scenario: An account identifier never reaches the message

- **WHEN** a provider returns `400` with a body containing `"user_id":"user_2ABC"`
- **THEN** the error message contains `«redacted»` and does not contain `user_2ABC`
- **AND** the body's surrounding JSON shape is still legible in the message

#### Scenario: A short body is still redacted

- **WHEN** the leaking body is 96 bytes, well under the 200-character cap
- **THEN** the identifier is still replaced, because redaction does not depend on the cap

#### Scenario: An auth failure carries no body at all

- **WHEN** a provider returns `401` or `403`
- **THEN** the error message carries an empty body, and only the status

#### Scenario: The typed field is redacted too, and uncapped

- **WHEN** a host catches the typed error after a `429` whose body is longer than 200 characters and carries an account identifier
- **THEN** the typed `body` field carries the whole body, not a truncation
- **AND** the identifier is `«redacted»` in that field as well as in the message
- **AND** `status` and `retryAfter` are readable as fields

#### Scenario: Typing the error does not change what is retried

- **WHEN** a `400` is returned with a retry budget of four attempts
- **THEN** the call fails after exactly one attempt, because the retryable set is unchanged
