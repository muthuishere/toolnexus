## ADDED Requirements

### Requirement: A CLI client is built from a style enum and a required command

Every port SHALL provide `createCliClient` (idiomatic spelling per port) taking a style from the
closed set `CliStyles.Claude | Codex | Copilot | Opencode | Devin | Gemini | Goose | Custom`, a
required `command` (binary path or name), and every in-process client option. No API SHALL accept
a style as free text. The library SHALL NOT embed, default or choose a binary. The result SHALL be
an ordinary client plus `close()` and `transport()`.

#### Scenario: Missing command
- **WHEN** a CLI client is created without `command`
- **THEN** construction fails with `cli: Command is required (path or name of the CLI binary)`

#### Scenario: Missing style
- **WHEN** a CLI client is created with a command but no style and no user style definition
- **THEN** construction fails with `cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)`

#### Scenario: Model on a style that takes none
- **WHEN** a model is set on a style whose args contain no `{{model}}` and the one-shot transport is possible
- **THEN** construction fails with `cli: style "<name>" takes no model`

### Requirement: Built-in styles are shared data

The seven built-in styles and the envelope preamble SHALL live in `conformance/cli-styles/` and be
embedded byte-identically by every port. A style SHALL hold no binary; it holds `args` (strings and
optional groups), `prompt` (`stdin|arg|file`), `answer` (`stdout|out`), an optional `acp` launch
(`args`, optional `projectMcpFile`) and a `verified` note. Styles not live-verified SHALL say
`UNVERIFIED`. `codex` SHALL have no ACP launch.

#### Scenario: Embedded copy drifts
- **WHEN** a port's embedded style or preamble differs from `conformance/cli-styles/`
- **THEN** that port's test suite fails

### Requirement: The transport is ACP when available, else the one-shot CLI

With `transport` Auto (default) the client SHALL use ACP when the style has an ACP launch or
`acpCommand` is set, and SHALL fall back to the one-shot CLI only when the agent fails to start or
to answer `initialize`, recording the reason. Failures after `initialize` SHALL be errors.
`transport` ACP SHALL never fall back; CLI SHALL never try ACP. `transport()` SHALL report
`auto` until the first model call decides, then `acp` or `cli`.

#### Scenario: Agent without ACP falls back
- **WHEN** the ACP launch exits before answering `initialize` and the style has one-shot args
- **THEN** the run completes over the one-shot CLI, `transport()` is `cli` and the fallback reason is non-empty

#### Scenario: Forced ACP does not fall back
- **WHEN** `transport` is ACP and the agent fails to start
- **THEN** the run fails with an error starting `cli: acp:`

#### Scenario: Style without an ACP launch forced to ACP
- **WHEN** `transport` is ACP for `CliStyles.Codex` and no `acpCommand` is set
- **THEN** construction fails with `cli: style "codex" has no ACP launch; set ACPCommand or use CliTransports.CLI`

### Requirement: The toolkit is served to an ACP agent behind a per-session bearer token

On session open the client SHALL serve `options.toolkit` as a streamable-HTTP MCP server on
`127.0.0.1:<ephemeral>/mcp` that rejects every request without `Authorization: Bearer <token>`
with 401, using a fresh random token per session. It SHALL list the server as `toolnexus` in
`session/new` `mcpServers`: the http shape with the token in `headers` when the agent advertises
`mcpCapabilities.http`; otherwise the stdio shape (no `type`) running a bridge with the token in
`env` `TOOLNEXUS_MCP_TOKEN`. `headers` and `env` SHALL be arrays of `{name,value}`. For a style
with `acp.projectMcpFile` the client SHALL send no session entry and SHALL open the session in a
fresh temp cwd holding that file (mode 0600) with the http server and its header. Server, bridge,
temp dir and the agent's process group SHALL be closed with the client. The token SHALL never be
logged.

#### Scenario: http entry with token
- **WHEN** the agent advertises `mcpCapabilities.http`
- **THEN** `session/new` carries `{"type":"http","name":"toolnexus","url":…,"headers":[{"name":"Authorization","value":"Bearer …"}]}` and a request without the header gets 401

#### Scenario: stdio bridge entry
- **WHEN** the agent does not advertise http and a bridge command is available
- **THEN** `session/new` carries `{"name":"toolnexus","command":…,"args":[…,url],"env":[{"name":"TOOLNEXUS_MCP_TOKEN",…}]}` and the agent's calls through it succeed

