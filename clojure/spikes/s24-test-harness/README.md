# S24 — test harness: one `.cljc` `clojure.test` suite, both hosts, **and can we trust it?**

> ADR 0009 spike **S9** — "`clojure.test` suite runs identically on both hosts",
> Decision 4 (JVM-first, cljgo-second).

## The question

Can ONE `.cljc` `clojure.test` suite run identically on Clojure (JVM) and on cljgo —
**and can we trust the result?**

The second half is the whole spike. Two documented traps have the same shape:

- `cljgo run <file>` does not call `-main`; it exits 0 having printed nothing.
- `cljgo test` was reported to collect zero `.cljc` test files, print
  `Ran 0 tests containing 0 assertions`, and **exit 0**.

In both cases **exit 0 means nothing threw, not that anything happened.** A CI job
that shells out to a test runner and checks `$?` is green on a suite that never ran.

## Verdict

**PASS — with one cljgo bug found.**

1. One `.cljc` `clojure.test` suite — `deftest`, `is`, `testing`, `use-fixtures`
   (`:once` **and** `:each`), pure logic **and** koine (`koine.json`, `koine.fs`,
   `koine.env`) — runs **identically** on Clojure (JVM), a `cljgo build` AOT binary,
   and `cljgo run` interpreted. All three emit the **same 920 bytes** (`:host` aside).
2. The **counting gate** (`toolnexus.harness/gate`) works on all three, and it
   **catches the empty case**: pointed at a target with no tests it returns
   `:red` with reason `no-tests-collected`, while the underlying runner exits 0.
3. **`cljgo test` DOES collect `.cljc` tests on the version measured** — the
   "zero `.cljc` collected" report is **stale**. See FINDING 1.
4. **`cljgo test --compiled` / `--both` are BROKEN for `.cljc`** — the AOT test path
   cannot be reached through `cljgo test` at all. See FINDING 2. This is the one
   thing that did not work, and it is a cljgo work item.

Measured on **`cljgo CLI version 0.1.0-dev (Go 1.26.3, Clojure 1.12.5)`**,
Clojure 1.12.5 on the JVM, koine `0.4.2`, macOS arm64, 2026-07-31.

## The numbers

### In-process gate — `toolnexus.harness`, three modes

The `-main` runs the suite three times in one process (passing / armed / empty) and
prints one JSON line. `:host` is the only field that differs across hosts.

| mode | tests | assertions | fail | gate verdict |
|---|---|---|---|---|
| `passing` (canary disarmed) | **8** | **22** | 0 | `green` |
| `armed` (canary armed) | **8** | **22** | **1** | `green` (failure expected here) |
| `empty` (`toolnexus.empty-target-test`) | **0** | **0** | 0 | **`red` — `no-tests-collected`, `no-assertions-run`, `below-test-floor`, `below-assertion-floor`** |

| host / mode | bytes | `gate.verdict` |
|---|---|---|
| Clojure (JVM), `clojure -M -m toolnexus.harness` | 920 | `green` |
| cljgo AOT binary, `cljgo build` → `./harness` | 920 | `green` |
| cljgo interpreted, `cljgo run src/run_interpreted.cljc` | 920 | `green` |

`jvm == cljgo-aot == cljgo-run`, byte-identical after stripping `"host"`. Fixtures
ran in the same order on every host (`once-before`, 8 × `each`, `once-after`, twice —
once per suite run), which is itself part of the answer: `use-fixtures` works on cljgo.

### External runner — `cljgo test`

| invocation | collected | exit | gate |
|---|---|---|---|
| `cljgo test` | **10 tests / 25 assertions** | 0 | **GREEN** |
| `TN_FORCE_FAIL=1 cljgo test` | 10 / 25, **1 failure** | **1** | failure correctly reported |
| `cljgo test --compiled` | — build error — | 1 | **BROKEN (FINDING 2)** |
| `cljgo test --both` | interpreted 10/25 ok, compiled leg fails | 1 | **BROKEN (FINDING 2)** |
| `cljgo test` in `empty-project/` | **0 tests / 0 assertions** | **0** | **RED — `no-tests-collected`** |

