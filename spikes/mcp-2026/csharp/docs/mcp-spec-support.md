# MCP 2026-07-28 support

> **C# port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/csharp` (project `src/Mcp2026`, namespace `Mcp2026`). Every code block is copied verbatim from a
> program under `examples/` that `ExamplesTests` runs and checks; `DocsTests` fails if a block here stops matching
> its source. Behaviour and example output are identical to the Go reference spike.

| feature | C# spike status |
|---|---|
| Streamable HTTP client, JSON or SSE response | supported (final SSE `data:` with a result or error) |
| stdio client (one message per line, concurrent ids, stderr passed through) | supported |
| `server/discover`, per-request `_meta`, headers `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name` (base64 sentinel) | supported, client + server |
| `-32020` header mismatch, `-32022` unsupported version `{supported, requested}` | supported |
| `resultType` (missing = `complete`), MRTR `input_required` + `inputResponses` + `requestState` | supported |
| `tools/list` cache hints `ttlMs` / `cacheScope` | supported; used by tool search |
| authorization (PRM, AS discovery, PKCE, resource, iss, refresh, step-up), pre-registered + CIMD clients | supported, client + server |
| skills extension (SEP-2640) | supported, client + server |
| `x-mcp-header`, SSE progress, `requestState` integrity, pagination, prompts helpers | **not yet** |
| Dynamic Client Registration | **not supported, by decision** |
| `initialize`, sessions, resumable SSE, server→client requests | **removed by the spec** |

Ambiguity choices are the Go spike's (see `../golang/README.md`): `-32020` wins over `-32022`; a missing
`MCP-Protocol-Version` header is `-32020`; `inputResponses`/`requestState` travel in `params`; `resource` is sent
on refresh unless `OmitRefreshResource`; scopes match exactly.
