# Tasks — fix-builtin-host-boundary

Per-language parity checklist. A pass that covers a subset leaves the rest unchecked; nothing
is quietly dropped. ADR 0034 holds the decisions, `spikes/builtin-host-boundary/SPIKE.md` the
measurements each task is verified against.

## 0. Contract

- [x] ADR 0034 written, decisions D1–D7
- [x] Spikes run with controls on macOS and on a native Windows box (Go/Node/Python there)
- [x] Spec delta: `specs/builtin-tools/spec.md`
- [ ] `SPEC.md §4A` updated: `shell` / `baseDir` / `confineToBaseDir`, the kill contract and the
      2000 ms grace window, the `metadata` keys, and the grep-path rule restated
- [ ] `openspec validate fix-builtin-host-boundary`

## 1. Shell interpreter (#100)

| port | option + verbatim use | detection order | reported (`metadata.shell` + accessor) | construction fails with no interpreter |
|---|---|---|---|---|
| js | [ ] | [ ] | [ ] | [ ] |
| python | [ ] | [ ] | [ ] | [ ] |
| golang | [ ] | [ ] | [ ] | [ ] |
| java | [ ] | [ ] | [ ] | [ ] |
| csharp | [ ] | [ ] | [ ] | [ ] |
| elixir | [ ] | [ ] | [ ] | [ ] |
| clojure | [ ] | [ ] | [ ] | [ ] |

- [ ] js and python stop using `shell:true` / `shell=True`, which silently means cmd.exe on
      Windows (SPIKE §2.2) — they take the same detected argv as every other port

## 2. Base directory (#101)

| port | `baseDir` option | read/write/edit | glob/grep (incl. default path) | apply_patch patch-text paths | bash `workdir` default |
|---|---|---|---|---|---|
| js | [ ] | [ ] | [ ] | [ ] | [ ] |
| python | [ ] | [ ] | [ ] | [ ] | [ ] |
| golang | [ ] | [ ] | [ ] | [ ] | [ ] |
| java | [ ] | [ ] | [ ] | [ ] | [ ] |
| csharp | [ ] | [ ] | [ ] | [ ] | [ ] |
| elixir | [ ] | [ ] | [ ] | [ ] | [ ] |
| clojure | [ ] | [ ] | [ ] | [ ] | [ ] |

## 3. Confinement (#101, opt-in)

| port | canonicalise both sides | non-existent tail | symlink / junction | Windows device names | error result, never raise |
|---|---|---|---|---|---|
| js | [ ] | [ ] | [ ] | [ ] | [ ] |
| python | [ ] | [ ] | [ ] | [ ] | [ ] |
| golang | [ ] | [ ] | [ ] | [ ] | [ ] |
| java | [ ] | [ ] | [ ] | [ ] | [ ] |
| csharp | [ ] | [ ] | [ ] | [ ] | [ ] |
| elixir | [ ] | [ ] | [ ] | [ ] | [ ] |
| clojure | [ ] | [ ] | [ ] | [ ] | [ ] |

- [ ] golang uses `GetFinalPathNameByHandle` on Windows, **not** `filepath.EvalSymlinks`,
      which fails through a junction and would invert the check (SPIKE §3.2)
- [ ] python applies `os.path.normcase` — `os.path.realpath` does not normalise case (SPIKE §3.2)

## 4. Kill the job, not the shell (#102)

| port | new group / job | TERM → 2000 ms → KILL | snapshot before parent dies | `metadata.timedOut` + `killedTree` | Windows path |
|---|---|---|---|---|---|
| js | [ ] | [ ] | n/a (group) | [ ] | [ ] |
| python | [ ] | [ ] | n/a (group) | [ ] | [ ] |
| golang | [ ] | [ ] | n/a (group) | [ ] | [ ] |
| java | [ ] | [ ] | [ ] | [ ] | [ ] |
| csharp | [ ] | [ ] | [ ] | [ ] | [ ] |
| elixir | [ ] | [ ] | [ ] | [ ] | [ ] |
| clojure | [ ] | [ ] | [ ] | [ ] | [ ] |

- [ ] clojure: `set -m` wrapper so the child is a group leader and its pgid is written out
      before anything is killed — koine's `sh` kills internally and exposes no pid, and a
      post-hoc `ps` walk measured `REACHABLE_AFTER_KILL=0` (SPIKE §1.1)
- [ ] follow-up opened on koine for a `:kill-tree?` option (better home than the wrapper)

## 5. The two defects the spikes found (#not-reported)

- [ ] golang `grep` emits `rel` (`/`-separated, walk-root-relative), not `file` — the string it
      sorts by (SPIKE §4.1)
- [ ] a test pins the grep path shape so the defect cannot return
- [ ] separator rule re-verified on the Windows box after the fix

## 6. Tests

- [ ] each port: a timeout leaves no orphan (the spike's marker assertion, as a test)
- [ ] each port: `baseDir` relative resolution, and `""` ⇒ process cwd unchanged
- [ ] each port: confinement refuses `../`, an absolute outside path, and a symlink escape
- [ ] each port: `metadata.shell` reports the resolved interpreter
- [ ] golang: grep path shape
- [ ] CI stays hermetic — no network, no live LLM, no privileged operation

## 7. Docs

- [ ] `CHANGELOG.md` under `## Unreleased`, naming what is **not** verified: java/csharp/elixir/
      clojure on Windows, and that confinement does not confine `bash`
- [ ] per-port READMEs where they document the builtins
- [ ] `docs/` cookbook note for the worktree-scoped host, the case that led to this

## 8. Not in this change

- OS-level confinement of the child (Seatbelt / bubblewrap / Job-Object sandboxing) — ADR 0033
- A pluggable exec/filesystem backend — ADR 0033, still proposed and unspiked
- Java/C#/Elixir/Clojure Windows verification — needs a box with those runtimes installed
