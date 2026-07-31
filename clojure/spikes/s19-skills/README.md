# S19 — the FULL agent-skills capability on both hosts

**Question:** does the whole agent-skills capability — SPEC §3 / §0.5 / §0.6, not
just the happy path — work in portable Clojure on Clojure (JVM) *and* cljgo,
**byte-exactly**?

**Answer: yes.** Verified 2026-07-31, on this machine, with one `.cljc`,
**zero reader conditionals**, **zero `java.*`**, koine 0.4.2 as the only
dependency.

```
== diff
  jvm == cljgo-aot  (byte-identical, 4059 bytes)
  jvm == cljgo-run  (byte-identical, 4059 bytes)
== assertions
  jvm        ok=True shared.output-bytes=995 (utf8=995) s15-bytes=1127 failed=none
  cljgo-aot  ok=True shared.output-bytes=995 (utf8=995) s15-bytes=1127 failed=none
  cljgo-run  ok=True shared.output-bytes=995 (utf8=995) s15-bytes=1127 failed=none
```

20 assertions, all true, in all three modes. Reproduce: `./run-both.sh` (needs
`clojure`, `cljgo`, `python3` for the assertion reader). No network, no LLM,
no key.

## The headline number, and why it is not 1127

The brief said the shared `examples/skills/hello-world` output "must match what
s15 measured: **1127 bytes**". It does not. This spike measures **995**.

**s15 was wrong, and this spike is right.** SPEC §3 says the sampler takes
"up to 10 sibling files (**everything except `SKILL.md`**)". s15 listed the whole
directory and left `SKILL.md` in, so its `<skill_files>` block carried an extra
`<file>…/hello-world/SKILL.md</file>` line — 132 bytes of it at this repo path.
The spike still recomputes s15's number (`shared.s15-bytes`, asserted `== 1127`)
so the two are provably looking at the same fixture and the same renderer, and
they differ by exactly that one line.

The 995 is not self-asserted. It was diffed against the **shipped Go port**
running `LoadSkills` on the same directory:

```
BYTES 995 · PROMPTBYTES 293 · NOTFOUND true "Skill \"nope\" not found. Available skills: hello-world"
diff go-skill.txt clj-skill.txt   -> SKILL OUTPUT IDENTICAL
diff go-prompt.txt clj-prompt.txt -> PROMPT IDENTICAL
```

The full 995-byte string is in the report (`shared.skill-output`), so any future
drift shows up in the three-way diff *and* against the other ports.

s15's `skillsPrompt()` was wrong the same way — it emitted only
`## Available Skills…` with **no §3 instruction preamble**. This spike emits the
preamble + `\n\n` + catalog, 293 bytes, byte-identical to Go.

## What it covers

| SPEC | what | measured |
|---|---|---|
| §0.5 | glob `**/SKILL.md` under a root | 6 candidates found under the temp tree, 4 become skills |
| §0.5 | YAML frontmatter, `name` **required** | `fixtures/noname/SKILL.md` → skipped, `missing-name` |
| §0.5 | malformed frontmatter is graceful, never a crash | `fixtures/broken/SKILL.md` (unclosed fence) → skipped, `malformed-frontmatter` |
| §0.5 | duplicate `name`, **first wins** | `fixtures/dup` sorts after `fixtures/alpha` ⇒ `alpha` resolves to `fixtures/alpha`; dup → skipped, `duplicate-name` |
| §0.5 | deterministic discovery order across hosts | relies on `koine.fs/find-files`, which **sorts** — see below |
| §0.5 | nested skill | `fixtures/nested/deep/beta/SKILL.md` discovered, dir `fixtures/nested/deep/beta` |
| §0.6 | **byte-exact** `skill` output | hello-world = **995 bytes**, full string in the report, `== ` Go port |
| §3 | sibling sampler excludes `SKILL.md`, recursive | hello-world samples exactly 1 file (`scripts/greet.sh`); `alpha` samples 2, one of them nested |
| §3 | sample cap 10 | `fixtures/many` has 13 files, 12 sampleable → **10** emitted |
| §3 S5 | `sampleLimit` n>0 / -1 | `3` → 3 files; `-1` → block **and** its "Note: file list is sampled." line omitted (218 bytes vs 626) |
| §3 | `skillsPrompt()` = preamble + `\n\n` + catalog, sorted, **described only** | `gamma` (no `description` key) is discovered but absent from the prompt |
| §3 | the empty case | a root with no `SKILL.md` → `"No skills are currently available."`, **no preamble** |
| §3 | unknown skill name is a **ToolResult, not a throw** | `{isError:true, output:"Skill \"nope\" not found. Available skills: hello-world"}`; with no skills at all, `…Available skills: none` |
| §3 S3 | list/validate inventory with typed skip reasons | `list-skills` returns `{skills, skipped}`; 3 typed skips in the temp tree |
| §3 | the `skill` tool itself | name/source/inputSchema + the verbatim loader description, **398 bytes** — same length as the Python port's `SKILL_TOOL_DESCRIPTION` |

