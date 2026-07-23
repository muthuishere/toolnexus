# Proposal — add-cli-agent-runtime

## Why

toolnexus 0.10.0 shipped the full agent runtime — sub-agents (§7D), persona home (§7E),
compaction (§7F), inbound `serve` (§7B/§7C) — but **only as a library**, reachable only from the
six language ports. The `toolnexus` CLI (`golang/cmd/toolnexus`, `SPEC.md §9`) still exposes just
the 0.x taster: `run` (a plain client loop) and `tools`.

The CLI is the **one polyglot bridge**: a single Go binary any application — Python, a shell
script, Node, a Makefile — can shell out to in order to get the *entire* agent runtime (agent
skills + MCP servers + sub-agent delegation + a listening endpoint) **without reimplementing it
in that language**. Owner intent: *"so people can use the CLI along with agent skills so any
application can create a sub-agent with this and do things,"* and — the reframing that anchors
this change — *"every toolnexus CLI always has a listener to answer."* The core is not a batch
`run`; it is a **resident, listening agent**.

## What Changes

- **The listener is the core, and the listener already exists.** A resident agent runs the
  shipped `Toolkit.Serve` A2A endpoint (`golang/serve.go`): a peer posts with A2A `message/send`,
  a §10 suspension surfaces as A2A **`input-required`** (ask-back — `serve.go:540-553`), and
  `contextId` threads the follow-up answer to resume the same conversation (`serve.go:379-390`).
  Agent-to-agent messaging is an A2A call to another agent's listener (`toolkit.go:246 AddAgent`).
  The CLI adds only a **name→URL registry** under `$TOOLNEXUS_HOME/agents/` and lifecycle
  (`up`/`ps`/`stop`). The listener binds **loopback by default**; exposing off-box is explicit.
- **One config file (`agent.json`) is the source of truth; flags override; flags-only still
  works.** It declares the model/endpoint, the toolkit (MCP + builtins), the root **agent**
  (persona `dir` or inline soul), a **`team`** of sub-agents, budgets, compaction, and a `serve`
  block. A config-less invocation keeps today's behavior.
- **`team[]` declares sub-agents** — each `name`+`does`, persona `dir` or inline soul,
  tool-scoped, budgeted, **nestable**. A non-empty team enables the `task` tool (the shipped
  opt-in rule). This is "any app creates sub-agents and does things."
- **Skills are ALWAYS global, one home.** Every agent reads `~/.toolnexus/skills`
  (`$TOOLNEXUS_SKILLS_DIR` overrides). There is no per-project skills dir and no shadowing.
- **`install`/`uninstall` mirror the winctl pattern with a fan-out.** `toolnexus install
  --skills <src>` places each skill in the canonical `~/.toolnexus/skills/<name>` **and symlinks
  it into `~/.claude/skills/<name>` and `~/.agents/skills/<name>`** so Claude Code and codex
  discover the same skill (the `window-ctl-skill/install.sh` model). It is **collision-safe**:
  unlike winctl's `rm -rf`, it only replaces a link that resolves into our canonical home; a
  foreign real dir or link is warned about and skipped. `uninstall --skills <name>` removes the
  mirror links (only ours) then the canonical.
- **Authoring: `create`.** `toolnexus create <name>` scaffolds an agent home (`SOUL.md` +
  `agent.json`). `create <new> --from <base> --add-mcp <server>` composes a **modified agent**
  (an `agent.json` with `extends: <base>` plus added MCP/tools) — "create a modified agent with
  MCP to build and all stuff."
- **`--json` structured output.** `run --once … --json` emits `{text, status, usage}` on stdout
  (logs to stderr) so a calling application parses a result. `send` behaves the same.
- **Distribution without a Go toolchain.** A release attaches prebuilt `toolnexus` binaries per
  OS/arch (+ `SHA256SUMS`) to the GitHub Release, and publishes an npm wrapper
  (`@muthuishere/toolnexus-cli`) whose postinstall downloads and verifies the matching binary.
  Skills stay **external** (SKILL.md + scripts in any language, e.g. Python), run via the `bash`
  builtin; the binary bundles no skills.
- **`SPEC.md §9` is rewritten** to document the config schema; the verbs (`create`/`up`/`send`/
  `ps`/`stop`/`run`/`tools`/`install`/`uninstall`); the listener (A2A) + ask-back; the global
  skills home + fan-out; `--json`; the npm + binary distribution; and the secrets invariant.

This change is **Go-only** (the CLI ships only in the Go module; not part of the six-port §0
byte-parity contract). It **composes** already-shipped library behavior. The single potential
library seam is a one-shot **A2A send** helper for `send` + agent-to-agent (else hand-rolled
CLI-side) — flagged in design, not required to change library behavior.

**OUT of scope**: a cross-port `loadAgent(config)` library API (schema designed to generalize
later); auth on `serve` (none in core by design); config hot-reload / directory watching; any
change to shipped library *behavior*.

## Capabilities

### New Capabilities
- `cli-agent-runtime`: the `toolnexus` CLI's config-file schema and its `create`/`up`/`send`/
  `ps`/`stop`/`run`/`tools`/`install`/`uninstall` verbs over the shipped runtime — the resident
  A2A listener as the core, declarative agent + team, `--json`, persona-from-dir, one global
  skills home with the `install` fan-out to Claude/codex roots, and the secrets-never-printed
  invariant.

### Modified Capabilities
- `release-publishing`: a release additionally (a) attaches prebuilt `toolnexus` CLI binaries
  for the common OS/arch matrix (+ checksums) as GitHub Release assets, and (b) publishes an npm
  CLI wrapper (`@muthuishere/toolnexus-cli`) that installs the matching binary — so the CLI is
  installable without a Go toolchain, by direct download and by `npm install`.
