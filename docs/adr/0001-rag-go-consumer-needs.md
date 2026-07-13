# ADR 0001 — rag_go consumer needs: seven gaps found in production use of the Go port

- **Status:** Proposed (requirements only — no implementation in this ADR)
- **Date:** 2026-07-13
- **Driver:** rag_go (the Go rewrite of the Volentis RAG API) adopted toolnexus/golang as its
  agent engine. Seven gaps forced consumer-side workarounds — an in-process sampling reverse
  proxy, a toolkit-build watchdog, a shadow conversation store, a wrapper-based tool filter,
  and a zero-tools degrade path. Each gap below records the production scenario, the exact
  additive Go API to close it, the acceptance tests the implementation should carry, and the
  cross-language parity debt.
- **Process note:** per the repo's prime directive, the implementation of each gap should flow
  through an OpenSpec change (`/opsx:propose`) with `SPEC.md` deltas where the cross-language
  contract moves (gaps 1, 3, 4, 5, 6, 7 all move it; gap 2 is per-port idiom). This ADR is the
  consumer-driven requirements record and priority order for those changes.

## Context

rag_go builds one `Toolkit` + `Client` per (agent, request) against OpenAI-style endpoints
(Scaleway generative APIs, OpenRouter) and per-agent MCP servers whose enabled-tool lists live
in the platform's `mcp_servers.agenttools` rows. Everything below was hit on that live path.
All proposals are **additive and backward-compatible**: a consumer passing none of the new
options gets byte-identical behavior to today.

Priority order for rag_go (highest impact first): **#1 sampling params → #3 ctx-aware MCP load
→ #7 per-server tool allowlist → #5 omit empty tools → #6 list-only mode → #4 store accessor
→ #2 injectable HTTP client.**

---

## Gap 1 — Sampling params and body transform are not injectable (priority 1)

### Motivation (production case)

`ClientOptions` exposes no way to set `temperature`, `top_p`, `frequency_penalty`,
`presence_penalty`, `max_tokens`, `reasoning_effort`, or provider-specific extra body fields
(e.g. `chat_template_kwargs: {"enable_thinking": false}` for GLM on Scaleway). The request
body is assembled inside `runOpenAI` / `runAnthropic` / `streamOpenAI` / `streamAnthropic`
(`golang/client.go`) from a fixed key set, and the `BeforeLLM` hook can only replace
`messages`/`tools` — not arbitrary body keys. rag_go had to run an **in-process reverse
proxy** (`ClientOptions.BaseURL` pointed at a localhost listener) that intercepts every
`POST /chat/completions`, JSON-decodes the body, injects the per-agent sampling params and GLM
`chat_template_kwargs`, rewrites Scaleway-incompatible tool-role messages, and forwards to the
real upstream — for both streaming and non-streaming. This is the single biggest workaround.

### Proposed API (additive)

```go
// ClientOptions additions (golang/client.go)

// RequestParams are extra top-level keys shallow-merged into EVERY LLM request
// body, after the client builds its own keys — a RequestParams key WINS on
// collision (so max_tokens here overrides the anthropic default 4096; setting
// "stream"/"messages"/"tools" is allowed but at the caller's own risk and
// documented as such). Applied on all four paths: run + stream, openai +
// anthropic. Nil ⇒ no change (today's body, byte-identical).
// e.g. {"temperature": 0.2, "top_p": 0.9, "max_tokens": 2048,
//       "reasoning_effort": "none",
//       "chat_template_kwargs": map[string]any{"enable_thinking": false}}
RequestParams map[string]any

// BodyTransform, when non-nil, receives the fully assembled request body
// (after RequestParams merge) just before marshal+send and returns the body to
// send — the escape hatch for provider-specific rewriting (e.g. Scaleway
// tool-role message preprocessing) without a proxy. Returning nil is treated
// as "unchanged". Runs on every LLM call, all four paths. The map passed in is
// owned by the callee (it may mutate and return it).
BodyTransform func(body map[string]any) map[string]any
```

Ordering contract (must be pinned in the spec delta): **base body → `BeforeLLM` hook
(messages/tools) → `RequestParams` merge (caller wins) → `BodyTransform` → marshal → wire.**

