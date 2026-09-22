# ADR 0034 — the builtin host boundary: the interpreter is chosen, the root is named, and a timeout kills the job

- **Status:** **Accepted** — spikes run, with controls, on macOS and on a native Windows box.
  See `spikes/builtin-host-boundary/SPIKE.md` for every measurement cited here.
- **Date:** 2026-09-22
- **Driver:** three consumer issues against `golang v0.19.0`, all from the same host (a Go
  workflow engine targeting Linux, WSL and Windows): **#100** `bash` hardcodes `sh -c`, so
  the tool cannot run at all on native Windows and silently means *dash* on Debian;
  **#101** the file builtins resolve a relative path against the **host process cwd** — which
  wrote an 18 KB test file into the platform's own repository instead of the run's git
  worktree, and reported success; **#102** a timeout kills the shell and leaves the real
  command running, so cancellation is a report rather than an effect.
- **Related:** ADR 0033 (the builtin **execution** seam — proposed, no code; this ADR is the
  narrower, unconditional half and does not presume it), `SPEC.md §4A` (the ten builtins and
  the capped-listing rule), issue #100/#101/#102.

---

## Context

The three issues look like three bugs. They are one: **every builtin closes over ambient
properties of the host process** — its `PATH` and platform (which interpreter `sh` means),
its working directory (what a relative path means), and its process tree (what a kill
reaches). None of those is inspectable or settable by a host, and all three were measured
wrong in all seven ports, not just Go:

- **All seven ports orphan the job on timeout** (SPIKE §1). The direct child is a shell; the
  work is its child; the kill reaches only the shell. On Windows `Process.Kill` behaves the
  same way (SPIKE §4.2), so this is not a POSIX-only oversight.
- **On Windows the ports disagree about what `bash` even is** (SPIKE §2.2). go/java/csharp/
  elixir/clojure hard-fail `"sh": executable file not found in %PATH%`; js/python pass
  `shell:true`/`shell=True` and quietly get **cmd.exe**, where `echo $HOME` prints the
  literal `$HOME` with exit 0. Two different wrong answers for one call is a §0 break.
- **Relative paths resolve against the host process cwd** on both platforms, measured with
  the shipped Go port on Windows: `RELATIVE_LANDED_IN=host_cwd`. A `bash` call can be pinned
  with `workdir`; `read`, `write`, `edit`, `glob`, `grep` and `apply_patch` cannot be pinned
  at all, which is why the consumer ended up rewriting tool arguments in a `BeforeTool` hook
  and parsing `apply_patch`'s grammar to rewrite the paths *inside the patch text*.

That last detail is the argument for fixing this in the library rather than in the host:
**the host knows which directory; only the library knows which arguments are paths.** One of
those two facts is stable. The other grows every time a builtin is added.

---

## Decisions

### D1 — The interpreter is a host-set argv prefix. Absent, it is detected; given, it is used verbatim.

`Shell` is an argv prefix — `["sh","-c"]`, `["bash","-lc"]`, `["cmd","/d","/s","/c"]`,
`["powershell","-NoProfile","-Command"]`. When the host sets it, the library runs exactly
that and never second-guesses it. When the host does not, the library detects:

| platform | order |
|---|---|
| POSIX | `$SHELL`-independent: `sh -c`, which is what every port means today |
| Windows | **`%COMSPEC%` (`cmd /d /s /c`)** → `pwsh -NoProfile -Command` → `powershell -NoProfile -Command` → `bash -lc` if one resolves (Git for Windows / WSL) |

**COMSPEC comes first on Windows, and that ordering is the decision.** PowerShell was present
on the test box but PowerShell is routinely blocked by execution policy or AppLocker on a
managed machine, and `pwsh` was absent (SPIKE §2.2). `%COMSPEC%` is the one interpreter
Windows guarantees. A host that wants POSIX semantics on Windows says so in one field.

