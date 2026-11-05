## Why

MCP 2026-07-28 is the current spec and a breaking redesign (stateless, `server/discover`, MRTR instead of
server→client requests, sessions and SSE resumability removed). Every stable SDK is still on 2025-11-25, and the
MCP client is toolnexus's core. Hosted servers already speak it (GitHub's MCP server answers `server/discover`
and rejects `initialize` without `_meta`), and most of them require OAuth, which toolnexus does not support at
all today. A stdlib-only Go spike (`spikes/mcp-2026/golang`) proved that owning the layer costs ~2.4k lines per
port and works live against Keycloak 26.8.1 and GitHub's hosted MCP server.

## What Changes

- toolnexus **owns its MCP layer** in every port (no MCP SDK) at protocol **2026-07-28**.
- **Client (stdio + Streamable HTTP)** speaks 2026-07-28: `server/discover`, per-request `_meta`
  (protocolVersion, clientCapabilities, clientInfo), `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name`
  headers, `resultType`, cache hints, MRTR `input_required` routed through SPEC §10 `waitFor`.
- The existing old-era client path is **frozen** and used only as a fallback when `server/discover` fails
  (servers on 2025-11-25 and older). No new features land on it.
- **OAuth client**: Protected Resource Metadata → AS metadata → CIMD or pre-registered `clientId` → PKCE S256 +
  `resource` → `iss` check → token → refresh → step-up on `insufficient_scope`. Two UX modes: automatic
  (system browser + loopback) and SPEC §10 `authorization` pause/resume. **No Dynamic Client Registration.**
- **OAuth server**: `serve` gains `auth: {issuer, audience?, scopes?: {tool: [...]}, verify?}` — serves PRM,
  401/403 with `WWW-Authenticate`, JWT verification via JWKS, principal visible to tools; plus a dev-only
  in-process authorization server.
- **Skills extension (SEP-2640)** on client (`skills/list`, `skills/get` feed the `skill` tool) and server.
- **Lazy tool search**: opt-in `toolSearch` exposes `search_tools` + `call_tool` instead of every tool;
  per-server `description` in `mcp.json`.
- **BREAKING (server)**: `serve /mcp` speaks 2026-07-28 only; old-era MCP clients can no longer call it.
- **BREAKING (golang)**: drops `mark3labs/mcp-go`; other SDK dependencies (`@modelcontextprotocol/sdk`, `mcp`,
  `io.modelcontextprotocol.sdk:mcp`, `ModelContextProtocol`, `rmcp`) are removed.
- Out of scope: roots, sampling, logging (deprecated by the spec), DCR, MCP server over stdio, Tasks extension.

## Capabilities

### New Capabilities
- `mcp-protocol-2026`: the 2026-07-28 wire contract for client and server, incl. MRTR, headers, errors, fallback.
- `mcp-oauth-client`: discovering, obtaining, refreshing and stepping up tokens; auto and pause/resume modes.
- `mcp-oauth-server`: protecting `serve /mcp` with one issuer line, per-tool scopes and a verify hook.
- `mcp-skills-extension`: SEP-2640 skills over MCP on both sides.
- `mcp-tool-search`: lazy tool exposure through a search tool.

### Modified Capabilities
<!-- none: mcp-inbound and mcp-load-lifecycle requirements keep holding; the new wire contract is additive
     in mcp-protocol-2026 and folds into them at archive time -->

## Impact

- All 8 ports (js, python, golang, java, csharp, elixir, clojure, rust): MCP source + `serve` MCP profile.
- `SPEC.md` §2 (MCP source) and §7C (MCP server) rewritten; new §2A (authorization).
- Dependencies: MCP SDKs removed; JWT/JWKS verification may need a small crypto dependency per port.
- Shared conformance fixtures under `examples/` + a cross-port interop matrix.
- Docs: "MCP: zero to authenticated" cookbook, authentication guide, spec support table, provider notes.
