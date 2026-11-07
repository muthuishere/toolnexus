# Warn (never truncate) on an oversized skill description (issue #84)

## Why

The system-prompt catalog interpolates every skill `description` verbatim. That is right —
truncating a user's text would break the byte-identical contract — but nothing tells the author
when a description is huge. A 4,443-char description costs ~1,536 chars on Claude Code (which
cuts at 1,535 + `…`) and the full 4,443 on toolnexus, every turn. The published Agent Skills
limit is 1,024 characters.

## What changes

- SPEC §3: a description longer than **1,024 Unicode code points** produces a warning
  `{ location, reason: "oversized-description", chars }` in a new `warnings` list, a sibling of
  `skipped`, on both the loader result (`loadSkills`, over the exposed skills) and the
  `listSkills` inventory (unfiltered). `chars` is the code-point count. Exactly 1,024 does not
  warn. Order: normative discovery order.
- The description, the catalog and the `skill` tool output stay byte-identical — the warning is
  the entire observable effect.
- Each port also emits the warning through the same warn channel it already uses for the
  duplicate-name warning (`console.warn`, `logging`, `log`, `Logger`, …) — never stdout.
- Shared fixture `conformance/skill_description/` (2,000 code points with non-ASCII; an
  at-limit 1,024 code points that is 1,034 UTF-16 units and 1,054 bytes, to pin the unit).

## Out of scope

Angle-bracket validation (a Claude-Code-specific lint), any truncation, a configurable threshold.
