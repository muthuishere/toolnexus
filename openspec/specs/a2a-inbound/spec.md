# a2a-inbound Specification

## Purpose
TBD - created by archiving change add-a2a-agents. Update Purpose after archive.
## Requirements
### Requirement: Serve a toolkit as an A2A agent

`toolkit.serve(addr, { client, a2a })` SHALL expose the toolkit as an A2A agent when the `a2a` option
(typed `A2AConfig`) is present. It SHALL serve `GET /.well-known/agent-card.json` returning an Agent
Card whose `skills` are derived from the toolkit's agent-skills (SKILL.md `name`+`description`), never
from raw tools, and whose `capabilities.streaming` is `false`. It SHALL expose one JSON-RPC endpoint
handling `SendMessage` and `GetTask`. `serve` SHALL return a handle with a `stop()`/`close()` method.
When `a2a` is absent, no A2A routes are mounted.

#### Scenario: Card advertises skills, not tools

- **WHEN** a served toolkit has skills `video-transcribe` and builtin tools `bash`/`read`
- **THEN** `GET /.well-known/agent-card.json` lists `video-transcribe` under `skills`
- **AND** it does NOT list `bash`/`read` (raw tools stay private implementation)
- **AND** the card reports `capabilities.streaming: false`

#### Scenario: A2A profile is opt-in

- **WHEN** `serve(addr, { client })` is called without an `a2a` option
- **THEN** the A2A card and JSON-RPC routes are not mounted

### Requirement: Inbound SendMessage/GetTask fulfilled via the client loop

On `SendMessage`, the server SHALL create a Task in state `submitted`, persist it, start
`client.run(task, toolkit)` asynchronously (updating the Task to `working` then `completed` with the
result as an artifact, or `failed` on error), and return the Task id. On `GetTask`, it SHALL return
the current Task by id. A fulfilment error SHALL be captured as a `failed` Task and SHALL NOT crash
the server. A `serve` `onTask` callback SHALL surface each Task's outcome (skill, caller, and the
`RunResult` telemetry: tokens, tool count, turns, ms).

#### Scenario: Submit then poll to completion

- **WHEN** a peer calls `SendMessage`, then polls `GetTask` by the returned id
- **THEN** the Task transitions `submitted` → `working` → `completed`
- **AND** the completed Task carries the `client.run` result as an artifact text part

#### Scenario: Fulfilment error becomes a failed task

- **WHEN** `client.run` throws while fulfilling a task
- **THEN** the Task is recorded as `failed` and `GetTask` returns it
- **AND** the server keeps serving subsequent requests

### Requirement: Pluggable TaskStore adapter

Task persistence SHALL be a pluggable `TaskStore` adapter with `get(id)` and `save(task)` operations.
The library SHALL ship an in-memory store as the default and SHALL allow the host to select a file or
custom (db/other) store via `a2a.store`. `SendMessage`/`GetTask`/background fulfilment SHALL read and
write Tasks only through this adapter.

#### Scenario: Default in-memory store

- **WHEN** `serve` is called with `a2a` but no `store`
- **THEN** an in-memory `TaskStore` is used and submit→poll round-trips through it

#### Scenario: Host-supplied store is used

- **WHEN** `serve` is called with `a2a.store` set to a file or custom adapter
- **THEN** all Task reads/writes go through that adapter instead of the in-memory default

