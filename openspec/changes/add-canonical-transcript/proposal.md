# Canonical transcript — one internal message shape, rendered per style at call time

## Why

A persisted conversation is silently style-locked today. The client stores whatever wire dialect the
provider happened to speak, so a transcript written under `style:"openai"` and resumed under
`style:"anthropic"` is forwarded verbatim to the wrong API. There is no error and no warning.

A spike against the built JS client reproduces it exactly. Under `style:"openai"` a conversation that
calls one tool is stored as:

```
{"role":"user","content":"use the tool"}
{"role":"assistant","content":null,"tool_calls":[{"id":"tc_1","type":"function","function":{"name":"echo","arguments":"{\"v\":1}"}}]}
{"role":"tool","tool_call_id":"tc_1","content":"echoed 1"}
{"role":"assistant","content":"hello from openai-up"}
```

Resuming the same store id under `style:"anthropic"` sends those four messages **unchanged** to the
Anthropic endpoint. Two of them are invalid there: `tool_calls` is not a recognised field, and
`role:"tool"` is not an allowed role (Anthropic carries tool results as a `tool_result` block inside a
`user` message). A real provider answers `400`.

The failure is latent, which is what makes it worth fixing rather than documenting. A text-only
transcript survives the same round trip — `{role, content:"..."}` is coincidentally valid in both
dialects — so the bug is invisible until the agent actually calls a tool, which for this library is
the normal case. Nothing in the client, the store, or `SPEC.md` records which style produced a stored
transcript, so nothing can detect the mismatch either.

**A named consumer is squarely in the blast radius.** routsi uses toolnexus "as its
OpenAI→Anthropic/Gemini translator" (`docs/adr/0010-relay-mode-declaration-only-tools.md:15`) and
takes §10's durable path, which means it persists transcripts and replays history. Cross-style
transcript handling is not a hypothetical for that consumer; it is the product.

Three separate capabilities already assume messages are portable and are wrong for the same reason:
`ask` + `ConversationStore` (§ Conversation memory) promises resume "works identically for `openai`
and `anthropic`"; A2A `serve` (§7B) resumes by `contextId` through that same store; and compaction
(§7F) is specified as a pure `messages → messages` helper, which is only true of a dialect-free
representation.

## What Changes

- The loop SHALL keep conversation history in **one internal, provider-neutral message
  representation**, rendered into the provider's wire dialect at call time and parsed back from the
  response — instead of accumulating wire-shaped messages.
- `ConversationStore.save` SHALL persist that canonical representation, and `RunResult.messages` SHALL
  expose it, so a stored conversation is portable across styles and across ports.
- Resuming a conversation under a different `style` SHALL produce a request valid for that provider,
  including transcripts containing tool calls and tool results.
- The canonical form SHALL carry a **balance invariant**: every assistant tool call has exactly one
  corresponding tool result. This absorbs a second, independently filed transcript-validity defect —
  on a durable halt the assistant message keeps all N `tool_use` blocks while only the **first**
  `tool_result` is written, so "that saved transcript is not directly replayable to the provider"
  (`docs/adr/0010-relay-mode-declaration-only-tools.md`, *Observation (filed, not fixed)*). It bites
  exactly the host that replays history — routsi's shape. Same class of bug, same layer, and cheaper
  to fix once here than twice.
- Stored transcripts SHALL carry a format marker so an older, wire-shaped transcript is recognised and
  read back rather than misinterpreted (see `design.md` for the migration decision).
- **Breaking in one narrow respect**: hosts that inspect `RunResult.messages` or a persisted store
  payload and depend on its OpenAI/Anthropic wire shape will see the canonical shape instead. The
  observable *conversation behaviour* is unchanged — a same-style resume produces the same request
  bytes it does today.

Not included: any change to the provider adapters' outbound tool-schema mapping (§5), to the streaming
event contract, or to the shape of `Tool`/`ToolResult`. This is the message channel only.

## Capabilities

### Modified Capabilities

- `conversation-store`: the "pluggable conversation store" and "ask is stateful by id" requirements
  gain the portability guarantee they already imply — what is stored is canonical, and a resume under
  a different style renders correctly rather than forwarding a foreign dialect. "A2A serve remembers
  by contextId" inherits this and is not separately modified.

### Unchanged but affected

- `context-compaction` (§7F) becomes genuinely style-independent, as its spec already claims. Its
  requirements do not change; its implementation now operates on canonical messages in every port.

## Impact

- **Code**: the message-accumulation and body-construction paths in all seven ports. In `js/` that is
  `js/src/client.ts` — the two style branches that build `messages` (`:815` hoists `system` out and
  posts `tool_result` blocks at `:869-872`; `:889-891` pushes a `system` role into the array instead),
  the response parsing that appends assistant turns, and the store read/write around `ask`. The
  equivalents are `python/src/toolnexus/client.py`, `golang/client.go`,
  `java/.../LlmClient.java`, `csharp/src/Toolnexus/LlmClient.cs`, `elixir/lib/toolnexus/client.ex`,
  `clojure/src/toolnexus/client.cljc`, plus each port's compaction module
  (e.g. `golang/agents/compaction.go`, `python/src/toolnexus/agents/compaction.py`).
- **Contract**: `SPEC.md § Conversation memory` — which today specifies `get`/`save` and cross-style
  resume without ever saying what a message *is*. The canonical shape must be pinned there, because
  a transcript written by one port must be readable by another.
- **Fixtures**: a shared cross-style resume fixture under `examples/`, exercising a transcript that
  contains a tool call — the case the spike proved broken.
- **Migration**: transcripts already persisted by hosts in file/db/redis stores. Addressed by the
  format marker above; `design.md` D1 records the decision.
- **Downstream sequencing**: `docs/adr/0015-compaction-as-a-logged-transaction.md` rejects an
  append-only transcript specifically because it would be "the *semantics* change over the same
  lines, in the same seven ports, at the same time" as this change, and sequences its
  `add-compaction-observability` follow-up to land **after** this one archives. ADR 0013 (model-free
  tool-result pruning) overlaps the same compaction modules. This change therefore has downstream
  dependents and should not be left in flight indefinitely.
- **Coordination**: ADR 0015 decision rule 3 asks `ConversationStore.save` to be documented as
  atomic per id. Since this change alters what is stored, that sentence is cheaper to land here
  than separately; carried as a task rather than absorbed into this change's spec deltas.
- **Risk**: this touches the single hottest path in the library in seven languages. Mitigated by the
  invariant that a same-style round trip must produce byte-identical request bodies to today, which
  is directly assertable and is the first task in `tasks.md`.
