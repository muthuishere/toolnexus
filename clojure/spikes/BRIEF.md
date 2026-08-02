# Spike brief — read this before writing a line

Every spike in this directory answers ONE question: **does this part of the
toolnexus contract work, in portable Clojure, identically on Clojure (JVM) and
on cljgo?** A spike that only runs on the JVM has not answered its question.

## Non-negotiables

1. **One `.cljc`, ZERO reader conditionals.** No `#?(...)` anywhere in your
   source. koine is the only place a reader conditional belongs. If you find
   something you genuinely cannot write without one, that is a **finding** —
   report it, do not work around it silently.
2. **No `java.*` anything.** No `(Exception. "x")`, no `Thread/sleep`, no
   `java.io.File`. Throw `ex-info`, catch `Throwable` (a bare symbol —
   portable on both hosts). Sleep is `koine.time/sleep!`.
3. **Never shadow a `clojure.core` name** with a `defn`/`def`. cljgo's static
   Java-interop scan reads a bare `proxy`/`err`/`deftype`-shaped symbol as the
   JVM special form and rejects **the whole namespace**, not just that fn.
4. **Hermetic.** No live LLM, no API key, no internet. The only network is
   `127.0.0.1`. Serve fakes with `koine.server` (`{:port 0}`, then
   `koine.server/port`). The one exception already blessed: `npx -y
   @modelcontextprotocol/server-everything` as a real MCP stdio child.
5. **Secrets are use-only.** Never print a header value or an env value. Report
   only shapes: which keys exist, whether `${ENV}` expansion changed anything.

## Everything comes from koine

```clojure
net.clojars.muthuishere/koine {:mvn/version "0.4.2"}   ;; JVM and cljgo alike
```

`koine.json` (`write-str` sorts keys — this is what makes byte-comparison
possible), `koine.fs`, `koine.env`, `koine.http` (`request`, `post-json`,
`failed?` — transport failures are DATA `{:status nil :error :timeout|:dns|
:connect-failed|:transport}`, never a throw), `koine.process` (`sh`, `spawn`,
`send-line!`, `read-line!`, `alive?`, `close!`), `koine.server` (`serve`,
`port`, `stop!` — one path, one handler; dispatch on `(:path req)` yourself),
`koine.stream`, `koine.time`, `koine.codec`, `koine.host` (`id`, `supports?`).

**No other third-party dependency.** That is the whole point.

## Project shape — copy `s15-spec0-slice`

```
sNN-your-spike/
  deps.edn                     ;; JVM:   clojure -M -m toolnexus.<ns>
  build.cljgo                  ;; cljgo: cljgo build run
  run-both.sh                  ;; runs all three modes and DIFFS them
  README.md                    ;; question, verdict, findings
  src/toolnexus/<ns>.cljc      ;; the spike, with a -main that prints ONE json line
  src/run_interpreted.cljc     ;; (require '...) (…/-main)  — see trap 1
```

Your `-main` must print exactly **one line of JSON** built with
`koine.json/write-str`, and nothing else, so the three runs can be diffed byte
for byte. Put nothing non-deterministic in it — no ports, no timestamps, no
durations, no UUIDs, no absolute temp paths. `:host` may differ (it is stripped
before the diff); nothing else may.

## Three cljgo traps that will cost you an hour each

1. **`cljgo run <file>` does NOT call `-main`.** It evaluates top-level forms
   and exits 0 having printed nothing — indistinguishable from success. Hence
   `src/run_interpreted.cljc`. `cljgo build` binaries DO call `-main`, so the
   two modes disagree about what your program is.
2. **On cljgo, assert on OUTPUT, never on the exit code.** Exit 0 means nothing
   threw, not that the thing happened. (`cljgo test` reports `Ran 0 tests` and
   exits 0 when it collects nothing.)
3. **The AOT discovery pass evaluates your Clojure with `nil` for every host
   result**, so a nil-intolerant pure fn on a host value fails at *build* time,
   not run time.

## Run all three modes, always

```
clojure -M -m toolnexus.<ns>          # JVM
cljgo build run                       # cljgo, AOT binary
cljgo run src/run_interpreted.cljc    # cljgo, interpreted
```

cljgo's own ADR 0007 calls a REPL-vs-binary divergence unforgivable and
toolnexus ships binaries, so proving one mode proves the wrong one.

`TN_EXAMPLES` points at the repo's shared `examples/` directory when your spike
needs the fixtures (`mcp.json`, `skills/hello-world/`). Use the **shared**
fixtures — never a Clojure-specific copy.

## Your README must be honest

State the question, the verdict, the exact numbers, and **what you did not
cover**. If something failed, say so with the error. A spike that reports
success it did not measure is worse than no spike.

## Rule 3, sharpened (measured 2026-07-31)

Non-negotiable #3 said "never shadow a `clojure.core` name." The surface is
**bigger on cljgo than on the JVM**, and that is the dangerous part:

```
(resolve 'clojure.core/ok)   ; cljgo => #=(var clojure.core/ok)   JVM => nil
(resolve 'clojure.core/err)  ; cljgo => #=(var clojure.core/err)  JVM => nil
```

`toolnexus.tool` shipped `ok`/`err` for exactly this long before it was caught,
because **the JVM never warns**. Check a name on BOTH hosts before you take it:

```clojure
(resolve 'clojure.core/<name>)   ; must be nil on cljgo AND on the JVM
```

`clojure.core/await` (banned in §10) exists on both, so it announces itself.
`ok`/`err` exist on only one, so they do not. The second kind is worse.
