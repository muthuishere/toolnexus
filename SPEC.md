# toolnexus — Shared Contract

A small, provider-agnostic library that gives **any LLM** the same two dynamic
capabilities opencode has:

1. **Dynamic MCP servers** — read an MCP config file, connect to every server
   (local stdio + remote streamable-HTTP), and expose each server tool as a
   uniform tool.
2. **Dynamic agent skills** — read a skills folder (`**/SKILL.md`), and expose a
   single `skill` tool that loads a skill's instructions + resources on demand
   (progressive disclosure), exactly like opencode/Claude skills.

The output is a single **Toolkit** of uniform `Tool`s plus adapters that emit the
tool schema in OpenAI / Anthropic / Gemini formats. You wire the schema into your
LLM call; when the model asks for a tool, you call `toolkit.execute(name, args)`.

This contract is identical across the `js/`, `python/`, `golang/`, `java/`,
`csharp/`, and `elixir/` implementations. Each builds on the most popular MCP SDK
for that language — except Elixir, which (lacking a mature SDK) owns its MCP
client in-house:

| Lang   | MCP SDK                                  |
|--------|------------------------------------------|
| JS/TS  | `@modelcontextprotocol/sdk` (same as opencode) |
| Python | `mcp` (modelcontextprotocol/python-sdk)  |
| Go     | `github.com/mark3labs/mcp-go`            |
| Java   | `io.modelcontextprotocol.sdk:mcp` (official)   |
| C#     | `ModelContextProtocol` (official)        |
| Elixir | in-house client (`Toolnexus.Mcp.*` — JSON-RPC 2.0, stdio + streamable-HTTP) |

> **Who reads this:** nobody, normally. End users read the per-language READMEs.
> This file exists only so a future porter keeps JS/Python/Go byte-identical.
> The one-page contract below is the whole obligation; everything after §1 is
> reference detail.

---

## 0. Conformance contract (the whole thing, one page)

A new language port is "correct" iff these hold. Run it against the shared
`examples/` (same `mcp.json` + skill) and the outputs must match the others.

1. **Tool** = `{ name, description, inputSchema (JSON-Schema object), source, execute(args,ctx)->ToolResult }`.
   `ToolResult = { output: string, isError: bool, metadata? }`.
2. **sanitize(x)** = replace `[^a-zA-Z0-9_-]` with `_`. **MCP tool name** = `sanitize(server)_sanitize(tool)`.
3. **MCP config**: accept top-level `mcpServers` | `servers` | `mcp`. `url`⇒remote, `command`⇒local.
   `disabled`/`enabled:false` ⇒ skip. Default timeout 30000ms. A failed server is isolated (status `failed`), never fatal.
   Remote `headers` values expand `${ENV_VAR}` from the environment (never logged).
4. **MCP execute**: `isError`⇒error w/ joined text; `structuredContent`⇒`JSON.stringify`; else joined text parts.
5. **Skills**: glob `**/SKILL.md`, YAML frontmatter (`name` required), body=content, first name wins.
6. **`skill` tool** output is byte-exact:
   ```
   <skill_content name="NAME">
   # Skill: NAME

   <trimmed body>

   Base directory for this skill: file:///abs/dir
   Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
   Note: file list is sampled.

   <skill_files>
   <file>/abs/path</file>
   </skill_files>
   </skill_content>
   ```
   `skillsPrompt()` (when ≥1 described skill) = the instruction preamble (§3) + `\n\n` +
   `## Available Skills\n- **name**: description` (sorted, described only); empty/"no skills" otherwise.
7. **Adapters** (schema only): OpenAI `{type:"function",function:{name,description,parameters}}` ·
   Anthropic `{name,description,input_schema}` · Gemini `[{functionDeclarations:[{name,description,parameters}]}]`.
8. **native** tool (`source:"native"`): fn→Tool; string return⇒output, throw/err⇒isError.
9. **http** tool (`source:"http"`): `{ph}` URL substitution, `${ENV}` header expansion (never logged),
   non-2xx⇒`HTTP <status>: <body>` isError, else body text.
