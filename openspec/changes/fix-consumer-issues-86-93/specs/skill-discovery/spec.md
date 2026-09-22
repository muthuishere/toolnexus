## ADDED Requirements

### Requirement: A skip record carries the parser's own error

A skip record SHALL carry a `detail` field holding the native parser's error text for the file it
refused — for example the library's own "mapping values are not allowed here" or "tabs are not
allowed as indentation at line 3".

The typed `reason` SHALL stay byte-identical across ports and SHALL NOT have the detail
concatenated into it: the reason is compared for parity, the detail is native to each ecosystem's
library and SHALL NOT be compared.

#### Scenario: A malformed file reports what is wrong with it

- **WHEN** a SKILL.md is refused for tab indentation
- **THEN** the skip's `reason` is the typed malformed-frontmatter reason, unchanged
- **AND** its `detail` carries the parser's own message naming the tab and the line

#### Scenario: Parity compares the reason, never the detail

- **WHEN** the same corpus is loaded in every port
- **THEN** the typed reasons match exactly across ports, and the details are allowed to differ

### Requirement: Loading skills returns what it skipped

The primary skill-loading entry point SHALL return its skips **as data on its result**, so that a
host learns a skill was dropped without calling the separate inventory surface. The returned
skips are what cross-port conformance compares; a hook shape differs per port and SHALL NOT be
the carrier of this contract. A port that already owns a warning slot MAY additionally call it,
but SHALL NOT rely on it in place of the returned data.

Loading a corpus where some files are refused SHALL NOT be indistinguishable from loading one
where none are. A host that ignores the returned skips SHALL load byte-identically to before this
capability existed.

#### Scenario: The skips come back as data

- **WHEN** a host loads a directory in which six of eighty-seven files are refused
- **THEN** the load result carries six skip records, each with its typed reason and its detail
- **AND** the host needs no hook and no separate inventory call to see them

#### Scenario: Conformance compares the returned data

- **WHEN** the same corpus is loaded in every port
- **THEN** the returned skip records match across ports on their typed reasons, whatever warning
  slot each port may additionally call

#### Scenario: An unchanged host is unchanged

- **WHEN** a host that ignores the returned skips loads a corpus in which nothing is refused
- **THEN** the skills it loads are byte-identical to those loaded before this capability existed

### Requirement: The skills prompt is ordered by code point

The §0.10 skills prompt SHALL list skills in **Unicode code-point order over the skill name**, in
every port. The `skill` tool's not-found "available skills" list SHALL use the same rule.

A locale-aware comparison SHALL NOT be used: it is unstable across machines, so the same code over
the same directory can emit a differently ordered prompt on two hosts — a defect in any port, and
fatal where the prompt is pinned byte-identical. A UTF-16 code-unit comparison SHALL NOT be used
either; it diverges above U+FFFF, exactly as it does in discovery.

This is the same comparison rule as discovery's tie-break, applied to a second, separately
implemented ordering. Discovery's order decides *which file* a name resolves to; this one decides
*what the model reads*, and the two were implemented independently.

#### Scenario: The prompt order does not depend on the host

- **WHEN** the same `skills/` directory is loaded on two machines with different locale data
- **THEN** the emitted skills prompt is byte-identical, because the order is by code point and not by locale

#### Scenario: Names above the BMP order by code point

- **WHEN** skill names differ above U+FFFF
- **THEN** every port orders them by code point, not by UTF-16 code unit

#### Scenario: The not-found list follows the same rule

- **WHEN** the `skill` tool is called with an unknown name and lists the available skills
- **THEN** that list is in the same code-point order as the skills prompt, in every port

### Requirement: Directory reads that feed shipped output are explicitly sorted

Every directory read whose results reach user- or model-visible output SHALL be **explicitly
sorted**; a port SHALL NOT rely on the order its platform's directory enumeration happens to
return, which promises nothing and is not stable across runs on some filesystems.

