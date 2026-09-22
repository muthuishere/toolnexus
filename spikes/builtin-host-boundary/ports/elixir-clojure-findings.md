# Port feasibility — elixir and clojure, the two hardest ports

**Scope:** ADR 0034's four obligations (D1 shell, D2 base directory, D3 confinement,
D4 kill-the-job), measured in `elixir/` and `clojure/` only. Every claim below is a
line of probe output, and every assertion has a **control arm** — the same call with
the fix removed. Two arms in this spike passed for the wrong reason and are called out
by name (§0.1); that is what the controls are for.

**Host, once:** macOS 26.4, arm64. Elixir 1.20.2 / Erlang OTP 29 · Clojure CLI 1.12.5.1645
on the JVM · cljgo 0.9.0 (Go 1.26.3) · koine 0.11.0 from Clojars. Measured 2026-09-22.

**Run it:**

```
spikes/builtin-host-boundary/ports/elixir/run.sh      # elixir, every obligation
spikes/builtin-host-boundary/ports/clojure/run.sh     # clojure, BOTH hosts, every obligation
spikes/builtin-host-boundary/ports/elixir/run-setm-shells.sh   # the set -m portability arm
```

No network, no LLM key, no new third-party dependency in either port, and **no koine
release is required** for any of the four. The clojure probe is one `.cljc` with no
reader conditionals and no `java.*`, loaded by both hosts, exactly like the port.

---

## 0. Verdict table

| obligation | elixir | clojure (JVM + cljgo) |
|---|---|---|
| **O1 shell** — argv prefix used verbatim + detection | **FEASIBLE-WITH-CAVEAT** — `Port.open` needs an ABSOLUTE path; `:os.find_executable/1` supplies it | **FEASIBLE** — koine `proc/sh` resolves bare names on both hosts; a missing program throws, and that throw is the candidate list |
| **O2 base directory** | **FEASIBLE** — `Path.expand/2` is exactly `resolve()`; empty base is byte-identical | **FEASIBLE-WITH-CAVEAT** — koine.fs has **no** join/absolutise; ~25 lines of pure string work, plus one subprocess for the process cwd |
| **O3 confinement** | **FEASIBLE-WITH-CAVEAT** — OTP has no `realpath`; the chain must be walked by hand with `:file.read_link_all`, and **case normalisation is not achievable** | **FEASIBLE-WITH-CAVEAT** — `koine.fs/real-path` does the whole job on both hosts, **but the two hosts disagree on case** (a real §0 break, §3.2) |
| **O4 kill the job** | **FEASIBLE-WITH-CAVEAT** — the ps-walk works; it does **not** run at all on cancellation (§4.2), and `set -m` is not a portable substitute (§4.4) | **FEASIBLE-WITH-CAVEAT** — `set -m` works through `proc/sh` on **both** hosts, but only under bash-family shells and only in the `2>/dev/null` spelling (§4.3–4.4) |

### 0.1 Two results that would have been wrong to believe

- **zsh's `set -m` arm reported `killed_whole_job`, and it means nothing.** zsh rejects
  `set -m` outright (`zsh:set:1: can't change option: -m`, exit 1), so the command never
  ran and the marker could not appear. A "kill worked" verdict from a probe that never
  started the work is exactly the failure mode this spike's controls exist to catch. Read
  the zsh row of §4.4 as **the wrapper does not run under zsh**, not as a pass.
- **The first `set -m` wrapper was a bash syntax error in every case** (`{ CMD\n; } &` —
  the `;` after the newline is an empty command). Every arm "diverged", which looked like
  a finding and was a broken probe. The working spelling is `{ CMD\n} &`, and the fact
  that the wrapper is sensitive to the command's own text is itself the finding
  (`p5_setm_fidelity.exs`, first paragraph of its header comment).

---

## 1. O1 — SHELL

### 1.1 elixir — FEASIBLE-WITH-CAVEAT

`elixir p1_shell.exs`:

```
CTRL_ABS_BIN_SH={:ok, 0, "hello"}
BARE_sh={:raise, ErlangError, "Erlang error: :enoent"}
BARE_bash={:raise, ErlangError, "Erlang error: :enoent"}
BARE_nosuchprog={:raise, ErlangError, "Erlang error: :enoent"}
FIND_sh="/bin/sh"
FIND_bash="/opt/homebrew/bin/bash"
FIND_zsh="/bin/zsh"
FIND_pwsh=false
FIND_powershell=false
FIND_cmd=false
FIND_nosuchprog-xyz=false
FIXED_bash={:ok, 0, "hello"}
PREFIX_sh_-c={:ok, 0, "prefix-ok"}
PREFIX_bash_-lc={:ok, 0, "prefix-ok"}
PREFIX_env_-i_sh_-c={:ok, 0, "prefix-ok"}
OS_TYPE={:unix, :darwin}
COMSPEC=nil
```

**The caveat, exactly.** `Port.open({:spawn_executable, path}, …)` does **no PATH search**.
A bare `"sh"` raises `ErlangError :enoent` — and `"bash"` and `"nosuchprog-xyz"` raise the
**identical** error. The runtime cannot tell "this interpreter is not installed" from
"you passed a relative name", so a host-set `["bash","-lc"]` prefix handed straight to
`Port.open` produces a misleading error for a perfectly present bash. That is why the
hardcoded `"/bin/sh"` is in `elixir/lib/toolnexus/builtin.ex:230` in the first place.

