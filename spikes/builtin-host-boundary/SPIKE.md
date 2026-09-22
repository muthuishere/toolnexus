# Spike — the builtin host boundary: shell, base directory, process lifetime, and Windows

**Drives issues #100 (hardcoded `sh -c`), #101 (relative paths resolve against the host
process cwd), #102 (a timeout kills only the shell, not the job).** Every number below was
measured on this machine pair on 2026-09-22, not reasoned about:

- **POSIX host** — macOS 26.4, arm64 (Darwin 25.4.0). All seven toolchains present.
- **Windows host** — native `windows/arm64`, Windows 10.0.26200, reached over `agentbus`
  (`muthukumara7b6b`). Go 1.26.5 (windows/amd64 toolchain), Node 24, Python 3.13 present;
  **java, dotnet, elixir and clojure are NOT installed there**, so those four ports'
  Windows behaviour is stated from their platform APIs and explicitly marked unverified.

Every probe carries a **control** — the same command with no kill at all. A spike whose
"fixed" arm passes because the probe was broken measures nothing, and the first version of
the Windows probe failed exactly that way (§3.1).

---

## 1. S1 — does the timeout kill reach the grandchild? (#102)

`spikes/builtin-host-boundary/s1-orphan/` · run `bash run.sh`

The command is `sleep 0.2; sh -c 'sleep 1; touch $MARKER'` with a **300 ms** timeout. The
outer shell is the direct child; the inner `sh` is a grandchild that writes the marker at
~1.2 s. **Marker present 2 s later ⇒ the grandchild outlived the kill.** The `sleep 0.2`
matters: without it the outer shell `exec`s the single command and there is no grandchild
to orphan — the first two runs of this spike reported a clean kill for that reason.

| port | today's code | verdict | fix under test | verdict |
|---|---|---|---|---|
| go | `exec.CommandContext` (`golang/builtin.go:338`) | **ORPHAN_SURVIVED** | `SysProcAttr{Setpgid:true}` + `Kill(-pgid, TERM)`→`KILL` | killed whole job |
| js | `spawn(cmd,{shell:true})` + `child.kill` (`js/src/builtin.ts:163`) | **ORPHAN_SURVIVED** | `detached:true` + `process.kill(-pid)` | killed whole job |
| python | `subprocess.run(shell=True, timeout=)` (`builtin.py:189`) | **ORPHAN_SURVIVED** | `start_new_session=True` + `os.killpg` | killed whole job |
| java | `ProcessBuilder` + `destroyForcibly` (`BuiltinTools.java:237`) | **ORPHAN_SURVIVED** | `descendants()` → destroy → grace → destroyForcibly (`DESCENDANTS=2`) | killed whole job |
| csharp | `/bin/sh -c` + `p.Kill()` (`BuiltinTools.cs:273`) | **ORPHAN_SURVIVED** | `p.Kill(entireProcessTree:true)` | killed whole job |
| elixir | `Port.open` + `Port.close` (`builtin.ex:230`) | **ORPHAN_SURVIVED** | `ps -eo pid=,ppid=` walk from `Port.info(:os_pid)`, kill each (`DESCENDANTS=2`) | killed whole job |
| clojure | `proc/sh` `:timeout-ms` (`builtin.cljc:254`) | **ORPHAN_SURVIVED** | see §1.1 — the only port that cannot fix this with the code it has | — |

**All seven ports orphan. This is not a Go defect, it is the shipped behaviour everywhere.**

### 1.1 The clojure finding, and the reparenting trap

koine's `sh` performs the kill *inside* the library (`.destroyForcibly` on the JVM,
`(:kill p)` on cljgo) and exposes no pid, so the port has nothing to signal. Two candidate
workarounds were measured:

- **`postwalk`** — record `$$` to a pidfile, then after the timeout walk `ps` from that pid
  and kill the descendants. **FAILS: `REACHABLE_AFTER_KILL=0`.** The parent is already dead,
  so its children have been reparented to init and are no longer reachable *from its pid*.
  **A tree walk must snapshot the descendants BEFORE the parent dies** — which is also why
  the java fix captures `descendants()` before `destroy()`, and why the elixir fix walks
  before killing.
- **`pgroup`** — make the child a process-group leader up front so the group id is known
  without a live parent. **Works** (`PGID=34447`, tree killed), but the probe used
  `perl -e 'setpgrp(0,0)'`, and perl is not a dependency this library may acquire.

