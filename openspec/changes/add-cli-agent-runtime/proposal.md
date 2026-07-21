# Proposal — add-cli-agent-runtime

## Why

toolnexus 0.10.0 shipped the full agent runtime — sub-agents (§7D), persona home (§7E),
compaction (§7F), inbound `serve` (§7B/§7C) — but **only as a library**, reachable only from
the six language ports. The `toolnexus` CLI (`golang/cmd/toolnexus`, `SPEC.md §9`) still
exposes just the 0.x taster: `run` (a plain client loop) and `tools` (list the registry). None
of the agent surface is reachable from the command line.

That leaves the biggest lever unused. The CLI is the **one polyglot bridge**: a single Go
binary that any application — a Python service, a shell script, a Node app, a Makefile — can
shell out to in order to get the *entire* agent runtime (agent skills + MCP servers +
sub-agent delegation + a served endpoint) **without reimplementing it in that language**. Today
a non-Go app that wants sub-agents has to embed one of the ports; with this change it writes a
config file and runs `toolnexus`.

The intent, stated by the owner: *"so people can use the CLI along with agent skills so any
application can create a sub-agent with this and do things."* The CLI becomes the way to
compose a coding-agent- or persona-class agent, with a team, from outside any one language.

## What Changes

- **A single declarative config file is the source of truth** (`-f, --file <agent.json>`). One
  file declares the whole runtime: the model/endpoint, the toolkit (MCP + skills + builtins),
  the **root agent** (inline soul or a persona `dir`), a **`team`** of sub-agents, budgets,
  compaction, and an optional `serve` block. Explicit CLI flags **override** matching file
  fields; a config-less invocation (flags only) keeps working exactly as today.
- **`run` gains the agent runtime.** With a config (or the new flags), `run` executes the
  declared **agent** — not just the bare client loop — so team delegation via the `task` tool,
  budgets, persona bootstrap, heartbeat, and compaction all apply. REPL and `--once` both work.
- **`team[]` declares sub-agents.** Each member has a `name` + `does` (routing description) and
  is either a persona `dir` (loaded via `FromDir`) or inline `soul`/`soulFile`, optionally
  tool-scoped (`tools: [...]`), model-pinned, budgeted, and **nestable** (a member may declare
  its own `team`). Declaring a team enables the `task` tool on the parent (opt-in, matching the
  library rule: no team ⇒ no `task`).
- **`--json` structured output.** `run --once … --json` emits `{text, status, usage}` on
  stdout so a calling application parses a result instead of scraping the REPL. This is what
  makes the CLI a programmatic bridge.
- **New `serve` command.** `toolnexus serve -f agent.json --addr :8080` exposes the configured
  toolkit as an **A2A** agent and/or **MCP** server (`tk.Serve`), blocking until interrupted.
- **Human-in-the-loop in the REPL.** When a run returns `status:"pending"` (a §10 suspension),
  the CLI prints the request and reads the answer from stdin, then resumes. In `--once`/`--json`
  mode a pending result is reported (status `pending` + the request) rather than hanging.
- **Persona from a directory.** `agent.dir` (or `--dir`) loads the root agent via `FromDir`
  (soul files + `memory` builtin + optional heartbeat via `heartbeatMs`).
- **`tools` accepts the config too** (`-f`), so introspection matches what `run`/`serve` build.
- **Prebuilt cross-platform binaries in GitHub Releases.** The CLI is the polyglot artifact, so
  it must be usable **without a Go toolchain**. The release pipeline gains a job that
  cross-compiles `toolnexus` for the common OS/arch matrix (darwin/linux/windows × amd64/arm64)
  and attaches the binaries — plus a `SHA256SUMS` file — as assets on each GitHub Release. This
  is what lets a Python/Node/shell app just download the binary and run it. Skills stay
  **external** (a `skills/` folder of `SKILL.md` + scripts in any language, e.g. Python); the
  binary runs them through its `bash`/skill builtins — the binary bundles no skills.
- **`npm install` distribution of the CLI.** Beyond raw release assets, an npm package
  (`toolnexus-cli`, providing the `toolnexus` bin) makes `npm install -g toolnexus-cli` fetch the
  matching prebuilt binary for the host platform — the most common "I don't have a Go toolchain"
  path for the JS/Node-adjacent world. (The existing `toolnexus` npm package stays the JS
  *library*; the CLI wrapper is a separate package.)
- **`toolnexus install --skills <src>` installs skills globally.** A new `install` command copies
  a skills source (a local `skills/` folder; a git URL is a stretch goal) into a global skills
  home (`$TOOLNEXUS_HOME`, default `~/.toolnexus`, under `skills/`). Thereafter `run`/`serve`/
  `tools` **auto-load the global skills** in addition to any `--skills`/config skills, so a user
  installs a skill once and every invocation has it — "install agent skills globally, then do
  work." Local (flag/config) skills override a global skill of the same name.
- **`SPEC.md §9` is rewritten** to document the config schema, the commands (`run`/`serve`/
  `tools`/`install`), `--json`, the team-declaration format, the global skills home + resolution
  order, the npm + binary distribution, and the secrets invariant.

This change is **Go-only**. The CLI ships only in the Go module (`golang/cmd/toolnexus`); it is
not part of the six-port §0 byte-parity contract. The *library* behavior it wires is already
shipped and identical across ports — the CLI adds no new library capability, only a
command-line surface and a config loader over the existing `agents`/`serve` APIs.

**Explicitly OUT of scope**: a cross-language `loadAgent(config)` library API in all six ports
(possible follow-up — the config schema is designed so it could generalize); auth on `serve`
(core has none by design); a config-file *format* migration tool; hot-reload of the config;
watching a skills/agents directory for changes; any change to the shipped library behavior.

## Capabilities

### New Capabilities
- `cli-agent-runtime`: the `toolnexus` CLI's config-file schema and its `run` / `serve` /
  `tools` / `install` commands over the shipped agent runtime — declarative agent + team,
  `--json` output, the human-loop behavior, persona-from-dir, a global skills home with
  `install --skills` + resolution order, and the secrets-never-printed invariant.

### Modified Capabilities
- `release-publishing`: a release additionally (a) attaches prebuilt `toolnexus` CLI binaries
  for the common OS/arch matrix (plus checksums) as GitHub Release assets, and (b) publishes an
  npm CLI wrapper package that installs the matching binary — so the CLI is installable without
  a Go toolchain, both by direct download and by `npm install`.
