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
library, byte-identical, also in **Python, Go, Java, C#, Elixir and Clojure**. Built on
`@modelcontextprotocol/sdk` (the MCP SDK opencode uses).

## Install

```sh
npm install toolnexus
```

## Quick start

Built-in tools are on by default, so an empty toolkit can already act:

```ts
import { createToolkit, createClient } from "toolnexus"

const tk = await createToolkit()                        // 10 built-in tools, on by default
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",              // any OpenAI- or Anthropic-style endpoint
  style: "openai",                                      // or "anthropic"
  model: "openai/gpt-4o-mini",
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

Contract across all seven ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
