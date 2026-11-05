# The in-process client is the seam; vendor agents are recipes (ADR 0041)

## Why

ADR 0037 (draft PR #138, never released) shipped a per-vendor `createCliClient` with a `CliStyles`
enum, vendor style files, a one-shot CLI transport and vendor-specific permission and prompt
policy. The owner reset the scope on 2026-11-05: vendor CLI/ACP driving is not maintained library
surface. Vendor knowledge changes faster than the library releases and put vendor names in the
public API. The part that is general — and that every vendor recipe needs — is small.

## What Changes

- **ADDED (all eight ports)** streaming generate on `createInProcessClient` (`generateStream(req, emit)`).
- **ADDED** to the ACP client (`loadACP`): `mcpServers` (verbatim passthrough), `sessionMeta`
  (`session/new` `_meta`), `clientCapsMeta` (`initialize` `clientCapabilities._meta`), `model`
  passthrough, `terminal:false`, prompt failure on agent exit, `-32601` for unimplemented agent
  requests, process-group teardown, incremental streaming in every port.
- **ADDED** `toolkit.asMcpServer(config?) -> {url, token, close}` on the §7C server.
- **REMOVED (never released)**: `createCliClient`, `CliStyles`, `conformance/cli-styles/`, the
  one-shot CLI transport, preamble/reply parsing, `load_skill` rename, vendor permission allow-list,
  devin system hint, isolation flags, `toolnexus mcp-bridge`, model lists.
- SPEC §8C rewritten; `conformance/acp-client/cases.json` replaces `conformance/cli-client/`.
- Vendor recipes are documented on the docs site (separate docs PR), not in the library.

## Impact

All eight ports. No released API changes. Consumers of the unreleased drafts (7cb7460, 9ec1830)
migrate as described in ADR 0041.
