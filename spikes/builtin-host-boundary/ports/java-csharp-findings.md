# Port feasibility — java and csharp, measured against ADR 0034's four obligations

**Scope:** `java/` (`java/src/main/java/io/github/muthuishere/toolnexus/BuiltinTools.java`)
and `csharp/` (`csharp/src/Toolnexus/BuiltinTools.cs`) only. Measuring, not designing:
every verdict below is backed by a probe that ran on this machine, with a **control arm**,
and the raw output is pasted at the bottom.

- Host: macOS 26.4 / Darwin 25.4.0, arm64. `openjdk 26.0.1` (Homebrew), `dotnet 10.0.301`.
- Probes: `ports/java/Probe.java` (`bash ports/java/run.sh`) and `ports/csharp/Program.cs`
  (`bash ports/csharp/run.sh`). Each takes an optional section argument (`o1`…`o4`).
  JDK / .NET SDK only — **no third-party dependency**, no network, no LLM key.
- **Nothing here was measured on Windows.** No JDK and no .NET SDK exist on the Windows box
  the spike can reach (SPIKE.md §5 says the same). Every Windows statement below is a
  *documentation* claim, tagged `UNVERIFIED-ON-WINDOWS`, with the doc it comes from.

## Verdict table

| obligation | java | csharp |
|---|---|---|
| 1 SHELL — argv prefix verbatim + detection | **FEASIBLE** | **FEASIBLE-WITH-CAVEAT** (`Win32Exception`, not a typed "not found") |
| 2 BASEDIR — incl. apply_patch text, `""` == today | **FEASIBLE** | **FEASIBLE-WITH-CAVEAT** (`Path.Combine` silently drops the base on a rooted arg) |
| 3 CONFINEMENT — canonicalise both sides | **FEASIBLE-WITH-CAVEAT** (`toRealPath` throws on the `write` case; no case folding) | **FEASIBLE-WITH-CAVEAT** (no `realpath` at all: `ResolveLinkTarget` sees only the FINAL segment ⇒ hand-rolled per-component walk) |
| 4a KILL THE JOB (tree) | **FEASIBLE** (`descendants()` snapshot **before** the kill) | **FEASIBLE** (`Kill(entireProcessTree: true)`) |
| 4b TERM → 2000 ms grace → KILL | **FEASIBLE** (`destroy()` **is** SIGTERM — measured, not assumed) | **NOT-FEASIBLE-IN-IDIOM with the BCL alone** — .NET has no graceful kill on POSIX; needs `DllImport("libc") kill(2)` + a `set -m` process group |

---

## 1. SHELL

### java — FEASIBLE

`ProcessBuilder` **does** resolve a bare program name on `PATH` (`bare_name_sh=exit=0
out="bare-ok"`), with the control arm proving the measurement is about PATH and not about
`sh` existing (`control_abs_bin_sh=exit=0 out="abs-ok"`). So the port can and should stop
hardcoding `/bin/sh` (`BuiltinTools.java:237`) and spell it `sh`.

A host-set prefix is used verbatim — measured with a bashism that separates the candidates:

```
prefix_bash_lc=exit=0 out="bashism-ok"
prefix_sh_c=exit=0   out="bashism-ok"      (macOS /bin/sh is bash in sh mode)
prefix_dash_c=exit=127 out="dash: 1: [[: not found"   <- the CONTROL: a real POSIX sh
prefix_4elem_env_shape=exit=0 out="1"      <- a 4-element prefix (the `cmd /d /s /c` shape) works
```

**The failure that "construction fails naming the candidates" is built from:**

```
resolve_pwsh=THROWS java.io.IOException msg="Cannot run program "pwsh": Exec failed, error: 2 (No such file or directory) "
non_executable_file=THROWS java.io.IOException msg="Cannot run program "/…/not-executable": Exec failed, error: 13 (Permission denied) "
```

Caveat worth naming even though it does not change the verdict: **both are plain
`java.io.IOException`**; "not found" (errno 2) and "not executable" (errno 13) are
distinguishable only by the message text. Detection should therefore be a `PATH` walk
(`Files.isExecutable`) rather than a spawn-and-catch, and the construction error should list
the candidates it tried rather than forward one exception message.

`UNVERIFIED-ON-WINDOWS`: on Windows `ProcessBuilder` goes through `CreateProcessW`, whose
documented search order is the application directory, the current directory, the system
directories, then `%PATH%`
(<https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw>,
"lpApplicationName / lpCommandLine" search-path notes), and a missing image surfaces as
`IOException: CreateProcess error=2, The system cannot find the file specified`. Not measured.
Note the ADR's Windows order needs `%COMSPEC%` read from the environment, which is a plain
`System.getenv("COMSPEC")` and needs nothing from the process API.

### csharp — FEASIBLE-WITH-CAVEAT

`ProcessStartInfo` with `UseShellExecute=false` (which the port must keep, for redirection)
**does** resolve a bare name on `PATH` (`bare_name_sh=exit=0 out="bare-ok"`; control
`control_abs_bin_sh=exit=0 out="abs-ok"`). Verbatim prefix works identically
(`prefix_bash_lc` ok, `prefix_dash_c=exit=127`, 4-element prefix ok).

**The caveat is the failure shape.** Everything is one exception type:

```
resolve_pwsh=THROWS System.ComponentModel.Win32Exception NativeErrorCode=2 msg="An error occurred trying to start process 'pwsh' with working directory '…'. No such file or directory"
non_executable_file=THROWS System.ComponentModel.Win32Exception NativeErrorCode=13 msg="… Permission denied"
```

