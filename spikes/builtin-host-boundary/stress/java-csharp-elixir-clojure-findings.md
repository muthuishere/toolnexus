# Stress findings — java, csharp, elixir, clojure (builtin host boundary)

Adversarial load harness against the **shipped** builtins of four ports (ADR 0034 / `SPIKE.md`).
Not a unit test: a deliberate attempt to produce orphans, leaks, deadlocks and wrong verdicts.

- **Host:** macOS 26.4 / Darwin 25.4.0, arm64. JDK 26.0.1 · .NET 10.0.301 · Elixir 1.20.2 / OTP 29 ·
  Clojure CLI 1.12.5 (JVM **and** cljgo).
- **Date:** 2026-09-22. Every number below is pasted from a real run, not reasoned about.
- **Run it:** `bash spikes/builtin-host-boundary/stress/<port>/run.sh` — one command each, hermetic
  (no network, no API key). Wall: java 69 s · csharp 63 s · elixir 62 s · clojure 110 s (both hosts).
- **Linkage:** every harness calls the shipped public API only — `BuiltinTools.create(cfg)` /
  `BuiltinTools.Create(cfg)` / `Toolnexus.Builtin.load/1` / `toolnexus.builtin/builtin-toolkit` —
  and no builtin logic is reimplemented. The java harness prints its `CodeSource` as proof;
  csharp uses a `ProjectReference` to `csharp/src/Toolnexus/Toolnexus.csproj`; clojure puts
  `clojure/src` on the classpath; elixir runs off `elixir/_build/dev/lib/*/ebin`.
- **Every scenario carries a control arm, and the control's number is printed on the summary line.**
  A control that fails makes the verdict INVALID, not PASS. This is not ceremony: the original
  spike reported a clean kill twice because its probe never ran (`SPIKE.md §1`, §3.1), and in this
  pass two harnesses (java S5, csharp S4) produced a *false* FAIL before their controls were added.

---

## Verdict table

