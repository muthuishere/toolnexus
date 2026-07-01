## Why

The client's conversation memory was in-memory only (`run(history=...)` + a `Conversation` object) —
nothing persisted, no way to resume a thread by id, and the A2A serve side ran every incoming task as
a fresh stateless `run`. Users want durable, resumable conversations, and a served agent should
remember a peer's turns. Both are the same need: a pluggable place to keep transcripts.

## What Changes

- Add a **`ConversationStore`** provider — two methods, `get(id) -> messages | None` and
  `save(id, messages)` — with an **`InMemoryConversationStore`** shipped as the default. Set it once on
  the client: `create_client(..., store=?)` (default in-memory, per-client, process lifetime). Implement
  the interface for a file/db/redis provider to persist across processes.
- Add **`client.ask(prompt, { toolkit, id? })`**: with an `id`, load that id's transcript from the
  store, run the loop, save the updated transcript, and return the `RunResult` — so the next `ask` with
  the same `id` continues it. Without an `id`, a stateless one-shot (identical to `run`). Works for
  both `openai` and `anthropic` styles.
- **Wire A2A `serve` to the same store:** `serve` fulfils each `SendMessage` via
  `ask(text, { id: message.contextId })` when the message carries an A2A `contextId`, so a peer's turns
  are remembered through the client's `ConversationStore`; no `contextId` ⇒ stateless `run`. The A2A
  `Task` gains an optional `contextId`.
- Applies across **all five ports** (js/python/golang/java/csharp). Backward-compatible: no `id`/`store`
  ⇒ today's behavior. Minor release **0.3.0**.

## Capabilities

### New Capabilities
- `conversation-store`: the `ConversationStore` provider (get/save), in-memory default, `client.ask`
  (id ⇒ stateful, none ⇒ one-shot), and the A2A serve integration via `contextId`.

## Impact

- **SPEC.md §8** — "Conversation memory" updated: `ConversationStore` provider, `ask` semantics, and
  the serve→`contextId` integration.
- **js/python/golang/java/csharp** — client (`ask`, `ConversationStore`, `InMemoryConversationStore`,
  `store` option), toolkit `serve` (runTask threads `contextId`), serve (extracts `message.contextId`),
  A2A `Task.contextId`, per-port tests.
- No new dependencies. Backward-compatible.
