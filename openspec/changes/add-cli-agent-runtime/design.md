# Design — add-cli-agent-runtime

Reconciled in the 2026-07-21 huddle (note:
`~/.config/muthuishere-agent-skills/toolnexus/feat-cli-agent-runtime/huddle/2026-07-21.md`).
The CLI is a thin, deterministic mapping from a **config file (+ flag overrides)** onto the
already-shipped Go library. No new library capability; one optional small seam (A2A send).

## Confirmed library entry points (Go, shipped 0.10.0)

- `toolnexus.CreateToolkit(ctx, Options{McpConfig, SkillsDir, Builtins}) (*Toolkit, error)`
- `toolnexus.CreateClient(ClientOptions{BaseURL, Style, Model, APIKey, SystemPrompt}) *Client`
- `agents.FromDir(dir, FromDirOptions{Does, Name, Model, Tools, Memory}) *Agent` (`home.go:170`)
- `agents.New(name, Spec{Does, Tools, Soul, SoulFile, Team, Budget, Model, WaitFor,
  OnSpawn, OnClose}) *Agent` (`agent.go:14,44`)
- `(*Agent).Run(agents.Options{LLM, Transport, Store, ...}, prompt) (TaskResult, *Runtime)`
  (`agent.go:97`)
- `agents.Budget{MaxTurns, MaxTokens, MaxToolCalls, MaxWall, MaxChildren, MaxConcurrent,
  MaxDepth}` (`runtime.go:67`)
- `(*Toolkit).Serve(addr, ServeOptions{Client, A2A:*A2AConfig{Name,Description,Version,Skills,
  Store}, MCP:*MCPServeConfig}) (*ServeHandle, error)` (`toolkit.go:276`); `ServeHandle.Stop()`
  (`serve.go:217`).
- Listener facts verified: `serve.go:540-553` maps a §10 `pending` run to A2A `input-required`
  carrying the request prompt; `serve.go:379-390` extracts `contextId` and threads it through
  `runTask(text, contextID)` so a follow-up message resumes the same conversation.

## The listener = serve (the core)

"Every agent always has a listener" ⇒ resident mode runs `Toolkit.Serve` with A2A on. A2A is
the actor mailbox over HTTP: `message/send` = post to the agent; `input-required` = ask-back;
`contextId` = the inbox thread. No bespoke socket protocol, no new library code.

- **Registry** (CLI-side): on `up`, write `$TOOLNEXUS_HOME/agents/<name>.json` =
  `{name, url, pid, card}`, mode `0600`. `send`/`ps`/`stop` read it. `stop` → `ServeHandle.Stop()`.
- **Security** (Senthil): bind `127.0.0.1:<port>` by default; off-box exposure requires an
  explicit `--addr 0.0.0.0:…`. "No auth in core" stays loud in docs.
- **Inter-agent**: agent A → agent B is an A2A call to B's registered URL. Reuses the shipped
  A2A outbound path (`AddAgent` / `a2a.go`).

## Config file (`-f, --file agent.json`) — source of truth, flags override

```jsonc
{
  "model":   { "baseUrl": "…", "style": "openai", "model": "…", "system": "…" },
  "toolkit": { "mcp": "./mcp.json", "builtins": true },   // skills are global, not here
  "agent":   { "name": "ava", "dir": "./ava",             // persona home (FromDir) — OR inline:
               "soul": "…", "soulFile": "./SOUL.md", "does": "…",
               "tools": ["read","grep","bash"], "model": "…",
               "budget": { "maxTurns": 20, "maxTokens": 200000, "maxToolCalls": 200,
                           "maxWallMs": 600000, "maxChildren": 8, "maxConcurrent": 8, "maxDepth": 3 },
               "compactAt": 150000, "heartbeatMs": 30000, "memory": true },
  "team":    [ { "name": "explorer", "does": "search the codebase", "dir": "./agents/explorer" },
               { "name": "verifier", "does": "adversarially check a result",
                 "soul": "…", "tools": ["read","grep"], "budget": { "maxTurns": 3 },
                 "team": [ /* nestable */ ] } ],
  "extends": "support",                                    // modified-agent composition (see below)
  "serve":   { "addr": "127.0.0.1:8080", "a2a": true, "mcp": false, "name": "ava",
               "store": "file:./tasks" }
}
```

Field→library mapping is unchanged from the earlier draft (model.*→ClientOptions+Options.LLM;
toolkit.*→toolnexus.Options; agent.dir→FromDir; agent inline→New Spec; budget→Budget with
`maxWallMs`→`MaxWall`; team[i]→child *Agent, recursive; serve→ServeOptions). Registry built via
`agent.Registry()` (transitive team closure).

## Global skills + install fan-out (DECIDED)

- **One home:** `${TOOLNEXUS_SKILLS_DIR:-~/.toolnexus/skills}`. Every `run`/`up`/`tools`
  **always** loads it. No per-project dir, no shadowing.
