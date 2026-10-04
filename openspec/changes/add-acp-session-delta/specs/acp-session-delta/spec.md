## ADDED Requirements

### Requirement: Only new messages are sent on a continuing session

After the opening prompt of an ACP session, the ACP model source SHALL send each later turn as a
continuation prompt carrying only the messages appended since the previous prompt, excluding the
agent's own previous reply, and SHALL NOT resend the system prompt, the tool schemas or earlier
messages.

#### Scenario: A tool loop sends tool results, not the whole request

- **WHEN** the agent asks for a tool on turn one and the loop executes it
- **THEN** the turn-two prompt is a continuation prompt whose messages are only the tool result
  message, with no tools array and no system message

#### Scenario: The opening prompt carries everything once

- **WHEN** a session receives its first prompt
- **THEN** that prompt carries the pinned preamble, the full messages and the tools, and no
  supersedes marker

### Requirement: Any mismatch opens a fresh session

The ACP model source SHALL open a fresh session on the same agent process, and send an opening
prompt, whenever the request is not the already-sent conversation extended by the agent's last
reply and at least one new message, with structurally equal tools.

#### Scenario: Changed tools reset the session

- **WHEN** the tool list of a request differs from the tool list the session was opened with
- **THEN** the client calls `session/new` and sends an opening prompt with the new tools

#### Scenario: Edited or compacted history resets the session

- **WHEN** the request's messages do not begin with the messages the session has already been sent
- **THEN** the client calls `session/new` and sends an opening prompt

#### Scenario: A new conversation does not inherit the old session

- **WHEN** a host starts a second, unrelated conversation on the same ACP client
- **THEN** it is sent to a fresh session, so the agent cannot answer from the first conversation

#### Scenario: A retried request is not answered from history

- **WHEN** the same request is sent again with no new message after the agent's reply
- **THEN** the client opens a fresh session rather than sending an empty continuation

#### Scenario: A failed turn discards session state

- **WHEN** a turn fails with an agent error or a timeout
- **THEN** the next turn opens a fresh session

### Requirement: Config options pass through to every session

The ACP model source SHALL accept an ordered map of ACP session config option ids to values and
SHALL apply each entry with `session/set_config_option` after every `session/new` (after any
mode), surfacing an agent's rejection unchanged, without interpreting or validating the ids.

#### Scenario: Config is applied at load and on every reset

- **WHEN** a host loads an ACP client with `config` `{model: "m-2"}` and a later turn resets the session
- **THEN** `session/set_config_option` with `configId` `model` and value `m-2` is sent for the first
  session and again for the fresh one

#### Scenario: A rejected config id fails the load

- **WHEN** the agent answers `session/set_config_option` with an error
- **THEN** loading the ACP client fails with that error

#### Scenario: Advertised options are readable

- **WHEN** the agent's `session/new` response contains `configOptions`
- **THEN** the host can read them, unmodified, from the client

### Requirement: The host's tool sources stay in the host loop

The ACP model source SHALL send `mcpServers: []` on every `session/new` and SHALL offer the host's
tools — MCP servers, skills, native, HTTP and builtin tools alike — only as tool schemas in the
prompt, so that every tool call is executed by the toolnexus loop and never handed to the agent's
own MCP client.

#### Scenario: MCP config is not passed to the agent

- **WHEN** a host builds its toolkit from an `mcp.json` and a skills directory and runs it over an
  ACP client
- **THEN** every `session/new` carries `mcpServers: []`, and those tools reach the agent only as
  schemas in the opening prompt, executed by the toolnexus loop when the agent calls them
