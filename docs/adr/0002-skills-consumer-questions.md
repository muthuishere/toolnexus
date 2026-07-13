# For rag_go — 5 questions on toolnexus **skills** (before we build)

Companion to the MCP consumer ADR you already answered (A1–A9). We found the skill source
(`toolnexus` `skill.go` / `SPEC.md §3`) has the **same** filesystem-glob assumptions the MCP
source did — it can only load skills from on-disk `skills/` dirs, exposes *every* skill (no
per-agent gating), and silently drops any SKILL.md that fails to parse.

Before we write anything, five answers. **Q1 and Q5 are the ones that matter most** — they decide
whether the biggest change is a real epic or nearly a no-op. Answer inline; terse is fine.

---

**Q1 — Where do a tenant's skills actually live?**
Postgres / object storage, or is materializing a per-request `skills/` **tmpdir** on the box
acceptable? → If a tmpdir is fine, we skip the "skills as data" work entirely and just ship the
allowlist + validate (Q2/Q3). If they must stream from storage, we build an injectable source.

**Q2 — How is per-agent skill enablement stored?**
Keyed on the skill **`name`** (the SKILL.md frontmatter name), like MCP `enabledTools`? Or some
id? → Confirms what the allowlist filters on.

**Q3 — Is there a skill-authoring / validate UI?**
A libreadmin screen where an admin adds a skill and needs to be told *"skipped: no `name:`"* /
*"malformed YAML"* — the skill analog of the MCP validate screen? Yes/no. → Gates a
list-with-diagnostics API.

**Q4 — Are skills ever exposed over A2A (`toolkit.serve()`)?**
If yes, note it: today the skill output leaks absolute server paths
(`file:///home/.../tenant/skills/x`), useless + leaky to a remote caller. → Gates a logical-base
fix.

**Q5 — Instruction-only, or real resource files?**
Are your skills just the **SKILL.md body as prompt text**, or do they ship sibling files
(`scripts/`, `references/`) that must load when the skill is invoked? → Instruction-only collapses
the big change to "pass `{name, description, content}` structs." Resource-bearing skills need a
resolver.

**Also (housekeeping):** do you even use skills in rag_go today, or is this all forward-looking? And
any skill-side workaround you've already built that we should know about (the way the MCP ADR
captured the reverse proxy / watchdog)?

---

Paste answers back as an `A1…` block and we'll pin the skills ADR priorities and open the change.
Full detail (proposed APIs, acceptance tests, parity) lives in `0002-skills-consumer-needs.md`.
