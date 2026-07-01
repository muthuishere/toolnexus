## ADDED Requirements

### Requirement: Remote agents are a tool source

The library SHALL let a host add remote A2A agents so that each agent's advertised skills become
tools in the toolkit. An agent SHALL be constructed with an `agent({ card, headers?, timeout?,
pollEvery? })` factory (returning an Agent object), composed via a `createToolkit({ agents: [...] })`
option (an array), declared in a config-file `agents` block, or added at runtime with
`toolkit.addAgent(agentOrCardUrl, opts?)`. The `agents` source SHALL default to empty. Header values
SHALL support `${ENV_VAR}` expansion and SHALL NOT be logged. Each advertised skill SHALL become a
tool named `sanitize(agent) + "_" + sanitize(skill)` with `source: "a2a"`.

#### Scenario: Agent skills become tools

- **WHEN** a toolkit is built with an agent whose Agent Card advertises skills `review` and `plan`
- **THEN** the toolkit exposes tools `<agent>_review` and `<agent>_plan`, each with `source: "a2a"`
- **AND** those tools appear in the provider schema (`toOpenAI`/`toAnthropic`/`toGemini`)

#### Scenario: Config, option, and dynamic add all work and merge

- **WHEN** agents are supplied via the `agents:[...]` option, the config-file `agents` block, and
  `toolkit.addAgent(...)` at runtime
- **THEN** all of their skills are present as tools, merged, following the existing first-wins dedupe
- **AND** an `enabled:false`/`disabled:true` agent in config is skipped (MCP `isEnabled` precedence)

### Requirement: An agent tool submits and polls over A2A JSON-RPC

An A2A agent tool's `execute` SHALL call the agent's JSON-RPC endpoint with method `SendMessage`
(non-blocking) to obtain a Task id, then poll method `GetTask` every `pollEvery` (default 1000ms)
until the Task reaches a terminal state (`completed`/`failed`/`canceled`) or the `timeout` (default
300000ms) elapses. It SHALL map a `completed` Task's artifact/message text to
`ToolResult{isError:false}`, and a `failed`/`canceled` Task or a poll timeout to
`ToolResult{isError:true}`. A `ctx` cancellation/timeout SHALL abort the poll. The result metadata
SHALL include `{agent, taskId, state, polls, ms}`.

#### Scenario: Successful task round-trip

- **WHEN** an agent tool is executed and the remote returns a Task that reaches `completed`
- **THEN** `SendMessage` is called once, `GetTask` is polled until `completed`
- **AND** the ToolResult output is the Task's artifact/message text with `isError:false`

#### Scenario: Failed task maps to an error result

- **WHEN** the polled Task reaches `failed` (or the poll exceeds `timeout`)
- **THEN** the ToolResult has `isError:true` with the state/message text

#### Scenario: Cancellation stops polling

- **WHEN** the `ctx` signal is aborted mid-poll
- **THEN** polling stops and the call returns without further `GetTask` requests