A perl-free version of the same idea was measured and **then refuted**. `set -m` (job control)
does put a background compound command in its own process group on bash:

```
$ sh -c 'set -m; { sleep 0.2; sh -c "sleep 1; touch /tmp/m2"; } & echo $! > /tmp/pg2; wait $!'
$ kill -TERM -$(cat /tmp/pg2)        # marker never appears — on macOS /bin/sh, i.e. bash
```

**Under `dash` it does not work at all**, measured in the per-port probes
(`ports/elixir-clojure-findings.md`): `set: can't access tty; job control turned off`, then
`kill: -PGID: No such process`, then `ORPHAN_SURVIVED`. `/bin/sh` **is** dash on Debian-family
Linux — the platform issue #100 is about — so a fix that depends on `set -m` fails exactly where
the shell question was raised. Plain `set -m` also appends bash's job notifications (`[1]+ Done …`)
into the combined output, moving `output` for every command; only the `wait $! 2>/dev/null` and
`set +m`-before-`wait` spellings are byte-identical to the control.

**What replaces it, for every port with no native process-group call** (clojure, csharp, elixir):
the command writes its own pid (`sh -c 'echo $$ > pidfile; exec <command>'`; `exec` keeps the pid),
and the descendants are **snapshotted from that pid while the parent is still alive**, then
signalled. That is the same rule the java and elixir fixes already obey, and it needs no job
control, no `perl`, and no koine release.

## 2. S2 — which interpreter actually runs? (#100)

`spikes/builtin-host-boundary/s2-shell/` · `node probe.mjs`, `python probe.py`

### 2.1 `sh` is not one language (POSIX)

| host shell | `[[ -d . ]] && echo bashism-ok` |
|---|---|
| macOS `/bin/sh` (bash in sh mode) | `bashism-ok` |
| `dash` | `dash: 1: [[: not found` (exit 127) |

The tool is **named `bash`**, its description says "Run a shell command", and the
interpreter is `sh`. On Debian-family Linux `/bin/sh` is dash, so a model that writes
`[[ ]]`, arrays or `$'…'` — because the tool is called bash — gets a syntax error. The
name, the description and the interpreter are three different claims today.

### 2.2 Native Windows: measured, and the ports disagree two ways

What resolves on the Windows box (`win/probe-env.ps1`):

```
sh -> MISSING          bash -> MISSING         pwsh -> MISSING
powershell -> C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe   (5.1)
cmd -> C:\WINDOWS\system32\cmd.exe            COMSPEC=C:\WINDOWS\system32\cmd.exe
taskkill -> C:\WINDOWS\system32\taskkill.exe
```

The **real Go port**, cross-compiled and run there (`win/goreal/`, the shipped
`CreateToolkit` + `bash` builtin, not a reimplementation):

```
BASH_ISERROR=true BASH_OUTPUT="bash: exec: \"sh\": executable file not found in %PATH%"
```

The **js and python** ports use `shell:true` / `shell=True`, which on Windows means
`%COMSPEC% /c` — so they do not fail, they silently change language:

| probe | js + python on Windows |
|---|---|
| `echo $HOME` | exit 0, output **`$HOME`** (literal — cmd has no `$VAR`) |
| `[[ -d . ]] && echo bashism-ok` | exit 1, `'[[' is not recognized…` |
| `echo %USERPROFILE%` | exit 0, `C:\Users\muthuishere` |

**So on Windows the same `bash` call gives a hard "sh not found" error in go/java/csharp/
elixir/clojure and a quiet, wrong-language success in js/python.** That is a §0 conformance
break, not a platform limitation — and the quiet half is the worse one, because a POSIX
command that "succeeds" while doing nothing is invisible in a transcript.

**PowerShell is not a safe default.** It is present here, but execution policy and AppLocker
routinely block it on managed machines, and `pwsh` is absent. `%COMSPEC%` is the one
interpreter Windows guarantees. Hence the ordering in ADR 0034 D1: COMSPEC first, then
`pwsh`/`powershell`, then a POSIX `bash` if one resolves (Git for Windows / WSL).

## 3. S3 — a base directory, and what defeats a naive containment check (#101)

`spikes/builtin-host-boundary/s3-basedir/` · `go run .` · cross-compiled for Windows

The candidate under test is the shape that would land in seven ports:

