# Stress — the builtin host boundary under load, in all seven ports

Run 2026-09-22 on macOS 26.4 (arm64), after the ADR 0034 work landed and `origin/main` was merged
in. This is not the unit suite: each harness is a deliberate attempt to produce **orphans, leaks,
deadlocks and wrong verdicts** against the *shipped* builtins of its port.

One command per port:

```
go run   spikes/builtin-host-boundary/stress/golang        # go
node     spikes/builtin-host-boundary/stress/js/stress.mjs # js
python   spikes/builtin-host-boundary/stress/python/stress.py
bash     spikes/builtin-host-boundary/stress/{java,csharp,elixir,clojure}/run.sh
```

**Every scenario carries a control.** That is not ceremony: on this branch a probe has twice
reported a clean result while measuring nothing, and three of the harnesses below produced a false
verdict before their controls were added (§4).

## The scenarios

| | what it attacks |
|---|---|
| **S1** concurrent timeouts | 30 simultaneous `bash` calls, each with a detached grandchild, 300 ms timeout. Zero markers = every job died. Control: the same command at a 20 s timeout **must** write its marker. |
| **S2** mixed load | 20 successes + 20 timeouts interleaved. Each success must carry **its own** output — the test for results crossing under concurrency. |
| **S3** big output + timeout | 5 MB on stdout, then a sleep past the deadline. This is the shape that **deadlocks** when a killed child's pipes are held open by a grandchild — the reason `CombinedOutput` had to go. Control: the same 5 MB with a generous timeout must return all of it. |
| **S4** leaks | threads / child processes / file descriptors across repeated identical rounds. A *pool* plateaus; a *leak* does not. |
| **S5** confinement under load | 500+ concurrent resolves mixing legal paths with `../`, absolute-outside, symlink-through, and deep climbs. Zero escapes allowed, zero legal paths refused, and deterministic across runs. |
| **S6** (python only) | 20 concurrent calls **cancelled** mid-flight — the port whose kill has to reach a `Popen` created inside a worker thread. |

## Results

| port | S1 | S2 | S3 | S4 | S5 | notes |
|---|---|---|---|---|---|---|
| go | PASS | PASS | PASS `1005 ms` | PASS `fds 10/10/10` | PASS | 0 orphans of 30; 5 MB returned on the timeout path |
| js | PASS | PASS | PASS `1003 ms` | PASS `fds 23/23/23` | PASS | |
| python | PASS | PASS | PASS `1007 ms` | PASS `threads 23/23/23` | PASS | **S6 PASS**: 20/20 cancelled, 0 orphans |
| java | PASS | PASS | PASS | PASS | PASS | |
| csharp | PASS | PASS | PASS | PASS | PASS | |
| elixir | PASS | PASS | PASS `1.0 s` | PASS `ports 1/1/1` | PASS | was 3.05 s — see §3 |
| clojure (JVM) | PASS | PASS | PASS `1.07 s` | PASS `69→69→70→70→70` | PASS | was 3.05 s — see §3 |
| clojure (cljgo) | PASS | PASS | PASS | PASS | PASS | the Go host of the same `.cljc` |

**No defect was found in any port's source by the stress run.** The clojure question SPIKE §1.1 left
open — the one port that could not kill a job with the code it had — is answered: the pidfile +
snapshot-before-the-parent-dies fix holds at 30-way concurrency on **both** clojure hosts.

## 3. The one real finding: the grace window is not the caller's wall clock

Measured: a **1 s** timeout returned in ~1.0 s in go, js, python, java and csharp — and in **~3.05 s**
in elixir and clojure, which slept through the whole 2000 ms kill grace whether or not the job was
already dead. Nothing in `SPEC.md` pinned which it should be, so this was a genuine parity break
hiding behind a number nobody had compared.

Fixed in both: they now **wait for the job to go, up to the grace window**, rather than sleeping
through it (`kill -0` liveness poll, `tasklist` on Windows). Re-measured: elixir `wall=1.0s`,
clojure `wall=1.07s`. The rule — *the grace window bounds how long the kill may take, not how long
the caller waits* — is now stated in the code of both ports.

## 4. Three harness bugs, kept in the record because they are the method

1. **`....//x` is not a traversal.** `....` is a legal directory name, so joining it stays inside
   the base. The first go harness listed it as an escape and "found" a library bug that did not
   exist. Judge on **where the bytes landed**, not on the shape of the string.
2. **JVM pools are not a leak.** The clojure S4 first failed with `threads 64→70→75`. Every one of
   the extra threads was `process reaper` or `clojure-agent-send-off-pool-N`, idle-parked in a
   `SynchronousQueue` poll. Extended to five rounds, the count plateaus at 70 and the verdict is
   PASS. A three-round window cannot tell a pool from a leak.
3. **A control that cannot succeed proves nothing.** The S5 control first used `/etc/passwd` as its
   "escape allowed when confinement is off" case; the filesystem refuses that anyway, so the
   control could not distinguish a working check from a permission error. It now climbs to a
   writable directory.

## 5. What this did NOT establish

- **Windows stress: attempted, not completed.** A reduced Windows harness exists
  (`stress/win/main.go`: 20 concurrent timeouts, mixed load, and confinement in Windows spellings
  including `CON`/`NUL`/`COM1` and backslash climbs). Two runs over `agentbus` returned no output
  and the listener was `stale` afterwards (`last seen 35m ago`), so **nothing is claimed either
  way** — it needs the listener restarted on that machine. The *functional* Windows verification of
  go/js/python stands (SPIKE §6) and is unaffected.
- **java, csharp, elixir, clojure on Windows** — those runtimes are not installed there at all.
- **Linux.** Everything here is macOS.
- **Which kill step ended each job** (TERM vs KILL), peak fd counts rather than post-round counts,
  and cancellation as distinct from timeout in the six ports that do not have an S6.