- `toolnexus install --skills <src>` (mirrors `window-ctl-skill/install.sh`, fixing its bug):
  1. Place `<src>`'s skill in the canonical `~/.toolnexus/skills/<name>` (copy vs symlink →
     **OPEN FORK 1**).
  2. Symlink `~/.claude/skills/<name>` → canonical (Claude Code discovery).
  3. Symlink `~/.agents/skills/<name>` → canonical (codex; winctl's rule: only if codex present
     or `AGENTS_SKILLS_DIR` set → **OPEN FORK 2**).
  - **Collision-safe:** only replace a symlink resolving into `~/.toolnexus/skills`; a foreign
    real dir/link is **warned + skipped**, never `rm -rf`'d. Prints each action.
  - Overrides `TOOLNEXUS_SKILLS_DIR`/`CLAUDE_SKILLS_DIR`/`AGENTS_SKILLS_DIR`. `--no-share` = step
    1 only. Windows: copy/junction fallback (symlinks need dev-mode).
- `toolnexus uninstall --skills <name>`: remove the mirror links (ours only), then the canonical.
- Installing never runs skill code; a skill is `SKILL.md` + resources run via the `bash` builtin.

## Command surface

| Verb | What | Maps to (shipped) |
|---|---|---|
| `create <name> [--from base] [--add-mcp n=cmd] [--dir DIR]` | scaffold/compose an agent home (`SOUL.md` + `agent.json`, `extends`) | file scaffold + loader |
| `up [name\|-f agent.json] [--addr …]` | **core**: resident agent + A2A listener; register name→URL; block until SIGINT | `Toolkit.Serve` + registry |
| `send <name> "msg" [--json]` | message a resident agent; prompt on `input-required` | A2A client (OPEN FORK 4) |
| `ps` / `stop <name>` | list / stop resident agents | registry + `ServeHandle.Stop()` |
| `run [-f] [--once "…"] [--json]` | batch one-shot / REPL, no listener (scripting, back-compat) | `agent.Run` |
| `tools [-f]` | list resolved tools + MCP status | `Toolkit.Tools` |
| `install --skills <src>` / `uninstall --skills <name>` | manage the global skills home + fan-out | copy/symlink |

### Human-in-the-loop
- **`up` / resident**: §10 suspension surfaces to the peer as A2A `input-required`; the peer's
  next `send` (same `contextId`) resumes. This is the "ask back over the listener" path.
- **`run --once`/`--json`**: do not block on stdin; return `status:"pending"` + the request.
- **`run` REPL**: print the request, read one stdin line, resume.

### Modified agent (composition)
`create billing --from support --add-mcp stripe=…` writes a small `billing/agent.json` with
`extends: "support"` + one MCP server. The loader merges base then overlays this file (OPEN
FORK 3: `extends` live-merge vs copy-base-on-create).

## Distribution

- `release.yml` `cli-binaries` job (release event only, gated `ENABLE_GO`): matrix
  darwin/linux/windows × amd64/arm64; `CGO_ENABLED=0 -trimpath -ldflags "-s -w -X
  main.version=<v>"`; assets `toolnexus_<v>_<os>_<arch>[.exe]` + `SHA256SUMS` via `gh release
  upload`. Add `main.version` (default "dev") + `toolnexus --version`.
- npm wrapper `@muthuishere/toolnexus-cli` (separate from the `toolnexus` JS library):
  `bin:{toolnexus}`, postinstall detects platform, downloads + checksum-verifies the matching
  release binary; loud failure with the releases URL when unavailable. Published from
  `release.yml`, gated `ENABLE_GO`, npm OIDC. Version tracks the release.

## Secrets invariant (carried)
`--api-key` empty ⇒ from env; config MAY name an env var, SHOULD NOT hold a literal key; MCP
`headers` expand `${ENV_VAR}` at call time. No key value ever printed — `--json`, logs, the
`up`/`serve` startup line, `tools`. Existing invariant (`main.go:132`), preserved + tested.

## OPEN FORKS (owner to decide before `/opsx:apply` — recorded, not blocking the proposal)
1. Canonical skill entry = **copy** (rec: owned, survives source deletion) vs **symlink-to-source**
   (`--link`, live edits, winctl-style).
2. Fan-out default: always mirror to Claude, agents only if codex present (winctl rule) vs mirror
   all roots unconditionally.
3. Modified agent = `extends:` live-merge (rec) vs copy-base-on-create.
4. The one seam: expose a tiny library `A2ASend(url,text,ctxId)` for `send` + agent-to-agent (rec,
   ~15 lines) vs hand-roll the JSON-RPC CLI-side (keeps "zero library change").
5. Default mode: does bare `toolnexus <agent>` default to resident `up` (owner: "always has a
   listener") with `--once` the batch opt-out, or is batch the default and `up` explicit?
