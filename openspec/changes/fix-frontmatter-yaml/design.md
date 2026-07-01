## Context

The frontmatter parser was hand-rolled (flat `key: value` split), which cannot represent YAML block
scalars. `description: >` yielded `>`; the folded lines were dropped. The `---` header extraction
regex is fine and kept; only the header *parsing* changes.

## Goals / Non-Goals

**Goals:** correct YAML frontmatter (block scalars, quoting, multi-line) via a standard parser;
byte-identical across five ports; malformed-safe. **Non-Goals:** changing the `---` fence extraction,
the body/content split, first-name-wins dedupe, or the `skill` tool output.

## Decisions

**Standard YAML parser, not more hand-rolling.** Each port uses its ecosystem lib (js `yaml`, python
`PyYAML`, go `gopkg.in/yaml.v3`, java SnakeYAML, csharp YamlDotNet). Rationale: block scalars +
chomping + quoting are exactly what a real parser gets right and a regex does not — the reported bug.
Alternative (a hand-rolled block-scalar regex) rejected: it would re-implement folding/chomping the
libs already do correctly.

**Scalar-coerce + trim for parity.** Only string/number/bool values are taken (skip nested
maps/sequences), coerced to string and trimmed. Trimming is load-bearing: YAML libs chomp block
scalars' trailing newline slightly differently; trimming normalizes so the five ports match byte for
byte (and a stray newline doesn't break the `- **name**: description` prompt line or the A2A card).

**Malformed-safe.** The parse is wrapped so any YAML error falls back to empty frontmatter; a skill
without a `name` is skipped — discovery never crashes on one bad file.

## Risks / Trade-offs

- [New dependency per port] → each is a small, standard, widely-used YAML lib; acceptable and more
  correct than hand-rolling.
- [Cross-lib chomping differences] → neutralized by the mandatory trim (covered by per-port tests).
