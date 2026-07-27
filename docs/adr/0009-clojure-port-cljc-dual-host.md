# ADR 0009 — The Clojure port is a single `.cljc` source tree on `clojure.core` alone, JVM-first, Go-hosted second

- **Status:** **Proposed** — spike battery in progress on branch `cljc`. Nothing in
  `SPEC.md` or the six existing ports has been changed by this document.
- **Date:** 2026-07-27
- **Driver:** toolnexus ships six ports (js / python / golang / java / csharp / elixir). A
  seventh — Clojure — is wanted, and it must run on **every host from one source**: JVM
  Clojure (the ecosystem, Clojars, `clojure.test`) and the Go-hosted dialects —
  [cljgo](https://github.com/muthuishere/cljgo),
  [Glojure](https://github.com/glojurelang/glojure) and
  [let-go](https://github.com/nooga/let-go). All four are verified below.
- **Evidence note:** every claim below was **measured on this machine** — `clojure` CLI
  1.12.5, `cljgo` 0.1.0-dev, and Glojure and let-go built from source — not reasoned from
  documentation. Reader-feature claims are cited to each implementation's source, not its
  README. Measurements are reproduced in `clojure/spikes/` and in the
  [`koine`](https://github.com/muthuishere/koine) conformance runner. Where a claim is
  unverified it says so.

## Context

The prime directive (`CLAUDE.md`) is byte-parity across ports against the shared
`examples/` fixtures. A Clojure port that behaves differently on JVM and cljgo would
violate that directive *within a single port* — the worst possible outcome, and the exact
failure mode cljgo itself calls "unforgivable" (REPL-vs-binary divergence, cljgo ADR 0007).

So the port's design question is not "which Clojure do we target" but **"what is the
smallest surface on which the two hosts can be proven identical?"**

### Measurement 1 — one `.cljc` tree runs on both hosts, unmodified

A two-namespace spike (`toolnexus.host` + `toolnexus.core`) loaded via `require` on both
hosts from identical source produced identical output apart from the deliberate
platform probe:

```
JVM   → platform: jvm    sanitized: add_two  execute: 5  sh: {:out "HELLO DUAL HOST", :exit 0}
cljgo → platform: cljgo  sanitized: add_two  execute: 5  sh: {:out "HELLO DUAL HOST", :exit 0}
```

This works because of two ratified cljgo decisions: reader features are exactly
`#{:cljgo :default}` and cljgo **never** answers `:clj` (cljgo ADR 0036 §A), and `.cljc`
is a resolvable extension for `require` (cljgo ADR 0068 §1). The JVM elides `:cljgo`
branches; cljgo elides `:clj` branches. Neither host ever reads the other's interop.

### Measurement 2 — the pure-Clojure Clojars ecosystem is empty, not thin

Eleven candidate libraries were resolved and their `.clj`/`.cljc` sources scanned for Java
interop (`clojure/spikes/s03-dependency-purity/`). **Every one carries Java interop** —
including libraries that sound pure:

| library | Java surface |
|---|---|
| `dev.weavejester/medley` (utility fns) | `java.util.ArrayList`, `java.util.UUID` |
| `org.clojure/tools.cli` | a static call |
| `org.clojure/data.json` | `java.io.Reader`/`Writer`, `Boolean`, `Byte`, `CharSequence`, `Double`, 5 statics |
| `borkdude/edamame` | `java.io.Reader`, `Character` |
| `org.babashka/http-client` | 5 imports, `java.io.*` streams |
| `rewrite-clj` | `java.io.Writer`, `Character`, `String` |
| `org.clojure/data.csv`, `core.match`, `cuerdas`, `lambdaisland/uri`, `tools.reader` | all Java |

cljgo ADR 0054 predicted a "thin" pure subset. The measurement says it is **zero**. And
cljgo ADR 0054 constraint 2 is categorical: *"cljgo does not do Java at all. Java interop
is not a supported host operation, in either direction."*

**Therefore: any third-party Clojure dependency makes the port JVM-only.** Not slower to
port — *impossible* to port, until cljgo grows a Java shim (see Consequences).

### Measurement 3 — `clojure.core` itself is already at parity

22 core vars were probed on both hosts. **All 22 resolve on both**, including the ones
that decide whether the port is even expressible:

`future` `future?` `deref` `promise` `deliver` `atom` `swap!` `slurp` `spit` `pmap`
`send` `agent` `read-string` `pr-str` `re-seq` `subs` `format` `with-out-str` `bytes`
`byte-array` `char` `string?`

`future`/`promise` cover §8's parallel tool calls. `slurp`/`spit` cover skills and the
agent-home files. `format`/`re-seq`/`subs` cover parsing. This is the whole port.

### Measurement 4 — the seam is four items, and one is a trap

Four capabilities genuinely fall outside `clojure.core`:

| # | need | JVM | cljgo | status |
|---|---|---|---|---|
| 1 | HTTP request | `java.net.http` | `cljg.net.http` | both present, verified |
| 2 | subprocess **with streaming pipes** | `ProcessBuilder` ✅ | **missing** | **cljgo gap** |
| 3 | directory listing | `file-seq` on `java.io.File` | `file-seq` on a string | **divergent** |
| 4 | environment variables | `System/getenv` | `cljg.os` | needs a branch |

Item 3 is the trap and the reason this ADR exists rather than a wiki page: `file-seq`
resolves on **both** hosts, so it reads as portable — but the argument types differ.
`(file-seq "sk")` returns `("sk" "sk/a" "sk/a/SKILL.md")` on cljgo and throws
`ClassCastException: String cannot be cast to java.io.File` on the JVM. A "portable"
name is not a portable function.

Item 2 is a genuine cljgo gap: `cljg.io/exec` (`core/cljg/io.cljg:155`) is
run-to-completion — it takes `:in` as a *string* and returns `{:out :err :exit}`. MCP's
stdio transport needs a **long-lived** child exchanging line-delimited JSON-RPC. Go
interop is not an escape hatch either: `(require-go '[os/exec :as ex])` binds nothing in
interpreted mode (verified — `no such namespace: ex`).

## Decision

### 1. One `.cljc` source tree, four dialects: `:clj`, `:cljgo`, `:glj`, `:lg`

No `.clj`/`.cljg` forks, no per-host copies. `.cljc` is chosen as the *portable
extension*.

**ClojureScript, `:cljr`, `:bb` and jank are explicitly out of scope.** ClojureScript
cannot spawn a subprocess, so stdio MCP servers — roughly half of what toolnexus does —
are impossible there. A port that silently drops half the spec is not a port. This is a
decision, not an omission; revisit only if `:cljs` gains a process model.

### 2. toolnexus depends on `clojure.core` + `koine` only — never on a third-party library

Given Measurement 2, any third-party Clojure dependency in the port itself makes the port
JVM-only. Given Measurement 3, `clojure.core` covers everything except the seam.

**`koine` is exempt and is where host libraries live.** It may use Java libraries on the
JVM branch and Go libraries on the cljgo branch freely — that is its whole purpose. The
port calls its portable API and never sees either.

#### 2a. `koine`'s contract is byte-identical output, not "wraps a library" [Measurement 5]

Wrapping two host libraries and calling it portable does not survive contact with the
prime directive. Six basic payloads were encoded through Go's `encoding/json` and JVM
`clojure.data.json` (`clojure/spikes/s05-json/`). **Four of six diverge:**

| payload | Go `encoding/json` | JVM `data.json` |
|---|---|---|
| `{b 1, a 2, c 3}` | `{"a":2,"b":1,"c":3}` (sorted) | `{"b":1,"a":2,"c":3}` (insertion) |
| `"a<b>c&d"` | `"a<b>c&d"` | `"a<b>c&d"` |
| `1.0` / `100.0` | `1` / `100` — fraction dropped | `1.0` / `100.0` |
| `"café ☃"` | literal UTF-8 | `"café ☃"` |
| tab/newline escapes | agree | agree |
| `[1 2.0 "x" nil true]` | `[1,2,"x",null,true]` | `[1,2.0,"x",null,true]` |

Two of these are semantic, not cosmetic:

- **Floats.** `{"temperature": 1.0}` encoded as `{"temperature": 1}` changes the JSON
  type. Tool schemas declaring `"type":"number"` and providers that coerce integers
  behave differently.
- **Key order.** Byte-identical prompt prefixes are what make provider prompt caching
  work (the `cache_control` breakpoints deferred in ADR 0008). Sorted-vs-insertion
  ordering silently destroys cache hits.

Therefore `koine` MUST normalize every host library it wraps to one agreed output, and
a conformance test MUST assert the two hosts produce identical bytes.

#### 2b. Both JSON encode and decode are ours

Every one of the four divergences is an **output-formatting** choice. Parsing is
unambiguous — both hosts turn a given document into the same Clojure data. So:

- **encode** — pure portable Clojure inside `koine`, ~80 lines, no host library.
- **decode** — *also* pure portable Clojure. Delegation was the original decision here
  and it was **revised on evidence**: with four hosts it would mean four parsers to keep
  in agreement, and two are unreachable (cljgo's decoder is a private builtin; Glojure
  ships no `encoding/json` by default). A core-only parser is smaller *and* more
  portable, and it leaves `koine` with **zero third-party dependencies**.
  Controlling key order, escaping and number formatting *is* the entire encoder;
  normalizing someone else's encoder into agreement costs the same code while leaving us
  debugging two libraries' escaping rules. The Elixir port made the identical call with
  its in-house MCP client.

The encoder MUST NOT use `StringBuilder`, `Long/parseLong`, `Double/parseDouble` or any
other Java interop — those are JVM-only and were the first thing this spike got wrong.
Build strings with `apply str` / `clojure.string/join`.

`clojure.test` is permitted for tests — it is `clojure.*`, present on both hosts (cljgo
reports it complete against the 1.12.5 oracle, 39 vars).

### 3. Reader conditionals live in exactly one namespace: `toolnexus.host`

Every other namespace is dialect-blind pure Clojure and MUST NOT contain `#?` or `#?@`.
The seam is the four items of Measurement 4 and nothing else — it is small enough to read
in one sitting, which is the entire point. A test asserts the invariant by scanning the
source tree for `#?` outside `host.cljc`.

Corollary from Measurement 4 item 3: a function that *resolves* on both hosts is not
thereby portable. Anything whose **argument or return types** differ across hosts goes
behind the seam even when the name is shared. `file-seq` is the first such case.

### 3b. The seam is built in-tree first, and extracted to its own library once proven

The four seam functions are not specific to toolnexus — *every* dual-host Clojure library
needs the same four. The intent (owner, 2026-07-27) is therefore to extract them into a
standalone portability library, published to Clojars, with a JVM-hosted and a Go-hosted
implementation, so all future libraries build on it instead of re-solving the seam.

**Shape when extracted: one artifact, not three.** A single `.cljc` library carrying both
hosts' branches — so reader conditionals exist in exactly one place in the whole ecosystem
and consumers write `(:require [koine.process :as proc])` with no conditional and no
implementation-selection step. The alternative (a pure `api` artifact of protocols plus
`-jvm` and `-go` implementation artifacts) buys pluggable third hosts, which Decision 1
has already ruled out of scope, at the cost of a second dependency for every consumer.

**Sequencing: prove, then extract — not the reverse.** The seam is built as
`toolnexus.host` and subjected to the full spike battery *before* it is lifted out.
Publishing an API to Clojars freezes it: changing it afterwards costs a breaking release.
S7 is already expected to reshape the process API (cljgo has no streaming child at all),
which is precisely the kind of finding that must land *before* the interface is public.
Extraction is mechanical once the shape has stopped moving.

### 4. JVM-first, cljgo-second — sequenced, not simultaneous

The port is built and proven **excellent on JVM Clojure first**: full `clojure.test`
suite, green against the shared `examples/` fixtures, parity-checked against the existing
six ports. Only then is the same `.cljc` pointed at cljgo and the gaps recorded.

This ordering is deliberate: it means a cljgo gap **cannot block the port from shipping**,
and it puts the port where the users, the tooling and Clojars already are. cljgo support
is a milestone, not a release gate.

### 5. Distribution: Clojars for the JVM, git coordinate for cljgo

Published to Clojars as a normal Clojure library (`tools.build` jar carrying the `.cljc`
source), consumed by `deps.edn`/Leiningen the ordinary way.

cljgo consumes **the same source tree via a git coordinate**, because cljgo cannot consume
from Clojars today: ADR 0054 built the *publish* side only and explicitly defers
consume-side interop; cljgo resolves Clojure deps as git source roots (ADR 0052). One
source, two coordinates, until Clojars consumption lands in cljgo.

We do **not** use `cljgo publish clojars` — its validator rejects any Go interop, and it
emits a git coordinate rather than a Clojars artifact anyway (ADR 0054, deferred items).

### 6. Four hosts are verified: `:clj`, `:cljgo`, `:glj`, `:lg`

cljgo is not the only Clojure hosted on Go. **Glojure and let-go are now verified
alongside it** — built from source, one `.cljc` file, all four hosts:

```
jvm        9/9 pass
cljgo      9/9 pass
let-go     9/9 pass
glojure    9/9 pass
```

Reader features were read out of each implementation's source, not its README:

| host | feature | source |
|---|---|---|
| Clojure | `:clj` | — |
| cljgo | `:cljgo` + `:default`, never `:clj` | cljgo ADR 0036 §A |
| Glojure | `:glj` + `:default` | `pkg/reader/reader.go:1403` |
| let-go | `:lg` + `:default`; opt-in `:clj`/`:bb` via `set-read-clj!` / `LG_READ_CLJ`, **off by default** | `pkg/compiler/reader.go:1122-1145` |

The three Go dialects do **not** share an implementation, because their interop models
are unrelated: cljgo uses `require-go` + `cljg.*`; Glojure exposes Go's stdlib directly
with `/` munged to `:` (`os:exec.Command`, `net:http`), shipping ~26 packages by default;
let-go has its own `os`/`io`/`http`/`json` namespaces *plus* Java-shaped shims — its
`System/getenv` works. Adding a dialect is therefore a branch, not a fork, and
`koine` is structured for it:

- every seam function ends in a `:default` branch that either delegates to a
  dialect-agnostic implementation or throws a **named, actionable** error
  (`"koine: no <capability> implementation for this host; add a branch in
  koine/<ns>.cljc"`) — never a silent `nil` or an obscure resolution failure;
- branch order is `#?(:clj … :cljgo … :default …)`, extended in place;
- the conformance suite is host-parameterised, so a new dialect is onboarded by running
  the existing suite against it and fixing what fails.

**Honesty:** `:clj` (1.12.5), `:cljgo` (0.1.0-dev), `:glj` and `:lg` are measured for
**JSON and env only**. `http`, `process` and `fs` have per-host branches but are verified
on the JVM alone. gloat and Joker are untested.

#### Measurement 6 — portability bugs that are invisible on the JVM

Writing one file for four hosts surfaced five traps, each now encoded as a rule:

1. **`(= key-fn keyword)` throws on Glojure** — "comparing uncomparable type
   `lang.ArityFn`". Apply a function; never compare one.
2. **`^:dynamic` is not honoured on Glojure** — "cannot dynamically bind non-dynamic
   var". Thread the parameter; it is also less code.
3. **Go's `os.Getenv` returns `""` where the JVM returns `null`**, and `""` is *truthy*
   in Clojure — so `(or (getenv x) default)` silently never falls back on Go-hosted
   dialects.
4. **`file-seq` takes different argument types** (Measurement 4, item 3).
5. **Map print order differs per host**, so any assertion over `pr-str` of a map is a
   false failure waiting to happen. This bit our own conformance script.

## Consequences

- The port carries no supply chain. No CVE surface, no version conflicts with a host
  application's own `data.json` — a real ergonomic win for a *library*, which is what
  toolnexus is.
- We own a JSON implementation and must test it as such (round-trip, unicode escapes,
  deep nesting, number formats, malformed input). It is spiked before it is trusted.
- ~95% of the port is dialect-blind, so cljgo support reduces to four functions.
- The seam is implemented in **[`koine`](https://github.com/muthuishere/koine)** — its
  own public repo, per Decision 3b. S5's encoder contract passes byte-identically on both
  hosts there (7/7 on cljgo, 38 assertions on the JVM).
- **cljgo work items**, each independently useful to cljgo beyond toolnexus:
  1. **Streaming subprocess** in `cljg.io` — a long-lived child with piped stdin/stdout.
     **Blocks stdio MCP; the only hard blocker found.** `koine.process/spawn` throws a
     named error on cljgo rather than pretending.
  2. **Environment-variable access.** cljgo cannot read env vars at all: `cljg.os` is
     cron/service only, there is no `System/getenv` shim, and `require-go` reaches only
     the seed registry — `strings`/`strconv`/`math`/`fmt` (`pkg/eval/host.go:15`) — so
     `(require-go '[os])` fails in **both** interpreted and AOT mode (verified against
     the installed *and* the in-repo binary). toolnexus needs this for `${ENV_VAR}`
     expansion in MCP headers. **Blocks remote MCP auth.**
  3. **A public JSON namespace.** `-json-decode` is a *private* builtin, unreachable from
     user code. No longer affects toolnexus — `koine` ships its own parser — but it is
     a real hole in cljgo's stdlib.
  4. **Clojars consumption** — so cljgo users get the same coordinate as JVM users.
     Not a blocker; a git coordinate works today.
  5. **A `java.io`/`java.lang`/`java.util` shim** *(large, strategic, NOT required by this
     port)*. Measurement 2 showed the ecosystem's interop is concentrated in ~20 boring
     classes with direct Go equivalents. Shimming them would unlock a wide slice of
     Clojars for cljgo. Recorded here because the measurement is the evidence for it; it
     belongs to cljgo and to its own ADR, not to this port.
- The port is a seventh implementation of `SPEC.md` and inherits the prime directive: a
  behavior change lands in all ports or it is not done. This ADR does not change `SPEC.md`
  and does not alter any existing port.

## Spike battery (gate — this ADR is not accepted until these pass)

| # | spike | verifies | status |
|---|---|---|---|
| S1 | dual-host `.cljc` require + conditional selection | Decision 1 | ✅ passed |
| S2 | seam confined to one namespace | Decision 3 | ✅ passed |
| S3 | dependency purity scan | Decision 2 | ✅ passed (0 of 11 pure) |
| S4 | `clojure.core` parity probe (22 vars) | Decision 2 | ✅ passed (22/22) |
| S5 | JSON encode/decode identical on all hosts | Decision 2a/2b | ✅ passed (9/9 × 4 hosts) |
| S6 | HTTP POST both hosts against a local server | seam 1 | ☐ |
| S7 | streaming stdio subprocess, both hosts | seam 2 | ⚠️ JVM passes, **cljgo gap confirmed** |
| S14 | Glojure + let-go run the same `.cljc` | Decision 6 | ✅ passed |
| S8 | skills discovery: `**/SKILL.md` glob + frontmatter | seam 3 | ☐ |
| S9 | `clojure.test` suite runs identically on both hosts | Decision 4 | ☐ |
| S10 | parallel tool calls via `future`/`promise` | §8 loop | ☐ |
| S11 | real MCP handshake against `examples/mcp.json` | §2 | ☐ |
| S12 | `cljgo build` AOT-compiles a program requiring the port | cljgo release path | ☐ |
| S13 | Clojars publish dry-run + cljgo git-coord consumption | Decision 5 | ☐ |

S5, S7 and S11 are the ones that can still kill or reshape this design. S7 is expected to
fail on cljgo and that failure is the specification for cljgo work item 1.
