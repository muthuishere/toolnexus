## Reference & contract

`SPEC.md` is the contract; the built-in tools must behave **byte-identically** across the five
ports, exactly like the `skill` tool's byte-exact output does today (`SPEC.md §3`). Implement each
tool once in prose here + in the spec delta, then port. Match behavior, not syntax — idiomatic Node
`fs`/`child_process`, Python `os`/`subprocess`, Go `os`/`os/exec`, Java `java.nio`/`ProcessBuilder`,
C# `System.IO`/`System.Diagnostics.Process`.

## Why on-by-default (and the tension)

The user ask is: `read`/`write`/`execute`/`grep` should "always be default." That maximizes
zero-config ergonomics — a stock toolkit can act. The cost is a wider default surface: a
default-on toolkit can run shell commands and overwrite files under `rootDir` the moment an LLM asks.
We accept on-by-default per the product decision, and make the surface **bounded and reversible**:

1. **Root confinement** — file tools cannot read/write/search outside `rootDir` (default = cwd).
   Path resolution is `realpath`-then-`startsWith(rootDir)`; escape ⇒ `isError`, not an exception.
2. **One kill switch** — `defaultTools: false` removes the whole set; `exclude: ["execute"]` drops
   just the shell tool for callers who want files-only.
3. **No secret leakage** — command text and file contents are returned to the model but **never
   logged** by the library (consistent with the `${ENV_VAR}` / header rule in `SPEC.md §2/§7`).
4. **First-name-wins** — a caller's own `execute`/`read`/etc. shadows the built-in (existing dedup
   rule in `Toolkit`), so opinionated hosts can override without a flag.

If we later want safe-by-default, the switch is already there to flip the default; this change keeps
the default ON as requested and documents the flip point.

## The `defaultTools` option

```
createToolkit({
  mcpConfig?, skillsDir?, extraTools?,
  defaultTools?: boolean | {          // default: true
    rootDir?: string,                 // default: process cwd
    exclude?: ("read"|"write"|"execute"|"grep"|"glob")[],
  },
})
```

- `undefined` or `true` ⇒ all five built-ins, `rootDir = cwd`.
- `false` ⇒ none.
- object ⇒ the built-ins minus `exclude`, confined to `rootDir`.

Registration order in the toolkit constructor: **mcp → skill → extraTools → built-ins**. Built-ins go
**last** so any same-named caller tool (from `extraTools` or an MCP server) wins under first-name-wins.
(Chosen over "built-ins first" precisely so hosts can override without touching the flag.)

## Tool-by-tool contract (byte-exact — full detail lives in specs/builtin-tools/spec.md)

| Tool | args | success output | error output (`isError:true`) |
|------|------|----------------|-------------------------------|
| `read` | `path` (req), `offset?` int (1-based line), `limit?` int | file text; if `offset`/`limit` given, only those lines, verbatim (no numbering) | `read: <path>: <reason>` (not found / is a directory / outside root) |
| `write` | `path` (req), `content` (req) | `Wrote <N> bytes to <relpath>` (N = UTF-8 byte length) | `write: <path>: <reason>` (outside root / io error) |
| `execute` | `command` (req), `timeout?` ms (default 30000) | combined stdout+stderr text, trailing newline trimmed; exit 0 | same combined text; non-zero exit or timeout ⇒ `isError:true`, output ends with `\n[exit <code>]` (or `\n[timeout]`) |
| `grep` | `pattern` (req, regex), `path?` (default root), `glob?` (default `**/*`) | matches as `<relpath>:<line>:<text>` joined by `\n`, sorted by path then line; no match ⇒ output `""`, `isError:false` | `grep: <reason>` (bad regex / outside root) |
| `glob` | `pattern` (req), `path?` (default root) | matching file relpaths, `\n`-joined, sorted; none ⇒ `""` | `glob: <reason>` |

Cross-cutting rules:
- Paths in output are **relative to `rootDir`** with `/` separators (stable across OSes); inputs may
  be relative (resolved against `rootDir`) or absolute (must still land inside `rootDir`).
- `source: "builtin"` on every one; `metadata` may carry `{ exitCode }` (execute) / `{ bytes }` (write).
- Text is UTF-8. Binary/undecodable read ⇒ `isError` with `read: <path>: not valid UTF-8`.
- Determinism for parity: `grep`/`glob` outputs are **sorted** (path, then line) so five ports emit
  identical bytes for the same tree.

## Schema shape (identical JSON-Schema in every port)

Each tool ships a hand-written `inputSchema` (not inferred) so the five ports are guaranteed equal —
e.g. `read` = `{type:"object", properties:{path:{type:"string"}, offset:{type:"integer"},
limit:{type:"integer"}}, required:["path"], additionalProperties:false}`. The exact schemas are
pinned in the spec delta so adapters emit identical OpenAI/Anthropic/Gemini payloads.

## Testing (hermetic, per port)

- Build a temp dir tree in each test; set `rootDir` to it — never the real cwd.
- Assert byte-exact success + error strings for each tool (the parity lever).
- Traversal test: `read`/`write` with `../outside` ⇒ `isError`, file system untouched.
- `execute` non-zero exit + timeout ⇒ `isError` with the `[exit N]` / `[timeout]` suffix.
- `defaultTools:false` ⇒ toolkit has zero built-ins; `exclude:["execute"]` ⇒ four, no `execute`.
- Cross-port parity: same temp tree fixture ⇒ identical `grep`/`glob`/`read` bytes.

## Open questions (resolve during apply)

1. `execute` shell: `sh -c` (POSIX) vs `cmd /c` (Windows) — pick per-OS but keep output format
   identical. CI is Linux-hermetic; document the Windows mapping, don't gate parity on it.
2. `grep` engine: native regex per language (Go `regexp`, Python `re`, JS `RegExp`, Java/C# regex).
   Pattern dialects differ slightly — spec pins the common subset used in `examples/` and notes that
   exotic regex is best-effort, like OpenAPI import in §7.
3. Do we add `edit` (in-place replace) later? Out of scope here — keep the default set to five.
