## Why

Skill discovery walks directories with `readdir(..., withFileTypes)` and descends only entries where
`isDirectory()` is true — which is **false for a symlink to a directory**. Real-world skills roots
(e.g. `~/.claude/skills`) frequently symlink individual skills to out-of-tree repos: measured on one
machine, `find` sees 19 `SKILL.md` but `find -L` sees 38 — **half the skills are invisible**, including
the largest ones. opencode's skill glob sets `symlink: true`; toolnexus does not, so this is also a
parity bug with the reference.

## What Changes

- Skill discovery SHALL **follow symlinked directories and symlinked `SKILL.md` files** when walking a
  skills root, matching opencode. Symlink cycles are guarded by tracking resolved real paths.
- The `skill` tool's sibling-file sampler follows symlinks the same way, so a symlinked skill's
  resource files appear in the sampled `<skill_files>` list.
- Applied across **all five ports** (js/python/golang/java/csharp) with a per-port regression test.
- `SPEC.md §3` (skill discovery) updated to state the symlink-following contract.

## Capabilities

### New Capabilities
- `skill-discovery`: how a skills root is walked to find `SKILL.md` files — now including symlinked
  directories and files, with cycle protection.

### Modified Capabilities
<!-- No archived capability specs yet (openspec/specs/ is empty); the cross-language contract for
     discovery lives in SPEC.md §3, updated in this change. -->

## Impact

- **SPEC.md §3** — symlink-following clause.
- **js/python/golang/java/csharp** — the directory walker in skill discovery + the sibling-file
  sampler; one regression test per port (temp fixture with a symlinked skill dir).
- No API or config change; purely more-complete discovery. No new dependencies (each port uses its
  stdlib stat/realpath).
