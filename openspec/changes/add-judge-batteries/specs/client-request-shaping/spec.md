## ADDED Requirements

### Requirement: A beforeLLM override may choose the model for one turn

A `beforeLLM` hook override MAY carry `model`. When present and non-empty, that turn's request
body and `afterLLM` event SHALL carry it; the next turn starts from the configured model again.
When absent, null or empty, the configured `model` SHALL be transmitted verbatim, byte-identical
to a build without this requirement, in every client style and in streaming.

#### Scenario: No override keeps the configured model verbatim

- **WHEN** no hook returns a `model`
- **THEN** every request body carries the configured model unchanged

#### Scenario: An override routes only its turn

- **WHEN** `beforeLLM` returns `model: "small-fast"` on the first turn only
- **THEN** the first request carries `small-fast` and the second carries the configured model