Deliberate design choice: both a declarative map (covers 95% of cases, portable to the other
ports as plain dict/map) **and** the transform hook (covers the tool-role rewriting rag_go
does, which a flat merge cannot express). Typed convenience fields (`Temperature *float64`,
…) are intentionally **not** proposed: they duplicate the map, need nil-able types in Go, and
grow forever; the map is the provider-superset shape.

### Acceptance tests (regression per path)

1. `httptest` upstream capturing the decoded body: `RequestParams{"temperature":0.42,
   "chat_template_kwargs":{...}}` appears at the top level on **non-streaming openai**,
   **streaming openai**, **non-streaming anthropic**, **streaming anthropic**.
2. Caller-wins merge: `RequestParams{"max_tokens": 999}` on the anthropic path produces
   `"max_tokens": 999` (not 4096).
3. `BodyTransform` receives the post-merge body (assert it sees the RequestParams key) and its
   return value is what hits the wire (transform drops a key → key absent upstream).
4. Back-compat: with both options nil, the captured body is byte-identical (same key set) to
   the pre-change body.

### Parity

Needed in **js, python, java, csharp** (all four: `requestParams` + `bodyTransform` in their
ClientOptions; SPEC.md §8 gains the ordering contract). JS is the reference — port there first
per repo convention; this ADR records the debt, it does not discharge it.

---

## Gap 2 — HTTP client is not injectable (priority 7)

### Motivation (production case)

`CreateClient` hardcodes `http.DefaultClient` (`golang/client.go`). rag_go could not attach a
custom `*http.Client` / `RoundTripper` for the observability, per-request timeouts, and proxy
routing it needed — one more reason the reverse proxy existed (with gap 1 closed, this is the
residual need).

### Proposed API (additive)

```go
// ClientOptions addition

// HTTPClient overrides the HTTP client used for LLM requests (run + stream,
// retries included). Nil ⇒ http.DefaultClient (today's behavior). Scope is the
// LLM path only: MCP transports use the MCP SDK's own clients and are out of
// scope here.
HTTPClient *http.Client
```

`CreateClient` uses `opts.HTTPClient` when non-nil for the `Client.http` field. No other
change.

### Acceptance tests

1. A custom `http.Client` whose `Transport` records requests: `Run` and `Stream` both route
   through it (recorder saw the calls; `http.DefaultClient` untouched).
2. Nil ⇒ `http.DefaultClient` (existing tests keep passing unmodified).

### Parity

Per-port idiom, all four: JS = injectable `fetch` implementation, Python = injectable
`httpx.AsyncClient`, Java = injectable `HttpClient`, C# = injectable `HttpClient`/handler.
SPEC.md needs only a one-line "the HTTP transport is host-injectable" note (no wire behavior
change).

---

## Gap 3 — `LoadMcp` ignores the caller's context; SSE start is unbounded (priority 2)

### Motivation (production case)

`CreateToolkit(ctx, opts)` takes a `ctx` but the MCP path never sees it: `connectServer`,
`initClient`, `listTools` all derive from `context.Background()`, and the transport `Start`
calls (`stdioTransport.Start`, `httpClient.Start`, `sseClient.Start` in `golang/mcp.go`) run
on `context.Background()` with **no timeout at all** on the SSE fallback. A hung MCP endpoint
(TCP accepts, never responds) blocked rag_go's toolkit construction unboundedly. rag_go's
workaround is a **toolkit-build watchdog**: wrap `CreateToolkit` in a goroutine, race it
against a timer, abandon the goroutine on timeout — which leaks the goroutine and the child
process/connection, because cancellation cannot reach the underlying work.

### Proposed API (additive + one behavior fix)

```go
// New exported entry point (LoadMcp keeps its exact signature and delegates
// with context.Background()):
func LoadMcpWithContext(ctx context.Context, input any,
    waitFor ...func(Request) (Answer, error)) (*McpSource, error)
```

Behavior requirements:

- `CreateToolkit(ctx, opts)` passes its `ctx` into the MCP load (this is the fix the existing
  signature already promises; no signature change).
