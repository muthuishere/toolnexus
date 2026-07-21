# Design — add-cli-agent-runtime

## Shape

The CLI is a thin, deterministic mapping from a **config file (+ flag overrides)** onto the
already-shipped Go library entry points. No new library capability; the CLI is a loader + a
command dispatcher.

Confirmed library entry points (Go, shipped in 0.10.0):

- `toolnexus.CreateToolkit(ctx, Options{McpConfig, SkillsDir, ...}) (*Toolkit, error)`
- `toolnexus.CreateClient(ClientOptions{BaseURL, Style, Model, APIKey, SystemPrompt}) *Client`
- `agents.FromDir(dir string, FromDirOptions{Does, Name, Model, Tools, Memory}) *Agent`
  — persona bootstrap (`home.go:170`).
- `agents.New(name string, Spec{Does, Tools, Soul, SoulFile, Team, Budget, Model, WaitFor,
  OnSpawn, OnClose}) *Agent` (`agent.go:14,44`).
- `(*Agent).Run(agents.Options{LLM, Transport, Store, InboxCap, MaxConcurrentTurns, Shutdown,
  Clock}, prompt string) (TaskResult, *Runtime)` (`agent.go:97`).
- `agents.Budget{MaxTurns, MaxTokens, MaxToolCalls, MaxWall, MaxChildren, MaxConcurrent,
  MaxDepth}` (`runtime.go:67`).
- `(*Toolkit).Serve(addr string, ServeOptions{Client, A2A: *A2AConfig{Name, Description,
  Version, Skills, Store}, MCP: *MCPServeConfig}) (*ServeHandle, error)` (`toolkit.go:276`).

The CLI never reaches into runtime internals — it only builds these structs from config.

## The config file (`-f, --file <agent.json>`)

One JSON file is the source of truth. Every field is optional; a missing block falls back to
flags, then to library defaults. Explicit flags **override** the matching file field.

```jsonc
{
  "model":   { "baseUrl": "https://openrouter.ai/api/v1", "style": "openai",
               "model": "openai/gpt-4o-mini", "system": "…" },
  "toolkit": { "mcp": "./mcp.json", "skills": ["./skills"], "builtins": true },

  "agent": {                          // the ROOT agent
    "name": "ava",
    "dir":  "./ava",                  // persona home (FromDir) — OR inline:
    "soul": "You are …", "soulFile": "./SOUL.md", "does": "…",
    "model": "…",                     // overrides model.model for the root
    "tools": ["read","grep","bash"],  // toolkit VIEW allowlist; omit ⇒ full toolkit
    "budget": { "maxTurns": 20, "maxTokens": 200000, "maxToolCalls": 200,
                "maxWallMs": 600000, "maxChildren": 8, "maxConcurrent": 8, "maxDepth": 3 },
    "compactAt": 150000,              // wire compactor{maxTokens} on beforeLLM
    "heartbeatMs": 30000,             // persona heartbeat (dir agents only)
    "memory": true                   // FromDir memory builtin (default true)
  },

  "team": [                           // SUB-AGENTS — enabling this gives root the `task` tool
    { "name": "explorer", "does": "search the codebase and report findings",
      "dir": "./agents/explorer" },
    { "name": "verifier", "does": "adversarially check a result; refuse on doubt",
      "soul": "You are a skeptical reviewer …",
      "tools": ["read","grep"], "model": "openai/gpt-4o-mini",
      "budget": { "maxTurns": 3, "maxTokens": 8000 },
      "team": [ /* members may nest */ ] }
  ],

  "serve": { "addr": ":8080", "a2a": true, "mcp": false,
             "name": "ava", "description": "…", "store": "file:./tasks" }
}
```

### Field → library mapping (pinned)

| Config path | Maps to |
|---|---|
| `model.*` / `--base-url` `--style` `--model` `--system` `--api-key` | `ClientOptions` + `agents.Options.LLM` |
| `toolkit.mcp` / `--config` · `toolkit.skills` / `--skills` · `toolkit.builtins` | `toolnexus.Options{McpConfig, SkillsDir, Builtins}` |
| `agent.dir` / `--dir` | `agents.FromDir(dir, FromDirOptions{Does, Name, Model, Memory})` |
| `agent.soul` / `agent.soulFile` / `agent.does` / `agent.tools` | `agents.New(name, Spec{Soul, SoulFile, Does, Tools})` |
| `agent.budget.*` | `agents.Budget{...}` (`maxWallMs` → `MaxWall = ms·time.Millisecond`) |
| `agent.compactAt` | `compactor(CompactorOptions{MaxTokens})` on `beforeLLM` |
| `agent.heartbeatMs` | `startAgent(..., everyMs)` heartbeat loop (dir agents) |
| `team[i]` | a child `*Agent` (FromDir or New), attached to parent `Spec.Team` |
| `serve.*` | `Toolkit.Serve(addr, ServeOptions{A2A, MCP})` |

### Team declaration (the design decision)

A team member is an `*Agent`, built exactly like the root:

- `name` (required, unique among siblings) and `does` (required — the routing string the
  delegating model sees) are mandatory.
