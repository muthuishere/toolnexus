# conversation-store Specification

## Purpose
TBD - created by archiving change add-conversation-store. Update Purpose after archive.
## Requirements
### Requirement: Pluggable conversation store with in-memory default

The client SHALL take a `ConversationStore` provider at construction (`create_client(..., store)`),
defaulting to a shipped `InMemoryConversationStore` (per-client, process lifetime). A
`ConversationStore` SHALL expose exactly two operations: `get(id)` returning the stored transcript or
none, and `save(id, messages)` persisting it. A host SHALL be able to supply a custom provider
(file/db/etc.) implementing those two operations.

#### Scenario: Default in-memory provider

- **WHEN** a client is created without a `store`
- **THEN** an in-memory `ConversationStore` is used

#### Scenario: Custom provider is used

- **WHEN** a client is created with a custom `store` and `ask` runs with an `id`
- **THEN** the store's `get(id)` is called before the run and `save(id, messages)` after it

### Requirement: ask is stateful by id, one-shot without

`client.ask(prompt, { toolkit, id? })` SHALL, when given an `id`, load that id's transcript from the
store, run the agent loop with it as history, save the updated `RunResult.messages` back under `id`,
and return the `RunResult` — so a subsequent `ask` with the same `id` continues the conversation.
Without an `id`, `ask` SHALL be a stateless one-shot identical to `run` and SHALL NOT touch the store.
Behavior SHALL be identical for `openai` and `anthropic` styles.

#### Scenario: Same id remembers across calls

- **WHEN** `ask` is called twice with the same `id`
- **THEN** the second call's run receives the first call's transcript as history
- **AND** a different `id` is an independent conversation

#### Scenario: No id is stateless

- **WHEN** `ask` is called without an `id`
- **THEN** it behaves exactly like `run` and the store is not read or written

### Requirement: A2A serve remembers by contextId

The inbound A2A `serve` (§7B) SHALL fulfil a `SendMessage` via `ask(text, { toolkit, id: contextId })`
when the message carries an A2A `contextId`, so a peer's turns are remembered through the client's
`ConversationStore`. When no `contextId` is present it SHALL fulfil via a stateless `run`. The
submitted `Task` SHALL carry the `contextId` when present.

#### Scenario: Served turns remember by contextId

- **WHEN** a peer sends two `SendMessage` requests with the same `contextId`
- **THEN** the second task's fulfilment sees the first turn's transcript (remembered via the store)
- **AND** a different `contextId` is an independent conversation

