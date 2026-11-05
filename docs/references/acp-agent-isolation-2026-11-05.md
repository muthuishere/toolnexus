# ACP agent isolation and model discovery — 2026-11-05

Research for PR #138 (`cli_acp.go`, `conformance/cli-styles/*.json`, ADR 0037). Question: when toolnexus
drives a coding agent over ACP, can each session see **only our MCP server** (or none), and nothing of
the agent's own skills, MCP servers, plugins, instruction files, hooks or memory? Plus: how to list and
pick models per agent.

Method: a 90-line Python ACP driver (initialize → session/new with one MCP server `toolnexus` exposing
`magic_word` → session/prompt), stdio and streamable-HTTP variants, permissions auto-allowed. Two
prompts: `reply with just: ok` (cost of the empty context) and "call magic_word" (our MCP still works).
Tokens are the agent's own figures: ACP `usage_update.used` (context in use) and/or the
`session/prompt` result `usage`. Subscriptions only; the owner's real configs were never modified;
no auth file was read. Scratch: `/private/tmp/claude-501/.../scratchpad/acpiso/`.

## Matrix — isolation

| agent (version) | loads by default over ACP | isolation knob (verified) | trivial-prompt tokens default → isolated | our MCP callable isolated | gap |
|---|---|---|---|---|---|
| claude (`@agentclientprotocol/claude-agent-acp@0.100.0`, claude 2.1.318) | `settingSources: [user, project, local]` hard-coded (dist/acp-agent.js:9207): CLAUDE.md, user+project skills, plugins, user/project MCP (+claude.ai connectors), hooks, settings (incl. permission `defaultMode`), all built-in tools | **`session/new _meta.claudeCode.options`**: `{"settingSources":[],"strictMcpConfig":true,"plugins":[],"skills":[],"tools":[]}` — every key is an allowed passthrough (`OPTION_REBUILDS_SESSION`) | **34,073 → 21,769** (settings off) **→ 3,368** (+ `tools:[]`) | yes, 3,436 total; and with `tools:[]` the MCP tool is called directly (default path goes through `ToolSearch` first) | none. Subscription login unaffected (keychain). Side effect: user `defaultMode` (owner has bypassPermissions) is no longer inherited — good. |
| copilot (1.0.105, `--acp`) | `~/.copilot` MCP config, built-in GitHub MCP, custom instructions (AGENTS.md / copilot-instructions), skills, plugins, ~30 built-in tools | launch flags: `--disable-builtin-mcps --no-custom-instructions --available-tools=toolnexus-<tool>,…` | **16,343 → 15,200** (flags w/o tool filter) **→ ~3,000** (+ `--available-tools`) | yes — **only over HTTP MCP** (stdio session servers are silently not attached; advertises `mcpCapabilities.http`). Tool name is `toolnexus-<tool>` | `--available-tools` needs our tool names at launch (we know them); it emits an `Info: Disabled tools: …` agent_message chunk that must be filtered from the answer. No per-session knob, no flag to skip user `~/.copilot` skills/MCP by name-less switch (`COPILOT_HOME`=temp would, but moves login state — not tried). |
| opencode (v2.0.23) | `~/.config/opencode` (user MCP e.g. `rea`, skills, plugins), project `opencode.json`/AGENTS.md, Claude-compat skills; MCP tools are reached via a code-mode **`execute`** tool (`tools.toolnexus.magic_word()`) | env: `OPENCODE_DISABLE_PROJECT_CONFIG=1` + `OPENCODE_CONFIG_CONTENT='{"tools":{"*":false,"execute":true,"toolnexus_*":true}}'` | **11,859 → ~1,000** | yes (via `execute`) | `"*":false` alone also hides our MCP from `execute`'s catalog (model searched forever) — must re-allow `execute` **and** `toolnexus_*`. `OPENCODE_CONFIG_DIR`=empty dir alone made it *worse* (15,733): owner config was pruning things. Config content merges, it does not replace. |
| codex-acp (0.16.0) | `~/.codex/config.toml`: MCP servers, apps/connectors, plugins, hooks, skills, AGENTS.md | `-c` overrides on codex-acp (same as codex): `-c mcp_servers={} --disable apps --disable plugins --disable hooks -c project_doc_max_bytes=0 -c skills.include_instructions=false` | ACP **unmeasurable** (every model 400s: "not supported when using Codex with a ChatGPT account"). One-shot `codex exec` with the same knobs: **26,905 → 17,204** | n/a over ACP | codex-acp unusable with a ChatGPT account (already in codex.json notes). Remaining 17k is codex's base prompt + built-in tools; no verified knob to drop built-ins. `--strict-config` exists for CI. |
| devin (2026.8.18) | user config `~/.config/devin/config.json`, rules, skills, plugins, project `.mcp.json`; **ignores session/new mcpServers**; MCP behind a lookup tool | `devin --agent-config <file> acp` (global flag) — strict schema: `system-instructions`, `allowed-tools`, `permissions`, `mcp-servers`, `extensions`; also `--config <path>` to replace the user config | **24,040 → 24,039** (no change with `allowed-tools`) | flaky: with `mcp-servers` in agent-config the call landed only after 15 tool calls (39,569 tokens), agent claimed the server "didn't register" | **real gap**: no knob measurably shrank the context. `--agent-config mcp-servers` could replace the temp-cwd `.mcp.json` hack but needs more work; keep `systemHint`. |
| gemini (0.66.0 via npx) | `~/.gemini` settings, extensions, skills (`~/.gemini/skills` exists), GEMINI.md, settings MCP | flags (from `--help`, unmeasured): `--extensions none`, `--allowed-mcp-server-names toolnexus`, `--skip-trust` (needed, already in style) | **not measured** — not authenticated on this machine ("Gemini API key is missing") | not measured | unverified; instruction file / skills knobs need settings (`GEMINI_CLI_SYSTEM_SETTINGS_PATH`) — check when an auth exists. |
| goose | not installed | docs: profiles/`GOOSE_*` env, extensions in `config.yaml` | not measured | not measured | unverified. |

