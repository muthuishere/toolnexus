## ADDED Requirements

### Requirement: The Classifier contract

The library SHALL provide a `Classifier` with exactly one operation,
`evaluate(state, questions) -> Decision`. A `Classifier` SHALL NOT participate in the client loop:
it takes no messages, exposes no tools, and is never selected as a model for `run`/`ask`.

`state` SHALL accept a string, an object, or an array. `questions` SHALL be a map from
caller-chosen keys to question definitions. Question keys SHALL be the caller's own and SHALL NOT
be transmitted as content to the backend, so a key MAY be a tool, skill, or agent name verbatim.

Questions SHALL be answered independently: an answer to one question SHALL NOT be used as context
for another.

#### Scenario: A decision answers every question asked

- **WHEN** `evaluate` is called with three questions
- **THEN** the returned `Decision` contains one answer per question, keyed by the caller's keys

#### Scenario: Keys are addressing, not content

- **WHEN** two evaluations differ only in their question keys
- **THEN** the content transmitted to the backend is unchanged

### Requirement: Three question types, each with its own answer shape

The library SHALL support exactly three question types.

- A **truth** question asks whether a statement holds and MAY carry descriptions of its true and
  false cases. Its answer SHALL be a single probability between 0 and 1 inclusive. It SHALL NOT
  report a separate confidence.
- A **choice** question selects one option from a named set of at most 255 options, each carrying
  a description. Its answer SHALL report the selected option, a probability for every offered
  option, and a confidence.
- A **score** question rates the state against an ordered rubric of between 2 and 10 levels. Its
  answer SHALL report a numeric score that MAY fall between levels, a probability per level, the
  rubric as a legend, and a confidence.

A choice answer's selected option SHALL always be one of the offered options. A score answer's
value SHALL always lie within the rubric's bounds.

#### Scenario: A choice cannot select an option that was not offered

- **WHEN** a choice question offers three options
- **THEN** the answer's selected option is one of those three
- **AND** its probabilities name exactly those three options

#### Scenario: A score stays inside its rubric

- **WHEN** a score question declares a rubric of three levels
- **THEN** the answer's score is between the lowest and highest level index inclusive

#### Scenario: A truth answer carries no confidence

- **WHEN** a truth question is answered
- **THEN** the answer reports a probability and does not report a confidence

#### Scenario: Question limits are enforced before a request is sent

- **WHEN** a choice question offers more than 255 options, or a score rubric has fewer than 2 or more than 10 levels
- **THEN** the call fails with an error naming the offending question key and the limit
- **AND** no request is sent

### Requirement: The canonical request covers the questions, never the state

For identical questions and model, every port SHALL produce a byte-identical request payload for
the `questions` and `model` portions. Object keys SHALL be sorted recursively in ASCII order.
Arrays SHALL NEVER be reordered, because a score rubric's order is its level numbering.

The caller's `state` SHALL be transmitted as supplied and is explicitly outside the byte-identity
claim, because numeric formatting does not agree across languages.

#### Scenario: Ports agree on the questions payload

- **WHEN** each port serialises the shared fixture's questions
- **THEN** every port produces the same bytes

#### Scenario: A rubric's order survives canonicalisation

- **WHEN** a score rubric is declared in an order that is not alphabetical
- **THEN** the serialised rubric preserves the declared order

#### Scenario: Characters that some encoders escape are transmitted raw

- **WHEN** a question's text contains `<`, `>`, `&`, a quotation mark, or a non-ASCII character
- **THEN** those characters are transmitted unescaped and the payload matches the shared fixture

### Requirement: Backends are selected by style and are invisible to callers

A `Classifier` SHALL select its backend by a `style` option, and the `Decision` shape SHALL be
identical whichever backend answered. The library SHALL support a System One wire style, an `llm`
style that emulates the three question types over an existing `Client` using structured output, a
`custom` style delegating to a host-supplied function, and a `static` style answering from recorded
fixtures.

The library SHALL NOT depend on any vendor SDK for any backend.

#### Scenario: A backend swap changes no calling code

- **WHEN** the same questions are evaluated through the System One style and through the `llm` style
- **THEN** both return the same `Decision` shape with an answer per question

