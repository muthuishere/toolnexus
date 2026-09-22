# Port feasibility — js and python against ADR 0034's four obligations

**Scope:** `js/src/builtin.ts` and `python/src/toolnexus/builtin.py` only. This measures
whether each port can implement D1–D4 **in its own idiom with no new third-party
dependency**, and what breaks when it tries. No implementation was written; nothing under
`js/src`, `python/src` or any other port was edited.

> **Timing note.** A parallel session landed the js implementation as `7c5a71d`
> *(feat(js builtins): the host boundary …)* while these measurements were being taken.
> Every line reference and every "today" claim below is against the **pre-change**
> `js/src/builtin.ts` (`spawn(command, {shell:true})` at :163). The probes themselves are
> standalone — they exercise node/python APIs, not the port — so the measurements stand;
> the landed js code independently uses `realpathSync.native` and `detached`, which is what
> O3(c) and O4 say it must.

**Host:** macOS 26.4 arm64 (Darwin 25.4.0), node **v24.18.0**, python **3.14.7**, APFS
**case-insensitive** (`FS_CASE_INSENSITIVE=true` in both O3 probes). **Nothing here ran on
Windows or Linux** — see *Unmeasured* at the bottom.

**Run everything:**

```
bash spikes/builtin-host-boundary/ports/js/run.sh
bash spikes/builtin-host-boundary/ports/python/run.sh
```

Each probe is also runnable alone (`node p1-shell.mjs`, `python3 p1_shell.py`, …). Node
stdlib and python stdlib only: no `npm install`, no `pip install`, no network, no LLM key.

**Every assertion has a control arm**, because an assertion whose "fixed" arm passes
because the probe was broken measures nothing — which already happened twice in this work:

- `s1-orphan`'s first two runs reported a clean kill because the command had no grandchild
  to orphan (SPIKE §1). The command shape `sleep 0.2; sh -c 'sleep 1; touch MARKER'` is
  carried over here unchanged for that reason.
- **In this pass:** `p1`'s first interpreter probe ran `ps -o comm= -p $$`, the shell
  `exec`'d `ps`, and both arms reported `"ps"` — a measurement of nothing. Fixed with a
  `:;` prefix that forces a real fork. And `p3`'s first `NORMALISES_CASE` line compared
  `realpathSync.native(…)` against a **lexical** `path.join(base,…)`; on macOS `base` is
  `/var/…` and its realpath is `/private/var/…`, so it printed `false` for the wrong reason
  and would have "confirmed" the ADR's claim backwards. The probe now compares canonical to
  canonical and keeps both lines in the output.

---

## Verdicts at a glance

| obligation | js | python |
|---|---|---|
| **O1 shell** — argv prefix used verbatim + detection | **FEASIBLE** | **FEASIBLE** |
| **O2 basedir** — incl. apply_patch text, empty base byte-identical | **FEASIBLE** | **FEASIBLE-WITH-CAVEAT** (`os.path.join` discards an absolute right-hand side) |
| **O3 confinement** — canonicalise both sides, refuse escapes | **FEASIBLE** | **FEASIBLE-WITH-CAVEAT** (no stdlib case normalisation on a case-insensitive POSIX volume — a measured js/python divergence) |
| **O4 kill the job** — timeout **and** cancel | **FEASIBLE** | **FEASIBLE-WITH-CAVEAT** (the blocking `subprocess.run` inside `asyncio.to_thread` cannot be interrupted; the fix is a handle published *out* of the worker thread, and the thread is not reclaimed synchronously) |

Nothing measured is **NOT-FEASIBLE-IN-IDIOM**. The cancellation dimension — the one flagged
as most likely impossible — is feasible in both, but in python only with a specific shape
that the current `asyncio.to_thread(do)` call does not have.

---

## O1 — SHELL

### js — **FEASIBLE**

`js/src/builtin.ts:163` today: `spawn(command, { shell: true, cwd: workdir })`.

Command: `node spikes/builtin-host-boundary/ports/js/p1-shell.mjs`

```
== O1 js: shell:true vs explicit argv ==
quotes     today_vs_argv=SAME  control_vs_today=DIFFER(ok)
pipe       today_vs_argv=SAME  control_vs_today=DIFFER(ok)
redirect   today_vs_argv=SAME  control_vs_today=DIFFER(ok)
andand     today_vs_argv=SAME  control_vs_today=DIFFER(ok)
multiline  today_vs_argv=SAME  control_vs_today=DIFFER(ok)
nonzero    today_vs_argv=SAME  control_vs_today=DIFFER(ok)
varexp     today_vs_argv=SAME  control_vs_today=DIFFER(ok)
SUMMARY shapes=7 same=7 control_false_positives=0
SHELL_TRUE_INTERPRETER="/bin/sh"
ARGV_INTERPRETER="sh"
```

