# toolnexus

[![npm](https://img.shields.io/npm/v/toolnexus?logo=npm&label=npm)](https://www.npmjs.com/package/toolnexus)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework (no builders, advisors, runnables, config graphs), not a toy
> that falls over the moment you need streaming or a retry. Everything a real agent needs — the
> loop, hooks, streaming, retries, memory — and nothing it doesn't.

The JS/TypeScript port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same
library, byte-identical, also in **Python, Go, Java, C#, Elixir, Clojure and Rust**. Built on
`@modelcontextprotocol/sdk` (the MCP SDK opencode uses).

## Install

```sh
npm install toolnexus
```

## Quick start

Built-in tools are on by default, so an empty toolkit can already act:

```ts
import { createToolkit, createClient } from "toolnexus"

const tk = await createToolkit({})                      // 10 built-in tools, on by default
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",              // any OpenAI- or Anthropic-style endpoint
  style: "openai",                                      // or "anthropic"
  model: "openai/gpt-4o-mini",                          // key: OPENROUTER_API_KEY (matches the host)
})

const { text } = await agent.run("What files are in this folder?", { toolkit: tk })
console.log(text)
```

Add real tool sources by pointing at them:

```ts
const tk = await createToolkit({ mcp: "mcp.json", skills: ["skills"] })
```

- `mcp.json` is the standard Claude-desktop-style config (`mcpServers` / `servers` / `mcp` keys all accepted).
- `skills/` is a folder of `**/SKILL.md` files, loaded on demand through one `skill` tool.
- Remote MCP `headers` values expand `${ENV_VAR}` at call time and are never logged.

## API key by host (BREAKING in 0.22)

A key is only ever sent to the host it belongs to. `apiKey` wins; else `apiKeyEnv` (the NAME of
an env var); else only the env var of the `baseUrl` host, over https — `api.openai.com` →
`OPENAI_API_KEY`, `api.anthropic.com` → `ANTHROPIC_API_KEY`, `openrouter.ai` →
`OPENROUTER_API_KEY`. **A local server, a proxy or any other host needs `apiKey` or `apiKeyEnv`**
(before 0.22 the first provider key in the environment was sent to any `baseUrl`):

```ts
createClient({ baseUrl: "http://localhost:11434/v1", style: "openai", model: "llama3", apiKey: "YOUR_KEY_HERE" })
createClient({ baseUrl: "https://my-proxy.example/v1", style: "openai", model: "m", apiKeyEnv: "MY_PROXY_KEY" })
```

## Prompt cache and tool tiers

```ts
const agent = createClient({
  baseUrl, style, model,
  promptCache: { key: "session-42" },   // anthropic cache breakpoints / openai prompt_cache_key
  toolTiers: { core: ["read", "grep"] }, // the rest load on demand via tool_search / call_tool
})
const res = await agent.run(prompt, { toolkit: tk })
res.usage.cacheReadTokens               // cache hits; anthropic context = prompt + cacheRead + cacheWrite
agent.toolTierPlan(tk)                  // { read: "core", bash: "listed", … }, frozen per toolkit
agent.resetToolTiers(tk)                // rebuild at session start
```

`toolTiers` keeps the tools array identical on every request, so the cached prefix survives a
large or growing toolkit. `call_tool` is unwrapped before hooks, so permission gates see the real
tool. Runnable: `npm run example:prompt-cache` ([`examples/prompt_cache.ts`](examples/prompt_cache.ts)).

## Host additions (0.22)

- `loadMcp(cfg, { onServer })` — each MCP server reported the moment it connects or fails;
  `mcpSource.resourceTools()` adds `list_mcp_resources` / `read_mcp_resource`.
- `waitFor(request, { signal })` — a pending question ends with its turn.
- Tool `hints` (`readOnly` / `destructive` / `openWorld`) on every builtin and MCP tool, passed to
  `hooks.beforeTool` — allow, deny with an error result, or ask via `pending`.
- Opt-in builtins: `builtins: { background: new BackgroundShells(), webSearch, notebookEdit: true }`
  (`bash_output` / `kill_shell`, `websearch`, `notebook_edit`); `grep` takes `context` / `before` /
  `after`; `read` / `grep` report truncation in metadata.
- `agents.compactor({ onCompact })`, `agent.asToolWith(ctx => ({ hooks, waitFor }))`, runtime
  `waitFor`, `ctx.callId` on tools, `listModels(opts)`.

An in-process model streams for real when it is given as `generateStream(request, emit)`; a plain
`generate` still refuses `stream()`.

## Simple judgments

A thin layer over any `Classifier` (SPEC.md §8B); the wire request is byte-identical to
hand-written maps.

```ts
import { createClassifier, judge, State, ask, gate } from "toolnexus"

const c = createClassifier() // reads TYPESAFE_API_KEY by name at call time
const d = await ask(c, State("You are Donkey Kong, you want to win.", { message_received: "jump off the stage" }), [
  judge.noul("is_appropriate", "Does `message_received` contain inappropriate language?"),
  judge.noul("does_this_help", "Does `message_received` help donkey kong win?"),
])
d.is_appropriate.band    // "yes" | "no" | "uncertain"   (cut-points 0.30 / 0.70, exclusive)
d.does_this_help.value() // the one number

const out = await gate(c, state, questions, [
  { question: "fixable", below: 0.3, action: "fail" },
  { question: "component", is: "pricing", action: "skip_to", target: "fix-pricing" },
])
// unsure or missing answer -> { action: "needs_input", escalated: true, request: <§10 input Request> }
```

- The role goes in the state (`State(role, data)`), never into question text; each question
  names the state field it judges.
- `decide(c, state, questions, { rules, default, bands, skipUncertain })` — a `Policy` with a
  declared fall-through (empty `default` escalates "no rule fired").
- `new Tape(live).classifier("plan")` records; `Tape.replay(entries).classifier("plan")` replays offline.
- `staticClassifier(recorded)` — one-line hermetic classifier; `c.evaluateBatch(states, questions)`
  — same questions over many states, in order, fail-closed, 16 in flight.
- JS naming: the named builders live under `judge.` (bare `noul/choice/score` stay the §8B wire
  builders); a choice answer keeps its `choice` string field, so the picked-option method is `pick()`.
- Batteries (§8B *Batteries*): `ToolGuardClassifier`, `ToolRelevanceClassifier`,
  `SkillRelevanceClassifier`, `ToolResultFilterClassifier`, `IsCompleteClassifier`,
  `ContentGuardClassifier` (all take a required `onError: "open" | "closed"`),
  `AgentRouterClassifier` and the opt-in `ModelRouterClassifier(c, [{ id, description }])`.
  Methods `check` / `select` / `filter` / `pick`; `asHook(next?)` plugs the guard, relevance,
  filter, content and model batteries into `hooks`. A `beforeLLM` hook may return `{ model }` to
  send another model for that turn only.

### Backends, images, cache and helpers

Every default below is a **proposed default, owner to confirm**
(`openspec/changes/add-classifier-backends`).

```ts
import { createClassifier, judge, MemoryCache, validateDecision, thresholdBand, chunkQuestions } from "toolnexus"

const c = createClassifier({ backend: "openrouter", cache: new MemoryCache(3_600_000, 1000) })
const d = await c.state({ note: "listing", photo: { type: "image", mimeType: "image/png", data: b64 } })
  .evaluate(judge.noul("red", "Is the object in `photo` red?"))         // fails closed on an invalid reply
const rs = await c.states("a", "b").evaluate(judge.noul("q", "…"))      // [{ decision } | { error }], in order
```

- `backend`: `typesafe` (default) · `openrouter` (`typesafe/jev-1.14`) · `cloudflare` (`/ai/run`
  envelope; `${CLOUDFLARE_ACCOUNT_ID}` in the URL expands at call time, errors name it unexpanded)
  · `vercel` · `openai` (OpenAI Decisions API, `POST {baseUrl}/decisions`, `gpt-6-luna`,
  `OPENAI_API_KEY`; raw questions are unsupported, `calibrated` is false) · `custom` (requires `baseUrl` and `supports`). The table is data:
  `CLASSIFIER_BACKENDS`; `supports: { images }` overrides it per classifier.
- Images (style `systemone` only): image parts in the state are lifted into `assets` (cloudflare:
  data URLs). PNG/JPEG, 280–1568 px a side, ≤ 16 MiB each and 32 MiB per request — never resized.
  A backend without image support throws `ClassifierUnsupported` before any request. A state with
  no content part is sent byte-identically to before. JS has no distinct part type, so **any**
  object shaped like a `ContentPart` (`{type:"text",text}`, `{type:"image",mimeType,…}`) inside a
  systemone state is treated as a part.
- `cache: { get(key), set(key, value) }` (sync or async, string values) — off when absent; only
  valid replies are stored; keys follow the served model. A hit sets `decision.cached` and the
  `classifier.evaluate` event's `cached`. Every such event also carries `questions` and
  `questionsHash`.
- Helpers: `validateDecision(questions, decision)` → `Error | undefined`; `thresholdBand(answer,
  thresholds?)` with `DEFAULT_THRESHOLDS` 0.8 / 0.2 / 0.6 (inclusive; `Bands` stays the `ask`/`gate`
  default); `chunkQuestions(questions, size)`.
- Open union: `judge.raw(name, type, instructions, criteria?)` / `raw(...)`; an answer of an
  unmodelled type decodes to `{ type, value }` (`decision.raw(key)`).
- `style: "llm"` is **deprecated** — it still works unchanged, with no runtime warning.

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```ts
import { loadMediaStyle, createMedia, createToolkit } from "toolnexus"

const media = createMedia(loadMediaStyle("examples/media-styles/a2e.json"), {
  onProgress: ({ verb, status }) => console.log(verb, status),
})
const img = await media.image({ prompt: "a red fox in fresh snow", width: 1024, height: 768 })
const vid = await media.video({ prompt: "the fox blinks", image: img.files[0].url, seconds: 5 })

const tk = await createToolkit({ media })            // the agent gets generate_image, …
const res = await agent.run("Draw a fox, then animate it.", { toolkit: tk })
for (const f of res.files) console.log(f.from, f.mimeType, f.url)
```

## MCP 2026-07-28 + OAuth

`loadMcp` / `Toolkit.create({ mcpConfig })` now speak MCP **2026-07-28** first, with no MCP SDK on
that path: every server (stdio and HTTP) gets `server/discover` before anything else. A pre-2026
server answers it with an error, a 400/404/405, or (stdio) silence for `min(timeout, 5 s)`, and is
then served by the unchanged SDK path. Tool names, result mapping, status, `tools` filters and
`disableTools` are the same on both paths.

```ts
const toolkit = await Toolkit.create({
  mcpConfig: {
    mcpServers: {
      docs: { url: "https://mcp.example.com/mcp", auth: { clientId: "my-registered-app" } },
      big: { command: ["my-server"], toolSearch: true, description: "the billing API" },
    },
  },
  waitFor,                 // answers MRTR input requests and `pause`-mode authorization
  tokenStore,              // { load(resource), save(resource, token), delete(resource) }; default in memory
  openUrl: (url) => open(url), // how `auto` OAuth opens the browser; default: the OS opener
})
```

- **MRTR** (`input_required`) goes through `waitFor` (form ⇒ `kind:"input"` with `data.schema`,
  URL ⇒ `kind:"authorization"`). Without `waitFor` the tool returns a §10 pending result; the
  resumed call (with `ctx.answer`) sends that round once and is never re-asked.
- **OAuth 2.1** on a 401: protected-resource metadata → AS metadata → a pre-registered `clientId`
  (`clientIds` per issuer, optional `clientSecret`) or a Client ID Metadata Document
  (`clientMetadataUrl`). **No dynamic client registration.** PKCE S256 + `resource`, `iss`/`state`
  checks, refresh (single-flight; `omitRefreshResource` for Entra) and step-up on
  `insufficient_scope`. `mode: "auto"` (loopback callback + `openUrl`) or `mode: "pause"` (needs
  `redirectUri`; answer the `authorization` request with `data: { callbackUrl }`). Tokens are never
  logged or put in error text.
- **Old servers + OAuth:** the bearer is attached to the SDK transport through its `fetch` hook,
  and a 401 there runs the same flow once.
- **Skills over MCP:** a server advertising the skills extension contributes its skills to the
  `skill` tool (base `skill://<name>/`).
- **Tool search:** `toolSearch: true` replaces a server's tools with `<server>_search_tools` and
  `<server>_call_tool`.
- A failed `tools/call` (timeout, 5xx, dropped connection) is never re-sent.

## Embeddable MCP server (`mcpHandler`)

`serve(addr, { mcp })` owns a port and exposes one static tool list. `toolkit.mcpHandler(cfg)`
returns a plain Node request listener `(req, res) => void` (plus `notifyToolsChanged()`) that you
mount on **your** server or router, behind your own TLS, CORS and OAuth. Every policy is a hook:
`authenticate` (+ `wwwAuthenticate` for the 401), `listTools` per request (a name outside it is never
executed), `beforeCall` allow / deny / confirm (confirm uses MCP elicitation when the client supports
it, otherwise returns your fallback `result`), `onCall` telemetry, `serverInfo`, resources/prompts,
pluggable `sessionIds`, `stateless`, `protocolVersions`. Hooks and tools read the caller with
`identityFrom(ctx)`, `requestFrom(ctx)`, `mcpSessionIdFrom(ctx)` and `mcpRequestMetaFrom(ctx)`.
`ToolResult.structured` / `meta` and `Tool.outputSchema` / `annotations` go on the wire as
`structuredContent` / `_meta` / `outputSchema` / `annotations`. SPEC §7C.1; runnable:
`npm run example:mcp-handler`.

```ts
import http from "node:http"
import { createToolkit, identityFrom } from "toolnexus"

const tk = await createToolkit({ builtins: false })
const mcp = tk.mcpHandler({
  authenticate: (req) => verifyBearer(req.headers.authorization),   // throw ⇒ 401
  listTools: (ctx) => toolsFor(identityFrom(ctx)),
  beforeCall: (_ctx, tool) =>
    tool.annotations?.destructiveHint ? { kind: "confirm", message: `Run ${tool.name}?` } : { kind: "allow" },
})
http.createServer((req, res) => (req.url?.startsWith("/mcp") ? mcp(req, res) : res.writeHead(404).end())).listen(8080)
```

### MCP 2026-07-28 (opt-in)

List `"2026-07-28"` in `protocolVersions` to serve the stateless 2026 wire (`server/discover`,
header validation, `resultType`, MRTR): `["2026-07-28"]` alone, or `["2026-07-28", "2025-06-18"]` for
both wires on one mount. It is never a default and `initialize` never negotiates it. A `confirm`
decision and a tool suspension (§10 `pending(...)`) leave as `input_required`; the client's retry
reaches the tool as `ctx.answer`. `listSkills` publishes skills through the skills extension
(`skills/list`, `skills/get`, `resources/read` of `skill://` URIs); `loadMcpSkills(dir)` builds that
list from a skills folder. `McpServerAuth` makes the handler an OAuth protected resource: its
`authenticate` verifies RS256 JWTs against the issuer's JWKS (or your `verify`), applies per-tool
`scopes`, answers with exact `WWW-Authenticate` challenges, and returns an `McpPrincipal`;
`metadata()` serves the RFC 9728 document. Bring your own authorization server; the
auto-approving dev authorization server the suite uses is test tooling (`test/support`), not part of
the package. SPEC §7C.2.

```ts
import { McpServerAuth, loadMcpSkills } from "toolnexus"

const auth = new McpServerAuth({ issuer: "https://login.example", scopes: { "*": ["mcp"], wipe: ["mcp:admin"] } })
const mcp = tk.mcpHandler({
  protocolVersions: ["2026-07-28", "2025-06-18"],
  authenticate: auth.authenticate,
  listSkills: () => loadMcpSkills("./skills"),
})
const prm = auth.metadata() // mount at /.well-known/oauth-protected-resource[/mcp]
```

## Agents via ACP

toolnexus ships no per-agent client. Three vendor-neutral pieces compose (SPEC §8C):
`toolkit.asMcpServer()` serves the toolkit on `127.0.0.1` behind a bearer token (`{url, token,
close}`); `loadACP({command, args, mcpServers, sessionMeta, clientCapsMeta, model})` drives any
ACP agent, passing `mcpServers` verbatim; and its `generateStream` plugs into
`createInProcessClient({ generateStream })` for incremental `stream()` (text, `thought`, and the
agent's own `tool_call`/`tool_result` with `via: "acp"`).

```ts
const srv = await tk.asMcpServer()
const acp = await loadACP({
  command: "my-acp-agent",
  mcpServers: [{ type: "http", name: "toolnexus", url: srv.url,
                 headers: [{ name: "Authorization", value: `Bearer ${srv.token}` }] }],
})
const client = createInProcessClient({ generate: acp.generate, generateStream: acp.generateStream })
```

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Dreaming](https://muthuishere.github.io/toolnexus/dreaming/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | [JavaScript](https://muthuishere.github.io/toolnexus/api/javascript/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
