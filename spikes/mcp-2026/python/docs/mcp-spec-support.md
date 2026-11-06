# MCP 2026-07-28 support

> **Python port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/python` (package `mcp2026`, `import mcp2026 as mcp`); the Go spike is the reference.
> Every code block is copied verbatim from a program under `examples/` that `tests/test_examples.py` runs and
> checks; `tests/test_docs.py` fails if a block here stops matching its source.

| feature | spike status |
|---|---|
| Streamable HTTP client, JSON or SSE response | supported (final SSE `data:` with a result or error is used) |
| stdio client (one message per line, concurrent ids, stderr passed through) | supported |
| `server/discover` (supportedVersions, capabilities, `_meta` serverInfo, ttlMs/cacheScope) | supported, client + server |
| per-request `_meta`: protocolVersion, clientCapabilities, clientInfo | supported |
| headers `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` (base64 sentinel) | supported; server answers -32020 on missing/mismatch |
| unsupported version → `-32022` with `{supported, requested}` | supported |
| `resultType` (missing = `complete`) | supported |
| MRTR `input_required` (elicitation form), `inputResponses` + `requestState` | supported (client responder; server `greet`) |
| `tools/list` cache hints `ttlMs` / `cacheScope` | supported; used by tool search |
| authorization (PRM, AS discovery, PKCE, resource, iss, refresh, step-up) | supported, client + server |
| client registration: pre-registered, CIMD | supported |
| skills extension (SEP-2640) | supported, client + server |
| `x-mcp-header` mirroring/validation | **not yet** |
| SSE progress notifications | **not yet** (final message only) |
| `requestState` integrity protection (server side) | **not yet** |
| pagination (`nextCursor`) | **not yet** |
| prompts, resource templates, subscriptions | **not yet** (`call` can send any method; no helpers) |
| MCP server over stdio | client only; `serve_stdio` exists for tests and examples, not as product surface |
| Dynamic Client Registration | **not supported, by decision** (deprecated by the spec) |
| roots, sampling, logging | **not supported** (deprecated by the spec) |
| `initialize` handshake, sessions, resumable SSE, server→client requests | **removed by the spec**; not implemented |
| asyncio API | **not in the spike**: the API is synchronous (threads), like the Go reference |

## Old servers (2025-11-25 and earlier)

The spike has no fallback: against an old server it fails at `server/discover`. The plan in the OpenSpec change
is to keep today's SDK-based client, frozen, for old servers.

## Choices where the spec is ambiguous

Same as the Go reference (see its README): header/`_meta` mismatch is `-32020` before `-32022`; a missing
`MCP-Protocol-Version` header is `-32020`; `inputResponses` / `requestState` travel in `params`; `resource` is sent
on refresh unless `omit_refresh_resource`; scopes match exactly.