10 / 25 = 8 / 22 from `src/toolnexus/logic_test.cljc` + 2 / 3 from the `test/`
collection sentinel.

## What arrangement works

**The gate does not depend on `cljgo test` at all, and that is deliberate.**

The suite is an ordinary `.cljc` namespace in the single `src/` tree
(ADR 0009 Decision 3). It is executed **in-process** by
`clojure.test/run-tests`, called from a `-main` that reads the returned summary map
and applies the counting gate itself. That one entrypoint is then run three ways —
`clojure -M -m`, `cljgo build` + binary, `cljgo run` via `src/run_interpreted.cljc` —
and the three JSON lines are diffed. No external test runner, no `$?`, on either host.

That arrangement is the recommendation for the port's CI, because:

- it covers the **AOT** path, which `cljgo test --compiled` cannot (FINDING 2);
- it never consults an exit code, so trap 2 is structurally impossible;
- it needs **no** test-runner dependency (no kaocha, no cognitect test-runner) — none
  of which exist on cljgo anyway.

`cljgo test` is measured **in addition**, as a cross-check, and its counts are put
through the same counting gate in `run-both.sh`.

For `cljgo test` specifically, the arrangement that works is:

- a `test/` directory must **exist** — with none, `cljgo test` prints
  `cljgo test: no test/ directory here` and does nothing;
- given that, `cljgo test` scans **both `src/` and `test/`** for namespaces whose name
  ends in `-test` and collects `.cljc` files from both. An unrequired
  `src/**/xxx_test.cljc` is collected. So the suite may stay in the single `src/` tree;
  `test/toolnexus/collection_sentinel_test.cljc` exists only to keep `test/` non-empty
  and to make silent loss of the `src/` collection visible as a count drop.

## The gate

`toolnexus.harness/gate` takes an observed run and returns `:green` only when the
suite **demonstrably ran**:

```
:suite-threw           the suite threw
:no-tests-collected    tests == 0          <- the silent-green case
:no-assertions-run     assertions == 0     <- a suite of empty deftests
:below-test-floor      tests < min-tests           (declared: 8)
:below-assertion-floor assertions < min-assertions (declared: 22)
:failures / :errors    ordinary red
```

The floors are **checked in**, so deleting tests is as loud as breaking them.
`run-both.sh` applies the same rule to an external runner's stdout by parsing
`Ran N tests containing M assertions` — never its exit code.

Deliverable 4 (the gate catches the empty case) is proven **twice**:

- in-process, by the `empty` mode above (`toolnexus.empty-target-test`, a namespace
  that requires `clojure.test` and defines no `deftest`);
- end-to-end, by `empty-project/` — a complete cljgo project with a `test/` directory
  and no tests anywhere. `cljgo test` there prints
  `Ran 0 tests containing 0 assertions.` and **exits 0**; the gate calls it RED.

## Findings

### FINDING 1 — `cljgo test` DOES see `.cljc` tests on 0.1.0-dev; the earlier report is stale

The brief's premise (`cljgo test` silently collects zero `.cljc` files) **did not
reproduce**. On `cljgo CLI version 0.1.0-dev (Go 1.26.3, Clojure 1.12.5)` it collected
10 tests / 25 assertions from `.cljc` files in both `src/` and `test/`, reported the
armed canary as a failure, and exited **1** on that failure. ADR 0009's note about
`cljgo test` should be updated to name a version.

The failure mode itself is still real and still worth gating against — it is exactly
what `empty-project/` reproduces on this same version — but it is now the *empty
project* case, not the *`.cljc` collection* case.

### FINDING 2 — `cljgo test --compiled` (and therefore `--both`) is broken for `.cljc` (cljgo bug)

