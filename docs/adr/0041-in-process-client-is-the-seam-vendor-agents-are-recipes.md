# ADR 0041 — The in-process client is the seam; vendor agents are recipes

- Status: **ACCEPTED** (owner, 2026-11-05)
- Supersedes: [ADR 0037](0037-cli-client-styles.md) (CLI client styles — never released)
- Related: ADR 0030 (the in-process seam), ADR 0031 (ACP: a warm session is the feature),
  the standing rule "no vendor names in public API"

## Context

ADR 0037 built `createCliClient` in all eight ports: a closed `CliStyles` enum (Claude, Codex,
Copilot, Opencode, Devin, Gemini, Goose), one shared JSON file per vendor (argv, prompt mode,
ACP args, a devin system hint), a one-shot CLI transport with a prompt preamble and a six-step
reply parser, a permission allow-list keyed on how each vendor titles a tool call, a stdio↔HTTP
bridge, and — in the follow-up drafts — isolation flags per vendor and model lists.

It worked, live, against six agents. It is also the wrong thing for this library to own:

- **Every vendor field rots on the vendor's release schedule, not ours.** Flags, ACP launch
  spellings, tool-title formats and model ids changed several times during the two days the
  work took. A library release cannot chase that; eight ports multiply it.
- **It put vendor names in the public API**, which the project had already ruled out.
- **The vendor-neutral part was small and already general.** What actually let any agent call
  a toolnexus toolkit was: an in-process client that streams, an ACP client that sends what it is
  given, and a way to serve the toolkit as MCP with a token. Everything else was policy about
  particular vendors — knowledge that belongs to the caller, and to docs that can change daily.

## Decision

toolnexus ships three vendor-neutral pieces (SPEC §8C) and documents the rest as recipes.

**Kept, all eight ports:**

1. `createInProcessClient` with the **streaming generate** (`generateStream(req, emit)`), so any
   model source — an ACP agent, a local runtime, a test double — streams for real.
2. A **plain ACP client** (`loadACP`): spawn, `initialize`, `session/new` with caller-supplied
   `mcpServers` / `_meta` / args / env **passed through untouched**, optional model passthrough,
   `session/prompt`, streaming of `agent_message_chunk` (and thoughts / agent tool events,
   `via:"acp"`), the permission hook, prompt failure when the agent exits, `-32601` for requests
   it does not implement, process-group teardown.
3. **`toolkit.asMcpServer()` → `{url, token, close}`**: the §7C server on loopback with a per-call
   bearer token — the smallest thing that lets an agent call the toolkit. The caller writes the
   `mcpServers` entry in whatever shape its agent accepts.

**Dropped:** the `CliStyles` enum and `createCliClient`, `conformance/cli-styles/*.json`, the
one-shot CLI transport, preamble and reply parsing, the `load_skill` rename, the vendor-title
permission allow-list, the devin system hint, isolation flags, `toolnexus mcp-bridge`, model lists.

**Recipes** — "drive Claude Code / Codex / Copilot / opencode / … through toolnexus" — live in the
docs site and are written against the three pieces above. They may name vendors; the library may not.

## Consequences

- The public surface shrinks to things whose behaviour we can pin in `conformance/` without a
  vendor binary (`conformance/acp-client/cases.json`, all fakes).
- A consumer pinned to the unreleased drafts (`7cb7460` on #138, `9ec1830` on #145) loses
  `createCliClient`/`CliStyles`; the migration is: `loadACP({command, args, mcpServers:[entry from
  asMcpServer]})` + `createInProcessClient({generateStream: acp.generateStream})`.
- The one-shot transport for agents without ACP is no longer offered. A caller who needs it writes
  a `generate` that runs their CLI — the seam exists for exactly that.
- Permission policy is the caller's: the default stays "first allow option" (§8); a recipe shows
  how to allow only toolkit tools.
