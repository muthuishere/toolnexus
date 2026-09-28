# client-request-shaping Specification

## Purpose
TBD - created by archiving change implement-rag-go-consumer-needs. Update Purpose after archive.

## Requirements

### Requirement: Declarative request params and body transform

The client SHALL accept optional `RequestParams` — a map of extra top-level keys shallow-merged into
**every** LLM request body after the client builds its own keys, where a `RequestParams` key **wins**
on collision. It SHALL accept an optional `BodyTransform` hook that receives the fully assembled body
(after the `RequestParams` merge) immediately before marshal and returns the body to send. The keys
`messages`, `tools`, and `stream` SHALL NOT be settable via `RequestParams` (they are stripped with a
warning); message rewriting is done through `BodyTransform`. Both apply on all four paths — run and
stream, openai and anthropic. The ordering contract is: base body → `BeforeLLM` hook →
`RequestParams` merge → `BodyTransform` → marshal → wire. With neither option set, the body is
byte-identical to today.

#### Scenario: RequestParams appears at the top level on every path

- **WHEN** `RequestParams` sets `temperature` and a provider-specific extra key
- **THEN** both keys appear at the top level of the captured request body on non-streaming openai, streaming openai, non-streaming anthropic, and streaming anthropic

#### Scenario: RequestParams wins on collision

- **WHEN** `RequestParams` sets `max_tokens` on the anthropic path
- **THEN** the sent body carries that value, not the client's default 4096

#### Scenario: BodyTransform runs last and its output is sent

- **WHEN** a `BodyTransform` receives the post-merge body and drops a key
- **THEN** the captured upstream body does not contain that key

#### Scenario: Forbidden keys are stripped from RequestParams

- **WHEN** `RequestParams` contains `messages` or `tools` or `stream`
- **THEN** those keys are ignored (a warning is logged) and the client's own values are used

### Requirement: Injectable HTTP transport for LLM calls

The client SHALL allow the host to supply the HTTP transport (client/fetch/handler) used for LLM
requests, retries included. When none is supplied, the default transport is used and behavior is
unchanged. Scope is the LLM path only; MCP transports are out of scope.

#### Scenario: Injected transport receives the LLM calls

- **WHEN** a host supplies a recording HTTP transport
- **THEN** both `run` and `stream` route their LLM calls through it, and the default transport is untouched

### Requirement: Omit empty tool keys

On all four paths, when the effective tool list is empty — including after a `BeforeLLM` hook sets
tools to empty — the client SHALL omit the `tools` key (and, on the openai style, the `tool_choice`
key) from the request body entirely. A non-empty tool list is unchanged.

#### Scenario: Zero-tool toolkit omits the keys

- **WHEN** the toolkit yields zero tools on an openai non-streaming call
- **THEN** the captured body has no `tools` key and no `tool_choice` key

#### Scenario: Non-empty toolkit is unchanged

- **WHEN** the toolkit yields one or more tools
- **THEN** the `tools` key (and openai `tool_choice`) are present exactly as before this change

### Requirement: A beforeLLM override may choose the model for one turn

A `beforeLLM` hook override MAY carry `model`. When present and non-empty, that turn's request
body and `afterLLM` event SHALL carry it; the next turn starts from the configured model again.
When absent, null or empty, the configured `model` SHALL be transmitted verbatim, byte-identical
to a build without this requirement, in every client style and in streaming. The model REPORTED SHALL be
the model transmitted: the turn's `llm` metric event, `RunResult.model` and the `run` metric event
(the last model call's model), and `translate`'s `result.model`.

#### Scenario: No override keeps the configured model verbatim

- **WHEN** no hook returns a `model`
- **THEN** every request body carries the configured model unchanged

#### Scenario: An override routes only its turn

- **WHEN** `beforeLLM` returns `model: "small-fast"` on the first turn only
- **THEN** the first request carries `small-fast` and the second carries the configured model

#### Scenario: The reported model is the transmitted model

- **WHEN** `beforeLLM` returns `model: "small-fast"` on every turn of a run
- **THEN** each `llm` metric event, the `run` metric event and `RunResult.model` carry `small-fast`, and a `translate` result's `model` is `small-fast`

#### Scenario: Without an override the configured model is reported

- **WHEN** no hook returns a `model`
- **THEN** `RunResult.model` and every metric event carry the configured model

### Requirement: A failing beforeLLM hook stops the call

When a `beforeLLM` hook fails (throws, rejects, or returns an error in the port's idiom), the entry
point SHALL fail with that error and SHALL NOT send a provider request for that turn. This holds in
every loop — run, stream, the agent run — in every client style, and in the single-call
`translate`. The failure SHALL NOT be swallowed, ignored or retried. On a §7D agent, a level-1
loop run SHALL throw the hook's error, and a runtime handle turn SHALL resolve it as the §7D
boundary result (`isError: true`, `status: "error"`) — identically in all seven ports.

#### Scenario: Translate stops on a hook error

- **WHEN** `beforeLLM` fails during `translate`
- **THEN** `translate` returns that error and the provider receives no request

#### Scenario: Streaming stops on a hook error

- **WHEN** `beforeLLM` fails on the first turn of a streamed run
- **THEN** the stream ends with that error and the provider receives no request

#### Scenario: A level-1 agent loop run throws on a hook error

- **WHEN** an agent's `beforeLLM` fails with "hook boom" and the agent's level-1 loop runs a prompt
- **THEN** the loop run throws or raises that error in every port and the provider receives no request

#### Scenario: A handle turn resolves a hook error as an error result

- **WHEN** an agent's `beforeLLM` fails with "hook boom" and a runtime handle for that agent runs one turn
- **THEN** the turn resolves `isError: true`, `status: "error"`, text containing "hook boom", never an exception, and the provider receives no request
