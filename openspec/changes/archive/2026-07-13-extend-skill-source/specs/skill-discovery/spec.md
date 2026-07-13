## ADDED Requirements

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
