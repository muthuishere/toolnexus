# toolnexus — Clojure

**One `.cljc` source tree, two runtimes**: Clojure on the JVM
and [cljgo](https://github.com/muthuishere/cljgo) (Clojure hosted on Go, both
AOT-compiled and interpreted). Not two implementations that agree — *one*
implementation, byte-identical on both.

```clojure
;; deps.edn
net.clojars.muthuishere/toolnexus {:mvn/version "0.16.0"}   ; this port
net.clojars.muthuishere/koine     {:mvn/version "0.11.0"}    ; its only dependency
```

Released to Clojars as **`net.clojars.muthuishere/toolnexus`** — see
[`PUBLISHING.md`](../PUBLISHING.md). Build it locally with `clojure -T:build jar`;
the tooling sits behind the `:build` alias so it never touches the library's own
classpath.

**Zero reader conditionals in this tree.** Not "few" — zero. Every host
difference lives behind [koine](https://github.com/muthuishere/koine), the
portability seam, which is the only place a `#?(:clj … :cljgo …)` belongs. koine
is the only third-party dependency.

## Status

291 tests / 1100 assertions, 0 failures, in **all five** execution modes:

| runtime | how |
|---|---|
| Clojure (JVM) | `clojure -M -m toolnexus.test-main` |
| cljgo, AOT binary | `cljgo build && ./toolnexus-test` |
| cljgo, interpreted | `cljgo run src/run_tests.cljc` |

Verified against koine 0.11.0 and the **published cljgo v0.9.0 release
archive** — no source checkout, no `CLJGO_SRC`. cljgo #177 is closed, so these
numbers are re-runnable by anyone rather than only on the machine that produced
them. Verify against a RELEASED binary, not a local build: a rebuild-from-source
shim on PATH made this gate track an old checkout for a while, and it reported a
mode as blocked upstream that no released cljgo has ever failed.

Two gates beyond the suite:

```sh
./all-modes-check.sh       # the suite in ALL FIVE ways this port can be executed
./jvm-only-check.sh        # can someone with ONLY Clojure use this? (no cljgo)
./cljgo-gate.sh            # both cljgo legs + a DIFF between them; also a downstream gate for cljgo CI
./consumer-exit-check.sh   # does the library let a consumer's process exit?
./deps-purity-check.sh     # is koine really the only dependency? (transitive classpath)
```

**Five execution modes, all green** — because "works on Clojure and on cljgo" is
five paths, not two, and the REPLs are where a human actually meets a library:

| mode | how |
|---|---|
| `jvm-main` | `clojure -M -m toolnexus.test-main` |
| `jvm-repl` | forms piped into `clojure -r` |
| `cljgo-aot` | `cljgo build` → native binary |
| `cljgo-run` | `cljgo run src/run_tests.cljc` |
| `cljgo-repl` | forms piped into `cljgo repl` |

All three have been **watched fail** before being trusted — see the header of
each. The spikes run the same way: `spikes/sNN-*/run-both.sh` diffs all three
modes byte for byte, and all ten pass on this stack.

## Do I need cljgo?

**No.** If you have a JDK and the Clojure CLI and have never heard of cljgo, this
is an ordinary Clojure library: `deps.edn` plus `src/`, one dependency (koine),
nothing else. No `build.cljgo`, no compiled binary, no cljgo artifacts of any
kind are required or consulted.

That is enforced, not asserted. `./jvm-only-check.sh` copies the source with
every cljgo artifact deleted and puts a **poisoned `cljgo` first on PATH** — a
script that prints `POISON` and exits 127 if anything invokes it. Removing cljgo
from PATH would not be enough, because on a typical machine it sits in the same
directory as `clojure` itself, and *"I didn't call it"* is not the same as
*"it was not called"*.

Result: 291 tests / 1100 assertions, 0 failures, **no POISON line**. The example
in `examples/minimal` runs the same way, with `app/main.cljg` sitting in the
same directory being correctly ignored — the JVM never looks for that extension.

The reverse holds too: a cljgo user needs no JDK. `all-modes-check.sh` covers
that side by running the AOT binary and both cljgo evaluators.

## What's implemented

| SPEC | |
|---|---|
| §0.1–0.2 | `Tool` / `ToolResult`, `sanitize`, naming |
| §0.3, §2 | MCP — stdio **and** streamable-HTTP, per-source isolation, status/error maps |
| §0.5, §0.6, §3 | agent skills, byte-exact `skill` tool output, skills prompt |
| §0.7 | OpenAI / Anthropic / Gemini adapters |
| §0.8, §0.9 | native and HTTP tools |
| §0.10, §8 | the client loop — both provider styles, parallel tool calls, event sink |
| §0.11, §4A | builtins and the precedence rule (MCP wins a name collision) |
| §0.12, §10 | suspension — `pending`, `wait-for`, resume |
| §7A, §7B, §7C | A2A outbound, `serve` inbound (Agent Card + JSON-RPC), MCP server inbound |

## Parity — tier `full`

This port is held to tier **`full`** in `conformance/check_options_parity.py`, the same as the
six shipped ports: every logical client and toolkit option present, **zero permitted absences**.

```
Option parity OK: 18 client + 12 toolkit options across 7 ports (7 at tier full, 0 at tier core)
```

The tier is declared in the shared manifest, never by this port about itself — a self-graded
exam is not a gate, and lowering the bar has to be a visible diff the other ports review.

**Still absent, and the option gate cannot see any of it** — it compares option NAMES in two
files, so a missing subsystem has no names to compare:

| capability | JS module | state here |
|---|---|---|
| agent runtime (§7D) | `agents/runtime.ts` | absent |
| subagents (§7D `task`) | `agents/agent.ts` | absent — needs the runtime |
| context compaction (§7F) | `agents/compaction.ts` | **shipped** — `toolnexus.agents.compaction` |
| agent home (§7E) | `agents/home.ts` | **partly** — `compose-soul` + the `memory` tool ship; `from-dir` and `start-agent` need the runtime |

This is what holds the port back from Clojars. It is not a tier downgrade and not
a quality gap in what exists — it is four subsystems that are not written yet, and
publishing a port that silently lacks them would make "byte-identical across seven
languages" false for anyone who reached for an agent team.

Note `:agents` here is the **A2A** option — remote agents behind an Agent Card — which is a
different capability from `openspec/specs/subagents` and does not satisfy it.

**MCP elicitation (§2/§10) is implemented over stdio only.** A streamable-HTTP peer cannot hold
`tools/call` open for a server→client reverse request: `koine.http/request` buffers the whole
body, and `koine.stream/sse-post` is incremental but exposes no response headers — where MCP's
`Mcp-Session-Id` lives. A consumer must choose streaming or the session id. Raised upstream.

Also absent: per-server tool allowlists, real SSE streaming (the loop buffers and emits no text
deltas — faking them out of a buffered body would be a lie), and durable resume.

## Three things this port learned the hard way

**1. Never `future` in library code.** Clojure's future pool threads are
non-daemon with a 60-second keep-alive, so a library that spawns one holds its
*consumer's* process open long after the program is done. Measured here: a
consumer program went from **61.6s to 1.19s** with identical output. Use
`koine.process/run-async!` (a daemon thread on the JVM, a goroutine on cljgo).
`(shutdown-agents)` is the fix for an *application* that owns its process — this
port's test runner calls it — and a bug in a library, which may never decide
when its host program exits.

The suite never caught it, and could not have: it owns its process and shuts the
pool down. Hence `consumer-exit-check.sh`, which measures from outside.

**2. Never shadow a `clojure.core` name — and the surface is bigger on cljgo.**
`clojure.core/ok` and `clojure.core/err` exist on cljgo and *not* on the JVM, so
this port shipped `tool/ok` and `tool/err` for a while with no warning anywhere.
They are `tool/success` and `tool/failure` now. Check both hosts before taking a
name:

```clojure
(resolve 'clojure.core/<name>)   ; must be nil on cljgo AND on the JVM
```

(cljgo v0.8.4 closed 77 such extras and added a ratchet test against JVM 1.12.5,
so this is shrinking — but check anyway.)

**3. A cross-host diff is not a correctness check.** Two hosts agreeing proves
they agree; it says nothing about whether both are wrong. This port measured a
skill payload at 1127 bytes on all three runtimes when the right answer was 995,
and koine 0.7.2 shipped a key-ordering bug that was consistent everywhere. Only
an EXTERNAL authority closes that class — the other ports, or a value derived
from the spec rather than from our own output. Never snapshot an expected value
from your own run: that enshrines whatever defect produced it as a regression
test defending the bug.

**4. On cljgo, assert on output, never on the exit code.** Exit 0 means nothing
threw, not that anything ran: a suite collecting zero tests still exits 0 and
looks green forever. `toolnexus.test-main` gates on a *count floor* and prints a
machine-readable verdict; both gate scripts refuse a run that produces no
verdict line.

## Host-specific entry points, by file extension

Shared logic is `.cljc`. When an entry point genuinely differs, the *file
extension* selects the host — `.clj` for the JVM, `.cljg` for cljgo — rather
than a reader conditional inside a shared file. See `examples/minimal/`, which
is one `app/core.cljc` plus `app/main.clj` and `app/main.cljg`, run both ways by
`examples/minimal/run-both.sh`.

## Layout

```
src/toolnexus/
  tool.cljc         Tool + ToolResult + toolkit   (a plain map and a closure —
                    no protocol, no record, no deftype)
  frontmatter.cljc  the strict SKILL.md front-matter subset (throws outside it)
  mcp.cljc          §2 — transport-as-data: {:rpc! fn :close! fn}
  skill.cljc        §3 — discovery, progressive disclosure, byte-exact output
  adapter.cljc      §0.7 — OpenAI / Anthropic / Gemini
  native.cljc       §0.8      http.cljc     §0.9
  builtin.cljc      §4A       client.cljc   §0.10/§8/§10
  a2a.cljc          §7A       serve.cljc    §7B + §7C
  core.cljc         the integration layer — registration ORDER is the contract
  test_main.cljc    the count-gated entry point (under src/, deliberately:
                    `cljgo run`/`build` resolve requires from the ENTRY's root)
spikes/             ten feasibility spikes, each with an honest README
examples/minimal/   the .cljc + .clj + .cljg shape, run on both hosts
```

## Running against the shared fixtures

The cross-language fixtures in `../examples/` are authoritative and shared — do
not fork a Clojure copy.

```sh
export TN_EXAMPLES=$(cd .. && pwd)/examples
clojure -M -m toolnexus.test-main
```