- Every phase — transport `Start` (stdio, streamable-HTTP, **and the SSE fallback**),
  `Initialize`, `ListTools` — derives its per-phase timeout context (`cfg.timeout()`, default
  30 s, exactly the bound init/list already have) **from the caller's ctx**, so parent
  cancellation/deadline propagates through all of them. The SSE `Start` gains the same
  `cfg.timeout()` bound it currently lacks.
- Cancellation mid-load: in-flight servers are closed (stdio child killed, connections
  released), and `LoadMcpWithContext` returns `ctx.Err()` promptly. Per-server failure
  isolation is unchanged (one bad server ⇒ `StatusFailed`, never an error for the whole load).

### Acceptance tests (regression: hanging-server cancel)

1. **Hanging-server ctx cancel:** an `httptest` server that accepts and never responds; call
   `CreateToolkit` (or `LoadMcpWithContext`) with a ctx canceled after 100 ms → returns in
   ≪ the 30 s default (assert < ~2 s), error is `context.Canceled` (or the server is marked
   failed — pick one and pin it in the spec delta).
2. **SSE start bounded:** a server that forces the SSE fallback and then hangs → load
   completes within `cfg.timeout()` with the server `StatusFailed` (today: hangs forever).
3. **Deadline propagation:** parent ctx with a 50 ms deadline beats a server that responds
   after 5 s.
4. Back-compat: `LoadMcp` (no ctx) against the shared `examples/mcp.json` behaves exactly as
   today.

### Parity

**python, java, csharp** need the same cancellation plumbing (JS is single-threaded around
`await` and its SDK calls already accept abort/timeout — verify and add an `AbortSignal`
passthrough on `createToolkit` if absent). SPEC.md §2 gains: "the MCP load honors the caller's
cancellation/deadline through connect, init, and list; the SSE fallback start is bounded by
the server `timeout`."

---

## Gap 7 — Native per-server tool selection (allowlist) (priority 3)

### Motivation (production case)

The platform stores a per-agent tool allowlist per MCP server
(`mcp_servers.agenttools[].enabledTools` — e.g. only `get_my_leave_balance` +
`get_my_hr_profile` from the Cobra server for a USER-tier agent). toolnexus loads **all** of a
server's tools; rag_go had to wrap every loaded tool in a **filter layer** (rebuild the tool
list post-`CreateToolkit`, matching on sanitized prefixed names it had to reverse-engineer).
toolnexus already has the enable/disable machinery — `ServerConfig.Enabled/Disabled` per
server, `BuiltinsConfig.Tools map[string]bool` per builtin — this extends that native
mechanism to per-server tool selection.

### Proposed API (additive)

```go
// ServerConfig addition (golang/mcp.go) — same key + shape as BuiltinsConfig.Tools:

// Tools filters which of this server's tools are exposed. Keys are the
// server's ORIGINAL tool names (before sanitize/prefix). Nil or empty map ⇒
// all tools (today's behavior). If the map contains AT LEAST ONE true entry it
// is an ALLOWLIST: only names mapped true are loaded ("from server X load only
// [a,b,c]" = {"a":true,"b":true,"c":true}). If it contains ONLY false entries
// it is a DROP-LIST over the all-on baseline (exactly the builtins §4A
// semantics). Unknown names are IGNORED silently — a tool may appear/disappear
// across server versions and config must not break — but names that matched
// nothing are logged once per load at the existing warn level, so a typo'd
// allowlist is visible.
Tools map[string]bool `json:"tools"`
```

Pinned semantics (each is a deliberate decision, documented here):

- **Filter point:** applied to the listed tool definitions *before* conversion/prefixing and
  before toolkit dedupe.
- **Empty vs nil:** both mean "all tools" — an empty allowlist must not mean "no tools"
  (an admin clearing a field must not silently disable a server; disabling is what
  `enabled:false` is for).
- **Unknown names:** ignored + logged (not an error) — chosen over hard-fail so a server
  upgrade that renames a tool degrades to "tool missing" (observable via gap 6) rather than
  "whole toolkit fails to build".
- **Gap 6 composition:** the list-only inventory (below) returns the **unfiltered** tool set —
  it exists to validate/author exactly these allowlists, so it must show what's available, and
  it reports which configured names did not match.