All seven shapes — quotes, a pipe, a redirection to `/dev/stderr`, `&&`, a three-line
multi-line command with an `if`, a non-zero exit, and variable expansion — produce a
**byte-identical `(combined output, exit code)`** under `spawn("sh", ["-c", command])` as
under `shell:true`. The `ARM_CONTROL` column (`spawn("echo",[command])`) differs on every
row, so the comparator can in fact tell things apart: `control_false_positives=0`.

**One detail to carry into the implementation, not a caveat:** node's `shell:true` spawns
literally **`/bin/sh`**, while `spawn("sh", …)` resolves `sh` through `PATH`
(`SHELL_TRUE_INTERPRETER="/bin/sh"` vs `ARGV_INTERPRETER="sh"`). The two agreed on every
output here, but the POSIX default prefix should be spelled **`["/bin/sh","-c"]`** if the
intent is "exactly what we shipped"; `["sh","-c"]` is a `PATH` lookup and is a different
(if usually identical) claim.

### python — **FEASIBLE**

`python/src/toolnexus/builtin.py:189` today: `subprocess.run(command, shell=True, …)`.

Command: `python3 spikes/builtin-host-boundary/ports/python/p1_shell.py`

```
== O1 python: shell=True vs explicit argv ==
quotes     today_vs_argv=SAME  control_vs_today=DIFFER(ok)
pipe       today_vs_argv=SAME  control_vs_today=DIFFER(ok)
redirect   today_vs_argv=SAME  control_vs_today=DIFFER(ok)
andand     today_vs_argv=SAME  control_vs_today=DIFFER(ok)
multiline  today_vs_argv=SAME  control_vs_today=DIFFER(ok)
nonzero    today_vs_argv=SAME  control_vs_today=DIFFER(ok)
varexp     today_vs_argv=SAME  control_vs_today=DIFFER(ok)
SUMMARY shapes=7 same=7 control_false_positives=0
SHELL_TRUE_INTERPRETER=b'/bin/sh'
ARGV_INTERPRETER=b'sh'
```

Same result, same `/bin/sh` vs `sh` detail (CPython's `shell=True` builds
`['/bin/sh','-c',command]`).

**The question asked explicitly: does dropping `shell=True` change the timeout path?**
**Measured: no.**

```
== O1 python: the timeout path, shell=True vs argv ==
control-no-timeout       raised=NO_TIMEOUT_RAISED e.output=b'partial\n'         blocked_for=1.23s  ORPHAN_SURVIVED
shellTrue-timeout        raised=TimeoutExpired   e.output=b'partial\n'         blocked_for=0.30s  ORPHAN_SURVIVED
argvList-timeout         raised=TimeoutExpired   e.output=b'partial\n'         blocked_for=0.31s  ORPHAN_SURVIVED
```

Identical on all four axes that the port's error message depends on: the same
`TimeoutExpired` type, the same **partial output** on `e.output` (which
`builtin.py:196` decodes into the `bash: command timed out after …ms` message), the same
~0.3 s unblock time, and the same orphaned grandchild. The control arm (no timeout) runs to
completion and writes the marker, so `ORPHAN_SURVIVED` on the two timeout rows is the
probe working, not the probe failing.

**Detection** (POSIX `sh -c`; Windows `%COMSPEC%` → `pwsh` → `powershell` → `bash`) is a
`process.platform` / `sys.platform` branch plus `fs.existsSync`/`shutil.which` — stdlib in
both, no dependency. **The detection order itself is UNMEASURED here** (no Windows host);
SPIKE §2.2 measured what resolves on the Windows box.

---

## O2 — BASEDIR

The obligation that bites is *"an empty base must stay byte-identical to today's
process-cwd behaviour"*. That is **not** a claim about where bytes land — `resolve("",p)`
and `p` open the same file. It is a claim about the **string the port echoes back**:
`read`'s media branch puts the path into `output` (`js/src/builtin.ts` `${p} (${mimeType}…)`),
and `glob`/`grep` emit paths relative to `root`. A resolver that absolutises on an empty
base moves a §0 golden while landing in the same place.

### js — **FEASIBLE**

Command: `node spikes/builtin-host-boundary/ports/js/p2-basedir.mjs`

