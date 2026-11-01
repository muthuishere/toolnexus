## ADDED Requirements

### Requirement: Tool hints
Every builtin SHALL carry hints `{readOnly, destructive, openWorld}`: read, grep, glob, question and todowrite read-only; webfetch read-only and open-world; write, edit and apply_patch destructive; bash destructive and open-world. MCP tools SHALL map readOnlyHint, destructiveHint and openWorldHint with MCP's defaults, and SHALL have no hints when the server sends none. Hints SHALL NOT appear in any adapter or wire output.

#### Scenario: Builtin hints
- **WHEN** the builtins are created
- **THEN** each carries the hints listed above

#### Scenario: MCP annotations absent
- **WHEN** an MCP tool has no annotations
- **THEN** its hints are unknown (nil)

### Requirement: Opt-in coding builtins
Background shells, websearch and notebook_edit SHALL be absent unless the host configures them, leaving the default builtin set and the bash schema unchanged. With a background registry, `bash` SHALL accept `run_in_background` and return an id; `bash_output` SHALL return only output produced since the last read plus the status; `kill_shell` SHALL stop the whole process group.

#### Scenario: Default set unchanged
- **WHEN** builtins are created with no opt-in configuration
- **THEN** bash_output, kill_shell, websearch and notebook_edit are absent and bash has no run_in_background

#### Scenario: Background lifecycle
- **WHEN** a background command prints, exits, and another that ignores SIGTERM is killed
- **THEN** reads return each output once, the status moves to exited with its code or killed, and the kill finishes within the grace window

### Requirement: grep context and truncation metadata
`grep` SHALL accept `context`, `before` and `after`, printing match lines as `path:N:text`, context lines as `path-N-text` and `--` between non-touching groups. When `limit` cuts matches, or a `read` window ends before the file does, the result metadata SHALL say so; the text output SHALL be unchanged.

#### Scenario: Context groups
- **WHEN** grep runs with context 1 over two distant matches
- **THEN** each match has one line either side and the groups are separated by `--`