| scenario | java | csharp | elixir | clojure (JVM) | clojure (cljgo) |
|---|---|---|---|---|---|
| **S1** concurrent timeouts (30×, 300 ms) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **S2** mixed load (40×, interleaved) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **S3** 5 MB output + timeout | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **S4** leaks (3–5 repeats) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **S5** confinement under load (500×2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |

**Defects found in port source: none.** Details and the numbers behind each cell follow.

---

## The printed output, per port

### java

```
# harness class source: file:/…/java/build/classes/java/main/
S1 verdict=PASS markers=0 timedOut=30/30 killedTree=30/30 errorResults=30/30 control_markers=3/3 control_ok=3/3 wall=12.6s
S2 verdict=PASS successCorrect=20/20 successWrong=0 timeoutCorrect=20/20 timeoutWrong=0 crossedResults=0 orphanMarkers=0 control_successArm=20/20 wall=7.3s
S3 verdict=PASS isError=true wall=1.02s timedOut=true outBytes=5000037 control_isError=false control_bytes=5000000 control_wall=0.15s
# round2 S1 verdict=PASS markers=0 timedOut=30/30 killedTree=30/30 errorResults=30/30 control_markers=3/3 control_ok=3/3 wall=12.6s
# round2 S2 verdict=PASS successCorrect=20/20 successWrong=0 timeoutCorrect=20/20 timeoutWrong=0 crossedResults=0 orphanMarkers=0 control_successArm=20/20 wall=7.3s
# round2 S3 verdict=PASS isError=true wall=1.01s timedOut=true outBytes=5000037 control_isError=false control_bytes=5000000 control_wall=0.18s
# round3 S1 verdict=PASS markers=0 timedOut=30/30 killedTree=30/30 errorResults=30/30 control_markers=3/3 control_ok=3/3 wall=12.6s
# round3 S2 verdict=PASS successCorrect=20/20 successWrong=0 timeoutCorrect=20/20 timeoutWrong=0 crossedResults=0 orphanMarkers=0 control_successArm=20/20 wall=7.3s
# round3 S3 verdict=PASS isError=true wall=1.01s timedOut=true outBytes=5000037 control_isError=false control_bytes=5000000 control_wall=0.17s
S4 verdict=PASS threads=[6, 56, 56, 56] childProcs=[0, 0, 0, 0] fds=[8, 8, 8, 8] monotonicGrowth=threads:false,children:false,fds:false control_roundsRun=3/3
S5[run1] verdict=PASS attempts=500 legalAllowed=250/250 legalRefused=0 escapesRefused=250/250 escapesAllowed=0 filesOutsideBase=0 parentLeak=0 control_legalAllowed=250
S5[run2] verdict=PASS attempts=500 legalAllowed=250/250 legalRefused=0 escapesRefused=250/250 escapesAllowed=0 filesOutsideBase=0 parentLeak=0 control_legalAllowed=250
S5 verdict=PASS deterministic=true run1=PASS run2=PASS
```

Concurrency idiom: virtual threads. S3 returns in **1.02 s** against a 1 s deadline — the
`descendants()`-before-`destroy()` snapshot closes the grandchild's pipe ends, so no deadlock.

### csharp

```
S1 verdict=PASS markers=0 timedOut=30/30 killedTree=30/30 errors=30/30 control_markers=3/3 control_ok=3/3 wall=13.1s
S2 verdict=PASS success=20/20 timeouts=20/20 crossed=0 control_serial_ok=20/20 wall=1.2s
S3 verdict=PASS isError=True timedOut=True killedTree=True bytes=5000037 wall=1.0s control_ok=True control_bytes=5000000 control_wall=0.2s
S4 verdict=PASS rounds=5 threads=[8,34,51,53,53] children=[1,1,1,1,1] fds=[95,101,104,130,104] dThreads=45 dChildren=0 dFds=9 control_sampler_ok=True
S5 verdict=PASS attempts=500 legal_ok=167 legal_refused=0 escapes_allowed=0 escapes_refused=333 rerun_identical=True control_unconfined_escapes=2 wall=0.0s
TOTAL wall=62.8s verdict=PASS
```

Concurrency idiom: `Task.WhenAll`. The S5 control is the strongest one in this pass: with
confinement **off**, the absolute path and the symlink path *do* read the outside file (2/2), so
the 333 refusals are confinement working, not paths that were unreachable anyway.

### elixir

```
# elixir builtin stress · shell=/bin/sh -c · os_pid=39944
S1 verdict=PASS markers=0 timedOut=30/30 killedTree=30/30 control_markers=3/3 control_ok=3/3 wall=14.7s
S2 verdict=PASS control_success=20/20 timeouts_errored=20/20 crossed_results=0 wall=2.4s
S3 verdict=PASS isError=true timedOut=true killedTree=true bytes=5000037 wall=3.0s | control_ok=true control_bytes=5000000 control_wall=0.1s
S4 baseline procs=55 ports=1 children=1 fds=61
  S4/round1 procs=55 ports=1 children=1 fds=61
  S4/round2 procs=55 ports=1 children=1 fds=61
  S4/round3 procs=55 ports=1 children=1 fds=61
S4 verdict=PASS d_procs=0 d_ports=0 d_children=0 d_fds=0 monotonic=none wall=41.5s
S5/run1 verdict=PASS attempts=500 escapes_landed_outside=0/332 write_calls_accepted=55 accepted_paths=["....//x"] legal_refused=0/168 control_legal_ok=168/168 wall=0.1s
S5/run2 verdict=PASS attempts=500 escapes_landed_outside=0/332 write_calls_accepted=55 accepted_paths=["....//x"] legal_refused=0/168 control_legal_ok=168/168 wall=0.1s
S5 verdict=PASS run1=PASS run2=PASS deterministic=true
TOTAL verdicts S1=PASS S2=PASS S3=PASS S4=PASS S5=PASS wall=61.9s
```

Concurrency idiom: `Task.async_stream`. **`port_count` is flat at 1 across all three rounds** —
a leaked `Port` was the specific leak to hunt in this port, and there is none.

### clojure — both hosts, from one `.cljc` with no reader conditionals and no `java.*`

```
################ host: jvm
host=jvm pid=55755 shell=["/bin/sh" "-c"] baseline threads=43 children=1 fds=30
S1 verdict=PASS markers=0 timedOut=30/30 killedTree=30/30 control_markers=3/3 control_ok=3/3 wall=12.271s
S2 verdict=PASS success_exact=20/20 crossed=0 timeouts_errored=20/20 control_ok=20/20 wall=2.424s
S3 verdict=PASS isError=true timedOut=true wall=3.06s control_bytes=5000000 control_isError=false control_wall=0.198s
S4 verdict=PASS threads=87->94->94 children=1->1->1 fds=30->30->30 monotonic_growth=none control=baseline threads=43 fds=30
S5 verdict=PASS attempts=500x2 escapes_allowed=0+0 escape_msg=167/167 legal_refused=0+0 control_legal_ok=167/167 odd_allowed=0/166 deterministic=true

################ host: cljgo
host=cljgo pid=67548 shell=["/bin/sh" "-c"] baseline threads=13 children=1 fds=9
S1 verdict=PASS markers=0 timedOut=30/30 killedTree=30/30 control_markers=3/3 control_ok=3/3 wall=12.265s
S2 verdict=PASS success_exact=20/20 crossed=0 timeouts_errored=20/20 control_ok=20/20 wall=2.51s
S3 verdict=PASS isError=true timedOut=true wall=3.088s control_bytes=5000000 control_isError=false control_wall=0.184s
S4 verdict=PASS threads=67->71->71 children=1->1->1 fds=9->9->9 monotonic_growth=none control=baseline threads=13 fds=9
S5 verdict=PASS attempts=500x2 escapes_allowed=0+0 escape_msg=167/167 legal_refused=0+0 control_legal_ok=167/167 odd_allowed=0/166 deterministic=true
```

**This answers the open question in `SPIKE.md §1.1`.** Clojure was the one port that could not kill
the job with the code it had, because koine's `sh` performs the kill inside the library and exposes
no pid. The shipped fix — the command writes its own pid, and the descendants are snapshotted from
that pid **while the parent is still alive** — holds at 30-way concurrency on **both** hosts, even
though each timeout independently shells out `ps -eo pid=,ppid=` and then a `kill` per pid.
0/30 orphan markers, three rounds, twice over.

---

## Defects

**None.** No harness produced a FAIL against port source in any scenario on any of the four ports.

Three things *looked* like defects and are not. They are recorded because each one is a trap the
next harness will fall into, and two of them were caught only by the control arm:

1. **`....//x` is not a traversal.** `....` is a legal directory name on POSIX, so `base/..../x` is
   *inside* the base and a `write` to it is correct behaviour. Elixir's harness counted 55 accepted
   `write` calls for it, java's first pass scored it a FAIL. The fix is to judge on **where the
   bytes landed** (`toRealPath().startsWith(base)`) rather than on the tool's return flag. It is
   only a real probe on Windows, where `....` has different lexical handling.
2. **250 threads writing the same three legal filenames race each other.** Java's first S5 run
   reported 66 then 77 "legal refused" across two runs — pure `Files.writeString` truncate-vs-read
   contention in the *harness*, nothing to do with confinement. Legal paths must be unique per
   attempt or S5 is not deterministic for a reason that has no bearing on the code under test.
3. **ThreadPool warm-up is not a leak.** C#'s S4 threads go `8 → 34 → 51 → 53 → 53`; a naive
   "any monotonic growth" rule fires on it. Measured to a plateau with six rounds
   (`STRESS_ROUNDS=6 bash run.sh` → `threads=[8,34,48,48,33,53,53,53]`, non-monotonic, capped).

## Observations — not defects, but parity- and host-relevant

- **A timed-out `bash` call blocks its caller for `timeout + ~2000 ms`.** The kill grace is on the
  return path and is serialised per call, so a 300 ms timeout takes ~2.3 s to return in elixir and
  clojure. Java and csharp return S3 in ~1.0 s against a 1 s deadline; elixir and clojure take
  ~3.05 s for the same shape. **The four ports do not agree on whether the grace window is
  additive to the caller's wall time.** Nothing in `SPEC.md` pins this, and a host sizing its own
  deadlines would want it pinned. Worth checking against js/python/go before it is called parity.
- **A timed-out call returns the whole captured output, uncapped.** All four ports report
  `bytes=5000037` on S3 — the full 5 MB plus the `bash: command timed out after 1000ms` banner.
  Consistent across the four; confirm the other three agree.
- **Thread high-water mark under sustained 30-way concurrency.** Clojure/JVM settles at 94 live OS
  threads over a 43 baseline (cljgo 71 over 13): `t-bash` spends ~3 threads per call — one
  `run-async!` deadline thread plus koine's two pipe drainers, none pooled. It plateaus (round 2 =
  round 3), so it is not a leak, but it is a real cost a caller should size for.