- **Identity** is either a persona `dir` (→ `FromDir`) **or** inline `soul`/`soulFile`. If both
  a `dir` and inline `soul` are given, `dir` wins (matches the library's file-wins rule).
- `tools` is an allowlist of tool names from the **resolved toolkit**; omitted ⇒ the member
  inherits the full toolkit view. Scoping is the security boundary (a verifier gets read-only).
- `model`, `budget` optional; `team` may nest (a member with its own team gets its own `task`
  tool — the library already supports arbitrary depth via `Spec.Team`).
- Attaching a non-empty `team` to an agent is what enables its `task` tool. No team ⇒ no `task`
  (the shipped opt-in rule, unchanged).

The root's `Spec.Team` is the top-level `team[]`; nesting recurses. The registry is built via
`agent.Registry()` (transitive team closure), as the library intends.

## Global skills home + `install`

- `$TOOLNEXUS_HOME` (default `~/.toolnexus`) is the CLI's runtime home; skills live under
  `$TOOLNEXUS_HOME/skills/`. This is a **runtime dir**, not repo state — it holds installed
  skills only, no code.
- `toolnexus install --skills <src>` copies every `**/SKILL.md` tree under `<src>` into
  `$TOOLNEXUS_HOME/skills/<skill-name>/`. Re-installing the same skill overwrites it
  (idempotent). `<src>` is a local directory in v1; a git URL (`clone → copy`) is a stretch
  goal, explicitly deferred if it complicates the first cut.
- **Resolution order** for `run`/`serve`/`tools`: global skills (`$TOOLNEXUS_HOME/skills`) are
  loaded first, then `--skills`/`toolkit.skills` from the invocation. On a **name collision the
  local (flag/config) skill wins** — a project can shadow a globally installed skill. This is
  the only new resolution rule; MCP servers and builtins are unaffected.
- `--no-global-skills` opts out of the global load for a hermetic run.
- Skills stay data, not code: an installed skill is a `SKILL.md` + resources (scripts in any
  language); the CLI runs them via its `bash`/skill builtins. Installing never runs skill code.

## npm distribution (CLI wrapper)

- A separate npm package `toolnexus-cli` (NOT the existing `toolnexus` JS library) declares
  `bin: { toolnexus: "…" }`. On `npm install`, a postinstall step detects `process.platform` +
  `process.arch`, downloads the matching `toolnexus_<version>_<os>_<arch>` asset from the
  GitHub Release for the package version, verifies it against `SHA256SUMS`, and places it as the
  bin. The package version tracks the release version so the binary and wrapper always match.
- Failure is loud (a clear "no prebuilt binary for <platform>; download from <releases-url>"),
  never a silent half-install.
- Published from `release.yml` alongside the other ports, gated by `ENABLE_GO` (the CLI is the
  Go artifact). No secret beyond npm OIDC trusted publishing (same as the `toolnexus` library).

## Commands

- `toolnexus install --skills <src>` — install skills into the global home (above).
- `toolnexus run [-f agent.json] [flags] [--once "…"] [--json] [--no-global-skills]` — build the root agent, run one
  prompt (`--once`) or a REPL. `--json` prints `{"text","status","usage":{...}}` (from
  `TaskResult`) to **stdout**; human-readable log lines go to **stderr** (so `--json` stdout is
  clean to pipe). Without a config and without `--dir`/`team`, falls back to today's bare client
  loop — **fully back-compatible**.
- `toolnexus serve [-f agent.json] [--addr :8080] [--a2a] [--mcp] [flags]` — build the toolkit
  (+ client), call `Toolkit.Serve`, print the bound address, block until SIGINT/SIGTERM, then
  `handle.Stop()`. Flags override `serve.*`.
- `toolnexus tools [-f agent.json] [--config …] [--skills …]` — unchanged behavior, now also
  accepts `-f` so introspection matches what `run`/`serve` build.

### Human-in-the-loop

`run` resolves a §10 suspension through a CLI `waitFor`:

- **REPL / interactive**: print the `Request` (prompt + any options) to stderr, read one line
  from stdin, resume with an `Answer`. This is the natural terminal approval loop.
- **`--once` / `--json`**: do **not** block on stdin (there may be no TTY). Return the result
  with `status:"pending"` and the request embedded, so a calling app can persist and resume via
  a follow-up invocation. (Durable resume across processes reuses the library's task-key
  reattachment; a config `serve.store`/a run store path makes it durable — documented, not
  auto-enabled.)

## Binary distribution

`release.yml` gains a `cli-binaries` job (gated on the `release` event; skipped on
`workflow_dispatch`, which has no Release to attach to):

- Matrix: `darwin/amd64`, `darwin/arm64`, `linux/amd64`, `linux/arm64`, `windows/amd64`
  (windows gets `.exe`).
- `CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=<v>"` from `golang`, output
  `toolnexus_<version>_<os>_<arch>[.exe]`.
- Compute `SHA256SUMS`; upload every binary + the checksums file with
  `gh release upload "v<version>" …` (or `softprops/action-gh-release`).
- Gated by `ENABLE_GO` (same variable as the module tag) so it flips on/off with the Go port.
- A `main.version` var is added to the CLI (defaults to `"dev"`) and printed by
  `toolnexus --version` / `version`.

Skills remain external and are **not** bundled — the binary runs a `skills/` folder's
`SKILL.md` + scripts through its `bash`/skill builtins, so a Python-scripted skill works with no
Go dependency.

## Secrets invariant (carried, not weakened)

- `--api-key` empty ⇒ resolved from env (`OPENROUTER_API_KEY`/`OPENAI_API_KEY`/
  `ANTHROPIC_API_KEY`); the config file MAY name an env var but SHOULD NOT contain a literal
  key. MCP remote `headers` keep expanding `${ENV_VAR}` at call time.
- No key value is ever printed — not in `--json`, not in logs, not in `serve`'s startup line,
  not in `tools`. This is the existing CLI invariant (`main.go:132`), preserved and tested.

## Out of scope / non-goals

- A cross-port `loadAgent(config)` library API (the schema is designed to generalize later).
- Auth on `serve` (core has none by design).
- Config hot-reload / directory watching.
- Any change to shipped library behavior — the CLI only *composes* it.
