# minimal — one `.cljc`, a `.clj`, a `.cljg`, and koine

The smallest honest demonstration of the port's premise:

| file | who reads it |
|---|---|
| `src/app/core.cljc` | **both runtimes** — the shared code, koine its only dependency |
| `src/app/core_test.cljc` | **both runtimes** — one suite, same assertions |
| `src/app/main.clj` | Clojure (JVM) only — the JVM reader never sees `.cljg` |
| `src/app/main.cljg` | cljgo only — cljgo prefers `.cljg` over `.clj` |
| `deps.edn` | the JVM project, koine from Clojars |
| `build.cljgo` | the cljgo project, **the same koine artifact** from Clojars |

The entry point is host-specific **by file extension**, not by a reader
conditional. Everything of substance is in `core.cljc`, which contains no
`#?(...)`, no `java.*` and no Go interop — and still does JSON, environment,
files and a real subprocess, because koine is the seam.

```bash
./run-both.sh
```

```
== app
  jvm == cljgo/aot     (214 bytes)
  jvm == cljgo/interp  (214 bytes)
== tests (the same src/app/core_test.cljc, three ways)
  jvm           tests=3 assertions=9 fail=0 error=0
  cljgo/aot     tests=3 assertions=9 fail=0 error=0
  cljgo/interp  tests=3 assertions=9 fail=0 error=0
PASS
```

Same bytes on both runtimes apart from `"runtime":"jvm"` vs `"runtime":"cljgo"`.

## The gate, and why it is not decoration

`app.test-main` runs the suite in-process and reads the summary map that
`run-tests` returns, then **fails on a zero test count**. On cljgo, exit 0 means
nothing threw — not that anything ran — so a suite that collects nothing would
otherwise look green forever.

The gate is not hypothetical: it caught two real failures while this example was
being written, both of which a `$?` check would have passed.

## What building this shook out

1. **`cljgo run` / `cljgo build` resolve requires relative to the entry file's
   own root.** An entry under `src/` therefore cannot `require` a namespace
   living in `test/` — `could not locate namespace app.core-test`. Because this
   example's gate entry (`src/app/test_main.cljc`) requires the suite, the suite
   lives under `src/`.

   **This is not "cljgo cannot see `test/`."** `cljgo test` walks both trees:
   measured here, it collected **6 tests / 18 assertions** from a suite in `src/`
   plus one in `test/`. The real constraint is the same runtime-vs-test
   classpath split the JVM has — a *require* crosses it, a *test walk* does not.
   (Corrected 2026-07-31; the first version of this README stated the broader
   claim, and koine disproved it with a counter-measurement.)
2. **Two `exe` calls in one `build.cljgo` corrupt the SECOND binary**, whichever
   it is (cljgo 0.1.0-dev, measured 2026-07-31). App-then-test produced a test
   binary with 5 errors; test-then-app produced an app binary that died on
   `cannot call unbound var: #'app.core/report`. One `exe` per build file — hence
   `aot-test/build.cljgo`.
3. **koine 0.5.0 removed `koine.host/tier`.** With two supported hosts and one
   tier the var described nothing. This example was written against 0.4.2 and
   updated; anything still printing `host/tier` will not compile on 0.5.0.

## Limits

Two runtimes only (koine 0.5.0 targets JVM and cljgo). The app writes to `/tmp`.
Nothing here is a conformance test — for that see `../../spikes/`, where each
toolnexus capability is spiked separately and diffed byte for byte.
