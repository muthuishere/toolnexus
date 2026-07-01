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

This contract is identical across the `js/`, `python/`, `golang/`, and `java/`
implementations. Each builds on the most popular MCP SDK for that language:

| Lang   | MCP SDK                                  |
|--------|------------------------------------------|
| JS/TS  | `@modelcontextprotocol/sdk` (same as opencode) |
| Python | `mcp` (modelcontextprotocol/python-sdk)  |
| Go     | `github.com/mark3labs/mcp-go`            |
| Java   | `io.modelcontextprotocol.sdk:mcp` (official)   |

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
}
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
**`agents`** (§7A, outbound), and **`a2a`** (§7B, inbound card config) — they are sibling config
sections, not MCP servers. (Without this, a config file that carries only a `builtins` toggle, an
`agents` block, or an `a2a` card block would produce a bogus MCP server.)

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

---

## 3. Skill source

### Discovery (mirrors opencode `skill/index.ts`)

- Recursively glob `**/SKILL.md` under the given skills dir(s). Discovery **follows symlinked
  directories and symlinked `SKILL.md` files** (matching opencode's `symlink: true` glob), so a
  skills root that symlinks to out-of-tree skills still discovers them. Symlink cycles are guarded by
  tracking resolved real paths already visited. The sibling-file sampler (§ the `skill` tool) follows
  symlinks the same way.
- Parse YAML frontmatter; require `name` (string), optional `description`
  (string). Body after frontmatter = `content`.
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
across all four ports) telling the model to load a skill via the `skill` tool, then the list:

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
built-ins, ported with **identical tool names and input schemas** across all four ports. Every tool
here has `source:"builtin"` and obeys the uniform `Tool`/`ToolResult` contract (§1): a failure is a
`ToolResult{isError:true, output:<message>}`, never a thrown exception across the boundary. Paths are
resolved relative to the process working directory unless absolute. Implementations are **native per
runtime** (child-process / fs / HTTP of each language) — same behavior, idiomatic shape.

The ten tools (`skill` is its own source, §3, and is not part of this set):

| name | inputSchema (required unless `?`) | behavior |
|------|-----------------------------------|----------|
| `bash` | `command:string`, `workdir?:string`, `timeout?:number(ms,default 60000)`, `description?:string` | Run one shell command via the runtime's process API in `workdir` (default cwd). Output = combined stdout+stderr. Non-zero exit ⇒ `isError:true`, output includes the exit code. Timeout kills the child ⇒ `isError:true`. |
| `read` | `path:string`, `offset?:number(1-based line)`, `limit?:number(lines)` | Read a UTF-8 text file. With `offset`/`limit`, return that line window. Missing file ⇒ `isError:true`. |
| `write` | `path:string`, `content:string` | Write `content` to `path` (create/overwrite), creating parent dirs. Output = confirmation w/ byte count. |
| `edit` | `path:string`, `oldString:string`, `newString:string`, `replaceAll?:boolean` | Exact-string replace in `path`. Default replaces the single occurrence; `oldString` absent OR (without `replaceAll`) non-unique ⇒ `isError:true`. `replaceAll:true` replaces all. |
| `grep` | `pattern:string(regex)`, `path?:string(dir,default cwd)`, `include?:string(glob)`, `limit?:number` | Search file contents by regex under `path`, optionally filtered by `include` glob. Output = `file:line:text` matches, capped at `limit` (default 100). |
| `glob` | `pattern:string`, `path?:string(dir,default cwd)`, `limit?:number` | List files matching the glob under `path`. Output = newline-joined relative paths, capped at `limit` (default 100). |
| `webfetch` | `url:string`, `format?:"text"\|"markdown"\|"html"(default markdown)`, `timeout?:number(s,default 30)` | HTTP GET `url`; return body as text/markdown/html. Non-2xx ⇒ `isError:true` w/ `HTTP <status>`. |
| `question` | `questions:array` (each `{ question:string, header?:string, options?:string[], multiple?:boolean }`) | Non-interactive in the headless loop: returns `ToolResult{isError:false, output:<JSON of questions>, metadata:{questions}}` for the host to answer. |
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
  returns a string or `ToolResult`. (A `@tool` decorator is optional sugar.)
- **Python** — a `@tool` decorator on a function; the input schema is **inferred**
  from type hints + the docstring (first line = description). Explicit
  `@tool(name=..., description=..., input_schema=...)` overrides inference.
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

`client.stream(prompt, { toolkit })` returns an async iterator of events (same loop, hooks,
tools, telemetry as `run()` — just incremental):

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

### Conversation memory

`run(prompt, { toolkit, history })` accepts a prior transcript and continues it; `RunResult.messages`
is the full updated transcript to pass back next turn. `client.conversation({ toolkit })` wraps this
as a stateful object: `convo.send(prompt)` retains history across calls (system prompt added once on
the first turn), `convo.messages` is the transcript, `convo.reset()` clears it. Each language exposes
the idiomatic equivalent (a Conversation/Session object).

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
