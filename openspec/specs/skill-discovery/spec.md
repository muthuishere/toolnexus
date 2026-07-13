# skill-discovery Specification

## Purpose
TBD - created by archiving change follow-symlinked-skills. Update Purpose after archive.
## Requirements
### Requirement: Skill discovery follows symlinked directories and files

Skill discovery SHALL follow symlinked directories and symlinked `SKILL.md` files when recursively
walking a skills root, so skills reachable only through a symlink are discovered (matching opencode's
`symlink: true` glob). Discovery SHALL guard against symlink cycles by tracking resolved real paths
already visited, and a broken or unreadable symlink SHALL be skipped without aborting the walk.

#### Scenario: Symlinked skill directory is discovered

- **WHEN** a skills root contains a symlink to an out-of-tree directory that holds a `SKILL.md`
- **THEN** discovery descends the symlink and registers that skill
- **AND** skills in real (non-symlinked) subdirectories are still discovered

#### Scenario: Symlink cycle does not hang discovery

- **WHEN** a skills root contains a symlink that forms a directory cycle
- **THEN** discovery visits each resolved directory at most once and terminates

#### Scenario: Broken symlink is skipped

- **WHEN** a skills root contains a symlink whose target does not exist
- **THEN** discovery skips that entry and continues without error

### Requirement: The skill tool's sibling-file sampler follows symlinks

The `skill` tool's sibling-file sampler SHALL follow symlinked directories and include symlinked files
(other than `SKILL.md`) when sampling the up-to-10 file list, using the same cycle guard as discovery,
so a symlinked skill's resources appear in the `<skill_files>` block.

#### Scenario: Symlinked resource appears in the sampled file list

- **WHEN** a loaded skill's directory reaches resource files through a symlinked subdirectory
- **THEN** those files are eligible for the sampled `<skill_files>` list (subject to the 10-file cap)

### Requirement: Frontmatter parsed with a standard YAML parser

SKILL.md frontmatter SHALL be parsed with a standard YAML parser (each port using its ecosystem
library) over the `---`-fenced header block — NOT a hand-rolled `key: value` split — so YAML block
scalars (folded `>`, literal `|`), chomping indicators, quoting, and multi-line values all resolve
correctly. Scalar values (string/number/bool) SHALL be coerced to string and trimmed so block-scalar
trailing newlines do not leak, keeping the five ports byte-identical. Malformed YAML SHALL fail
gracefully (empty frontmatter; the skill is skipped for a missing `name`) and SHALL NOT crash
discovery.

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

### Requirement: Skills may be supplied as data or a provider, not only directories

The toolkit SHALL accept skills from three composable sources: on-disk directories (today's
`SkillsDir`), an in-memory list of skill definitions, and a lazy provider callback invoked at load
time. A skill definition SHALL carry `name`, `description`, `content` (the SKILL.md body), an
OPTIONAL logical `resources` list, and an OPTIONAL logical `base`. When more than one source yields
the same skill `name`, the existing first-wins dedupe SHALL apply across all sources. A caller
supplying only `SkillsDir` (no data, no provider) SHALL observe byte-identical behavior to today.

#### Scenario: Skill supplied as data with no directory

- **WHEN** the toolkit is built with a single in-memory skill definition `{name:"x", description:"d", content:"body"}` and no `SkillsDir`
- **THEN** the system prompt catalog lists `x` with description `d`, and invoking the `skill` tool with `name:"x"` returns a progressive-disclosure block containing `body`

#### Scenario: Provider callback contributes skills

- **WHEN** a provider callback returns two skill definitions and no other source is configured
- **THEN** both skills are discoverable and loadable exactly as if passed in the in-memory list

#### Scenario: Provider failure is isolated

- **WHEN** the provider callback returns an error but a directory source also yields skills
- **THEN** the directory-sourced skills still load and the toolkit is built (the failing source is isolated, mirroring MCP per-server isolation)

#### Scenario: Directory, data, and provider compose with first-wins dedupe

- **WHEN** a directory source and an in-memory source each yield a skill named `x`
- **THEN** exactly one `x` is exposed, resolved by the existing first-wins rule, and the other source's `x` is dropped

### Requirement: Per-agent skill allowlist

