# Design — canonical transcript

## The shape

Four message kinds cover everything the loop produces today. Nothing else is needed, and adding more
would be speculative.

| kind | carries |
|---|---|
| `system` | instruction text (one, conceptually pinned to the head of the transcript) |
| `user` | prompt text |
| `assistant` | optional text + zero or more `toolCalls` (`id`, `name`, `arguments` as a parsed object) |
| `toolResult` | `callId`, `output` text, `isError` |

`arguments` is held **parsed**, not as a JSON string. OpenAI sends a string, Anthropic sends an
object; storing the string would keep an OpenAI-ism in the canonical form and force the Anthropic
renderer to re-parse what it never had to serialize.

Rendering is mechanical and already exists, split across the two branches in each port:

- **openai** — `system` becomes a `{role:"system"}` entry inside `messages`; `assistant` becomes
  `{role:"assistant", content, tool_calls:[{id,type:"function",function:{name,arguments:<stringified>}}]}`;
  `toolResult` becomes `{role:"tool", tool_call_id, content}`.
- **anthropic** — `system` is hoisted to the top-level `system` field, out of `messages`;
  `assistant` becomes content blocks (`{type:"text"}` + `{type:"tool_use", id, name, input}`);
  `toolResult` becomes a `{type:"tool_result", tool_use_id, content, is_error}` block inside a
  **`user`** message, with consecutive tool results merged into one user message as the loop does today.

Parsing back is the same table read the other way.

## Decisions

**D1 — Existing persisted transcripts: format marker + read-time upgrade.**
Stored payloads gain a version marker. Reading an unmarked payload means it was written by an older
release in a wire dialect; the client upgrades it in memory using the parser for **its own configured
style**, and saves it back canonical on the next `save`. This is right for the overwhelmingly common
case (a host does not change `style` between releases) and is exactly wrong for a host that persisted
under one style and upgrades while switching to the other — which is already broken today and cannot
be silently rescued, because an unmarked transcript carries no evidence of its origin. So: when an
unmarked payload contains fields that are unambiguously *foreign* to the configured style (an
OpenAI `tool_calls` key or `role:"tool"` under `anthropic`; an Anthropic `tool_use` block under
`openai`), the client SHALL fail with a clear error naming the mismatch rather than forward it. That
converts today's silent `400` into a diagnosable one, which is most of the value of this change.

Rejected: a flag day (breaks hosts with durable stores), and sniffing the dialect per message
(guesswork that would sometimes succeed, teaching hosts to rely on it).

**D2 — Canonical is what `RunResult.messages` exposes.**
The alternative — keep exposing wire messages and canonicalize only inside the store — was tempting as
the smaller diff, but it splits the representation in two, leaves `run(prompt, {history})` (the
stateless primitive) still style-locked, and leaves compaction operating on wire shapes. One
representation or none.

**D3 — `system` stays a message kind, not a side field.**
It is a message in the OpenAI dialect and a top-level field in the Anthropic one; the canonical form
must hold it either way, and holding it as a kind lets compaction and inspection treat the transcript
as one ordered list. The renderer decides where it lands. A transcript SHALL hold at most one
`system` message and it SHALL be first; the client already derives it from the toolkit per call, so
this is an invariant to assert, not a feature to build.

**D4 — Unknown provider content is preserved, not dropped.**
Content blocks the canonical shape has no kind for (thinking blocks, citations, provider-specific
extras) are retained opaquely on the message so a same-style round trip stays lossless. They are
**not** rendered when the target style differs from the one that produced them — a cross-style resume
drops them, which is the only honest option. This keeps D5's byte-identity assertion achievable.

**D5 — The gate: same-style round trip is byte-identical.**
For every port, a transcript built by the loop, rendered, stored, loaded, and rendered again SHALL
produce the same request body bytes as today's implementation. This is the regression guard for a
change that touches the hot path in seven languages, and it is the first task.

**D6 — Naming is per-port idiomatic, the wire form is not.**
The in-memory type names follow each language (`Message`/`ToolCall` records in Java, structs in Go,
maps in Clojure/Elixir, dataclasses in Python). The **serialized** form a `ConversationStore` writes
is fixed by `SPEC.md`, because a store is a shared substrate: a transcript written by the Go port
must be resumable by the Python one. Field names in the persisted JSON are therefore normative.

## Open question

**Q1 — Does `RunResult.messages` need a compatibility accessor?** A host that today feeds
`RunResult.messages` straight into its own OpenAI SDK call would break. A `renderFor(style)` helper
would cover them cheaply. Deferred to review: adding it is easy, removing it later is not, and no
consumer has asked. ADR-0001's consumer (`rag_go`) uses the loop, not raw messages.
