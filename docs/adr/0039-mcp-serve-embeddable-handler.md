# ADR 0039 — MCP serve as a plain, embeddable handler with generic hooks

- Status: **PROPOSED** (2026-11-04)
- Scope: Go first (reference), then the other seven ports via SPEC §7C.1
- Issue: #139
- Change: `openspec/changes/add-mcp-serve-handler`
- Related: SPEC §7C (shipped `Serve(addr, {mcp})`), issue #134 / `upgrade-mcp-2026-07-28` (spike only)

## Context

`toolkit.Serve(addr, {mcp})` (§7C) owns the listener and exposes one static tool list with no
identity, no gate and no way to change the list. #139 (a hosted MCP product) asked for a lot more:
per-tenant tools, session replication, branding, resources/prompts. The owner's ruling (2026-11-04):
**"many tenants is an issue for it to solve, our job is to start a server for MCP… SaaS requires a
different DB; we give something plain and let it work on top."**

## Decision — the split

toolnexus ships a **plain, embeddable MCP server** with generic hooks. It has no tenant, user,
role, storage or replication concepts.

```go
h := tk.MCPHandler(toolnexus.MCPHandlerConfig{...}) // *MCPHTTPHandler, an http.Handler
mux.Handle("/mcp", h)                                 // host owns path, TLS, CORS, OAuth
h.NotifyToolsChanged()                                // notifications/tools/list_changed
```

| what toolnexus does | hook / API |
|---|---|
| streamable-HTTP transport (POST / GET / DELETE), protocol 2025-06-18 (+2025-03-26) | `MCPHandler(cfg) http.Handler` |
| run an auth check on every HTTP request; failure ⇒ 401 + caller's `WWW-Authenticate` | `Authenticate(r) (any, error)`, `WWWAuthenticate(r, err) string` |
| carry the auth result, opaque, to every hook and tool | `IdentityFrom(ctx) any` |
| resolve the tool list per request (tools may be synthesized at runtime) | `ListTools(ctx) ([]Tool, error)`; nil ⇒ the toolkit's tools |
| `tools/call` of a name not in that list ⇒ JSON-RPC `-32602`, never executed | — |
| push `notifications/tools/list_changed` on open GET streams | `h.NotifyToolsChanged()` |
| gate each call: allow / deny(reason) / confirm(message) | `BeforeCall(ctx, tool, args)` |
| confirm ⇒ `elicitation/create` on the same POST (SSE) when the client declared `elicitation`; else an isError result "confirmation required: …" | — |
| annotations passthrough (`title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`) | `Tool.Annotations` |
| per-call metering event | `OnCall(MCPCallEvent{Name, Source, Ms, IsError, Err, ResultBytes, Decision, Identity})` |

| session ids: generate / validate / terminate (ctx-aware), our own interface; default = in-memory | `SessionIDs MCPSessionIDs` |
| no sessions at all (no `Mcp-Session-Id`) | `Stateless bool` |
| `serverInfo` name/version + `instructions` resolved at `initialize`, identity and the HTTP request (Host header) on ctx | `ServerInfo(ctx) MCPServerInfo`, `RequestFrom(ctx)` |
| resources and prompts, per request; a hook absent ⇒ the capability is not advertised | `ListResources(ctx)`, `ReadResource(ctx, uri)`, `ListPrompts(ctx)`, `GetPrompt(ctx, name, args)` |
| a gate that answers with its own shaped tool result (e.g. "re-call with nonce X") | `MCPDecision.Result` |

Every type in this API is toolnexus's own (`MCPResource`, `MCPPrompt`, `MCPServerInfo`,
`MCPDecision`, `ToolAnnotations`…) — never an mcp-go type — so the other seven ports can implement
the same contract on their own SDKs. There is no `Configure(*server.MCPServer)` escape hatch.

