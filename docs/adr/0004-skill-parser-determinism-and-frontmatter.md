# ADR 0004 — Skill parser: cross-port determinism and lossless frontmatter

- **Status:** K1 accepted; K2 deferred (2026-07-14) — K1 was re-verified against source: Go's
  `os.ReadDir` returns name-sorted entries while JS `readdirSync` (skill.ts:169) does not, so
  the Go and JS ports **already emit different `<skill_files>` blocks today** for any skill
  with more than `limit` siblings — a live parity violation, not a latent one. Promote K1 to
  an OpenSpec change. K2 waits for a consumer that needs structured frontmatter. K3 stays a
  recorded non-goal.
- **Date:** 2026-07-14
- **Driver:** the skill-source companion to [ADR 0003](0003-mcp-host-lifecycle-and-liveness.md).
  Where ADR 0003 compares the MCP source against VS Code's MCP host, this one compares the
  toolnexus skill source (`js/src/skill.ts`, mirrored in `golang/skill.go` + python/java/csharp;
  `SPEC.md §3`) against VS Code's prompt-file/instructions pipeline
  (`promptFileParser.ts`, `automaticInstructionsCollector.ts`). toolnexus's skill design is
  genuinely good — real-YAML frontmatter, progressive disclosure via one `skill` tool, a typed
  skip inventory, symlink-cycle guards, data-sourced skills. Two things, though, quietly undercut
  its own headline guarantee ("byte-identical across five ports") and throw away metadata it will
  want later. This ADR records exactly those two, plus one deliberately-deferred design question.
- **Honesty note:** like ADR 0003 this is **reference-architecture-verified, not
  consumer-verified**. K1 is a real cross-port correctness bug (line-cited below); K2 is a
  forward-compat gap, not a present-day failure. K3 is explicitly *not* a requirement — it is a
  recorded non-goal so the minimalism stays a choice, not an oversight.
- **Process note:** each confirmed gap flows through an OpenSpec change with `SPEC.md §3` deltas
  (K1 and K2 both move the observable contract) and a shared `examples/` fixture where the emitted
  block must match across ports. All proposals are **additive and backward-compatible**.

## Context

`loadSkills` discovers `SKILL.md` files, parses frontmatter, dedupes first-wins, and builds one
`skill` tool whose `execute` — at invoke time — emits the skill body plus a **sampled** list of
sibling files inside a `<skill_files>` block (`skill.ts` `loadSkills`, sample via
`sampleSiblingFiles` `:161`). Frontmatter is parsed with a real YAML parser
(`parseFrontmatter` `:90`) but then **flattened**: only scalar values survive
(`data[key] = String(value).trim()` `:107`, Go `strings.TrimSpace(fmt.Sprintf(...))`
`skill.go:127`). The sibling sample walks `readdirSync` order with no sort
(`skill.ts:169`, Go `os.ReadDir` `skill.go:190`). Priority order: **K1 deterministic sampling →
K2 lossless frontmatter.**

---

## Gap K1 — Sibling-file sampling is non-deterministic across ports and filesystems (priority 1)

### Motivation

`sampleSiblingFiles` collects the first `limit` (default 10) files by DFS over raw directory-read
order — `readdirSync(cur, ...)` (`skill.ts:169`), `os.ReadDir(cur)` (Go `skill.go:190`) — and
**never sorts**. `readdir` order is filesystem- and OS-dependent (ext4 hash order ≠ APFS ≠ what Go
happens to return sorted-by-name). So the `<skill_files>` block a skill emits differs by machine
and by language port for any skill with more than `limit` files — directly contradicting the
"byte-identical across five ports" guarantee the source claims for that block. It is a silent
divergence: nothing errors, the sampled set just isn't the same set.

Note VS Code sidesteps this class of bug because it resolves referenced files by explicit link,
not by sampling directory order (`AutomaticInstructionsCollector._addReferencedInstructions`,
`extensions/copilot/src/platform/promptFiles/node/automaticInstructionsCollector.ts:584`).

### Proposed change (additive; fixes a latent bug)

Sort entries by name (byte order, case-sensitive) before DFS descent and before taking the first
`limit`, in every port. Directories and files sorted by the same key so traversal order is
identical everywhere. No API surface changes; the emitted block simply becomes stable.