10. **Unified client**: `{baseUrl,style:"openai"|"anthropic",model,apiKey?}`; system = systemPrompt+`\n\n`+skillsPrompt();
    loop call→execute tool calls→feed back (provider's tool-result shape)→repeat to maxTurns(10).
11. **Built-in tools** (`source:"builtin"`): the default toolset — `bash, read, write, edit, grep, glob,
    webfetch, question, apply_patch, todowrite` — names + input schemas per §4A. **On by default**;
    global toggle (`builtins:false` | `builtins.disabled:true` | `builtins.enabled:false` ⇒ whole source off, MCP
    precedence). **Per-tool** override via `builtins.tools` (a name→bool map on the all-on baseline: `{bash:false}` drops
    `bash`, `{...:true}` keeps it on). Global-off short-circuits the map. Surfaced via the tool-schema array only
    (like MCP) — **not** the system prompt.
12. **Suspension** (§10): a `ToolResult` whose `metadata.pending` is a `Request` is a suspension.
    With a `waitFor` host slot: call `answer = waitFor(request)`; on `ok` re-execute the tool once
    with `Context.answer = answer` and feed that back; on `!ok` (or a second suspension) feed back an
    error result. Without `waitFor`:
    `run` does not hang — it returns `{status:"pending", pending:request}`. `Request` =
    `{id,kind,prompt,url?,data?,expiresAt?}`, `Answer` = `{id,ok,data?}`, keys byte-identical across ports.
    Streaming emits `{type:"pending",request}`. Never name the slot `await` (reserved in JS/Python/C#).

That's it. The sections below just expand each point with wire-format detail.

---

## 1. Core types

### Tool

The single uniform abstraction. Mirrors opencode's `dynamicTool`.

```
Tool {
  name:        string          // unique, sanitized to [a-zA-Z0-9_-]
  description: string
  inputSchema: JSONSchema      // a JSON-Schema object ({type:"object", properties, ...})
  source:      "mcp" | "skill" | "native" | "http" | "custom"
  execute(args: object, ctx?: Context) -> ToolResult
}
```

### ToolResult

```
ToolResult {
  output:    string            // text handed back to the model
  isError:   boolean
  metadata?: object            // free-form (title, server, skill name, ...)
}
```

### Context (optional, passed to execute)

```
Context {
  signal?:  AbortSignal/cancellation token
  timeout?: number (ms)
  answer?:  Answer             // present ONLY on a post-waitFor retry (§10); carries the resolution
}
```

### Pending (suspension — full contract in §10)

A tool that needs an out-of-band, async resolution (login, approval, input) returns a
normal `ToolResult` carrying `metadata.pending = Request`. The client calls the host's
`waitFor(request) -> Answer` and retries the tool. `execute`'s signature is unchanged —
suspension is **data on the existing result**, not a new return type.

```
Request { id, kind, prompt, url?, data?, expiresAt? }   // byte-identical wire data
Answer  { id, ok, data? }
```

---

## 2. MCP source

### Config file format

Superset of the Claude-desktop / opencode format. The top-level key may be
`mcpServers` (Claude style), `servers`, or `mcp` — all accepted.

```jsonc
{
  "mcpServers": {
    "filesystem": {
      "type": "local",                       // optional; inferred from command/url
      "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "environment": { "FOO": "bar" },        // alias: "env"
      "cwd": "/optional/working/dir",
      "enabled": true,                         // alias: "disabled": false
      "timeout": 30000
    },
    "remote-api": {
      "type": "remote",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer ..." },
      "timeout": 30000
    }
  }
}
```

Type inference when `type` is omitted: presence of `url` ⇒ `remote`, presence of
`command` ⇒ `local`.

**Reserved sibling keys:** when no `mcpServers`/`servers`/`mcp` wrapper is present and the object is
used as the raw server map, the parser MUST ignore the reserved top-level keys **`builtins`** (§4A),
**`agents`** (§7A, outbound), **`a2a`** (§7B, inbound card config), and **`mcpServer`** (§7C, inbound
MCP serve profile — singular, distinct from the client-side plural `mcpServers` wrapper) — they are
sibling config sections, not MCP servers. (Without this, a config file that carries only a `builtins`
toggle, an `agents` block, an `a2a` card block, or an `mcpServer` profile would produce a bogus MCP
server.)

### Behaviour (mirrors opencode `mcp/index.ts` + `mcp/catalog.ts`)

- **local** → connect over **stdio** (spawn `command[0]` with `command[1:]`, merged
  env = process env + `environment`, in `cwd`).
- **remote** → connect over **streamable-HTTP** (fall back to SSE if needed), with
  optional `headers`. Header values support `${ENV_VAR}` expansion (same as HTTP
  tools, §7) so tokens live in the environment, not the committed config — e.g.
  `"Authorization": "Bearer ${ACME_TOKEN}"`. Values are never logged. (OAuth is out
  of scope for v1 — pass a bearer token via `headers`.)
- A `disabled`/`enabled:false` server is skipped.
- Connect with `timeout` (default **30000 ms**). On failure, record status
  `failed` and continue — one bad server never breaks the toolkit.
- **Per-server load bound.** Each *phase* — connect, `initialize`, `tools/list` —
  is bounded by the server `timeout` (a fresh budget per phase, so a slow-but-working
  server whose init *and* list are each slow still succeeds). A server that connects
  but then stops responding is therefore given up within roughly **(waited phases) ×
  `timeout`** in the worst case, not a single `timeout` — this is intentional (it
  protects slow servers), always **bounded and isolated** (`failed`, never fatal, never
  a hang), and a port MAY bound tighter (≤ per-phase) as long as it never exceeds it.
  Set a smaller per-server `timeout` if a faster give-up on an unresponsive server matters.
- After connect, **list tools** (paginate via `nextCursor`) and convert each.
- Tool name = `sanitize(server) + "_" + sanitize(toolName)` where
  `sanitize(x) = x.replace(/[^a-zA-Z0-9_-]/g, "_")`.
- `execute(args)` → `client.callTool({name, arguments: args})`:
  - if result `isError` ⇒ `ToolResult{ isError:true, output: <joined text content> }`
  - if `structuredContent` present ⇒ output = `JSON.stringify(structuredContent)`
  - else ⇒ output = joined text of `content[]` text parts.
- `close()` disconnects every client (and kills stdio child trees).

### Status (queryable)

`connected | disabled | failed`  (v1 drops opencode's `needs_auth` /
`needs_client_registration` since OAuth is deferred.)

### Elicitation bridge (server→client input, mapped onto §10)

An MCP server may **elicit** input from the user *during* a `tools/call` it is servicing —
`elicitation/create`, a reverse-request on the same session (the mirror of sampling/roots). toolnexus
bridges it onto the one §10 `waitFor` rather than inventing a second input path:

- When the MCP source is constructed **with** a `waitFor` (from `createToolkit({ waitFor })`, typically
  the same function given to the client), the MCP client advertises the `elicitation` capability and
  registers a handler. **Without** a `waitFor`, the capability is not advertised — a compliant server
  will not elicit, so the host degrades cleanly.
- The handler maps the request onto a `Request` and back: **form mode** → `kind:"input"` with the
  `requestedSchema` carried in `data.schema`; **URL mode** → `kind:"authorization"` with `url`. It calls
  `waitFor(request)` and maps the `Answer` to the MCP result: `ok` → `accept` (with `answer.data` as the
  content); `ok==false` → `decline` if `answer.reason=="declined"`, else `cancel`. It satisfies the
  reverse-request **inline** — it does not re-execute the tool; the in-flight `tools/call` resumes when
  the handler returns.
- Credentials are never collected via form/`input` (the §10 trust boundary): a server needing a secret
  uses URL mode → `kind:"authorization"`, entered out-of-band. v1 ships form mode across all ports; URL
  mode where the port's MCP SDK supports it.

### Host-facing extensions (ctx-aware load, per-server allowlist, list-only inventory)

Additive; grounded in `docs/adr/0001-rag-go-consumer-needs.md`. A caller passing none of these gets
byte-identical behavior.

- **Gap 3 — ctx-aware load + bounded SSE.** A context-aware entry point (`LoadMcpWithContext` /
  `signal` passthrough) propagates the caller's cancellation/deadline through connect, initialize, and
  list, and the SSE-fallback start is bounded by the server `timeout` (it previously ran unbounded).
  A per-server timeout **within** budget marks only that server `failed` and the build continues;
  parent-ctx cancellation/deadline aborts the **whole** load and returns the context error.
- **Gap 7 — per-server tool allowlist.** `ServerConfig.tools` (`name → bool`, keyed on the server's
  ORIGINAL tool name) selects which of a server's tools are exposed, with semantics identical to the
  builtins §4A and skills §3 filters (nil/empty ⇒ all; ≥1 `true` ⇒ allowlist; only-`false` ⇒ drop-list;
  unknown ⇒ ignore+warn). Applied to the listed defs before sanitize/prefix.
- **Gap 6 — list-only inventory.** `ListMcpTools` connects to every enabled server, lists its tool
  defs, and disconnects — no toolkit, nothing left running. Returns per server the full **unfiltered**
  tool defs under their original names (`ToolInfo{name, description, inputSchema}`) plus a per-server
  status (`connected|disabled|failed`); failure isolation as in the normal load.
- **Toolkit sugar.** `DisableTools []string` drops tools by their final exposed name across every
  source; `DisableSkills []string` drops skills by name from the `skill` catalog. Both compose with the
  map filters above.

---

## 3. Skill source

### Discovery (mirrors opencode `skill/index.ts`)

- Recursively glob `**/SKILL.md` under the given skills dir(s). Discovery **follows symlinked
  directories and symlinked `SKILL.md` files** (matching opencode's `symlink: true` glob), so a
  skills root that symlinks to out-of-tree skills still discovers them. Symlink cycles are guarded by
  tracking resolved real paths already visited. The sibling-file sampler (§ the `skill` tool) follows
  symlinks the same way.
- Parse the `---`-fenced YAML frontmatter with a **standard YAML parser** (each port uses its
  ecosystem lib: js `yaml`, python `PyYAML`, go `gopkg.in/yaml.v3`, java SnakeYAML, csharp YamlDotNet)
  — NOT a hand-rolled `key: value` split — so folded (`>`), literal (`|`), chomping, quoting, and
  multi-line values all resolve. Scalar values are coerced to string and **trimmed** (so block-scalar
  trailing newlines, which chomp slightly differently per lib, don't leak and the ports stay
  byte-identical). Malformed YAML fails gracefully (empty frontmatter, skill skipped for missing
  `name`), never crashing discovery. Require `name` (string), optional `description` (string). Body
  after frontmatter = `content`.
- Skill `Info { name, description, location (abs path to SKILL.md), content }`.
- Duplicate names: first wins, log a warning.

### The `skill` tool (mirrors opencode `tool/skill.ts`)

A **single** tool named `skill`, shipped **by default**:

```
name: "skill"
description: <the loader description — see skill.txt below>
inputSchema: { type:"object", properties:{ name:{type:"string",
              description:"The name of the skill to load"} }, required:["name"] }
```

`execute({name})`:
1. Look up the skill; if missing ⇒ `ToolResult{isError:true, output:"Skill \"x\" not found. Available: ..."}`.
2. Sample up to 10 sibling files (everything except `SKILL.md`) in the skill dir.
3. Return progressive-disclosure block:

```
<skill_content name="NAME">
# Skill: NAME

<trimmed SKILL.md body>

Base directory for this skill: file:///abs/dir
Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
Note: file list is sampled.

<skill_files>
<file>/abs/dir/scripts/foo.sh</file>
...
</skill_files>
</skill_content>
```

### System-prompt helper

`skillsPrompt()` returns the markdown catalog to inject into the system prompt so
the model knows which skills exist (mirrors opencode `Skill.fmt` + `SystemPrompt.skills`).
When ≥1 described skill exists, it begins with a fixed **instruction preamble** (byte-identical
across all ports) telling the model to load a skill via the `skill` tool, then the list:

```
Skills provide specialized instructions and workflows for specific tasks.
Use the skill tool to load a skill when a task matches its description.

## Available Skills
- **name**: description
- ...
```

When no described skill exists, the output is the existing empty/"No skills are currently
available." result with **no** preamble.

### skill.txt (loader description, verbatim from opencode)

```
Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.

The skill name must match one of the skills listed in your system prompt.
```

### Host-facing extensions (injectable sources, filter, inventory, sample cap)

All additive: a caller supplying only skill **directories** and nothing else gets byte-identical
behavior to the sections above. Grounded in `docs/adr/0002-skills-consumer-needs.md`.

**S1 — Skills supplied as data / provider.** Besides directories, the source accepts skills as
data — `SkillDef { name, description?, content, resources? (logical names), base? (logical, default
`skill://<name>/`) }` — and/or a lazy provider resolved once at toolkit build. The three sources
compose and dedupe by `name` with the existing **first-wins** rule (directory candidates precede
data candidates). A failing provider is isolated (other sources still load), mirroring MCP
per-server isolation.

**S2 — Per-agent skill allowlist.** An optional `name → bool` filter selects which discovered
skills are exposed, with semantics **identical to the MCP per-server `tools` filter and builtins
§4A**: nil/empty ⇒ all; ≥1 `true` ⇒ allowlist (only true-mapped names); only-`false` ⇒ drop-list
over the all-on baseline; unknown names ignored + warned once. Applied after discovery, before the
`skill` tool is built, so the prompt catalog and the tool's lookup agree.

**S3 — List/validate inventory.** A list-only operation (`listSkills` / `list_skills` / `ListSkills`)
discovers + validates from the same sources and returns `{ skills, skipped }`, where each skip
carries a typed reason: `missing-name` | `malformed-frontmatter` | `duplicate-name` | `unreadable`.
It wires **no** toolkit and leaves nothing open. The inventory is **unfiltered** (it exists to author
the S2 allowlist).

**S4 — Logical output base for non-filesystem skills.** In the `skill` tool output:
- A **directory-sourced** skill is **byte-identical** to the section above — a `file://` base plus
  on-disk sibling sampling (the shared `examples/skills/hello-world` output must not move).
- A **data/provider-sourced** skill uses the logical `base` (default `skill://<name>/`) and lists
  the supplied `resources` in `<skill_files>`, emitting **no** absolute host path. A data skill with
  **no** resources is instruction-only: the `<skill_files>` block (and its "Note: file list is
  sampled." line) is omitted entirely.

**S5 — Configurable sibling-file sample cap.** An optional `sampleLimit`: `0` ⇒ default 10 (today's
behavior), `n > 0` ⇒ cap the sample at `n`, `-1` ⇒ omit the `<skill_files>` block entirely.

---

## 4. Toolkit (aggregator)

```
createToolkit({
  mcpConfig?:  string | object,   // path to config file OR parsed object
  skillsDir?:  string | string[], // one or more skill roots
  extraTools?: Tool[],            // your own custom tools
  builtins?:   boolean | object,  // built-in tools (§4A); default ON. false|{disabled:true}|{enabled:false} ⇒ off;
                                  //   {tools:{bash:false,...}} ⇒ per-tool disable on the all-on baseline
}) -> Toolkit

Toolkit {
  tools()              -> Tool[]                 // mcp tools + skill tool + builtin tools + extras
  get(name)            -> Tool | undefined
  execute(name, args, ctx?) -> ToolResult        // routes to the right tool
  skillsPrompt()       -> string                 // system-prompt catalog
  mcpStatus()          -> { [server]: "connected"|"failed"|"disabled" }
  close()              -> void

  // provider adapters — schema only, names match tools()
  toOpenAI()    -> [{ type:"function", function:{ name, description, parameters } }]
  toAnthropic() -> [{ name, description, input_schema }]
  toGemini()    -> [{ functionDeclarations:[{ name, description, parameters }] }]
}
```

"Custom tools by default" = the `skill` tool (and any `extraTools`) are always in
the toolkit alongside the dynamic MCP tools, so the same toolkit drives any LLM.

### Adapter mapping

- **OpenAI**: `{type:"function", function:{name, description, parameters: inputSchema}}`
- **Anthropic**: `{name, description, input_schema: inputSchema}`
- **Gemini**: `{functionDeclarations:[{name, description, parameters: inputSchema}]}`
  (one element wrapping all tools).

The execution loop is identical for every provider: read the tool name + args the
model returned → `toolkit.execute(name, args)` → feed `output` back as the tool
result message.

### Assembly order & builtin toggle

`tools()` assembles deterministically: (1) drop any builtin whose name a host `extraTools` entry
provides — so a host can shadow a builtin (e.g. a sandboxed `bash`); (2) concatenate the remaining
sources in the order **MCP → skill → builtin → extraTools**; (3) dedupe by name, **first wins** (this
governs the other pairs — MCP beats skill beats builtin). Net effect: MCP > skill > builtin for their
own collisions, and `extraTools` > builtin for a shared name. The builtin
source is **on by default**. The `builtins` option (or a top-level `builtins` key in a parsed config
object) controls it at two levels:
- **Whole source** — `builtins:false`, `{disabled:true}`, or `{enabled:false}` turns the entire source
  off (MCP precedence: `disabled:true` wins, else `enabled:false` disables, else on).
- **Per tool** — `builtins.tools` is a name→bool map applied on the all-on baseline: a tool mapped to
  `false` is dropped, `true` (or absent) stays on. Unknown names are ignored. A whole-source-off
  short-circuits and the map is not consulted.

Builtin tools reach the model **only** through `toOpenAI()/toAnthropic()/toGemini()` (like MCP tools);
nothing about them is added to the system prompt.

---

## 4A. Built-in tools (source: "builtin")

The default toolset toolnexus ships so an agent can act with zero custom wiring — opencode's
built-ins, ported with **identical tool names and input schemas** across all ports. Every tool
here has `source:"builtin"` and obeys the uniform `Tool`/`ToolResult` contract (§1): a failure is a
`ToolResult{isError:true, output:<message>}`, never a thrown exception across the boundary. Paths are
resolved relative to the process working directory unless absolute. Implementations are **native per
runtime** (child-process / fs / HTTP of each language) — same behavior, idiomatic shape.

The ten tools (`skill` is its own source, §3, and is not part of this set; the file-backed
`memory` tool is **opt-in** — a persona surface wired only when an agent home dir is present,
§7E — never one of these default, process-stateless builtins):

| name | inputSchema (required unless `?`) | behavior |
|------|-----------------------------------|----------|
| `bash` | `command:string`, `workdir?:string`, `timeout?:number(ms,default 60000)`, `description?:string` | Run one shell command via the runtime's process API in `workdir` (default cwd). Output = combined stdout+stderr. Non-zero exit ⇒ `isError:true`, output includes the exit code. Timeout kills the child ⇒ `isError:true`. |
| `read` | `path:string`, `offset?:number(1-based line)`, `limit?:number(lines)` | Read a UTF-8 text file. With `offset`/`limit`, return that line window. Missing file ⇒ `isError:true`. |
| `write` | `path:string`, `content:string` | Write `content` to `path` (create/overwrite), creating parent dirs. Output = confirmation w/ byte count. |
| `edit` | `path:string`, `oldString:string`, `newString:string`, `replaceAll?:boolean` | Exact-string replace in `path`. Default replaces the single occurrence; `oldString` absent OR (without `replaceAll`) non-unique ⇒ `isError:true`. `replaceAll:true` replaces all. |
| `grep` | `pattern:string(regex)`, `path?:string(dir,default cwd)`, `include?:string(glob)`, `limit?:number` | Search file contents by regex under `path`, optionally filtered by `include` glob. Output = `file:line:text` matches, capped at `limit` (default 100). |
| `glob` | `pattern:string`, `path?:string(dir,default cwd)`, `limit?:number` | List files matching the glob under `path`. Output = newline-joined relative paths, capped at `limit` (default 100). |
| `webfetch` | `url:string`, `format?:"text"\|"markdown"\|"html"(default markdown)`, `timeout?:number(s,default 30)` | HTTP GET `url`; return body as text/markdown/html. Non-2xx ⇒ `isError:true` w/ `HTTP <status>`. |
| `question` | `questions:array` (each `{ question:string, header?:string, options?:string[], multiple?:boolean }`) | **Suspends** (§10) — asking the human is a `Request`, not a special case. First call returns `pending({ kind:"question", prompt:<rendered>, data:{questions} })`; the host's `waitFor` resolves it, the loop re-executes the tool with `ctx.answer`, and the tool returns `ok(<JSON of answer.data>)` (the resolution *is* the answer, as with `kind:"input"`). With no `waitFor`, the run halts durably (`RunResult.status:"pending"`). `<rendered>` = each question's text in order, `" (options: a, b, c)"` appended when it has non-empty `options`, joined by `"\n"` (no trailing newline; `header` is not rendered, it stays in `data.questions`) — **byte-identical across ports**. |
| `apply_patch` | `patchText:string` | Apply one patch using opencode's grammar: `*** Begin Patch` / `*** Add File: p` / `*** Update File: p` / `*** Delete File: p` / `*** End Patch`, with `+`/`-`/context lines. Applies add/update/delete atomically; a hunk that doesn't match ⇒ `isError:true` and no partial write. |
| `todowrite` | `todos:array` (each `{ id:string, text:string, completed:boolean }`) | Replace the session todo list with `todos`. Output = the rendered list. Stateless across processes in v1 (echoes back the list). |

Enable/disable (§4 assembly): the whole source is gated by the `builtins` toggle (`false` /
`{disabled:true}` / `{enabled:false}` ⇒ none of the ten appear anywhere). Individual tools are
gated by `builtins.tools` — a name→bool map on the all-on baseline (`{tools:{bash:false,write:false}}`
drops those two, the rest stay). Whole-source-off wins over the map.

Safety: `bash`, `write`, `edit`, `apply_patch` execute commands / mutate the filesystem. Command
output and `${ENV}`-expanded values are **never logged**; no secret value is written into any spec,
test, or example fixture. The global toggle is the supported off-switch for locked-down hosts.

---

## 5. Reference usage (pseudocode, identical shape in all 3 langs)

```
tk = createToolkit({ mcpConfig: "./mcp.json", skillsDir: "./skills" })

tools   = tk.toOpenAI()                  // or toAnthropic()/toGemini()
sysmsg  = tk.skillsPrompt()

# ... call your LLM with `tools` + `sysmsg` ...
# model returns tool_call { name, arguments }

res = tk.execute(name, arguments)
# feed res.output back to the model, loop.

tk.close()
```

---

## 6. Native / annotation tools (source: "native")

Turn a plain function into a `Tool`. Two ergonomic forms per language; both produce
the same uniform `Tool` (`source: "native"`).

- **JS/TS** — `defineTool({ name, description, inputSchema, run })` where `run(args, ctx)`
  returns a string or `ToolResult`.
- **Python** — `define_tool(fn, name=..., description=..., input_schema=...)`, usable
  directly or as a decorator; the input schema is **inferred** from type hints + the
  docstring (first line = description). Explicit `name`/`description`/`input_schema`
  override inference.
- **Go** — `NativeTool(name, description, inputSchema, func(ctx, args) (string, error))`,
  plus `NativeToolReflect[T](name, desc, func(ctx, T) (string,error))` that derives the
  schema from the struct `T` via `json`/`jsonschema` tags.

Rules:
- A `run` that returns a plain string ⇒ `ToolResult{output: <string>, isError:false}`.
- A thrown error / returned error ⇒ `ToolResult{isError:true, output: <message>}`.
- Schema inference (Python type hints / Go struct tags): `str→string`, `int/float→number`,
  `bool→boolean`, `list→array`, `dict→object`; required = params without defaults.
  When inference is impossible, require an explicit `input_schema`.

Pass native tools to the toolkit via `extraTools` (they are just `Tool`s), or register
them with `tk.register(tool)`.

---

## 7. HTTP / REST tools (source: "http")

Declare a remote endpoint as a tool. The library performs the request and returns the
response body text.

```
httpTool({
  name, description,
  method,                       // "GET" | "POST" | ...
  url,                          // may contain {placeholders} filled from args
  headers?,                     // static headers; ${ENV_VAR} expands from process env
  query?,                       // arg names sent as querystring
  body?,                        // "json" (default) | "form" | "raw"; remaining args become the body
  inputSchema,                  // JSON-Schema for the args the model supplies
  timeout?,                     // ms, default 30000
  resultMode?,                  // "text" (default) | "json" | "status+text"
})
```

Behaviour:
- URL `{name}` placeholders are substituted from matching args (and removed from the body).
- `headers` values support `${ENV}` expansion so secrets are read from the environment,
  never hardcoded. Never log header values.
- Non-2xx ⇒ `ToolResult{isError:true, output:"HTTP <status>: <body>"}`.
- 2xx ⇒ `ToolResult{output:<body text>, isError:false, metadata:{status}}`.

**OpenAPI import (best-effort):** `httpToolsFromOpenAPI(specUrlOrPath, { baseUrl?, headers? })`
returns one `httpTool` per operation (operationId = tool name, parameters/requestBody →
inputSchema). The single-endpoint `httpTool` is the required core; OpenAPI import may
land as a follow-up.

---

## 7A. A2A agents — outbound (source: "a2a")

Call remote **A2A agents** and expose their skills as tools. A genuine, minimal **subset of real A2A**
(JSON-RPC 2.0; verified against the `a2a-python` SDK): the Agent Card lives at
`/.well-known/agent-card.json`, the wire is JSON-RPC over one HTTP POST, and we use the
**submit→poll** methods `SendMessage` + `GetTask`. No streaming / push / gRPC / auth in core.

```
agent({ card, headers?, timeout?, pollEvery? }) -> Agent   // card = Agent Card URL
createToolkit({ agents: [a1, a2] })                        // array of Agent objects (like extraTools)
toolkit.addAgent(agentOrCardUrl, opts?)                    // async, runtime add (register()s the tools)
// config-file `agents` block (mirrors mcpServers): { "<id>": { card, headers?, timeout?, pollEvery?, enabled?/disabled? } }
```
Defaults: `timeout` 300000ms, `pollEvery` 1000ms. `headers` support `${ENV}` expansion, never logged.
`enabled`/`disabled` use MCP `isEnabled` precedence. The config key is only an identifier — the tool
prefix comes from the fetched card's `name`.

**Resolve** = `GET <card>` → `AgentCard { name, description, version, protocolVersion,
capabilities:{streaming,pushNotifications}, defaultInputModes, defaultOutputModes,
skills:[{id,name,description,tags?,examples?}], url }`. For each skill emit a Tool: name =
`sanitize(card.name) + "_" + sanitize(skill.id ?? skill.name)`, `source:"a2a"`, inputSchema
`{type:"object", properties:{task:{type:"string"}}, required:["task"]}` (JSON-RPC endpoint = `card.url`,
fallback = card URL origin). A failing agent is isolated (logged, no tools), never fatal — like MCP.

**execute(args, ctx)** — one **`SendMessage`** then poll **`GetTask`**:
```
POST card.url  {"jsonrpc":"2.0","id":<uuid>,"method":"SendMessage",
  "params":{"message":{"role":"user","messageId":<uuid>,"parts":[{"kind":"text","text":<task>}]},
            "configuration":{"blocking":false}}}                     → result Task {id, status:{state}}
POST card.url  {"jsonrpc":"2.0","id":<uuid>,"method":"GetTask","params":{"id":<taskId>}}  (every pollEvery)
```
Poll loop: check abort → check timeout → sleep(pollEvery) → check abort → `GetTask`, until a terminal
state. **TaskState**: `submitted|working|completed|failed|canceled` (terminal = completed/failed/canceled).
Map: `completed` ⇒ `ToolResult{isError:false, output:` all `kind:"text"` parts across `artifacts[].parts[]`
joined by `"\n"` (fallback: last `role:"agent"` history message text) `}`; `failed`/`canceled` ⇒
`isError:true, "A2A task <id> <state>[: <status.message text>]"`; timeout ⇒
`isError:true, "A2A task <id> timed out after <ms>ms (state=<state>)"`; `ctx` abort ⇒ stop before the
next `GetTask`, `"A2A task <id> canceled"`. `metadata` on every result = `{agent, taskId, state, polls, ms}`.
Reuses httpTool's `${ENV}` header expansion + timeout + non-2xx mapping. Method names are the literal
strings `SendMessage`/`GetTask`.

---

## 7B. A2A agents — inbound (`serve`)

Expose a toolkit as an A2A agent so real A2A peers can call it. `serve` is generic (protocol-neutral);
**A2A is an opt-in profile** via the `a2a` option (or a top-level `a2a` config block — a reserved key,
§2). No streaming / push / auth in core.

```
toolkit.serve(addr, { client, a2a?, onTask? }) -> ServeHandle { url, stop() }   // close() aliases stop()
A2AConfig = { name?, description?, version?, provider?:{organization,url}, skills?: string[], store? }
```
When `a2a` is absent, no A2A routes mount (every request 404s). When present, mount:

- **`GET /.well-known/agent-card.json`** → an Agent Card:
  ```json
  { "name":"...", "description":"", "version":"0.1.0", "protocolVersion":"0.3.0",
    "capabilities":{"streaming":false,"pushNotifications":false},
    "defaultInputModes":["text"], "defaultOutputModes":["text"],
    "skills":[{"id":"<skill>","name":"<skill>","description":"<SKILL.md desc>"}], "url":"<base>/" }
  ```
  `skills[]` come from the toolkit's SkillSource (`id=name=`SKILL.md name), filtered to `a2a.skills`
  if given — **never raw tools**. `provider` included only when configured. Defaults when omitted:
  `name:"toolnexus-agent"`, `description:""`, `version:"0.1.0"`, `protocolVersion:"0.3.0"`,
  `capabilities.streaming:false`. `url` = base + `/` (the JSON-RPC POST endpoint).
- **`POST /`** JSON-RPC 2.0 (`{jsonrpc:"2.0", id, method, params}`):
  - **`SendMessage`** (`params.message.parts[].text` = the task) → create Task `{id:<uuid>,
    status:{state:"submitted"}}`, `save` to the store, **return it immediately**, and run fulfilment
    **async**: save `working` → `client.run(<task text>, {toolkit})` → on success save `completed` with
    `artifacts:[{artifactId:<uuid>, parts:[{kind:"text", text: runResult.text}]}]`; on throw save
    `failed` with `status.message={role:"agent", parts:[{kind:"text", text:<error>}]}`. A fulfilment
    error never crashes the server.
  - **`GetTask`** (`params.id`) → the current Task; unknown id → JSON-RPC `error {code:-32001}`.
  - Unknown method → `-32601`; parse error → `-32700`.
- **`onTask({ id, skill?, task, result?, state })`** fires on a terminal state with the `RunResult`
  telemetry (tokens, tool count, turns).

**TaskStore** (pluggable persistence): `get(id) -> Task|undefined`, `save(task)`. `resolveStore(store?)`:
`undefined`|`"memory"` ⇒ in-memory (default); `"file:<dir>"` ⇒ file store (one `<id>.json` per task);
an object ⇒ used as-is. All Task reads/writes go through the store.

---

## 7C. MCP server — inbound (`serve` MCP profile)

Expose a toolkit **as an MCP server** so any MCP client (Claude Desktop, an IDE, another agent) can
call its tools — the inbound mirror of §7B. Where A2A advertises **skills** and fulfils a Task through
the whole client loop, MCP advertises the toolkit's **unified tools** (every source — mcp · skill ·
native · http · builtin · a2a) and dispatches each `tools/call` straight to `Tool.execute`. The calling
MCP client *is* the LLM host, so there is **no `client`, no Task, and no TaskStore**. This turns
toolnexus into a **universal MCP gateway**: aggregate N servers + skills + your own tools behind one
toolkit, then re-expose the union as one MCP server. **Opt-in** via the `mcp` profile (or a top-level
`mcpServer` config block — a reserved key, §2, singular, distinct from the client-side `mcpServers`).
Built on each port's existing MCP SDK **in server mode** (no new dependency). Transport is
**streamable-HTTP** (a networked MCP server); stdio is intentionally out of scope.

```
MCPServeConfig = { name?, version?, tools?: string[] }        // name default "toolnexus", version "0.1.0"
OnCall = ({ name, source, ms, isError }) => void              // fires per inbound tools/call

// streamable-HTTP — co-mounted at POST /mcp on the serve() server, beside the A2A routes:
toolkit.serve(addr, { mcp?, onCall?, ...a2a })  -> ServeHandle { url, stop() }
```

- **`initialize`** advertises `serverInfo:{name, version}` from the profile (defaults `name:"toolnexus"`,
  `version:"0.1.0"`) and `capabilities.tools`.
- **`tools/list`** → one entry per exposed tool: `{ name, description, inputSchema }`, where `name` is
  the toolkit `Tool.name` used **verbatim** (already sanitized at registration, §0.2 — *not*
  re-sanitized, unlike §7A/§7B skill ids) and `inputSchema` is the tool's `parameters` (JSON Schema).
  When `mcp.tools` is set, the list is filtered to exactly those names; **unknown names are ignored**
  (never an error). Omit `mcp.tools` ⇒ all toolkit tools.
- **`tools/call`** (`{ name, arguments }`) → `Tool.execute(arguments, ctx)`; map the `ToolResult` to a
  `CallToolResult`: `output` → a single `{type:"text", text}` content part, `isError` propagates. An
  `execute` throw becomes `isError:true` with the error text — **never crashes the server**. An unknown
  tool name → the SDK's standard error (`InvalidParams`/`-32602`). `metadata` is not on the MCP wire
  (surfaced via `onCall` only). `ctx` carries the request's cancellation signal.
- **`mcp` absent** ⇒ no MCP surface (`serve` mounts no `/mcp`; an MCP-only `serve` 404s all other paths).
- **Transport**: streamable-HTTP via the `/mcp` endpoint on `serve(addr, …)` (stateless — a fresh
  server+transport per request). Hermetic tests connect the port's own MCP **client** to the served
  toolkit (in-memory/linked transport where the SDK offers one, else an ephemeral HTTP port) and assert
  `tools/list`/`tools/call` round-trips.

Deferred (forward-compatible): a **stdio** transport (for local clients like Claude Desktop), MCP
resources / prompts / sampling / completion (skills already reach clients via the `skill` tool), auth
in core, and the Go CLI `serve --mcp` subcommand.

---

## 7D. Sub-agents & agent runtime (source: "agent")

One axiom completes §7A/§7B's symmetry **locally**: **an Agent is a Tool** — (system
prompt × a filtered toolkit view × the §8 loop), invocable as `{ name, description:
does, inputSchema:{prompt}, execute: run its loop, return ONLY its final text +
metadata {agent, turns, totalTokens} }`. The classic API is untouched; everything here
is additive. Reference evidence: six-port spike, 276/276 transition-trace checks
(`docs/references/agent-fundamentals-audit-2026-07-17.md`).

### Handle state machine (SPEC pins transitions, never scheduling)

```
states:  idle → running → (idle | suspended | closed)
         suspended → running  ONLY via the Answer to its pending Request
Handle = { id, def, state, inbox, budget, children }   // inbox = AGENT STATE, never a runtime/language mailbox
```
Ids are deterministic and parent-scoped (`root/coordinator.1/explore.2`) — never random.
Scheduling, thread placement, and concurrency level are unobservable; conformance =
identical per-handle transition traces against the shared `examples/subagent-*` fixtures
on a **virtual clock**.

### Six host verbs (+ read-only `list`/`inspect` views)

| verb | contract |
|---|---|
| `spawn(parent, def, budget?)` | new child handle; deterministic id; budget carve `min(own, parent remaining)`; caps (`maxChildren`/`maxDepth`) checked here; handle returned to the spawner ALONE (handles are capabilities: post/wake what you hold, wait only what you spawned) |
| `post(h, item)` | append to inbox; NO transition; bounded inbox — at cap, reject synchronously to the sender (loud, never silent); after close ⇒ error result |
| `wake(h, prompt?)` | `idle→running`; turn input = drain(inbox); **admission is atomic with the verb** (check + slot take + state flip as one step); over `maxConcurrent` per parent ⇒ wake queues FIFO, a completed sibling **transfers** its slot |
| `wait(h, timeout?)` | resolves with the **next result or the last result** (a settled handle answers immediately; registration order is unobservable); timeout ⇒ explicit timeout error, child keeps running |
| `interrupt(h)` | abort the in-flight Run (per-port cancellation contract, table below) → `idle`, **drained inbox items restored** (drain is transactional — consumed only by a completed turn); on a `suspended` handle ⇒ cancel the pending Request → `idle`. Never a kill |
| `close(h, {force?})` | graceful: stop accepting → close children **leaf-first** → running turn may finish bounded by `shutdownMs` (then escalate to interrupt) → `onClose(reason)` → final state kept queryable (close ≠ loss; a successor may spawn from the checkpoint). Stop-all = `close(root)` |

**Call discipline** (or truly-concurrent ports deadlock): handle→handle *blocking* calls
flow strictly **rootward**; parent→child interaction is non-blocking; downward traversal
(close cascade, list, resume) runs from outside the tree.

### Two delivery rails

- **Solicited** — a tool call's own result returns into the live turn (sync `task` rides this).
- **Unsolicited** — posts, timer ticks, background results, inbound events land in the
  inbox and enter ONLY as a fresh turn at idle. One wake drains the WHOLE inbox as one
  coalesced context block (timer ticks dedupe to one counted entry); every item renders
  with provenance `{from: handle-path|"external", channel}`, and non-ancestor/external
  senders render inside an explicitly untrusted block.

### Backpressure — three gates, always loud

1. **Inbox gate**: bounded; full ⇒ synchronous reject to the sender.
2. **Concurrency gate**: `maxConcurrent` running children per parent; atomic admission; queued wakes FIFO via slot transfer.
3. **Turn gate**: global `maxConcurrentTurns` wraps **ONLY the LLM HTTP call** — never a
   whole Run (holding it across a Run deadlocks parent against child) — and **releases on
   acquirer death**, never only via the acquirer's cleanup path.

### Budgets (hierarchical, live-enforced)

`Budget { maxTurns, maxTokens, maxToolCalls, maxWallMs, maxChildren, maxConcurrent, maxDepth }`.
Carve at spawn + **live ancestor-chain walk before each turn and spawn** (carve alone
misses sibling spend). Usage roll-up (§7D task, below) is the ledger; between turn
boundaries budget reads are eventually consistent. Money is excluded (vendor data; hosts
convert in `onBudget`). Any limit stop ⇒ `status:"incomplete"` with the limit named —
never silent `"done"`, never a crash; partial work + transcript preserved. Optional
`onBudget(info) → "stop"|"extend"|"suspend"` ("suspend" routes §10 as an approval).

### Model surface: the `task` tool (team tools opt-in, default OFF)

`task { agent:string, prompt:string }` = spawn→wake→wait→close fused. Child runs on a
fresh transcript; parent gains EXACTLY one tool message per call; child usage rolls up
into parent usage. Parallel task calls in one turn run concurrently (§8). The tool's
description advertises ONLY the caller's **team** (sorted by name, composed from each
agent's `does` — the §3 skillsPrompt pattern); out-of-team targets ⇒ error listing team
names. Children get no `task` unless their own def declares a team (recursion opt-in).
The registry = transitive closure of the entry agent's team graph.

### Suspension escalation & durable resume

A suspending child agent presents to its parent EXACTLY as a suspending tool — §10
verbatim, no new pending type. Nearest interpreter wins, strict one-hop: child's
`waitFor` → else child suspends and the parent's task result carries the child's Request
(+ `data.path`, §10) → parent's `waitFor` → … → root returns `status:"pending"`. Parked
levels burn zero tokens. Resume: the Answer routes to the **deepest** suspended handle,
which resumes at its checkpoint (turns/usage grow, never reset); the upward cascade
re-runs each parent, and a re-invoked `task` **REATTACHES to the existing child by task
key** (agent+prompt) — settled ⇒ its recorded result; suspended ⇒ its pending; running ⇒
await — and never spawns a duplicate. Reattachment (not transcript inspection, not a
completion cache) is the required idempotency mechanism.

Three pins the first implementations forced: (1) **rewind-to-checkpoint** — on a durable
pending the runtime restores the handle's PERSISTED transcript to its pre-turn snapshot
(the §10 placeholder-append rule is scoped to bare-client runs; a persisted placeholder
would make the resumed parent skip re-invoking `task`); (2) **two resume shapes** —
inline resume traces `suspended→running` (the Run never ended); durable resume traces
`suspended→idle` (Answer accepted, checkpoint restored) then `idle→running` (the replay
wake); the Answer remains the only exit from `suspended` in both; (3) **result status
vocabulary is closed**: `"done" | "pending" | "incomplete" | "interrupted" | "closed" |
"timeout" | "error"` — identical strings in all six ports.

### Errors: one boundary rule

Mechanical retry/backoff (§8) runs at the level where the failure occurred. A failed
child Run crosses the handle boundary as a uniform `isError` result — **never an
exception** — for the parent's model to judge (reprompt / respawn / reroute / abandon).
Only the root may throw to the host.

### Lifecycle & runtime obligations

`AgentDef` may declare `onSpawn(h)` (once, pre-first-turn — the session-start injection
point) and `onClose(h, reason: closed|interrupted|budget|error)` (pre-final-checkpoint).
The runtime owns: **one ConversationStore for all handles** (conversation id = handle id
— transcripts genuinely survive turns; resume reads real history), an **injectable
clock** (all timers/timeouts/deadlines; fixtures run virtual), the **handle table**
(rebuildable tree), and **name-sorted registry iteration** wherever prose or traces are
composed.

### Cancellation contract (per port)

| port | seam | guarantee |
|---|---|---|
| js | `AbortSignal` on `run/ask` | mid-request abort |
| golang | `context` cancellation (cause owned by the runtime) | mid-request abort |
| csharp | `CancellationToken` (interrupt classified by token state, not exception type) | mid-request abort |
| elixir | Run-process termination (structural; turn-gate slot released by monitor) | mid-request abort |
| python | cooperative cancel | between attempts (transport abort where the stack allows) |
| java | cooperative cancel (interruptible virtual thread) | between attempts / interruptible send |

Only abort **latency** may differ; the observable outcome is identical everywhere:
`idle` + restored inbox + an interrupted error result to waiters.

### Level-1 surface

`agent(name, { does, uses?, soul?|soulFile?, team?, budget?, model? ("inherit"),
waitFor?, onSpawn?, onClose? })` → `.run(prompt)` (one-shot) and `.asTool()` (the bridge
into `extraTools` — the axiom's other direction). `serve(agent)` = §7B unchanged.

---

## 7E. Agent home — personas (source: "agent", persona surface)

The persona archetype over the §7D runtime: an identity that lives in **files**, durable
**memory** the agent can edit, and a **heartbeat** so it can act unprompted. All three ride
shipped seams — `onSpawn` injection, the six verbs, the runtime-wide store — and add no
runtime behavior.

### The directory is the agent

`fromDir(dir)` builds a persona from a folder. These files, in this order, are each injected
(when present) into the agent's `soul` (system prompt) as a `## <filename>` section:

| # | file | role |
|---|------|------|
| 1 | `AGENTS.md` | operating instructions |
| 2 | `SOUL.md` | identity / voice |
| 3 | `IDENTITY.md` | who the agent is |
| 4 | `USER.md` | model of the user |
| 5 | `TOOLS.md` | tool guidance |
| 6 | `HEARTBEAT.md` | what to do on a heartbeat |
| 7 | `MEMORY.md` | durable long-term notes |

Absent files are skipped. Each is read with a **2 MB cap** (larger ⇒ truncated with a
notice, on-disk file untouched). Composition happens **at session start**; the soul is fixed
for the run (frozen snapshot — the cache-stability rule, for free).

### The `memory` builtin (file-backed, opt-in)

A `memory` tool — **not** one of the default §4A builtins — exists only when a home dir is
wired (`fromDir`, or `memoryTool(dir)` added to `uses.tools`). One tool, three actions:

| action | effect |
|--------|--------|
| `add` | append an entry |
| `replace` | swap an existing substring (`with`) |
| `remove` | delete an existing substring |

`target` = `self` (`MEMORY.md`, default) or `user` (`USER.md`). **All actions write to disk.**
A `replace`/`remove` whose substring is absent ⇒ loud `isError`. The tool **does not** mutate
the current session's prompt — persisted memory loads at the **start of the next session**
(the frozen-snapshot rule that keeps a long-lived persona cache-stable; the tool description
states this to the model). Omittable per persona (read-only agents).

### Heartbeat

`startAgent(agent, …, { everyMs })` gives a persona its own clock. Each interval it `post`s a
tick to the agent's **own inbox** (the unsolicited rail — ticks **coalesce**, so a slow beat
can't pile up) and, when idle, `wake`s it with a prompt to read `HEARTBEAT.md` and act, else
reply `HEARTBEAT_OK`. A `HEARTBEAT_OK` reply is **silent** (no report) — silence is the
default, only a substantive reply surfaces to the host's `onBeat`. All timing goes through the
runtime's **injectable clock** (fixtures run on a virtual clock). Inbound **channels are the
host's job**: deliver external events by calling `post`/`wake` — the library owns the
trigger→turn seam, not a scheduler or gateway.

### Composition, not API

Higher patterns are recipes over the above, no new surface: **dream / consolidation** = a
`startAgent` whose `HEARTBEAT.md` says "fold notes into `MEMORY.md` via the memory tool"; a
**channel assistant** = the host's inbound handler calling `post`/`wake`.

---

## 8. Unified LLM client (the host loop)

The payoff: give it a plain base URL + a "style", and it runs the whole tool-calling
agent loop against a `Toolkit`.

```
client = createClient({
  baseUrl,            // "https://openrouter.ai/api/v1" or "https://api.anthropic.com"
  style,              // "openai" | "anthropic"
  model,
  apiKey?,            // from env if omitted (OPENAI_API_KEY / ANTHROPIC_API_KEY / OPENROUTER_API_KEY)
  headers?,           // extra headers
  systemPrompt?,      // prepended to the toolkit's skillsPrompt()
  maxTurns?,          // default 10
})

client.run(prompt, { toolkit }) -> RunResult
```

`RunResult` carries the full outcome + telemetry:
```
RunResult {
  text:          string        // final assistant answer
  messages:      [...]          // full transcript
  toolCalls:     [{ name, args, output, isError, metadata }]   // every call + its result
  toolCallCount: number         // = toolCalls.length
  turns:         number         // LLM round trips
  usage:         { promptTokens, completionTokens, totalTokens }  // summed across turns
  model:         string
}
```
Token usage is summed from each response's `usage` (OpenAI `prompt/completion/total_tokens`;
Anthropic `input_tokens`→prompt, `output_tokens`→completion). `metadata` on each tool call is
the tool result's metadata (e.g. MCP `server`, HTTP `status`).

Loop (identical for both styles, only wire-format differs):
1. system = `systemPrompt + "\n\n" + toolkit.skillsPrompt()`, then the user prompt.
2. Call the endpoint with the toolkit's tool schema for that style
   (`toOpenAI()` for `"openai"`, `toAnthropic()` for `"anthropic"`).
3. If the model returned tool calls: run each via `toolkit.execute(name, args)`, append the
   results in the provider's tool-result shape, go to 2.
4. No tool calls ⇒ return the final assistant text (and the transcript).
5. Stop after `maxTurns`.

- **openai style** → `POST {baseUrl}/chat/completions`, `Authorization: Bearer <key>`,
  tools = `toOpenAI()`, tool results as `{role:"tool", tool_call_id, content}`.
- **anthropic style** → `POST {baseUrl}/messages` (append `/v1` only if not already present),
  headers `x-api-key` + `anthropic-version: 2023-06-01`, tools = `toAnthropic()`, tool
  results as a `user` message with `tool_result` content blocks.

This makes "bring any OpenAI- or Anthropic-style endpoint + point at mcp.json/skills =
working agent" a few lines in any of the three languages.

### Hooks (lifecycle middleware)

`ClientOptions.hooks` — four optional, async-capable callbacks around the loop. Each may
observe; the noted ones may mutate or short-circuit.

- `beforeLLM({ messages, tools, model, turn })` → optionally return `{ messages?, tools? }`
  to replace them (trim/inject history, swap tools).
- `afterLLM({ response, model, turn })` → observe (logging, cost, tracing). `response` is
  the raw provider payload (carries `usage`).
- `beforeTool({ name, args, id, turn })` → return `{ result }` to **short-circuit** the tool
  (deny / cache hit / dry-run — the real tool never runs), or `{ args }` to rewrite the call.
- `afterTool({ name, args, result, id, turn })` → return `{ result }` to transform the output
  (redact, annotate).

These are what make it programmable as an agent: permissions/guardrails (deny in `beforeTool`),
caching, arg rewriting, and observability (cost/latency in `afterLLM`).

### Streaming

`client.stream(prompt, { toolkit, id? })` returns an async iterator of events (same loop, hooks,
tools, telemetry as `run()` — just incremental). Like `ask`, an optional **`id`** makes it stateful:
the thread's transcript is loaded as history before the stream, and saved back to the
`ConversationStore` when the `done` event fires (§ Conversation memory). No `id` ⇒ stateless.

- `{ type: "text", delta }` — assistant text token deltas
- `{ type: "tool_call", id, name, args }` — a tool about to run
- `{ type: "tool_result", id, name, output, isError }` — after it ran
- `{ type: "usage", usage }` — token usage
- `{ type: "done", result }` — the final `RunResult`

OpenAI: `stream:true` + `stream_options.include_usage`, SSE deltas (assemble `tool_calls` by
index). Anthropic: `stream:true`, SSE `content_block_*` / `message_delta` events. Each language
exposes the idiomatic stream type (JS async generator, Python async generator, Go channel, Java
a callback/Stream).

### Resilience (retries + timeout/cancel)

`ClientOptions`: `retries` (default 2), `retryBaseMs` (default 500), `timeoutMs` (whole-run
deadline, optional). The LLM request retries on `429`/`500`/`502`/`503`/`504` and network errors
with exponential backoff + jitter, honoring `Retry-After`. A run-level abort signal is built from
`timeoutMs` + an external cancel token (`run(prompt, { toolkit, signal })`) and threaded into the
HTTP request, so a timeout or external cancel aborts the in-flight call. Aborts are not retried.

**`onError` — host-classified retry-vs-fail (optional).** The retry-vs-fail decision for each
failed LLM attempt is host-configurable via `onError(info) -> "retry" | "fail"` (idiomatic name +
return per port). `info = { error?, status?, attempt, retryable }` — `status` on a non-ok HTTP
response, `error` on a transport/network throw, `attempt` zero-based, `retryable` = whether the
status/error is in the default set (`429`/`5xx`/network). A `"retry"` is always **bounded by
`retries`** (the classifier cannot loop unbounded); `"fail"` surfaces the error immediately,
skipping remaining retries. **Absent `onError` ⇒ the default classifier `retryable ? "retry" :
"fail"`, i.e. byte-identical to the paragraph above.** There is **no `"suspend"` tier** — a failure
is not a user action, so it never becomes a §10 `Pending`; suspension stays a user-action pause
(§10). A `"fail"` result never carries `status:"pending"`. Aborts (timeout/cancel) bypass `onError`
and are never retried.

**Cancellation** beyond `timeoutMs`/abort: the per-port cancellation contract used by the
agent runtime's `interrupt` is tabled in §7D — ports differ only in abort latency, never in
observable outcome.

### Conversation memory

`run(prompt, { toolkit, history })` accepts a prior transcript and continues it; `RunResult.messages`
is the full updated transcript to pass back next turn (the low-level, stateless primitive).
`client.conversation({ toolkit })` wraps this as a stateful object: `convo.send(prompt)` retains
history across calls, `convo.messages` is the transcript, `convo.reset()` clears it.

**`ask` + `ConversationStore` (the durable path).** The client takes a conversation **provider** at
construction — `createClient({ ..., store })`, defaulting to an **in-memory store** (per-client,
process lifetime). A `ConversationStore` is two methods: `get(id) -> messages | undefined` and
`save(id, messages)` — implement it for a file/db/redis provider to persist across processes.

`client.ask(prompt, { toolkit, id?, on_text? })`:
- **with `id`** — loads that id's transcript from the store, `run`s the loop with it as `history`,
  `save`s the updated `RunResult.messages` back under `id`, and returns the `RunResult`. So the next
  `ask` with the same `id` continues the conversation. Works identically for `openai` and `anthropic`.
- **without `id`** — a stateless one-shot, identical to `run` (the store is untouched).
- **with `on_text`** — a callback invoked with each assistant text delta as it streams; `ask` still
  returns the final `RunResult` (the return type never changes). Omit it ⇒ non-streaming. This is the
  block-style streaming path; `stream()` remains the idiomatic iterator for consuming tool events too.

**A2A serve uses the same store.** The inbound `serve` (§7B) fulfils each `SendMessage` via
`ask(text, { toolkit, id: <message.contextId> })` when the message carries an A2A `contextId`, so a
peer's turns are remembered through the client's `ConversationStore`; no `contextId` ⇒ stateless `run`.

Each language exposes the idiomatic equivalent (a `ConversationStore` interface + an in-memory default
+ `ask`).

### Observability (metrics)

Zero-dependency, two outputs from one internal instrumentation. Both are opt-in and add no cost when
unused.

**`on_metric` — semantic events (forward anywhere).** The `on_metric` client option (idiomatic name
per port, e.g. `onMetric` in JS/Java, `on_metric` in Python) receives a readable record at each
significant point — NOT counter/histogram primitives. Field **names follow each port's idiomatic
casing** (exactly like `RunResult` — `toolCalls`/`tool_calls`), so these are NOT byte-identical across
ports; only the Prometheus text below is:
- `event: "llm"` + `model`, `status` (`ok`/`error`), `ms`, prompt-tokens, completion-tokens — one per LLM call
- `event: "tool"` + `tool`, `source`, is-error, `ms` — one per tool call
- `event: "run"` + `model`, `turns`, tool-calls, total-tokens, `ms`, `error?` — one per `run`/`ask`
Forward these to statsd, logs, OTel, your own exporter — the library holds no opinion.

**`client.metrics()` — built-in Prometheus text (scrape).** The client accumulates those same events
into a tiny in-memory registry and renders the **Prometheus text exposition format** (no third-party
dependency — the format is plain text). The host mounts it at `GET /metrics`. Metrics + labels
(bounded cardinality — no `id`):
- `toolnexus_llm_requests_total{model,status}` · `toolnexus_llm_tokens_total{type}` ·
  `toolnexus_llm_request_duration_seconds{model}` (histogram) ·
  `toolnexus_tool_calls_total{tool,source,is_error}` · `toolnexus_tool_duration_seconds{tool}`
  (histogram) · `toolnexus_run_errors_total{model}`

The `metrics()` **text is byte-identical across all ports** — pin: metrics in the fixed order
above; each with `# HELP`/`# TYPE` lines; series within a metric sorted lexicographically by their
rendered label string; histogram buckets (seconds) fixed at
`[0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60]` + `+Inf` (cumulative `_bucket{le=…}` + `_sum` +
`_count`, observing `ms/1000`, `le` rendered `0.05`/`0.1`/…/`60`/`+Inf`); label values escaped
`\`→`\\`, `"`→`\"`, newline→`\n`; output ends with a trailing newline; before any activity, only the
`# HELP`/`# TYPE` lines render. Label names stay snake_case (`is_error`, `type`) per Prometheus
convention in every port.

OTLP/OpenTelemetry push is intentionally a **future opt-in companion** (heavy SDK per language), not
core. Each port exposes the idiomatic `on_metric` callback + a `metrics()` returning the Prometheus text.

### Right-size routing (model faithfulness + the route-gate seam)

toolnexus is the substrate the fleet routes work *through*: the routing **policy** (which model
for which job class) lives with the operator, and toolnexus guarantees the route is executed
faithfully and can be gated. This follows the model-cascade / routing literature — **FrugalGPT**
(Chen, Zaharia, Zou, arXiv:2305.05176: route each job to the cheapest model that clears a quality
bar; up to ~98% cost reduction at matched quality) and **RouteLLM** (Ong et al., arXiv:2406.18665:
learned strong/weak routing riding the cost–quality Pareto frontier). toolnexus + the fleet
registry implement the **deterministic, per-job-class** point on that frontier (static
operator-chosen tiers), not a learned per-query router — auditable and reproducible, consistent
with the governance ethos (§8 hooks, and `add-governed-execution-layer`).

Two properties are contractual and conformance-tested:

- **Model faithfulness.** `Client.run` (and `toolnexus run --model`) transmits the caller's
  configured `model` id to the endpoint **verbatim** on every turn — no rewrite, alias, or silent
  default when the caller supplied one. A route the operator chose as cheap
  (e.g. `deepseek/deepseek-chat`) is the model actually billed. Identical in all ports (the
  client loop sends `model` unchanged); pinned by `golang/routing_conformance_test.go`.
- **The route-gate seam.** The `beforeLLM` hook (§8) receives the `model` for each turn and may
  abort the call (raise/error). This is where an expensive-tier route is gated: the reference
  pattern shells out to `brain check "<why this model>" --json` and aborts unless the shield
  returns `guaranteed`. toolnexus does **not** hard-depend on `brain`; the adapter is
  host-supplied. Hard veto at the *tool* layer is `add-governed-execution-layer`; the two compose
  (route-gate at the model call, tool-veto at `execute()`).

**Routing-tier registry contract (fleet interop).** `fleet-nexus` / `huddle-nexus` drive toolnexus
from a runtime registry (`~/.config/deemwar-one-os/openrouter.json`) with this shape — the
contract between the fleet skills and toolnexus, which toolnexus itself never reads:

```jsonc
{
  "base_url": "https://openrouter.ai/api/v1",
  "style": "openai",
  "api_key_env": "OPENROUTER_API_KEY",   // the NAME of the env var, never the value
  "models": {
    "classify_verify": "deepseek/deepseek-chat",  // cheap tier: verify/classify
    "route":           "deepseek/deepseek-chat",
    "aux_default":     "deepseek/deepseek-chat",
    "huddle_voice":    "deepseek/deepseek-chat",
    "fleet_agentic":   "moonshotai/kimi-k2"        // strong tier: agentic work
  }
}
```

A fleet skill maps `base_url`→`baseUrl`, `style`→`style`, `models.<tier>`→`model` onto
`ClientOptions` and reads the key from the env var named by `api_key_env` at call time. Per the
secrets rule, toolnexus reads that key from the environment and never logs it.

### Host-facing extensions (request shaping, empty-tools omission, store accessor)

Additive; grounded in `docs/adr/0001-rag-go-consumer-needs.md`. Neither option set ⇒ body
byte-identical to today.

- **Gap 1 — request shaping.** `RequestParams` (map) is shallow-merged into **every** LLM body after
  the client builds its own keys — a `RequestParams` key **wins** on collision (e.g. `max_tokens`
  overrides the anthropic default 4096). The keys `messages`/`tools`/`stream` are forbidden there
  (stripped + warned). `BodyTransform` receives the fully assembled body (after the merge) just before
  marshal and returns the body to send — the escape hatch for provider-specific rewriting (including
  message rewriting) without a proxy. Ordering: base body → `BeforeLLM` hook → `RequestParams` merge →
  `BodyTransform` → marshal → wire. Applied on all four paths (run/stream × openai/anthropic).
- **Gap 2 — injectable HTTP transport.** The host may supply the HTTP client/transport for LLM
  requests (retries included); nil ⇒ the default. Scope is the LLM path only.
- **Gap 5 — omit empty tool keys.** When the effective tool list is empty (including after a
  `BeforeLLM` override), the `tools` key — and, on the openai style, `tool_choice` — is omitted from
  the body entirely. This is an all-port behavior change (the keys were previously sent unconditionally).
- **Gap 4 — conversation-store accessor.** `Client.ConversationStore()` returns the client's store
  (the instance passed in, else the default in-memory one) so a host can read/rewind the transcript
  and share state with the stateful `ask` — no shadow copy needed.

---

## 9. Go CLI (`toolnexus`)

A single binary that wraps the library into a continuous interactive agent loop —
opencode's run loop, distilled.

```
toolnexus run \
  --config   mcp.json \         # MCP servers (optional)
  --skills   ./skills \         # skills folder (optional, repeatable)
  --base-url https://openrouter.ai/api/v1 \
  --style    openai \           # openai | anthropic
  --model    openai/gpt-4o-mini \
  --system   "You are a helpful agent." \
  [--once "do X and exit"]      # single-shot instead of REPL
```

- API key from `--api-key` or env (`OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`).
- Builds a `Toolkit` from `--config` + `--skills`, a unified client, then loops:
  read a line from stdin → `client.Run(line, toolkit)` → print the answer → repeat.
- `toolnexus tools --config ... --skills ...` lists the resolved tools and exits.
  `toolnexus --help` documents flags.
- Ctrl-D / `exit` quits; the toolkit is closed (MCP servers torn down) on exit.

---

## 10. Suspension — `Pending` / `waitFor`

Some tools can't finish in one shot: they need an **out-of-band, asynchronous
resolution** — a human logs in, approves a trade, uploads a file, replies "YES".
This is one idea, not one feature: *login* is just the `kind:"authorization"` case.
There is **no auth subsystem** — there is a suspend/resume primitive, and auth is
data on top of it.

The whole thing in one sentence:

> A tool may return **`Pending(request)`** instead of an answer. The client calls the
> host's **`waitFor(request) → answer`**, then **retries the tool** and resumes.

Everything crossing a boundary is **data** (`request`, `answer`) — so this works
identically across the ports, across processes, across agents (A2A), and across
restarts. Only the host's `waitFor` is behavior, and it is not the engine's business:
it may open a browser, message a channel, watch a file, or forward to another agent.

### `Pending` (rides on `ToolResult` — no signature change)

`execute` still returns a `ToolResult`. A result is a **suspension** iff its
`metadata.pending` is present and is a `Request`:

```
ToolResult {
  output:   string             // human-readable fallback ("Login required: <url>")
  isError:  true               // it did not produce a usable answer
  metadata: { pending: Request }
}
```

Keeping it on `metadata` (not a new return type) is deliberate: the uniform
`execute(args,ctx) -> ToolResult` contract (§0.1) is untouched, so no port changes a
signature. A convenience producer helper MAY exist —
`authRequired(url, prompt?)` → a `ToolResult` with `metadata.pending =
{ kind:"authorization", url, prompt }` and a generated `id` — but it is sugar, not
required.

### `Request` — byte-identical wire data

```
Request {
  id:         string           // unique per suspension; the correlation key
  kind:       string           // "authorization" | "approval" | "input" | ...  (open vocabulary)
  prompt:     string           // what is being asked, in human words
  url:        string?          // present when the action happens at a link
  data:       object?          // kind-specific extra (e.g. choices, or data.schema — see below)
  expiresAt:  string?          // RFC3339; the request is stale after this
}
```

`data.schema` (optional, **R2**) MAY carry a restricted JSON-Schema (flat primitive properties only —
string/number/boolean/enum, the MCP `requestedSchema` shape) describing the expected `answer.data`, so
a generic host/renderer can render and validate input without bespoke glue. Its absence is fully
backward-compatible; carrying the schema under `data` keeps the `Request` shape unchanged.

### `Answer` — byte-identical wire data

```
Answer {
  id:      string              // echoes Request.id
  ok:      boolean             // satisfied, vs declined / aborted / expired
  data:    object?             // kind-specific payload (e.g. the value entered)
  reason:  string?             // when ok==false: "declined" | "cancelled" | "expired"  (R1, advisory)
}
```

`reason` (optional, **R1**) is populated only when `ok == false`, distinguishing an explicit refusal
from a dismissal/timeout. **The loop rule branches only on `ok`** — `reason` is advisory. The MCP
elicitation bridge (§2) uses it to map `ok==false` onto MCP's `decline` (when `reason=="declined"`)
vs `cancel` (otherwise).

`Request`/`Answer` keys are **fixed across all ports** (they serialize over the wire
and cross agent boundaries) — pinned exactly as above, *not* idiomatic-cased like
`RunResult`.

**The `authorization` kind = OAuth2 / OIDC.** By convention `kind:"authorization"` follows
OAuth2 / OpenID-Connect authorization-code semantics: `url` is the authorize endpoint; the
host's `waitFor` performs the redirect → consent → callback (and any token exchange) **out-of-band**;
`answer.ok` marks success and `answer.data` MAY carry the resulting token/claims. **The kernel stays
OIDC-agnostic** — no OIDC library, no token logic in core; OIDC lives entirely inside the host's
`waitFor` at the edge. This is the canonical instance the mechanism exists for (login), but it is
*just data* — the same `Pending`/`waitFor` serves `approval`, `input`, and any other `kind`.

**The `question` kind = ask the human.** The built-in `question` tool (§4A) is the canonical
`kind:"question"` producer: it suspends with `data.questions` (the structured questions) and a
rendered `prompt`, and on resolution returns `answer.data` verbatim — the resolution *is* the
answer, exactly as with `kind:"input"`. A host `waitFor` MAY populate `answer.data` however it
likes; a **recommended, non-normative** shape is `{ answers: [ ... ] }` (one entry per question, in
order), but the tool forwards whatever it receives without interpreting it. **Trust boundary
(normative):** `kind:"question"` and `kind:"input"` MUST NOT be used to collect credentials —
passwords, API keys, tokens, or payment secrets. Those go through `kind:"authorization"` with a
`url`, so the secret is entered out-of-band and never transits the tool's `data`/`answer` channel
or the model's context. (This mirrors MCP elicitation's form-vs-URL security split.)

**When suspension crosses A2A (`serve()`, §7B).** A run that halts with `status:"pending"` and is
being fulfilled as an inbound A2A task MUST surface the protocol's `input-required` state (carrying
`pending.prompt` in the task's status message) — never a `completed` task. Reporting `completed`
would pass the unanswered prompt off as the result and strand the suspension.

### `waitFor` — the one host slot

A client option (`waitFor` in JS/Python, `WaitFor` in Go/Java/C#):

```
waitFor(request: Request) -> Answer      // idiomatic async per port
```

- Signature is **data → data**. Its interior is unconstrained (open browser + poll;
  send link to a channel + poll; write a file + watch; forward over A2A).
- Async flavor is idiomatic per port (JS `Promise`, Go blocking + `ctx`, Python
  coroutine, Java `CompletableFuture`, C# `Task`) — same rule as streaming (§8).
- Named `waitFor`, **never `await`** — `await` is a reserved word in JS/Python/C#.

### Loop rule (the one behavioral pin)

When a tool call returns a suspension (`metadata.pending` = `request`):

1. **`waitFor` configured** → call `answer = waitFor(request)`.
   - `answer.ok == true` → **re-execute the same tool with the same args once**, passing
     the `answer` in `Context.answer`; feed that result back to the model. A tool uses
     `ctx.answer.data` when the resolution *is* the payload (`kind:"input"`) and ignores
     it when the world changed out-of-band (`kind:"authorization"` — the session is now
     valid). If the retry still suspends, feed back an error `ToolResult`
     (`"unresolved: <prompt>"`) — never loop forever on the same request.
   - `answer.ok == false` → feed back an error `ToolResult`
     (`"declined/expired: <prompt>"`). The loop continues; the model decides what to do.
2. **`waitFor` not configured** → the run does **not** hang. It halts and returns a
   `RunResult` with `status:"pending"` and the `request`, so a durable host can deliver
   it out-of-band and resume by calling `run` again later (possibly in another process).

`RunResult` therefore gains:

```
RunResult {
  ...                          // as §8
  status:   "done" | "pending" | "incomplete"
  limit?:   string             // set ONLY when status="incomplete": which limit stopped it ("maxTurns", …)
                               // "pending" ⇒ a tool suspended and no waitFor was set
                               // "incomplete" ⇒ a §7D limit stopped the run (loud, named in `limit`)
  pending:  Request?           // present iff status == "pending"
}
```

**A suspension is never a tool error.** A `Pending` result is a distinct state, not a failure: the
`tool` observability event for a suspended call carries `isError:false` and a `pending:true` marker
(so error-rate metrics and circuit-breakers do not count it), and the `afterTool` hook's failure
path does not run on the suspension — only on the resolved result. This holds whether the suspension
comes from the tool's own execution or from a `beforeTool` hook short-circuit (a guard-raised
pending).

**Concurrent suspensions surface deterministically.** When more than one tool call in a turn
suspends and no `waitFor` is set, the run halts on the **first** suspension in tool-call order (not a
scheduling-dependent one) and does not record the other suspensions' placeholder results in the
transcript — those calls re-suspend on resume. With a `waitFor`, each concurrent suspension resolves
independently inline and none is lost.

### Streaming event

`stream()` (§8) emits one more event when a tool suspends, before `waitFor` runs — so a
channel handler can push the link in real time:

- `{ type: "pending", request }` — a tool is waiting on an out-of-band resolution

### Why in-process *and* durable both fall out of one contract

- **In-process** host: provide `waitFor` (block, poll, retry). Simplest; state lives on
  the live process. Rule 1.
- **Durable** host: omit `waitFor`; take the `status:"pending"` `RunResult`, persist the
  `request`, deliver it however (file, HTTP, channel, another agent), and later call
  `run` again once the world has changed. Rule 2. Because `request`/`answer` are plain
  serializable data, this survives restarts and lets a *different* process/agent resolve
  it. No extra core machinery.

### Agent escalation addendum (§7D)

- **`Request.data.path`** — when a suspension propagates across an agent boundary, the
  suspended handle's deterministic id path rides `data.path` (array of segments; each
  relaying level stamps its own parent-prefixed path, so the value identifies the
  suspended SUBTREE — Answer routing is defined as deepest-suspended within it) — the
  ONE portable location (closed Request shapes in Go/Java/C#/Elixir forbid grafted
  fields). Each relaying level stamps its own path; parent-prefixed ids make the deepest
  path identify the suspended leaf's subtree, which is how the runtime routes the Answer.
- **Halted-tool transcript rule (verified in all six shipped clients, 2026-07-18)**: on a
  durable halt every BARE-CLIENT run APPENDS the first halted tool's placeholder result (`role:
  "tool"`, content = the pending output) to the transcript, then returns `pending`; later
  concurrent suspensions' placeholders never enter (they re-suspend on resume, §10
  first-in-order). Resume therefore re-invokes the halted tool via retry-with-answer —
  which is why §7D's task REATTACHMENT (not transcript inspection) is the idempotency
  mechanism for delegated work.
- The elicitation bridge (§2) and agent escalation produce byte-identical Request wire
  shapes (modulo `data.path` presence) and resolve through the same single `waitFor` slot
  — there is no second suspension mechanism.