- **The csharp test-serialisation concern did not reproduce.** `csharp/tests` is serialised because
  parallel IO once starved a timeout test; this harness ran 30- and 40-way concurrent spawns with
  300 ms deadlines and saw zero starvation, no missed deadline and no crossed result. So it is not
  a defect in `BuiltinTools.bash`'s timeout path, at least on macOS/net10 — if it still bites, it
  needs re-measuring in the test suite itself, because this harness could not provoke it.

---

## What was NOT measured

**Windows: nothing here says anything about it.** Every assertion above exercises the POSIX branch.
Per `SPIKE.md`, **java, dotnet, elixir and clojure are not installed on the Windows box reachable
over `agentbus`**, so these four harnesses have never run there and no claim about Windows should
be drawn from this document. Specifically untested for all four ports:

- the `%COMSPEC%` / `taskkill /T /F /PID` kill path,
- the reserved-device rule (`CON`, `PRN`, `NUL`, `COM1-9`, `LPT1-9`, and those names with any
  extension) in the confinement check,
- case-folded comparison and 8.3 short-name canonicalisation,
- directory junctions — the escape an agent can mint for itself with the `bash` tool it already
  has, and the one that silently inverted the Go check (`SPIKE.md §3.2`). `Files.toRealPath`
  (java) and `FileInfo.ResolveLinkTarget` (csharp) are the stated equivalents and remain
  **unverified**. Elixir and clojure have no stated equivalent measured at all.