The toolkit SHALL accept a `SkillsFilter` map keyed on skill `name` that selects which discovered
skills are exposed, using semantics identical to the MCP per-server tools filter and the builtins
filter: a nil or empty map means all skills; a map containing at least one `true` value is an
allowlist exposing only names mapped `true`; a map containing only `false` values is a drop-list
over the all-on baseline; unknown names are ignored and logged once at warn level. The filter SHALL
be applied after discovery and before the `skill` tool is built, so the prompt catalog and the
tool's internal lookup agree.

#### Scenario: Allowlist exposes only enabled skills

- **WHEN** three skills `a`, `b`, `c` are discovered and `SkillsFilter` is `{a:true, b:true}`
- **THEN** the prompt catalog and `skill` tool expose exactly `a` and `b`, and loading `c` returns the "not found" result

#### Scenario: Drop-list removes named skills

- **WHEN** three skills `a`, `b`, `c` are discovered and `SkillsFilter` is `{c:false}`
- **THEN** exactly `a` and `b` are exposed

#### Scenario: Unknown allowlist name is ignored and warned

- **WHEN** `SkillsFilter` is `{a:true, nope:true}` and no skill named `nope` exists
- **THEN** `a` is exposed, no error is raised, and a single warn-level log records the unmatched name

#### Scenario: Nil and empty filter expose all skills

- **WHEN** `SkillsFilter` is nil, or is an empty non-nil map
- **THEN** all discovered skills are exposed (backward-compatible)

### Requirement: List-and-validate skill inventory

The system SHALL provide a list-only inventory operation that discovers and validates skills from
the same sources the toolkit accepts, returning the successfully parsed skills together with a
structured list of skipped candidates, each carrying a typed reason drawn from `missing-name`,
`malformed-frontmatter`, `duplicate-name`, and `unreadable`. The operation SHALL NOT build a
toolkit, wire the `skill` tool, or leave any resource open.

#### Scenario: Inventory reports parsed skills and typed skip reasons

- **WHEN** a source contains one valid SKILL.md, one with no `name`, one with malformed YAML, and a duplicate of the valid name
- **THEN** the inventory lists the one valid skill and reports three skips with reasons `missing-name`, `malformed-frontmatter`, and `duplicate-name`

#### Scenario: Inventory does not wire a toolkit

- **WHEN** the inventory operation returns
- **THEN** no `skill` tool has been constructed and no file handle or child process remains open

### Requirement: Logical output base for non-filesystem skill sources

For skills supplied as data or via a provider, the `skill` tool output SHALL use a logical base —
the supplied `base` when present, otherwise `skill://<name>/` — and SHALL list the supplied logical
`resources` in the `<skill_files>` block, emitting no absolute host path. For skills discovered from
an on-disk `SkillsDir`, the output SHALL remain byte-identical to today (a `file://` base and
on-disk sibling sampling), so the shared `examples/skills/hello-world` conformance output does not
move.

#### Scenario: Data-sourced skill emits a logical base

- **WHEN** a data-sourced skill named `x` with `resources:["scripts/foo.sh"]` is loaded via the `skill` tool
- **THEN** the output base is `skill://x/` (or the supplied `base`), the `<skill_files>` block lists `scripts/foo.sh`, and no absolute filesystem path appears

#### Scenario: Directory-sourced skill output is unchanged

- **WHEN** the shared `examples/skills/hello-world` skill is loaded via the `skill` tool
- **THEN** the output is byte-identical to the pre-change output (a `file://` base and on-disk sampled files)

### Requirement: Configurable sibling-file sample cap

The toolkit SHALL accept a `SkillSampleLimit` controlling the invoke-time sibling-file sample: a
value of 0 means the default cap of 10 (today's behavior), a positive value caps the sample at that
count, and a value of -1 omits the `<skill_files>` block entirely.

#### Scenario: Default sample cap is unchanged

- **WHEN** `SkillSampleLimit` is 0 and a skill directory contains more than 10 sibling files
- **THEN** at most 10 files are sampled, exactly as today

#### Scenario: Sampling can be disabled

- **WHEN** `SkillSampleLimit` is -1
- **THEN** the `skill` tool output contains no `<skill_files>` block

