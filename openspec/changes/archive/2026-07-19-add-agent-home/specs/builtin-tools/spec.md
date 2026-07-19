# builtin-tools — spec delta

## ADDED Requirements

### Requirement: The memory builtin is file-backed and opt-in
The built-in inventory SHALL gain an optional `memory` tool that is **not** part of the
default §4A builtins (which stay unchanged and process-stateless). The `memory` tool exists
only when a home directory is wired — via `fromDir(dir)`, or by adding `memoryTool(dir)` to a
toolkit's tools. It is file-backed (`MEMORY.md`/`USER.md` under that directory), and its
detailed semantics are specified by the `agent-home` capability.

#### Scenario: Not present by default
- **WHEN** a toolkit is built with the default builtins and no home directory
- **THEN** no `memory` tool appears in `tools()`

#### Scenario: Present when a home dir is wired
- **WHEN** a persona is built with `fromDir(dir)` and memory enabled
- **THEN** a `memory` tool backed by that directory appears in its toolkit view