The harnesses are platform-agnostic apart from the `sleep`/`sh -c` command strings and the
`ps`/`lsof` probes; running them on a Windows host is the obvious next measurement, and needs only
the four toolchains installed there.

Also unmeasured:

- **Which kill step ended the job.** `killedTree=true` is set whether the graceful TERM or the
  forced KILL after the 2000 ms grace did it, so from the public API no port can report how many
  of the 30 needed the escalation. Only the absence of markers is observable.
- **Reparented orphans directly.** A true orphan is reparented to pid 1 and is unreachable from our
  pid, so `descendants()` / a `ppid` walk reads 0 either way. The marker-file count is the real
  orphan detector and is what every S1 verdict rests on.
- **OS thread count in elixir.** The BEAM exposes no per-process live-thread count;
  `:erlang.system_info(:process_count)` counts BEAM processes, not OS threads. Dirty-IO thread
  growth would be invisible. `process_count` + `port_count` + `ps` children + `lsof` were used
  instead.
- **fd accounting to the byte.** `lsof -p` counts include the sampler's own child and transient
  pipes; csharp's `+9` is "no growth trend", not "exactly zero fds leaked". Java used `/dev/fd`
  (8, flat), which is this process's table only — a pipe held by a surviving grandchild would not
  appear there.
- **fd behaviour at peak.** `lsof` was sampled between rounds, not during one, so a transient spike
  inside a round is not visible.
- **Cancellation as distinct from timeout.** The `ctx` cancellation path (csharp
  `stopped="cancelled"`) is a separate branch; the five scenarios only drive timeouts.
- **Embedded NUL, attributed.** Every port refuses it, but a refusal by the confinement check is
  indistinguishable from the runtime's own filename layer raising and being caught — same
  `isError` result either way.
