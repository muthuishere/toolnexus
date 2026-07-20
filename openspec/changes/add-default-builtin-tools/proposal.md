## Why

Today a fresh `Toolkit` is **empty** until the caller wires up tools from one of the four sources
(MCP, skill, native, http). There is no built-in `read` / `write` / `execute` / `grep` — the
`execute` people see is `toolkit.execute(name, args)`, the dispatch router, **not** a shell tool.
So an LLM handed a stock toolkit cannot touch the filesystem or run a command without the developer
first hand-writing native tools. This surprises users coming from opencode / Claude Code, where those
primitives are always present, and it is the same in all five ports (the parity contract guarantees
it) — so the gap is real, not a two-port quirk.

This change gives toolnexus a **default-on built-in toolset** — the smallest set of local-agent
primitives — so "create a toolkit, hand it to an LLM, it can act" works out of the box, while
staying safe (root-dir confinement) and reversible (one switch turns it all off).

## What Changes

- **New tool source `source: "builtin"`** and five default tools, registered **automatically** on
  every `Toolkit` unless disabled:
  - `read` — read a UTF-8 text file (optional line `offset`/`limit`).
  - `write` — create or overwrite a text file.
  - `execute` — run a shell command, capture combined stdout+stderr and exit code.
  - `grep` — regex search across files, emit `path:line:text` matches.
  - `glob` — list files matching a glob pattern.
- **Root-directory confinement.** File tools (`read`/`write`/`grep`/`glob`) resolve every path
  under a configurable `rootDir` (default: process working directory); a path that escapes the root
  is an `isError` result, never a traversal. `execute` runs with its working directory set to
  `rootDir`.
- **On by default, with a kill switch.** `createToolkit({...})` includes the built-ins with no opt-in.
  `defaultTools: false` removes all of them; an options form (`{ rootDir, exclude: ["execute"] }`)
  scopes the root or drops individual tools (notably `execute`, the shell surface). A caller-provided
  tool of the same name wins (first-name-wins already holds), so built-ins never shadow explicit tools.
- **Contract move.** `SPEC.md §0` (the conformance contract) gains the built-in toolset: the tool
  names, input schemas, byte-exact output/error formats, the default-on rule, and the disable switch.
  A new `SPEC.md §11` documents each tool. This is a **behavior change to the shared contract** and
  lands in **all five ports together** (js / python / golang / java / csharp) — or it is not done.

## Capabilities

### New Capabilities
- `builtin-tools`: the default-on `read`/`write`/`execute`/`grep`/`glob` toolset, its root-dir
  confinement, output/error formats, and the default-on/disable behavior — byte-identical across the
  five ports against the shared `examples/`.

### Modified Capabilities
<!-- none archived yet: openspec/specs/ is empty, so all requirements are ADDED. The toolkit
     default-inclusion behavior is captured as an ADDED requirement here and mirrored into SPEC.md §0. -->

## Impact

- Each port gains a built-in tools module (JS `builtin.ts`, Python `builtin.py`, Go `builtin.go`,
  Java `Builtin*.java`, C# `Builtin*.cs`) and wires it into the toolkit constructor default.
- `ToolkitOptions` (and each port's equivalent) gains a `defaultTools` / `rootDir` option.
- `SPEC.md`: §0 contract line + new §11; the `source` enum gains `"builtin"`.
- Tests: a new hermetic suite per port (temp-dir sandbox — no network, no real cwd writes).
- **Security note (see design.md):** shipping a shell + filesystem-write toolset *on by default*
  widens the default surface. Confinement, the `execute` opt-out, and never-logged command text are
  load-bearing and specified below.
