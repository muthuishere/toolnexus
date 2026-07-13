# ADR 0002 — skills consumer needs: the same platform gaps, on the skill source

- **Status:** Proposed (requirements only — no implementation in this ADR)
- **Date:** 2026-07-13
- **Driver:** the companion to [ADR 0001](0001-rag-go-consumer-needs.md). ADR 0001 recorded seven
  gaps rag_go hit driving the **MCP** tool source from a multi-tenant platform. The **skill**
  source (`golang/skill.go`, mirrored in js/python/java/csharp; `SPEC.md §3`) has the *same*
  architectural mismatch — it assumes skills live in on-disk `skills/` directories the process
  can `os.ReadDir`, and it surfaces neither a per-agent selection nor a validate path. A platform
  that stores agent skills in Postgres / object storage, gates them per agent, and lets admins
  author them through a UI will hit each of these.
- **Honesty note (how this differs from ADR 0001):** ADR 0001's gaps were *verified against
  rag_go's production workarounds* (a reverse proxy, a watchdog, a shadow store — real code that
  exists). This ADR is **code-surface-verified but consumer-anticipated**: the toolnexus-side
  facts (line-cited below) are real, but rag_go has not yet documented equivalent skill-side
  workarounds. Its priority order and even whether some gaps apply hinge on the **consumer
  questions** at the end — answer those before promoting any gap to an OpenSpec change. Two
  questions (Q1 source-of-truth, Q5 resource model) can collapse the biggest gap to almost
  nothing, so ask first, build second.
- **Process note:** as with ADR 0001, each confirmed gap flows through an OpenSpec change
  (`/opsx:propose`) with `SPEC.md §3` deltas where the cross-language contract moves (S1–S4 all
  move it), a per-language parity checklist, and a shared `examples/` fixture where the output or
  config format moves (S2, S4). All proposals are **additive and backward-compatible**: a
  consumer passing today's `SkillsDir []string` and nothing else gets byte-identical behavior.

## Context

Today the entire skill source is reachable only as:

```go
// golang/toolkit.go
SkillsDir []string          // "one or more skill roots. Empty skips skill loading."
// → LoadSkills(dirs ...string) *SkillSource   (golang/skill.go:210)
```

`LoadSkills` walks each root (`os.Stat` :214, `os.ReadDir` :110/:148, `os.ReadFile` :220),
parses frontmatter, and builds one `skill` tool whose `Execute` closure — at **invoke** time —
re-reads the skill's sibling files off disk (`sampleSiblingFiles` → `os.ReadDir` :148) and emits
absolute host paths (`file:///abs/dir` base :279/:291, `<file>/abs/dir/...</file>` :283). There is
no way to (a) supply skills as data instead of a directory, (b) restrict which skills an agent
sees, (c) ask "which skills parsed, and why did the rest fail," or (d) get output that means
anything to an agent on another host. Priority order (highest impact first, **pending Q1/Q5**):
**S1 injectable skill source → S3 list/validate inventory → S2 per-agent allowlist → S4
served/remote base → S5 sample config.**

Build-order note independent of priority: **S2 and S3 are shippable on the pure-filesystem path
today** (small, no design doc). **S1 and S4 are the architectural epic** (need a `design.md`, go
together). If Q1/Q5 say rag_go is fine materializing a per-request `skills/` tmpdir, S1/S4 may
never be needed and S2+S3 alone close the gap.

---

## S1 — Skills can only come from on-disk directories (priority 1, gated by Q1/Q5)

### Motivation

`LoadSkills` is filesystem-only end to end: discovery reads directories, and the `skill` tool's
`Execute` closure reads sibling files off disk **at call time**, not just at load. A platform that
stores a tenant's skills as rows/objects (not a checkout on the box) has no directory to point
`SkillsDir` at. The only workaround is to **materialize a temp `skills/` tree per request** —
write every enabled skill (and its resources) to disk before `CreateToolkit`, clean up after —
which is I/O per request, races on shared tmp, and forces the resource bytes onto local disk even
for an instruction-only skill.

### Proposed API (additive) — a data shape + an optional lazy provider