```
resolve(p)   = p if absolute, else join(baseDir, p)
contained(p) = canonicalize(resolve(p)) == base or under it   # via Rel, not string prefix
canonicalize = resolve symlinks on the deepest EXISTING ancestor, re-attach the tail
```

The "deepest existing ancestor" part is not an optimisation: a file `write` is about to
create has no realpath, and a check that only works on existing files is not a check for
`write`.

### 3.1 POSIX — the candidate holds

| case | contained | note |
|---|---|---|
| `sub/file.txt` | true | |
| `../escape.txt`, `sub/../../escape.txt` | **false** | |
| absolute path outside the base | **false** | |
| `link/secret.txt` (symlink → outside) | **false** | canonicalisation resolved it to the outside path |
| `.` and `""` | true | both mean the base itself |

### 3.2 Windows — five traps, all measured

| case | result | what it means for the implementation |
|---|---|---|
| base arrives as `C:\Users\MUTHUI~1\…` | canonicalised to `C:\Users\muthuishere\…` | **8.3 short names**: both sides must be canonicalised. A string-prefix check fails here even though both paths name the same directory. |
| `C:\USERS\…\TN-BASE\sub\file.txt` | contained=true | case-insensitivity comes free *if* canonicalisation normalises case. Go's `EvalSymlinks` and Node's `realpathSync.native` do; **Python's `os.path.realpath` does not** — it needs `os.path.normcase`. |
| `sub\NUL` | canonicalises to `\\.\NUL`, `Rel` errors ⇒ **not contained** | fail-closed by luck, not by design. |
| `CON` | contained=**true**, and `Set-Content …\CON` **succeeded with no file created** (`WROTE_CON=ok`, `CON_EXISTS_AS_FILE=False`) | **reserved device names pass containment and do not write into the directory.** The implementation must refuse `CON, PRN, AUX, NUL, COM1-9, LPT1-9` (and those names with any extension) on Windows. |
| `sub/file.txt.` | canonicalises to `sub/file.txt` | Windows strips trailing dots/spaces: two model-visible paths, one file. |
| `\\?\C:\…` | `Rel` errors ⇒ not contained | the extended-length prefix must be handled deliberately, not by an error path. |
| **directory junction** (`mklink /J`, **`IS_ADMIN=False`** — no privilege needed) | see below | the escape an agent can mint for itself with the `bash` tool it already has |

The junction result is the sharpest one (`win/junction.ps1`, `win/junction2/`):

| call | through a junction pointing outside the base |
|---|---|
| PowerShell `[IO.Path]::GetFullPath` | **does not resolve it** — purely lexical |
| Node `fs.realpathSync.native` | resolves → `…\j2-out-…\secret.txt` ✅ |
| Python `os.path.realpath` | resolves → `…\j2-out-…\secret.txt` ✅ |
| Go `filepath.EvalSymlinks` | **FAILS**: `The system cannot find the path specified.` and `Lstat` reports no symlink bit |
| Go `GetFinalPathNameByHandle` | resolves → `\\?\C:\Users\muthuishere\…\j2-out-…\secret.txt` ✅ |

