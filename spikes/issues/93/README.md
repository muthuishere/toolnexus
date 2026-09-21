# spike — issue #93: which SKILL.md files does each port actually load?

Hermetic (no network, no LLM). Runs **all seven ports' own `ListSkills`** over one
corpus and prints an accept/skip matrix, then compares that against **what Claude Code
itself accepts** and against a prototype of the issue's option (1).

Everything below is captured output, not a prediction.

```
python3 harness.py fixtures            # the 17-fixture matrix
python3 harness.py ~/.claude/skills    # a real corpus; prints only the rows ports disagree about
python3 prototype/lenient.py <dir>     # the option-(1) prototype
claude plugin validate probe           # Claude Code's own verdict on the same fixtures
```

Runners live in `runners/<port>/` and do nothing but call that port's `ListSkills`
and print `{skills:[{location}],skipped:[{location,reason}]}`. `probe/` is the
fixture set wrapped as a Claude Code plugin so `claude plugin validate` will read it.

One-time setup the harness assumes: `js/` built (`npm run build`), the Python port
installed into a venv at `/tmp/claude-501/i93venv` (`pip install -e python/`), the
Java jar built (`./gradlew jar`) and `javac -d runners/java/out runners/java/Run.java`
against it, `dotnet build runners/csharp`. Go/Elixir/Clojure need nothing.

---

## 1. The fixture matrix — the ports already disagree

`python3 harness.py fixtures`, 2026-09-21, ports at 0.18.1:

| fixture | js | python | golang | java | csharp | elixir | clojure | Claude Code |
|---|---|---|---|---|---|---|---|---|
| plain | ok | ok | ok | ok | ok | ok | ok | ok |
| **colon-space** (`Trigger on: …`) | **malformed** | **malformed** | **malformed** | **malformed** | **malformed** | **malformed** | **ok** | **ok** |
| colon-space-quoted | ok | ok | ok | ok | ok | ok | ok | ok |
| colon-space-single | ok | ok | ok | ok | ok | ok | ok | ok |
| url-colon (`https://…`) | ok | ok | ok | ok | ok | ok | ok | ok |
| hash-inline (`#stockloop` + `Trigger on:`) | ok | ok | ok | ok | ok | ok | ok | ok |
| **block-literal** (`description: \|`) | ok | ok | ok | ok | ok | ok | **malformed** | ok |
| **block-folded** (`description: >`) | ok | ok | ok | ok | ok | ok | **malformed** | ok |
| **list-value** (`[bash, read]`) | ok | ok | ok | ok | ok | ok | **malformed** | ok |
| **list-block** | ok | ok | ok | ok | ok | ok | **malformed** | ok |
| **nested-map** | ok | ok | ok | ok | ok | ok | **malformed** | ok |
| **anchors** (`&d` / `*d`) | ok | ok | ok | ok | ok | ok | **malformed** | ok |
| broken-flow (`[unterminated`) | malformed | malformed | malformed | malformed | malformed | malformed | malformed | **ok** |
| broken-tab (tab indentation) | malformed | malformed | malformed | malformed | malformed | malformed | malformed | **error** |
| broken-unclosed (no closing `---`) | missing-name | missing-name | missing-name | missing-name | missing-name | missing-name | malformed | warn (no frontmatter) |
| no-frontmatter | missing-name | missing-name | missing-name | missing-name | missing-name | missing-name | missing-name | warn (no frontmatter) |
| no-name | missing-name | missing-name | missing-name | missing-name | missing-name | missing-name | missing-name | ok (name ← dir) |

Totals: six ports **11 ok / 6 skipped**; **clojure 6 ok / 11 skipped**.