```go
// SkillDef supplies one skill directly, bypassing the filesystem. Content is the
// SKILL.md body (already sans-frontmatter, OR full text — see Discovery note).
// Resources is an OPTIONAL logical resource list for the invoke-time <skill_files>
// block; nil ⇒ instruction-only skill (no <skill_files>, per Q5). Base is a
// logical base identifier (e.g. "skill://leave-policy/"); empty ⇒ a default
// derived from Name.
type SkillDef struct {
    Name        string
    Description string
    Content     string
    Resources   []string // optional; logical names, not host paths
    Base        string   // optional; logical base, not a file:// path
}

// ToolkitOptions additions (golang/toolkit.go) — any one source, or a mix:
Skills       []SkillDef                                   // supply skills as data
SkillSource  func(ctx context.Context) ([]SkillDef, error) // lazy/remote provider, ctx-aware
// (SkillsDir []string stays; the three compose — dir-discovered + data + provider,
//  deduped by name with the existing first-wins rule, SPEC §3.)
```

Design tension to resolve in `design.md` (this is why S1 needs one): the invoke-time output
template (`SPEC.md §3`, skill.go :285-299) hardcodes a `file://` base + on-disk sibling sampling.
For a `SkillDef` with no filesystem behind it, the base becomes `Base` (logical) and
`<skill_files>` lists `Resources` verbatim (no `os.ReadDir`). **The local-`SkillsDir` path stays
byte-identical** (real `file://`, real sampling) — only the data/provider path uses the logical
form. Pin that split in the delta so the byte-exact conformance for the shared `examples/` fixture
never moves.

### Acceptance tests

1. `Skills: []SkillDef{{Name:"x", Description:"d", Content:"body"}}` and no `SkillsDir` → the
   `skill` tool lists `x` in the prompt and `execute({name:"x"})` returns the progressive block
   with `body`, a logical base, and **no** `<skill_files>` (instruction-only).
2. `SkillDef` with `Resources:["scripts/foo.sh"]` → `<skill_files>` lists exactly that, no disk read.
3. `SkillSource` provider returning two skills → same result as passing them via `Skills`;
   provider error ⇒ isolated (skills from other sources still load), like MCP per-server isolation.
4. Mix: `SkillsDir` (fixture hello-world) + `Skills` (data) → both present, name-dedup first-wins.
5. Back-compat: `SkillsDir` only, no new fields → byte-identical to today (the `examples/`
   conformance fixture output does not move).

### Parity

All five ports; `SPEC.md §3` gains a "skills may be supplied as data (`SkillDef`) or a provider,
not only a directory; the invoke-time base/file-list is logical for non-filesystem sources"
subsection. **Gated by Q5:** if rag_go skills are instruction-only, `Resources`/`Base` and the
whole sibling-sampling half of this gap can ship as a no-op stub and S4 nearly evaporates.

---

## S3 — No list/validate inventory; skill parse failures are invisible (priority 2)

### Motivation

Every skill-discovery failure is a silent `log.Printf` + `continue` with nothing returned to the
caller: dir not found (:216), missing `name` (:225 — the single most common authoring mistake),
malformed YAML (:63-64 → empty frontmatter → skipped), duplicate name (:229 — first wins). A
platform whose admins author skills through a UI (libreadmin's skill screens, exactly the MCP
gap-6 story) needs to tell the author *"this SKILL.md was skipped because it has no `name:`"* —
today it can only build a toolkit and diff what's missing, with no reason. `SkillSource.Skills`
exposes the successes but never the failures.

### Proposed API (additive)

```go
// SkillSkip records why a candidate SKILL.md did not become a skill.
type SkillSkip struct {
    Location string // path (or logical id) of the SKILL.md
    Reason   string // "missing-name" | "malformed-frontmatter" | "duplicate-name" | "unreadable"
}

// SkillInventory is the result of a list/validate pass — no toolkit, no `skill`
// tool wired, nothing left open.
type SkillInventory struct {
    Skills  []SkillInfo // parsed OK (name, description, location, content)
    Skipped []SkillSkip // with structured reason
}

// ListSkills discovers + validates skills from the same sources LoadSkills accepts
// (dirs, and — with S1 — data/provider), reporting successes AND typed skip reasons.
// ctx-aware for the S1 provider case.
func ListSkills(ctx context.Context, dirs ...string) (*SkillInventory, error)
```