```
"a.txt"        join_guard="a.txt"      identical=YES  path.resolve=".../ports/js/a.txt" identical=NO
"sub/a.txt"    join_guard="sub/a.txt"  identical=YES  path.resolve=".../ports/js/sub/a.txt" identical=NO
"./a.txt"      join_guard="./a.txt"    identical=YES  path.resolve=".../ports/js/a.txt" identical=NO
"../a.txt"     join_guard="../a.txt"   identical=YES  path.resolve=".../ports/a.txt" identical=NO
"/var/.../abs.txt"                     identical=YES  path.resolve unchanged            identical=YES
"."            join_guard="."          identical=YES  path.resolve=".../ports/js"      identical=NO
""             join_guard=""           identical=YES  path.resolve=".../ports/js"      identical=NO
CONTROL base_set_is_not_identity=differs(ok)
LANDED_UNDER_BASE=true
LANDED_UNDER_CWD=true
```

`(base, p) => base ? (path.isAbsolute(p) ? p : path.join(base, p)) : p` is **identity on
every input when the base is empty**, including `"."`, `""` and `"./a.txt"` where `path.resolve`
is not (six of seven `identical=NO`). The control proves the function is not trivially
identity: with a base set it differs.

**apply_patch** is the case a host cannot reach, and it turns out to need no text rewrite
at all. Both ports already parse the patch into ops carrying a `path` field
(`js/src/builtin.ts:539` `FILE_MARKER` + `parsePatch`), so the base applies to `op.path` at
apply time:

```
PORT_REGEX_EXTRACTED=[["Add","sub/new.txt"],["Update","sub/landed.txt"],["Delete","sub/gone.txt"]]
PORT_REGEX_IGNORES_BODY_DECOY=true
PATCH_EMPTY_BASE_BYTE_IDENTICAL=true
PATCH_ALL_HEADERS_ABSOLUTE=true
PATCH_ABSOLUTE_UNTOUCHED=true
PATCH_BODY_DECOY_UNTOUCHED=true
```

The probe also measures the text-rewrite alternative and its two controls: an already-absolute
header comes through untouched, and a **body line that looks like a header** (`+*** Add File: decoy.txt`)
is not moved, because the port's own regex is `^`-anchored.

### python — **FEASIBLE-WITH-CAVEAT**

Command: `python3 spikes/builtin-host-boundary/ports/python/p2_basedir.py` — same shape,
same result (`join_guard` identity on all seven inputs; `os.path.abspath` on six of seven
is not; `EMPTY_BASE_OP_PATHS_BYTE_IDENTICAL=True`).

**The caveat is the exact API:** `os.path.join` **discards its left operand when the right
operand is absolute**, which is not true of node's `path.join`:

```
JOIN_WITH_ABSOLUTE_DISCARDS_BASE='/etc/passwd'
RESOLVE_A_WITH_ABSOLUTE='/etc/passwd'
```

Here that silently does the right thing (an absolute path must be unaffected by the base),
so a naive `os.path.join(base, p)` **passes the O2 tests by accident**. It is a caveat and
not a bug because the moment confinement (O3) is on, "absolute paths bypass the base" must
become "absolute paths are checked against the base" — and a resolver that reached the right
answer via `join`'s discard rule has no `isabs` branch to hang the check on. The
`isabs`-first spelling is the one to ship, and the difference is invisible until O3.

---

## O3 — CONFINEMENT

Candidate in both ports: canonicalise the **deepest existing ancestor** and re-attach the
tail; compare canonical-to-canonical by path relation (`path.relative` / `os.path.commonpath`),
never by string prefix.

### js — **FEASIBLE**

Command: `node spikes/builtin-host-boundary/ports/js/p3-confine.mjs`

```
CONTROL inside file            contained=true  want=true  ok
CONTROL the base itself        contained=true  want=true  ok
CONTROL empty string           contained=true  want=true  ok
(b) nonexistent file (write)   contained=true  want=true  ok
(b) nonexistent ESCAPE         contained=false want=false  ok
relative escape                contained=false want=false  ok
absolute escape                contained=false want=false  ok
(a) via symlink out of base    contained=false want=false  ok
(a) CONTROL symlink dir itself contained=false want=false  ok
ROWS_WRONG=0
```

Three control rows are allowed by the same function that refuses the six escapes, so
"refuses escapes" is not "refuses everything". **(b)** is the pair that matters for `write`:
a nonexistent file *inside* the base is allowed and a nonexistent file *outside* it is
refused, which a check built on a plain `realpath` cannot do at all —