An **absent** sort is the same defect class as a wrong one, and it is invisible to an audit that
greps for comparators, because the defective site has none. Each port SHALL therefore audit its
directory **reads** — its enumeration calls — and not only its comparisons, and SHALL confirm
each such site is explicitly ordered.

Where a read feeds a listing that is later truncated, an unsorted read changes **which** entries
reach the model, not merely their order. It is a content defect, not a cosmetic one.

#### Scenario: An unsorted enumeration is a defect even with no comparator present

- **WHEN** a site reads a directory and ships the entries with no ordering applied
- **THEN** it violates this requirement, although an audit for comparators would not have found it

#### Scenario: The audit covers reads, not only comparisons

- **WHEN** a port reports its ordering audit
- **THEN** the audit enumerates its directory-read sites and states, for each one that ships output, how it is ordered

### Requirement: A capped sample is collected, sorted, then truncated

The `<skill_files>` sample SHALL be produced by **collecting every candidate, sorting by the path
relative to the skill directory in plain Unicode code-point order, and only then truncating to the
cap**. There SHALL be no depth rule, no per-directory ordering during traversal, and no cap
applied mid-walk.

The order SHALL be a function of the **file set and the cap alone**. A per-directory sort is a
function of the *traversal*, so it obliges every port to reproduce the same stack discipline to
agree — including pushing continuations to reproduce an interleaving — and a port that merely
sorts each directory read while keeping a plain stack emits subdirectories in the wrong order
while appearing sorted. Discovery's depth rule SHALL NOT be reused here: it exists to decide which
file a duplicate *name* resolves to, and applying it to a flat listing interleaves the entries
differently.

**Sorting before the cap is the load-bearing half.** Truncating during the walk lets the
filesystem decide which files the model sees — a different sample on a different machine, and on
some filesystems between two runs of the same one.

#### Scenario: The sample is sorted globally, not per directory

- **WHEN** a skill directory contains `a-root.txt`, `z-root.txt` and `alpha/f.txt`
- **THEN** the sample orders them `a-root.txt`, `alpha/f.txt`, `z-root.txt`, by relative path
- **AND** not `a-root.txt`, `z-root.txt`, `alpha/f.txt`, which a depth-first or per-directory rule would produce

#### Scenario: A directory beside a file sharing its prefix

- **WHEN** a skill directory contains both a directory `alpha/` and a file `alpha-b.txt`
- **THEN** `alpha-b.txt` precedes `alpha/f.txt`, because `-` (U+002D) precedes `/` (U+002F) in the flat relative path
- **AND** a per-level name comparison, which would order `alpha/` first, is not in force

#### Scenario: The cap selects from the sorted set, not from the walk

- **WHEN** a skill directory holds more candidates than the cap, and a file that sorts early is reached late in the walk
- **THEN** that file is present in the capped sample and a file that sorts late is absent
- **AND** the capped sample does not depend on the order the filesystem returned

### Requirement: Listings emit and sort on the same slash-separated path

Every ordered or capped listing that reaches the model SHALL emit relative paths using `/` as the
separator on **every** platform, and SHALL sort on the **same string it emits**.

Two reasons, both load-bearing: the output is cross-port byte-identical only if the separator is;
and a listing that sorts on one string while displaying another is ordering by one thing and
showing another, which is how a port passes its own ordering test while emitting a different
sequence.

#### Scenario: A Windows host emits forward slashes

- **WHEN** a listing is produced on a platform whose native separator is not `/`
- **THEN** the emitted relative paths use `/`, matching every other platform and port

#### Scenario: The sort key is the emitted string

- **WHEN** a listing is ordered
- **THEN** the key it sorts on is the same string it emits, not a differently-shaped path

### Requirement: Discovery order is specified, not left to the walk

First-wins resolution of duplicate skill names SHALL be decided by a specified, deterministic
discovery order, identical in every port: **directories in the order the caller supplied them**,
and within a directory by **depth ascending, then by Unicode code point**, over the path relative
to that directory's logical base. The first skill found for a name wins.