### Acceptance tests

1. A root with one good SKILL.md, one missing `name`, one malformed YAML, and a duplicate of the
   good name → `Skills` has the one good skill; `Skipped` has three entries with reasons
   `missing-name`, `malformed-frontmatter`, `duplicate-name`.
2. Nonexistent root → not a crash; represented as an empty pass (or a skip, pinned in the delta).
3. Instruction-only good skill → present in `Skills` with its description.
4. Composes with S1: data/provider skills validate the same way.

### Parity

All five ports (`listSkills` / `list_skills`); `SPEC.md §3` gains a "list/validate inventory
(parsed skills + typed skip reasons)" subsection. This is independently valuable **today** on the
pure-filesystem path — it does not depend on S1.

---

## S2 — No per-agent skill selection (allowlist) (priority 3)

### Motivation

Direct parallel to ADR 0001 gap 7. `LoadSkills` exposes **every** discovered skill; there is no
per-agent restriction. A platform that stores which skills each agent may use (the skill analog of
`mcp_servers.agenttools[].enabledTools`) must today point each agent at a *different physical
directory*, or post-filter the toolkit — the same wrapper hack gap 7 documented for tools.
toolnexus already has this exact machinery elsewhere (`BuiltinsConfig.Tools map[string]bool`,
`ServerConfig.Tools` from gap 7); this extends it to skills for consistency.

### Proposed API (additive)

```go
// ToolkitOptions addition — same key + shape + semantics as MCP gap 7 / builtins §4A:
//
// SkillsFilter selects which discovered skills are exposed. Keys are skill NAMES
// (the frontmatter `name`, which is what the platform stores — parallels gap 7's
// "original names"). Nil or empty ⇒ all skills. ≥1 true entry ⇒ ALLOWLIST (only
// those names). Only-false entries ⇒ DROP-LIST over all-on. Unknown names IGNORED
// + warned once (a skill may be renamed across versions; config must not break).
SkillsFilter map[string]bool
```

Semantics pinned to match the resolved MCP gap-7 decision (see ADR 0001 consumer answer A3, as
overridden by the owner): **nil *and* empty ⇒ all skills** — clearing the field must not silently
blind an agent; "no skills" is expressed by simply enabling none via `SkillsDir`/sources, not by
an empty allowlist. Filter is applied post-discovery, pre-`skill`-tool-build, so the prompt catalog
and the tool's internal map agree.

### Acceptance tests

1. Three discovered skills `a,b,c`; `SkillsFilter{"a":true,"b":true}` → prompt + `skill` tool
   expose exactly `a,b`; `execute({name:"c"})` → "not found".
2. Drop-list `{"c":false}` → `a,b`.
3. Unknown `{"a":true,"nope":true}` → `a` only, no error, warn once.
4. Nil and `{}` → all three (back-compat, matches gap-7 resolution).

### Parity

All five ports; `SPEC.md §3` gains a "per-agent skill allowlist (`SkillsFilter`, same semantics as
the MCP per-server `tools` filter)" bullet, plus a shared `examples/` fixture exercising it.
Independently shippable on the filesystem path.

---

## S4 — Invoke output leaks absolute host paths; meaningless when served or non-FS (priority 4)

### Motivation

The `skill` tool output hardcodes an on-disk base and absolute file paths (skill.go :279/:283/:291;
`SPEC.md §3` template). For a toolkit exposed via `toolkit.serve()` as an **A2A agent**, the
consuming agent runs on another host: `file:///home/stockwork/tenants/42/skills/x` both leaks the
server's filesystem layout and is unusable to the caller. For an S1 data/provider source the path
doesn't exist at all. The base/file-list must come from the source (logical `skill://name/` for
served/data sources; real `file://` preserved for local `SkillsDir`).

### Proposed behavior (composes with S1; one output-template branch)