```
RAW_REALPATH_NATIVE_ON_MISSING="THREW:ENOENT"
CANONICAL_ON_MISSING="/private/var/.../base/nope/deeper/x.txt"
```

**(c) case normalisation — the ADR's claim is confirmed, and it matters more than it looks:**

```
REALPATH_NATIVE_OF_MIXED_CASE=".../base/sub/in.txt"
REALPATH_PLAIN_OF_MIXED_CASE=".../base/SUB/in.txt"
TRUE_CASED_REALPATH=".../base/sub/in.txt"
NATIVE_NORMALISES_CASE=true
PLAIN_NORMALISES_CASE=false
```

`fs.realpathSync.native` normalises case; **`fs.realpathSync` (the JS implementation) does
not.** Those are two functions one character apart and only one of them satisfies the spec.
`.native` is a hard requirement in the js port, not a preference.

**(d)** `fs.realpathSync.native` on a missing path **throws `ENOENT`**, which is why the
ancestor walk catches exactly `e.code === "ENOENT"` and rethrows anything else (an `EACCES`
on an unreadable ancestor must not be silently downgraded to "contained").

### python — **FEASIBLE-WITH-CAVEAT**

Command: `python3 spikes/builtin-host-boundary/ports/python/p3_confine.py`

```
CONTROL inside file              contained=True  want=True  ok
CONTROL the base itself          contained=True  want=True  ok
CONTROL empty string             contained=True  want=True  ok
(b) nonexistent file (write)     contained=True  want=True  ok
(b) nonexistent ESCAPE           contained=False want=False  ok
relative escape                  contained=False want=False  ok
absolute escape                  contained=False want=False  ok
(a) via symlink out of base      contained=False want=False  ok
(a) CONTROL symlink dir itself   contained=False want=False  ok
sibling-prefix trap              contained=False want=False  ok
ROWS_WRONG=0
```

The extra row is the reason for `os.path.commonpath`: `<base>2` is a string-prefix match of
`<base>` and must be refused.

**(d) is the opposite of js and is the caveat's first half.** `os.path.realpath` on a
missing path **raises nothing** — it returns the lexical path:

```
RAW_REALPATH_ON_MISSING='/private/var/.../base/nope/deeper/x.txt'  raised=NOTHING
REALPATH_STRICT_ON_MISSING raised=FileNotFoundError: [Errno 2] ... '.../base/nope'
CANONICAL_ON_MISSING='/private/var/.../base/nope/deeper/x.txt'
```

Silent non-strictness is worse than js's throw, because a *partially* resolvable path gets
resolved up to the first missing component and then concatenated lexically — which is
precisely the ancestor-walk semantics we want, but obtained by accident and **only for the
non-strict overload**. The ancestor walk must be written explicitly (or `strict=True` used
and `FileNotFoundError` caught), or the code silently depends on an implementation detail
of a function whose strict sibling raises.

**(c) is the real caveat, and it is a measured js/python divergence.** The ADR says python
"needs `normcase`". On a case-**insensitive POSIX** volume `normcase` does not help, because
`posixpath.normcase` is the identity function — only `ntpath.normcase` lowercases:

```
REALPATH_NORMALISES_CASE=False
NORMCASE_MAKES_THEM_EQUAL=False
OS_PATH_NORMCASE_IS_IDENTITY_ON_THIS_OS=True
```

The shape that exposes it is a host handing in a differently-cased base naming the very
same directory. **js agrees; python does not:**

| | python (`p3_confine.py`) | js (`p3-confine.mjs`) |
|---|---|---|
| `CANONICAL_BASE` | `.../tn-o3-…/base` | `.../tn-o3-…/base` |
| `CANONICAL_BASE_MIXED` | `.../tn-o3-…/BASE` | `.../tn-o3-…/base` |
| `CANONICAL_AGREE` | **False** | **true** |
| `CONTROL_STRINGS_DIFFER` | True | true |
| `INSIDE_FILE_UNDER_MIXED_BASE_CONTAINED` | True | true |
| `REAL_ESCAPE_UNDER_MIXED_BASE_REFUSED` | True | true |

Both ports reach the **right containment verdict** in every row measured, because base and
target are canonicalised through the same mis-cased string and stay consistent with each
other. The divergence is in the **canonical string itself**, which is what a refusal
message and any `metadata` would carry — two ports, same call, different bytes. And the
consistency that rescues the verdict here is not guaranteed: mix the cases *between* the
base and the target (host sets `.../BASE`, model passes an absolute `.../base/sub/in.txt`)
and python's `commonpath` compares `.../BASE` with `.../base`.