The "Claude Code" column is `claude plugin validate probe` on **v2.1.278**: the only
file it errors on is `broken-tab` ("YAML frontmatter failed to parse: Unexpected
token. At runtime this skill loads with empty metadata"). It does not complain about
`colon-space`, `broken-flow`, or the probe-only extras in `probe/skills/p-*`
(undefined alias `*nope`, duplicate `description:` keys, `a: b: c: …`, `{unterminated`).

## 2. A real corpus — `~/.claude/skills` (87 SKILL.md, symlinks followed)

```
js       69 ok / 18 skipped   {malformed-frontmatter: 6, duplicate-name: 12}
python   69 ok / 18 skipped   {malformed-frontmatter: 6, duplicate-name: 12}
golang   69 ok / 18 skipped   {malformed-frontmatter: 6, duplicate-name: 12}
java     69 ok / 18 skipped   {malformed-frontmatter: 6, duplicate-name: 12}
csharp   69 ok / 18 skipped   {malformed-frontmatter: 6, duplicate-name: 12}
elixir   69 ok / 18 skipped   {malformed-frontmatter: 6, duplicate-name: 12}
clojure  33 ok / 54 skipped   {malformed-frontmatter: 42, duplicate-name: 12}
```

The issue's "6 of 76" reproduces exactly: **6 `malformed-frontmatter`**, every one of
them an unquoted `": "` inside a plain-scalar `description` —
`volentis-timesheet`, `volentis-invoice`, `publish-invoice`, `book-management`,
`monthlyshare`, `reclaim`. All six are live in Claude Code, with their full
frontmatter descriptions (this session's own skill listing quotes
`volentis-timesheet`'s "…Trigger on: update the timesheet, …" verbatim).

`~/.agents/skills` (36 SKILL.md): six ports **36 ok / 0 skipped**, clojure **13 ok / 23
malformed** — i.e. on a corpus where the YAML ports are perfect, the Clojure port
loses **64%** of the catalog.

## 3. The option-(1) prototype (`prototype/lenient.py`)

**YAML first, line-wise fallback** — the inverse of the order the issue proposes, and
that inversion is what makes it safe. Line-wise *first* misparses `description: |`,
`description: >` and any plain scalar continued on the next line, because rest-of-line
is empty or a block marker there. Running it only over frontmatter a real YAML parser
has already refused means it can never touch a file whose YAML semantics matter.

The fallback rescues only `name` and `description`, only at column 0, first-wins, and
**refuses any value opening `| > & * [ { !` or empty** — so a half-broken block scalar
degrades to "no description", never to garbage.

Captured:

| corpus | strict YAML (six ports) | prototype |
|---|---|---|
| fixtures (17) | 11 ok / 3 malformed / 3 missing-name | **14 ok / 3 missing-name** |
| `~/.claude/skills` (87) | 81 parse / 6 malformed | **87 parse / 0 malformed** |
| `~/.agents/skills` (36) | 36 parse | 36 parse (unchanged) |

Values preserved, checked field by field:

```
block-literal  desc='First line of the description.\nSecond line, with a colon: still fine …'   (YAML path, untouched)
block-folded   desc='A folded description that runs across two source lines.'                    (YAML path, untouched)
list-value / nested-map / anchors   unchanged — YAML succeeded, the fallback never ran
colon-space    desc='Work out billable hours … Trigger on: …'   ← rescued, full sentence
broken-flow    desc=''  ← name rescued, value refused rather than guessed
```

The six real casualties come back with **byte-identical** descriptions to the ones
Claude Code shows. No fixture the six ports accept today changes value.

Residual difference from Claude Code, stated rather than hidden: `no-name` and
`broken-unclosed` still skip as `missing-name`, because toolnexus requires `name`
(SPEC §3) while Claude Code falls back to the directory name. That is a separate,
already-specified rule and this spike does not touch it.

## 4. Second finding, not in the issue: duplicate-name resolution diverges too

On `~/.claude/skills`, `docx` / `pdf` / `pptx` exist twice (a plugin-synced copy and a
personal copy). All seven ports report 12 `duplicate-name` skips — but **which copy
wins differs**: js/golang/elixir keep the `synced/6636…` copies, python/java/csharp
keep the top-level ones. Same names, different files, therefore different `content`.
First-wins is specified; **discovery order is not**, so it falls out of each
ecosystem's directory walk. Worth its own issue.