## Discovery order — the load-bearing detail

Every port's "first name wins" rule is **order-dependent**, so the port is only
deterministic if discovery order is. It is, and for exactly one reason:
`koine.fs/find-files` **sorts** (`koine/fs.cljc`: *"Sorted because skill
discovery must be deterministic across hosts — the underlying traversal order is
not guaranteed to match"*). `koine.fs/list-tree`, which it wraps, explicitly
documents its own order as *unspecified per host*. The spike relies on
`find-files`' sort and does **not** re-sort. If that sort ever leaves koine, this
spike's `temp-first-wins` assertion is the thing that will catch it.

Note `find-files` takes a **suffix, not a glob**, so the spike passes
`"/SKILL.md"` — a bare `"SKILL.md"` would also match a file called
`MYSKILL.md`.

## The temp fixtures, and why they are at a relative path

The spike builds its own tree at `./fixtures` (`rm -rf` + rebuild each run, so it
is idempotent). It is a **relative** root on purpose: a `mktemp -d` path would
put a random absolute string inside `Base directory for this skill:` and inside
every `<file>` line, and **every byte count in the report would move between
runs** — the three-way diff would then be measuring the temp directory, not the
code. A fixed relative root makes the temp half of the report deterministic and
machine-independent, at the cost of a `file://fixtures/alpha` base where
production emits `file:///abs/...`. The shared hello-world fixture (path from
`TN_EXAMPLES`, identical across the three runs) is the one that proves the
absolute-path shape, and it is the one reported in full.

The harness is not vacuously green: a negative control (flip one character in
one report) makes the diff fail, as it should.

## Findings

**F1 — s15's `skill` output and `skillsPrompt()` were both wrong.** s15 sampled
the skill directory without excluding `SKILL.md` (1127 vs the correct 995) and
omitted the §3 instruction preamble from `skillsPrompt()` (it emitted only the
`## Available Skills` block). Anything quoting s15's 1127 as the conformance
number should be corrected. This spike's 995/293 match the shipped Go port
byte-for-byte.

**F2 — no YAML parser exists for this port, and SPEC §3 mandates one.** §3 says
frontmatter must be parsed with "a **standard YAML parser** … NOT a hand-rolled
`key: value` split", so folded (`>`), literal (`|`), chomping and quoting all
resolve. koine has no YAML, and a spike may take no other dependency, so this
spike hand-rolls a documented subset: `key: value` scalars, optional surrounding
quotes, `#` comments, blank lines, trimmed values. **Block scalars, nesting and
flow collections are not handled.** This is the single largest open risk for a
real Clojure port — it needs either a YAML seam in koine or a pure-Clojure YAML
subset written and tested against the other ports' fixtures. It is not a
byte-parity problem *today* only because the shared fixture uses plain scalars.

**F3 — the sibling sampler's ORDER is unspecified, and no port sorts it.**
`js/src/skill.ts`, `golang/skill.go` and `python/.../skill.py` all walk a DFS
stack over raw readdir order and slice the first `limit` entries. Go's
`os.ReadDir` sorts, Node's `readdirSync` usually does on macOS, Python's
`os.scandir` does not — so with more than one sibling file the six ports are not
guaranteed to emit the same `<skill_files>` list, let alone the same 10 out of
13. The shared `hello-world` fixture (one sibling, one subdirectory) happens not
to expose it. **This spike sorts**, because a spike whose whole claim is
byte-identical output cannot depend on readdir order — recorded here rather than
smuggled in. SPEC §3 should pin the sampler's order.

**F4 — SPEC §3 prose and all six ports disagree on the not-found string.**
§3 step 1 says `output:"Skill \"x\" not found. Available: ..."`. Every shipped
port emits `"… not found. Available skills: …"` (js/go/python/java/csharp/elixir
all checked). The spike follows the **ports**, because byte-parity is measured
against them. SPEC §3 should be corrected to `Available skills: `.

**F5 — the ports disagree with each other on what "described" means.**
`js/src/skill.ts` filters the prompt with `s.description !== undefined`;
`golang/skill.go` filters with `info.Description != ""`. A skill with
`description: ""` therefore appears in the JS catalog as `- **x**: ` and is
absent from the Go catalog — a real byte divergence between two shipped ports,
not a Clojure problem. The spike follows JS (`some?`, i.e. key present). Not
exercised by the shared fixture. Worth a one-line fixture in the parity suite.

**F6 — no cljgo/JVM divergence found anywhere in this capability.** Everything
here is `clojure.core` + `clojure.string` + `koine.fs` + `koine.json` +
`koine.process/sh`, and all three modes agreed on the first run. `str/includes?`,
`str/last-index-of`, `str/split-lines`, `str/starts-with?/ends-with?`,
`str/blank?`, `sort-by`, `mapcat`, `assoc-in`, `doseq`/`range` and `update` all
behave identically on both hosts. `cljgo build` again reported
`11 namespace(s) with no Java interop` and pruned `org.clojure/clojure`.

**F7 — `cljgo which` does not exist.** s15's `run-both.sh` calls
`cljgo which slice` and silently falls through to `./slice` on the error. Harmless
there, but it means s15's harness has a dead branch; this spike's harness invokes
`./skills` directly.

## What it does NOT cover

- **S1 — skills supplied as data / by a provider** (`SkillDef`, logical
  `skill://name/` base, instruction-only data skills that omit `<skill_files>`
  entirely). Only the `-1` sample-limit path through that same branch is
  exercised. Not measured.
- **S2 — the per-agent allowlist** (`filter`: nil/empty ⇒ all, ≥1 true ⇒
  allowlist, only-false ⇒ drop-list, unknown names warned once). Not implemented,
  not measured.
- **Symlinks.** SPEC §3 requires discovery *and* the sampler to follow symlinked
  directories and symlinked `SKILL.md` files, with a resolved-realpath cycle
  guard. koine exposes no `realpath`/`readlink`, so a portable cycle guard is not
  currently writable. **Untested and probably not portable today — this is a real
  gap, not an omission for brevity.**
- **`node_modules` / `.git` pruning** is done by path-substring match, not by
  directory-entry name as the ports do. Equivalent for the ports' inputs, not
  proven so.
- **Byte counts are character counts.** `count` on a string counts characters;
  every fixture here is ASCII, so the two coincide, and `run-both.sh`
  independently re-measures the real UTF-8 length of the hello-world output in
  `python3` and fails if it disagrees. A non-ASCII skill body has **not** been
  measured, and koine has no UTF-8 byte-length helper.
- **Multiple skill roots.** `load-skills` accepts a vector and earlier roots win,
  but only the single-root path is measured.
- **Warnings.** The ports log a warning on a duplicate name / unknown filter
  name. The spike returns typed skips instead and logs nothing (a spike prints
  one JSON line, by the brief).
- Everything downstream: the toolkit assembly order, the adapters, the client
  loop. Those are S15/S16/S17's question, not this one.