**The exact API that makes it awkward:** `os.path.realpath` does not normalise case and
`os.path.normcase` is `lambda s: s` on POSIX. A stdlib fix exists —
`pathlib.Path.resolve()` has the same property, so the only stdlib route to the on-disk
casing is to walk the path and match each component against `os.scandir` of its parent,
or to compare with `os.path.samefile` (which needs both sides to exist, and O3(b) is
exactly the case where the target does not). **Recommendation: decide this explicitly in the
change** — either accept case-sensitive canonical strings on POSIX (and say so in the spec,
since js will differ) or implement the component walk. It is not a blocker; it is an
unstated behaviour that will drift between the two ports if nobody states it.

---

## O4 — KILL THE JOB

Command shape carried over unchanged from `spikes/builtin-host-boundary/s1-orphan/probe.mjs`
and `probe.py`: `sleep 0.2; sh -c 'sleep 1; touch MARKER'` with a 300 ms timeout. Marker
present 2 s later ⇒ the grandchild outlived the kill. Both probes open with a **control arm
that does no kill at all and MUST report `ORPHAN_SURVIVED`** — if it did not, the probe
could not write its marker and every "killed" below would be an artefact.

### js — **FEASIBLE**, timeout *and* abort

Command: `node spikes/builtin-host-boundary/ports/js/p4-kill.mjs`

```
== O4 js (node v24.18.0, darwin) ==
control      ORPHAN_SURVIVED   await=not-awaited
naive        ORPHAN_SURVIVED   await=closed
group        killed_whole_job  await=closed
abort-naive  ORPHAN_SURVIVED   await=closed
abort-group  killed_whole_job  await=closed
NOTE non_detached_child_pid=… our_pid=… distinct=true — kill(-pid) on a NON-detached child
     signals a pgid we do not own (EPERM/ESRCH) or, worse, is our own group
```

The control survives (probe valid). `naive` is `js/src/builtin.ts:163`'s
`child.kill("SIGKILL")` and orphans, reproducing SPIKE §1 in this harness.
`detached:true` + `process.kill(-pid, TERM)` → grace → `KILL` kills the whole job.

**The async dimension is a non-event in js**, and that is the finding: `bashTool` is already
a `new Promise` resolved on `close`, so an `AbortSignal` is one
`signal.addEventListener("abort", killTree, {once:true})` on the same kill function the
timeout uses — `abort-group` kills the job exactly as `group` does, and the promise still
resolves on `close` (`await=closed`, not left pending). Two implementation notes:

- **`bashTool`'s run callback is `(args) => …` — it does not take `ctx` at all**
  (`js/src/builtin.ts`), although `ToolContext.signal?: AbortSignal` already exists
  (`js/src/types.ts:31`). Wiring it is a signature change in the builtin, not a new concept.
- An `AbortSignal` firing does **not** reject the promise by itself. Whether an aborted
  `bash` resolves as an error result or stays a normal result is a **spec decision the
  change must make**; this probe only shows the kill lands either way.

### python — **FEASIBLE-WITH-CAVEAT**

Command: `python3 spikes/builtin-host-boundary/ports/python/p4_kill.py`

```
== O4 python (py 3.14.7, Darwin) ==
control                ORPHAN_SURVIVED   caller_unblocked_at=1.23s  live_worker_threads=1
naive-timeout          ORPHAN_SURVIVED   caller_unblocked_at=0.31s  live_worker_threads=1
group-timeout          killed_whole_job  caller_unblocked_at=0.52s  live_worker_threads=1
naive-cancelled        ORPHAN_SURVIVED   caller_unblocked_at=0.30s  live_worker_threads=1  CancelledError raised to the caller
group-cancelled-nohook ORPHAN_SURVIVED   caller_unblocked_at=0.30s  live_worker_threads=1  CancelledError raised to the caller
group-cancelled-hook   killed_whole_job  caller_unblocked_at=0.51s  live_worker_threads=2  CancelledError raised to the caller
```

Timeout path: `start_new_session=True` + `os.killpg` works (`group-timeout`), exactly as
SPIKE §1 measured.

**The cancellation dimension is where python differs from js, and it is the caveat.** The
port's `bash` is `async def run(...)` doing `await asyncio.to_thread(do)` where `do()` calls
the **blocking** `subprocess.run` (`python/src/toolnexus/builtin.py:183-206`).

- `naive-cancelled` — cancelling the coroutine raises `CancelledError` to the caller at
  0.30 s and **the grandchild still runs to completion**. `asyncio.to_thread` has no way to
  interrupt its worker; the cancellation cancels the *future*, never the thread.
