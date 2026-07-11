# Design — MCP elicitation → §10 `Request` (transport-side bridge)

## The shape (from the spike, all cited in the change history)

MCP **elicitation** is a server→client reverse-request raised *during* our `tools/call`:
- Request `elicitation/create { message, requestedSchema, mode?: "form"|"url", url? }`. `requestedSchema`
  is a restricted flat-primitive JSON-Schema subset (string/number/boolean/enum only).
- Response `{ action: "accept" | "decline" | "cancel", content? }`. `accept` carries `content` (form) or
  nothing (URL). `decline` = refused; `cancel` = dismissed/timed-out.
- Capability negotiation: the client advertises `capabilities.elicitation` at init; URL mode requires
  `elicitation: { url: {} }`, empty `{}` = form only.

Maps onto §10:

| MCP elicitation | toolnexus §10 |
|---|---|
| `message` | `Request.prompt` |
| `requestedSchema` | `Request.data.schema` (R2) |
| `mode:"form"` | `Request.kind:"input"` |
| `mode:"url"` + `url` | `Request.kind:"authorization"` + `Request.url` |
| `action:"accept"` + `content` | `Answer.ok=true`, `Answer.data=content` |
| `action:"decline"` | `Answer.ok=false`, `Answer.reason:"declined"` (R1) |
| `action:"cancel"` | `Answer.ok=false`, `Answer.reason:"cancelled"` (R1) |

## The hard part — a transport callback, NOT a tool return

§10 today assumes a suspension originates from a tool **returning** `ToolResult{metadata.pending}`; the
loop inspects the return value and retries the *same tool*. Elicitation does not fit that:

1. **It fires inside the SDK while `callTool(...)` is still awaiting** — before our `execute` has
   produced any `ToolResult`. There is nothing to hang `metadata.pending` on. The existing loop rule
   cannot see it.
2. **The interception must live in the elicitation handler we register on the MCP `Client`** — inside
   the MCP source adapter, a place that today has no reference to the host `waitFor`.
3. **It does NOT re-execute the tool.** Once the handler returns an `ElicitResult`, the *same in-flight*
   `callTool` resumes; §10's "re-execute with `ctx.answer`" retry does not apply here.

So the seam is: an **`ElicitationBridge`** constructed with the host `waitFor`, registered as the
client's elicitation handler, that runs `create → Request → waitFor → Answer → ElicitResult` **inline**,
blocking the SDK's reverse-request until the `Answer` arrives. This is exactly opencode's own idiom for
the interactive half (block on a callback-Promise resolved out-of-band, as in its OAuth
`waitForCallback`) — opencode just never wired it to an elicitation handler.

### Wiring `waitFor` into the MCP source

`loadMcp(config)` today knows only config. Add an optional carrier so the source can reach `waitFor`:
- Option A (preferred): `loadMcp(config, { waitFor? })` — the toolkit/client passes its `waitFor` down
  when assembling MCP sources. Bridge is created iff `waitFor` is present.
- The client capability `elicitation` is advertised **iff `waitFor` is present** — a durable host with
  no in-process `waitFor` does not claim elicitation (a server then won't elicit; if a misbehaving one
  does, the SDK rejects cleanly, exactly as today).

### The durable (no-`waitFor`) case

Without `waitFor`, the bridge cannot synchronously answer the SDK's reverse-request, and MCP has no
"suspend the whole JSON-RPC call and resume in another process" primitive. So in the durable case we
**do not advertise elicitation** — the remote tool proceeds without it (or the server degrades per its
own rules). Genuinely durable elicitation-over-MCP is out of scope (it would require persisting an open
transport, which MCP does not support). This is called out as a known limitation.

## Contract additions (extend the `suspension` capability)

- **R1 `Answer.reason?`** — `"declined" | "cancelled" | "expired"`, present only when `ok == false`. The
  loop rule (SPEC §10) is unchanged (it branches on `ok`); `reason` is advisory. Byte-identical field
  across ports (like the rest of `Answer`).
- **R2 `Request.schema?`** — optional restricted JSON-Schema (flat primitives), the MCP `requestedSchema`
  shape. Carried in `Request` (or under `data.schema` — decide below). A host/bridge MAY validate
  `answer.data` against it.

## Contract gaps to pin (open, resolve before implementing)

1. **`schema` location.** `Request.schema` as a first-class optional field, or `Request.data.schema`?
   First-class is cleaner for generic renderers; `data.schema` avoids touching the `Request` shape. The
   `question` builtin already uses `data.questions` — leaning `Request.data.schema` for consistency and
   zero `Request`-shape change. DECIDE.
2. **`ok=false` default action.** When `reason` is absent, does the bridge map `ok=false → decline` or
   `→ cancel`? MCP treats them differently (decline = final, cancel = retry-later). Leaning `→ cancel`
   (safest: server may retry) unless `reason:"declined"`. DECIDE + pin in SPEC.
3. **`content` validation ownership.** Does the bridge validate `answer.data` against `requestedSchema`
   before replying `accept` (recommended — downgrade to `cancel` on mismatch), or trust the host? Pin.
4. **Python URL mode.** Python SDK 1.28.0 is form-only. Ship form-mode parity everywhere, and either bump
   Python or ship Python URL mode as a follow-up. DECIDE scope.
5. **Per-port SDK API differences.** JS `client.setRequestHandler(ElicitRequestSchema, fn)`; Py
   `ClientSession(elicitation_callback=…)`; Go `client.WithElicitationHandler(h)`; Java
   `.capabilities(...elicitation(true,true)).elicitation(h).urlElicitation(h)`; C#
   `McpClientOptions.Handlers.ElicitationHandler`. The bridge logic is shared; only registration differs.

## Testing strategy (per port, hermetic)

Stand up an in-process/stdio **stub MCP server that elicits** during a tool call (each SDK has an
in-memory transport or a tiny server harness). Assert: (a) form-mode elicit → our `waitFor` is called
with a `kind:"input"` Request carrying the schema, and `accept` returns the answer to the server which
completes the tool; (b) `waitFor` returning `ok=false` → `decline`/`cancel` per the pinned rule and the
tool degrades gracefully; (c) URL mode → `kind:"authorization"` + `url`, `accept` with no content; (d)
no-`waitFor` → capability not advertised, server does not elicit. This is the parity lock.

## Sequencing

Lands **after** `harden-suspension-layer` (needs the `suspension` capability + pending-not-error
semantics). Ships form mode across all five first; Python URL mode is the one likely follow-up.