**The awkward API is `Port.open/2`'s `{:spawn_executable, _}` requiring an absolute path.**
The fix is one call: resolve `hd(shell)` through `:os.find_executable/1` (which returns a
charlist or `false`) **at toolkit construction**, keep the flags verbatim, and pass
`flags ++ [command]` as `args:`. `FIXED_bash` shows that round-tripping through
`find_executable` works, and the three `PREFIX_*` lines show a multi-flag prefix
(`env -i sh -c`) runs verbatim. `find_executable` returning `false` for each candidate is
precisely the material for D1's "construction fails naming the candidates tried".

Not `{:spawn, "sh -c …"}`: that form takes a *command line* the BEAM word-splits, so it
cannot carry an argv prefix as data.

**Unmeasured:** `%COMSPEC%`, `pwsh`, `powershell` and Windows ordering — `COMSPEC=nil`,
`OS_TYPE={:unix,:darwin}`, and no Windows box has elixir installed (SPIKE §5).

### 1.2 clojure — FEASIBLE

`clojure -M -m probe shell` and `cljgo run run_cljgo.cljc shell`:

```
HOST=:jvm
BARE_sh={:ok {:out "hello\n", :err "", :exit 0, :timed-out? false}}
BARE_bash={:ok {:out "hello\n", :err "", :exit 0, :timed-out? false}}
BARE_pwsh={:threw "Cannot run program \"pwsh\": Exec failed, error: 2 (No such file or directory) "}
BARE_nosuchprog-xyz={:threw "Cannot run program \"nosuchprog-xyz\": Exec failed, error: 2 (No such file or directory) "}
PREFIX_sh_-c={:ok …}  PREFIX_bash_-lc={:ok …}  PREFIX_env_-i_sh_-c={:ok …}

HOST=:cljgo
BARE_sh={:ok {:out "hello\n", :err "", :exit 0, :timed-out? false}}
BARE_bash={:ok {:out "hello\n", :err "", :exit 0, :timed-out? false}}
BARE_pwsh={:threw "cljg.process: spawn \"pwsh\": exec: \"pwsh\": executable file not found in $PATH"}
BARE_nosuchprog-xyz={:threw "cljg.process: spawn \"nosuchprog-xyz\": exec: \"nosuchprog-xyz\": executable file not found in $PATH"}
PREFIX_sh_-c={:ok …}  PREFIX_bash_-lc={:ok …}  PREFIX_env_-i_sh_-c={:ok …}
```

`proc/sh` takes a vector and **does** search `PATH` on both hosts (JVM `ProcessBuilder`,
Go `exec.Command`), so a host-set argv prefix is used verbatim with no resolution step at
all — this is the easier of the two ports on O1.

**"Construction fails naming the candidates" is built from the throw, not from a resolver.**
There is no `which` in koine and no capability for one (`koine.host/capabilities` lists no
`:process/which`). The portable detection is: run each candidate once with a harmless
command at construction, catch, and collect the names that threw. The messages differ per
host — the JVM says `Cannot run program "pwsh"`, cljgo says `executable file not found in
$PATH` — so the **error text must not be asserted or surfaced**; only the candidate list is.
Cost: one process per candidate, once, at construction. That is D1's stated price
("discovering it on turn 14 of a paid run is the expensive place to learn it").

**Unmeasured:** only the `:timeout-ms` code path was exercised on cljgo. koine's `sh` has a
*second*, structurally different cljgo branch when `:timeout-ms` is absent (`cljg.io/exec`
rather than `cljg.process/spawn`, koine/process.cljc); whether it throws the same way on a
missing binary is **not measured here**. Detection should pass a timeout, which keeps it on
the branch that was measured.

---

## 2. O2 — BASE DIRECTORY

### 2.1 elixir — FEASIBLE

`elixir p2_basedir.exs`:

```
WITHBASE "sub/file.txt" -> "/var/folders/…/tn-p2-4034/sub/file.txt"
WITHBASE "../escape.txt" -> "/var/folders/…/T/escape.txt"
WITHBASE "." -> "/var/folders/…/tn-p2-4034"
WITHBASE "" -> "/var/folders/…/tn-p2-4034"
ABS /etc/hosts -> "/etc/hosts"
EMPTY_BASE_IDENTICAL=true
PATCH_REWRITE_OK=true
LANDED_IN_BASE=true
LANDED_IN_CWD=false
```

`Path.expand/2` **is** the spec's `resolve()`: relative-to-base, absolute untouched, `.`
and `""` meaning the base. `Path.expand/1` is the empty-base case, and
`EMPTY_BASE_IDENTICAL=true` is the control arm — six inputs, `resolve(p,"")` equals
`Path.expand(p)` in all six, so an absent `base_dir` is byte-identical to today.
`apply_patch`'s `*** Add File:` line rewrites with one regex (`PATCH_REWRITE_OK=true`), and
`bash`'s `workdir` already reaches the port through `Port.open`'s `cd:` option, so its
default just moves from `File.cwd!()` to the base.

**One trap worth pinning in a test.** `Path.expand("~/x", base)` returns
`/Users/muthuishere/x` — the base is **ignored** for a tilde path (`p2`, `WITHBASE "~/x"`).
A model-supplied `~/…` silently leaves the base. With `confine_to_base_dir` on it is
refused (`TILDE_contained=false` in `p3`); with it off, it escapes. Not a blocker, but it
is a behaviour the spec does not currently mention.

### 2.2 clojure — FEASIBLE-WITH-CAVEAT

`clojure -M -m probe basedir <base>` / `cljgo run run_cljgo.cljc basedir <base>`, identical
on both hosts:

```
EMPTY_BASE_IDENTICAL=true
LANDED_IN_BASE=true
LANDED_IN_CWD=false
PATCH_REWRITE_OK=true
KOINE_FS_HAS_JOIN=false
KOINE_FS_HAS_ABSOLUTE=false
KOINE_FS_HAS_REAL_PATH=true
HOST_SUPPORTS_real_path=true
```

