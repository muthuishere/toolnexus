## ADDED Requirements

### Requirement: Conversation history is held in a provider-neutral canonical form

The client SHALL hold conversation history in a single internal, provider-neutral message
representation, and SHALL render it into the configured style's wire dialect at call time rather than
accumulating wire-shaped messages. The representation SHALL cover exactly four message kinds:
`system` (instruction text), `user` (prompt text), `assistant` (optional text plus zero or more tool
calls, each with an id, a name, and parsed arguments), and `toolResult` (a call id, output text, and
an error flag).

`RunResult.messages`, the `history` accepted by `run`, and the transcript a `ConversationStore`
persists SHALL all be this canonical representation. The serialized field names used by a
`ConversationStore` are normative and identical in every port, so a transcript written by one port is
readable by another.

Rendering SHALL be lossless for a same-style round trip: a transcript produced by the loop, rendered,
persisted, loaded, and rendered again SHALL produce the same provider request body as before this
change. Provider content the canonical form has no kind for SHALL be retained opaquely and re-emitted
on a same-style render; it SHALL be omitted when rendering for a different style.

A transcript SHALL hold at most one `system` message, positioned first.

#### Scenario: A same-style round trip is unchanged

- **WHEN** a conversation is run, persisted, and resumed under the same `style`
- **THEN** the request body sent to the provider is identical to the body sent before this change

#### Scenario: The stored transcript is not a wire dialect

- **WHEN** a conversation containing a tool call is persisted under `style:"openai"`
- **THEN** the stored messages carry the canonical kinds and contain no `tool_calls` key and no `role:"tool"` entry

#### Scenario: Tool-call arguments survive as data

- **WHEN** an assistant turn requesting a tool call is persisted and reloaded
- **THEN** the tool call's arguments are restored as the same parsed structure, not as a provider-specific encoding

### Requirement: A conversation resumes correctly under a different provider style

Resuming a stored conversation under a `style` different from the one that produced it SHALL render a
request valid for the target provider, including transcripts that contain tool calls and tool results.

Resuming an `openai`-produced transcript under `anthropic` SHALL hoist the system instruction to the
top-level `system` field, emit assistant tool calls as `tool_use` content blocks, and carry tool
results as `tool_result` blocks inside a `user` message. Resuming an `anthropic`-produced transcript
under `openai` SHALL place the system instruction as a `system` entry inside `messages`, emit
`tool_calls` on the assistant message, and carry tool results as `role:"tool"` messages.

Under no circumstance SHALL the client forward a message field belonging to one provider's dialect to
the other provider.

#### Scenario: Cross-style resume with a tool call

- **WHEN** a conversation containing a tool call and its result is persisted under `style:"openai"` and resumed under `style:"anthropic"`
- **THEN** the request sent to the Anthropic endpoint contains no `tool_calls` key and no `role:"tool"` message, and carries the tool result as a `tool_result` block inside a `user` message

#### Scenario: Cross-style resume hoists the system instruction

- **WHEN** a conversation is resumed under `style:"anthropic"`
- **THEN** the system instruction appears in the top-level `system` field and no message in `messages` has `role:"system"`

#### Scenario: Text-only cross-style resume still works

- **WHEN** a conversation with no tool calls is persisted under one style and resumed under the other
- **THEN** the resume succeeds and the transcript's ordering and text content are preserved

### Requirement: A persisted transcript is balanced and replayable

A transcript written to a `ConversationStore` SHALL be replayable to a provider without repair.
Every assistant tool call in the transcript SHALL have exactly one corresponding tool result, and
every tool result SHALL correspond to an assistant tool call already present.

This SHALL hold on the durable suspension path. When a turn halts with several tool calls
outstanding and only the first is surfaced to the host, the transcript SHALL still be persisted
balanced — the unsurfaced calls carry a result rather than being left open — so a host that resumes
by replaying history sends a request the provider accepts.

#### Scenario: A durable halt with parallel tool calls persists balanced

- **WHEN** a turn emits three tool calls, halts durably, and the transcript is persisted
- **THEN** the stored transcript contains one tool result for each of the three calls

A tool result SHALL be a distinct message kind and SHALL NOT be represented as a `user` turn in the
canonical form, so any rule expressed over "a user turn" is well defined regardless of provider
style.

#### Scenario: A tool result is never a user turn

- **WHEN** a transcript produced under `style:"anthropic"`, where tool results ride inside user messages on the wire, is held canonically
- **THEN** each tool result is a `toolResult` message and no `user` message carries a tool result

#### Scenario: A replayed transcript is accepted

- **WHEN** a host loads a transcript persisted after a durable halt and replays it under `style:"anthropic"`
- **THEN** every `tool_use` block in the request has a matching `tool_result` block

### Requirement: Legacy wire-shaped transcripts are recognised, not misread

A persisted transcript SHALL carry a format marker identifying it as canonical. A transcript loaded
without that marker SHALL be treated as having been written by an earlier release in a wire dialect.

When an unmarked transcript is consistent with the client's configured style, the client SHALL parse
it into the canonical form, continue the conversation normally, and persist it in canonical form on
the next save.

When an unmarked transcript contains a field unambiguously foreign to the configured style — an
OpenAI `tool_calls` key or a `role:"tool"` message while the client is `anthropic`, or an Anthropic
`tool_use` or `tool_result` block while the client is `openai` — the client SHALL fail with an error
naming the mismatch, and SHALL NOT forward the transcript to the provider.

#### Scenario: An unmarked transcript matching the configured style is upgraded

- **WHEN** a transcript persisted by an earlier release under `style:"openai"` is loaded by an `openai` client
- **THEN** the conversation resumes normally and the next save writes the canonical, marked form

#### Scenario: An unmarked foreign-dialect transcript fails loudly

- **WHEN** a transcript persisted by an earlier release under `style:"openai"` and containing a tool call is loaded by an `anthropic` client
- **THEN** the client raises an error identifying the dialect mismatch instead of sending the transcript to the provider