#### Scenario: Fixtures answer without a network

- **WHEN** a `Classifier` is configured with the `static` style
- **THEN** evaluation succeeds with no network access and no credential present

### Requirement: A decision reports whether its probabilities are calibrated

Every `Decision` SHALL report whether its probabilities are calibrated. The System One style SHALL
report calibrated; the `llm` style SHALL report uncalibrated unless it derived probabilities from
provider token probabilities. Documentation SHALL state that a threshold tuned against one backend
does not transfer to another.

#### Scenario: The emulation backend declares itself uncalibrated

- **WHEN** a decision is produced by the `llm` style from a model's self-reported confidence
- **THEN** the decision reports that its probabilities are not calibrated

### Requirement: Credentials resolve at call time and are never logged

A `Classifier` SHALL read its credential from an environment variable **named** in its options,
resolved at call time. Custom headers SHALL expand `${ENV_VAR}` references from the environment at
call time, identically to remote MCP headers. No credential value SHALL appear in any log, error
message, or returned value.

#### Scenario: A failure names no secret

- **WHEN** a request fails with an authentication error
- **THEN** the raised error names the status and the endpoint
- **AND** contains no credential value and no expanded header value

### Requirement: Failures reuse the existing error classification

Transport and HTTP failures SHALL be classified through the same error-tier mechanism the client
loop uses, and SHALL honour `Retry-After` by the same rule. This capability SHALL NOT introduce a
second retry policy. A backend's own limit errors SHALL be surfaced with their reported cause
intact so a caller can distinguish a limit from a transport fault.

The default retryable set SHALL be an exhaustive enumeration, not a range: `429`, `500`, `502`,
`503`, `504` and `529`, plus transport failures, and additionally `408` on the classifier path.
Every other status SHALL be terminal by default. Both the client and the classifier SHALL accept a
`retryableStatuses` option (named per port) whose statuses are **added** to that set; it SHALL NOT
be able to remove a default status, and `onError` SHALL retain the final say on every attempt. The
set and the option SHALL be identical in all seven ports.

#### Scenario: A rate-limited evaluation is retried on the existing policy

- **WHEN** a backend responds with a rate-limit status carrying a delay
- **THEN** the call is retried according to the client's existing error-tier and `Retry-After` rules

#### Scenario: A documented overload status is retried, not failed

- **WHEN** a backend responds `529 Overloaded`
- **THEN** the call is retried with backoff up to the configured retry budget

#### Scenario: An unlisted status is terminal until the host opts in

- **WHEN** a backend responds `520` and the host has set no `retryableStatuses`
- **THEN** the call fails on the first attempt
- **AND** with `retryableStatuses` containing `520` the same call is retried, while `429` remains retryable

#### Scenario: The host option cannot remove a default, and onError still decides

- **WHEN** a host sets `retryableStatuses` to a list that does not contain `429`
- **THEN** `429` is still retried and `Retry-After` is still honoured
- **AND** an `onError` returning fail on a status the host listed stops the call after one attempt

#### Scenario: A non-429 client error is still terminal

- **WHEN** a backend responds `422 Unprocessable Entity`
- **THEN** the call fails on the first attempt with no retry

#### Scenario: A limit breach is reported as itself

- **WHEN** a backend rejects a request because it exceeds a documented limit
- **THEN** the error names that cause rather than reporting a generic transport failure

### Requirement: An unreported cost is absent, never zero

A `Decision`'s usage SHALL be able to express that the backend reported no cost. A backend that
omits `cost` SHALL yield an absent cost in every port, and a backend that reports a cost of `0`
SHALL yield a cost of `0`. No port SHALL substitute zero for an unreported cost.

#### Scenario: A backend that reports no cost

- **WHEN** a response carries `usage` with token counts and no `cost` key
- **THEN** the decision's usage reports the token counts and an absent cost

#### Scenario: A reported zero is preserved

- **WHEN** a response carries `usage.cost` of `0`
- **THEN** the decision's usage reports a cost of `0`, distinguishable from absent

### Requirement: The System One style is reachable at more than one endpoint