```ts
// sampleSiblingFiles: sort each directory listing before use
const entries = readdirSync(cur, { withFileTypes: true })
  .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
```

Optional follow-on (separate change if wanted): expose `sampleLimit` ordering as documented
"lexicographic, breadth-then-name" so authors can predict which 10 files show.

### Acceptance tests
- A skill dir with 25 sibling files emits the **same** 10 `<file>` entries in the same order on
  APFS, ext4, and across js/go/python/java/csharp (golden fixture).
- Existing single-digit-file skills are unaffected (they already emit all files; order now stable).
- Symlink-cycle guard behavior unchanged.

### Cross-language parity
This is the parity fix — `SPEC.md §3` must state "listings are name-sorted before sampling" as a
normative rule, and `examples/skill-sampling/` carries the 25-file golden.

---

## Gap K2 — Non-scalar frontmatter is silently dropped (priority 2)

### Motivation

`parseFrontmatter` coerces every value with `String(value).trim()` and keeps only
string/number/boolean (`skill.ts:107`, Go `skill.go:127`), so a header like:

```yaml
---
name: pdf-fill
allowed-tools: [read_file, write_file]
applyTo: "**/*.pdf"
model: [opus, sonnet]
---
```

loses `allowed-tools` and `model` entirely — arrays vanish at parse time. Today nothing consumes
them, so nothing breaks; but the moment toolnexus wants **per-skill tool-gating** (the natural
sibling of the MCP per-server `tools` allowlist and the skills `filter` that already exist) or
**applyTo auto-attach** (VS Code's `PromptHeader` keeps exactly this structured metadata —
`src/vs/workbench/contrib/chat/common/promptSyntax/promptFileParser.ts:96-138`), the data has
already been thrown away and every author's file has to be re-parsed. Preserve it now; consume it
later.

### Proposed API (additive)

```ts
interface SkillInfo {
  name: string
  description?: string
  content: string
  location: string
  // NEW — the full parsed frontmatter, structure preserved (arrays, maps, scalars).
  // `description`/`name` continue to be surfaced as today for back-compat.
  meta?: Record<string, unknown>
}
```

`meta` is populated straight from the YAML parse (before the scalar coercion), so `allowed-tools`
stays an array. No behavior change to the prompt catalog or the `skill` tool output; this is
purely retaining what was parsed. A future change can read `meta["allowed-tools"]` to scope the
toolkit per skill — but that is a *separate* ADR, not this one.

### Acceptance tests
- A skill with array/map frontmatter exposes them intact on `info.meta`; `name`/`description`
  unchanged.
- `prompt()` catalog output and `skill`-tool output are byte-identical to today (meta is carried,
  not emitted).
- Malformed frontmatter still yields the `malformed-frontmatter` skip (K2 doesn't touch the skip
  path).

### Cross-language parity
Moves `SPEC.md §3`: `SkillInfo.meta` and the rule "preserve parsed structure; surface name +
description as before." Each port maps to its native any/object type (`map[string]any`,
`Map<String,Object>`, `dict`). Fixture `examples/skill-frontmatter/`.

---

## K3 — Non-goal (recorded so it stays a choice): no automatic reference following

VS Code's instructions pipeline follows `[..](./ref.md)` links and pulls referenced content in
(`_addReferencedInstructions`, `automaticInstructionsCollector.ts:584`), and auto-attaches
instructions to files matching an `applyTo` glob. toolnexus deliberately does **not** — a skill is
pulled on demand through the `skill` tool (progressive disclosure), and that is the correct
minimalism for a library: the model decides when to load, the host doesn't front-load a content
graph. This is recorded as an explicit non-goal so a future reader doesn't "fix" it by accident.
Revisit only if a consumer files a concrete need (e.g. a skill whose body is useless without a
referenced spec). K2's `meta["applyTo"]` would be the hook if we ever do.

---

## Consumer questions (answer before promoting)

- **QK1 (K1):** case-sensitive byte sort vs locale-aware — byte sort is the only one that's truly
  identical across all five language runtimes, so this ADR assumes byte order. Confirm no author
  relies on locale collation for which files sample.
- **QK2 (K2):** should `meta` retain **all** keys or only a documented allowlist? Retaining all is
  simpler and future-proof; an allowlist avoids surprising an author whose stray YAML key becomes
  load-bearing later. Leaning "retain all, document the reserved ones."
