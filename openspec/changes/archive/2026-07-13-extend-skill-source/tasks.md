## 1. Contract & fixtures (do first — the shared parity target)

- [x] 1.1 Update `SPEC.md §3`: add subsections for injectable sources (S1), skill allowlist (S2), list/validate inventory (S3), logical output base for non-filesystem sources with the on-disk path preserved byte-for-byte (S4), and configurable sample cap (S5).
- [x] 1.2 S2 parity: exercised via identical in-memory `SkillDef` inputs + identical byte-level assertions in all five ports (data sources are in-memory by definition; a physical `examples/` file is N/A for a filter over data skills).
- [x] 1.3 S4 parity: data-sourced skill + expected logical-base output block asserted byte-for-byte in all five ports (`skill://x/` base, `<file>scripts/foo.sh</file>`, no host path).
- [x] 1.4 Pin the S4 regression baseline: the `examples/skills/hello-world` `skill`-tool output bytes are the byte-identical target, asserted in all five ports.

## 2. JS reference port (`js/`) — implement + settle exact output bytes ✅ 73/73 green

- [x] 2.1 S1: `SkillDef` shape + accept `skills` data list and a provider callback in toolkit options; normalize all three sources into the existing name→info map with first-wins dedupe.
- [x] 2.2 S2: `skillsFilter` with MCP-gap-7 semantics (nil/empty⇒all; ≥1 true⇒allowlist; only-false⇒drop-list; unknown⇒ignore+warn-once), applied post-discovery/pre-tool-build.
- [x] 2.3 S3: `listSkills` returning parsed skills + typed skips (`missing-name` | `malformed-frontmatter` | `duplicate-name` | `unreadable`); no toolkit wired.
- [x] 2.4 S4: origin discriminator on skill info; logical base (`skill://name/` or supplied) + supplied resources for data/provider skills; on-disk branch unchanged.
- [x] 2.5 S5: `skillSampleLimit` (0⇒10, n⇒cap, -1⇒omit `<skill_files>`).
- [x] 2.6 Tests: S1–S5 unit tests + the byte-identical `hello-world` regression (task 1.4).

## 3. Python port (`python/`) — parity with JS ✅ 93 passed

- [x] 3.1 S1 skill data list + provider; 3.2 S2 filter; 3.3 S3 `list_skills`; 3.4 S4 logical base; 3.5 S5 sample cap.
- [x] 3.6 Tests: S1–S5 + `hello-world` byte-identical regression; diff output against JS.

## 4. Go port (`golang/`) — parity with JS ✅ go test -race green, vet clean

- [x] 4.1 S1 `[]SkillDef` + `SkillProvider` in `Options`; 4.2 S2 `SkillsFilter`; 4.3 S3 `ListSkills` + `SkillInventory`/`SkillSkip`; 4.4 S4 logical base branch in the `skill` tool; 4.5 S5 `SkillSampleLimit`.
- [x] 4.6 Tests: S1–S5 `go test -race` + `hello-world` byte-identical regression; diff output against JS.

## 5. Java port (`java/`) — parity with JS ✅ BUILD SUCCESSFUL

- [x] 5.1 S1 skill defs + provider; 5.2 S2 filter; 5.3 S3 list/validate; 5.4 S4 logical base; 5.5 S5 sample cap.
- [x] 5.6 Tests: S1–S5 + `hello-world` byte-identical regression; diff output against JS.

## 6. C# port (`csharp/`) — parity with JS ✅ 112 passed

- [x] 6.1 S1 skill defs + provider; 6.2 S2 filter; 6.3 S3 list/validate; 6.4 S4 logical base; 6.5 S5 sample cap.
- [x] 6.6 Tests: S1–S5 + `hello-world` byte-identical regression; diff output against JS.

## 7. API-reference docs (`site/`) ✅ builds clean, 6 API pages render

- [x] 7.1 Added an `api/` section to the Astro Starlight site: a combined surface page + a per-port page for each of js/python/go/java/csharp covering toolkit options, client options, skill loading/inventory (`LoadSkills`/`ListSkills`), MCP load, and adapters.
- [x] 7.2 Five-language synced code tabs on the combined page; each per-port symbol lists its cross-language equivalents (e.g. `ListSkills`/`listSkills`/`list_skills`).
- [x] 7.3 Documented the new S1–S5 surface for all five ports (skills data/provider, `skillsFilter`, `skillSampleLimit`, `listSkills`) in this change.
- [x] 7.4 `astro build` succeeds; `/api/*` pages + sidebar nav render.

## 8. Verify & parity check ✅

- [x] 8.1 Suites green per port: js `npm test` 73/73 · python `pytest` 93 · go `go test -race`/`vet` · java `gradlew test` BUILD SUCCESSFUL · csharp `dotnet test` 112.
- [x] 8.2 Conformance parity confirmed: on-disk `hello-world` `skill`-tool output asserted byte-identical in all five ports; the data-source S4 block (`skill://x/` + `<file>scripts/foo.sh</file>`, no host path) asserted identically in all five.
- [x] 8.3 `openspec validate extend-skill-source` passes; all S1–S5 per-port checkboxes ticked.