**Resolution happens at toolkit construction, not per call**, and what was chosen is
**reported** — a host can print it, and our own `doctor` output can carry it. A missing
interpreter is a configuration fact; discovering it on turn 14 of a paid run is the expensive
place to learn it. If nothing resolves *and* `bash` is enabled, construction fails naming the
candidates tried (fail closed — ADR 0033 D4's rule, applied to the only seam that exists
today). `builtins.tools.bash:false` is the supported way to run on a box with no shell.

**What does not change: the tool's name, description and input schema.** Renaming `bash`
would break every host and every transcript; putting the resolved interpreter in the
*description* would make the description platform-dependent, and the description is part of
what §0 pins byte-identical. The interpreter is reported in `ToolResult.metadata` and through
the toolkit, where it is inspectable without moving a conformance golden.

### D2 — One named root, honoured by every builtin that touches the filesystem.

`BaseDir` (`base_dir` / `baseDir` / `:base-dir` per port idiom). Relative paths resolve
against it; absolute paths are unaffected; **`""` means the process cwd, which is today's
behaviour byte-for-byte**, so this is not a breaking change and no existing host moves.

It binds *all* of them, and the list is the point:

- `read`, `write`, `edit` — the plain cases.
- `glob`, `grep` — including the default `path`, which is `os.Getwd()` today.
- **`apply_patch`** — the paths in `*** Add File:` / `*** Update File:` / `*** Delete File:`
  lines resolve against it. This is the case a host genuinely cannot reach, because those
  paths are not arguments; they are content.
- **`bash`'s `workdir` defaults to it**, so the shell and the file tools stop disagreeing
  about what a relative path means.

### D3 — Confinement is opt-in, enumerates what is *included*, and is a resolution guarantee — not a sandbox.

`ConfineToBaseDir` (default **off**). On, a builtin refuses a path whose canonical form is
not `BaseDir` or under it, and says so as a normal `ToolResult{isError:true}`.

**Inclusion, not exclusion.** The consumer patched this class of escape three times by
enumerating what to exclude (a `cd` scanner, then absolute paths, then relative paths on
non-`bash` tools); each patch was correct and still incomplete. Only the library sees every
path, so only the library can state the rule positively.

Canonicalisation is the whole substance of it, and the spike changed the design twice:

- Resolve symlinks **on the deepest existing ancestor and re-attach the tail** — a file
  `write` is about to create has no realpath, and a check that only works on existing files
  is not a check for `write`.
- **A string-prefix comparison is wrong.** On Windows the base arrived as `C:\Users\MUTHUI~1\…`
  and canonicalised to `C:\Users\muthuishere\…`: same directory, different string (SPIKE §3.2).
  Compare canonical-to-canonical, by path relation.
- **Go must not use `filepath.EvalSymlinks` on Windows.** Through a directory junction it
  returns `The system cannot find the path specified.`, and on that error the check falls back
  to the lexical path and reports an escape as *contained* — it inverts. `GetFinalPathNameByHandle`
  resolves it correctly. Node's `realpathSync.native` and Python's `os.path.realpath` resolve
  junctions; **Python does not normalise case**, so it needs `normcase`. (SPIKE §3.2.)
- **A directory junction needs no privilege** (`IS_ADMIN=False`), while a symlink does. The
  escape an agent can mint for itself with the `bash` tool it already has is the junction, so
  junction resolution is the requirement, not a nicety.
- **Windows reserved device names are refused outright**: `CON`, `PRN`, `AUX`, `NUL`,
  `COM1-9`, `LPT1-9`, with or without an extension. Measured: `CON` *passes* a containment
  check and writing to it created no file — a path that is "inside" the directory and does
  not write into it.

**And the limit is stated in the same breath, because it is measured too:** with `workdir`
pinned to the base, `cd .. && ls`, `env -C / ls`, and `type <outside>\secret.txt` all still
work (SPIKE §3.3). `ConfineToBaseDir` binds **the file builtins' path resolution**. It is not
a sandbox, it does not claim to contain `bash`, and anything stronger is OS confinement of the
child — ADR 0033's subject, not this one's.

### D4 — A timeout kills the job, not the shell. TERM, a grace period, then KILL.