**The caveat is that koine.fs offers nothing for paths.** It has `exists?`, `directory?`,
`list-tree`, `find-files`, `mkdirs!`, `delete!`, `delete-tree!`, `temp-dir!`, `real-path`,
`read-file`/`write-file`/`read-bytes`/`write-bytes` — **no join, no absolutise, no
separator, no cwd**. Measured directly: `KOINE_FS_HAS_JOIN=false`,
`KOINE_FS_HAS_ABSOLUTE=false`.

The pure-string alternative works and is ~25 lines (`path-join` + `clean-segments` in
`probe.cljc`): split on `/`, drop `.` and empty segments, pop on `..`, re-join, re-prefix
`/` when the first part was absolute. Both hosts produce identical output for all seven
inputs. The port already assumes `/` everywhere (`builtin.cljc` splits glob patterns on
`#"/"` and joins with `"/"`), so this adds no new assumption — **on POSIX**.

**The one thing that is not pure string work is the process cwd.** There is no portable
call for it, so `""`-means-cwd costs one `sh -c pwd` subprocess. That is the shape the
probe measures (`cwd`), and it should be read **once at construction**, not per call —
otherwise every relative path in every builtin pays for a fork.

**A koine change would remove the caveat**, and it is small: `koine.fs/join`
(pure, no host branch needed) and `koine.fs/cwd` (`System/getProperty "user.dir"` /
`cljg.io` equivalent — one reader conditional inside koine). Neither is a blocker: the
wrapper above avoids both, so **no koine release is required**.

