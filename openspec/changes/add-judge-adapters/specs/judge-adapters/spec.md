## ADDED Requirements

### Requirement: Judgments are built from a state map and an ordered question list

The library SHALL provide builders that turn a state map (or `context` + `message` plus an
optional extra map) and an ordered list of named `noul` / `choice` / `score` questions into
the SPEC §8B `evaluate(state, questions)` inputs, byte-identical to hand-written inputs.

#### Scenario: The video example builds the existing wire inputs

- **WHEN** a caller builds state `{role, message_received}` and the list
  `[noul("is_appropriate", …), noul("does_this_help", …)]`
- **THEN** the produced state and question map equal `wantState` / `wantQuestions` of the
  `video-donkey-kong` case in `examples/judge/state-cases.json`

#### Scenario: Context and message sugar merges extra fields

- **WHEN** a caller builds state from context `"triage bugs"`, message `"checkout 500"` and
  extra `{reporter: "support-team"}`
- **THEN** the state is `{context, message, reporter}` exactly

#### Scenario: A duplicate question name is rejected before any request

- **WHEN** the question list contains two questions named `x`
- **THEN** the builder fails with an error naming `"x"` and no request is sent

### Requirement: ask returns answers by name with bands

`ask(classifier, state, questions, bands?)` SHALL return one answer per question name.
A `noul` answer SHALL carry `band` in `{yes, no, uncertain}`; a `choice` or `score` answer
SHALL carry `sure` as a boolean. Cut-points SHALL default to `low = 0.30`, `high = 0.70`,
SHALL be overridable per call, and SHALL be exclusive on the confident side.

#### Scenario: Exactly on a cut-point is uncertain

- **WHEN** a noul answer is exactly `0.30` or exactly `0.70` under default bands
- **THEN** its band is `uncertain`

#### Scenario: A near-uniform choice is not sure

- **WHEN** a choice answer has confidence `0.80` and `nearUniform` is true
- **THEN** `sure` is false

#### Scenario: Custom cut-offs move the band

- **WHEN** bands are `{low: 0.20, high: 0.50}` and a noul answer is `0.55`
- **THEN** its band is `yes`

### Requirement: gate escalates instead of deciding when unsure

`gate(classifier, state, questions, rules, bands?)` SHALL apply rules (`below`, `at_least`,
`is`) in order, first match wins. A rule whose answer is uncertain, not sure, or missing
SHALL escalate: the outcome SHALL be `needs_input` with a SPEC §10 `Request` of kind
`input` whose data carries the question, the reason and the answers. An escalation on rule
i SHALL win over later rules.

#### Scenario: An unsure pick does not fire an action

- **WHEN** `fixable` is `0.55` and `component` is `pricing` at confidence `0.40`
- **THEN** the outcome is escalated `needs_input`, not `skip_to fix-pricing`

#### Scenario: A missing answer escalates

- **WHEN** a rule names a question absent from the decision
- **THEN** the outcome is escalated `needs_input` with reason naming the missing answer

#### Scenario: Every shared gate case holds in every port

- **WHEN** each case of `examples/judge/gate-cases.json` is run through the `static` style
- **THEN** each port yields that case's `want` action, target and escalated flag

### Requirement: Absent is byte-identical

A host that uses no builder, `ask` or `gate` SHALL observe byte-identical behaviour to a
build without them, and the §8B wire request SHALL be unchanged.

#### Scenario: The canonical request is unchanged

- **WHEN** the same state and questions are evaluated via builders and via hand-written maps
- **THEN** the transmitted request bodies are byte-identical
