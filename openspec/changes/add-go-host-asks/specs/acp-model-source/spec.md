## ADDED Requirements

### Requirement: ACP host hooks for permission, updates, cancellation and streaming
The ACP model source SHALL accept three opt-in host hooks, and SHALL behave byte-identically to before when none is set.
`onPermission(ctx, params) -> optionId` SHALL decide every `session/request_permission`; an error or empty result SHALL answer with the first reject-kind option, or `cancelled` when none is offered; unset SHALL keep auto-selecting the first allow-kind option.
`onUpdate(params)` SHALL be called with the raw params of every `session/update` notification, without changing the accumulated reply.
A context-taking generate SHALL send the `session/cancel` notification when its context is cancelled mid-turn and return the cancellation error, leaving the session usable.
A streaming generate SHALL emit each `agent_message_chunk` as a text stream event, each `tool_call` as a `tool_call` event and each completed or failed `tool_call_update` as a `tool_result` event, all marked as run by the agent; the in-process client SHALL stream through it instead of refusing, and SHALL NOT execute the agent's own tool calls.

#### Scenario: Host picks the permission option
- **WHEN** `onPermission` returns `reject` for a permission request offering reject and allow options
- **THEN** the agent receives outcome `selected` with optionId `reject`

#### Scenario: Host hook fails
- **WHEN** `onPermission` returns an error, or an empty id and no reject-kind option exists
- **THEN** the agent receives the first reject-kind option, or outcome `cancelled` when there is none

#### Scenario: No hook keeps today's answer
- **WHEN** `onPermission` is unset
- **THEN** the first allow-kind option is selected, as before

#### Scenario: Every update reaches onUpdate
- **WHEN** a turn interleaves thought chunks, tool calls and message chunks
- **THEN** `onUpdate` sees each one in order and the reply is still only the message-chunk text

#### Scenario: Cancelling a turn
- **WHEN** the context of an in-flight generate is cancelled
- **THEN** exactly one `session/cancel` notification (no id) is sent, the call returns the cancellation error, and the next turn on the same session is accepted

#### Scenario: Streaming an ACP turn through the client
- **WHEN** a client built on the streaming generate streams a turn with message chunks and an agent tool call
- **THEN** the stream yields the text deltas in order, one `tool_call` and one `tool_result` event for the agent's tool, and a `done` result whose text is the joined deltas and whose tool calls are empty
