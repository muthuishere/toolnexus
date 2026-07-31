# S12 — a vertical slice of SPEC §0 on both hosts

**Question:** can the toolnexus conformance contract be written in portable
Clojure over koine alone — **no reader conditional in toolnexus' own source** —
and produce byte-identical output on Clojure (JVM) and cljgo?

**Answer: yes.** Verified 2026-07-31.

```
== Clojure (JVM)
== cljgo (AOT binary)
== cljgo (interpreted)
== diff
  jvm == cljgo-aot  (byte-identical, 2746 bytes)
  jvm == cljgo-run  (byte-identical, 2746 bytes)
```

> **Corrected 2026-07-31.** The first version of this spike got §0.6 **wrong** in
> two ways, both caught by S19 and confirmed against the shipped Go and JS ports:
> the sibling sample must **exclude `SKILL.md` itself** (`skill.go:217`,
> `skill.ts:198`), and `skillsPrompt()` must carry the §3 preamble. The skill
> output is **995 bytes**, not the 1127 originally reported. Byte-identity across
> hosts was never the problem — the spike was identically wrong on both, which is
> exactly why a cross-host diff cannot substitute for a cross-*port* check.

Reproduce: `./run-both.sh` (needs `clojure`, `cljgo`, and `npx` for the MCP server).

## What it covers

One file, `src/toolnexus/slice.cljc`, 295 lines, **zero reader conditionals,
zero `java.*`, zero Go interop**:

| SPEC | what | via |
|---|---|---|
| §0.2 | `sanitize` + `sanitize(server)_sanitize(tool)` | pure |
| §0.3 | `mcp.json` parse, `mcpServers`/`servers`/`mcp`, `url`⇒remote / `command`⇒local, `disabled`/`enabled:false`, default timeout 30000, `${ENV}` header expansion | `koine.json`, `koine.env` |
| §0.4 | **a real MCP stdio session** — `initialize` → `notifications/initialized` → `tools/list` → `tools/call`, skipping interleaved notifications; result shaping (`isError` / `structuredContent` / joined text) | `koine.process/spawn` |
| §0.5 | skill discovery: `**/SKILL.md`, frontmatter, `name` required, first-wins | `koine.fs` |
| §0.6 | the **byte-exact** `skill` tool output + `skillsPrompt()` | pure |
| §0.7 | OpenAI / Anthropic / Gemini schema adapters | pure |

It runs against the **shared** `examples/mcp.json` and `examples/skills/` — the
same fixtures the other six ports run, not a Clojure-flavoured imitation. The
live server is `@modelcontextprotocol/server-everything`: 13 tools discovered,
`echo` called, `Echo: toolnexus` returned, on every host and every mode.

## Why three runs, not two

cljgo is exercised **interpreted and as an AOT binary**. cljgo's own ADR 0007
calls a REPL-vs-binary divergence unforgivable, and toolnexus ships binaries — a
spike that proves only one mode proves the wrong one. All three agree byte for
byte.

## What it found

1. **`cljgo run <file>` does not call `-main`.** It evaluates top-level forms and
   exits 0 with no output — which reads exactly like a program that ran and
   printed nothing. `src/run_interpreted.cljc` is the interpreted entrypoint for
   that reason. Related to the trap koine flagged (`cljgo test` silently running
   zero `.cljc` tests and exiting 0): on cljgo, **assert on output, never on the
   exit code.**
2. **cljgo prunes `org.clojure/clojure` from the koine artifact** and reports
   `11 namespace(s) with no Java interop` at dependency resolution — koine's
   central claim gets machine-checked every build.
3. **`koine.json`'s sorted-key encoding is what makes the diff possible.** The
   report is a nested JSON document; with insertion-order encoding the two hosts
   would differ on map ordering and the byte-comparison would be meaningless.

## What it does NOT cover

Remote streamable-HTTP transport (the fixture's remote server is
`enabled: false`), the client loop (§0.10), builtins (§0.11) and suspension
(§0.12). Those are composition over what is proven here — but "composition"
is a claim, not a measurement.

Header **values** are never printed: the report carries only which header keys
exist and whether `${ENV}` expansion changed anything (§0.3 — secrets are
use-only).
