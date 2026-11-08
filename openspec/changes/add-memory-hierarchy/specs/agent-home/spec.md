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
injection view. Permitted per-port difference: clojure writes in place and its lock is
in-process only (no atomic rename in `koine.fs` yet).

#### Scenario: Concurrent adds keep every entry
- **WHEN** two agents in one runtime sharing a home each issue 20 concurrent `memory add` calls to `MEMORY.md`
- **THEN** the file contains all 40 entries

#### Scenario: Large file not truncated by a write
- **WHEN** `MEMORY.md` is 3 MB and the agent calls `memory add`
- **THEN** the file on disk is the original 3 MB plus the new entry

### Requirement: Built-in dreamer preset
The library SHALL provide `dreamer(dir, opts)` returning an AgentDef built from a library-owned
`DREAM_PROMPT` identical in every port, with exactly the `memory` and `transcript_search`
tools, needing no user-written file. `fromDir` SHALL accept an opt-in `dream: { everyMs, store }`
that starts that dreamer on the existing heartbeat. Each pass that changes memory SHALL append
one entry to `memory/log.md`.

#### Scenario: A dream pass fixes a wrong note and changes the next session
- **WHEN** a home's `MEMORY.md` holds a note the transcript contradicts and one `dreamer` pass runs with a scripted model
- **THEN** the note is corrected, `memory/log.md` gains one entry naming it, and a next session built with `fromDir` answers per the corrected note

#### Scenario: Nothing to do is silent
- **WHEN** the dreamer's scripted model replies `HEARTBEAT_OK`
- **THEN** no file is written and `memory/log.md` is unchanged

#### Scenario: Dreamer cannot reach other tools
- **WHEN** `dreamer` is built for a persona whose own agent has shell and file tools
- **THEN** the dreamer's tool list is exactly `memory` and `transcript_search`

#### Scenario: Absent dream option is unchanged
- **WHEN** `fromDir` is called without `dream`
- **THEN** no dreamer is started and behaviour is byte-identical to today

### Requirement: Dreamer cannot erase memory without a trace
The dreamer's prompt SHALL tell it to fix or remove a claim only when the transcripts contradict
it, never because they do not mention it. The dreamer's `memory` tool SHALL refuse (`isError`,
nothing read or written) every action on `USER.md` and `replace`/`remove` on `note:log`, so the
log is append-only for the dreamer.

#### Scenario: Dreamer cannot rewrite its log or the user model
- **WHEN** the dreamer's `memory` tool is called with `replace` or `remove` on `note:log`, or any action on target `user`
- **THEN** it returns `isError` with the dream-guard message and `memory/log.md` and `USER.md` are byte-unchanged

#### Scenario: An unmentioned claim is kept
- **WHEN** the dreamer's prompt is read
- **THEN** it says to fix or remove a claim only when it is contradicted by the transcripts
