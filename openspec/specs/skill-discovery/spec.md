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

