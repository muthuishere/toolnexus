## ADDED Requirements

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
