## ADDED Requirements

### Requirement: An ACP agent is a model source

The library SHALL provide a client that connects to an ACP (Agent Client Protocol) agent
over a child process's stdin/stdout using JSON-RPC 2.0 with one object per line, and SHALL
expose it as a semantic generate function suitable for the in-process client, so that the
tool-calling loop, skills, MCP tools and sub-agents operate unchanged.

#### Scenario: A warm session serves many turns

- **WHEN** a host opens an ACP connection and runs several turns of a tool-calling loop
- **THEN** the agent process is started once, the session is created once, and each turn is
  a `session/prompt` on that existing session

#### Scenario: Only agent message chunks form the reply

- **WHEN** an agent emits thought chunks and tool narration interleaved with message chunks
- **THEN** only the message chunks are accumulated into the reply, and structured output
  requested by the host parses successfully

#### Scenario: A permission request is answered rather than awaited

- **WHEN** the agent sends a permission request during a turn
- **THEN** the client answers with the first permitting option and the turn completes,
  rather than blocking until a timeout

#### Scenario: A superseding prompt is not answered from stale history

- **WHEN** a host sends a complete assembled request on each turn into one stateful session
- **THEN** the request carries an explicit marker that it supersedes all earlier prompts,
  and the agent answers the current request rather than an earlier near-duplicate

#### Scenario: Turns on one session are serialised

- **WHEN** a host issues concurrent prompts against a single ACP session
- **THEN** the client serialises them, because one session is one conversation

#### Scenario: The process outlives a single turn's cancellation

- **WHEN** one turn's context is cancelled
- **THEN** the agent process remains usable for subsequent turns, and closing the
  connection is idempotent
