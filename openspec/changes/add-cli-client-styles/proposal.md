# Agent CLIs as tool-calling clients (CLI client styles, ACP primary)

## Why

Many users already pay for a coding-agent subscription — Claude Code, Codex, GitHub Copilot,
opencode, Devin, Gemini CLI, goose — and each ships a CLI. They want that subscription to be the
model behind a normal toolnexus client, with *their own* toolkit (MCP servers, skills,
`defineTool` functions) callable, in one line of configuration. Today the in-process client seam
(§8 "In-process models") exists but nothing turns an installed CLI into a `generate`, and
`golang/acp.go` always sends `session/new` `mcpServers: []`, so an ACP agent can never see the
user's tools.

The spike on `spike/cli-client` (ADR 0037, draft PR #136) proved both halves live: the toolkit
served as MCP into ACP `session/new` (claude, copilot, devin, gemini, opencode: 3/3 checks each),
and the one-shot CLI with our JSON envelope as the fallback (codex, and every CLI forced to it).
It also found four defects a library version must fix: an unauthenticated local endpoint, a
`skill` name collision with copilot/devin built-ins, title-based permission matching, and the
hard-coded empty `mcpServers`.

## What Changes

- **New constructor** `createCliClient({ style, command, … every in-process option })` returning
  an ordinary client plus `close()` and `transport()`. `style` is a closed enum
  `CliStyles.Claude | Codex | Copilot | Opencode | Devin | Gemini | Goose | Custom` — never a string.
  `command` (binary path or name) is **required**; no binary is embedded or defaulted.
- **Shared style data** in `conformance/cli-styles/*.json` (+ `preamble.txt`), embedded
  byte-identically by every port: argv template, prompt mode, answer source, an optional `acp`
  launch (`args`, `projectMcpFile`), and a `verified` note (`UNVERIFIED` where not live-run).
- **ACP primary.** When the style has an ACP launch (or `acpCommand` is given) and the binary
  answers `initialize`, the toolkit is served as a local streamable-HTTP MCP server guarded by a
  **per-session bearer token**, and listed in `session/new` `mcpServers` (http entry when the agent
  advertises `mcpCapabilities.http`, else a stdio bridge; a project MCP file in a temp cwd for
  agents that ignore session servers). The `skill` tool is served as **`load_skill`**. Permission
  requests are decided **per `toolCallId`**: only our served tools are allowed.
- **One-shot CLI fallback** when there is no ACP launch or it fails to start: our envelope
  (preamble + OpenAI-shaped request), the tolerant six-step parser, tool calls executed by the
  toolnexus loop; streaming refused; pinned errors; 300 s default per call.
- **ACP model source:** `ACPOptions` gains `MCPServers` (fixes the always-empty list),
  `ClientCapsMeta`, `Model` (set_config_option → set_model), `Stderr`; the agent runs in its own
  process group; a request fails fast when the agent exits; unknown agent→client requests are
  answered with -32601.
- **Go CLI:** `toolnexus mcp-bridge <url>` (stdio ⇄ HTTP, token from `TOOLNEXUS_MCP_TOKEN`).
- **Go first.** js, python, java, csharp, elixir, clojure and rust follow as tracked, unchecked
  tasks.

## Capabilities

### New Capabilities

- `cli-client`: agent CLIs as tool-calling clients — style enum, shared style data, ACP transport
  with the toolkit served as MCP, the one-shot fallback, and their pinned errors.

### Modified Capabilities

(none — the ACP model source has no archived capability spec yet; its `mcpServers` change is
specified here.)

## Impact

- New: `SPEC.md §8C`, `conformance/cli-styles/`, `golang/cli.go`, `golang/cli_acp.go`,
  `golang/clistyles/` (copy), `golang/examples/cliclient/`, `toolnexus mcp-bridge`.
- Changed: `golang/acp.go` (additive options; process group; fail-fast on exit).
- Vendor names appear only as style *data* (owner ruling 2026-11-04, ADR 0037); no per-vendor
  function, type or code path.