**Headline:** the agent's own baggage is 3–30k tokens per turn, and for the three that work over ACP
today the clean knob cuts it **~10x** (claude 34k→3.4k, copilot 16k→3k, opencode 12k→1k) while
subscription login and our MCP keep working. The big win is never "settings off" — it is turning off
the agent's **built-in tools**, which toolnexus does not want the agent to run anyway.

## Matrix — models

| agent | list (ACP) | list (CLI) | select | verified |
|---|---|---|---|---|
| claude | `session/new.configOptions[category=model]`, 13 values (`default, opus, fable, sonnet, haiku`, full ids) | none (aliases only) | `session/set_config_option {configId:"model", value}` → `currentValue` confirms | yes, set `haiku` |
| copilot | configOptions model **and** legacy `models.availableModels` — only `auto` (twice, one with `_meta.copilotUsage`) | none; `--model <id>` / `COPILOT_MODEL` | set_config_option | yes (`auto`); ACP does not enumerate real models |
| opencode | configOptions model, 592 values (`provider/model`) | `opencode models` (593 lines) | set_config_option | yes, set `anthropic/claude-haiku-4-5` (spike 5bc1a4a also ran a turn) |
| devin | configOptions model, 2 values (`swe-2-flash-medium`, `swe-1-6-slow`) | none (`--model`, `DEVIN_MODEL`) | set_config_option | yes, set `swe-1-6-slow` |
| codex | codex-acp configOptions model, 5 values, all rejected for ChatGPT accounts | `codex debug models` (JSON array, 10 slugs: `gpt-6.1-sol`, …) | `-m` / `-c model=` | list yes; ACP selection useless |
| gemini / goose | — | — | — | not measured |

Consistent with spike `spike/acp-models` (5bc1a4a): read `configOptions[category=model]` (flat or
grouped), fall back to legacy `models`/`session/set_model`, select with `session/set_config_option`
and refuse unless the returned `currentValue` matches. No vendor "free" flag: copilot's `_meta.copilotUsage`
and opencode's `-free` suffix stay raw `_meta`/name, filtered by a host predicate.

## Recommendation — one `isolation` block in the style JSON

Keep it to what the style JSON already is: data. Add one optional block next to `acp.args`:

```json
"acp": {
  "args": ["--acp"],
  "isolate": {
    "args": ["--disable-builtin-mcps", "--no-custom-instructions", "--available-tools={{toolnexusTools}}"],
    "env": {},
    "meta": null
  }
}
```

- claude: `meta: {"claudeCode":{"options":{"settingSources":[],"strictMcpConfig":true,"plugins":[],"skills":[],"tools":[]}}}`
- opencode: `env: {"OPENCODE_DISABLE_PROJECT_CONFIG":"1","OPENCODE_CONFIG_CONTENT":"{\"tools\":{\"*\":false,\"execute\":true,\"toolnexus_*\":true}}"}`
- copilot: args above (one new placeholder: our tool names, `toolnexus-<name>`, comma-joined).
- codex / devin / gemini / goose: block absent or partial + a `notes` line naming the gap.

One option on the Go side (`Isolate bool`, default **on** for ACP — the toolkit is the point) merges
args/env into the launch and `meta` into `session/new._meta`. Three mechanisms, all already in the
launcher's vocabulary; no files written.

| option | pros | cons |
|---|---|---|
| **per-style `isolate` block (flags/env/_meta)** — recommended | data-only, no files, auditable in conformance JSON, same in 8 ports, measured 10x win | needs a `{{toolnexusTools}}` placeholder for copilot; knobs drift with agent versions (re-verify per style, as today) |
| generated profile/config files | covers agents with only file-based config (devin agent-config, gemini settings) | temp-file lifecycle, per-agent schemas, strict parsers reject unknown fields; more code in 8 ports |
| temp HOME / `COPILOT_HOME` / `OPENCODE_CONFIG_DIR` | total isolation in one stroke | breaks or moves subscription login (auth lives there or in keychain keyed by home); opencode measured *worse*; fragile |

Gaps with no clean option today: **devin** (no knob reduced context; MCP discovery flaky),
**codex-acp** (unusable with ChatGPT accounts, so moot), **gemini/goose** (unverified here). For these,
ship without `isolate` and say so in the style notes — the uniform block makes the absence visible.

Also worth fixing in PR #138 regardless: copilot needs the **HTTP** MCP transport (stdio session
servers are dropped silently), and the copilot `Info: Disabled tools` chunk must not leak into the answer.
