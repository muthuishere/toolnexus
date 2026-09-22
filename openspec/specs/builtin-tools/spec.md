# builtin-tools Specification

## Purpose
TBD - created by archiving change add-builtin-tools. Update Purpose after archive.

## Requirements

### Requirement: Builtin tool source

The library SHALL ship a `builtin` tool source that provides a fixed set of default tools. Every
tool from this source SHALL carry `source: "builtin"` and SHALL conform to the existing `Tool`
interface (name, description, inputSchema, source, execute) identically in all four ports
(js/python/golang/java). Tool **names** and **input schemas** SHALL match the opencode reference
exactly, so the four ports remain byte-substitutable.

#### Scenario: Builtin source exposes the default toolset

- **WHEN** a toolkit is assembled with the builtin source enabled
- **THEN** it exposes exactly these tool names: `bash`, `read`, `write`, `edit`, `grep`, `glob`,
  `webfetch`, `question`, `apply_patch`, `todowrite`
- **AND** `websearch` is NOT included (a no-provider stub is not shipped as a builtin; add web search
  as a native/http tool if needed)
- **AND** each tool's `source` field equals `"builtin"`
- **AND** the `skill` tool (its own source) is unaffected

#### Scenario: Names and schemas match opencode

- **WHEN** any builtin tool's inputSchema is emitted
- **THEN** its parameter names, types, and required/optional status match the opencode reference
  tool of the same name (e.g. `edit` takes `path`, `oldString`, `newString`, optional `replaceAll`)

### Requirement: Builtin source is on by default

The builtin tool source SHALL be enabled by default. A toolkit assembled without any builtin-related
configuration SHALL include all default builtin tools.

#### Scenario: Default assembly includes builtins

- **WHEN** a toolkit is built with no `builtins` configuration present
- **THEN** all default builtin tools are present in the toolkit's tool list

### Requirement: Global and per-tool enable/disable

Configuration SHALL control the builtin source at two levels, read from a top-level `builtins` key
(or the `builtins` option). (1) A **whole-source** switch — `builtins: false`, `{disabled: true}`,
or `{enabled: false}` — disables the entire source so **no** builtin tool appears, using MCP's
precedence (`disabled: true` wins; else `enabled: false` disables; otherwise enabled). (2) A
**per-tool** map `builtins.tools` (name→boolean) applied on the all-on baseline: a tool mapped to
`false` SHALL be removed, `true` (or absent) SHALL stay on; unknown names are ignored. A
whole-source-off SHALL short-circuit and the map SHALL NOT be consulted.

#### Scenario: Disabling removes all builtin tools

- **WHEN** configuration sets the builtins toggle off (`builtins: false`, `builtins.disabled = true`,
  or `builtins.enabled = false`)
- **THEN** the assembled toolkit contains none of the builtin tool names
- **AND** MCP tools, the skill tool, and native/http extra tools are unaffected

#### Scenario: Precedence matches MCP semantics

- **WHEN** the builtins config sets `disabled: true`
- **THEN** the source is off regardless of any `enabled` value, matching MCP's `isEnabled` precedence

#### Scenario: Per-tool map disables named tools only

- **WHEN** configuration sets `builtins.tools = { bash: false, write: false }`
- **THEN** `bash` and `write` are absent from the toolkit
- **AND** the other nine builtin tools remain present
- **AND** an unknown name in the map is ignored without error

#### Scenario: Whole-source-off overrides the per-tool map

- **WHEN** configuration sets both `builtins.disabled = true` and a `builtins.tools` map
- **THEN** no builtin tool appears (the map is not consulted)

### Requirement: Builtin source integrates into toolkit assembly

The toolkit SHALL combine the builtin source alongside the existing MCP, skill, and extra-tool
sources, preserving the current first-wins dedupe-by-name behavior so a host-provided tool of the
same name overrides a builtin.

#### Scenario: Name collision keeps first-registered tool

- **WHEN** a host registers an extra tool whose name equals a builtin tool name and the assembly
  order places one before the other
- **THEN** the toolkit keeps the first-registered tool and drops the duplicate, consistent with
  existing dedupe behavior across all four ports

### Requirement: Builtin tools are surfaced via tool schema, not prompt text

Builtin tools SHALL be presented to the model only through the toolkit's tool-schema array
(`toOpenAI`/`toAnthropic`/`toGemini`), exactly like MCP, native, and HTTP tools — NOT injected as
system-prompt text. The client SHALL NOT add a builtins-specific prompt section, matching the
opencode reference where built-in and MCP tools are schema-surfaced and only skills are listed in
the prompt. This preserves cross-language parity and avoids duplicating tool descriptions.

#### Scenario: Builtins appear in the schema array only

- **WHEN** a toolkit with the builtin source enabled emits its provider tool schema
- **THEN** every builtin tool appears in that schema array
- **AND** no builtin-specific section is added to the system prompt text