- Local `SkillsDir` source → **unchanged** (`file://` + on-disk sampling; byte-identical, protects
  the conformance fixture).
- Data / provider / served source → base is the `SkillDef.Base` (or `skill://<name>/`), and
  `<skill_files>` lists logical `Resources`. No host path ever emitted.

### Acceptance tests

1. Served/data skill → output base is `skill://x/` (or supplied `Base`), `<file>` entries are the
   logical resource names, no absolute path present.
2. Local `SkillsDir` skill → output is byte-identical to today (regression on the `examples/`
   fixture).

### Parity

All five ports; this is a `SPEC.md §3` **output-template** change (the highest-scrutiny kind —
byte-exact) so the delta must show both branches explicitly and the shared fixture must pin the
unchanged local-FS bytes.

---

## S5 — Sibling-file sample size is not configurable (priority 5, optional)

### Motivation

`sampleSiblingFiles(dir, 10)` hardcodes the sample cap at 10 (skill.go :280) with no way to raise
it or to **disable** file sampling entirely — which a served toolkit may want (emit zero local
file references) or a resource-heavy skill may want higher.

### Proposed API (additive)

```go
// ToolkitOptions addition:
// SkillSampleLimit caps the invoke-time sibling-file sample. 0 ⇒ default 10
// (today's behavior). -1 ⇒ disable sampling (no <skill_files> block).
SkillSampleLimit int
```

### Acceptance tests

1. `0` → up to 10 files (unchanged). 2. `3` → at most 3. 3. `-1` → no `<skill_files>` block.

### Parity

All five ports; `SPEC.md §3` one-line note. Lowest priority; fold in with S4 if convenient.

---

## Consumer questions — for rag_go (answer before promoting any gap)

These gate priority and even applicability. **Q1 and Q5 are the load-bearing pair** — they decide
whether S1/S4 are a real epic or nearly a no-op.

- **Q1 (source of truth):** Where do a tenant's skills actually live — Postgres/object storage, or
  is materializing a per-request `skills/` tmpdir on the box acceptable? If a tmpdir is fine, S1/S4
  drop out and **S2 + S3 alone** close the gap.
- **Q2 (allowlist key):** Does the platform store per-agent skill enablement keyed on the skill
  **`name`** (frontmatter), like MCP `enabledTools`? (Confirms S2 keys on name.)
- **Q3 (validate UI):** Is there a libreadmin skill-authoring screen that needs the S3 diagnostics
  ("why didn't my SKILL.md load"), mirroring the MCP validate screen (gap 6)?
- **Q4 (served skills):** Is a toolkit ever exposed over A2A (`toolkit.serve()`) *with skills*? If
  yes, S4's host-path leak is a real defect, not a hypothetical.
- **Q5 (resource model):** Are rag_go skills **instruction-only** (just the SKILL.md body as prompt
  text), or do they ship sibling resource files (`scripts/`, `references/`) that must load at
  invoke? Instruction-only collapses S1 to "pass `{name, description, content}` structs" and makes
  S4/S5 nearly moot.

## Consequences

- If Q1/Q5 say "instruction-only, tmpdir-materialization is a smell we want gone," S1 is a small,
  high-value change (skills as data structs) and rag_go deletes its per-request materialization
  path — the skill analog of deleting the reverse proxy.
- S2 and S3 are worth shipping **regardless** of the S1 answer: S3 improves skill-authoring DX for
  every consumer (structured "why skipped" instead of silent logs); S2 gives per-agent skill
  gating with the same config vocabulary as MCP/builtins, so a platform configures both tool
  sources identically.
- Every change is additive; `SkillsDir`-only callers stay byte-identical, and the shared
  `examples/skills/hello-world` conformance output does not move (S1/S4 preserve the local-FS
  branch verbatim).
- Cross-language parity debt by design: Go may land first for rag_go, but S1–S4 all move
  `SPEC.md §3` and must be recorded as unchecked per-port tasks (js, python, java, csharp) with the
  `SPEC.md` delta and, for S2/S4, a shared `examples/` fixture every port honors identically.
```