**Unmeasured:** Windows separators and drive letters in `path-join` — neither host was run
on Windows, and `\` / `C:` are not handled by the pure-string version at all.

---

## 3. O3 — CONFINEMENT

### 3.1 elixir — FEASIBLE-WITH-CAVEAT

`elixir p3_confine.exs`:

```
BASE_RAW=/var/folders/…/tn-p3-2052
PATH_EXPAND=/var/folders/…/tn-p3-2052
CANON=/private/var/folders/…/tn-p3-2052
PATH_EXPAND_IS_LEXICAL_ONLY=true
READ_LINK_ALL_one_level={:ok, ~c"/var/folders/…/tn-p3-out-2116"}
CONFINE "sub/file.txt"                     contained=true expected=true OK
CONFINE "sub/deep/not/created/yet.txt"     contained=true expected=true OK
CONFINE "../escape.txt"                    contained=false expected=false OK
CONFINE "sub/../../escape.txt"             contained=false expected=false OK
CONFINE "/var/…/tn-p3-out-2116/secret.txt" contained=false expected=false OK
CONFINE "link/secret.txt"                  contained=false expected=false OK
CONFINE "link/newfile.txt"                 contained=false expected=false OK
CONFINE "."                                contained=true expected=true OK
CONFINE ""                                 contained=true expected=true OK
CONTROL_naive_symlink_says_contained=true
CONTROL_naive_relescape_says_contained=false
CASE_upper_path_exists=true
CASE_upper_canon=/private/var/FOLDERS/CB/…/TN-P3-2052/sub
CASE_contained_via_upper=false
```

The control arm is load-bearing: a naive `Path.expand` + string-prefix check says the
symlink escape **is contained** (`CONTROL_naive_symlink_says_contained=true`) while
correctly rejecting `../escape.txt`. So the canonicaliser is doing real work, and the
`../` cases alone would not have proved it.

**The awkward API: OTP has no `realpath(3).`** `Path.expand/1,2` is purely lexical
(`PATH_EXPAND_IS_LEXICAL_ONLY=true` — it leaves `/var` where the real path is
`/private/var`), and `:file.read_link_all/1` resolves **exactly one** link, not the chain.
The whole canonicaliser has to be written by hand: walk the path segment by segment,
`read_link_all` each prefix, restart on a hit, cap the depth (the probe's `Canon` module,
~30 lines). The deepest-existing-ancestor rule falls out of the same walk, and
`link/newfile.txt` — a non-existent file *through* a symlink, the nastiest `write` case —
comes out `false` correctly.

**`Path.safe_relative/2` looks like a ready-made answer and is not** (`p3b_safe_relative.exs`):

```
existing inside                          {:ok, "sub/file.txt"}
NON-EXISTENT inside (the write case)     {:ok, "sub/brand/new.txt"}
symlink OUT, existing target             :error
symlink OUT, non-existent target         :error
symlink INSIDE the base                  :error
relative escape                          :error
absolute inside the base                 :error
absolute outside                         :error
tilde                                    {:ok, "~/x"}
```

It **refuses a symlink whose target is inside the base** and **refuses every absolute
path, including one inside the base** — both of which D3 requires to be allowed. It is
usable as a cheap fail-closed pre-filter for the relative case, never as the check.

**NOT FEASIBLE, named: case normalisation.** `CASE_contained_via_upper=false` on a
case-insensitive macOS filesystem where `CASE_upper_path_exists=true` — the same directory,
spelled in upper case, is refused. `read_link_all` returns the link target verbatim and
never normalises case, and OTP has no `normcase`. Elixir can only close this by
lower-casing both sides on platforms known to be case-insensitive, which is a guess, not a
measurement. **On Windows, where D3 requires case-insensitive comparison, this is the
elixir port's real gap and it is unverified** (no elixir on the Windows box).

**Unmeasured, elixir:** Windows 8.3 short names, directory junctions, `\\?\` prefixes, and
reserved device names (`CON`, `NUL`, …). All four are D3 requirements and none can be
measured without elixir on Windows.

### 3.2 clojure — FEASIBLE-WITH-CAVEAT, and one host divergence

`clojure -M -m probe confine <base> <out>` (JVM) vs `cljgo run run_cljgo.cljc confine …`:

```
HOST=:jvm                                   HOST=:cljgo
REAL_PATH_base={:ok "/private/var/…/base"}  REAL_PATH_base={:ok "/private/var/…/base"}
REAL_PATH_missing={:threw "koine.fs/real-path: no such path: …"}   (both hosts)
LEXICAL_DIFFERS_FROM_CANON=true             LEXICAL_DIFFERS_FROM_CANON=true
CONFINE "sub/file.txt"                  contained=true  expected=true  OK   (both)
CONFINE "sub/deep/not/created/yet.txt"  contained=true  expected=true  OK   (both)
CONFINE "../escape.txt"                 contained=false expected=false OK   (both)
CONFINE "sub/../../escape.txt"          contained=false expected=false OK   (both)
CONFINE "<out>/secret.txt"              contained=false expected=false OK   (both)
CONFINE "link/secret.txt"               contained=false expected=false OK   (both)
CONFINE "link/newfile.txt"              contained=false expected=false OK   (both)
CONFINE "." / ""                        contained=true  expected=true  OK   (both)
CONTROL_naive_symlink_says_contained=true   CONTROL_naive_symlink_says_contained=true
```

**Answering the brief's hard question directly: yes, there is a symlink-resolving call
reachable from a `.cljc` on both hosts, and no subprocess is needed.** It is
**`koine.fs/real-path`** — `HOST_SUPPORTS_real_path=true` on both, `:fs/real-path` is in
`koine.host/capabilities` for `:jvm` and `:cljgo` alike. It throws on a missing path
(`REAL_PATH_missing`), which is exactly what the deepest-existing-ancestor walk needs: try
the longest prefix that `fs/exists?`, `real-path` it, re-attach the tail. No `pwd -P`, no
`realpath(1)`. The `pwd -P` subprocess fallback was measured anyway
(`SUBPROC_pwd_-P=/private/var/…/base`, agreeing with `real-path`) and is **not required**.

**The divergence, and it is a real §0 break:**

| | JVM | cljgo |
|---|---|---|
| `CASE_upper_canon` | `/private/var/folders/…/base/sub` | `/private/var/FOLDERS/CB/…/BASE/sub` |
| `CASE_contained_via_upper` | **true** | **false** |

Same koine function, same filesystem, two answers. The JVM's `.getCanonicalPath` normalises
case against a case-insensitive filesystem; Go's `filepath.EvalSymlinks` behind
`cljg.io/real-path` does not. So **one clojure port gives two different confinement verdicts
for the same path depending on which host it is compiled for** — the exact class of drift
this repo exists to prevent, and it is in koine, not in toolnexus.

**If it is to be fixed in koine** it is `koine.fs/real-path`, and the option is a
documented statement about case (either "normalises case on case-insensitive filesystems"
with the cljgo branch brought up to the JVM's behaviour, or the reverse — but one of them).
**A wrapper avoids it** for confinement's purposes: compare `real-path` outputs after a
`str/lower-case` on both sides when the host is known case-insensitive. That is the same
guess elixir would have to make, so it is a caveat in both ports, not a clojure-only one —
but only clojure can *observe* it, because only clojure has two hosts.

**Unmeasured, clojure:** everything Windows (junctions, 8.3 names, `\\?\`, `CON`/`NUL`) on
both hosts.

---

## 4. O4 — KILL THE JOB

### 4.1 elixir, the ps-walk — verified independently, with the control

`./run.sh`, O4 section:

```
control    ORPHAN_SURVIVED   NOKILL=1
naive      ORPHAN_SURVIVED
tree       killed_whole_job  DESCENDANTS=2
taskkill   killed_whole_job  AFTER_TASK_SHUTDOWN_child_alive=true DESCENDANTS_AFTER_SHUTDOWN=2
portclose  ORPHAN_SURVIVED   AFTER_PORT_CLOSE_direct_child_alive=true
ownerdie   ORPHAN_SURVIVED   AFTER_OWNER_KILL_direct_child_alive=true
setm       killed_whole_job  PGID=95071
portinfo   ORPHAN_SURVIVED   PORT_INFO=[name: ~c"/bin/sh", links: […], id: 16, connected: …, input: 0, output: 0, os_pid: 95147]
                             PORT_OPTS_ACCEPTED={ArgumentError, "errors were found at the given arguments:\n\n  * 2nd argument: invalid option in list\n"}
