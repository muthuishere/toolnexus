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

ACP host hooks (permission choice, update stream, cancel) are Go-only for now. `stream()` on a CLI
client never errors. Over ACP it is incremental: text deltas, `thought` events and the agent's
own `tool_call` / `tool_result` events (`via: "acp"`, reported, never executed) arrive as the
agent sends them. The one-shot CLI transport degrades to a non-incremental stream (tool_call /
tool_result events, the whole answer as one text event, then done). An in-process model streams
for real when it is given as `generateStream(request, emit)`; a plain `generate` still refuses
`stream()`.

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

## CLI client (agent CLIs as the model)

`createCliClient` makes an agent CLI you already have installed (and pay for) the model behind
an ordinary client. The style is a closed enum, never a string: `CliStyles.Claude | Codex |
Copilot | Opencode | Devin | Gemini | Goose | Custom`. `command` is required: nothing is
embedded or defaulted. SPEC §8C.

```ts
import { createCliClient, CliStyles, createToolkit } from "toolnexus"

const tk = await createToolkit({ skillsDir: "./skills" })
const client = createCliClient({ style: CliStyles.Opencode, command: "opencode", toolkit: tk })
const res = await client.run("What is my badge number?", { toolkit: tk })
console.log(client.transport(), res.text)   // "acp" …
await client.close()
```

- **Transports** `CliTransports.Auto | ACP | CLI`. Auto uses ACP when the style has an ACP launch
  (`[command] + style.acp.args`, or `acpCommand` verbatim, e.g. claude's
  `["npx", "-y", "@agentclientprotocol/claude-agent-acp@0.100.0"]`) and falls back to the one-shot
  CLI only when the agent fails to start or answer `initialize` (`fallbackReason()` says why).
  `transport()` is `"auto"` until the first call.
- **ACP**: the toolkit is served as MCP on `127.0.0.1:<ephemeral>/mcp` behind a per-session
  bearer token, `skill` is served as `load_skill`, and `beforeTool`/`afterTool` run at that
  server. Only calls to served tools are allowed; the agent's own shell, edit and fetch are
  rejected. Agents without http MCP get a stdio bridge (`bridgeCommand`, default
  `toolnexus mcp-bridge`; `runMcpBridge` is the same thing as a function).
- **One-shot**: the conversation goes in under a fixed preamble, the reply is parsed as an
  OpenAI-shaped envelope, and tool calls run in the toolnexus loop. Streaming is refused.
- Errors are pinned (`cli: command not found: <bin>`, `cli: exit <code>: <stderr>`,
  `cli: empty reply`, `cli: no output file`, `cli: timed out after <ms>ms`). Default call timeout
  300000 ms (`callTimeoutMs`). `usage` is always zero. Styles are data: `loadCliStyle(path | json)`
  for your own.
- Live check: `npm run example:cli -- claude opencode`.

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | [JavaScript](https://muthuishere.github.io/toolnexus/api/javascript/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