### Requirement: Skills prompt instructs the model to use the skill tool

The `skillsPrompt()` output SHALL, when at least one skill is available, begin with an instruction
preamble telling the model that skills provide specialized instructions and to use the `skill` tool
to load a skill when a task matches its description, followed by the existing `## Available Skills`
list. This mirrors the opencode reference so listed skills are not missed by default. The preamble
text SHALL be byte-identical across all four ports; when no skills are available the output SHALL
remain the existing empty/"no skills" result with no preamble.

#### Scenario: Preamble precedes the skills list

- **WHEN** `skillsPrompt()` is called and one or more skills have descriptions
- **THEN** the output starts with the instruction preamble (use the skill tool when a task matches a
  skill's description)
- **AND** the `## Available Skills` list of `- **name**: description` entries follows it
- **AND** the preamble string is identical across js/python/golang/java

#### Scenario: No skills means no preamble

- **WHEN** `skillsPrompt()` is called and no skills are available
- **THEN** the output is the existing empty/no-skills result with no instruction preamble

### Requirement: Filesystem and command tools honor safety rules

The command and filesystem tools (`bash`, `write`, `edit`, `apply_patch`) SHALL follow existing repo
secrets rules, since they grant host command execution and filesystem mutation. Environment-expanded values and
command output MUST NOT be logged, and no secret value SHALL be written into spec, test, or example
fixtures. The global toggle SHALL be the supported mechanism for disabling these tools on
locked-down hosts.

#### Scenario: Toggle off removes command and mutation tools

- **WHEN** a host disables the builtin source via the global toggle
- **THEN** `bash`, `write`, `edit`, and `apply_patch` are absent from the toolkit

### Requirement: The question builtin suspends via a kind:"question" Request
The built-in `question` tool SHALL treat asking the human as a §10 suspension, not an immediate
result. On its **first** execution (no resolution present in context) it SHALL return a
suspension — a `ToolResult` whose `metadata.pending` is a `Request` with `kind: "question"`, a
human-readable `prompt` rendered from the questions, and `data.questions` carrying the structured
questions array unchanged. When the loop re-executes the tool after the host's `waitFor` resolved
the request affirmatively (`answer.ok == true`, per the §10 loop rule), the tool SHALL return a
non-error `ToolResult` whose output is the resolution payload (`context.answer.data`), forwarded
verbatim without interpretation — the resolution *is* the answer, as with `kind: "input"`. When no
`waitFor` is configured, the run SHALL halt durably (`RunResult.status == "pending"`) carrying the
`question` request, resumable by a later `run`, exactly as for any other suspension `kind`.

The tool's name (`question`) and input schema SHALL be unchanged; only the return value changes
from an immediate result to a suspension. `kind: "question"` is a convention over the open §10
`kind` vocabulary, not a new closed type. This behavior SHALL be identical across all five ports.

#### Scenario: First call suspends instead of answering
- **WHEN** the `question` tool is executed with a `questions` array and no prior resolution in context
- **THEN** it returns a `ToolResult` with `metadata.pending` present, `pending.kind == "question"`,
  a non-empty `pending.prompt`, and `pending.data.questions` equal to the supplied questions —
  and it does NOT return an immediate non-error result carrying the questions as output

#### Scenario: Resolved re-execute returns the human's answer
- **WHEN** a `waitFor` resolves the question request with `Answer{ok: true, data: <picks>}` and the
  loop re-executes the `question` tool once with that answer in context
- **THEN** the tool returns a non-error `ToolResult` whose output is `<picks>` (the answer data)
  forwarded verbatim, and that output is fed back to the model

#### Scenario: No waitFor halts durably with the question request
- **WHEN** the `question` tool suspends and no `waitFor` is configured on the client
- **THEN** the run does not hang; it returns a `RunResult` with `status == "pending"` whose
  `pending` is the `kind: "question"` request, resumable by calling `run` again later

#### Scenario: A guard-raised Request is honored identically to a tool-raised one
- **WHEN** a `beforeTool` hook returns `{ result: pending({ kind: "approval", prompt: ... }) }`
  so that no underlying tool code runs
- **THEN** the loop detects the suspension downstream of the hook and resolves it through the same
  one `waitFor` (or halts durably when none is set) — identically to a suspension returned by a
  tool such as `question`

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

### Requirement: read returns a content part for a recognised media file

The `read` builtin SHALL return a `ContentPart` when the target file's extension is in a fixed,
spec-listed table (`png`, `jpg`, `jpeg`, `gif`, `webp`, `pdf`, `mp3`, `wav`). In that case
`output` SHALL be a one-line description naming the file and its mime type, and `parts` SHALL hold
one part carrying the file's bytes as base64 with the mime type from the table. For every other
extension the existing UTF-8 text behavior — including `offset`/`limit` windowing — SHALL be
unchanged. The port SHALL NOT sniff magic bytes and SHALL NOT resolve mime types through a
platform mime database, whose contents vary per machine and would break cross-port parity.

#### Scenario: Reading a PNG yields an image part

- **WHEN** `read` is called on a `.png` file
- **THEN** `output` is a one-line description naming the file and `image/png`
- **AND** `parts` holds one `image` part carrying the file's base64 bytes

#### Scenario: Reading a text file is unchanged

- **WHEN** `read` is called on a `.md` file with `offset` and `limit`
- **THEN** the output is that line window exactly as before and `parts` is absent

### Requirement: read returns an error result for undecodable bytes, never a raised exception

When `read` targets a file that is neither in the media extension table nor decodable as UTF-8,
the port SHALL return `ToolResult{isError:true}` naming the file. A decoding failure SHALL NOT
propagate as an unhandled exception out of `execute` into the client loop.

#### Scenario: An unrecognised binary yields an error result

- **WHEN** `read` is called on a `.bin` file whose bytes are not valid UTF-8
- **THEN** an `isError:true` result naming the file is returned
- **AND** no exception escapes the tool into the loop

### Requirement: The shell interpreter is host-set, detected when absent, and reported

The builtin source SHALL accept a `shell` option: an argv prefix whose last element receives
the command string (`["sh","-c"]`, `["bash","-lc"]`, `["cmd","/d","/s","/c"]`,
`["powershell","-NoProfile","-Command"]`). When a host supplies it, the `bash` builtin SHALL
invoke exactly that argv with the command appended, and SHALL NOT substitute, reorder or
second-guess it.

When the option is absent, the library SHALL detect an interpreter **at toolkit construction
time**, not per call. On POSIX platforms the detected interpreter SHALL be `sh -c`. On Windows
the first of the following that resolves on `PATH` SHALL be used, in this order: `%COMSPEC%`
(as `cmd /d /s /c`), `pwsh -NoProfile -Command`, `powershell -NoProfile -Command`,
`bash -lc`. `%COMSPEC%` precedes the PowerShell candidates because PowerShell can be blocked
by execution policy or application-control policy on a managed machine, while `%COMSPEC%` is
always present.

The resolved interpreter SHALL be reported to the host: readable from the toolkit, and carried
on every `bash` result's `metadata` as `shell`. It SHALL NOT be written into the tool's name,
description or input schema, all of which stay byte-identical across ports and platforms.

When no interpreter resolves and the `bash` builtin is enabled, toolkit construction SHALL
fail with an error naming every candidate that was tried. Disabling `bash` through
`builtins.tools` SHALL make construction succeed on a machine with no interpreter.

#### Scenario: A host-supplied shell is used verbatim

- **WHEN** a host constructs a toolkit with `shell` set to `["bash","-lc"]` and the model calls `bash`
- **THEN** the command runs under `bash -lc`
- **AND** the result's `metadata.shell` reports `bash -lc`

#### Scenario: Windows detection prefers COMSPEC over PowerShell

- **WHEN** a toolkit is constructed on Windows with no `shell` option and `%COMSPEC%` resolves
- **THEN** the detected interpreter is `%COMSPEC%` invoked as `cmd /d /s /c`
- **AND** a PowerShell interpreter is not used even when one is also present

#### Scenario: No interpreter at all fails construction, not turn fourteen

- **WHEN** a toolkit is constructed with the `bash` builtin enabled on a machine where no candidate interpreter resolves
- **THEN** construction fails with an error naming each candidate that was tried
- **AND** constructing the same toolkit with `bash` disabled through `builtins.tools` succeeds

### Requirement: One base directory, honoured by every builtin that touches the filesystem

The builtin source SHALL accept a `baseDir` option naming the directory that **relative** paths
resolve against. An absolute path SHALL be unaffected by it. An absent or empty `baseDir` SHALL
mean the host process working directory, which is the behaviour before this change, so a host
that sets nothing observes no difference.

`baseDir` SHALL bind every builtin that reaches the filesystem: `read`, `write`, `edit`, the
default and supplied `path` of `glob` and `grep`, and the file paths **inside `apply_patch`'s
patch text** (`*** Add File:`, `*** Update File:`, `*** Delete File:`). It SHALL also be the
default working directory of `bash`, so a relative path means the same thing to the shell and
to the file tools. An explicit `workdir` argument SHALL still win.

#### Scenario: A relative write lands under the base directory, not the process cwd

- **WHEN** a toolkit with `baseDir` set to a worktree executes `write` with the relative path `apps/api/x_test.go`
- **THEN** the bytes land under the worktree
- **AND** no file is created under the host process working directory

#### Scenario: apply_patch resolves the paths inside the patch text

- **WHEN** a toolkit with `baseDir` set applies a patch whose `*** Add File:` line carries a relative path
- **THEN** the added file lands under `baseDir`

#### Scenario: bash inherits the base directory as its working directory

- **WHEN** a toolkit with `baseDir` set executes `bash` with no `workdir` argument
- **THEN** the command's working directory is `baseDir`
- **AND** a `workdir` argument, when supplied, is used instead

### Requirement: Optional confinement refuses paths whose canonical form leaves the base directory

The builtin source SHALL accept a `confineToBaseDir` option, default **off**. When on, any
builtin that touches the filesystem SHALL refuse a path whose canonical form is neither
`baseDir` nor under it, reporting it as `ToolResult{isError:true}` with a message naming the
refused path — never by raising across the boundary.

Confinement SHALL be decided on the **canonical** form of both the base and the target, not on
a string prefix: canonicalisation resolves symlinks, Windows directory junctions and 8.3 short
names, and normalises case on case-insensitive platforms. A path that does not yet exist SHALL
be canonicalised by resolving its deepest existing ancestor and re-attaching the remaining
segments, so a `write` to a new file is checked as strictly as a `read` of an existing one.

On Windows, a path whose final segment is a reserved device name — `CON`, `PRN`, `AUX`, `NUL`,
`COM1`–`COM9`, `LPT1`–`LPT9`, with or without an extension — SHALL be refused when confinement
is on, because such a path satisfies a containment check and does not write into the directory.

Confinement is a guarantee about **path resolution in the file builtins**. It SHALL NOT be
described as a sandbox, and it does not confine commands run by `bash`.

#### Scenario: A relative escape is refused

- **WHEN** confinement is on and a builtin is given `../outside.txt`
- **THEN** the call returns an error result naming the refused path
- **AND** nothing outside `baseDir` is read, written or listed

#### Scenario: A symlink or junction out of the base directory is refused

- **WHEN** confinement is on and a path inside `baseDir` traverses a symlink (POSIX) or a directory junction (Windows) whose target is outside it
- **THEN** the call returns an error result
- **AND** the decision is made on the canonicalised target, not on the lexical path

#### Scenario: A Windows reserved device name is refused

- **WHEN** confinement is on, on Windows, and a builtin is given the path `CON` inside `baseDir`
- **THEN** the call returns an error result naming the refused path

### Requirement: A timeout or cancellation kills the whole job, not just the shell

The `bash` builtin SHALL start its command such that the command and every process it starts
can be terminated together, and on timeout or cancellation SHALL terminate that whole set: a
new process group on POSIX, and a Job Object or `taskkill /T /F` on Windows. Killing only the
direct child — leaving the real command running, reparented — SHALL NOT be the behaviour of
any port.

Termination SHALL be graceful before it is forceful: the job is asked to terminate (SIGTERM,
or the platform equivalent), given a fixed grace window of **2000 ms**, and only then killed
(SIGKILL, or the platform equivalent). The window is identical in every port.

The window bounds how long the **kill** may take, not how long the **caller** waits: a port SHALL
wait for the job to be gone for at most the window and return as soon as it is gone. Sleeping
through the window regardless SHALL NOT occur — it turns a one-second timeout into a three-second
call.

#### Scenario: A timeout returns as soon as the job is gone

- **WHEN** a `bash` call times out and the job ends as soon as it is asked to stop
- **THEN** the call returns without waiting out the remaining grace window

Where a port must enumerate descendants rather than signal a group, it SHALL capture that
enumeration **before** terminating the parent: once the parent dies its children are reparented
and are no longer reachable from its process id.

The result SHALL distinguish the two outcomes in `metadata`: `timedOut` and whether the whole
job was killed (`killedTree`). `output` SHALL NOT change for either outcome, so a port can
report this without moving a conformance golden.

#### Scenario: A timed-out command's grandchild does not outlive the call

- **WHEN** `bash` runs a command that starts a longer-running child process and the call times out
- **THEN** neither the command nor the process it started is still running once the call returns
- **AND** the result's `metadata` reports the timeout and that the job was killed

#### Scenario: Cancelling the surrounding call stops the work

- **WHEN** the context, token or process driving a `bash` call is cancelled while the command runs
- **THEN** the command and everything it started are terminated

### Requirement: A grep match line carries the walk-root-relative path it was sorted by

`grep` SHALL emit each match as `<rel>:<line>:<text>` where `<rel>` is the matched file's path
**relative to the walk root**, `/`-separated on every platform — the same string the matches
were sorted by. Emitting the joined, absolute or platform-separated path while ordering by the
relative one SHALL NOT occur in any port.

#### Scenario: grep emits relative, forward-slashed paths on every platform

- **WHEN** `grep` matches a file two directories below its `path` argument
- **THEN** the match line begins with the `/`-separated path relative to that argument
- **AND** the emitted path is identical to the sort key, on Windows as on POSIX