```

`control ORPHAN_SURVIVED` is the arm that makes the rest mean anything: with no kill the
grandchild writes its marker, so `killed_whole_job` below is a kill, not a probe that never
started. `naive` reproduces the shipped bug (`builtin.ex:257` — `kill -9` the `os_pid`,
then `Port.close`). **SPIKE §1.1's elixir fix is confirmed: `tree` kills the whole job,
`DESCENDANTS=2`.**

### 4.2 elixir (b) — the ps-walk does NOT hold when the Task is killed

This is the brief's question (b), and the answer is no:

```
taskkill   AFTER_TASK_SHUTDOWN_child_alive=true   DESCENDANTS_AFTER_SHUTDOWN=2
portclose  AFTER_PORT_CLOSE_direct_child_alive=true
ownerdie   AFTER_OWNER_KILL_direct_child_alive=true
```

Three separate facts, all measured:

1. **`Port.close/1` does not kill the OS process.** The direct child is alive afterwards.
   The `Port.close` in the shipped timeout path is decoration; only the explicit `kill -9`
   does anything.
2. **`Task.shutdown(task, :brutal_kill)` kills nothing outside the BEAM.** The whole job
   keeps running. The shipped structure is
   `Task.async(fn -> run_shell(…) end) |> Task.await(:infinity)` (`builtin.ex:221`), and
   the `after` clause that does the killing lives *inside* the task — so when the task is
   shut down, **the kill code never runs**.
3. **Killing the Port owner process is the same**: `AFTER_OWNER_KILL_direct_child_alive=true`.
   A BEAM process crash leaks the entire job.

The silver lining is that the ps-walk still *works* from outside after a shutdown
(`DESCENDANTS_AFTER_SHUTDOWN=2`) — precisely because the direct child is still alive, so
nothing has been reparented. That is the opposite of SPIKE §1.1's postwalk trap and for the
same reason: reparenting happens when the parent **dies**, and here it does not.

**So D4's "Cancelling the surrounding call stops the work" scenario is NOT satisfied by the
ps-walk fix alone in elixir.** The port needs the kill to run somewhere that survives the
task dying: trap exits in the port-owning process, or `Process.monitor` the caller, or a
`Task.Supervisor` child that cleans up in `terminate/2`. **That is a structural change to
`run_shell`, not an addition to it, and it should be an explicit task in the change.**

### 4.3 elixir (c) — can it be done WITHOUT shelling out to `ps`?

Measured, not reasoned:

- **`Port.open` has no process-group option.** `PORT_OPTS_ACCEPTED` shows `setpgid: true`
  rejected with `ArgumentError: 2nd argument: invalid option in list`.
- **`:erlang.port_info/1` exposes nothing usable but `os_pid`**: `name, links, id,
  connected, input, output, os_pid`. No descendants, no group, no signal.
- **`Port.close` does not signal the child** (§4.2).
- **`set -m` does work, and needs no `ps`**: `setm → killed_whole_job, PGID=95071`.

So the answer to (c) is **yes, via `set -m`** — and §4.4 is why that is not the
recommendation.

### 4.4 The `set -m` wrapper is NOT portable, and it moves `output`

SPIKE §1.1 endorsed `set -m` as clojure's in-port fix. Two measurements qualify that.

**(i) It emits bash job notifications into `output` — a §0 golden break.**
`elixir p5_setm_fidelity.exs`, plain vs wrapped through the shipped `:stderr_to_stdout` port:

```
stdout                 {"hello\n", 0} -> {"hello\n[1]+  Done                    { echo hello; }\n", 0}  *** DIVERGES ***
exit 7                 {"", 7}        -> {"[1]+  Done(7)                 { exit 7; }\n", 7}             *** DIVERGES ***
no trailing newline    {"abc", 0}     -> {"abc[1]+  Done                    { printf abc; }\n", 0}       *** DIVERGES ***
```

Exit codes survive; `output` does not. `elixir p6_setm_quiet.exs` measured five spellings
against the unwrapped control; **two are byte-identical**:

```
== shell /bin/sh(bash-as-sh) (/bin/sh)
  A plain set -m         byte_identical=false pgid_written=true
  B wait 2>/dev/null     byte_identical=true  pgid_written=true
  C set +m before wait   byte_identical=true  pgid_written=true
  D subshell set -m      byte_identical=false pgid_written=true
  E set -m +notify       byte_identical=false pgid_written=true
```

The same holds through koine on both clojure hosts (`probe fidelity`, identical output on
`:jvm` and `:cljgo`): plain `set -m` `*DIVERGES*` on all five commands, `wait $! 2>/dev/null`
is `SAME` on all five.

**(ii) It does not work under every POSIX interpreter — including Debian's `/bin/sh`.**
`elixir/run-setm-shells.sh`, control arm per shell:

```
sh     control  ORPHAN_SURVIVED
sh     setm     killed_whole_job  SHELL=sh PGID="8561" KILLGROUP_rc=0 ""
dash   control  ORPHAN_SURVIVED
dash   setm     ORPHAN_SURVIVED   SHELL=dash PGID="10221" KILLGROUP_rc=1 "kill: -10221: No such process"
zsh    control  ORPHAN_SURVIVED
zsh    setm     killed_whole_job
```

- **dash fails outright.** `set -m` in a non-interactive dash prints
  `set: can't access tty; job control turned off` and job control stays **off**, so the
  background job is never a group leader and `kill -TERM -PGID` gets `No such process`.
  The orphan survives. **`/bin/sh` is dash on Debian-family Linux — the very platform
  SPIKE §2.1 raised — so the endorsed clojure fix does not work there.**