Per platform, using each runtime's real mechanism rather than a lowest common denominator:

| port | POSIX | Windows |
|---|---|---|
| go | `SysProcAttr{Setpgid:true}`, signal `-pgid` | Job Object (`KILL_ON_JOB_CLOSE`), `taskkill /T /F` fallback |
| js | `detached:true`, `process.kill(-pid)` | `taskkill /T /F /PID` |
| python | `start_new_session=True`, `os.killpg` | `taskkill /T /F /PID` |
| java | `ProcessHandle.descendants()` snapshot → destroy → destroyForcibly | same API (unverified on Windows) |
| csharp | descendant snapshot + `kill(2)` via libc P/Invoke for the TERM step, then `Process.Kill(entireProcessTree: true)` | `Process.Kill(entireProcessTree: true)` (unverified on Windows) |
| elixir | `ps` descendant walk from `Port.info(:os_pid)`, then `kill` | `taskkill /T /F` (unverified) |
| clojure | pid file + descendant snapshot taken while the parent is alive, then signalled | `taskkill /T /F` (unverified) |

Two findings are load-bearing:

- **The descendant snapshot must be taken before the parent dies.** Measured: after the
  parent is killed its children are reparented and `REACHABLE_AFTER_KILL=0` — a post-hoc
  tree walk finds nothing. The java and elixir fixes capture first, then kill; clojure cannot
  capture at all through koine's internal kill, which is why it gets a process group up front.
- **.NET has no graceful kill.** Measured: both `Process.Kill()` and `Process.Kill(entireProcessTree: true)` send **SIGKILL**, and `CloseMainWindow()` returns `False` and does nothing. The TERM step in that port therefore costs a `DllImport("libc") kill(2)` — platform-guarded, no third-party dependency — and it must reach the **job**: TERM'ing the direct child alone kills the shell instantly, leaving the tree reparented and the "graceful" step self-defeating (measured: `gracetree_kill_child_only=ORPHAN_SURVIVED`).
- **`SIGTERM` then `SIGKILL`, with a grace window.** A test runner that gets TERM removes its
  temp directories; one that gets KILL does not. The window is fixed and spec'd so the seven
  ports agree.
- **The grace window is POSIX-only, and that too was measured rather than reasoned.** The first
  Windows implementation asked politely with `taskkill /T` (no `/F`) and it made things *worse*:
  every console process in the tree refuses ("can only be terminated forcefully") while the
  parent can still go down, which reparents the grandchild and leaves it running. Measured on the
  Windows box against the shipped js port: `KILLEDTREE=false`, `ORPHAN=SURVIVED`. All seven ports
  now stop the job outright there — a Job Object close, or one `taskkill /T /F`.

**Correction, and it is the reason the per-port spikes exist.** This ADR first endorsed a `set -m`
job-control wrapper for the ports with no native process-group call. The per-port probes refuted
it: under **dash** `set -m` reports `can't access tty; job control turned off`, the pgid kill then
fails, and the job survives — and `/bin/sh` is dash on the Debian-family Linux that issue #100 is
about. It also appends bash job notifications into the combined output, which moves `output`.
The replacement is the rule the java and elixir fixes already follow: **write the child's pid,
snapshot its descendants while it is still alive, then signal them.** No job control, no `perl`,
no koine release.

A further finding, also from the probes: **koine's `real-path` disagrees between the two clojure
hosts on case** — the JVM normalises it, cljgo does not — so one path yields two confinement
verdicts. That is a §0 break inside koine, visible only because clojure has two hosts; the port
folds case itself rather than waiting for a koine release.

### D5 — The two timeout outcomes are different facts, and `output` still does not move.

A timeout that killed the whole job and a timeout that could not are not the same event, and
only the library can tell them apart. Both are reported — in **`metadata`**
(`timedOut`, `killedTree`, `shell`), never by changing `output`.

`output` is what the transcript, compaction, token estimation and the conformance goldens
see. Putting new facts in `metadata` is the same ruling ADR 0033 made for truncation
notices, for the same reason: a port can become more honest without any golden moving.

