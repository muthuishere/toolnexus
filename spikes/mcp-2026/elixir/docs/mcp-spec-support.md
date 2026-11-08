# MCP 2026-07-28 support

> **Elixir port of the Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API
> below is `spikes/mcp-2026/elixir` (modules `Mcp2026.*`). Every code block is copied verbatim from a script under
> `examples/` that `test/examples_test.exs` runs and checks; `test/docs_test.exs` fails if a block here stops
> matching its source.

Same matrix as the Go spike; Elixir differences are marked.

| feature | spike status |
|---|---|
| Streamable HTTP client, JSON or SSE response | supported (final SSE `data:` with a result or error is used) |
| stdio client (one message per line, concurrent ids) | supported; server stderr is **inherited, not capturable** (Elixir ports cannot split stderr) |
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
| `x-mcp-header`, SSE progress, `requestState` integrity, pagination | **not yet** |
| prompts, resource templates, subscriptions | **not yet** (`Client.call/3` can send any method) |
| MCP server over stdio | `Mcp2026.Stdio.serve/1` exists for tests and examples |
| Dynamic Client Registration | **not supported, by decision** |
| `initialize`, sessions, resumable SSE, server→client requests | **removed by the spec**; not implemented |

## Choices where the spec is ambiguous

Identical to Go: header/`_meta` disagreement → `-32020` first; missing `MCP-Protocol-Version` → `-32020`;
`inputResponses`/`requestState` in `params`; `resource` sent on refresh unless `omit_refresh_resource`; exact
scope match. Interop is verified both ways against the Go spike (`./run.sh client`).
