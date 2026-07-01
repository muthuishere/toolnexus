## Why

The SKILL.md frontmatter parser was hand-rolled (a flat `key: value` line split), so YAML **block
scalars** — folded `description: >` and literal `description: |` — captured only the `>`/`|` marker
and dropped the actual (multi-line) content. Real skills use folded descriptions (e.g. `huddle`,
`reqsume-kernel`), so their descriptions rendered as literally `>` in the skills prompt — and now
also in the A2A Agent Card. The ports had even drifted: JS was hand-rolled (broke on `>`), Go used a
YAML struct but didn't trim (leaked a trailing newline). Reported by a package consumer.

## What Changes

- Replace the hand-rolled frontmatter parser in every port with each ecosystem's **standard YAML
  parser** over the `---`-fenced header block: js `yaml`, python `PyYAML`, go `gopkg.in/yaml.v3`,
  java SnakeYAML, csharp YamlDotNet. Folded/literal/chomping/quoting/multi-line all resolve.
- Take only scalar values (string/number/bool), coerce to string, and **`.trim()`** them — block
  scalars' trailing newline chomps slightly differently per lib, so trimming keeps the five ports
  byte-identical.
- Malformed YAML **fails gracefully** (empty frontmatter → skill skipped for missing `name`), never
  crashing discovery.
- Add the YAML parser as a real runtime dependency in each port's manifest.

## Capabilities

### Modified Capabilities
- `skill-discovery`: frontmatter parsing now uses a standard YAML parser (block scalars, quoting,
  multi-line), scalar-coerced + trimmed, malformed-safe.

## Impact

- **SPEC.md §3** — frontmatter-parsing clause (already updated): standard YAML lib, scalar+trim,
  malformed-safe.
- **js/python/golang/java/csharp** — `skill` module frontmatter parse rewritten + a YAML dependency
  added + per-port tests (single-line / folded `>` / literal `|` / empty / malformed).
- **Dependencies** — one small, standard YAML parser per port. No API change.
- Patch release **0.2.1**.