- **zsh's row is vacuous** — see §0.1. `set -m` is rejected (`can't change option: -m`,
  exit 1), the command never ran, nothing printed a PGID. Not a pass.

Reproduced through koine on both clojure hosts (`./run.sh`, O4 section):

```
jvm    setm-dash   ORPHAN_SURVIVED  OUT="/bin/dash: 1: set: can't access tty; job control turned off\n" … KILLGROUP_exit=1 "kill: -47354: No such process"
cljgo  setm-dash   ORPHAN_SURVIVED  OUT="" … KILLGROUP_exit=1 "kill: -49152: No such process"
```

**Consequence for D1 + D4 together:** the wrapper's correctness depends on the interpreter
the *host* chose. A `set -m`-based fix must therefore (a) use the `wait $! 2>/dev/null`
spelling, (b) verify at construction that the resolved interpreter actually grants a process
group, and (c) fall back to the `ps` walk when it does not. It is not a drop-in for elixir,
whose `ps` walk already works under every interpreter.

### 4.5 clojure — both hosts, all modes

`./run.sh`, O4 section (each row's control is the `control` row of the same host):

```
jvm    control     ORPHAN_SURVIVED
jvm    naive       ORPHAN_SURVIVED
jvm    postwalk    ORPHAN_SURVIVED   REACHABLE_AFTER_KILL=0
jvm    setm        killed_whole_job  OUT="" EXIT=nil TIMED_OUT=true PGID=46092 KILLGROUP_exit=0 ""
jvm    setm-quiet  killed_whole_job  OUT="" EXIT=nil TIMED_OUT=true PGID=46172 KILLGROUP_exit=0 ""
jvm    setm-dash   ORPHAN_SURVIVED   … KILLGROUP_exit=1 "kill: -47354: No such process"
jvm    spawn-pgid  killed_whole_job  SPAWN_FIRST_LINE="PG=47649"
cljgo  control     ORPHAN_SURVIVED
cljgo  naive       ORPHAN_SURVIVED
cljgo  postwalk    ORPHAN_SURVIVED   REACHABLE_AFTER_KILL=0
cljgo  setm        killed_whole_job  OUT="" EXIT=nil TIMED_OUT=true PGID=49036 KILLGROUP_exit=0 ""
cljgo  setm-quiet  killed_whole_job  OUT="" EXIT=nil TIMED_OUT=true PGID=49121 KILLGROUP_exit=0 ""
cljgo  setm-dash   ORPHAN_SURVIVED   … KILLGROUP_exit=1 "kill: -49152: No such process"
cljgo  spawn-pgid  killed_whole_job  SPAWN_FIRST_LINE="PG=50540"
```

**Answering the brief's question (a): yes on both hosts.** The `set -m` wrapper works
through `koine.process/sh` unchanged on the JVM **and on cljgo** — the cljgo host is
**verified, not assumed**; cljgo 0.9.0 is installed and both legs ran. koine kills the
direct child internally and never exposes a pid, and that does not matter, because the pgid
comes back through the pidfile *before* anything is killed.

**SPIKE §1.1's postwalk failure independently reproduced**: `REACHABLE_AFTER_KILL=0` on
both hosts. The descendants are reparented the moment koine's internal `.destroyForcibly` /
`(:kill p)` lands, so a post-hoc `ps` walk from the dead parent's pid finds nothing. A
`ps`-walk fix is therefore genuinely unavailable to clojure — not merely awkward — which is
the asymmetry with elixir.

**A second, perl-free route also works and is worth knowing about**: `spawn-pgid` uses
`koine.process/spawn` instead of `sh` and reads the pgid off the child's **first stdout
line** (`SPAWN_FIRST_LINE="PG=47649"`), so no pidfile and no temp-file lifecycle. It kills
the whole job on both hosts. The cost is that `spawn` has no `:timeout-ms`, so the port
would own the deadline itself, and stdout would have to be re-assembled from `read-line!`
— which loses `sh`'s exact-bytes guarantee and would move `output`. **The pidfile route is
the cheaper one; this is recorded as the alternative, not the recommendation.**

**Does anything require a koine change?** No. The recommended shape needs
`koine.process/sh` with `:timeout-ms` (has it), `koine.fs/read-file` (has it), and one
`sh -c kill` (has it). The better long-term home is still what SPIKE §1.1 named: a
**`:kill-tree?` option on `koine.process/sh`** — the function is `koine.process/sh`, the
option is `:kill-tree?`, and it would make the JVM branch capture `ProcessHandle.descendants()`
before `.destroyForcibly` and the cljgo branch use a `Setpgid` process group. The wrapper
above avoids it entirely, so it is a follow-up, not a blocker.

---

## 5. What this write-up does NOT establish — by name

- **Windows, for either port.** Nothing here ran on Windows, and neither elixir nor a
  Clojure/cljgo toolchain is installed on the Windows box SPIKE §2.2 used. Specifically
  unmeasured: `%COMSPEC%`/`pwsh`/`powershell` detection ordering, `taskkill /T /F` as the
  D4 mechanism, directory junctions, 8.3 short names, `\\?\` prefixes, reserved device
  names, case-insensitive comparison, and whether `path-join`'s `/`-only assumption
  survives at all. **Every Windows row of ADR 0034's D4 table for elixir and clojure
  remains unverified.**
- **cljgo's AOT leg.** Both clojure legs here were run interpreted (`cljgo run`). The
  port's own gate (`clojure/cljgo-gate.sh`) requires AOT and interpreted to *agree*, and
  that comparison was **not** run for these probes.
- **Linux.** The `dash` result came from dash on macOS, not from a Debian box. The
  conclusion ("Debian's `/bin/sh` defeats the `set -m` wrapper") is inferred from dash's
  behaviour, and the inference is about the shell, not the kernel — but it is an inference.
- **koine's no-timeout cljgo path** on a missing binary (§1.2).
- **Whether the elixir case-normalisation gap matters in practice on Linux**, where the
  filesystem is usually case-sensitive and the gap is invisible.

---

## 6. Test shapes — the smallest test that pins each obligation

Real code, in each port's existing suite, beside the tests already there.

### 6.1 elixir — `elixir/test/builtin_test.exs`, inside `describe "bash"` / new describes

```elixir
  describe "shell (ADR 0034 D1)" do
    test "a host-set argv prefix is used verbatim" do
      tk = Builtin.builtin_toolkit(shell: ["bash", "-lc"])
      r = Toolnexus.Toolkit.execute(tk, "bash", %{"command" => "echo $BASH_VERSION"}, nil)
      refute r.is_error
      refute String.trim(r.output) == ""
      assert r.metadata[:shell] == ["bash", "-lc"]
    end

    test "construction fails naming every candidate when none resolves" do
      assert {:error, msg} = Builtin.build_toolkit(shell: ["nosuchsh-a"], detect: ["nosuchsh-a", "nosuchsh-b"])
      assert msg =~ "nosuchsh-a" and msg =~ "nosuchsh-b"
      # ...and disabling bash makes the same construction succeed
      assert {:ok, _} = Builtin.build_toolkit(tools: %{"bash" => false}, detect: ["nosuchsh-a"])
    end
  end

  describe "base_dir (ADR 0034 D2)" do
    @tag :tmp_dir
    test "a relative write lands under base_dir, not the process cwd", %{tmp_dir: tmp} do
      tk = Builtin.builtin_toolkit(base_dir: tmp)
      refute Toolnexus.Toolkit.execute(tk, "write", %{"path" => "sub/x.txt", "content" => "hi"}, nil).is_error
      assert File.read!(Path.join(tmp, "sub/x.txt")) == "hi"
      refute File.exists?(Path.join(File.cwd!(), "sub/x.txt"))
    end

    @tag :tmp_dir
    test "apply_patch resolves the paths INSIDE the patch text", %{tmp_dir: tmp} do
      tk = Builtin.builtin_toolkit(base_dir: tmp)
      patch = "*** Begin Patch\n*** Add File: a/b.txt\n+hi\n*** End Patch\n"
      refute Toolnexus.Toolkit.execute(tk, "apply_patch", %{"patchText" => patch}, nil).is_error
      assert File.exists?(Path.join(tmp, "a/b.txt"))
    end

    @tag :tmp_dir
    test "an EMPTY base_dir is byte-identical to today", %{tmp_dir: tmp} do
      # the control arm: the option's absence must change nothing
      a = Toolnexus.Toolkit.execute(Builtin.builtin_toolkit(), "bash", %{"command" => "pwd"}, nil)
      b = Toolnexus.Toolkit.execute(Builtin.builtin_toolkit(base_dir: ""), "bash", %{"command" => "pwd"}, nil)
      assert a == b
      _ = tmp
    end
  end

  describe "confine_to_base_dir (ADR 0034 D3)" do
    @tag :tmp_dir
    test "a symlink out of the base is refused, and the naive check would not catch it", %{tmp_dir: tmp} do
      out = Path.join(tmp, "out")
      base = Path.join(tmp, "base")
      File.mkdir_p!(out); File.mkdir_p!(base)
      File.write!(Path.join(out, "secret.txt"), "secret")
      File.ln_s!(out, Path.join(base, "link"))

      tk = Builtin.builtin_toolkit(base_dir: base, confine_to_base_dir: true)
      r = Toolnexus.Toolkit.execute(tk, "read", %{"path" => "link/secret.txt"}, nil)
      assert r.is_error
      assert r.output =~ "link/secret.txt"
      # CONTROL: lexically it looks contained, which is why canonicalisation is the check
      assert String.starts_with?(Path.expand("link/secret.txt", base), base <> "/")
    end

    @tag :tmp_dir
    test "a path that does not exist yet is still checked (the write case)", %{tmp_dir: tmp} do
      tk = Builtin.builtin_toolkit(base_dir: tmp, confine_to_base_dir: true)
      assert Toolnexus.Toolkit.execute(tk, "write", %{"path" => "../out.txt", "content" => "x"}, nil).is_error
      refute Toolnexus.Toolkit.execute(tk, "write", %{"path" => "new/deep/ok.txt", "content" => "x"}, nil).is_error
    end
  end

  describe "timeout kills the job (ADR 0034 D4)" do
    @tag :tmp_dir
    test "the grandchild does not outlive the call", %{tmp_dir: tmp} do
      marker = Path.join(tmp, "m")
      r = Toolnexus.Toolkit.execute(Builtin.builtin_toolkit(), "bash",
            %{"command" => "sleep 0.2; sh -c 'sleep 1; touch #{marker}'", "timeout" => 300}, nil)
      assert r.is_error
      assert r.metadata[:timedOut] == true
      assert r.metadata[:killedTree] == true
      Process.sleep(2000)
      refute File.exists?(marker), "the grandchild survived the kill"
    end

    @tag :tmp_dir
    test "CONTROL — with no timeout the grandchild DOES write the marker", %{tmp_dir: tmp} do
      # without this arm the test above passes for a probe that never started the work
      marker = Path.join(tmp, "m2")
      Toolnexus.Toolkit.execute(Builtin.builtin_toolkit(), "bash",
        %{"command" => "sleep 0.2; sh -c 'sleep 1; touch #{marker}'", "timeout" => 5000}, nil)
      Process.sleep(1500)
      assert File.exists?(marker)
    end

    @tag :tmp_dir
    test "cancelling the caller also stops the work (§4.2 — this is the one that fails today)", %{tmp_dir: tmp} do
      marker = Path.join(tmp, "m3")
      task = Task.async(fn ->
        Toolnexus.Toolkit.execute(Builtin.builtin_toolkit(), "bash",
          %{"command" => "sleep 0.2; sh -c 'sleep 1; touch #{marker}'", "timeout" => 60_000}, nil)
      end)
      Process.sleep(300)
      Task.shutdown(task, :brutal_kill)
      Process.sleep(2000)
      refute File.exists?(marker), "the job outlived the cancelled caller"
    end
  end
