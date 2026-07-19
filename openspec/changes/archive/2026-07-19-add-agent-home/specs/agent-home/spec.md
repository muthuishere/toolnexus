# agent-home — spec delta

## ADDED Requirements

### Requirement: The directory is the agent (bootstrap files)
A persona SHALL be constructible from a directory via `fromDir(dir)`. The runtime SHALL
discover these files, in this order, injecting each present one into the agent's soul
(system prompt) as a `## <filename>` section: `AGENTS.md`, `SOUL.md`, `IDENTITY.md`,
`USER.md`, `TOOLS.md`, `HEARTBEAT.md`, `MEMORY.md`. Absent files are skipped. Each file is
read with a 2 MB cap; a larger file is truncated with an explicit notice. Composition happens
at session start (the soul is fixed for the run).

#### Scenario: Ordered discovery and injection
- **WHEN** a directory contains SOUL.md, USER.md, TOOLS.md and MEMORY.md (no others)
- **THEN** the composed soul contains exactly those four `## <file>` sections in the
  canonical order, and it reaches the child's system prompt

#### Scenario: Per-file cap
- **WHEN** a bootstrap file exceeds 2 MB
- **THEN** its injected copy is truncated with a notice and the on-disk file is untouched

### Requirement: The memory builtin persists durable memory with frozen-snapshot semantics
A `memory` tool SHALL be available to a home-wired persona with actions `add`, `replace`,
`remove`, targeting `self` (`MEMORY.md`, default) or `user` (`USER.md`). All actions write to
disk. A `replace`/`remove` whose target substring is absent SHALL return a loud `isError`.
The tool SHALL NOT mutate the current session's system prompt — persisted memory loads at the
START of the next session (the frozen-snapshot rule that keeps a long-lived persona
cache-stable). The tool description SHALL state this to the model. The tool SHALL be
omittable per persona.

#### Scenario: Write persists but does not change the live prompt
- **WHEN** a persona calls `memory add` during a run
- **THEN** the entry is on disk after the run, and a subsequent fresh `fromDir` run's soul
  snapshot contains it

#### Scenario: Missing substring is loud
- **WHEN** `memory replace`/`remove` names a substring not present in the file
- **THEN** the result is `isError` naming the missing text; the file is unchanged

#### Scenario: Opt-out
- **WHEN** a persona is built with memory disabled
- **THEN** no `memory` tool appears in its toolkit view

### Requirement: Heartbeat wakes the persona and stays silent on no-op
`startAgent(agent, …, { everyMs })` SHALL, on each interval, post a tick to the agent's own
inbox (the unsolicited rail — ticks coalesce) and, when the agent is idle, wake it with a
prompt instructing it to read HEARTBEAT.md and act, else reply `HEARTBEAT_OK`. A reply
containing `HEARTBEAT_OK` SHALL be treated as silent (no report emitted). All timing SHALL go
through the runtime's injectable clock so conformance fixtures run on a virtual clock.
Inbound channels are out of scope — a host delivers external events by calling `post`/`wake`.

#### Scenario: Silent no-op beat
- **WHEN** a heartbeat fires and the model replies `HEARTBEAT_OK`
- **THEN** no report is produced to the host

#### Scenario: Substantive beat surfaces
- **WHEN** a heartbeat fires and HEARTBEAT.md's condition is due, so the model replies with a
  message
- **THEN** that message is surfaced to the host's `onBeat` handler

#### Scenario: Ticks coalesce
- **WHEN** several intervals elapse while a beat's turn is still running
- **THEN** the queued ticks drain as one coalesced turn, not one turn per tick