**So the Go port cannot canonicalise with `filepath.EvalSymlinks` on Windows.** With that
error the candidate falls back to the lexical path and reports the escape as *contained* —
the check silently inverts. Go must open the path and ask Windows for the final name
(`GetFinalPathNameByHandle`, then strip the `\\?\` prefix); Node and Python are fine with
their native realpath; Java (`Files.toRealPath`) and .NET (`FileInfo.ResolveLinkTarget`,
.NET 6+) are the equivalents and are **unverified on Windows** here.

### 3.3 The honest limit, measured: `bash` leaves the base whatever the file tools do

With `workdir` pinned to the base:

| POSIX | Windows (cmd) |
|---|---|
| `cd .. && ls` → lists the parent | `cd /d .. && dir /b` → lists the parent |
| `env -C / ls` → lists `/` | `type C:\…\outside\secret.txt` → `secret` |
| `cat <outside>/secret.txt` → `secret` | `powershell -c "Get-Content …"` → `secret` |

`BaseDir` + `ConfineToBaseDir` is therefore a **path-resolution guarantee for the file
builtins, not a sandbox** — exactly the distinction ADR 0033 D7 draws. Anything stronger
needs OS confinement of the child, which is that ADR's subject, not this one's.

## 4. Two defects found by the spikes that no issue reported

1. **Go's `grep` emits the wrong path.** It sorts by the walk-root-relative path and then
   prints `file` — the joined path — in the match line. Measured: on macOS
   `GREP_OUTPUT="/tmp/gp/tree/sub/a.txt:1:x"`, on Windows
   `GREP_OUTPUT="tree\\sub\\a.txt:1:x"`. `SPEC.md §4A` requires the path **relative to the
   walk root**, `/`-separated on every platform, and *"SORT ON THE SAME STRING you emit"* —
   which this is the canonical violation of. The js port does it correctly
   (`toPosixPath(path.relative(root, file))`, `js/src/builtin.ts:344`), so this is Go-only
   drift. `glob` in the same run is correct (`GLOB_OUTPUT="sub/a.txt"`).
2. **Windows `taskkill /T /F` and a Job Object both work; `Process.Kill` does not**
   (`win/orphan/`, controls included):

   | mode | verdict |
   |---|---|
   | `control` (no kill) | ORPHAN_SURVIVED — the probe can write the marker, so the arms below mean something |
   | `naive` (`Process.Kill`, what every port does) | **ORPHAN_SURVIVED** |
   | `taskkill /T /F /PID` | killed whole job (4 processes terminated) |
   | Job Object + `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` | killed whole job |

   There is no signalable process group on Windows: `CREATE_NEW_PROCESS_GROUP` only makes
   Ctrl-Break deliverable, and a service-hosted process has no console to break. `taskkill`
   is the portable answer; a Job Object is the one a child cannot escape.

## 5. What this spike does NOT establish

- **Java, C#, Elixir and Clojure on Windows.** Those runtimes are not installed on the
  Windows box. Their fixes are written against documented platform APIs
  (`ProcessHandle.descendants`, `Process.Kill(true)`, `taskkill`) and must be marked
  unverified-on-Windows until a box has them.
- **Linux.** Nothing here ran on Linux. The POSIX arm is macOS only; the `dash` result
  above is the one Linux-shaped data point, and it came from `dash` on macOS.
- **Whether `ConfineToBaseDir` should be the default.** It is not, and this spike does not
  argue it should be: default-off keeps every existing host byte-identical (ADR 0034 D3).

---

## 6. After the fix — the shipped ports, re-run on the same machines

The sections above measured the defects. This one measures the fix, with the same probes, on the
same native Windows box, against **the shipped port code** rather than a reimplementation.

| probe | go (`win/goreal`) | js (`win/jsreal`) | python (`win/pyreal`) |
|---|---|---|---|
| interpreter detected | `cmd.exe /d /s /c` | `cmd.exe /d /s /c` | `cmd.exe /d /s /c` |
| `bash` runs | `hello-from-bash-builtin` | same | same |
| relative write | `RELATIVE_LANDED_IN=baseDir` | same | same |
| `..\..\escape.txt` with confinement | refused | refused | refused |
| `CON` with confinement | refused | refused | refused |
| timeout | `ORPHAN=none` | `ORPHAN=none`, `KILLEDTREE=true` | `ORPHAN=none`, `KILLEDTREE=true` |
| `glob` | `sub/a.txt` | `sub/a.txt` | `sub/a.txt` |
| `grep` | `sub/a.txt:1:x` (was `tree\sub\a.txt:1:x`) | `sub/a.txt:1:x` | `sub/a.txt:1:x` |

**Two defects were found by running there, both invisible to every POSIX suite:**

1. **js called `taskkill` without `/PID`.** `taskkill /T /F <pid>` is not a valid command line —
   `ERROR: Invalid argument/option - '7668'` — so every kill failed, silently, and the job
   survived. The line never executes on POSIX, so 308 green js tests said nothing about it.
2. **A "graceful" first step is actively harmful on Windows.** `taskkill /T` without `/F` refuses
   every console process in the tree (*"can only be terminated forcefully"*) while still being
   able to take the **parent** down — which reparents the grandchild and leaves it running. All
   seven ports now go straight to a forceful stop there; the TERM → 2000 ms → KILL sequence is a
   POSIX effect, and `SPEC.md §4A` says so.

**java, csharp, elixir and clojure are still unverified on Windows** — those four runtimes are not
installed on the box, which is stated here, in ADR 0034, in the change's tasks, and in
`CHANGELOG.md`, rather than being left for a reader to notice.
