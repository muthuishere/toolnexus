# Tasks — fix-builtin-host-boundary

Per-language parity checklist. A pass that covers a subset leaves the rest unchecked; nothing
is quietly dropped. ADR 0034 holds the decisions, `spikes/builtin-host-boundary/SPIKE.md` the
measurements each task is verified against.

## 0. Contract

- [x] ADR 0034 written, decisions D1–D7
- [x] Spikes run with controls on macOS and on a native Windows box (Go/Node/Python there)
- [x] Spec delta: `specs/builtin-tools/spec.md`
- [x] `SPEC.md §4A` updated: `shell` / `baseDir` / `confineToBaseDir`, the kill contract and the
      2000 ms grace window, the `metadata` keys, and the grep-path rule restated
- [x] `openspec validate fix-builtin-host-boundary`

## 1. Shell interpreter (#100)

| port | option + verbatim use | detection order | reported (`metadata.shell` + accessor) | construction fails with no interpreter |
|---|---|---|---|---|
| js | [x] | [x] | [x] | [x] |
| python | [x] | [x] | [x] | [x] |
| golang | [x] | [x] | [x] | [x] |
| java | [x] | [x] | [x] | [x] |
| csharp | [x] | [x] | [x] | [x] |
| elixir | [x] | [x] | [x] | [x] |
| clojure | [x] | [x] | [x] | [x] |

- [x] js and python stop using `shell:true` / `shell=True`, which silently means cmd.exe on
      Windows (SPIKE §2.2) — they take the same detected argv as every other port

## 2. Base directory (#101)

| port | `baseDir` option | read/write/edit | glob/grep (incl. default path) | apply_patch patch-text paths | bash `workdir` default |
|---|---|---|---|---|---|
| js | [x] | [x] | [x] | [x] | [x] |
| python | [x] | [x] | [x] | [x] | [x] |
| golang | [x] | [x] | [x] | [x] | [x] |
| java | [x] | [x] | [x] | [x] | [x] |
| csharp | [x] | [x] | [x] | [x] | [x] |
| elixir | [x] | [x] | [x] | [x] | [x] |
| clojure | [x] | [x] | [x] | [x] | [x] |

## 3. Confinement (#101, opt-in)

| port | canonicalise both sides | non-existent tail | symlink / junction | Windows device names | error result, never raise |
|---|---|---|---|---|---|
| js | [x] | [x] | [x] | [x] | [x] |
| python | [x] | [x] | [x] | [x] | [x] |
| golang | [x] | [x] | [x] | [x] | [x] |
| java | [x] | [x] | [x] | [x] | [x] |
| csharp | [x] | [x] | [x] | [x] | [x] |
| elixir | [x] | [x] | [x] | [x] | [x] |
| clojure | [x] | [x] | [x] | [x] | [x] |

- [x] golang uses `GetFinalPathNameByHandle` on Windows, **not** `filepath.EvalSymlinks`,
      which fails through a junction and would invert the check (SPIKE §3.2)
- [x] python applies `os.path.normcase` — `os.path.realpath` does not normalise case (SPIKE §3.2)

## 4. Kill the job, not the shell (#102)

| port | new group / job | TERM → 2000 ms → KILL | snapshot before parent dies | `metadata.timedOut` + `killedTree` | Windows path |
|---|---|---|---|---|---|
| js | [x] | [x] | n/a (group) | [x] | [x] (verified on a real Windows box) |
| python | [x] | [x] | n/a (group) | [x] | [x] (verified on a real Windows box) |
| golang | [x] | [x] | n/a (group) | [x] | [x] (verified on a real Windows box) |
| java | [x] | [x] | [x] | [x] | [x] (unverified on Windows) |
| csharp | [x] | [x] | [x] | [x] | [x] (unverified on Windows) |
| elixir | [x] | [x] | [x] | [x] | [x] (unverified on Windows) |
| clojure | [x] | [x] | [x] | [x] | [x] (unverified on Windows) |

- [x] clojure: **NOT** `set -m` — the port probes refuted it (fails under dash, and moves
      `output`). The deadline moved out of koine instead: the command runs on a `run-async!`
      thread, the shell records its own pid, and the tree is enumerated while it is still alive
- [ ] follow-up to open on koine: a `:kill-tree?` option, and `real-path` case parity between the two hosts (the JVM normalises case, cljgo does not — worked around in-port for now)

## 5. The two defects the spikes found (#not-reported)

- [x] golang `grep` emits `rel` (`/`-separated, walk-root-relative), not `file` — the string it
      sorts by (SPIKE §4.1)
- [x] a test pins the grep path shape so the defect cannot return
- [x] separator rule re-verified on the Windows box after the fix

## 6. Tests

- [x] each port: a timeout leaves no orphan (the spike's marker assertion, as a test)
- [x] each port: `baseDir` relative resolution, and `""` ⇒ process cwd unchanged
- [x] each port: confinement refuses `../`, an absolute outside path, and a symlink escape
- [x] each port: `metadata.shell` reports the resolved interpreter
- [x] golang: grep path shape
- [x] CI stays hermetic — no network, no live LLM, no privileged operation

## 7. Docs

- [x] `CHANGELOG.md` under `## Unreleased`, naming what is **not** verified: java/csharp/elixir/
      clojure on Windows, and that confinement does not confine `bash`
- [ ] per-port READMEs where they document the builtins — NOT DONE
- [ ] `docs/` cookbook note for the worktree-scoped host — NOT DONE

## 8. Not in this change

- OS-level confinement of the child (Seatbelt / bubblewrap / Job-Object sandboxing) — ADR 0033
- A pluggable exec/filesystem backend — ADR 0033, still proposed and unspiked
- Java/C#/Elixir/Clojure Windows verification — needs a box with those runtimes installed

## 9. Verified where, and where not

**Measured on native Windows with the shipped port code** (`spikes/builtin-host-boundary/win/`):
go, js, python — interpreter detection, `baseDir`, confinement (including `CON`), the timeout
killing the tree, and `/`-separated listings. Running there found two defects no POSIX suite could
see: js calling `taskkill` without `/PID`, and the "graceful" Windows step orphaning the job.

**NOT verified on Windows:** java, csharp, elixir, clojure — those runtimes are not installed on
the machine available to us. Their Windows paths are written against documented platform APIs and
are unproven until someone runs them.

**NOT verified on Linux at all.** The POSIX arm is macOS, plus one `dash` data point (which is the
one that refuted `set -m`).
