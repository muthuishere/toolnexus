## Why

`docs/adr/0001-rag-go-consumer-needs.md` records seven gaps rag_go (the Volentis RAG Go rewrite)
hit driving toolnexus from a multi-tenant platform, forcing four consumer-side workarounds (an
in-process sampling reverse-proxy, a toolkit-build watchdog, a wrapper-based tool filter, a shadow
conversation store). rag_go answered every design question (A1–A9 in the ADR). None of the seven
shipped in v0.8.0 (verified against the tag). This change lands all seven across all five ports so
rag_go can delete its workarounds.

## What Changes

Three clusters by code surface (each independently shippable; maps 1:1 onto a rag_go workaround):

**A — client request shaping (§8).** Additive `ClientOptions`:
- **Gap 1** `RequestParams` (map, shallow-merged into every LLM body, caller wins) + `BodyTransform`
  (hook receiving the assembled body last, before marshal) — kills the reverse-proxy. `messages`/
  `tools`/`stream` are forbidden in `RequestParams` (use `BodyTransform` to rewrite messages).
- **Gap 2** `HTTPClient`/injectable transport for the LLM path (retries included).
- **Gap 5** omit the `tools` (and openai `tool_choice`) keys when the effective tool list is empty —
  a five-port **behavior change** (JS verified to send them unconditionally today).
- Ordering contract: base body → `BeforeLLM` hook → `RequestParams` merge (caller wins) →
  `BodyTransform` → marshal → wire.

**B — MCP load lifecycle (§2).**
- **Gap 3** ctx-aware MCP load: a `LoadMcpWithContext` entry (`LoadMcp` unchanged, delegates with
  Background); `CreateToolkit`'s ctx propagates through connect/init/list; the SSE fallback `Start`
  gains the same `timeout` bound it lacks. Parent-ctx cancel → whole build errors `ctx.Err()`
  (A1); per-server timeout within budget → that server `failed`, build continues.
- **Gap 7** `ServerConfig.Tools` (name→bool) per-server tool allowlist — same semantics as the
  builtins/skills filter; keys are the server's ORIGINAL tool names (A2); empty = all (owner
  decision); never-mixed maps (A3). Kills the tool-filter wrapper.
- **Gap 6** `ListMcpTools` list-only inventory: connect, list, disconnect — `{tools: server→ToolInfo[]
  (unfiltered, original names), status}` — no toolkit. Kills the full-toolkit validate hack.

**C — conversation store accessor (§8).**
- **Gap 4** `Client.ConversationStore()` returns the client's store (the one passed in, else the
  default in-memory) so a host can read/rewind the transcript. Kills the shadow store. `Get`+`Save`
  suffice (A8).

All additive and backward-compatible except gap 5 (a deliberate wire behavior change, safe: providers
400 on empty `tools`). No existing signature changes; a caller passing none of the new options gets
byte-identical behavior.

## Capabilities

### New Capabilities
- `client-request-shaping`: the client SHALL expose declarative per-request body params, a body
  transform hook, an injectable HTTP transport, and SHALL omit empty tool keys.
- `mcp-load-lifecycle`: the MCP load SHALL honor the caller's cancellation/deadline, bound the SSE
  start, support a per-server tool allowlist, and offer a list-only inventory.

### Modified Capabilities
- `conversation-store`: add a host-facing accessor to the client's conversation store.

## Impact

- **Code, all five ports:** client body assembly (4 paths: run/stream × openai/anthropic) + options
  + HTTP transport injection + store accessor; MCP load ctx plumbing + `ServerConfig.Tools` filter +
  `ListMcpTools`.
- **Contract:** `SPEC.md §8` (request shaping, empty-tools omission, store accessor) and `§2`
  (ctx-aware load, SSE bound, per-server allowlist, list-only inventory).
- **Fixtures:** shared `examples/` MCP fixture with a `tools`-filtered server (gap 7). openai-path
  acceptance tests seeded from rag_go's real fixtures (`sampling_proxy_test.go`,
  `scaleway_messages_test.go`); anthropic path is synthetic parity.
- **Release:** ships as **0.9.0** (all seven together); rag_go strips workarounds incrementally.
