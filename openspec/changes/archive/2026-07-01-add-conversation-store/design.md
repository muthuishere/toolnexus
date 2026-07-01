## Context

Conversation memory was in-memory only; A2A serve ran each task statelessly. Both want a pluggable
transcript store. This reuses the exact adapter shape already established by the A2A `TaskStore`
(get/save, in-memory default, custom for file/db).

## Goals / Non-Goals

**Goals:** durable, resumable conversations via a `ConversationStore` provider + `ask(id?)`; a served
agent that remembers by A2A `contextId`; byte-parity across five ports; backward-compatible.
**Non-Goals:** a shipped file/db store (users bring their own — in-memory is the default and the
extension point is the two-method interface); changing `run`/`Conversation`; auth.

## Decisions

**Provider on the client, `id` on the call.** `create_client(..., store)` sets the provider once
(default in-memory); `ask(prompt, { id })` opts a call into persistence. Rationale: the store is a
client-level concern, the id is a per-conversation handle — mirrors how a DB connection vs a row key
relate. Simpler than a per-conversation object.

**`ask` = `run` + load/save.** With id: `history = store.get(id) ?? []` → `run(history=…)` →
`store.save(id, result.messages)`. Without id: just `run`. Keeps `run` the stateless primitive and
adds one thin stateful method — no new object, no duplicated loop.

**Serve reuses the same store via `contextId`.** A2A messages carry a `contextId` grouping a peer's
turns; `serve` threads it into `ask(id=contextId)`, so one provider powers both your own conversations
and the agent conversations you serve. `Task` gains an optional `contextId`.

## Risks / Trade-offs

- [Unbounded in-memory growth] → the default store is process-lifetime and per-client; hosts needing
  eviction/TTL supply their own provider (the whole point of the interface).
- [Cross-port history/message shape] → `ask` stores `RunResult.messages` verbatim and passes it back as
  `history`; no reshaping, so it round-trips identically in every port (covered by the message-count tests).
