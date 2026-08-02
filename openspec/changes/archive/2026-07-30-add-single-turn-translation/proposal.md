## Why

`SPEC.md §0` item 7 pins the format adapters as **schema only**: `ToOpenAI` /
`ToAnthropic` / `ToGemini` translate tool declarations **outbound**, and nothing in the
library reads a provider's tool calls back **inbound**. Every user of those public
functions hits the same wall — they can tell a provider about their tools, but not
receive the calls it makes.

That asymmetry means toolnexus serves exactly one posture well ("the library executes
tools in a loop") and the majority posture — "I want provider-portable tool calling, but
**I** execute the tools", the premise of the entire OpenAI function-calling protocol —
not at all. A caller who owns its own conversation and dispatch has no path through the
library today except the agent loop, which forces suspension semantics and state onto a
problem that has neither.

ADR-0011 (accepted), following ADR-0010 and spikes 0001/0002.

## What Changes

- **New single-turn translation entry point** — `translate(request) -> result`
  (idiomatic naming per port). Exactly **one** provider call. No agent loop, no tool
  execution, no conversation state; every call is self-contained.
- **Request takes OpenAI shapes verbatim** — `messages`, `tools`, `toolChoice`, plus
  optional `system` and `maxTokens`. A caller never builds provider-native payloads;
  provider knowledge stays in the library.
- **Request also accepts an ordinary toolkit** — MCP tools, skills, native functions,
  A2A agents, builtins — which is **declared and never executed**. This is what makes
  the capability general rather than proxy-shaped: it is the inbound half of the
  adapters. Composes with the OpenAI `tools` array.
- **Inbound translation preserves tool structure** that a text flattening destroys:
  assistant `tool_calls` → provider `tool_use` blocks (arguments re-parsed to objects);
  `tool`-role results → `tool_result` blocks keyed by `tool_call_id`, **merged into one
  user turn** when consecutive; `system` messages hoisted to the provider's separate
  field.
- **Outbound translation returns OpenAI shapes** — `text`, `toolCalls` with
  `arguments` as a JSON **string** (the wire format, so a caller can echo it
  byte-for-byte), `finishReason`, `usage`, `model`, and the raw decoded response.
- **`finishReason` mapping** — any turn emitting a tool call is `"tool_calls"`;
  otherwise the provider stop reason maps onto `"stop"` / `"length"` /
  `"content_filter"`.
- **Reuses the loop's infrastructure** — retries/backoff, request-param merging, the LLM
  observability event. `beforeLLM` / `afterLLM` fire once; tool hooks do not, because no
  tool runs.
- Not breaking, and **nothing existing changes**: this is a new entry point that touches
  neither the agent loop nor §10.

## Capabilities

### New Capabilities
- `tool-translation`: single-turn, loop-free translation between OpenAI shapes and a
  provider's native wire format, in both directions — declarations and messages out,
  tool calls and finish reason back — with nothing executed.

### Modified Capabilities

None. The agent loop, the suspension layer (§10) and the adapters keep their current
requirements; this adds a parallel entry point beside them.

## Impact

- New `SPEC.md` §11, and a new item in the §0 conformance contract.
- All six ports — `js/`, `python/`, `golang/`, `java/`, `csharp/`, `elixir/`. Per the
  prime directive this lands everywhere or it is not done. `golang/` is implemented.
- No change to the adapters, the toolkit, `ConversationStore`, or the loop.
- Scopes the sibling change `add-tool-relay-mode`: relay + durable resume remains the
  right machinery for **proxy-managed memory** (where toolnexus owns the conversation and
  the caller sends only the new message). Two postures, two mechanisms.
- Streaming translation is a deliberate follow-up, not part of this change.
