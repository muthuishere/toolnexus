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
   `skillsPrompt()` = `## Available Skills\n- **name**: description` (sorted, described only).
7. **Adapters** (schema only): OpenAI `{type:"function",function:{name,description,parameters}}` ·
   Anthropic `{name,description,input_schema}` · Gemini `[{functionDeclarations:[{name,description,parameters}]}]`.
8. **native** tool (`source:"native"`): fn→Tool; string return⇒output, throw/err⇒isError.
9. **http** tool (`source:"http"`): `{ph}` URL substitution, `${ENV}` header expansion (never logged),
   non-2xx⇒`HTTP <status>: <body>` isError, else body text.
10. **Unified client**: `{baseUrl,style:"openai"|"anthropic",model,apiKey?}`; system = systemPrompt+`\n\n`+skillsPrompt();
    loop call→execute tool calls→feed back (provider's tool-result shape)→repeat to maxTurns(10).

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

### Behaviour (mirrors opencode `mcp/index.ts` + `mcp/catalog.ts`)

- **local** → connect over **stdio** (spawn `command[0]` with `command[1:]`, merged
  env = process env + `environment`, in `cwd`).
- **remote** → connect over **streamable-HTTP** (fall back to SSE if needed), with
  optional static `headers`. (OAuth is out of scope for v1 — pass a bearer token
  via `headers`.)
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

- Recursively glob `**/SKILL.md` under the given skills dir(s).
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
the model knows which skills exist (mirrors opencode `Skill.fmt`):

```
## Available Skills
- **name**: description
- ...
```

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
}) -> Toolkit

Toolkit {
  tools()              -> Tool[]                 // mcp tools + skill tool + extras
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

client.run(prompt, { toolkit }) -> { text, messages, toolCalls }
```

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
