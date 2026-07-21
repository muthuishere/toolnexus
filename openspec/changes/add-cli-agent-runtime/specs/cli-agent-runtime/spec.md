# cli-agent-runtime — spec delta

## ADDED Requirements

### Requirement: One config file declares the runtime; flags override
The `toolnexus` CLI SHALL accept a single JSON config file via `-f`/`--file` that declares the
model/endpoint, the toolkit (MCP + skills + builtins), the root agent, an optional `team`, and
an optional `serve` block. Every field SHALL be optional. An explicit command-line flag SHALL
override the matching config field. An invocation with **no** config file SHALL behave
byte-identically to the pre-change CLI (a bare client loop over `--config`/`--skills`).

#### Scenario: Flag overrides config field
- **WHEN** a config file sets `model.model` to `A` and the command line passes `--model B`
- **THEN** the run uses model `B`

#### Scenario: No config is backward compatible
- **WHEN** `toolnexus run --config mcp.json --skills ./skills --base-url URL --model M --once "hi"` is invoked with no `-f`
- **THEN** it builds the toolkit and runs the bare client loop exactly as the previous version did, with no team and no agent runtime

### Requirement: run executes the declared agent with team delegation
With a config that declares an `agent` and/or a non-empty `team`, `toolnexus run` SHALL execute
the **root agent** (not merely the bare client loop), so that budgets, persona bootstrap,
compaction, and delegation apply. A non-empty `team` SHALL enable the `task` tool on the parent;
an empty or absent `team` SHALL NOT expose `task` (the library opt-in rule).

#### Scenario: Team enables delegation
- **WHEN** a config declares `team: [{name, does, …}]` and the model calls `task`
- **THEN** the named sub-agent runs in isolation and returns its final text to the parent

#### Scenario: No team means no task tool
- **WHEN** a config declares an `agent` but no `team`
- **THEN** the resolved tool set offered to the model does not include a `task` tool

### Requirement: Team members are agents, inline or from a directory, tool-scoped and nestable
Each `team[i]` SHALL require a `name` and a `does`, and SHALL take its identity from either a
persona `dir` (loaded as a persona home) or an inline `soul`/`soulFile`; when both are present
the directory SHALL win. A member MAY restrict its toolkit view with a `tools` allowlist
(omitted ⇒ the full resolved toolkit), MAY pin a `model` and a `budget`, and MAY declare its own
nested `team`.

#### Scenario: Scoped member sees only its allowlisted tools
- **WHEN** a member declares `tools: ["read","grep"]`
- **THEN** that sub-agent's toolkit view contains only `read` and `grep` (plus its own `task` only if it has a nested team), and no write/bash tool

#### Scenario: Nested team gets its own delegation
- **WHEN** a member declares its own non-empty `team`
- **THEN** that member is given a `task` tool scoped to its nested members

### Requirement: --json emits a structured result on stdout
`toolnexus run --once … --json` SHALL print a single JSON object containing at least the final
`text`, the run `status`, and token `usage` to **stdout**, and SHALL route human-readable log
output to **stderr**, so the stdout stream is machine-parseable by a calling application.

#### Scenario: JSON result is parseable
- **WHEN** `run --once "…" --json` completes
- **THEN** stdout is a single JSON object with `text`, `status`, and `usage` fields and nothing else

### Requirement: serve exposes the configured toolkit as A2A and/or MCP
`toolnexus serve` SHALL build the toolkit (and client) from the config/flags, expose it via
`Toolkit.Serve` as an A2A agent and/or an MCP server per the `serve` block (`--a2a`/`--mcp`
override), print the bound address, and block until interrupted, then stop the server cleanly.

#### Scenario: Serve starts and reports its address
- **WHEN** `toolnexus serve -f agent.json --addr 127.0.0.1:0` is invoked
- **THEN** the process binds a port, prints the bound address, and serves the toolkit's tools/skills until it receives an interrupt

### Requirement: A suspension is surfaced, never silently dropped
When a run returns a §10 pending suspension, the CLI SHALL, in interactive mode, print the
request and read an answer from stdin to resume; and in `--once`/`--json` mode SHALL return a
result whose status is `pending` with the request included, rather than blocking on stdin or
reporting a false completion.

#### Scenario: Pending in --once is reported
- **WHEN** a tool suspends during `run --once … --json`
- **THEN** the emitted JSON has `status: "pending"` and includes the request, and the process does not block waiting on stdin

### Requirement: install --skills installs skills into a global home used by later runs
`toolnexus install --skills <src>` SHALL copy every `SKILL.md` tree found under `<src>` into a
global skills home (`$TOOLNEXUS_HOME/skills`, default `~/.toolnexus/skills`), overwriting a
same-named skill idempotently. Subsequent `run`/`serve`/`tools` invocations SHALL auto-load the
global skills in addition to any `--skills`/config skills, unless `--no-global-skills` is given.
On a skill-name collision between a global and a local (flag/config) skill, the local skill
SHALL win. Installing SHALL NOT execute any skill code.

#### Scenario: Install then use
- **WHEN** `toolnexus install --skills ./pack` installs a skill `foo`, then `toolnexus tools` is run with no `--skills`
- **THEN** the skill `foo` appears in the resolved tools, loaded from the global home

#### Scenario: Local skill shadows a global one
- **WHEN** a skill named `foo` exists both in the global home and in a `--skills` folder
- **THEN** the local `foo` is the one exposed, and the global one is shadowed

#### Scenario: Opt out of global skills
- **WHEN** `run`/`tools` is invoked with `--no-global-skills`
- **THEN** no skill from the global home is loaded

### Requirement: The CLI never prints a secret value
The CLI SHALL resolve an absent `--api-key` from the environment and SHALL NOT print any API key
or resolved MCP header value in any output — `--json`, logs, the `serve` startup line, or
`tools`. A config file MAY reference an environment variable by name but is not required to hold
a literal secret.

#### Scenario: Key never appears in output
- **WHEN** the CLI runs with an API key resolved from the environment
- **THEN** no command output (stdout or stderr) contains the key value