### D6 — Per-platform source files are allowed, and are the smaller cost.

`_unix.go`/`_windows.go`, a `runtime.GOOS`/`process.platform`/`:os.type` branch, a
`[SupportedOSPlatform]` guard. This repo has avoided them, which is part of why none of this
was fixed. The alternative — one portable expression of process-group semantics — does not
exist: there is no signalable process group on Windows at all
(`CREATE_NEW_PROCESS_GROUP` only makes Ctrl-Break deliverable, and a service-hosted process
has no console to break), so a "portable" implementation would just be the POSIX one, wrong
on Windows, which is the bug.

This is **not** ADR 0033's twenty-one platform-confinement implementations. It is one
process-group call and one path canonicalisation per port, both with a measured control.

### D7 — The two defects the spikes found ship in the same change.

1. **Go's `grep` emits the joined path, not the walk-root-relative one** — measured
   `"/tmp/gp/tree/sub/a.txt:1:x"` on macOS and `"tree\\sub\\a.txt:1:x"` on Windows, where
   `§4A` requires a `/`-separated path relative to the walk root and says *"SORT ON THE SAME
   STRING you emit"*. It sorts by `rel` and prints `file`. The js port is correct, so this is
   Go-only drift and exactly the failure §4A's own note describes.
2. **Windows path separators in any builtin listing** follow the same §4A rule, verified on
   the Windows box rather than assumed (`glob` was already correct there: `"sub/a.txt"`).

Neither was in an issue. Both are §4A violations found only because the spike ran the shipped
code on a real Windows machine, which is the argument for having done that.

---

## Consequences

- Three new options (`Shell`, `BaseDir`, `ConfineToBaseDir`) in seven ports, all defaulting
  to today's behaviour, so an existing host upgrades with no diff in what it observes.
- `bash` works on native Windows for the first time, with the interpreter named rather than
  guessed, and the same call no longer means two different things across ports.
- A cancelled or timed-out `bash` stops the work. The per-call `timeout` becomes a budget
  instead of a report.
- Per-platform files enter the repo. Seven ports × two platform paths is the maintenance
  cost, and it is paid where CI can see it: the spike's probes become tests.
- **Three ports are verified on native Windows, with their shipped code.** go, js and python were
  run on the Windows box (`spikes/builtin-host-boundary/win/{goreal,jsreal,pyreal}`): the
  interpreter detected as `cmd /d /s /c`, a relative write landing in `baseDir`, `..\..` and `CON`
  refused, no orphan after a timeout, and `grep` emitting `sub/a.txt:1:x` where it used to emit
  `tree\sub\a.txt:1:x`.
- **Running them there paid for itself immediately**: it found a Windows-only defect in the js
  implementation — `taskkill` invoked without `/PID`, so every kill failed silently and the job
  survived — which every POSIX test suite passed straight through, because that line never runs on
  POSIX.
- **Java, C#, Elixir and Clojure remain unverified on Windows** — those runtimes are not
  installed on the box we have. That is named in `CHANGELOG.md` and in the change's tasks,
  because an omission that stops being mentioned is indistinguishable from something that was
  finished.

## What was rejected

- **Renaming `bash` to `shell`.** The breakage buys nothing the reported interpreter does not.
- **Putting the interpreter in the tool description.** It would make a §0-pinned string
  platform-dependent.
- **Defaulting `ConfineToBaseDir` to on.** It would change behaviour for every host that
  passes absolute paths outside a base it never set, and this ADR's whole claim to being
  safe to ship is that absent options are byte-identical.
- **Guessing a POSIX shell on Windows (Git Bash / WSL first).** A host that wants POSIX
  semantics says so; silently preferring a shell that may not exist reintroduces #100 with a
  longer error message.
- **Waiting for ADR 0033.** The seam is proposed, unspiked and large. These three defects are
  present, measured, and fixable without it — and a seam does not make an orphaned test
  runner or a file written into the wrong repository any less wrong.
