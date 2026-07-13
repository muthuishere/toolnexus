## Why

The skill source assumes skills live in on-disk `skills/` directories the process can walk
(`LoadSkills(dirs ...string)` → `os.Stat`/`os.ReadDir`/`os.ReadFile`, and the `skill` tool re-reads
sibling files off disk at invoke time). A host application that stores skills as data, gates them
per agent, exposes the toolkit over A2A, or authors skills through a UI cannot use it without
materializing a temp directory per request, post-filtering the toolkit, or leaking absolute host
paths to remote callers. See `docs/adr/0002-skills-consumer-needs.md`. Separately, the GitHub Pages
docs site has no API-level reference for the public surface of each port, so five-language API
parity is incidental rather than contractual.

## What Changes

- **S1 — Injectable skill source.** Skills may be supplied as **data** (`SkillDef{name, description,
  content, resources?, base?}`) and/or via a **lazy provider** function, in addition to today's
  directories. The three sources compose and dedupe by name (first-wins, unchanged).
- **S2 — Per-agent skill allowlist.** A new `SkillsFilter` (`map[string]bool`, keyed on skill
  frontmatter name) with the **same semantics as the MCP per-server tools filter and builtins**
  (nil/empty ⇒ all; ≥1 `true` ⇒ allowlist; only-`false` ⇒ drop-list; unknown names ignored + warned
  once).
- **S3 — List/validate inventory.** A new `ListSkills` returns parsed skills **plus typed skip
  reasons** (`missing-name` | `malformed-frontmatter` | `duplicate-name` | `unreadable`) without
  wiring a toolkit — so a host can validate admin-authored skills and report *why* one was dropped.
- **S4 — Logical base for non-filesystem / served skills.** For data/provider sources the `skill`
  tool output uses a **logical base** (`skill://<name>/` or a supplied `base`) and lists the supplied
  `resources`; the local `SkillsDir` path stays **byte-identical** (`file://` + on-disk sampling), so
  the `examples/skills/hello-world` conformance output does not move. **BREAKING** only for callers
  who fed skills through a non-directory path — there is none today (additive).
- **S5 — Configurable sample cap.** `SkillSampleLimit` (0 ⇒ default 10, today's behavior; `-1` ⇒
  omit the `<skill_files>` block entirely).
- **Docs.** The Astro Starlight site (`site/`) gains an **API Reference** section carrying the public
  surface of every port (Toolkit/Client options, `LoadSkills`/`ListSkills`, MCP load, adapters) with
  five-language synced code tabs, and this is pinned as a spec requirement so docs parity is
  enforced, not incidental.

All code changes are **additive and backward-compatible**: a caller passing today's `SkillsDir` and
nothing else gets byte-identical behavior in every port.

## Capabilities

### New Capabilities
- `api-reference-docs`: the published docs site MUST carry API-level reference documentation for the
  public surface of all five ports, kept in five-language parity.

### Modified Capabilities
- `skill-discovery`: gains injectable data/provider sources (S1), a per-agent name allowlist (S2), a
  list/validate inventory with typed skip reasons (S3), a logical output base for non-filesystem
  sources with the local-directory path preserved byte-for-byte (S4), and a configurable sibling-file
  sample cap (S5).

## Impact

- **Code (all five ports):** `js/src/skill.ts` + toolkit options, `python/src/toolnexus/skill.py`,
  `golang/skill.go` + `golang/toolkit.go`, `java/.../Skill*.java` + toolkit options,
  `csharp/src/Toolnexus/Skill*.cs` + toolkit options.
- **Contract:** `SPEC.md §3` gains the injectable-source, allowlist, list/validate, logical-base, and
  sample-cap subsections; shared `examples/` gains a filtered-skills fixture (S2) and a data-source
  fixture (S4).
- **Docs:** new `site/src/content/docs/api/**` pages + nav; the docs-parity spec requirement.
- **Tests:** new unit/parity tests per port for S1–S5; a conformance assertion that the
  `hello-world` on-disk output is unchanged.
- **No dependency changes.** No existing signature changes; no port ships a breaking release.
