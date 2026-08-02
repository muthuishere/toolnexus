# tool-translation Specification

## Purpose
TBD - created by archiving change add-single-turn-translation. Update Purpose after archive.
## Requirements
### Requirement: A single-turn translation entry point makes exactly one provider call
Every port SHALL provide a `translate` entry point that performs exactly **one** provider
call and returns the turn in OpenAI shape. It SHALL NOT drive the agent loop, SHALL NOT
execute any tool, and SHALL NOT read or write any conversation store. Every call SHALL be
self-contained, so a caller may invoke it statelessly and concurrently.

#### Scenario: One call in, one provider request out
- **WHEN** `translate` is called with a message list
- **THEN** exactly one request reaches the provider and the result is returned without any
  further provider call

#### Scenario: Repeated calls accumulate no state
- **WHEN** `translate` is called three times with the same single-message request
- **THEN** each call sends the provider exactly that one message, and no history from a
  prior call appears in any later request

#### Scenario: Nothing is executed even when a tool is available
- **WHEN** a toolkit containing an executable tool is passed to `translate` and the model
  responds by calling that tool
- **THEN** the tool's handler is never invoked and the call is returned to the caller
  instead

### Requirement: The request accepts OpenAI shapes verbatim
The request SHALL accept the OpenAI `messages` array, the OpenAI `tools` array, and the
OpenAI `tool_choice` value **verbatim**, so a caller never constructs provider-native
payloads. It SHALL additionally accept an optional system-prompt override and an optional
max-tokens override. Message entries SHALL be accepted both as plain maps/objects and as
any value the port can serialize to JSON.

#### Scenario: OpenAI tool declarations are translated to the provider's native shape
- **WHEN** an OpenAI `tools` entry (`{type:"function",function:{name,description,parameters}}`)
  is passed and the upstream is Anthropic-style
- **THEN** the provider receives that tool as a native declaration carrying the name,
  description and input schema, and the OpenAI-only `parameters` key does not appear

#### Scenario: tool_choice maps onto the provider's equivalent
- **WHEN** `tool_choice` is `"required"`, `"none"`, or a specific function selection
- **THEN** the provider receives its corresponding native tool-choice value; and when
  `tool_choice` is absent or `"auto"`, no tool-choice value is sent

#### Scenario: A serializable message object is accepted
- **WHEN** a message is supplied as a typed object rather than a map
- **THEN** it is serialized and translated exactly as the equivalent map would be

### Requirement: A toolkit may be declared without being executed
The request SHALL accept an ordinary toolkit — any tool source, including MCP tools,
skills, native functions, A2A agents and builtins — and SHALL declare its tools to the
provider using the existing format adapters **without executing any of them**. This is the
inbound counterpart to the outbound-only adapters. Toolkit declarations SHALL compose with
an OpenAI `tools` array supplied in the same request.

#### Scenario: A toolkit's tools are declared and its calls handed back
- **WHEN** a toolkit holding an executable tool is passed and the model calls that tool
- **THEN** the tool appears in the provider request as a native declaration, the handler is
  not run, and the model's call is returned to the caller with its id, name and arguments

#### Scenario: A toolkit and an OpenAI tools array compose
- **WHEN** both a toolkit and an OpenAI `tools` array are supplied
- **THEN** the provider request declares the tools from both sources

### Requirement: Inbound translation preserves tool-call structure
When translating an OpenAI message list to a provider that uses native content blocks, the
port SHALL preserve tool-call structure rather than flattening messages to text.
Specifically: an assistant turn's `tool_calls` SHALL become native tool-use blocks with the
arguments parsed back from their JSON string into an object; a `tool`-role result SHALL
become a native tool-result block keyed by its `tool_call_id`; consecutive tool results
SHALL be merged into a **single** user turn, as providers requiring one result-bearing turn
per preceding assistant turn expect; and `system` messages SHALL be hoisted into the
provider's separate system field.

#### Scenario: A multi-turn tool exchange survives translation
- **WHEN** the message list contains a system message, a user message, an assistant turn
  carrying `tool_calls`, and a `tool`-role result carrying `tool_call_id`
- **THEN** the provider receives the system prompt in its own field, a native tool-use block
  whose arguments are an object, and a native tool-result block referencing the same
  `tool_call_id`

#### Scenario: Three consecutive tool results become one user turn
- **WHEN** an assistant turn calls three tools and three `tool`-role results follow
- **THEN** the provider receives one assistant turn with three tool-use blocks and exactly
  **one** user turn carrying all three tool-result blocks

#### Scenario: Content supplied as parts is flattened to text
- **WHEN** a message's `content` is an array of text parts
- **THEN** the parts are concatenated into the translated message's text

### Requirement: Outbound translation returns OpenAI shapes
The result SHALL carry the assistant text, the tool calls the model emitted **in provider
order**, an OpenAI `finishReason`, the call's token usage, the model, and the provider's
raw decoded response. Each tool call SHALL carry its id, its name, and its arguments as a
JSON **string** — the OpenAI wire form — so a caller can pass it to a conforming client
byte-for-byte. No tool call SHALL be dropped or truncated.

#### Scenario: A provider tool call becomes an OpenAI tool call
- **WHEN** the provider returns a native tool-use block
- **THEN** the result carries one tool call with the provider's call id, the tool name, and
  the arguments as a JSON string that parses back to the original object

#### Scenario: Parallel tool calls are all returned
- **WHEN** the provider returns text plus three native tool-use blocks in one turn
- **THEN** the result carries the text and all three tool calls in provider order

#### Scenario: Finish reason maps from the provider stop reason
- **WHEN** the provider reports a stop reason and the turn emitted no tool call
- **THEN** `finishReason` is `"stop"` for a normal stop, `"length"` for a token-limit stop,
  and `"content_filter"` for a refusal

#### Scenario: Tool calls win the finish reason
- **WHEN** the turn emitted at least one tool call
- **THEN** `finishReason` is `"tool_calls"` regardless of the provider's stop reason

### Requirement: Translation reuses the client's shared infrastructure
The entry point SHALL reuse the client's retry/backoff policy, its request-parameter
merging, and its LLM observability event, so a translating caller gets the same resilience
and metrics as a looping one. The `beforeLLM` and `afterLLM` hooks SHALL each fire exactly
once for the single call. Tool hooks SHALL NOT fire, because no tool runs.

#### Scenario: Request parameters configured on the client are applied
- **WHEN** the client is configured with extra request parameters and `translate` is called
- **THEN** the provider request carries those parameters

#### Scenario: LLM hooks fire once and tool hooks do not
- **WHEN** `translate` is called on a client with all hooks configured and the model
  responds with a tool call
- **THEN** `beforeLLM` and `afterLLM` each ran exactly once and no tool hook ran