- `group-cancelled-nohook` — moving to `Popen` + `start_new_session` **changes nothing on
  its own**. The `Popen` handle lives inside the worker thread's frame, so the cancelled
  coroutine has nothing to kill. This is the arm that shows the obvious fix is not enough.
- `group-cancelled-hook` — **works.** The worker publishes its `Popen` out through a
  `threading.Event` + holder the moment it spawns; the coroutine's `except CancelledError`
  waits for that handle and calls `killpg` (itself off-loop, via `to_thread`) before
  re-raising. No third-party dependency; `threading` and `subprocess` are stdlib.

**Three exact-API caveats, all measured:**

1. **`os.killpg` raises `PermissionError` (EPERM), not `ProcessLookupError`**, when the
   group leader has taken SIGTERM but has not yet been reaped. The first run of this probe
   **crashed** on the SIGKILL step with `PermissionError: [Errno 1] Operation not permitted`.
   `spikes/builtin-host-boundary/s1-orphan/probe.py` catches only `ProcessLookupError` — the
   obvious spelling, and the one that fails. Both must be caught, on both signals.
2. **The worker thread is not reclaimed synchronously.** `live_worker_threads=2` on the
   hook arm is the still-blocked worker, alive when the arm reports. The job *is* killed —
   the marker never appears — but awaiting the inner task after cancelling it returns
   immediately (its future is already cancelled), so the thread unwinds on its own once the
   group dies. On a default executor this is a bounded leak; a host that cancels many
   `bash` calls will hold worker threads for as long as their groups take to die.
3. **Everything above requires abandoning `subprocess.run` for `Popen`.** `subprocess.run`
   never exposes a handle, so the "publish the handle out of the thread" fix is not
   expressible against it. The O1 measurement above says this is safe: the timeout path
   behaves identically with a list argv, and `Popen.communicate(timeout=…)` raises the same
   `TimeoutExpired` carrying the same partial `e.output` the error message is built from.

`ToolContext.signal` exists in python (`python/src/toolnexus/types.py:62`) typed
`Optional[Any]` and commented "advisory". This probe uses **task cancellation**, which is
the idiomatic python spelling and the one that actually arrives. If the change intends
`ctx.signal` to be the cancellation channel instead, **that is unmeasured** — I measured
`task.cancel()`.

---

## Test shapes — the smallest test that pins each obligation

Real code, in each port's existing idiom. Placement below. All are hermetic: no network, no
LLM key, `sleep`/`sh` only (the two O4 tests are POSIX-only and gated on `process.platform`
/ `sys.platform` — a Windows equivalent needs the `taskkill` arm, which is unmeasured here).

### js — beside the builtin block in `js/test/unit.test.ts` (from line 781, after
`"toolkit: builtins.tools map disables named tools only"`)