**Addendum (2026-11-04, after the consumer's pushback, accepted):** session ids, resources/prompts
and a ctx-aware `ServerInfo` came back as *generic hooks*. Still no storage in toolnexus: the
`SessionIDs` implementation (and whatever DB backs it) is the host's.

**Left to the host** (explicitly not toolnexus): who the caller is and what tenant/project/role
it maps to (decided inside `Authenticate`/`ListTools`); OAuth / RFC 9728 metadata endpoints; the
non-elicitation confirm path (e.g. a nonce re-call — the host's `BeforeCall` sees the args and can
allow on a valid nonce); what backs `SessionIDs`; what branding to return; rate limits; CORS; TLS.

Sessions: the default `SessionIDs` is an in-memory set. A host plugs its own (e.g. signed ids,
or a DB lookup) to let any replica validate any session. The client's declared capabilities are
cached in memory on the replica that saw `initialize`; an elicitation round-trip (the confirm
request goes out on a POST's SSE stream, the answer comes back on a *new* POST) is delivered only
on the replica holding the waiting call — so **elicitation needs sticky routing**; on a replica that
never saw the session's `initialize`, confirm takes the no-elicitation path. `Stateless: true`
issues no session id at all, and so never elicits.

## Protocol versions (review follow-up, 2026-11-04)

A handler advertises and accepts **only** versions whose wire it implements. Default
`ProtocolVersions: ["2025-06-18"]`; a host may opt into more, filtered against the implemented set.

| version | server-visible difference from 2025-06-18 | verdict |
|---|---|---|
| 2025-03-26 | allows JSON-RPC batching; no `structuredContent`/`outputSchema`/elicitation/`MCP-Protocol-Version` header | **not accepted** — the handler rejects batches, so a conforming 2025-03-26 client could break |
| 2025-06-18 | baseline | default |
| 2025-11-25 | optional fields only (`icons`, `Implementation.title/description/websiteUrl`, tasks, URL-mode elicitation, sampling with tools), SSE polling/resumption is optional for the server, plus one rule: **invalid tool input is a tool execution error (`isError`), not `-32602`** | **implemented, opt-in** — the rule is applied when the request carries `MCP-Protocol-Version: 2025-11-25` |
| 2026-07-28 | the stateless-first rewrite tracked in #134 (spike only) | **not accepted** until #134 lands |

The 2025-11-25 Origin-validation (403) requirement is the host's: it sits in the host's CORS/Origin
middleware outside the handler, like TLS.

## Structured results and `_meta` (review follow-up)

`ToolResult.Structured` → `structuredContent` (with a JSON text block when `Output` is empty),
`Tool.OutputSchema` → `outputSchema`, `ToolResult.Meta` → `_meta`, request `params._meta` →
`MCPRequestMetaFrom(ctx)`. These are SPEC §1 core-type additions, optional, absent ⇒ byte-identical;
the §7C `Serve` path maps them too. `MCPServerInfo.Title` → `serverInfo.title`. In stateless mode
`StatelessSessionID` returns a fixed `Mcp-Session-Id` on initialize, never validated.

## Why an in-house transport over mcp-go's types, not mcp-go's `server`

Verified in `mark3labs/mcp-go v0.48` source:

1. Tools are registered on an `MCPServer`, not resolved per request; per-request servers (what §7C
   does) lose the session.
2. A server→client request (elicitation) is only dequeued by a **GET** stream (`handleGet` reads
   `elicitationRequestChan`); responses route only to sessions in that instance's `activeSessions`.
   Most clients never hold a GET open, so confirm would silently not work.

The streamable-HTTP transport is small; implementing it over `mcp.*` wire structs costs a few hundred
lines, adds no dependency, and lets a confirm elicitation ride the same POST's SSE response — which
the mcp-go **client** handles (`handleSSEResponse` → `handleIncomingRequest`). The tests use that
client as the interop check (the "spike" for this ADR is the source reading above plus
`mcphandler_test.go`).

## Relation to the MCP 2026-07-28 work (#134)

#134 decided: client-first, own layer, server HTTP only, OAuth via CIMD/pre-registered. This is
consistent and smaller: inbound HTTP only, our own layer over mcp-go types, no OAuth in core —
`Authenticate` + `WWWAuthenticate` is the seam an authorization server plugs into. When #134 promotes
its server, protocol negotiation here is a table to extend.

## Consequences

- `Serve(addr, {mcp})` is untouched (same code path, same tests).
- Other seven ports: unchecked tasks in the change, named in CHANGELOG.

## Rejected

- Tenant / principal / role concepts and any session *store* in toolnexus (owner ruling: host concerns).
- Fork or patch mcp-go (new dependency surface).
- `server.WithToolFilter` over a registered superset — cannot express synthesized tools.