Depth is the number of path segments. Sorting by depth first is what makes the rule everyone
states — *a top-level skill beats a nested copy of the same name* — true for **every** name
rather than for some. Under a pure code-point sort the winner depends on the skill's own first
letter relative to a sibling directory's name: `docx`, `pdf` and `pptx` sort before `synced/` and
win, while `xlsx` sorts after it and loses to `synced/<uuid>/xlsx`. That is not a rule, and it is
not what any port intended.

The code-point comparison SHALL be the **tie-break within a depth**, not the whole order. It
SHALL be a **Unicode code-point comparison of the whole path**
relative to that root's logical base. It SHALL NOT use locale collation, SHALL NOT case-fold, and
SHALL NOT compare path segments separately. A port whose platform comparator is culture-sensitive
by default — as the natural string comparison is on the JVM and in .NET — SHALL select an
ordinal, culture-invariant comparison explicitly, so that non-ASCII paths do not order differently
per port or per host locale.

Ordinal is necessary but not sufficient. **Code-point order is normative, and it is not the
platform default on a UTF-16 host**, where the natural comparator orders by code *unit*: a
surrogate pair then sorts before U+E000..U+FFFF instead of after it. Every UTF-16-backed port —
those on the JVM, on .NET and on JavaScript, including a dual-host port's JVM and JavaScript
hosts, which SHALL agree with each other — SHALL compare by code point explicitly rather than
relying on its platform's ordinal string comparison. Ports whose strings iterate as code points
or runes natively SHALL still sort explicitly.

Code-point and code-unit order are identical for all ASCII and BMP paths, so no real corpus
changes; the requirement exists so that a divergence which would fire only on an astral-plane
filename, only on some hosts, is not planted now.

A symlinked skill SHALL sort at the path where it was **discovered**, never at its target's path.

A port SHALL NOT let its ecosystem's directory-walk order decide the winner. A port whose
directory listing is not already sorted SHALL sort explicitly.

#### Scenario: A duplicate name resolves to the same file in every port

- **WHEN** a corpus contains two skills of the same name in different subdirectories of one directory
- **THEN** every port loads the one whose path sorts first under depth-then-code-point, relative to that directory's logical base, with the same content

#### Scenario: A shallower path beats a nested one whatever the name sorts like

- **WHEN** one directory contains both `xlsx/SKILL.md` and `synced/<uuid>/xlsx/SKILL.md`, declaring the same skill name, where `xlsx` sorts **after** the nested copy's first segment `synced`
- **THEN** every port loads `xlsx/SKILL.md`, because depth is compared before code point
- **AND** `synced/<uuid>/xlsx/SKILL.md` is the duplicate-name skip, in every port
- **AND** the same holds for a name such as `docx` that sorts before `synced`, so the winner does not depend on the skill's first letter

#### Scenario: The comparison is ordinal, not cultural

- **WHEN** two candidate paths at the same depth differ only in non-ASCII characters whose order under a locale collation differs from their code-point order
- **THEN** every port resolves by code-point order, and the winner does not change with the host's locale

#### Scenario: An astral-plane path sorts by code point, not code unit

- **WHEN** two candidate paths at the same depth, carrying the same skill name, differ in that one contains a character above U+FFFF (for example U+1F600) and the other a character in U+E000..U+FFFF
- **THEN** every port resolves by code-point order, in which the astral character sorts after the BMP one
- **AND** the winner is the same on a UTF-16-backed host as on a port whose strings iterate as code points, and does not vary by host

#### Scenario: A symlink sorts where it was found

- **WHEN** a skill is discovered through a symlink whose target path would sort differently
- **THEN** it is ordered by the path at which it was discovered, not by its target

#### Scenario: The caller's directory order decides between directories

- **WHEN** the same skill name exists in two directories supplied to the loader
- **THEN** the one in the directory the caller listed first wins, in every port
- **AND** reversing the caller's list reverses the winner

#### Scenario: An unsorted filesystem listing does not change the winner