#### Scenario: Project MCP file
- **WHEN** the style declares `projectMcpFile` `.mcp.json`
- **THEN** no session entry is sent, the session cwd is a temp dir holding `.mcp.json`, and the dir and server are gone after `close()`

### Requirement: The skill tool is served as load_skill

The toolkit's `skill` tool SHALL be served to an ACP agent as `load_skill`, with the §3 skills
prompt appended to its description. No served tool SHALL be named `skill`. Other tools keep their
names.

#### Scenario: No collision with an agent's own skill tool
- **WHEN** an ACP agent lists the served tools of a toolkit with skills
- **THEN** it sees `load_skill` and no tool named `skill`, and `load_skill` returns the §3 skill output

### Requirement: Permission requests are decided per toolCallId

Each `session/request_permission` SHALL be decided once per `toolCallId` and cached. The identity
SHALL be the request's title, else the first non-empty title the agent reported for that id in a
`tool_call` or `tool_call_update`. It SHALL be allowed only when it exactly equals one of
`<tool>`, `mcp__toolnexus__<tool>`, `toolnexus/<tool>`, `<tool> (toolnexus MCP Server)`,
`Calling <tool> from toolnexus`, or is `<tool>(…)`, for a served tool. Every other request,
including one without a `toolCallId`, SHALL be rejected.

#### Scenario: Empty title resolved through the tool_call update
- **WHEN** a `tool_call` update titled `Calling get_badge_number from toolnexus` precedes a permission request for the same id with an empty title
- **THEN** the request is allowed

#### Scenario: The agent's own tool and lookalikes
- **WHEN** permission is requested for `Bash`, or for `my_get_badge_number_wrapper`
- **THEN** it is rejected

#### Scenario: A decision does not flip
- **WHEN** a second request for an already-allowed id arrives with a different title
- **THEN** it is allowed again (the cached decision), never re-decided from the new title

### Requirement: The one-shot CLI uses the envelope and the tolerant parser

Each model turn SHALL run the CLI once with `PREAMBLE + "\nREQUEST:\n" + {"messages","tools"}`
(compact JSON, no HTML escaping); the system prompt is the first message. The argv is rendered from
the style (`{{prompt}}`, `{{file}}`, `{{out}}`, `{{model}}`; optional groups kept only with a
model) and spawned without a shell, in its own process group. The reply SHALL be parsed by the
six-step parser (trim; one fence; whole object; else first `{` to last `}`; unwrap
`choices[0].message`/`message`; `tool_calls` win, else `content`); anything else is content
verbatim. Tool calls SHALL be executed by the toolnexus loop. Streaming SHALL be refused.

#### Scenario: Tool round trip
- **WHEN** the CLI replies with a fenced `tool_calls` envelope and then, given the tool result, a `content` envelope
- **THEN** the loop executes the tool once and returns the content

#### Scenario: Pinned errors
- **WHEN** the CLI is missing, exits non-zero, prints nothing, writes no `out` file, or exceeds the call timeout (default 300000 ms)
- **THEN** the error is `cli: command not found: <bin>`, `cli: exit <code>: <stderr ≤1000 code points>`, `cli: empty reply`, `cli: no output file`, or `cli: timed out after <ms>ms`, and temp files are removed

### Requirement: The model passes through unchanged

`model` SHALL fill `{{model}}` for the one-shot CLI and, over ACP, SHALL be sent as
`session/set_config_option {configId:"model", value}` and, if that is rejected,
`session/set_model {modelId}`. Both failing SHALL be an error.

#### Scenario: Older agent
- **WHEN** the agent rejects `session/set_config_option`
- **THEN** the same id is sent with `session/set_model` and the session proceeds

### Requirement: The ACP model source sends the given mcpServers

`ACPOptions` SHALL accept MCP server entries and send them in `session/new` `mcpServers` (empty
list when none), resolving an entry that carries both a URL and a command against the agent's
advertised `mcpCapabilities.http`. A pending request SHALL fail with
`agent exited before replying to <method>` as soon as the agent's stdout closes. Agent→client
requests the client does not implement SHALL be answered with JSON-RPC error -32601.

#### Scenario: Entries reach session/new
- **WHEN** `LoadACP` is given one MCP server entry
- **THEN** `session/new` carries exactly that entry in the shape the agent's capabilities select
