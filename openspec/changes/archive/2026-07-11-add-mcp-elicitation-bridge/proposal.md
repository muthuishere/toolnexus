## Why

§10 unified every *tool-side* out-of-band ask onto one `Request`/`waitFor`: login, approval, input,
and now `question`. But there is a second place a human-input request originates that we don't handle
at all — **the MCP boundary**. When toolnexus (MCP-outbound, `SPEC.md §2`) calls a remote MCP
server's tool, that server can turn around and ask *us* for input via **`elicitation/create`** — a
reverse-request raised while our `tools/call` is still in flight. Today every port connects to MCP
servers with **empty client capabilities and no elicitation handler** (`js/src/mcp.ts:171`,
`python/.../mcp_source.py:236`, `golang/mcp.go:298`), so a server that elicits is refused: the tool
call fails and the human is never asked.

This is the same idea as §10, one layer down. MCP elicitation *is* a suspension:
`{message, requestedSchema}` → `{action: accept|decline|cancel, content}` maps almost exactly onto
`Request`/`Answer`, and form-vs-URL mode maps onto `kind:"input"` vs `kind:"authorization"`. Bridging
it onto the **same** `waitFor` means "every out-of-band ask, from a tool OR a remote MCP server, is one
`Request`" — the human sees the identical channel/browser prompt they already see for login and
approvals.

**We would be first.** The reference implementation, **opencode**, does not handle elicitation at all —
it is a deliberately commented-out TODO (`packages/opencode/src/mcp/index.ts:44-45`, issue #23066),
alongside sampling (#11948); it advertises only `roots` and answers `roots/list` trivially. Yet it
pins the same SDK we do (`@modelcontextprotocol/sdk` 1.29.0), which fully supports elicitation
including URL mode. So there is no upstream blueprint to port — we design from the MCP spec + the SDK,
and reuse our own `waitFor` (which is exactly opencode's own out-of-band idiom: block on a callback
Promise resolved elsewhere, as in its OAuth `waitForCallback`).

## What Changes

- **A new `ElicitationBridge` seam in the MCP-outbound source** (all five ports). On MCP client
  construction, register a handler for `elicitation/create` and advertise `capabilities.elicitation`
  **only when a `waitFor` is available** (mirroring how the Python/Go SDKs already gate the capability
  on handler presence — a `waitFor`-less host degrades cleanly instead of promising elicitation it
  can't satisfy). The handler: synthesize a §10 `Request` (`kind:"input"` for form mode / `"authorization"`
  for URL mode; `prompt=message`; `data.schema=requestedSchema`; `url` for URL mode), call the host
  `waitFor(request)`, and translate the `Answer` back into an `ElicitResult`.
- **Plumb `waitFor` into `loadMcp`.** Today the MCP source is constructed from config alone and cannot
  reach the client-loop's `waitFor`. Add the wiring so the bridge can call it. (New constructor param /
  option, not a public API break for existing callers — optional.)
- **Contract additions to §10 (the `suspension` capability)** that elicitation needs and that also
  stand alone as refinements deferred from earlier:
  - **R1 — three-state resolution.** Add optional `Answer.reason: "declined" | "cancelled" | "expired"`,
    populated when `ok == false`. MCP distinguishes `decline` (refused) from `cancel` (dismissed); our
    boolean `ok` flattens it. The loop still branches only on `ok` (non-breaking); the bridge maps
    `ok=true→accept`, `ok=false + reason:"declined"→decline`, else `→cancel`.
  - **R2 — optional `Request.schema`.** A restricted/flat JSON-Schema (MCP's `requestedSchema` shape)
    so a generic durable/channel host can render `input`/`question` without bespoke glue. The bridge
    validates `answer.data` against it before replying `accept`; on mismatch it downgrades to
    `decline`/`cancel`.
- **URL-mode re-entrancy.** `accept` in URL mode means "consent to open the URL," not "done" — the
  server may elicit again on the same `tools/call`. The bridge handles repeated elicitations within one
  tool execution (its own loop), distinct from §10's tool-retry guard.
- **Security (already pinned in §10 by the prior change):** credentials never go through form/`input`;
  they use URL/`authorization`. The bridge enforces this by mapping mode→kind, never surfacing
  `requestedSchema` secrets through the data channel.

## Impact

- **Affected spec:** `suspension` (new requirement: MCP elicitation → §10 via the bridge; + R1/R2
  contract additions). `SPEC.md §2` (MCP outbound gains the elicitation bridge + `waitFor` wiring) and
  `§10` (R1/R2). This moves the cross-language contract, so `SPEC.md` changes in this change.
- **Affected code:** the MCP-outbound source in all five ports (register handler, advertise capability,
  plumb `waitFor`), plus `Request`/`Answer` gaining optional `schema`/`reason`. No change to the tool
  loop or `question`.
- **Depends on:** `harden-suspension-layer` (the `suspension` capability + pending-not-error semantics)
  landing first — a transport-raised elicitation is a suspension and must not be miscounted either.
- **Not blocked by SDKs:** all five pinned MCP SDKs expose a client-side elicitation handler; all have
  URL mode except Python 1.28.0 (form-only, one minor bump away — URL mode may be a follow-up for the
  Python port only).
- **Risk:** the interception point is a transport callback fired *before* `execute()` returns — a
  genuinely new seam, not the tool-return path. It is the main design surface and is spelled out in
  design.md; this change stays **design-stage** until that seam is reviewed.
