# S03 — is there ANY dual-host Clojure library, or must we build the seam?

**Question:** the port needs JSON, HTTP, subprocess and fs. A Clojars library is
usable on cljgo only if its Clojure source carries **no Java interop**. Does a
usable dual-host ecosystem already exist, or does the seam have to be written?

**Answer: it has to be written. 11 of 11 popular libraries are JVM-only.**
Re-run 2026-08-01; the result has not moved.

```sh
./scan.sh     # unzips every jar on the classpath and reports interop hits
```

| jar | files | interop |
|---|---|---|
| data.json 2.5.1 | 1 | imports:1 statics:5 — `java.io.Reader`, `java.io.Writer`, `java.lang.Double` |
| edamame 1.4.27 | 8 | imports:1 statics:2 — `java.io.Reader`, `java.lang.Character` |
| medley 1.8.1 | 2 | `java.util.ArrayList`, `java.util.UUID` |
| tools.cli 1.1.230 | 1 | statics:1 |
| core.match 1.1.0 | 10 | imports:2 — `java.util.Date`, `java.util.Map` |
| cuerdas 2022.06.16 | 2 | imports:2 — `java.io.PushbackReader`, `java.io.StringReader` |
| uri 1.19.155 | 3 | imports:1 — `java.net.URI`, `java.io.Writer` |
| data.csv 1.1.0 | 1 | imports:1 — `java.io.Reader` |
| rewrite-clj 1.1.49 | 52 | imports:3 — `java.io.Writer`, `java.lang.Character` |
| babashka/http-client 0.4.22 | 9 | imports:5 — `java.io.*` throughout |
| tools.reader 1.5.0 | 9 | imports:5 statics:9 — `java.sql.Timestamp`, `java.text.SimpleDateFormat` |

**Zero clean.** Not "mostly clean" or "clean except one call" — every single one
would fail to load on cljgo, including `data.json`, which is the one a JSON-first
port would reach for by reflex.

For contrast, the same scan against **koine 0.7.1**: 11 source files, **zero
`(:import …)` forms and zero `java.*` package references** — the host calls live
behind reader conditionals, which is the entire design.

## Why this is the spike that justifies the architecture

It is the empirical answer to "why not just use a library?", and it is why the
port's rule is **koine is the only third-party dependency**. Any other dep would
have to be audited by this scanner first, and on this evidence it would almost
certainly fail.

The scanner is worth keeping runnable for exactly that: before adding a
dependency, point `scan.sh` at it. A jar with no `.clj`/`.cljc` source at all
(compiled-only) is also disqualified, and the scan says so.

## Not covered

Runtime behaviour — this is a static source scan. A library could avoid `import`
and still call a JVM-only method reflectively; the scan would miss it. It is a
fast disqualifier, not a certificate.
