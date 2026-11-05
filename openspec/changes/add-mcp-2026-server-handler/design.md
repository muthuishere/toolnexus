## Context

#140 built `mcpHandler` (stateful 2025-06-18/2025-11-25 wire, plain hooks). #142 built the 2026 client and pinned
the server contract in SPEC §7C / §2A.2 against a `serve`-shaped API. This change puts that contract on the handler
instead, because the handler is where hosts mount MCP behind their own auth and routing (ADR 0039).

## Decisions

1. **Opt-in version, one handler.** `"2026-07-28"` in `protocolVersions` enables the wire; absent ⇒ byte-identical
   to §7C.1. Mixed lists serve both wires on one mount; routing is pinned in §7C.2 so a request is never ambiguous.
   `initialize` never negotiates 2026 (it would promise a session wire the client will not use).
2. **MRTR replaces elicitation on this wire.** Confirm becomes an `input_required` round with key `confirm`; a §10
   `Pending` from a tool becomes an `input_required` round keyed by the request id, and the retry's single
   response becomes the tool's `Answer` (re-execution, the §10 idempotence rule).
3. **`requestState` is unsigned.** `{tool, confirmed?}` in base64url JSON. Signing buys nothing for confirm (the
   client can answer accept anyway) and would force a key on every host; `beforeCall` re-runs on every round so a
   forged state cannot bypass `deny`. A host that needs integrity wraps its own state in `beforeCall`/tools.
4. **Auth stays a hook.** `MCPServerAuth` produces an `authenticate` function and a metadata handler; the handler
   learns only one new thing — `MCPAuthError` (status + challenge). Per-tool scopes read the validated
   `Mcp-Method`/`Mcp-Name` headers, so they need no change to the hook signature; on the legacy wire only `"*"`
   scopes apply (documented).
5. **Skills are data from a hook.** `listSkills(ctx)` returns `{name, frontmatter, files}`; where skills live is the
   host's call. `loadMcpSkills(dir)` covers the common case. Text files only (the wire is text).
6. **cacheScope** is `private` whenever `authenticate` is set (lists may depend on the caller), else `public`.
7. **Dev AS over plain HTTP**, not self-signed TLS: trusting a dev cert process-wide (the spike's approach) is not
   acceptable library behaviour; the 2026 client already has `allowHttp` for exactly this. The dev AS is
   test/interop tooling in each port's test tree, not exported library API.
8. **Tool search needs nothing server-side**: it is client-side (§2.8) over `tools/list`; the server's part is the
   deterministic order and `ttlMs` the client caches by. The interop runner proves it.

## Risks / Trade-offs

- `serve(addr, {mcp})` is still pre-2026; #142's SPEC text calls the 2026 switch for `serve` BREAKING. Left for an
  owner decision rather than bundled here.
- Multi-round tools only see the latest round's answer (one `Answer` per §10 retry).
