## ADDED Requirements

### Requirement: Optional MEMORY.md index cap in soul composition
`fromDir` SHALL accept an optional `memoryIndexCap` (`maxLines`, `maxBytes`; preset 200 / 25600)
that limits the injected `MEMORY.md` to whole lines within both bounds and, when anything was
dropped, appends a fixed notice telling the agent to keep it a short index and move detail into
notes. Disk SHALL be untouched; absent the option the soul SHALL be byte-identical to today.

#### Scenario: Oversized index is capped with a notice
- **WHEN** `memoryIndexCap` is set to the preset and `MEMORY.md` has 500 short lines
- **THEN** the soul's `## MEMORY.md` section holds the first 200 lines followed by the truncation notice, and the file on disk still has 500 lines

#### Scenario: Absent option is byte-identical
- **WHEN** a persona with a 40 KB `MEMORY.md` is built without `memoryIndexCap`
- **THEN** the composed soul equals today's composition byte for byte

### Requirement: Memory notes tier is written and read on demand, never injected
With notes enabled, the `memory` tool SHALL accept `target: "note:<slug>"` (writing
`<home>/memory/<slug>.md`, never injected) and SHALL offer `read` and a slug-sorted `list`.
Slugs SHALL match `^[a-z0-9][a-z0-9._-]{0,63}$` without `..`, else `isError` with no I/O.
With notes disabled the tool's schema and description SHALL be unchanged.

#### Scenario: Note written but not injected
- **WHEN** a notes-enabled persona calls `memory add` with target `note:route-plan`
- **THEN** `<home>/memory/route-plan.md` contains the entry and the next session's soul does not contain it

#### Scenario: Path traversal rejected
- **WHEN** the agent calls `memory add` with target `note:../SOUL`
- **THEN** the result is `isError` and no file is created or modified

#### Scenario: List is sorted
- **WHEN** notes `b`, `a` and `c` exist and the agent calls `memory list`
- **THEN** the output lists `a`, `b`, `c` in that order with their byte sizes

### Requirement: Memory writes are atomic and serialized per file
Every `memory` write SHALL replace the file atomically (temporary file in the same directory,
flushed, then renamed over the target). Read-modify-write actions on the same resolved file SHALL
be serialized within one runtime, and SHALL operate on the full file content, never on the capped
injection view.

#### Scenario: Concurrent adds keep every entry
- **WHEN** two agents in one runtime sharing a home each issue 20 concurrent `memory add` calls to `MEMORY.md`
- **THEN** the file contains all 40 entries

#### Scenario: Large file not truncated by a write
- **WHEN** `MEMORY.md` is 3 MB and the agent calls `memory add`
- **THEN** the file on disk is the original 3 MB plus the new entry

### Requirement: Built-in dreaming
The library SHALL provide `dreamOnce(dir, opts)` (one dream pass, returns a `DreamReport`) and
`startDreaming(dir, opts)` (the same pass on a §7E heartbeat via `startAgent`, `everyMs`
required). Both SHALL build the dreamer from a library-owned prompt that is byte-identical in
every port, with only the `memory` tool (notes enabled) and `transcriptSearchTool(opts.store)`,
and SHALL require no `HEARTBEAT.md` or other file from the user. Every pass SHALL append one
entry to `memory/log.md` when it changed anything.

#### Scenario: One pass with no user-written files
- **WHEN** `dreamOnce(dir, { client, store })` runs on a home holding only `MEMORY.md`, and the scripted model fixes one contradicted line
- **THEN** `MEMORY.md` holds the corrected line, `memory/log.md` gains one entry naming the change, and the report lists that change

#### Scenario: Nothing to do is silent
- **WHEN** the scripted model replies `HEARTBEAT_OK`
- **THEN** no file is written, `memory/log.md` is unchanged, and the report has no changes

#### Scenario: Dreamer cannot reach other tools
- **WHEN** a dream pass runs on a persona whose own agent has shell and file tools
- **THEN** the dreamer's tool list is exactly `memory` and `transcript_search`

#### Scenario: Scheduled dreaming coalesces
- **WHEN** `startDreaming` runs on a virtual clock and three intervals elapse while one pass is still running
- **THEN** exactly one further pass runs after it, never three
