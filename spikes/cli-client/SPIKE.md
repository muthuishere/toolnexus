# Spike: CLI client styles (ADR 0037)

**Question:** can a one-shot agent CLI the user already pays for act as a tool-calling toolnexus
model, selected by a style name, with no per-vendor code?

**Answer: yes.** All five CLIs installed on the spike machine made a real tool call through the
toolnexus loop and answered with data that only the tool could supply.

## What is here

| file | what |
|---|---|
| `cli.go` | `CreateCliClient(CliOptions{Style, Model, Command, …})` over `toolnexus.CreateInProcessClient`; embedded styles; argv templating; envelope render; the six-step tolerant parser |
| `styles/*.json` | the seven built-in styles (data, meant to be shared byte-identically by every port) |
| `preamble.txt` | the pinned seven-line preamble (the same text as `add-acp-tool-calling`, PR #114) |
| `fake-cli.sh` | a hermetic fake CLI for the tests |
| `cli_test.go` | parser cases, style validation, unknown-style error, model rules, `Command` override, prompt via stdin, arg and file, system prompt in the request, a tool-call round trip, and the pinned errors (exit, empty, timeout, out file) |
| `main.go` | live runner: `go run . [style…]` |
| `live-results.json` | the recorded live run |

`go test ./...` passes in under a second, with no network.

## Live run (2026-11-04, macOS, each CLI's own default model)

Task: the toolkit holds one native tool, `lookup_order`, whose answer (tracking `ZX-7731-QP`)
exists nowhere else. System prompt: "You are a support assistant. Answer in one short sentence."
Prompt: "What is the tracking number for order A-1042?" A pass means: `lookup_order` was called by
the toolnexus loop, and the final text contains `ZX-7731-QP`.

| style | version (`--help` checked) | result | total | per invocation | prompt bytes (turn 1 / turn 2) |
|---|---|---|---|---|---|
| claude | 2.1.318 | PASS | 6.4 s | 3.8 s + 2.6 s | 1223 / 1511 |
| codex | codex-cli 0.160.0 | PASS | 11.8 s | 6.8 s + 5.0 s | 1223 / 1510 |
| copilot | 1.0.98 | PASS | 37.5 s | 23.2 s + 14.3 s | 1223 / 1544 |
| devin | 2026.8.18 | PASS | 28.7 s | 14.9 s + 13.8 s | 1223 / 1511 |
| opencode | v2.0.23 | PASS | 5.1 s | 2.9 s + 2.2 s | 1223 / 1510 |
| gemini | — | not installed, style UNVERIFIED | | | |
| goose | — | not installed, style UNVERIFIED | | | |

Every CLI used two invocations: one tool-call envelope, then one content envelope.

## Facts found

1. **The fence step of the parser is needed.** Devin wrapped its final answer in a ```` ```json ````
   fence despite "no markdown fences" in the preamble. The other four replied with bare JSON.
2. **Copilot adds trailing blank lines** to stdout; trimming before parsing is needed.
3. **Codex's stdout is progress, not the answer.** The final message has to come from
   `--output-last-message <file>`, so styles need an `out` answer source.
4. **Claude Code's own tools must be switched off** (`--tools ""`), or it may act on the request
   itself. The documented way is `--tools ""`; the other CLIs have no one-flag equivalent
   (Copilot only has allow/deny lists, Devin a permission mode), and every reply in this run
   was a bare envelope, with no sign of a tool of its own (not proven: the CLIs' own logs were not
   inspected).
5. **On this machine `claude` is a shell function** that adds `--dangerously-skip-permissions`.
   Child processes never see shell functions, so the client resolves the real binary on `PATH`.
   Users with a wrapper should know their flags are not applied.
6. **Start-up cost dominates.** Copilot spends about 14–23 s per call; this transport suits short
   loops, not chatty ones.
7. **No CLI doc was wrong for the flags used.** Every flag in the five verified styles matched the
   installed CLI's `--help`. (Issue #97's suggested Copilot flags `--log-level none` still exist,
   but `--silent` is the documented way to get only the answer.)

## Open questions for the owner

1. **Style directory.** Shared styles need one home that every port embeds: `styles/cli/` at the
   repo root, or `conformance/cli-styles/`? (Embedding from outside a package needs a copy step
   in Go, Rust and Elixir, as the media fixtures already do.)
2. **Agent tools.** Should every style switch off the CLI's own tools where a flag exists
   (Claude: yes; Codex: `--sandbox read-only`), or leave that to the user?
3. **Default timeout.** 600 s per invocation is generous; Copilot's worst call here was 23 s. Keep
   600 s or lower it to 300 s?
4. **Unverified styles.** Ship `gemini` and `goose` marked UNVERIFIED, or leave them out until
   someone verifies them on a machine that has them?
5. **Model names.** The style passes `model` through unchanged (`opencode` wants
   `provider/model`). Is pass-through enough, or should a style document its model format?
6. **Port order.** Go first, then the other seven, as for media?

## ACP primary: the user's own toolkit inside the agent (2026-11-04)

**Question:** does a user's own toolkit (MCP servers from `mcp.json`, agent skills, `defineTool`
functions) work through every agent CLI on this machine?

**Answer: yes, for all six installed agents** — five over ACP, codex through the CLI-client
fallback (its ACP adapter cannot reach the account's models).

`golang/acp.go` still sends `"mcpServers": []`, so the spike does not touch library code:
`acp_toolkit.go` opens its own ACP session. On open it serves the toolkit with `Toolkit.Serve`
(MCP profile, SPEC §7C) on `127.0.0.1:0`, puts that server into `session/new` `mcpServers`, and
stops it (and the agent's process group) on `Close`.

| file | what |
|---|---|
| `acp_toolkit.go` | `OpenToolkitSession(ctx, tk, ACPAgent{…}, ToolkitSessionOptions{…})`, the permission hook, `RunBridge` (stdio ⇄ streamable HTTP) |
| `acp_main.go` | `acp [agent…]` live matrix, `cli-matrix [style…]` fallback matrix, `fixture-mcp` (stdio MCP fixture), `bridge <url>` |
| `acp_toolkit_test.go` | hermetic: the test binary is the fake ACP agent and the bridge. http entry, stdio entry, ForceStdio, server stops with session, project `.mcp.json`, permission matching |
| `acp-results.json` | the recorded live matrix |

### The three checks

Each run makes three fresh random tokens. A check passes only if **our MCP server executed the
tool** (recorded server-side by `ServeOptions.OnCall`, not inferred from text) **and** the agent's
final text contains the token.

1. **mcp** — `vault_get_vault_code`, from a stdio MCP fixture server listed in the toolkit's
   `mcp.json` (re-served through the toolkit, so the agent never sees the fixture directly).
2. **skill** — the toolkit's `skill` tool loading `secret-recipe/SKILL.md` from a temp skills dir.
3. **native** — `get_badge_number`, a `NativeTool` (defineTool).

### Results (macOS, each agent's default model)

| agent | how launched | version | path | mcp | skill | native |
|---|---|---|---|---|---|---|
| claude | `npx -y @agentclientprotocol/claude-agent-acp@0.100.0` | adapter 0.100.0, Claude Code 2.1.318 | ACP http | PASS 6.2 s | PASS 5.1 s | PASS 4.4 s |
| claude | same | same | ACP stdio bridge (forced) | PASS 5.9 s | PASS 5.2 s | PASS 4.5 s |
| copilot | `copilot --acp` | 1.0.105 | ACP http | PASS 8.2 s | PASS 6.8 s | PASS 3.6 s |
| devin | `devin acp` | 2026.8.18 | ACP + project `.mcp.json` | PASS 15.7 s | PASS 5.3 s | PASS 18.9 s |
| gemini | `npx -y @google/gemini-cli@0.66.0 --acp --skip-trust` (API key via `sec run GEMINI_API_KEY`) | 0.66.0 | ACP http | PASS 27.9 s | PASS 13.5 s | PASS 16.3 s |
| opencode | `opencode acp` | v2.0.23 | ACP http | PASS 15.6 s | PASS 11.9 s | PASS 11.5 s |
| opencode | same | same | ACP stdio bridge (forced) | PASS 9.0 s | PASS 8.4 s | PASS 6.4 s |
| codex | `npx -y @zed-industries/codex-acp@0.16.0` | adapter 0.16.0 | ACP http | FAIL | FAIL | FAIL |
| codex | `CliStyles.Codex`, `Command: "codex"` | codex-cli 0.160.0 | CLI fallback | PASS 21.2 s | PASS 19.4 s | PASS 19.5 s |
| goose | — | not installed | — | not run | not run | not run |

Session open (initialize + session/new + MCP connect) is 0.5–3.8 s once the npx cache is warm
(claude's first open took 68 s for the download).

### The `mcpServers` shapes that worked

- **http** (claude, copilot, gemini, opencode — each advertises `mcpCapabilities.http: true`):
  `{"type":"http","name":"toolnexus","url":"http://127.0.0.1:<port>/mcp","headers":[]}`
- **stdio** (sent when `mcpCapabilities.http` is false; forced live on claude and opencode):
  `{"name":"toolnexus","command":"<this binary>","args":["bridge","http://127.0.0.1:<port>/mcp"],"env":[]}`
  — no `type` field. `env` and `headers` are arrays of `{name,value}`, never objects.
- **devin**: none. It advertises `http:false`, accepts the stdio entry, and then **ignores
  `session/new` mcpServers entirely**: it loads MCP from its own config (`_meta.mcpConfigPath`
  in its initialize result) plus project files `.mcp.json` / `.devin/config.json` in the session
  cwd. What worked: open the session in a fresh temp cwd holding
  `.mcp.json` = `{"mcpServers":{"toolnexus":{"type":"http","url":"http://127.0.0.1:<port>/mcp"}}}`,
  removed on close. Sending `clientCapabilities._meta["cognition.ai/mcp"]: true` flips its
  logged `mcp=true` but still does not load session servers.

### Facts found

1. **Codex's ACP adapter cannot run on this account.** `codex-acp@0.16.0` (the latest) offers
   only `gpt-5.2 … gpt-5.5`; the ChatGPT account rejects all of them (`model_not_found`), and a
   current model id (`gpt-6.1-sol`, from `~/.codex/models_cache.json`) set via
   `session/set_config_option` is rejected too. The installed `codex` CLI (0.160.0) has no ACP
   mode. So codex goes through the CLI client, where the toolnexus loop runs the tools — that
   passed all three. This is exactly the fallback case.
2. **Gemini needs `--skip-trust`.** Without it, gemini-cli treats the cwd as an untrusted folder
   and **silently drops every session MCP server**; the model then says the server "is not
   connected". `--experimental-acp` is deprecated in 0.66.0; use `--acp`. Without trust it also
   tried its own shell (`ps aux | grep mcp`), which the hook rejected.
3. **Name collision with an agent's own `skill` tool.** Copilot (and devin) ship a built-in tool
   called `skill`. Asked to "use the `skill` tool", copilot used its own ("Using skill:
   secret-recipe") and failed. The prompt had to say "the MCP tool named `skill` on the toolnexus
   server". For the library: the served name should not collide (consider exposing it as
   `load_skill` or mentioning the server in its description); tracked as an open question.
4. **Permission requests differ per agent.** copilot titles them with the bare tool name, gemini
   `"<tool> (toolnexus MCP Server)"` or `"skill(name: …)"`, devin sends an **empty title** (the
   name is only on the earlier `tool_call` update: `"Calling <tool> from toolnexus"`), and
   claude and opencode never asked at all (claude respects the user's own Claude Code permission
   settings; opencode auto-allows MCP). The hook therefore remembers `toolCallId → title` from
   `tool_call` updates and matches our server name and tool names. Matching on titles is
   heuristic: a library version should match on `toolCallId` against what our server is about to
   execute, or require the agent to name the server.
5. **Agents' own tools stay on.** Over ACP there is no general "no tools" switch: codex has a
   `read-only` mode (used), claude/copilot/opencode/gemini have none in ACP. The spike sends
   `clientCapabilities` `fs` and `terminal` as false and rejects every permission request that is
   not ours; tools an agent runs without asking (opencode's) are not controlled.
6. **The toolkit endpoint has no auth.** Any local process can call `127.0.0.1:<port>/mcp` while
   a session is open. `Serve` cannot require a header today; the library version should add a
   per-session bearer token (the http entry's `headers` carries it; the bridge adds it).
7. **Model ids.** `session/set_config_option {configId:"model"}` is the stable spelling (codex
   adapter), with `session/set_model` as the fallback. The id is passed through unchanged.
