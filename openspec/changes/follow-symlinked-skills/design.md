## Context

The skill discovery walker and the `skill` tool's sibling-file sampler both iterate directory entries
and recurse only when `entry.isDirectory()` is true. For a symlink to a directory that predicate is
false (the entry is a symlink, not a directory), so symlinked skill dirs are never descended and their
`SKILL.md` is never found. opencode avoids this by globbing with `symlink: true`.

## Goals / Non-Goals

**Goals:**
- Discover skills reachable through symlinked directories and symlinked `SKILL.md` files.
- Identical behavior across all five ports; guard against symlink cycles.

**Non-Goals:**
- Changing the glob pattern, frontmatter parsing, first-name-wins dedupe, or the `skill` tool output.
- Following symlinks that escape into system dirs beyond what the skills root references (no allowlist
  policy — same trust model as before; the host chooses the skills root).

## Decisions

**Resolve each symlink entry with a follow-stat.** When an entry `isSymbolicLink()`, stat the target
(follows the link): if it resolves to a directory, treat it as a directory to descend; if to a file
named `SKILL.md`, collect it. This mirrors opencode's `symlink: true` without pulling in a glob
dependency. Alternative considered: switch to a glob library per language — rejected as heavier and
divergent across ports.

**Cycle guard via resolved real path.** Before descending a directory, resolve its real path
(`realpath`) and skip if already visited. Symlink loops (a→b→a) otherwise hang the walk. A per-walk
`seen` set of real paths is cheap and portable.

**Apply to both walkers.** The discovery walker and the sibling-file sampler share the same fix so a
symlinked skill's resources also appear in the sampled file list.

## Risks / Trade-offs

- [Symlink cycle] → resolved-real-path `seen` set stops re-descent.
- [Broken symlink] → the follow-stat throws; caught and skipped (entry ignored), never fatal.
- [Slightly more `stat` calls] → only for symlink entries; negligible; discovery already does IO.
