## ADDED Requirements

### Requirement: Configurable kill grace

The builtin source SHALL accept `killGraceMs`, the wait between the graceful stop and the forced kill
of a timed-out or cancelled job. Absent or 0 SHALL mean 2000. On Windows, which has no graceful
signal, it SHALL have no effect.

#### Scenario: Short grace kills a TERM-ignoring job fast

- **WHEN** `killGraceMs` is 100 and a job that ignores SIGTERM is cancelled
- **THEN** the job's process group is gone within 1 second

### Requirement: File-editing results carry a structured diff

`edit`, `write` and `apply_patch` results SHALL carry `metadata.files`, a list of
`{path, status, additions, deletions}` where status is `added`, `modified` or `deleted`, and
`metadata.diff`, a unified diff of the change with paths relative to `baseDir` when set. The counts
SHALL be identical across ports; the diff text SHALL be a valid unified diff but need not be
byte-identical. The streaming `tool_result` event SHALL carry the result's `metadata`.

#### Scenario: Edit reports one modified file

- **WHEN** `edit` replaces one line in an existing file
- **THEN** `metadata.files` is `[{path, status:"modified", additions:1, deletions:1}]` and `metadata.diff` contains the `-` and `+` line

#### Scenario: Write of a new file is added

- **WHEN** `write` creates a 3-line file
- **THEN** `metadata.files` is `[{path, status:"added", additions:3, deletions:0}]`