```ts
// O1 — the argv prefix is used verbatim
test("bash: a host-set shell prefix is used verbatim and reported in metadata", async () => {
  const tk = await createToolkit({ builtins: { shell: ["/bin/sh", "-c"] } } as any)
  const r = await tk.execute("bash", { command: "echo one; echo two" })
  assert.equal(r.isError, false)
  assert.equal(r.output, "one\ntwo\n")
  assert.deepEqual((r.metadata as any).shell, ["/bin/sh", "-c"])
  await tk.close()
})

// O2 — empty base is byte-identical; a set base moves the bytes, not the golden
test("baseDir: relative paths resolve against it; empty baseDir is today's behaviour", async () => {
  const base = tmpdir() // the existing helper at unit.test.ts:57
  const tk = await createToolkit({ builtins: { baseDir: base } } as any)
  assert.equal((await tk.execute("write", { path: "sub/x.txt", content: "hi" })).isError, false)
  assert.equal(readFileSync(path.join(base, "sub/x.txt"), "utf8"), "hi")
  // apply_patch's paths are CONTENT, not arguments — they must move too
  const patch = "*** Begin Patch\n*** Add File: sub/y.txt\n+yo\n*** End Patch"
  assert.equal((await tk.execute("apply_patch", { patch })).isError, false)
  assert.equal(readFileSync(path.join(base, "sub/y.txt"), "utf8"), "yo")
  // bash's workdir defaults to it
  const pwd = await tk.execute("bash", { command: "pwd" })
  assert.equal(pwd.output.trim(), fs.realpathSync.native(base))
  await tk.close()
  // CONTROL: with no baseDir the same call is unchanged — the read output string,
  // which is what a §0 golden pins, must not gain an absolute prefix.
  const plain = await createToolkit({})
  const rel = path.relative(process.cwd(), path.join(base, "sub/x.txt"))
  assert.equal((await plain.execute("read", { path: rel })).output, "hi")
  await plain.close()
})

// O3 — canonicalise both sides; (b) is the write case
test("confineToBaseDir: refuses escapes, allows a not-yet-existing file inside", async () => {
  const base = tmpdir()
  const outside = tmpdir()
  fs.mkdirSync(path.join(base, "sub"))
  fs.writeFileSync(path.join(outside, "secret.txt"), "secret")
  fs.symlinkSync(outside, path.join(base, "link"))
  const tk = await createToolkit({ builtins: { baseDir: base, confineToBaseDir: true } } as any)
  // CONTROL: the check is not "refuse everything"
  assert.equal((await tk.execute("write", { path: "sub/new.txt", content: "x" })).isError, false)
  for (const p of ["../escape.txt", path.join(outside, "secret.txt"), "link/secret.txt"]) {
    const r = await tk.execute("read", { path: p })
    assert.equal(r.isError, true, `refused: ${p}`)
    assert.ok(r.output.includes(p), "the message names the refused path")
  }
  assert.equal(fs.existsSync(path.join(outside, "escape.txt")), false)
  await tk.close()
})

// O4 — the kill reaches the grandchild, on timeout AND on abort
test("bash: a timeout kills the grandchild, not just the shell", { skip: process.platform === "win32" }, async () => {
  const dir = tmpdir()
  const marker = path.join(dir, "m")
  const tk = await createToolkit({})
  const r = await tk.execute("bash", {
    // the `sleep 0.2` is load-bearing: without it the shell execs the single
    // command and there is no grandchild to orphan.
    command: `sleep 0.2; sh -c 'sleep 1; touch ${marker}'`,
    timeout: 300,
  })
  assert.equal(r.isError, true)
  assert.equal((r.metadata as any).timedOut, true)
  assert.equal((r.metadata as any).killedTree, true)
  await new Promise((res) => setTimeout(res, 1500))
  assert.equal(fs.existsSync(marker), false, "the grandchild outlived the kill")
  await tk.close()
})

test("bash: aborting ctx.signal kills the grandchild", { skip: process.platform === "win32" }, async () => {
  const dir = tmpdir()
  const marker = path.join(dir, "m")
  const ac = new AbortController()
  const tk = await createToolkit({})
  const p = tk.execute("bash", { command: `sleep 0.2; sh -c 'sleep 1; touch ${marker}'` }, { signal: ac.signal })
  setTimeout(() => ac.abort(), 300)
  await p
  await new Promise((res) => setTimeout(res, 1500))
  assert.equal(fs.existsSync(marker), false)
  await tk.close()
})
```

### python — beside the builtin block in `python/tests/test_unit.py` (from line 402, after
`test_toolkit_builtins_toggle_off_removes_all_10`)