```
$ cljgo test --compiled
cljgo test --compiled: build: could not locate namespace toolnexus.empty-target-test.cljc
  (no registered provider, and no toolnexus/empty_target_test/cljc.clj/.cljg/.cljc relative to the requiring file)
```

It forms the namespace symbol from the file path **without stripping the extension**:
`src/mini/core.cljc` → `mini.core.cljc`. Reproduced on a 10-line project from
`cljgo new`-shaped scratch: **identical project with `.clj` sources passes
(`Ran 1 tests containing 2 assertions`, exit 0); with `.cljc` sources it fails.**

Consequences for the port:

- `--both` (the interpreted-vs-AOT divergence check cljgo ADR 0007 asks for) **cannot
  run** on a `.cljc` tree, which is every file this port will ship.
- It fails **loudly** (exit 1, clear message), so it is not a silent-green trap — but
  it does mean the AOT test path must be covered by `cljgo build` on a `-main` that
  runs the suite itself, which is what this spike does.

This is a cljgo work item: strip `.cljc`/`.cljg`/`.clj` before forming the ns symbol
in the `--compiled` test builder.

### FINDING 3 — cljgo's `clojure.core` has names JVM Clojure does not

`(defn cljgo-version …)` produced:

```
WARNING: cljgo-version already refers to #'clojure.core/cljgo-version in namespace: toolnexus.harness
```

