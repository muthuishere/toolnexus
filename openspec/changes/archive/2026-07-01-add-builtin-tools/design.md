## Context

toolnexus already assembles four tool sources (MCP, skill, native `@tool`, remote HTTP) behind one
`Tool` interface, with a single toolkit builder per port that concatenates sources and dedupes by
name (first wins). MCP servers are toggled by a per-server `enabled`/`disabled` bool with a shared
`isEnabled` precedence (`disabled` wins). There is no library-provided toolset today.

opencode ships 12 default tools registered at location boot (`tool/builtins.ts`), all on by default,
disabled through a permissions ruleset (out of scope here). We are porting the toolset and its
on-by-default behavior, but replacing opencode's permission model with a single global on/off for the
whole source, per the product decision to keep v1 all-on-or-all-off.

Reference tool set + exact schemas (from opencode `packages/core/src/tool/`):

| name | parameters (required unless marked optional) |
|------|-----------|
| `bash` | `command:string`, `workdir?:string`, `timeout?:number(ms)`, `description?:string` |
| `read` | `path:string`, `offset?:number(line)`, `limit?:number(lines)` |
| `write` | `path:string`, `content:string` |
| `edit` | `path:string`, `oldString:string`, `newString:string`, `replaceAll?:boolean` |
| `grep` | `pattern:string(regex)`, `path?:string`, `include?:string(glob)`, `limit?:number` |
| `glob` | `pattern:string`, `path?:string`, `limit?:number` |
| `webfetch` | `url:string`, `format?:"text"\|"markdown"\|"html"` (default markdown), `timeout?:number(s)` |
| `websearch` | `query:string`, `numResults?:number(1-20)`, `livecrawl?:string`, `type?:string`, `contextMaxCharacters?:number` |
| `question` | `questions:Question[]` (label, question, multiple, custom, options) |
| `apply_patch` | `patchText:string` |
| `todowrite` | `todos:SessionTodo[]` (id, text, completed) |

`skill` already exists as its own source and is excluded.

## Goals / Non-Goals

**Goals:**
- A `builtin` tool source shipping the opencode default toolset with matching names + input schemas.
- On by default; a single global toggle to turn the whole source off, read from the existing config
  input under a top-level `builtins` key, reusing MCP's `isEnabled` precedence.
- Byte-parity across js/python/golang/java, pinned in `SPEC.md` and covered by per-port tests.
- Integration into each port's toolkit builder, preserving first-wins dedupe.

**Non-Goals:**
- opencode's `permissions` ruleset (allow/deny/ask, wildcards, shared `edit` action).
- Per-tool enable/disable granularity.
- Sandbox/permission prompting, approval hooks, or the `ask` effect.
- Reproducing opencode's exact tool *output formatting* byte-for-byte where it depends on host state
  (dir listings, image handling); behavior parity targets names, schemas, and core semantics.

## Decisions

**1. Single global toggle, not a permissions ruleset.** Config carries a top-level `builtins` object
with `enabled`/`disabled` booleans, evaluated by the same precedence as `isEnabled` for MCP. Rationale:
the product decision is all-on-or-all-off for v1; reusing the existing MCP precedence keeps four-port
parity trivial and avoids porting opencode's wildcard permission matcher into four languages. The
`permissions` model is a clean follow-up change when per-tool control is needed.

**2. New source module per port, not folded into native tools.** Each builtin tool is defined with
`source: "builtin"` in a dedicated module (`builtin.ts` / `builtin.py` / `builtin.go` / `Builtin*.java`),
exposing a factory that returns the enabled tool list. Rationale: keeps the source boundary explicit,
mirrors how `mcp`/`skill` sources are structured, and makes the toggle a single gate in front of the
whole list.

**3. Assembly order: MCP → skill → builtin → extraTools.** Builtins slot in before host `extraTools`
so a host-registered tool of the same name overrides a builtin under existing first-wins dedupe.
Rationale: hosts must be able to shadow a builtin (e.g. replace `bash` with a sandboxed variant)
without extra config.

**4. Native-per-runtime implementations, not a transliteration.** Each tool uses the port's idiomatic
stdlib: `bash` via the runtime's process API (child_process / subprocess / os/exec / ProcessBuilder);
`read`/`write`/`edit`/`grep`/`glob` via native fs + regex/glob; `webfetch`/`websearch` via the platform
HTTP client. Behavior (schemas, error-as-ToolResult, `isError` on failure) is identical; code shape is
native. Rationale: the repo's stated convention — same behavior, native shape.

**5. Staged delivery within one change.** To contain parity risk, implement in tiers, each landing in
all four ports before the next: (a) source scaffold + toggle + assembly wiring + `read`/`write`/`edit`;
(b) `bash`/`grep`/`glob`; (c) `webfetch`/`websearch`/`apply_patch`/`todowrite`/`question`. Every tier
carries the four-port checklist. Rationale: keeps each parity checkpoint small and verifiable against
`examples/`.

## Risks / Trade-offs

- **`bash`/`edit` semantic drift across four runtimes** → Pin exact behavior (working dir, timeout,
  exact-string-replace, `replaceAll`, not-found handling) in SPEC.md with shared example fixtures;
  add per-port tests asserting identical ToolResult on the same inputs.
- **`websearch` needs an external provider (opencode uses Exa/Parallel)** → For parity without a
  network dependency in hermetic CI, spec `websearch` to a provider-agnostic contract; if no provider
  is configured it returns an `isError` ToolResult rather than calling out. Revisit provider wiring if
  it proves too thin — flagged as an open question.
- **Security surface** → `bash` + fs mutation tools are powerful; the global toggle is the documented
  off-switch. CI stays hermetic (no network, no live LLM); tests must not execute destructive commands.
- **Output-format parity is softer than schema parity** → Accepted: we guarantee name/schema/semantic
  parity, not byte-identical human-readable output where it depends on host filesystem state.

## Open Questions

- **`websearch` provider**: ship a no-provider stub (isError when unconfigured) for v1, or defer
  `websearch` to a follow-up and land the other 10 tools now? Leaning stub-for-parity; confirm during apply.
- **`question` in a headless client**: the unified client loop has no interactive UI. Does `question`
  return its prompt as structured output for the host to answer, mirroring opencode's shape? Pin in SPEC.md.
- **`apply_patch` patch format**: adopt opencode's exact add/update/delete hunk grammar, or a minimal
  subset? Parity argues for the full grammar; confirm scope during apply.
