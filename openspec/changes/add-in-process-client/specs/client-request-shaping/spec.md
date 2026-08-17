# Delta for client-request-shaping

## ADDED Requirements

### Requirement: An in-process model is constructed as a client, without wire configuration

The library SHALL provide a constructor (idiomatic name per port, e.g. `createInProcessClient`) that
builds a client backed by a host-supplied generate function rather than by a network endpoint. It
SHALL NOT require a base URL, an API key, or a provider style, and SHALL NOT require the host to
construct any HTTP type.

The resulting value SHALL be an ordinary client: every other client option and every tool source —
MCP servers, skills, native and HTTP tools, sub-agents, the completion gate, hooks, metrics and
conversation memory — SHALL behave exactly as with a network-backed client.

#### Scenario: No wire configuration is required

- **WHEN** a host constructs an in-process client with only a model name and a generate function
- **THEN** the client is usable and no base URL, API key or style was supplied

#### Scenario: It is an ordinary client

- **WHEN** an in-process client runs against a toolkit containing tools
- **THEN** the tool-calling loop, hooks and usage reporting behave as they do for a network client

### Requirement: The generate function receives a request and returns one assistant message

The generate function SHALL receive the assembled request, carrying at least the conversation
messages, the tool schemas offered for this call, and the model name. It SHALL return exactly one
assistant message, expressed as either final content or a list of tool calls, and MAY report token
usage.

The library SHALL derive the finish reason from whether tool calls are present, and SHALL construct
the provider response envelope itself. The host SHALL NOT be required to produce `choices`, a finish
reason, or an HTTP status.

#### Scenario: Returning content ends the run

- **WHEN** generate returns content and no tool calls
- **THEN** the run completes with that content as the final text and a finish reason of `stop`

#### Scenario: Returning tool calls continues the loop

- **WHEN** generate returns one tool call for a tool in the toolkit
- **THEN** the loop executes that tool, appends the result, and calls generate again with the tool
  result present in the messages

#### Scenario: Usage is optional

- **WHEN** generate returns no usage
- **THEN** the run still completes and reports zero tokens rather than failing

### Requirement: Tool calls cross the seam in a flat shape

A tool call returned by generate SHALL be expressed with its identifier, name and arguments
directly, without a nested provider-specific wrapper. Arguments SHALL be accepted either as a
structured value, which the library encodes, or as an already-encoded string, which is passed
through unchanged.

#### Scenario: Structured arguments are encoded by the library

- **WHEN** generate returns a tool call whose arguments are a structured value
- **THEN** the tool receives the corresponding parsed arguments, and the host did not encode them

#### Scenario: Pre-encoded arguments are accepted unchanged

- **WHEN** generate returns a tool call whose arguments are already an encoded string
- **THEN** the tool receives the same parsed arguments as for the structured form

### Requirement: Streaming from an in-process client fails loudly

Because a generate function returns a complete answer, the streaming path of an in-process client
SHALL raise an explicit error naming the limitation. It SHALL NOT emit the whole answer as a single
delta, and SHALL NOT otherwise present buffered output as a stream.

#### Scenario: A streaming call is refused rather than faked

- **WHEN** a host calls the streaming entry point on an in-process client
- **THEN** an error is raised that names the limitation, and no partial or single-chunk stream is
  produced

### Requirement: The transport seam is unchanged

Adding the in-process constructor SHALL NOT alter the existing injectable HTTP transport. A host
that supplies a transport, and a host that supplies nothing, SHALL observe behavior identical to
before this change.

#### Scenario: The existing seam still works

- **WHEN** a host supplies a custom HTTP transport to the ordinary constructor
- **THEN** it is used for LLM requests exactly as before this change

#### Scenario: Absent configuration is byte-identical

- **WHEN** a host constructs an ordinary client with no transport
- **THEN** behavior is byte-identical to the pre-change library