- Alternative considered: `IncludeTools []string` / `ExcludeTools []string`. More explicit,
  but introduces a second filtering vocabulary next to the existing `tools` map the builtins
  already use; consistency won. If the owner prefers the explicit pair, the acceptance tests
  below hold with trivial renaming.

### Acceptance tests

1. Fake MCP server exposing tools `a,b,c` (reuse `mcp_test.go`'s in-process server pattern):
   config `"tools": {"a":true, "b":true}` → toolkit exposes exactly `srv_a`, `srv_b`.
2. Drop-list: `"tools": {"c":false}` → `srv_a`, `srv_b` only.
3. Unknown name: `"tools": {"a":true, "nope":true}` → `srv_a` loads, no error, warn logged.
4. Nil and `{}` → all three tools (back-compat).
5. JSON path: the same config given as a raw `mcp.json` file parses the `tools` key.

### Parity

All four other ports (js, python, java, csharp) — the config file is the shared contract, so
this is a SPEC.md §2 change (config format block + behavior bullet) plus a shared
`examples/` fixture with a `tools`-filtered server every port must honor identically.

---

## Gap 5 — Empty tools array sent upstream (priority 4)

### Motivation (production case)

With a zero-tool toolkit (builtins off, no MCP/skills), the client still sends
`"tools": []` and `"tool_choice": "auto"` (`ToOpenAI` returns an empty non-nil slice —
`golang/adapters.go`; the body builders in `golang/client.go` add both keys
unconditionally). Many providers 400 on an empty tools array. rag_go's workaround is a
**zero-tools degrade** path: detect the empty toolkit before calling and route those requests
through a separate raw-HTTP code path that builds its own body — a full parallel client.

### Proposed behavior (no new API)

On all four paths (run/stream × openai/anthropic): when the effective tool list is empty —
including after a `BeforeLLM` hook overrides `Tools` to an empty slice — **omit** the `tools`
key and (openai style) the `tool_choice` key from the request body entirely. A non-empty list
is unchanged.

### Acceptance tests (wire assertion via httptest)

1. Zero-tool toolkit, openai non-streaming: captured body has **no** `"tools"` and **no**
   `"tool_choice"` key (assert key absence on the decoded map, not value emptiness).
2. Same for openai streaming, anthropic non-streaming, anthropic streaming (`tools` absent).
3. One-tool toolkit: `"tools"` (and openai `"tool_choice":"auto"`) present exactly as today.
4. `BeforeLLM` returning `Tools: []any{}` → keys omitted on that turn.

### Parity

All four other ports; SPEC.md §8 gains one sentence ("when the toolkit yields zero tools the
`tools`/`tool_choice` keys are omitted"). Verified: the JS reference sends both keys
unconditionally too (`js/src/client.ts` lines ~473/~649), so this is a genuine five-port
behavior change, not Go drift.

---

## Gap 6 — List-only mode for MCP configs (priority 5)

### Motivation (production case)

Validating an admin-entered MCP server config (libreadmin's MCP-server screens: "does it
connect, what tools does it expose, is this allowlist valid?") required building a **full
toolkit** — spawning builtins, globbing skills, resolving A2A agents — then parsing
sanitized/prefixed names back apart. rag_go needed a lightweight inspect/validate call.

### Proposed API (additive)

```go
// ToolInfo is one listed tool definition (original, unprefixed name).
type ToolInfo struct {
    Name        string     `json:"name"`
    Description string     `json:"description"`
    InputSchema JSONSchema `json:"inputSchema"`
}

// McpInventory is the result of a list-only pass over an MCP config.
type McpInventory struct {
    // Tools maps server name → its full listed tool definitions, UNFILTERED by
    // ServerConfig.Tools (gap 7) — the inventory exists to author/validate
    // those filters, and it reports what a filter would/wouldn't match.
    Tools  map[string][]ToolInfo `json:"tools"`
    // Status per server: connected | disabled | failed (same values as McpStatus).
    Status map[string]McpStatus  `json:"status"`
}

// ListMcpTools connects to every enabled server in the config, lists tool
// definitions, and DISCONNECTS before returning — no toolkit, no skills/
// builtins/agents, no Execute wiring, nothing left running. ctx-aware per
// gap 3 (cancellation + per-server timeout). Failure isolation as in LoadMcp:
// a bad server is Status failed, never an error for the whole call.
func ListMcpTools(ctx context.Context, input any) (*McpInventory, error)
```

Chosen over a `Toolkit` option ("skip skills/builtins/agents") because the validate use-case
wants original names, per-server grouping, and guaranteed teardown — a toolkit is the wrong
return type for all three. Internally it should share the gap-3 connect/init/list plumbing.

### Acceptance tests

1. Config with one good server (tools `a,b`) + one unreachable server → `Tools["good"]` has
   original names `a,b` with schemas; `Status` = `{good: connected, bad: failed}`; no error.
2. Teardown: after return, the stdio child process is gone (no leaked client) — assert via
   the transport's close having been called (or process-liveness check).
3. Disabled server → `Status disabled`, no connection attempt.
4. ctx canceled mid-list → prompt return (composes with gap 3's test).

### Parity

All four other ports (`listMcpTools` / `list_mcp_tools` etc.); SPEC.md §2 gains a "list-only
inventory" subsection pinning the shape (server → original-name tool defs + status).

---

## Gap 4 — Conversation store is unreachable through the client (priority 6)

### Motivation (production case)

`Client.store` is unexported with no accessor. rag_go's multi-attempt runs (an empty/refused
answer is retried against the same history) need to read and rewind the transcript the client
just saved. Because the default in-memory store is created inside `CreateClient` and never
exposed, rag_go maintained a **shadow session store** — a parallel `ConversationStore`
mirroring every `Ask` — with the obvious drift risk. (When the consumer passes
`ClientOptions.Store` the client does already treat it as authoritative — that half of the
contract exists; the accessor is the missing piece, plus documentation blessing the pattern.)

### Proposed API (additive)

```go
// ConversationStore returns the client's conversation provider — the exact
// instance passed in ClientOptions.Store, else the default in-memory store the
// client created. Callers may read (Get) and write (Save) it directly to share
// state with Ask/StreamWithID: the store is the single source of truth for
// conversation history, and consumer access is a supported pattern (e.g.
// re-run an attempt against the transcript as it was before the last save).
func (c *Client) ConversationStore() ConversationStore
```

Documentation note to carry: for full control, pass your own store in `ClientOptions.Store`
(already supported); the accessor makes the default store equally reachable so no consumer
ever needs a shadow copy.

### Acceptance tests

1. Identity: `CreateClient(ClientOptions{Store: s}).ConversationStore()` returns `s` (same
   instance).
2. Default: without `Store`, accessor is non-nil; after `Ask(ctx, p, tk, "id1")` (stub LLM),
   `accessor.Get("id1")` returns the saved transcript.
3. Shared reuse: `Save("id2", history)` via the accessor, then `Ask(..., "id2")` continues
   from that history (multi-attempt pattern proven end-to-end).

### Parity

All four other ports (js `client.conversationStore` getter, python property, java/csharp
getter); SPEC.md §8 conversation-memory paragraph gains the accessor +
"`ClientOptions.store` is authoritative and host-shareable" sentence. Also fold into the
`conversation-store` capability spec (`openspec/specs/conversation-store/`) when the OpenSpec
change lands.

---

## Consequences

- rag_go can delete, in order: the sampling reverse proxy (gap 1 + 2), the toolkit-build
  watchdog (gap 3), the wrapper-based tool filter (gap 7), the zero-tools degrade path
  (gap 5), the full-toolkit validate hack (gap 6), and the shadow session store (gap 4).
- Every change is additive; no existing signature changes, `LoadMcp`/`CreateClient` behavior
  with no new options set stays byte-identical, so no port ships a breaking release.
- Cross-language parity debt is created by design: Go may land first for rag_go, but gaps 1,
  3, 4, 5, 6, 7 move the shared contract and MUST be recorded as unchecked per-port tasks in
  their OpenSpec changes (js, python, java, csharp), with SPEC.md deltas (§2 for 3/6/7, §8 for
  1/4/5) and shared `examples/` fixtures where the config format moves (gap 7).
- Gap 5 is a behavior change in every port (JS verified to send the empty array today); the
  spec sentence pins the new omission rule for all five.

---

## Consumer answers — rag_go (2026-07-13, verified against code)

**A1 (Gap 3, cancel mid-load): (a) — fail the whole build with `ctx.Err()`.**
Parent-ctx cancel means the client disconnected; the request is dead and the
build must abandon promptly (no leaked connections). rag_go supplies its own
overall budget (150s) as the ctx deadline and degrades to no-tools generation
on timeout itself. Keep the existing per-server isolation for individual
connect failures *within* budget — one flaky server drops its tools, it does
not kill the build. Only parent-ctx cancel/deadline kills the build.

**A2 (Gap 7, name space): ORIGINAL names.** `agenttools[].enabledTools` stores
raw server tool names (e.g. `get_my_leave_balance`), never prefixed. The
pre-prefix filter contract is correct as proposed. Nuance (FYI, not a
requirement): rag_go re-exposes filtered tools to the LLM under the RAW name
(python parity — see `apps/rag_go/src/mcp/toolkit.go wrapEnabledTools`); with a
native allowlist we would keep a thin rename wrapper unless toolnexus grows an
"expose unprefixed" option someday.

**A3 (Gap 7, mixed map): never mixed.** We convert a JSON array of enabled
names into an all-true map; there is no "everything except X" encoding in the
platform. "Any true ⇒ pure allowlist" is safe. Please also pin: empty non-nil
map = load nothing (rag_go's router drops zero-enabled servers before the
toolkit build, so we won't hit it, but pin it anyway).

**A4 (Gap 1, RequestParams scope): forbid `messages`/`tools`/`stream` in
RequestParams.** Keys we actually inject today (verified in
`src/llm/sampling_proxy.go`): `temperature`, `top_p`, `frequency_penalty`,
`presence_penalty`, `max_tokens`, `reasoning_effort`, `chat_template_kwargs`.
BUT the `BodyTransform` hook MUST receive the full body and be permitted to
rewrite `messages`: our GLM `/nothink` appends to the last user message's
content, and the Scaleway tool-role preprocessing rewrites the whole messages
array. Contract we need: transform runs LAST (after merge), output sent
verbatim.

**A5 (Gap 2, HTTP client scope): LLM path only is sufficient.** The reverse
proxy existed for body rewriting, not transport observability. MCP transport
needs are covered by Gap 3's ctx-awareness.

**A6 (captured bodies): real fixtures exist on this machine** — no redaction
needed, they're test fixtures:
- `…/raman-workspace/worktrees/woktree-rag-go-rewrite/apps/rag_go/src/llm/sampling_proxy_test.go`
  — merged bodies incl. GLM `chat_template_kwargs` placement and the
  reasoning_effort provider gating (scaleway always; openai o-*/gpt-5 only).
- `…/apps/rag_go/src/llm/scaleway_messages_test.go` — 7 hand-traced
  before/after transcripts of the Scaleway tool-role rewrite (ported from
  `scaleway_chat_openai.py`).
Production providers are scaleway/openai/mistral, all OpenAI-style wire — no
OpenRouter/Anthropic bodies exist to capture. Representative Scaleway GLM
shape: `{model, messages[… last user content ends " /nothink"], temperature,
top_p, max_tokens, chat_template_kwargs:{enable_thinking:false}, stream,
stream_options:{include_usage:true}, tools?, tool_choice?}`.

**A7 (Gap 6, validate names): original name is the primary requirement** — the
libreadmin UI's enabledTools keys must match it (that key mismatch is exactly
the HR-350 bug class). Returning the prefixed name as an optional secondary
field is harmless and mildly useful; don't make it the primary.

**A8 (Gap 4): `Get` + `Save` are enough.** Empty-answer retry appends to the
same conversation; length-retry deliberately starts a fresh session (python
parity). No rewind/delete/truncate needed.

**A9 (housekeeping): priority order unchanged.** One new item since the ADR,
minor and non-blocking: a toolkit introspection accessor (open-toolkit /
connected-server count) — rag_go keeps its own `ActiveToolkits()` counter for
a debug endpoint; native support would be a nice-to-have only.
