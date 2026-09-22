## ADDED Requirements

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
