# Delta for local-models

## ADDED Requirements

### Requirement: Local OpenAI-compatible endpoint needs no key

`createLocalClient` with format `openai` (the default) SHALL build a client against the given
OpenAI-compatible `baseUrl` that does not require an API key; when none is given a placeholder
key SHALL be sent so the client never fails with `no API key`.

#### Scenario: llama-server without a key

- **WHEN** a host creates a local client with only `baseUrl` and runs a prompt
- **THEN** the request is sent to `<baseUrl>/chat/completions` and the run succeeds

### Requirement: FunctionGemma request shape

`createLocalClient` with format `functiongemma` SHALL send chat requests whose first message is a
`developer` message starting with the exact FunctionGemma developer line, with any system prompt
on later lines, plus the toolkit's tools, `temperature: 0` and `stop: ["<end_function_call>"]`.

#### Scenario: Developer line and prefill

- **WHEN** a functiongemma client with a system prompt and `prefill` on runs a prompt
- **THEN** the first message's first line is `You are a model that can do function calling with the following functions`
- **AND** the last message is an assistant message `<start_function_call>call:`

### Requirement: FunctionGemma call parsing

The model's text SHALL be parsed for `call:NAME{...}` occurrences; each becomes one tool call, in
order, executed by the normal client loop. Values between `<escape>` markers are strings; bare
values are integers, floats, `true`, `false`, `null`, or else kept as strings. Text without a call
SHALL be returned as the assistant's text.

#### Scenario: A call is parsed and the tool runs

- **WHEN** the endpoint returns `call:add_to_order{product:<escape>raw honey<escape>,quantity:2}`
- **THEN** the tool `add_to_order` runs with `{"product":"raw honey","quantity":2}`

#### Scenario: The prefill continuation is accepted

- **WHEN** prefill is on and the endpoint returns only `add_to_order{...}` (the continuation)
- **THEN** the call is parsed as if the prefix were present

#### Scenario: Plain text is the answer

- **WHEN** the endpoint returns text with no `call:`
- **THEN** no tool runs and the run's text is that text