The awkward API is exactly this: **`System.ComponentModel.Win32Exception` with a
`NativeErrorCode`**, not a `FileNotFoundException` — so "is this candidate absent?" is
`ex.NativeErrorCode == 2` (ENOENT on POSIX, `ERROR_FILE_NOT_FOUND` on Windows), and the
message embeds the working directory, which makes it a poor thing to surface verbatim.
There is also **no BCL "which"**: detection must walk `PATH` by hand
(`probe_without_spawn_hint=no BCL API; PATH is walked by hand — sh=>/bin/sh | pwsh=>MISSING`),
and on Windows it must additionally apply `%PATHEXT%`. Both are a dozen lines, neither is
a dependency — hence FEASIBLE-WITH-CAVEAT, not NOT-FEASIBLE.

`UNVERIFIED-ON-WINDOWS`: `ProcessStartInfo.FileName` with `UseShellExecute=false` is
documented to use `CreateProcess` search semantics
(<https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.processstartinfo.filename>).
Not measured.

## 2. BASEDIR

### java — FEASIBLE

The candidate resolver is `baseDir.isEmpty() ? Path.of(p) : (isAbsolute ? p : base.resolve(p))`.

**An empty base is today's behaviour, byte-for-byte** — measured as the same *string* handed
to the filesystem, not merely the same file:

```
empty_base_same_string=true ("tn-empty-base-probe.txt")
empty_base_same_absolute=true
empty_base_is_process_cwd=true
control_nonempty_base_differs=true ("/…/base/tn-empty-base-probe.txt")   <- the control
```

Base honoured, absolutes untouched, `apply_patch`'s *content* paths resolved:

```
relative_write_landed_under_base=true
nothing_in_cwd=true
absolute_unaffected=true
patch_text_path_landed_under_base=true ([sub/added.txt])
control_patch_unresolved_target=/…/spikes/builtin-host-boundary/ports/java/sub/added.txt   <- issue #101, reproduced
```

`bash`'s workdir via `ProcessBuilder.directory()`:

```
bash_workdir_default_base=/…/base
bash_workdir_explicit_wins=/…/other
control_bash_workdir_unset=/…/ports/java        <- unset == process cwd, i.e. today
```

**§4A separators: the base does not change them.** `relativize` against the base emits
`"sub/x.txt"` with `File.separatorChar` → `/` applied (`sep_char="/"`,
`rel_emitted_with_base_root="sub/x.txt"`). The port's `relativize` helper
(`BuiltinTools.java:910`) already absolutises both sides, so passing the base as the walk root
is the same code path. One thing the probe shows and the port must keep: the walk root must be
made absolute *before* relativizing — `Path.of(".")` against an absolute file gives
`"../../../../../../../../../../private/var/…"`, a correct-but-useless relative path.
`control_replace_on_backslash` records that on POSIX the `File.separatorChar → '/'` replace is
a **no-op**, so **the `/`-separated obligation is UNVERIFIED on Windows** in both ports; it is
asserted by the code, not by a measurement.

### csharp — FEASIBLE-WITH-CAVEAT

Same results (`empty_base_same_string=True`, `empty_base_is_process_cwd=True`,
`control_nonempty_base_differs=True`, `patch_text_path_landed_under_base=True`,
`bash_workdir_default_base` / `explicit_wins` / `control_bash_workdir_unset` all as in java,
via `ProcessStartInfo.WorkingDirectory`).

**The caveat is one API:**

```
Path.Combine_discards_rooted_second_arg="/etc/passwd"  <- why IsPathRooted must be checked explicitly
```

`Path.Combine(base, p)` **silently discards the base** when `p` is rooted. That is convenient
and it is also a containment escape hatch: a model-supplied absolute path would bypass the
base without the resolver noticing it had done so. The port must branch on
`Path.IsPathRooted(p)` itself and treat the absolute case as an explicit, named decision
(ADR 0034 D2 says absolute paths are unaffected — that must be a written rule, not a
side effect of `Combine`). Note `IsPathRooted` is platform-dependent on Windows (`\foo` is
"rooted" but drive-relative) — `UNVERIFIED-ON-WINDOWS`.

`Path.GetRelativePath(root, file).Replace('\\','/')` at `BuiltinTools.cs:235` is already the
§4A shape and the base does not change it (`rel_emitted_with_base_root="sub/x.txt"`); as in
java, `GetRelativePath(".", file)` resolves `.` against the **cwd**, so the walk root must be
absolutised first.

## 3. CONFINEMENT

Both ports can implement it. Both need a hand-written canonicaliser, and in both the
**naive** version inverts the check — measured, with the control.

### java — FEASIBLE-WITH-CAVEAT

What `toRealPath` does and throws:

```
toRealPath_existing=/private/var/…/c-base/sub/in.txt          <- resolves symlinks AND /var -> /private/var
toRealPath_missing=THROWS java.nio.file.NoSuchFileException msg="/…/c-base/sub/does-not-exist.txt"
toRealPath_missing_NOFOLLOW=THROWS NoSuchFileException          <- NOFOLLOW does not rescue it
```

**That throw is the whole caveat**: `Path.toRealPath` is unusable on the `write` case, so the
port must implement "deepest existing ancestor, re-attach the tail" by catching
`NoSuchFileException` and walking up. With it:

