## ADDED Requirements

### Requirement: Built-in tools are registered on every toolkit by default
Every `Toolkit` SHALL include a default set of built-in tools — `read`, `write`, `execute`, `grep`,
and `glob` — without any opt-in. Each built-in tool SHALL carry `source: "builtin"`. The built-ins
SHALL be added **last** in construction order (after MCP, skill, and caller-supplied tools) so that,
under the existing first-name-wins rule, a caller-supplied tool of the same name shadows the built-in.

#### Scenario: Fresh toolkit exposes the built-ins
- **WHEN** a toolkit is created with no `defaultTools` option and no other tool sources
- **THEN** `tools()` contains exactly `read`, `write`, `execute`, `grep`, `glob`, each with
  `source: "builtin"`, and calling `execute("read", ...)` dispatches to the built-in read tool

#### Scenario: Caller tool overrides a built-in of the same name
- **WHEN** the caller passes their own tool named `execute` via `extraTools`
- **THEN** the caller's `execute` is kept and the built-in `execute` is dropped (first name wins),
  and no duplicate-name warning changes the caller's tool

### Requirement: Built-in tools can be disabled or scoped
The toolkit SHALL accept a `defaultTools` option that is `true` (default), `false`, or an object
`{ rootDir?, exclude? }`. `false` SHALL remove all built-in tools. The `exclude` array SHALL remove
the named built-ins while keeping the rest. `rootDir` SHALL set the confinement root (default: the
process working directory).

#### Scenario: Disable all built-ins
- **WHEN** a toolkit is created with `defaultTools: false`
- **THEN** `tools()` contains none of `read`, `write`, `execute`, `grep`, `glob`

#### Scenario: Exclude only the shell tool
- **WHEN** a toolkit is created with `defaultTools: { exclude: ["execute"] }`
- **THEN** `tools()` contains `read`, `write`, `grep`, `glob` but not `execute`

### Requirement: Built-in file tools are confined to a root directory
The `read`, `write`, `grep`, and `glob` tools SHALL resolve every path argument against `rootDir` and
SHALL refuse any path that resolves outside `rootDir`. A refused path SHALL return
`{ isError: true }` and SHALL NOT read, create, modify, or list any file. Paths in tool output SHALL
be expressed relative to `rootDir` using `/` separators. The `execute` tool SHALL run its command
with the working directory set to `rootDir`.

#### Scenario: Path traversal is refused, filesystem untouched
- **WHEN** `write` is called with `path: "../escape.txt"` and any content, with `rootDir` set to a
  temp directory
- **THEN** the result has `isError: true` with output `write: ../escape.txt: outside root`
- **AND** no file is created outside `rootDir`

#### Scenario: Output paths are root-relative
- **WHEN** `glob` is called with `pattern: "**/*.md"` and files exist at `rootDir/docs/a.md`
- **THEN** the output contains `docs/a.md` (relative, `/`-separated), not the absolute path

### Requirement: `read` built-in tool
The `read` tool SHALL take `path` (required string) and optional `offset` (1-based integer line) and
`limit` (integer line count), and SHALL return the file's UTF-8 text. With `offset`/`limit` it SHALL
return only those lines, verbatim and without line numbering. A missing file, a directory, or
non-UTF-8 content SHALL return `{ isError: true }` with output `read: <path>: <reason>`. Its
`inputSchema` SHALL be `{type:"object", properties:{path:{type:"string"}, offset:{type:"integer"},
limit:{type:"integer"}}, required:["path"], additionalProperties:false}`.

#### Scenario: Read a whole file
- **WHEN** `read` is called with `path: "notes.txt"` for a file containing `hello\nworld\n`
- **THEN** the result is `{ output: "hello\nworld\n", isError: false }`

#### Scenario: Read is line-ranged by offset and limit
- **WHEN** `read` is called with `path: "notes.txt"`, `offset: 2`, `limit: 1` for `a\nb\nc\n`
- **THEN** the output is `b\n`

