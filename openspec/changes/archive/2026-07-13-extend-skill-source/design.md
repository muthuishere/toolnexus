## Context

The skill source (`SPEC.md §3`; `golang/skill.go`, mirrored in js/python/java/csharp) is
filesystem-only end to end. `LoadSkills(dirs...)` walks directories; the `skill` tool's `execute`
closure re-reads sibling files off disk **at invoke time** and emits absolute host paths as a
`file://` base plus `<file>/abs/...</file>` entries. `docs/adr/0002-skills-consumer-needs.md`
records the resulting host-side gaps (no data source, no per-agent gating, no validate path, host
paths leaked when served over A2A). This change closes them additively while preserving the
byte-exact conformance output for the shared `examples/skills/hello-world` fixture — the whole point
of the five-port parity contract.

The prime directive constraint dominates every decision here: **the on-disk `SkillsDir` path must
produce byte-identical output after this change.** New behavior rides only on the new sources.

## Goals / Non-Goals

**Goals:**
- Skills loadable as data / via a provider, not only from directories (S1).
- Per-agent name allowlist with the *same* vocabulary as MCP `tools` and builtins (S2).
- A list/validate inventory that reports typed skip reasons without wiring a toolkit (S3).
- Logical output base for non-filesystem sources; on-disk output unchanged (S4).
- Configurable / disable-able sibling-file sampling (S5).
- Per-port API reference on the docs site, kept in five-language parity.
- Byte-identical behavior for existing `SkillsDir`-only callers in all five ports.

**Non-Goals:**
- Remote/async resource *fetching* mechanics (an S3-object loader): the provider returns already
  materialized `content`; how the host obtains it is out of scope.
- Changing discovery of on-disk skills (symlink handling, YAML parsing) — untouched.
- Auto-generated API docs from source (doc-comment extraction); the reference is hand-authored MDX
  with synced tabs, matching the existing site style.

## Decisions

**D1 — Skills as a normalized `SkillDef` internally; three sources funnel into one map.**
Each port already builds an internal `name → SkillInfo` map. Directories, the in-memory list, and
the provider callback all normalize to that same map before the `skill` tool is built, preserving
the existing first-wins dedupe. Alternative (separate code paths per source) was rejected: it would
duplicate the dedupe and the prompt/catalog logic and invite drift.

**D2 — `SkillInfo` gains an internal `origin` discriminator (filesystem vs logical).** The `skill`
tool's output branch keys on this, not on a runtime `os.Stat`. Filesystem-origin skills keep the
`file://` base + on-disk `sampleSiblingFiles`; logical-origin skills use `base` (or `skill://<name>/`)
+ the supplied `resources` slice with **no disk access**. This is what guarantees the on-disk branch
is byte-identical — it is literally the unchanged code path — while the logical branch never touches
the filesystem. Alternative (always logical, derive `file://` from a path) was rejected: it risks
perturbing the exact bytes of the existing output.

**D3 — `SkillsFilter` reuses the resolved MCP gap-7 semantics verbatim.** nil/empty ⇒ all; ≥1 true ⇒
allowlist; only-false ⇒ drop-list; unknown ⇒ ignore + warn-once. Keyed on frontmatter `name`. This
was a deliberate consistency choice (ADR 0001 A3 as overridden by the owner: empty = all). A host
configures MCP tools, builtins, and skills with one mental model. Applied post-discovery,
pre-tool-build so catalog and lookup can never disagree.

**D4 — `ListSkills` shares discovery with `LoadSkills` but returns diagnostics instead of a tool.**
Discovery is refactored so the walk/parse step can emit either `SkillInfo` (success) or a typed
`SkillSkip` (failure) for every candidate; `LoadSkills` keeps successes, `ListSkills` returns both.
The four skip reasons map to the existing silent `continue`/`log` sites (missing name, malformed
frontmatter, duplicate name, unreadable file). No new discovery logic — just surfacing what is
already computed.

**D5 — `SkillSampleLimit`: 0=default(10), n>0=cap, -1=disable.** A single int keeps the option
surface minimal and ports cleanly (no nullable). -1 (not 0) means "disable" so the natural
zero-value preserves today's behavior.

**D6 — API reference is hand-authored MDX with the site's existing five-tab component.** One page
per port under `site/src/content/docs/api/`, cross-linking equivalents. The spec pins *parity*
(every shipped symbol documented for every port that has it), not a generator, so the requirement is
verifiable by review and survives the current static-site setup.

**Port order:** JS is the reference (repo convention) — implement + settle the exact output bytes
there first, then port to python/golang/java/csharp and diff each against the JS wire/output.

## Risks / Trade-offs

- **[S4 output bytes drift on the on-disk path]** → the conformance test asserts the `hello-world`
  output is byte-identical before/after in every port; the logical branch is exercised only by new
  data-source fixtures. The two branches never share formatting code paths beyond the unchanged
  template.
- **[Filter semantics diverge from MCP by accident]** → S2 tests mirror the MCP gap-7 test names 1:1
  so a reviewer sees the parity; the empty=all decision is pinned in the spec scenario.
- **[Five-port drift during the port-over]** → each port carries its own S1–S5 parity checklist in
  `tasks.md`; a partial pass leaves the rest as unchecked tasks (never silently skipped).
- **[Docs parity rots over time]** → the `api-reference-docs` spec makes "document the new symbol for
  every port in the same change" a requirement, so future changes inherit the obligation.

## Migration Plan

Additive; no rollback needed. No existing signature changes — new options default to today's
behavior. Each port ships in the next normal release; no breaking bump. `SPEC.md §3` and the two
capability specs land in this change; `examples/` gains a filtered-skills fixture (S2) and a
data-source fixture (S4) that every port must honor identically.

## Open Questions

- Exact spelling of the data-source option per port (`Skills []SkillDef` vs a builder) is left to
  each port's idiom, provided the behavior and the `SkillDef` shape match — settled during the JS
  reference pass and recorded in `SPEC.md §3`.
- Whether the API-reference pages live under one combined page with tabs or one page per port is a
  site-navigation detail resolved during the docs task; the spec requires only that every port's
  surface is covered and cross-linked.