- **WHEN** a port's directory listing returns entries in an arbitrary order
- **THEN** the port sorts before resolving, and the winner is unchanged

#### Scenario: The skipped duplicates still agree

- **WHEN** duplicates are resolved
- **THEN** the set of duplicate-name skips is the same in every port, as it is today

## MODIFIED Requirements

### Requirement: Frontmatter parsed with a standard YAML parser

SKILL.md frontmatter SHALL be parsed with a standard YAML parser (each port using its ecosystem
library) over the `---`-fenced header block — NOT a hand-rolled `key: value` split — so YAML block
scalars (folded `>`, literal `|`), chomping indicators, quoting, and multi-line values all resolve
correctly. Scalar values (string/number/bool) SHALL be coerced to string and trimmed so block-scalar
trailing newlines do not leak, keeping the ports byte-identical. Malformed YAML SHALL fail
gracefully (empty frontmatter; the skill is skipped for a missing `name`) and SHALL NOT crash
discovery.

A hand-rolled read SHALL NEVER pre-empt the YAML parse. On frontmatter the YAML parse has
**failed to yield usable string fields**, and only then, a port SHALL attempt a line-wise rescue
for `name` and `description` alone, reading each as the remainder of its line.

Ports' YAML libraries disagree about what "refused" means: on frontmatter such as
`description: [unterminated`, some throw while others recover it into a sequence. Both paths
SHALL reach the same observable outcome, so the rescue SHALL trigger when the parse **throws**,
when it yields a **non-mapping**, or when it yields a mapping whose `name` or `description` is
present but **not a scalar**.

"Not a scalar" is the operative test, and it is narrower than "not a string". A YAML **scalar** —
a string, integer, float or boolean — SHALL be coerced to its string form and trimmed, exactly as
the existing coercion rule above requires: `name: 123` loads as `123` and `description: true`
loads as `true`. Only a **mapping or a sequence** where a scalar was required is structurally
wrong, and only that triggers the rescue. Treating a non-string scalar as structurally wrong
would break parity in the opposite direction, against behaviour existing cross-port tests already
pin.

The invariant that decides every remaining case: a file SHALL NEVER gain an **invented**
description, and SHALL NEVER silently keep a **structurally wrong** one. A recovered sequence or
mapping where a scalar was required is structurally wrong and SHALL NOT be coerced into one.

The rescue SHALL be bounded by
three guards: only keys at column 0 are considered; the first occurrence of a key wins; and a
value that is empty or whose first non-space character is one of `|`, `>`, `&`, `*`, `[`, `{` or
`!` SHALL be **refused, not guessed**, so a half-broken block scalar degrades to an absent
description rather than to its indicator character.

Frontmatter that the YAML parser accepts SHALL keep YAML's semantics byte for byte; no file that
parses today may change value. Every port SHALL implement this parse — including ports whose
ecosystem lacks a first-party YAML library, which SHALL adopt one rather than carry a documented
subset, because a subset makes the cross-port byte-identity claim false.

Compatibility is defined against the tool that writes these files and is pinned by a shared
fixture set, not by a YAML version. `spikes/issues/93/fixtures/` is that arbiter: every port
SHALL produce an **identical table** over its 17 files — 14 accepted and 3 skipped — so that a
divergence no port could notice alone, one library throwing where another recovers, is caught by
the fixture rather than by a user.

The comparison SHALL be on the **parsed description string** and the **typed skip reason**, not
merely on the accept/skip verdict. A verdict-only comparison is insufficient: a port whose YAML
library treats an inline `#` differently accepts the same file while carrying a different
description, and would be called conformant while silently disagreeing. Skip **details** remain
native per library and SHALL NOT be compared.

#### Scenario: An inline hash opens a comment, and the string is compared

- **WHEN** a frontmatter description reads `Tag things with #stockloop`, where ` #` opens a YAML comment
- **THEN** the parsed description is exactly `Tag things with`, in every port
- **AND** conformance compares that string, so a port whose parser keeps the `#…` tail fails rather than passing on a matching verdict

