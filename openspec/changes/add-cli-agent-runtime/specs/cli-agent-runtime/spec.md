# cli-agent-runtime — spec delta

## ADDED Requirements

### Requirement: One config file declares the runtime; flags override
The `toolnexus` CLI SHALL accept a single JSON config file via `-f`/`--file` that declares the
model/endpoint, the toolkit (MCP + builtins), the root agent, an optional `team`, an optional
`extends` base, and an optional `serve` block. Every field SHALL be optional. An explicit
command-line flag SHALL override the matching config field. An invocation with **no** config
file SHALL behave as the pre-change CLI did (a bare client loop).

#### Scenario: Flag overrides config field
- **WHEN** a config sets `model.model` to `A` and the command line passes `--model B`
- **THEN** the run uses model `B`

#### Scenario: No config is backward compatible
- **WHEN** `toolnexus run --config mcp.json --base-url URL --model M --once "hi"` is invoked with no `-f`
- **THEN** it builds the toolkit and runs the bare client loop as the previous version did

### Requirement: A resident agent always exposes a listener
`toolnexus up` SHALL run the declared agent as a resident process exposing the shipped
`Toolkit.Serve` A2A endpoint, register the agent's `name → URL` under `$TOOLNEXUS_HOME/agents/`,
and block until interrupted, then stop the server cleanly. The listener SHALL bind loopback by
default; exposing it off-box SHALL require an explicit address. A §10 suspension raised during a
served turn SHALL surface to the peer as A2A `input-required` carrying the request, and a
follow-up message on the same `contextId` SHALL resume that conversation.

#### Scenario: Resident agent is reachable and registered
- **WHEN** `toolnexus up -f agent.json` starts
- **THEN** it binds a loopback address, writes `$TOOLNEXUS_HOME/agents/<name>.json` with the URL, and serves the agent until interrupted

#### Scenario: Ask-back over the listener
- **WHEN** a served turn suspends (a tool returns pending)
- **THEN** the peer receives A2A `input-required` with the request, and sending an answer on the same `contextId` resumes the turn

### Requirement: Agents message each other and are listed and stopped
`toolnexus send <name> "msg"` SHALL resolve `<name>` via the registry and deliver the message to
that agent's listener, printing the reply and, on `input-required`, prompting for an answer.
`toolnexus ps` SHALL list resident agents and `toolnexus stop <name>` SHALL stop one. An agent
delegating to another agent SHALL do so through the same A2A listener mechanism.

#### Scenario: Send reaches a resident agent
- **WHEN** agent `ava` is up and `toolnexus send ava "hello"` is run
- **THEN** the message reaches `ava`'s listener and its reply is printed

### Requirement: run executes the declared agent with team delegation
With a config that declares an `agent` and/or a non-empty `team`, `toolnexus run` SHALL execute
the root agent (not the bare client loop), so budgets, persona bootstrap, compaction, and
delegation apply. A non-empty `team` SHALL enable the `task` tool on the parent; an empty or
absent `team` SHALL NOT expose `task`.

#### Scenario: Team enables delegation
- **WHEN** a config declares `team: [{name, does, …}]` and the model calls `task`
- **THEN** the named sub-agent runs in isolation and returns its final text to the parent

#### Scenario: No team means no task tool
- **WHEN** a config declares an `agent` but no `team`
- **THEN** the resolved tool set offered to the model does not include a `task` tool

### Requirement: Team members are agents, inline or from a directory, tool-scoped and nestable
Each `team[i]` SHALL require a `name` and a `does`, and SHALL take its identity from either a
persona `dir` or an inline `soul`/`soulFile` (directory wins if both present). A member MAY
restrict its toolkit view with a `tools` allowlist (omitted ⇒ the full resolved toolkit), MAY
pin a `model` and a `budget`, and MAY declare its own nested `team`.

#### Scenario: Scoped member sees only its allowlisted tools
- **WHEN** a member declares `tools: ["read","grep"]`
- **THEN** that sub-agent's toolkit view contains only `read` and `grep` (plus its own `task` only if it has a nested team)

#### Scenario: Nested team gets its own delegation
- **WHEN** a member declares its own non-empty `team`
- **THEN** that member is given a `task` tool scoped to its nested members

### Requirement: create scaffolds an agent and composes a modified one
`toolnexus create <name>` SHALL scaffold an agent home (a `SOUL.md` and an `agent.json`).
`toolnexus create <name> --from <base>` SHALL produce an `agent.json` that extends the base, and
`--add-mcp <server>` SHALL add an MCP server to it, so a new agent can be composed from an
existing one plus added capability without copying the whole base.

#### Scenario: Create a modified agent with an added MCP server
- **WHEN** `toolnexus create billing --from support --add-mcp stripe=…` is run
- **THEN** a `billing/agent.json` is written that extends `support` and includes the `stripe` MCP server

### Requirement: --json emits a structured result on stdout
`toolnexus run --once … --json` (and `send --json`) SHALL print a single JSON object containing
at least the final `text`, the run `status`, and token `usage` to **stdout**, and SHALL route
human-readable log output to **stderr**, so stdout is machine-parseable.

#### Scenario: JSON result is parseable
- **WHEN** `run --once "…" --json` completes
- **THEN** stdout is a single JSON object with `text`, `status`, and `usage` and nothing else

### Requirement: Skills are always global, in one home
Every `run`/`up`/`tools` invocation SHALL load skills from a single global home
(`$TOOLNEXUS_SKILLS_DIR`, default `~/.toolnexus/skills`). There SHALL be no per-invocation skills
directory and no local-over-global shadowing; the global home is the only skills source.

#### Scenario: Global skills are always present
- **WHEN** a skill `foo` exists in the global home and `toolnexus tools` is run with no skill flags
- **THEN** `foo` appears in the resolved tools

### Requirement: install populates the global home and fans out to Claude and codex roots
`toolnexus install --skills <src>` SHALL place each `SKILL.md` tree from `<src>` in the canonical
global home (`~/.toolnexus/skills/<name>`) and SHALL additionally create a symlink to it in the
Claude skills root (`$CLAUDE_SKILLS_DIR`, default `~/.claude/skills`) and, when codex is present
or `$AGENTS_SKILLS_DIR` is set, in the agents root (`~/.agents/skills`), so those tools discover
the same skill. It SHALL be collision-safe: it SHALL only replace a link that resolves into the
canonical home, and SHALL warn about and skip a target that is a foreign directory or link
(never deleting it). `uninstall --skills <name>` SHALL remove the mirror links it owns and then
the canonical entry. Installing SHALL NOT execute any skill code.

#### Scenario: Install fans out to the agent roots
- **WHEN** `toolnexus install --skills ./pack` installs a skill `foo`
- **THEN** `~/.toolnexus/skills/foo` exists and `~/.claude/skills/foo` links to it (and `~/.agents/skills/foo` when codex is present)

#### Scenario: A foreign skill directory is not clobbered
- **WHEN** `~/.claude/skills/foo` already exists as a real directory not owned by toolnexus and an install of `foo` runs
- **THEN** that directory is left intact and the fan-out for that root is skipped with a warning

### Requirement: The CLI never prints a secret value
The CLI SHALL resolve an absent `--api-key` from the environment and SHALL NOT print any API key
or resolved MCP header value in any output — `--json`, logs, the `up`/`serve` startup line, or
`tools`. A config file MAY reference an environment variable by name but is not required to hold
a literal secret.

#### Scenario: Key never appears in output
- **WHEN** the CLI runs with an API key resolved from the environment
- **THEN** no command output (stdout or stderr) contains the key value
