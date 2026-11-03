# MCP 2026-07-28 support

> **Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/golang` (import path `toolnexus/spikes/mcp2026`). Every code block is copied
> verbatim from a program under `examples/` that `examples_test.go` runs and checks; `docs_test.go` fails if a
> block here stops matching its source.

| feature | spike status |
|---|---|
| Streamable HTTP client, JSON or SSE response | supported (final SSE `data:` with a result or error is used) |
| stdio client (one message per line, concurrent ids, stderr passed through) | supported |
| `server/discover` (supportedVersions, capabilities, `_meta` serverInfo, ttlMs/cacheScope) | supported, client + server |
| per-request `_meta`: protocolVersion, clientCapabilities, clientInfo | supported |
| headers `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` (base64 sentinel) | supported; server answers -32020 on missing/mismatch |
| unsupported version → `-32022` with `{supported, requested}` | supported |
| `resultType` (missing = `complete`) | supported |
| MRTR `input_required` (elicitation form), `inputResponses` + `requestState` | supported (client `Responder`; server `greet`) |
| `tools/list` cache hints `ttlMs` / `cacheScope` | supported; used by tool search |
| authorization (PRM, AS discovery, PKCE, resource, iss, refresh, step-up) | supported, client + server |
| client registration: pre-registered, CIMD | supported |
| skills extension (SEP-2640) | supported, client + server |
| `x-mcp-header` mirroring/validation | **not yet** |
| SSE progress notifications | **not yet** (final message only) |
| `requestState` integrity protection (server side) | **not yet** |
| pagination (`nextCursor`) | **not yet** |
| prompts, resource templates, subscriptions | **not yet** (`Call` can send any method; no helpers) |
| Tasks extension | **not planned in this change** (follow-up) |
| MCP server over stdio | client only; `ServeStdio` exists for tests and examples, not as product surface |
| Dynamic Client Registration | **not supported, by decision** (deprecated by the spec) |
| roots, sampling, logging | **not supported** (deprecated by the spec) |
| `initialize` handshake, sessions (`Mcp-Session-Id`), resumable SSE, server→client requests | **removed by the spec**; not implemented |
| `private_key_jwt`, `offline_access`, encrypted token store | not done |

## Old servers (2025-11-25 and earlier)

The ports keep today's MCP client, **frozen**, and use it only when `server/discover` fails. No new feature lands
on it. **The Go spike has no fallback**: against an old server it fails at `server/discover`. Our server speaks
2026-07-28 only, so old-era MCP clients cannot call it (BREAKING in the change).

## Choices where the spec is ambiguous

- Header and `_meta` disagree and the value is unsupported: `-32020` (mismatch is checked first).
- Missing `MCP-Protocol-Version` header: `-32020`, not "assume 2025-03-26".
- `inputResponses` / `requestState` travel in `params`.
- `resource` on refresh: sent (spec MUST), with `OmitRefreshResource` for Entra.
- Scopes match exactly; the spec's scope hierarchy is not modelled.

The full list, with spec section references, is in `README.md` (OAuth table and "Spec ambiguities").