#### Scenario: A refused flow value keeps the name and loses the description

- **WHEN** a frontmatter `description` opens with `[` and is unterminated, which some ports' libraries throw on and others recover into a sequence
- **THEN** the skill is **accepted** with its name and **no** description, in every port — it is not skipped
- **AND** this row is the observable proof that the non-string rescue trigger is implemented, because the throwing and recovering libraries converge only through it

#### Scenario: Folded block scalar description resolves

- **WHEN** a SKILL.md has `description: >` followed by indented multi-line content
- **THEN** the parsed description is the folded text (newlines collapsed to spaces), not the literal `>`
- **AND** it has no leading/trailing whitespace

#### Scenario: Literal block scalar preserves newlines

- **WHEN** a SKILL.md has `description: |` with multiple indented lines
- **THEN** the parsed description preserves the line breaks between them

#### Scenario: Single-line value still parses (no regression)

- **WHEN** a SKILL.md has `description: some text` on one line
- **THEN** the parsed description is `some text`

#### Scenario: Malformed YAML does not crash discovery

- **WHEN** a SKILL.md has malformed YAML frontmatter
- **THEN** discovery continues without raising, that skill has empty frontmatter (skipped if `name`
  is missing), and other valid skills still load

#### Scenario: An unquoted colon in a description is rescued

- **WHEN** a SKILL.md has a plain-scalar `description` containing an unquoted `": "`, which YAML refuses
- **THEN** the rescue reads the whole remainder of the line as the description
- **AND** the skill loads rather than being skipped

#### Scenario: The rescue never runs on frontmatter YAML accepted

- **WHEN** a SKILL.md carrying a literal or folded block scalar parses as YAML
- **THEN** the rescue does not run and the value is the YAML value, byte for byte

#### Scenario: A refused opener is not guessed at

- **WHEN** YAML refuses frontmatter whose `description` value begins with a block-scalar or flow indicator
- **THEN** the rescue refuses that value and the skill has no description, rather than a description of the indicator character

#### Scenario: A genuinely broken file is still refused

- **WHEN** a SKILL.md carries an unterminated flow sequence that YAML refuses and whose values the guards reject
- **THEN** the skill keeps its typed skip and gains no invented description, in every port

#### Scenario: A throwing parser and a recovering parser reach the same outcome

- **WHEN** a SKILL.md carries `description: [unterminated`, which some ports' YAML libraries throw on and others recover into a sequence
- **THEN** every port reaches the same observable outcome: the rescue runs in both cases, because a recovered non-string value triggers it exactly as a thrown parse does
- **AND** no port keeps the recovered sequence as the description

#### Scenario: A non-string scalar coerces rather than triggering the rescue

- **WHEN** a frontmatter carries `name: 123` and `description: true`, which parse as an integer and a boolean
- **THEN** they are coerced to `123` and `true` and the skill loads normally
- **AND** the rescue does not run, because a scalar is not structurally wrong

#### Scenario: A structurally wrong value is never coerced

- **WHEN** a YAML parse succeeds but yields a `description` that is a sequence or a mapping rather than a scalar
- **THEN** that value is not coerced into a description
- **AND** the file either gains a rescued line-wise description or has none, never an invented one

#### Scenario: A non-mapping document triggers the rescue

- **WHEN** a YAML parse succeeds but yields something other than a mapping for the frontmatter block
- **THEN** the rescue runs, under the same guards as a thrown parse

#### Scenario: Every port produces the same table over the shared fixtures

- **WHEN** the fixture set at `spikes/issues/93/fixtures/` is loaded in every port
- **THEN** 14 of its 17 files are accepted and 3 are skipped, identically file for file, including ports that previously carried a hand-rolled subset
- **AND** each accepted file's parsed description string and each skip's typed reason match the reference table exactly
- **AND** a port whose YAML library behaves differently from the others is caught by that table rather than by a user
