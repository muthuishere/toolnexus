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