#### Scenario: Missing file is an error
- **WHEN** `read` is called with `path: "nope.txt"` and no such file exists
- **THEN** the result is `{ isError: true }` with output `read: nope.txt: not found`

### Requirement: `write` built-in tool
The `write` tool SHALL take `path` (required string) and `content` (required string), create parent
directories as needed, write the content as UTF-8 (overwriting any existing file), and return
`Wrote <N> bytes to <relpath>` where `N` is the UTF-8 byte length. An I/O failure or out-of-root path
SHALL return `{ isError: true }` with output `write: <path>: <reason>`. Its `inputSchema` SHALL be
`{type:"object", properties:{path:{type:"string"}, content:{type:"string"}}, required:["path","content"],
additionalProperties:false}`.

#### Scenario: Write reports byte count and relative path
- **WHEN** `write` is called with `path: "out/x.txt"`, `content: "hi"` under a temp `rootDir`
- **THEN** the file `out/x.txt` exists with contents `hi`
- **AND** the output is `Wrote 2 bytes to out/x.txt` with `isError: false`

### Requirement: `execute` built-in tool
The `execute` tool SHALL take `command` (required string) and optional `timeout` (ms, default 30000),
run the command through the platform shell with working directory `rootDir`, and return the combined
stdout+stderr with the trailing newline trimmed. Exit code 0 SHALL be `isError: false`. A non-zero
exit SHALL be `isError: true` with the output suffixed by `\n[exit <code>]`; a timeout SHALL be
`isError: true` suffixed by `\n[timeout]`. The command text SHALL NOT be logged by the library.

#### Scenario: Successful command returns combined output
- **WHEN** `execute` is called with `command: "echo hello"`
- **THEN** the result is `{ output: "hello", isError: false }` with `metadata.exitCode == 0`

#### Scenario: Non-zero exit is an error with suffix
- **WHEN** `execute` is called with a command that exits `3` after printing `boom` to stderr
- **THEN** the result has `isError: true` and output ending with `boom\n[exit 3]`

### Requirement: `grep` built-in tool
The `grep` tool SHALL take `pattern` (required regex string) and optional `path` (default `rootDir`)
and `glob` (default `**/*`), search matching files' lines, and return matches as
`<relpath>:<line>:<text>` joined by `\n`, sorted by path then ascending line number. No match SHALL
return `{ output: "", isError: false }`. An invalid regex or out-of-root path SHALL return
`{ isError: true }` with output `grep: <reason>`.

#### Scenario: Matches are sorted and formatted deterministically
- **WHEN** `grep` is called with `pattern: "TODO"` over a tree where `a.txt` line 2 and `b.txt`
  line 1 contain `TODO`
- **THEN** the output is `a.txt:2:...\nb.txt:1:...` (sorted by path then line), identical across ports

#### Scenario: No match yields empty non-error output
- **WHEN** `grep` is called with a pattern that matches nothing
- **THEN** the result is `{ output: "", isError: false }`

### Requirement: `glob` built-in tool
The `glob` tool SHALL take `pattern` (required string) and optional `path` (default `rootDir`), and
return the relative paths of matching files, `/`-separated and sorted ascending, joined by `\n`. No
match SHALL return `{ output: "", isError: false }`. An out-of-root `path` SHALL return
`{ isError: true }` with output `glob: <reason>`.

#### Scenario: Glob returns sorted relative paths
- **WHEN** `glob` is called with `pattern: "**/*.md"` over a tree containing `b.md` and `a/c.md`
- **THEN** the output is `a/c.md\nb.md` (sorted, relative, `/`-separated)

### Requirement: Built-in toolset is at parity across all five ports
The built-in tools SHALL produce byte-identical success and error output, identical input schemas,
and identical default-on / disable behavior in js, python, golang, java, and csharp when run against
the same file tree and `rootDir`.

#### Scenario: Same tree, identical bytes
- **WHEN** the same temp file tree and `rootDir` are given to `read`, `write`, `grep`, and `glob` in
  each of the five ports
- **THEN** each tool returns the same output bytes and the same `isError` flag in every port