The System One style SHALL be configurable at any endpoint serving that wire, through the existing
`baseUrl`, `model` and `apiKeyEnv` options and no new API. Documentation SHALL name both TypeSafe's
first-party API and a gateway that serves the same wire, SHALL state that they are equivalent in
latency, and SHALL state which response fields are backend-specific.

#### Scenario: The same questions through either endpoint

- **WHEN** a classifier is pointed at TypeSafe's own API rather than the gateway, by `baseUrl`, `model` and `apiKeyEnv` alone
- **THEN** the returned `Decision` has the same shape and the same typed accessors, differing only in the absent cost

#### Scenario: A runnable example works with either key or none

- **WHEN** the runnable judge example runs with `TYPESAFE_API_KEY`, with `OPENROUTER_API_KEY`, or with neither
- **THEN** it reports which backend it used and produces a decision, using the recorded `static` replay when no key is present

### Requirement: Absent configuration changes nothing

A host that constructs no `Classifier` SHALL observe byte-identical behaviour to a build without
this capability. Constructing a `Classifier` SHALL NOT alter any request the client loop makes.

#### Scenario: A run without a classifier is unchanged

- **WHEN** a toolkit and client are used without constructing a `Classifier`
- **THEN** every request the client sends is byte-identical to one sent before this capability existed

### Requirement: A classifier interprets, it never authorises

Documentation for this capability SHALL state that a classifier's output is an interpretation and
SHALL NOT be described as a security boundary or an authorisation mechanism. Numeric limits and
permission checks belong in code.

#### Scenario: Documentation states the boundary

- **WHEN** a reader consults the documentation for this capability
- **THEN** it states that a decision is advisory, that calibration is not correctness, and that authority must be enforced elsewhere

### Requirement: Option descriptions are what differentiate options, and callers SHALL be told so

For a `choice` question, the value at `criteria[id]` SHALL be the only thing that distinguishes
one option from another to the model. Documentation for this capability SHALL state that a
`choice` whose criteria values are its own keys, are empty, or are all identical is schema-valid,
passes validation, returns a well-formed distribution, and ranks at chance — and SHALL state the
measurement behind that claim.

#### Scenario: Documentation states the encoding obligation

- **WHEN** a reader consults the documentation for the `choice` question type
- **THEN** it states that undescribed options rank at chance, and cites the measurement that establishes it

#### Scenario: A schema-valid degenerate choice is still accepted

- **WHEN** a caller submits a `choice` whose every criteria value equals its own key
- **THEN** the request is sent unmodified and the response is parsed and returned as for any other choice

### Requirement: Degenerate criteria are detected and reported, never repaired

A port SHALL detect a `choice` whose criteria values are all empty, or each equal to its own key,
or all identical to one another, and SHALL report it once per question id through the configured
metrics or log sink, naming that question id. A port SHALL NOT alter the request in response to
this detection.

#### Scenario: Criteria equal to their keys are reported

- **WHEN** a caller evaluates a `choice` question whose every criteria value equals its own key
- **THEN** a single warning naming that question id is emitted through the configured sink

#### Scenario: Detection does not change the bytes

- **WHEN** the same questions are evaluated with and without detection enabled
- **THEN** the request bytes are identical in both cases

#### Scenario: A described choice is not reported

- **WHEN** a caller evaluates a `choice` whose criteria values are distinct descriptive sentences
- **THEN** no degenerate-criteria warning is emitted

### Requirement: A choice answer carries a derived near-uniform signal

Every `choice` answer SHALL carry a derived boolean indicating whether its probability
distribution lies within the documented tolerance of uniform for its option count. This value
SHALL be computed from the response and SHALL NOT be read from the wire. Documentation SHALL
state that it is advisory, that it detects an encoding that gives the model nothing to rank on,
and that it does not distinguish a good encoding from a subtly wrong one.

#### Scenario: A peaked distribution is not near-uniform

- **WHEN** a choice answer returns probabilities that are clearly peaked on one option
- **THEN** the derived near-uniform signal is false

#### Scenario: An exactly uniform distribution is near-uniform

- **WHEN** a choice answer returns equal probability for every option
- **THEN** the derived near-uniform signal is true

#### Scenario: Every port agrees on the tolerance boundary

- **WHEN** each port evaluates the shared boundary fixture
- **THEN** every port reports the same near-uniform value for it