```

The `async: false` at the top of `Toolnexus.BuiltinTest` already covers these — they fork
processes and read wall-clock time, so they must not run concurrently.

### 6.2 clojure — `clojure/src/toolnexus/builtin_test.cljc`, beside `bash-basics`

Dual-host constraints observed: no `java.*`, no `Thread/sleep` (use `koine.time/sleep!`),
no reader conditionals, everything under the `tn-builtin` temp root the `:once` fixture
already creates and deletes.

```clojure
(deftest shell-option                                   ; ADR 0034 D1
  (testing "a host-set argv prefix is used verbatim"
    (let [tk (builtin/builtin-toolkit {:shell ["bash" "-lc"]})
          r  (tool/execute tk "bash" {:command "echo $BASH_VERSION"} nil)]
      (is (false? (:isError r)))
      (is (not= "" (str/trim (:output r))))
      (is (= ["bash" "-lc"] (get-in r [:metadata :shell])))))
  (testing "construction fails naming every candidate when none resolves"
    (let [e (builtin/build-toolkit {:shell ["nosuchsh-a"] :detect ["nosuchsh-a" "nosuchsh-b"]})]
      (is (str/includes? (:error e) "nosuchsh-a"))
      (is (str/includes? (:error e) "nosuchsh-b")))
    (is (nil? (:error (builtin/build-toolkit {:tools {"bash" false} :detect ["nosuchsh-a"]}))))))

