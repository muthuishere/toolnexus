## Why

A caller that uses toolnexus purely as a **translator** — routsi proxies OpenAI-shaped
requests to Anthropic/Gemini — needs standard OpenAI function calling to work: the model
emits a tool call, and the **HTTP client, not toolnexus, executes it**. Today every
`Tool` is an executor, so a proxy that must never run anything host-side has to drop all
tools (`Builtins:false` today drops client-declared schemas too), and an Anthropic model
behind the translator can never do function calling — while the same request through raw
passthrough can. Issue #37, ADR-0010 (accepted), spike 0001.

The spike found the loop **already** has a no-execute-and-return path — the §10
suspend/resume primitive. So this is a declaration-only constructor plus a pinned wire
shape, not a second agent-loop mode. Doing it any other way would create the second
suspension mechanism `SPEC.md` explicitly forbids.

## What Changes

- **New `RelayTool(name, description, schema)` constructor** — a declaration-only tool.
  On first execution it returns a §10 suspension; on retry-with-answer it returns the
  caller's supplied output as its `ToolResult`. No change to the loop's execute branch.
- **Pinned relay wire shape on `Request`** — reserved `kind: "tool_call"`, with
  `data.calls` an array of `{id, name, input}` carrying **every** relay `tool_use` block
  of the turn (ADR-0010 fork F2-a). Byte-identical keys in all six ports, like the rest
  of §10. This preserves §10's hardened first-in-order halt rule exactly while matching
  OpenAI's `tool_calls` array one-to-one.
- **Answer-carrying resume entry point** — `RunWithAnswer` / `Ask(…, answer)` (naming
  idiomatic per port), so a durable host with no `waitFor` can resume a persisted run
  (fork F1-a). It MUST fill **every** outstanding `tool_result` slot of the halted
  assistant turn, not only the halted tool's.
- **`Answer.data` carries the caller's tool output** plus an error flag, so a relayed
  tool failure reaches the model as a normal error `tool_result`.
- **Relay suspensions are not tool errors** — inherits §10's existing rule
  (`isError:false`, `pending:true` on the observability event, no `afterTool` failure
  path). Relaying is normal operation for a proxy and must not move error-rate metrics
  or trip circuit breakers.
- **Collision guard** — a relay tool whose name collides with a builtin tool name is
  rejected at toolkit construction, so a future toolkit change cannot be socially
  engineered into executing something host-side.
- **Absent relay tools, behavior is byte-identical** in every port. No relay tool
  declared ⇒ not one observable difference.
- Not breaking. `Builtins:false` semantics unchanged.

## Capabilities

### New Capabilities
- `tool-relay`: declaration-only (relay) tools — the model's tool call is surfaced to
  the caller as structured data and the caller's result is fed back, with nothing
  executed host-side; the collision guard; and the byte-identical-when-absent rule.

### Modified Capabilities
- `suspension`: the `Request` wire shape gains the reserved `kind:"tool_call"` and the
  pinned `data.calls` array (all N relay calls of the turn ride the single
  first-in-order suspension); and the durable path gains an answer-carrying resume entry
  point that fills every outstanding `tool_result` slot of the halted turn. The
  first-in-order halt rule and the "suspension is never a tool error" rule are
  unchanged — relay inherits them.

## Impact

- `SPEC.md` §10 (suspension wire shape + durable resume) and §8 (client API surface:
  the new resume entry point). The §0 conformance contract gains relay.
- All six ports — `js/`, `python/`, `golang/`, `java/`, `csharp/`, `elixir/` — both the
  streaming and non-streaming loops. Per the prime directive this lands everywhere or
  it is not done.
- `ConversationStore` needs **no** change: it already round-trips `tool_use`/
  `tool_result` pairs structurally (raw provider block maps, persisted verbatim). This
  closes routsi ADR-010's open question.
- Format adapters need **no** change: `ToOpenAI`/`ToAnthropic`/`ToGemini` already emit
  each provider's native declaration shape for any `Tool`.
- Consumer unblocked: routsi ADR-010 moves from Proposed once this ships.
- Filed, out of scope: on the durable path two concurrent **non-relay** suspensions
  leave the assistant turn with N `tool_use` blocks and one `tool_result` — an open §10
  defect that wants its own change (ADR-0010 "Observation").