cljgo's embedded `clojure.core` carries at least one extra public name
(`cljgo-version`) that JVM Clojure does not have. BRIEF rule 3 ("never shadow a
`clojure.core` name") therefore has a **larger surface on cljgo than on the JVM**, and
the JVM build will not warn you. The fn here is named `measured-cljgo-version`.
The port needs a lint pass that checks names against cljgo's core, not only Clojure's.

### FINDING 4 — `run-tests` needs a namespace SYMBOL on cljgo, and fails *soft* if given an object

`(clojure.test/run-tests *ns*)` — legal on the JVM — errors on cljgo:

```
error: -collect-test-vars expects namespace symbols, got: user
```

…and **the process still exits 0**. A harness written the JVM-idiomatic way is
therefore a textbook silent-green on cljgo. Always pass a quoted symbol.

### FINDING 5 — `clojure.test/*report-counters*` is not portable

On the JVM it is a `ref`; on cljgo it is a `*lang.Ref` that `swap!` rejects
(`error: swap! expects an atom, got: #object[*lang.Ref]`). Never read the counters
directly. The map **returned** by `run-tests`
(`{:test :pass :fail :error :type :summary}`) is identical on both hosts — that is the
portable source of counts, and it is what the gate uses.

### FINDING 6 — capturing test output diverges: `with-out-str` alone is enough on cljgo, not on the JVM

`-main` must print exactly one JSON line, so the `clojure.test` report has to be
captured. On cljgo `(with-out-str (run-tests 'ns))` captures it. On the JVM it does
**not** — JVM `clojure.test` writes through `*test-out*`, which was captured at load
time. Binding both (`(with-out-str (binding [test/*test-out* *out*] …))`) works on
both hosts. A harness developed cljgo-first would have shipped a JVM that spews test
output into its JSON stream.

## Run it

```sh
cd clojure/spikes/s24-test-harness
./run-both.sh                          # all modes + both gates + the empty-case proof

clojure -M -m toolnexus.harness        # JVM
cljgo build && ./harness               # cljgo, AOT binary
cljgo run src/run_interpreted.cljc     # cljgo, interpreted
cljgo test                             # the external runner (cross-check)
TN_FORCE_FAIL=1 cljgo test             # arm the canary from OUTSIDE the process
                                       # (expect: 1 failure, exit 1)
```

`run-both.sh` exits non-zero on any regression; it also prints `S24: PASS` / `S24: FAIL`,
because on cljgo the exit code is the thing we are least willing to trust.

## Layout

```
src/toolnexus/logic.cljc               small REAL logic (SPEC §0.2 sanitize/naming)
src/toolnexus/logic_test.cljc          THE SUITE — 8 deftests, 22 assertions,
                                       fixtures, koine.json / koine.fs / koine.env,
                                       + the deliberate-failure canary
src/toolnexus/empty_target_test.cljc   negative control: requires clojure.test,
                                       defines no deftest (do not add one)
src/toolnexus/harness.cljc             the runner + THE COUNTING GATE + -main
src/run_interpreted.cljc               interpreted entrypoint (cljgo run ignores -main)
test/toolnexus/collection_sentinel_test.cljc
                                       keeps test/ non-empty for `cljgo test`;
                                       2 tests / 3 assertions of known size
empty-project/                         a whole cljgo project with zero tests —
                                       end-to-end proof of the silent-green case
```

Zero reader conditionals, zero `java.*`, zero Go interop in any of it
(`grep -rn '#?(' src test` finds nothing; the only `java.` in the tree is inside
prose comments). koine `0.4.2` is
the only third-party dependency, from Clojars, same coordinate on both hosts.

## What this spike did NOT cover

- **Only cljgo `0.1.0-dev` and JVM Clojure 1.12.5, on macOS arm64.** Not Linux, not CI,
  not Glojure, not let-go (S14's other two hosts were not exercised — `run-tests` there
  is unmeasured).
- **`is` beyond `=` / predicates.** No `thrown?`, no `thrown-with-msg?`, no
  `are`, no custom `assert-expr`, no `:default` reporting hooks. `thrown?` in particular
  is likely to be the next portability cliff and is untested here.
- **No async/concurrent tests** — no `future`/`promise` inside a `deftest` (that is S10).
- **No test *selection*** — no metadata selectors (`^:integration`), no
  `test-vars` on a subset, no per-var filtering. cljgo's collection is
  "every ns ending in `-test`", and whether it honours var metadata is unmeasured.
- **No parallelism, no timing, no coverage.** The Elixir port has a 95% coverage gate;
  there is no coverage tool on cljgo and this spike did not look for one.
- **The floor is a hand-maintained constant.** `min-tests` / `min-assertions` are
  checked in and must be bumped by hand; the gate catches deletion, not staleness of
  the floor itself.
- **`cljgo test`'s collection rule was measured, not read.** It is inferred from
  observed behaviour on one version, not from cljgo's source or a spec, so it may
  change.
- **`empty-project/` is only run through `cljgo test`.** Its JVM equivalent is the
  in-process `empty` mode, which is a namespace rather than a whole project.

## Re-run 2026-08-01 — koine 0.7.1, cljgo v0.8.4+ (`56da5a3`)

Still PASS: all four sections, all three modes byte-identical.

**The payload grew 887 -> 920 bytes, and that is cljgo #170 working.** The 33
bytes are in `cljgo-version`, which used to read `0.1.0-dev` and nothing else and
now reads `... [dev build, commit 56da5a32ad0b]`. Worth stating plainly, because
it makes this spike's payload **host-version-dependent**: the three modes still
agree with each other within a single run, which is what the diff asserts, but a
byte count quoted from one day is not comparable with another day's. Compare the
three modes against each other, never against yesterday's number.

**FINDING 2 IS STILL LIVE ON v0.8.4** — re-measured, not assumed:

```
$ cljgo test --compiled
cljgo test --compiled: build: could not locate namespace run-interpreted.cljc
  (no registered provider, and no run_interpreted/cljc.clj/.cljg/.cljc
   relative to the requiring file)
```

The namespace symbol keeps its `.cljc` extension, so `--compiled` (and `--both`)
cannot run this suite. The AOT test path is covered here by section 2 —
`cljgo build` plus running the binary — which is what makes this a nuisance
rather than a hole. Reported upstream with this repro.