(deftest base-dir-option                                ; ADR 0034 D2
  (let [b (p "bd")]
    (fs/mkdirs! b)
    (testing "a relative write lands under base-dir, not the process cwd"
      (let [tk (builtin/builtin-toolkit {:base-dir b})]
        (is (false? (:isError (tool/execute tk "write" {:path "sub/x.txt" :content "hi"} nil))))
        (is (= "hi" (fs/read-file (str b "/sub/x.txt"))))))
    (testing "apply_patch resolves the paths INSIDE the patch text"
      (let [tk (builtin/builtin-toolkit {:base-dir b})]
        (is (false? (:isError (tool/execute tk "apply_patch"
                                {:patchText "*** Begin Patch\n*** Add File: a/c.txt\n+hi\n*** End Patch\n"} nil))))
        (is (fs/exists? (str b "/a/c.txt")))))
    (testing "CONTROL — an empty base-dir is byte-identical to today"
      (is (= (tool/execute (builtin/builtin-toolkit) "bash" {:command "pwd"} nil)
             (tool/execute (builtin/builtin-toolkit {:base-dir ""}) "bash" {:command "pwd"} nil))))))

(deftest confine-to-base-dir                            ; ADR 0034 D3
  (let [b (p "cf/base") o (p "cf/out")]
    (fs/mkdirs! b) (fs/mkdirs! o)
    (fs/write-file (str o "/secret.txt") "secret")
    ;; no koine symlink fn — the one place this suite must shell out, and it is
    ;; the SETUP, never the assertion.
    (proc/sh ["ln" "-s" o (str b "/link")] {:timeout-ms 4000})
    (let [tk (builtin/builtin-toolkit {:base-dir b :confine-to-base-dir true})]
      (testing "a symlink out of the base is refused"
        (let [r (tool/execute tk "read" {:path "link/secret.txt"} nil)]
          (is (true? (:isError r)))
          (is (str/includes? (:output r) "link/secret.txt"))))
      (testing "CONTROL — lexically it looks contained, which is why canonicalisation is the check"
        (is (str/starts-with? (str b "/link/secret.txt") (str b "/"))))
      (testing "a path that does not exist yet is still checked (the write case)"
        (is (true?  (:isError (tool/execute tk "write" {:path "../out.txt" :content "x"} nil))))
        (is (false? (:isError (tool/execute tk "write" {:path "new/deep/ok.txt" :content "x"} nil))))))))

(deftest timeout-kills-the-job                          ; ADR 0034 D4
  (let [m (p "kill-marker") c (str "sleep 0.2; sh -c 'sleep 1; touch " m "'")]
    (testing "CONTROL — with a generous timeout the grandchild DOES write the marker"
      ;; without this arm the assertion below passes for a probe that never ran
      (tool/execute (builtin/builtin-toolkit) "bash" {:command c :timeout 5000} nil)
      (ktime/sleep! 1500)
      (is (fs/exists? m)))
    (fs/delete! m)
    (testing "a timed-out command's grandchild does not outlive the call"
      (let [r (tool/execute (builtin/builtin-toolkit) "bash" {:command c :timeout 300} nil)]
        (is (true? (:isError r)))
        (is (true? (get-in r [:metadata :timedOut])))
        (is (true? (get-in r [:metadata :killedTree])))
        (ktime/sleep! 2000)
        (is (false? (fs/exists? m)) "the grandchild survived the kill")))))

(deftest setm-wrapper-does-not-move-output              ; §4.4 — the §0 guard
  ;; The wrapper is what makes D4 possible in this port, and plain `set -m`
  ;; appends bash job notifications ("[1]+  Done …") to stderr, which §4A folds
  ;; into `output`. This pins the quiet spelling on BOTH hosts.
  (doseq [[cmd out err? exit] [["printf hi" "hi" false 0]
                               ["printf oops 1>&2; exit 7" "oops" true 7]]]
    (let [r (tool/execute (builtin/builtin-toolkit) "bash" {:command cmd :timeout 5000} nil)]
      (is (false? (str/includes? (:output r) "Done")) "a job notification leaked into output")
      (is (str/starts-with? (:output r) out))
      (is (= (not= 0 exit) (:isError r)))
      err?)))
```

`ktime` (`koine.time`) and `proc` (`koine.process`) need adding to the `ns` `:require` in
`builtin_test.cljc`; `fs`, `str`, `builtin` and `tool` are already there. The suite's count
gate (`clojure/src/toolnexus/test_main.cljc`) is a floor, so added tests do not need it
moved — but the same tests must be added for **both** legs of `cljgo-gate.sh`, and the AOT
leg is the one this spike did not exercise (§5).