```
(a) symlink out of the base
symlink_target=/…/c-outside
canonical_through_symlink=/…/c-outside/secret.txt
a_symlink_escape_contained=false          <- must be false
control_real_inside_contained=true        <- must be true (the probe is not just refusing everything)
control_lexical_would_say_contained=true  <- the bug, if you skip canonicalisation

(b) a path that does not exist yet — the `write` case
b_new_file_in_base_contained=true
b_new_file_in_new_dirs_contained=true          (a/b/c/brand-new.txt: none of a, b, c exist)
b_new_file_escape_contained=false
b_new_file_through_symlink_contained=false
control_naive_canon_new_file_escape=true  <- the naive canonicaliser (toRealPath on the whole
                                             path, fall back to lexical on failure) says CONTAINED.
                                             That is the inversion ADR 0034 warns about, reproduced.

(c) case
fs_case_insensitive_lookup=true                    (APFS, case-insensitive)
toRealPath_case_folds="/…/c-base"                  <- it returns the ON-DISK spelling, so it DOES fold
c_upper_case_path_contained=true
c_equals_is_case_sensitive_string_compare=false    <- Path.equals("/tmp/A","/tmp/a") is FALSE here

string_prefix_says_contained=true    <- /tmp/pfx-evil/f.txt "starts with" /tmp/pfx: WRONG
startsWith_says_contained=false      <- Path.startsWith is right
```

Two API notes for the implementer:

- **`Path.equals` does not fold case on a case-insensitive filesystem** (`false` above). Case
  insensitivity came free here only because `toRealPath` returned the on-disk spelling for an
  *existing* path; a **non-existent** tail is re-attached with the spelling the caller gave,
  so a `write` to `sub/IN.TXT` inside the base compares as a different string. Compare with
  `Path.startsWith` on the canonical forms (which is path-relation, not string prefix) and
  do not rely on `equals` for the "is the base itself" case on Windows/macOS.
- `toRealPath` also rewrote `/var` → `/private/var`; **the base must be canonicalised with
  the same function**, or every path "escapes". (The first run of this probe failed exactly
  that way and is why `Probe.java:18` canonicalises the temp root up front.)

`UNVERIFIED-ON-WINDOWS`: the JDK implements `WindowsPath.toRealPath` via
`GetFinalPathNameByHandle` (`WindowsLinkSupport.getFinalPath`, JDK `java.base`), which is the
same call SPIKE §3.2 measured as *correctly* resolving a directory junction where Go's
`filepath.EvalSymlinks` failed and inverted the check. So java is expected to be fine on
junctions and on 8.3 short names — **expected, not measured.** Likewise the reserved-device-name
rule (`CON`, `NUL`, …) is unmeasured for java; it must be a written refusal list, since SPIKE
§3.2 measured that such a path *passes* a containment check.

### csharp — FEASIBLE-WITH-CAVEAT (the largest caveat of the eight)

**.NET has no `realpath`.** Measured:

```
GetFullPath_is_purely_lexical="/…/c-outside/secret.txt"                      (it does collapse ..)
GetFullPath_through_symlink_does_NOT_resolve="/…/c-base/link/secret.txt"     <- the escape survives it
ResolveLinkTarget_on_the_link_itself=/…/c-outside                            <- works on a link
ResolveLinkTarget_on_path_THROUGH_a_link=null   <- THE TRAP: only the FINAL segment is inspected
ResolveLinkTarget_on_missing_path=THROWS System.IO.FileNotFoundException
ResolveLinkTarget_on_non_link=null              <- a non-link and a broken probe look identical
```

So the port must write a **per-component walk** (`RealPath` in `Program.cs`): split the path,
call `ResolveLinkTarget(false)` on each prefix, follow up to a hop limit, and re-attach the
non-existent tail. With that walk the obligations hold, and the naive version inverts:

```
a_symlink_escape_contained=False            <- must be false
control_real_inside_contained=True          <- must be true
control_lexical_would_say_contained=True    <- the bug if you skip canonicalisation
b_new_file_in_base_contained=True
b_new_file_in_new_dirs_contained=True
b_new_file_escape_contained=False
b_new_file_through_symlink_contained=False
control_naive_resolvelinktarget_says_contained=True  <- naive says CONTAINED (and it threw
                                                        FileNotFoundException first, so the
                                                        fallback-to-lexical is not optional)
```

Case, and the comparison rule:

```
fs_case_insensitive_lookup=True
GetFullPath_case_folds="/PRIVATE/VAR/…/C-BASE"   <- GetFullPath does NOT case-fold at all
ResolveLinkTarget_case_folds=null (not a link => no case folding either)
c_upper_case_path_contained_with_OrdinalIgnoreCase=True
control_ordinal_compare_would_say=False   <- Ordinal on a case-insensitive fs: two spellings
                                             of ONE directory disagree
string_prefix_says_contained=True     <- /…/pfx-evil/f.txt "starts with" /…/pfx: WRONG
GetRelativePath_says_contained=False  <- right
```

Named caveats, exactly the awkward APIs:

- **`Path.GetFullPath` is lexical** (documented, and measured) — it is a normaliser, not a
  canonicaliser, and using it alone is the inverted check.
- **`FileSystemInfo.ResolveLinkTarget` inspects only the final segment**, returns `null` both
  for "not a link" and after a successful non-link probe, and **throws
  `FileNotFoundException` on a missing path** — the `write` case again.
- **No case-folding API.** The comparison has to be `StringComparison.OrdinalIgnoreCase` on
  Windows and macOS and `Ordinal` on Linux, chosen by `OperatingSystem.Is*()`. That is a
  policy decision the port must write down, because .NET will not make it.
- Containment itself is `Path.GetRelativePath` + "not rooted and not starting with `..`"
  (there is no `Path.StartsWith`). `Path.GetRelativePath` is **lexical**, so it must be fed
  canonical inputs.

