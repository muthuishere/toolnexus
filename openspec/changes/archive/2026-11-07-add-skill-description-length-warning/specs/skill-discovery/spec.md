## ADDED Requirements

### Requirement: Oversized skill descriptions are reported, never modified

The skill loader SHALL report every skill whose `description` is longer than 1,024 Unicode code
points as a warning `{ location, reason: "oversized-description", chars }` in a `warnings` list
on the loader result (over the exposed skills) and on the `listSkills` inventory (unfiltered),
where `chars` is the description's length in code points. The description SHALL be emitted in
the system-prompt catalog in full and unmodified, and the warning SHALL NOT alter any other
output. A port MAY additionally emit the warning through its existing warn/logging channel, and
SHALL NOT print it to stdout.

#### Scenario: A 2,000-code-point description warns and is kept whole

- **WHEN** skills are loaded from `conformance/skill_description/skills`
- **THEN** `warnings` holds exactly one entry for `long-desc` with reason
  `oversized-description` and `chars` 2000, and the catalog contains the full description

#### Scenario: Exactly 1,024 code points does not warn

- **WHEN** the `at-limit` skill (1,024 code points, 1,034 UTF-16 units) is loaded
- **THEN** no warning is reported for it

#### Scenario: Short descriptions produce no warnings

- **WHEN** every loaded description is at most 1,024 code points
- **THEN** `warnings` is empty
