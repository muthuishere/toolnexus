## Why

Three issues (#100, #101, #102) arrived against `golang v0.19.0` from one consumer — a Go
workflow engine that gives each run an isolated git worktree and targets Linux, WSL and
Windows. They read as three bugs in one file. Spikes measured them as **one defect in three
places, present in all seven ports**: every builtin closes over an ambient property of the
host process that no host can inspect or set.

| issue | reported | measured (`spikes/builtin-host-boundary/SPIKE.md`) |
|---|---|---|
| #100 | `bash` hardcodes `sh -c`, so it cannot run on native Windows, and means *dash* on Debian | correct, and worse: on Windows go/java/csharp/elixir/clojure hard-fail `"sh": executable file not found`, while **js/python quietly get cmd.exe** — `echo $HOME` prints the literal `$HOME` with exit 0. One call, two wrong answers |
| #101 | relative paths resolve against the host process cwd; no way to scope the file builtins to a directory | correct, and verified with the shipped Go port **on a real Windows box** (`RELATIVE_LANDED_IN=host_cwd`). The consumer's own incident: an 18 KB test file written into the platform's repository instead of the run's worktree, reported as success |
| #102 | a timeout kills only `sh`; the real command keeps running | correct in **all seven ports**, and on Windows too — `Process.Kill` orphans the tree there exactly as `CommandContext` does on POSIX |

Two defects no issue reported, found only because the spike ran the shipped code on a real
Windows machine:

1. **Go's `grep` emits the joined path, not the walk-root-relative one** — `"/tmp/gp/tree/sub/a.txt:1:x"`
   on macOS, `"tree\\sub\\a.txt:1:x"` on Windows. `SPEC.md §4A` requires a `/`-separated path
   relative to the walk root and says *"SORT ON THE SAME STRING you emit"*; Go sorts by `rel`
   and prints `file`. The js port is correct, so this is Go-only drift — and it is the exact
   failure §4A's own note describes.
2. **A Windows directory junction needs no privilege** (`mklink /J`, `IS_ADMIN=False`) and
   defeats lexical path canonicalisation. Go's `filepath.EvalSymlinks` *fails* on a path
   through one, which would make a containment check silently report an escape as contained.

## What Changes

Three options on the builtin source, in all seven ports, each defaulting to today's
behaviour so an existing host observes no difference:

- **`shell`** — an argv prefix (`["sh","-c"]`, `["cmd","/d","/s","/c"]`, `["powershell","-NoProfile","-Command"]`,
  `["bash","-lc"]`). Given, it is used verbatim. Absent, it is **detected** at toolkit
  construction: `sh -c` on POSIX; on Windows `%COMSPEC%` first, then `pwsh`, then
  `powershell`, then a POSIX `bash` if one resolves. The resolved interpreter is reported.
  Construction fails, naming what it tried, when nothing resolves and `bash` is enabled.
- **`baseDir`** — the root that relative paths resolve against, honoured by `read`, `write`,
  `edit`, `glob`, `grep`, **the paths inside `apply_patch`'s patch text**, and as the default
  for `bash`'s `workdir`. `""` ⇒ the process cwd, i.e. today.
- **`confineToBaseDir`** — opt-in refusal of any path whose canonical form lies outside
  `baseDir`, including through symlinks and Windows junctions, plus outright refusal of
  Windows reserved device names (`CON`, `NUL`, `COM1`…), which pass a containment check and
  do not write into the directory.

Plus the behaviour the timeout was always meant to have: **a timeout or cancellation kills the
whole job** — process group on POSIX, Job Object / `taskkill /T /F` on Windows — with SIGTERM,
a fixed grace window, then SIGKILL. Whether the tree was killed, and which interpreter ran,
are reported in `metadata`; **`output` does not move**, so no conformance golden moves.

And the two spike findings: Go's `grep` path is corrected, and the `/`-separator rule is
verified on Windows rather than assumed.

## Impact

- **Affected specs:** `builtin-tools`
- **Affected code:** `js/src/builtin.ts`, `python/src/toolnexus/builtin.py`, `golang/builtin.go`
  (+ new `builtin_unix.go` / `builtin_windows.go`), `java/…/BuiltinTools.java`,
  `csharp/src/Toolnexus/BuiltinTools.cs`, `elixir/lib/toolnexus/builtin.ex`,
  `clojure/src/toolnexus/builtin.cljc`, and each port's toolkit options plumbing.
- **Affected contract:** `SPEC.md §4A` — the interpreter, the base directory, and the kill
  semantics become part of the written contract instead of an accident of each runtime.
- **Not covered:** Java, C#, Elixir and Clojure are **unverified on Windows** — those runtimes
  are not installed on the Windows box available to us. Their implementations are written
  against documented platform APIs and the gap is named in `CHANGELOG.md`. Nothing here
  confines `bash` itself: `cd ..`, `env -C` and an absolute path all still leave `baseDir`
  (measured), which is ADR 0033's subject, not this change's.
