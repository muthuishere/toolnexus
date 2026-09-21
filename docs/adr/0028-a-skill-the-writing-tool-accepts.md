# ADR 0028 — A skill is what the writing tool accepts: the SKILL.md compatibility boundary, and the parity violation found while measuring it

- **Status:** **Proposed — measured 2026-09-21.** Every number below is captured output from
  `spikes/issues/93/`, re-runnable offline; two of my own predictions did not survive and are in
  *Corrections* rather than quietly dropped.
- **Date:** 2026-09-21
- **Driver:** [Issue #93](https://github.com/muthuishere/toolnexus/issues/93) — a `description`
  containing an unquoted `": "` ("… Trigger on: update the timesheet") is invalid YAML, so
  `LoadSkills` skips the file as `malformed-frontmatter`, while Claude Code — reading the *same
  directory* — loads it. Reported as 6 of 76 skills lost on one machine, silently.
- **Evidence:** `spikes/issues/93/` — 17 fixtures plus two real corpora (`~/.claude/skills`, 87
  SKILL.md; `~/.agents/skills`, 36), run through **all seven ports' own `ListSkills`**, through
  `claude plugin validate` on Claude Code **v2.1.278**, and through a prototype of the fix.
  Raw tables in that directory's `README.md`.
- **Related:** SPEC.md §3 (skill source, "a standard YAML parser … NOT a hand-rolled `key: value`
  split"), ADR 0002 (skills consumer needs), ADR 0004 (skill parser determinism and frontmatter),
  ADR 0009 (the Clojure port's `.cljc` dual host).

## Context

SPEC §3 makes one instruction do two jobs: *parse frontmatter with your ecosystem's real YAML
library*, and *be byte-identical across ports*. It reads as a single rule because YAML sounds like
a standard. The measurement says it is two rules, and they were already pulling in opposite
directions before this issue was filed.

Issue #93 is the first job failing. The second failure was found while checking it, is larger, and
is the reason this ADR exists rather than a one-line patch.

## The measurements

### 1. The issue is correct, and the count is exact

`~/.claude/skills`, 87 SKILL.md (symlinks followed, as §3 requires). Six ports agree:

```
js / python / golang / java / csharp / elixir   69 ok / 18 skipped
                                                {malformed-frontmatter: 6, duplicate-name: 12}
```

All **6** malformed files have the same cause — an unquoted `": "` in a plain-scalar
`description`: `volentis-timesheet`, `volentis-invoice`, `publish-invoice`, `book-management`,
`monthlyshare`, `reclaim`. All six are live skills in Claude Code right now, **with their
frontmatter descriptions intact** — this session's own skill listing quotes
`volentis-timesheet`'s "… Trigger on: update the timesheet, do my timesheet, …" verbatim, which is
the offending substring itself. The reporter's "6 of 76" reproduces as 6 of 87.

### 2. The larger finding: the ports do not agree today

The same harness, same fixtures:

| fixture | six YAML ports | **clojure** | Claude Code |
|---|---|---|---|
| `description: … Trigger on: …` | malformed | **ok** | ok |
| `description: \|` (literal block) | ok | **malformed** | ok |
| `description: >` (folded block) | ok | **malformed** | ok |
| `allowed-tools: [bash, read]` | ok | **malformed** | ok |
| `allowed-tools:` + `- bash` | ok | **malformed** | ok |
| nested mapping | ok | **malformed** | ok |
| `&anchor` / `*alias` | ok | **malformed** | ok |

Fixture totals: six ports 11 ok / 6 skipped; **clojure 6 ok / 11 skipped**. On the real corpora:
`~/.claude/skills` — clojure **33 ok / 54 skipped** against 69/18; `~/.agents/skills` — six ports
**36 ok / 0 skipped**, clojure **13 ok / 23 skipped**, i.e. **64% of a catalog the other six load
perfectly is invisible in Clojure**, and the two sets diverge in *both* directions.

This is not an accident. `clojure/src/toolnexus/frontmatter.cljc` is a deliberate, documented
departure — "a DOCUMENTED SUBSET, deliberately not YAML", with the divergence spelled out in
`skill.cljc`'s own comment. It was recorded and not hidden. But it means the sentence "the skill
loader is byte-identical across seven ports" has been false since the Clojure port landed, and the
one bug this repo exists to prevent (CLAUDE.md: "silent drift") is present in the loader that is
most exposed to user files. **This is the biggest finding in #93, and the reporter could not have
seen it**: from Go alone, toolnexus looks strict-but-consistent.

Note the shape of it: the hand-rolled subset is *lenient exactly where the issue wants leniency*
(it takes rest-of-line, so `Trigger on:` is fine) and *strict everywhere users actually write
YAML*. The port that already implements option (1) is the port that proves option (1) must not be
implemented that way.

### 3. What Claude Code actually accepts — measured, not assumed

`claude plugin validate` on **v2.1.278**, over the same 17 fixtures plus four adversarial extras
(`spikes/issues/93/probe/`):

- **Errors on exactly one file**: tab indentation — *"YAML frontmatter failed to parse: Unexpected
  token. At runtime this skill loads with empty metadata (all frontmatter fields silently dropped)."*
- **Says nothing** about `Trigger on: …`, an unterminated `[flow sequence`, an unterminated
  `{flow map`, duplicate `description:` keys, `a: b: c: many colons`, or an **undefined alias**
  `other: *nope`.
- Emits a *warning*, not an error, for a file with no frontmatter block, and loads it anyway with
  `name` ← directory name and `description` ← first line of the body (its own wording, from the
  shipped binary).

So the boundary is not "valid YAML" and it is not "YAML with recovery" either. I checked recovery
directly: `YAML.parseDocument("description: x. Trigger on: do it.")` — the same `yaml` package the
JS port uses — *recovers*, but recovers `description` as a **map**
`{"x. Trigger on": "do it."}`. Claude Code shows the whole sentence. Its live behaviour is
consistent only with a line-wise read of the scalar; the binary carries a literal
`^name:[ \t]*["']?([A-Za-z0-9_-]+)["']?[ \t]*$` regex for exactly that kind of extraction, and
`strict: false` was tested and makes no difference (all seven error classes above survive it).

**The compatibility boundary is therefore "what the tool that wrote the file accepts", and that
tool is far more permissive than any YAML library.** A user does not author YAML; they author a
SKILL.md, in an editor, against a tool that never complained. The file is the interface. A parser
that rejects it is not defending a standard — it is disagreeing with the only implementation the
user has ever run.

### 4. Option (1) is safe — but only in the opposite order to the one proposed

The issue proposes line-wise `key: rest-of-line` for `name`/`description`, with YAML as the
*fallback*. In that order it **does** misparse legitimate YAML, and the Clojure port is the
existence proof: rest-of-line for `description: |` is `"|"`, for `description: >` is `">"`, and for
a plain scalar continued on the next line it silently truncates to the first line. Those shapes are
in the shared `examples/`-adjacent corpus and in SPEC §3's own justification for using a real
parser.

Inverting the order removes the risk by construction:

> **YAML first. Line-wise only for `name`/`description`, only on frontmatter a real YAML parser has
> already refused.**

A file that YAML parses has YAML semantics, and keeps them, byte for byte. A file that YAML refuses
has no YAML semantics to preserve — there is nothing left to misparse. The prototype
(`spikes/issues/93/prototype/lenient.py`) adds two guards: only column-0 keys, first-wins; and a
value that is empty or opens `| > & * [ { !` is **refused, not guessed**, so a half-broken block
scalar degrades to "no description" instead of to `"|"`.

Captured:

| corpus | strict YAML (six ports) | prototype |
|---|---|---|
| fixtures (17) | 11 ok / 3 malformed / 3 missing-name | **14 ok / 3 missing-name** |
| `~/.claude/skills` (87) | 81 parse / **6 malformed** | **87 parse / 0 malformed** |
| `~/.agents/skills` (36) | 36 parse | 36 parse — **unchanged** |

The six casualties return with descriptions byte-identical to the ones Claude Code displays.
`block-literal` and `block-folded` keep their exact multi-line values (the YAML path never ran the
fallback). **No file that parses today changes value** — that is the property that makes this
additive rather than a behaviour change for existing users.

### 5. The silence is a separate defect from the strictness

`LoadSkills` returns a source. `ListSkills` returns the skips. A host that calls the former — which
is every host in every README and every example — loads 69 of 75 parseable skills and is told
nothing. Fixing the parser removes today's six; it does not remove the class. The next
`broken-tab`, the next unreadable file, the next duplicate name still vanishes quietly.

And the reason string is not actionable: `malformed-frontmatter` does not say *tab indentation at
line 3*, which is one keystroke from fixed. The parser error already exists in all seven ports and
is discarded in all seven.

## Decision

Take **all three of the issue's options**, because they fix three different defects, and add a
fourth that the measurement forced.

1. **(1), inverted — YAML first, line-wise fallback for `name`/`description` only.** With the
   column-0, first-wins and refused-opener guards above. SPEC §3's "NOT a hand-rolled `key: value`
   split" is amended to say what it was protecting — that a hand-rolled split must never *pre-empt*
   a real parser — rather than forbidding a rescue path that runs only after one has failed.
2. **(3) — the skip reason carries the parser error.** `malformed-frontmatter: mapping values are
   not allowed here` / `Tabs are not allowed as indentation at line 3`. Kept as a *separate field*
   (`detail`), not concatenated into `reason`, so the typed reason stays byte-identical across
   ports while the message stays native (the messages differ per library and must not be compared
   for parity).
3. **(2) — `LoadSkills` surfaces skips.** A `Warn` hook / returned skips, so the default path
   reports "6 skills failed to parse" instead of quietly loading 69 of 75. This is the only one of
   the three that survives the parser fix, because it is about the class, not the instance.
4. **Close the Clojure divergence in the same change.** `toolnexus.frontmatter` must become a real
   YAML parse plus the same fallback, or the byte-identity claim has to be retracted from SPEC §0,
   README and the docs site. 42 of 87 files is not a footnote. ADR 0009's reasoning (koine declines
   to own YAML) explains *why* the subset exists; it does not license the subset to be the
   contract. A YAML library on the JVM+JS hosts (SnakeYAML / `yaml`) behind one `.cljc` seam is the
   expected route, and is a prerequisite for the fix above, not a follow-up to it.

**Rejected: keeping strict and documenting it.** Defensible in the abstract — the file *is*
invalid YAML — and that was my starting position. It does not survive §3: the writing tool accepts
it, the user has no signal, and toolnexus is the only party in the loop that thinks a standard is
being enforced. The cost of leniency here is bounded to files that are already broken; the cost of
strictness is a skill that exists and cannot be seen.

**Rejected: matching Claude Code's `name` ← directory-name fallback.** Out of scope. SPEC §3
requires `name`, `missing-name` is an already-typed, already-consistent skip, and changing it moves
the contract for every port on a question #93 does not raise.

## Corrections — two predictions that did not survive

- **"Go and Java are the strict ones; JS/Python/Elixir/C# probably differ."** They do not. Six
  ports agree on all 17 fixtures and on both real corpora, to the file. The fault line is not
  strict-vs-lenient YAML libraries; it is YAML-vs-not-YAML, and it runs around the Clojure port
  alone. I went looking for library leniency drift and found a hand-rolled parser instead.
- **"Claude Code parses the frontmatter with `yaml` in non-strict / error-recovering mode."**
  Tested and false. `strict: false` changes none of the seven error classes, and `parseDocument`'s
  recovery turns the disputed description into a *map*, not the sentence Claude Code actually
  displays. The leniency is not a parser option; it is a different read.

## Consequences

- Six real skills per machine come back, with the descriptions their authors wrote. On this
  machine the skipped-and-silent count goes 6 → 0 without any file being edited.
- The parity claim becomes true instead of aspirational — or is retracted. Either is better than
  the present state, where the docs say byte-identical and one port loses 64% of a catalog.
- A host that upgrades sees *more* skills and, for the first time, a warning when it sees fewer.
  Nothing a host loads today changes value.
- SPEC §3 gains an explicit statement of the boundary: **compatibility is defined against the tool
  that writes the file, and is pinned by fixtures, not by a YAML version.**

## Sizing

Per port, for items 1 + 2 + 3 (item 4 is Clojure-only and separate):

| port | size | why |
|---|---|---|
| js | **S** | one fallback function; `parse` already throws, the catch site exists |
| python | **S** | same shape; `yaml.YAMLError` already caught |
| golang | **S** | same shape; `parseFrontmatter` already returns `malformed` |
| elixir | **S** | same shape; `safe_yaml/1` already funnels failures |
| java | **S/M** | same shape, plus `SkillSkip` gains a field and its `Map<String,Object>` return needs the detail threaded |
| csharp | **S/M** | same shape; `SkillSkip` is a record — adding `Detail` touches its call sites |
| clojure | **L** | item 4: replace `toolnexus.frontmatter` with a real YAML parse across two `.cljc` hosts, then the same fallback. This is the whole cost of the change |

The `Warn` hook on `LoadSkills` (item 2) is **S** everywhere the port already has a hook slot and
**M** where it does not; that is a §8-adjacent surface decision and should be checked against
ADR 0014 (hook composition) before it is written into a spec delta.

## Gate — what would earn this an "Accepted"

1. The 17 fixtures land as a **shared** fixture set (like `examples/`), and all seven ports produce
   the *same* accept/skip table over it in CI. Today's run is the baseline to beat.
2. `~/.claude/skills`: 0 `malformed-frontmatter` in all seven ports, and the six rescued
   descriptions compared byte-for-byte against the strings Claude Code displays.
3. A file the fallback must still refuse (`broken-flow`) keeps its `name` and gains **no** invented
   description, in all seven.
4. A `LoadSkills` caller that passes no hook still loads byte-identically to 0.18.1 on a corpus
   with no malformed files (`~/.agents/skills`: 36/36, unchanged).

## Out of scope, found while measuring — file separately

Duplicate-name resolution also diverges. On `~/.claude/skills`, `docx`/`pdf`/`pptx` each exist
twice; all seven ports report 12 `duplicate-name` skips, but js/golang/elixir keep the
`synced/6636…` copies while python/java/csharp keep the top-level ones. The *names* agree, so no
catalog entry is missing — but the winning **file**, and therefore the skill's `content`, differs
by port. SPEC §3 specifies first-wins and says nothing about discovery order, so the winner falls
out of each ecosystem's directory walk. That is the same class of defect as this ADR, in a
different clause, and deserves its own issue.
