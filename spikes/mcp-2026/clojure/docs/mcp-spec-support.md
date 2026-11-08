# MCP 2026-07-28 support

> **Clojure port of the spike (openspec `upgrade-mcp-2026-07-28`); the Go spike in `spikes/mcp-2026/golang` is
> the reference.** The API below is `spikes/mcp-2026/clojure` (namespaces `mcp2026.core`, `mcp2026.auth`,
> `mcp2026.devauth`). Every code block is copied verbatim from a program under `examples/` that
> `examples_test.cljc` runs and checks; `docs_test.cljc` fails if a block here stops matching its source.

Same matrix as the Go spike; every row below is implemented and tested in this port unless marked.

| feature | spike status |
|---|---|
| Streamable HTTP client, JSON or SSE response | supported (final SSE `data:` with a result or error is used) |
| stdio client (one message per line, concurrent ids, stderr passed through or captured) | supported |
| `server/discover` (supportedVersions, capabilities, `_meta` serverInfo, ttlMs/cacheScope) | supported, client + server |
| per-request `_meta`: protocolVersion, clientCapabilities, clientInfo | supported |
| headers `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` (base64 sentinel) | supported; server answers -32020 on missing/mismatch |
| unsupported version → `-32022` with `{supported, requested}` | supported |
| `resultType` (missing = `complete`) | supported |
| MRTR `input_required` (elicitation form), `inputResponses` + `requestState` | supported (client responder fn; server `greet`) |
| `tools/list` cache hints `ttlMs` / `cacheScope` | supported; used by tool search |
| authorization (PRM, AS discovery, PKCE, resource, iss, refresh, step-up) | supported, client + server |
| client registration: pre-registered, CIMD | supported |
| skills extension (SEP-2640) | supported, client + server |
| `x-mcp-header` mirroring/validation | **not yet** |
| SSE progress notifications | **not yet** (final message only; the SSE body is buffered) |
| `requestState` integrity protection (server side) | **not yet** |
| pagination (`nextCursor`) | **not yet** |
| prompts, resource templates, subscriptions | **not yet** (`call` can send any method; no helpers) |
| MCP server over stdio | client only; `serve-stdio` exists for tests and examples |
| Dynamic Client Registration | **not supported, by decision** |
| `private_key_jwt`, `offline_access`, encrypted token store | not done |

## Old servers (2025-11-25 and earlier)

No fallback in the spike: against an old server it fails at `server/discover`.

## Choices where the spec is ambiguous

Identical to the Go spike (see `../golang/README.md`): header/`_meta` disagreement on an unsupported value is
`-32020`; a missing `MCP-Protocol-Version` header is `-32020`; `inputResponses` / `requestState` travel in
`params`; `resource` is sent on refresh unless `:omit-refresh-resource`; scopes match exactly.

Cross-port: this port's client passes the 8 checks against the Go server and the Go client passes them against
this server (`./run.sh client <url>`).