`UNVERIFIED-ON-WINDOWS`: `Directory/FileInfo.ResolveLinkTarget` is documented as resolving
"the target of the link"
(<https://learn.microsoft.com/en-us/dotnet/api/system.io.filesysteminfo.resolvelinktarget>);
whether it follows an **NTFS directory junction** (`IO_REPARSE_TAG_MOUNT_POINT`, creatable
with **no privilege** — SPIKE §3.2 measured `IS_ADMIN=False`) rather than only a symlink
(`IO_REPARSE_TAG_SYMLINK`) is **not established by that doc and was not measured**. Given
that Go's `EvalSymlinks` inverted the check on exactly this case, **this is the single
highest-risk unverified claim in this report** and should be settled either on a Windows box
with the .NET SDK or by P/Invoking `GetFinalPathNameByHandle`, which SPIKE §3.2 *did* measure
as correct through a junction. 8.3 short names and reserved device names are likewise
unmeasured for this port.

## 4. KILL THE JOB

Probe shape from `spikes/builtin-host-boundary/s1-orphan`: `sleep 0.2; sh -c 'sleep 1; touch
MARKER'` with a 300 ms timeout. The leading `sleep 0.2` stops the outer shell `exec`ing the
single command, so there really is a grandchild. **Marker present ⇒ the job outlived the kill.**

### java — 4a FEASIBLE, 4b FEASIBLE

```
orphan_control=ORPHAN_SURVIVED              <- the CONTROL: with no kill the marker appears,
                                               so the arms below measure something
orphan_naive=ORPHAN_SURVIVED                <- today's code (BuiltinTools.java:237, destroyForcibly)
orphan_tree=killed_whole_job DESCENDANTS=2  <- snapshot -> destroy -> grace -> destroyForcibly
REACHABLE_AFTER_KILL=0                      <- after the parent dies, descendants() finds NOTHING
reparent_marker_written=true                <- ...and the work carried on. Snapshot BEFORE the kill.
```

The TERM/KILL claim, **measured with a shell trap rather than taken from the javadoc**:

```
signal_of_destroy=trap_saw=GOT_TERM exit=0                 <- destroy() IS SIGTERM
signal_of_destroyForcibly=trap_saw=(nothing) exit=137      <- 128+9 => SIGKILL. The control.
grace_child_honours_TERM=exited_in_grace=true elapsed_ms=0 exit=0
grace_child_ignores_TERM=exited_in_grace=false elapsed_ms=2009 exit=137   <- 2000 ms grace, then KILL
```

And the same sequence against a **grandchild that ignores TERM**:

```
grace_tree_grace=killed_whole_job DESCENDANTS=2 exited_in_grace=false
grace_control_term_only=ORPHAN_SURVIVED DESCENDANTS=2 exited_in_grace=false
   <- the control: TERM alone does NOT finish the job, so the forceful follow-up is load-bearing
```

So java expresses the whole contract with `ProcessHandle` alone: `destroy()` = TERM,
`destroyForcibly()` = KILL, `descendants()` = the set, `waitFor(2000, MILLISECONDS)` = the
grace window. The only trap is ordering, and it is measured above.
`UNVERIFIED-ON-WINDOWS`: `ProcessHandle.destroy()` is documented as "normal termination" and
`destroyForcibly()` as "forcible" — the javadoc explicitly says whether `destroy()` terminates
normally or forcibly is **implementation dependent**
(<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html#destroy()>),
and on Windows both are `TerminateProcess`. **There is no graceful termination for java on
Windows**, so the 2000 ms grace window is a POSIX-only effect there. Not measured.

### csharp — 4a FEASIBLE, 4b NOT-FEASIBLE-IN-IDIOM with the BCL alone

The tree kill works:

```
orphan_control=ORPHAN_SURVIVED               <- control
orphan_naive=ORPHAN_SURVIVED                 <- today's code (BuiltinTools.cs:273, p.Kill())
orphan_tree=killed_whole_job                 <- p.Kill(entireProcessTree: true)
REACHABLE_AFTER_KILL_children_of_dead_parent=exit=0 out="0"
reparent_marker_written=True
```

The grace period does not:

```
signal_of_Kill_false=trap_saw=(nothing) exit=137        <- Kill()  sends SIGKILL
signal_of_Kill_true=trap_saw=(nothing) exit=137         <- Kill(true) also SIGKILL
signal_of_CloseMainWindow=trap_saw=(nothing) exit=STILL_RUNNING CloseMainWindow_returned=False
signal_of_libc_SIGTERM=trap_saw=GOT_TERM exit=0 kill(2)_rc=0   <- only a P/Invoke delivers TERM
```

**`Process.Kill` has no graceful form on POSIX — measured, both overloads send SIGKILL — and
`CloseMainWindow` returns `False` and does nothing.** There is no `Process.SendSignal`;
`PosixSignalRegistration` only *receives*. So the grace window requires
`[DllImport("libc")] static extern int kill(int pid, int sig)`. That is not a third-party
dependency (it is `System.Runtime.InteropServices` against the platform libc), but it *is*
a P/Invoke and a `[SupportedOSPlatform("...")]`-guarded per-platform branch entering the port.

And a second, sharper measurement: a graceful TERM must reach the **job**, not the child —
`Kill(entireProcessTree: true)` is the only tree-aware call and it is forceful-only:

```
gracetree_control_no_kill=ORPHAN_SURVIVED                       <- control
gracetree_kill_child_only=ORPHAN_SURVIVED kill(pid,TERM)_rc=0
     <- TERM the direct child: the shell dies IMMEDIATELY, so the later Kill(true) has no tree
        left to walk, the grandchild is reparented, and the job survives the "graceful" step.
gracetree_pgroup_setm=killed_whole_job pgid=23856 kill(-pgid,TERM)_rc=0
     <- a `set -m` wrapper (the SPIKE §1.1 trick) makes the job its own process group, and
        kill(-pgid, SIGTERM) then reaches all of it.
```

So the honest csharp answer is: **`Kill(entireProcessTree: true)` satisfies "kill the job" but
cannot satisfy "TERM, grace, then KILL"**. Expressing the full D4 contract on POSIX costs a
libc P/Invoke *and* a process-group wrapper (`sh -c 'set -m; { cmd; } & echo $! > pg; wait $!'`),
which changes what the child sees (an extra shell level, `$!` bookkeeping) — the reason to
flag this rather than quietly implement it.

`UNVERIFIED-ON-WINDOWS`: `Process.Kill(entireProcessTree: true)` is documented to kill the
process and its descendants
(<https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.process.kill#system-diagnostics-process-kill(system-boolean)>),
which is the ADR's Windows mechanism for this port. SPIKE §4.2 measured that plain
`Process.Kill` (the naive arm) orphans on Windows; the `true` overload was **not** measured
there. Windows has no graceful termination for either port, so the grace window is documented
as a POSIX effect. Not measured.

---

## Test shapes — the smallest test that pins each obligation

Real code, in each port's existing conventions, naming the file it belongs beside. Each is
hermetic (temp dirs, no network, no LLM). **csharp note:** the suite is serialised on purpose
(`csharp/tests/Toolnexus.Tests/AssemblyInfo.cs`, `DisableTestParallelization = true`) because
parallel IO once starved a timeout test — the timing tests below depend on that and no test
config change is needed or wanted.

### java — beside `java/src/test/java/io/github/muthuishere/toolnexus/BuiltinToolsTest.java`

```java
// O1 — a host-set shell prefix is used verbatim, and is reported.
@Test
void hostSuppliedShellPrefixIsUsedVerbatimAndReported() {
    var opts = Map.<String, Object>of("shell", List.of("bash", "-lc"));
    var result = BuiltinTools.create(opts).stream()
            .filter(t -> t.name().equals("bash")).findFirst().orElseThrow()
            .execute(Map.of("command", "[[ -d . ]] && echo bashism-ok"), null);
    assertTrue(result.output().contains("bashism-ok"));      // dash/sh would exit 127
    assertEquals(List.of("bash", "-lc"), result.metadata().get("shell"));
}

// O2 — a relative write lands under baseDir, and NOTHING lands in the process cwd.
@Test
void relativePathsResolveAgainstBaseDirNotProcessCwd() throws Exception {
    Path base = Files.createTempDirectory("tn-base");
    var tools = BuiltinTools.create(Map.of("baseDir", base.toString()));
    run(tools, "write", Map.of("filePath", "sub/x.txt", "content", "x"));
    assertEquals("x", Files.readString(base.resolve("sub/x.txt")));
    assertFalse(Files.exists(Path.of("sub/x.txt")));          // the #101 control
}

// O2 — the empty base is today's behaviour, byte-for-byte.
@Test
void emptyBaseDirIsProcessCwdExactlyAsBefore() throws Exception {
    Path cwd = Path.of(System.getProperty("user.dir"));
    Path f = cwd.resolve("tn-empty-base-test.txt");
    try {
        run(BuiltinTools.create(Map.of("baseDir", "")), "write",
                Map.of("filePath", "tn-empty-base-test.txt", "content", "x"));
        assertTrue(Files.exists(f));
    } finally { Files.deleteIfExists(f); }
}

// O3 — confinement decides on the CANONICAL path; a symlink escape is refused,
//      a brand-new file inside the base is not.
@Test
void confinementRefusesSymlinkEscapeButAllowsNewFileInsideBase() throws Exception {
    Path base = Files.createTempDirectory("tn-base").toRealPath();
    Path outside = Files.createTempDirectory("tn-out").toRealPath();
    Files.writeString(outside.resolve("secret.txt"), "secret");
    Files.createSymbolicLink(base.resolve("link"), outside);
    var tools = BuiltinTools.create(Map.of("baseDir", base.toString(), "confineToBaseDir", true));

    ToolResult escaped = run(tools, "read", Map.of("filePath", "link/secret.txt"));
    assertTrue(escaped.isError());
    assertFalse(escaped.output().contains("secret"));
    // CONTROL: the write case — a path that does not exist yet, inside the base, is allowed.
    assertFalse(run(tools, "write", Map.of("filePath", "a/b/new.txt", "content", "x")).isError());
}

// O4 — a timed-out command's grandchild does not outlive the call.
@Test
void timeoutKillsTheWholeJobNotJustTheShell() throws Exception {
    Path marker = Files.createTempDirectory("tn-kill").resolve("MARKER");
    ToolResult r = run(BuiltinTools.create(), "bash", Map.of(
            "command", "sleep 0.2; sh -c 'sleep 1; touch " + marker + "'", "timeout", 300));
    Thread.sleep(2000);
    assertFalse(Files.exists(marker), "the grandchild outlived the kill");
    assertEquals(true, r.metadata().get("timedOut"));
    assertEquals(true, r.metadata().get("killedTree"));
}
```

`run(tools, name, args)` is the two-line lookup helper `BuiltinToolsTest` already has
(`tool(...)` / `run(...)` at the top of that file), widened to take the tool list.

### csharp — beside `csharp/tests/Toolnexus.Tests/BuiltinToolsTests.cs`

```csharp
// O1 — a host-set shell prefix is used verbatim, and is reported.
[Fact]
public async Task HostSuppliedShellPrefixIsUsedVerbatimAndReported()
{
    var tools = BuiltinTools.Select(null, new BuiltinOptions { Shell = new[] { "bash", "-lc" } });
    var bash = tools.Single(t => t.Name == "bash");
    var r = await bash.ExecuteAsync(new Dictionary<string, object?>
        { ["command"] = "[[ -d . ]] && echo bashism-ok" });
    Assert.Contains("bashism-ok", r.Output);
    Assert.Equal(new[] { "bash", "-lc" }, r.Metadata?["shell"]);
}

// O2 — relative paths resolve against BaseDir, not the process cwd (#101).
[Fact]
public async Task RelativePathsResolveAgainstBaseDirNotProcessCwd()
{
    var baseDir = Directory.CreateTempSubdirectory("tn-base").FullName;
    var tools = BuiltinTools.Select(null, new BuiltinOptions { BaseDir = baseDir });
    await tools.Single(t => t.Name == "write").ExecuteAsync(new Dictionary<string, object?>
        { ["filePath"] = "sub/x.txt", ["content"] = "x" });
    Assert.Equal("x", File.ReadAllText(Path.Combine(baseDir, "sub", "x.txt")));
    Assert.False(File.Exists("sub/x.txt"));           // the control
}

// O3 — confinement decides on the canonical path (a per-component link walk),
//      and a brand-new file inside the base is still allowed.
[Fact]
public async Task ConfinementRefusesSymlinkEscapeButAllowsNewFileInsideBase()
{
    var baseDir = Directory.CreateTempSubdirectory("tn-base").FullName;
    var outside = Directory.CreateTempSubdirectory("tn-out").FullName;
    File.WriteAllText(Path.Combine(outside, "secret.txt"), "secret");
    Directory.CreateSymbolicLink(Path.Combine(baseDir, "link"), outside);
    var tools = BuiltinTools.Select(null,
        new BuiltinOptions { BaseDir = baseDir, ConfineToBaseDir = true });

    var escaped = await tools.Single(t => t.Name == "read").ExecuteAsync(
        new Dictionary<string, object?> { ["filePath"] = "link/secret.txt" });
    Assert.True(escaped.IsError);
    Assert.DoesNotContain("secret", escaped.Output);
    // CONTROL: the write case — not-yet-existing, inside the base, must be allowed.
    var ok = await tools.Single(t => t.Name == "write").ExecuteAsync(
        new Dictionary<string, object?> { ["filePath"] = "a/b/new.txt", ["content"] = "x" });
    Assert.False(ok.IsError);
}

// O4 — a timed-out command's grandchild does not outlive the call.
// (Serialised by AssemblyInfo.cs, so the wall-clock waits here are meaningful.)
[Fact]
public async Task TimeoutKillsTheWholeJobNotJustTheShell()
{
    var marker = Path.Combine(Directory.CreateTempSubdirectory("tn-kill").FullName, "MARKER");
    var bash = BuiltinTools.Select(null).Single(t => t.Name == "bash");
    var r = await bash.ExecuteAsync(new Dictionary<string, object?>
    {
        ["command"] = $"sleep 0.2; sh -c 'sleep 1; touch {marker}'",
        ["timeout"] = 300,
    });
    await Task.Delay(2000);
    Assert.False(File.Exists(marker), "the grandchild outlived the kill");
    Assert.Equal(true, r.Metadata?["timedOut"]);
    Assert.Equal(true, r.Metadata?["killedTree"]);
}
```

The option-bag spellings (`BuiltinOptions`, `BuiltinTools.create(Map)`) are placeholders for
whatever the change lands — the assertions are what matters, and each carries its control.

**Windows test coverage:** none of the four shapes above can assert the Windows half of its
obligation on CI as it stands (`.github/workflows/ci.yml` runs the suites on one platform).
The junction, 8.3-short-name, reserved-device-name and `taskkill`/`Kill(true)` behaviours stay
unverified for these two ports until a Windows runner with a JDK and the .NET SDK exists —
which is the same gap ADR 0034's Consequences and SPIKE §5 already name.

---

## What this report does NOT establish (by name)

1. **Every Windows behaviour, for both ports.** No JDK, no .NET SDK on the reachable Windows
   box. Specifically unmeasured: `%COMSPEC%` detection; `CreateProcess` name resolution and
   its error shape; whether `Files.toRealPath` and `FileSystemInfo.ResolveLinkTarget` see
   through a **directory junction**; 8.3 short-name normalisation; reserved device names
   (`CON`, `NUL`, …); `/`-separated listing paths (the `Replace('\\','/')` is a no-op on
   POSIX, so §4A's separator rule is untested in both ports); `Process.Kill(entireProcessTree:
   true)` on Windows; and the fact that **neither runtime has a graceful termination on
   Windows at all**, which makes the 2000 ms grace window POSIX-only there.
2. **Linux.** Nothing ran on Linux. `dash` was exercised on macOS as a stand-in for a
   Debian `/bin/sh`; the `/proc`-backed differences in `ProcessHandle.descendants()` and in
   `Kill(entireProcessTree: true)` (which reads `/proc` on Linux and `sysctl` on macOS) are
   untested.
3. **The real port code.** These probes measure the *runtime APIs* the fix would use, in
   isolation. They do not run `BuiltinTools.java` / `BuiltinTools.cs`. The SPIKE's Go arm ran
   the shipped port on Windows; nothing equivalent was done here, so "the port will behave
   this way" is an inference from the API measurements, not a measurement of the port.
4. **`ProcessHandle.descendants()` completeness under load.** `DESCENDANTS=2` was measured for
   one command shape at one moment. A process that forks after the snapshot is taken is not
   covered by it, and that race was not probed in either port.
5. **The `set -m` wrapper's side effects** (csharp O4b). It killed the job, but what it does to
   stdin/stdout inheritance, exit-code fidelity and interactive commands was not measured here.

---

## Raw output

### `bash spikes/builtin-host-boundary/ports/java/run.sh`

```

== O1 SHELL — argv prefix used verbatim; bare-name PATH resolution; failure shape
  bare_name_sh=exit=0 out="bare-ok"
  control_abs_bin_sh=exit=0 out="abs-ok"
  resolve_pwsh=THROWS java.io.IOException msg="Cannot run program "pwsh": Exec failed, error: 2 (No such file or directory) "
  resolve_powershell=THROWS java.io.IOException msg="Cannot run program "powershell": Exec failed, error: 2 (No such file or directory) "
  resolve_definitely-not-a-shell-xyz=THROWS java.io.IOException msg="Cannot run program "definitely-not-a-shell-xyz": Exec failed, error: 2 (No such file or directory) "
  non_executable_file=THROWS java.io.IOException msg="Cannot run program "/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/not-executable": Exec failed, error: 13 (Permission denied) "
  prefix_bash_lc=exit=0 out="bashism-ok"
  prefix_sh_c=exit=0 out="bashism-ok"
  prefix_dash_c=exit=127 out="dash: 1: [[: not found"
  prefix_4elem_env_shape=exit=0 out="1"

== O2 BASEDIR — relative resolution, empty base == today, apply_patch text, bash workdir, §4A separators
  empty_base_same_string=true ("tn-empty-base-probe.txt")
  empty_base_same_absolute=true
  empty_base_is_process_cwd=true
  control_nonempty_base_differs=true ("/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/base/tn-empty-base-probe.txt")
  relative_write_landed_under_base=true
  nothing_in_cwd=true
  absolute_unaffected=true
  patch_text_path_landed_under_base=true ([sub/added.txt])
  control_patch_unresolved_target=/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/java/sub/added.txt
  bash_workdir_default_base=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/base
  bash_workdir_explicit_wins=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/other
  control_bash_workdir_unset=/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/java
  sep_char="/"
  rel_emitted_with_base_root="sub/x.txt"
  rel_emitted_from_dot_root_is_cwd_relative="../../../../../../../../../../private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/base/sub/x.txt"
  base_changes_emitted_separator=false (separator is a platform fact, not a base fact)
  control_replace_on_backslash="sub/x.txt" (POSIX File.separator is '/', so the replace is a NO-OP here and this obligation is UNVERIFIED on Windows)

== O3 CONFINEMENT — canonicalise both sides, refuse escapes; what toRealPath does and throws
  toRealPath_existing=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/c-base/sub/in.txt
  toRealPath_missing=THROWS java.nio.file.NoSuchFileException msg="/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/c-base/sub/does-not-exist.txt"
  toRealPath_missing_NOFOLLOW=THROWS NoSuchFileException
  symlink_target=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/c-outside
  canonical_through_symlink=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/c-outside/secret.txt
  a_symlink_escape_contained=false  <- must be false
  control_real_inside_contained=true  <- must be true
  control_lexical_would_say_contained=true  <- the bug if you skip canonicalisation
  b_new_file_in_base_contained=true  <- must be true
  b_new_file_in_new_dirs_contained=true  <- must be true
  b_new_file_escape_contained=false  <- must be false
  b_new_file_through_symlink_contained=false  <- must be false (tail re-attached to the RESOLVED ancestor)
  control_naive_canon_new_file_escape=true  <- naive says contained; that is the inverted check
  fs_case_insensitive_lookup=true
  toRealPath_case_folds="/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262/c-base"
  c_upper_case_path_contained=true
  c_equals_is_case_sensitive_string_compare=false (Path.equals on this fs)
  string_prefix_says_contained=true  <- string prefix is WRONG
  startsWith_says_contained=false  <- Path.startsWith is right
  WINDOWS_junction=UNMEASURED on this host (no JDK on the Windows box)

== O4 KILL THE JOB — orphan marker (control/naive/tree) and TERM -> grace -> KILL
  orphan_control=ORPHAN_SURVIVED
  orphan_naive=ORPHAN_SURVIVED
  orphan_tree=killed_whole_job DESCENDANTS=2
  REACHABLE_AFTER_KILL=0  <- why the snapshot must be taken first
  reparent_marker_written=true
  grace_tree_grace=killed_whole_job DESCENDANTS=2 exited_in_grace=false
  grace_control_term_only=ORPHAN_SURVIVED DESCENDANTS=2 exited_in_grace=false
  signal_of_destroy=trap_saw=GOT_TERM exit=0  (128+15=143 => TERM, 128+9=137 => KILL)
  signal_of_destroyForcibly=trap_saw=(nothing) exit=137  (128+15=143 => TERM, 128+9=137 => KILL)
  grace_child_ignores_TERM=exited_in_grace=false elapsed_ms=2009 exit=137
  grace_child_honours_TERM=exited_in_grace=true elapsed_ms=0 exit=0

TMPDIR=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-java-probe18020952904981274262
```

### `bash spikes/builtin-host-boundary/ports/csharp/run.sh`

```

== O1 SHELL — argv prefix used verbatim; bare-name PATH resolution; failure shape
  bare_name_sh=exit=0 out="bare-ok"
  control_abs_bin_sh=exit=0 out="abs-ok"
  resolve_pwsh=THROWS System.ComponentModel.Win32Exception NativeErrorCode=2 msg="An error occurred trying to start process 'pwsh' with working directory '/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/csharp'. No such file or directory"
  resolve_powershell=THROWS System.ComponentModel.Win32Exception NativeErrorCode=2 msg="An error occurred trying to start process 'powershell' with working directory '/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/csharp'. No such file or directory"
  resolve_definitely-not-a-shell-xyz=THROWS System.ComponentModel.Win32Exception NativeErrorCode=2 msg="An error occurred trying to start process 'definitely-not-a-shell-xyz' with working directory '/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/csharp'. No such file or directory"
  non_executable_file=THROWS System.ComponentModel.Win32Exception NativeErrorCode=13 msg="An error occurred trying to start process '/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/not-executable' with working directory '/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/csharp'. Permission denied"
  probe_without_spawn_hint=no BCL API; PATH is walked by hand — sh=>/bin/sh | pwsh=>MISSING
  prefix_bash_lc=exit=0 out="bashism-ok"
  prefix_sh_c=exit=0 out="bashism-ok"
  prefix_dash_c=exit=127 out="dash: 1: [[: not found"
  prefix_4elem_env_shape=exit=0 out="1"

== O2 BASEDIR — relative resolution, empty base == today, apply_patch text, bash workdir, §4A separators
  empty_base_same_string=True ("tn-empty-base-probe.txt")
  empty_base_same_fullpath=True
  empty_base_is_process_cwd=True
  control_nonempty_base_differs=True ("/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/base/tn-empty-base-probe.txt")
  relative_write_landed_under_base=True
  nothing_in_cwd=True
  absolute_unaffected=True
  Path.Combine_discards_rooted_second_arg="/etc/passwd"  <- why IsPathRooted must be checked explicitly, not left to Combine
  patch_text_path_landed_under_base=True (sub/added.txt)
  control_patch_unresolved_target=/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/csharp/sub/added.txt
  bash_workdir_default_base=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/base
  bash_workdir_explicit_wins=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/other
  control_bash_workdir_unset=/Users/muthuishere/.herdr/worktrees/toolnexus/windows-support/spikes/builtin-host-boundary/ports/csharp
  dir_sep_char="/"
  rel_emitted_with_base_root="sub/x.txt"
  rel_emitted_with_relative_root_dot="../../../../../../../../../../private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/base/sub/x.txt"  <- GetRelativePath resolves '.' against the cwd
  control_replace_on_backslash="sub/x.txt" (POSIX separator is '/', so the Replace is a NO-OP here; UNVERIFIED on Windows)

== O3 CONFINEMENT — canonicalise both sides, refuse escapes; what GetFullPath/ResolveLinkTarget do
  GetFullPath_is_purely_lexical="/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/c-outside/secret.txt"
  GetFullPath_through_symlink_does_NOT_resolve="/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/c-base/link/secret.txt"  <- still shows .../c-base/link/...
  ResolveLinkTarget_on_the_link_itself=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/c-outside
  ResolveLinkTarget_on_path_THROUGH_a_link=null  <- null: the final segment is not itself a link, so a per-component walk is required
  ResolveLinkTarget_on_missing_path=THROWS System.IO.FileNotFoundException
  ResolveLinkTarget_on_non_link=null (a non-link is indistinguishable from a broken probe)
  canonical_through_symlink=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs/c-outside/secret.txt
  a_symlink_escape_contained=False  <- must be false
  control_real_inside_contained=True  <- must be true
  control_lexical_would_say_contained=True  <- the bug if you skip canonicalisation
  b_new_file_in_base_contained=True  <- must be true
  b_new_file_in_new_dirs_contained=True  <- must be true
  b_new_file_escape_contained=False  <- must be false
  b_new_file_through_symlink_contained=False  <- must be false
  control_naive_resolvelinktarget_says_contained=True  <- naive says contained: the inverted check
  fs_case_insensitive_lookup=True
  GetFullPath_case_folds="/PRIVATE/VAR/FOLDERS/CB/FW9_2_FD39N1JN_XM5R7Q1DH0000GN/T/TN-CS-PROBEJ19FVS/C-BASE"  <- GetFullPath does NOT case-fold
  ResolveLinkTarget_case_folds=null (not a link => no case folding either)
  c_upper_case_path_contained_with_OrdinalIgnoreCase=True  <- must be true
  control_ordinal_compare_would_say=False  <- Ordinal on a case-insensitive fs: two spellings of one directory disagree
  string_prefix_says_contained=True  <- string prefix is WRONG
  GetRelativePath_says_contained=False  <- right
  WINDOWS_junction=UNMEASURED on this host (no .NET SDK on the Windows box)

== O4 KILL THE JOB — orphan marker (control/naive/tree) and TERM -> grace -> KILL
  orphan_control=ORPHAN_SURVIVED
  orphan_naive=ORPHAN_SURVIVED
  orphan_tree=killed_whole_job
  REACHABLE_AFTER_KILL_children_of_dead_parent=exit=0 out="0"  <- a post-hoc walk finds nothing
  reparent_marker_written=True  <- the work carried on
  signal_of_Kill_false=trap_saw=(nothing) exit=137  (128+15=143 => TERM, 128+9=137 => KILL)
  signal_of_Kill_true=trap_saw=(nothing) exit=137  (128+15=143 => TERM, 128+9=137 => KILL)
  signal_of_CloseMainWindow=trap_saw=(nothing) exit=STILL_RUNNING CloseMainWindow_returned=False  (128+15=143 => TERM, 128+9=137 => KILL)
  signal_of_libc_SIGTERM=trap_saw=GOT_TERM exit=0 kill(2)_rc=0  (128+15=143 => TERM, 128+9=137 => KILL)
  grace_child_ignores_TERM=exited_in_grace=False elapsed_ms=2031 exit=137
  grace_child_honours_TERM=exited_in_grace=True elapsed_ms=0 exit=0
  gracetree_kill_child_only=ORPHAN_SURVIVED kill(pid,TERM)_rc=0
  gracetree_pgroup_setm=killed_whole_job pgid=23856 kill(-pgid,TERM)_rc=0
  gracetree_control_no_kill=ORPHAN_SURVIVED

TMPDIR=/private/var/folders/cb/fw9_2_fd39n1jn_xm5r7q1dh0000gn/T/tn-cs-probej19Fvs
```