```python
# O1 — the argv prefix is used verbatim
async def test_bash_host_set_shell_used_verbatim():
    tk = await create_toolkit(builtins={"shell": ["/bin/sh", "-c"]})
    try:
        r = await tk.execute("bash", {"command": "echo one; echo two"})
        assert r.is_error is False
        assert r.output == "one\ntwo\n"
        assert r.metadata["shell"] == ["/bin/sh", "-c"]
    finally:
        await tk.close()


# O2 — empty base is byte-identical; a set base moves the bytes, not the golden
async def test_base_dir_binds_write_apply_patch_and_bash(tmp_path):
    tk = await create_toolkit(builtins={"base_dir": str(tmp_path)})
    try:
        assert (await tk.execute("write", {"path": "sub/x.txt", "content": "hi"})).is_error is False
        assert (tmp_path / "sub" / "x.txt").read_text() == "hi"
        # apply_patch's paths are CONTENT, not arguments
        patch = "*** Begin Patch\n*** Add File: sub/y.txt\n+yo\n*** End Patch"
        assert (await tk.execute("apply_patch", {"patch": patch})).is_error is False
        assert (tmp_path / "sub" / "y.txt").read_text() == "yo"
        pwd = await tk.execute("bash", {"command": "pwd"})
        assert pwd.output.strip() == os.path.realpath(tmp_path)
    finally:
        await tk.close()
    # CONTROL: no base_dir ⇒ the echoed path string is unchanged (the §0 golden)
    plain = await create_toolkit()
    try:
        rel = os.path.relpath(tmp_path / "sub" / "x.txt")
        assert (await plain.execute("read", {"path": rel})).output == "hi"
    finally:
        await plain.close()


# O3 — canonicalise both sides; the nonexistent-inside case is the `write` case
async def test_confine_to_base_dir_refuses_escapes(tmp_path):
    base, outside = tmp_path / "base", tmp_path / "outside"
    (base / "sub").mkdir(parents=True)
    outside.mkdir()
    (outside / "secret.txt").write_text("secret")
    os.symlink(outside, base / "link")
    tk = await create_toolkit(builtins={"base_dir": str(base), "confine_to_base_dir": True})
    try:
        # CONTROL: the check is not "refuse everything"
        assert (await tk.execute("write", {"path": "sub/new.txt", "content": "x"})).is_error is False
        for p in ["../outside/secret.txt", str(outside / "secret.txt"), "link/secret.txt"]:
            r = await tk.execute("read", {"path": p})
            assert r.is_error is True, f"refused: {p}"
            assert p in r.output
        assert not (outside / "new.txt").exists()
    finally:
        await tk.close()


# O4 — the kill reaches the grandchild, on timeout AND on task cancellation
@pytest.mark.skipif(sys.platform == "win32", reason="POSIX process groups")
async def test_bash_timeout_kills_the_grandchild(tmp_path):
    marker = tmp_path / "m"
    tk = await create_toolkit()
    try:
        # the `sleep 0.2` is load-bearing: without it the shell execs the single
        # command and there is no grandchild to orphan.
        r = await tk.execute(
            "bash",
            {"command": f"sleep 0.2; sh -c 'sleep 1; touch {marker}'", "timeout": 300},
        )
        assert r.is_error is True
        assert r.metadata["timedOut"] is True
        assert r.metadata["killedTree"] is True
        await asyncio.sleep(1.5)
        assert not marker.exists(), "the grandchild outlived the kill"
    finally:
        await tk.close()


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX process groups")
async def test_bash_task_cancellation_kills_the_grandchild(tmp_path):
    marker = tmp_path / "m"
    tk = await create_toolkit()
    try:
        task = asyncio.ensure_future(
            tk.execute("bash", {"command": f"sleep 0.2; sh -c 'sleep 1; touch {marker}'"})
        )
        await asyncio.sleep(0.3)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.sleep(1.5)
        assert not marker.exists(), "cancellation did not reach the job"
    finally:
        await tk.close()
```

**A note on the two O4 tests' cost:** each holds ~1.8 s of wall clock, and the sleep is not
removable — it is what creates the grandchild. `python/tests` already serialises IO-sensitive
tests (the csharp parallel-IO lesson from the multimodal work applies here too); the js suite
runs `node --test` single-file. If the ~4 s is unwelcome, mark them and run them in a
dedicated CI step rather than shortening the sleeps, which is how SPIKE §1's first two runs
measured nothing.

---

## Unmeasured, by name

- **Windows, both ports.** Nothing in this pass ran on Windows. Specifically unmeasured:
  `%COMSPEC%` → `pwsh` → `powershell` → `bash` detection order in js/python; `taskkill /T /F /PID`
  as the O4 kill in either port; whether node's `fs.realpathSync.native` and python's
  `os.path.realpath` resolve a **directory junction** *from these ports' code paths* (SPIKE
  §3.2 measured the raw APIs there and both resolve); Windows reserved device names (`CON`,
  `NUL`, …) being refused; the 8.3 short-name case. SPIKE §2.2/§3.2 covers the raw-API half
  of these on a real Windows box; the port-level half is not covered.
- **Linux, both ports.** Nothing ran on Linux. `/bin/sh` is `dash` on Debian-family, which
  is O1's whole motivation, and the `dash` data point in SPIKE §2.1 came from `dash` on macOS.
- **A case-SENSITIVE POSIX volume.** The O3 case findings were taken on case-insensitive
  APFS. On a case-sensitive volume `.../BASE` and `.../base` are genuinely different
  directories and the js/python divergence should vanish — not measured.
- **Whether `ctx.signal` (rather than task cancellation) is python's intended cancel
  channel.** `ToolContext.signal` is `Optional[Any]`, "advisory". I measured `task.cancel()`.
- **Whether an aborted/cancelled `bash` should resolve as an error result or propagate.**
  Both probes show the kill lands either way; the observable result shape is a spec decision
  the change has to make, and no probe here can decide it.
- **Behaviour under a non-default asyncio executor** (the O4 python worker-thread leak was
  measured against the default `ThreadPoolExecutor`).
