# toolnexus

**We wrote one agent SDK and ported it, by hand, to eight languages — JS, Python, Go, Java,
C#, Elixir, Clojure, Rust — until the same conformance suite passes byte-identical output in every
one of them.**

**Building any agentic framework? Build it on toolnexus.** MCP client and server, agent skills, A2A and ACP agents, a typed decision classifier,
image/video/speech generation, streaming, memory and resilience — with the lowest or level overhead
in most languages; [see the benchmark](https://muthuishere.github.io/toolnexus/standing-2026-11/).

[![CI](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml/badge.svg)](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/toolnexus?logo=npm&label=npm)](https://www.npmjs.com/package/toolnexus)
[![PyPI](https://img.shields.io/pypi/v/toolnexus?logo=pypi&logoColor=white&label=PyPI)](https://pypi.org/project/toolnexus/)
[![NuGet](https://img.shields.io/nuget/v/Toolnexus?logo=nuget&label=NuGet)](https://www.nuget.org/packages/Toolnexus)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.muthuishere/toolnexus?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus)
[![Hex.pm](https://img.shields.io/hexpm/v/toolnexus?logo=elixir&label=Hex)](https://hex.pm/packages/toolnexus)
[![Clojars](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.clojars.org%2Fnet%2Fclojars%2Fmuthuishere%2Ftoolnexus%2Fmaven-metadata.xml&logo=clojure&logoColor=white&label=Clojars)](https://clojars.org/net.clojars.muthuishere/toolnexus)
[![crates.io](https://img.shields.io/crates/v/toolnexus?logo=rust&label=crates.io)](https://crates.io/crates/toolnexus)
[![Go](https://img.shields.io/github/v/tag/muthuishere/toolnexus?filter=golang%2Fv*&logo=go&logoColor=white&label=Go&color=00ADD8)](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
[![Go Reference](https://pkg.go.dev/badge/github.com/muthuishere/toolnexus/golang.svg)](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
[![docs.rs](https://img.shields.io/docsrs/toolnexus?logo=docsdotrs&label=docs.rs)](https://docs.rs/toolnexus)
[![Docs](https://img.shields.io/badge/docs-toolnexus-blue?logo=astro&logoColor=white)](https://muthuishere.github.io/toolnexus/)
[![license](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Discord](https://img.shields.io/badge/AgentNexus-join%20the%20community-5865F2?logo=discord&logoColor=white)](https://discord.gg/V9C2kvHC8D)

### Your LLM, with MCP tools and agent skills built in — in 3 lines, in 8 languages.

Point toolnexus at an `mcp.json` and a `skills/` folder and you get a **working agent**: the
tool-calling loop, skills injection, six unified tool sources, and conversation memory — all
included. Vendor-neutral, byte-identical across **JavaScript · Python · Go · Java · C# · Elixir ·
Clojure** — the Clojure port is one `.cljc` tree that runs on both the JVM and cljgo, held
to the same `full` tier as the other six and published to Clojars like the rest.

> **Right-sized.** Not a framework — no builders, advisors, runnables, or config to wade through.
> Not a toy that falls over the moment you need streaming or a retry. Exactly what a real agent
> needs — MCP, skills, native + HTTP + built-in tools, remote A2A agents, in-process sub-agents,
> the loop, hooks, streaming, retries, memory, and media generation (images, video and speech from a JSON style, as typed calls and as agent tools). Nothing it doesn't.

```sh
npm i toolnexus                                   # JS / TypeScript
pip install toolnexus                             # Python
go get github.com/muthuishere/toolnexus/golang    # Go
dotnet add package Toolnexus                       # C#
{:toolnexus, "~> 0.27"}                             # Elixir (mix.exs deps)
# Java (Maven): io.github.muthuishere:toolnexus:0.27.0
# Clojure (deps.edn): net.clojars.muthuishere/toolnexus {:mvn/version "0.27.0"} — JVM and cljgo
cargo add toolnexus                                # Rust (crates.io)
```

The insight (borrowed from [opencode](https://github.com/anomalyco/opencode)): MCP server
tools, agent skills, your own functions, remote HTTP endpoints, the built-in shell/file
tools, remote A2A agents, and in-process **sub-agents** are all *the same thing* to an LLM — a
named, described, schema'd callable. toolnexus unifies **every tool source** behind one `Tool`
interface and drives **any** model with them.


## Built on toolnexus

- **[wfnexus](https://muthuishere.github.io/wfnexus/)**: typed pipelines for coding agents. Each step is a whole agent with scoped tools, a budget, guardrails and a JSON-schema output.

Built something on toolnexus? Open a PR adding one line here: name, link and what it does.

## From zero to agent in 3 steps

**1. Point at an `mcp.json`** — the standard Claude-desktop-style config:

```jsonc
{
  "mcpServers": {
    "fs":   { "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"] },
    "acme": { "type": "remote", "url": "https://api.acme.com/mcp",
              "headers": { "Authorization": "Bearer ${ACME_TOKEN}" } }
  }
}
```

**2. Point at a `skills/` folder** — `**/SKILL.md` files with YAML frontmatter, loaded on demand
through one `skill` tool (progressive disclosure).

**3. Run.**

```ts
import { createToolkit, createClient } from "toolnexus"

const tk = await createToolkit({ mcp: "mcp.json", skills: ["skills"] })
const agent = createClient({ baseUrl: "https://openrouter.ai/api/v1", style: "openai", model: "openai/gpt-4o-mini" })

const { text } = await agent.run("Refund order 4021 using the policy.", { toolkit: tk })
```

The key above comes from `OPENROUTER_API_KEY`, because the host is `openrouter.ai`. **Since
0.22.0 a key is only ever sent to the host it belongs to**: with no `apiKey`, the client reads
only the env var matching the `baseUrl` host (`openrouter.ai` → `OPENROUTER_API_KEY`,
`api.openai.com` → `OPENAI_API_KEY`, `api.anthropic.com` → `ANTHROPIC_API_KEY`, https only).
A local server or a proxy needs `apiKey` or `apiKeyEnv` (the *name* of the env var to read):

```ts
createClient({ baseUrl: "http://localhost:11434/v1", style: "openai", model: "llama3", apiKey: "unused" })
createClient({ baseUrl: "https://llm.internal/v1", style: "openai", model: "m", apiKeyEnv: "INTERNAL_LLM_KEY" })
```

The same three steps, in the same shape, in every port — see your language's
[API reference](https://muthuishere.github.io/toolnexus/) below.

## New in 0.22

- **Prompt caching and tool tiers.** `promptCache` marks the stable prefix for the provider
  (Anthropic cache breakpoints, OpenAI `prompt_cache_key`) and usage reports
  `cacheReadTokens` / `cacheWriteTokens`. `toolTiers` sends only your core tool schemas and
  lets the model load the rest through `tool_search` / `call_tool`, so the tools array — and
  the cached prefix — never changes mid-session.
  [Recipe](https://muthuishere.github.io/toolnexus/cookbook/prompt-cache-and-tool-tiers/).
- **API key by host** (breaking for local/custom hosts, above).
  [Recipe](https://muthuishere.github.io/toolnexus/cookbook/api-key-by-host/).
- **Host hooks**: a cancellable `waitFor`, per-server MCP readiness (`onServer`), MCP resource
  tools, tool hints (`readOnly` / `destructive` / `openWorld`) on `beforeTool` for permission
  gates, opt-in builtins (background shells, `websearch`, `notebook_edit`), grep context,
  compaction notices, `asToolWith` for subagents under the parent's gates, `callId` on the tool
  context, and `listModels`.
  [Recipe](https://muthuishere.github.io/toolnexus/cookbook/host-hooks/).
- **Go and Rust only, for now:** ACP host hooks (`OnPermission`, `OnUpdate`, `GenerateCtx`,
  `GenerateStream`). [Recipe](https://muthuishere.github.io/toolnexus/cookbook/acp-hooks/).

Per-port spellings are in [`CHANGELOG.md`](CHANGELOG.md) and each port's API reference.

## The eight ports

| language | package | API reference |
|---|---|---|
| JavaScript / TypeScript | [`toolnexus`](https://www.npmjs.com/package/toolnexus) (npm) | [api/javascript](https://muthuishere.github.io/toolnexus/api/javascript/) |
| Python | [`toolnexus`](https://pypi.org/project/toolnexus/) (PyPI) | [api/python](https://muthuishere.github.io/toolnexus/api/python/) |
| Go | [`github.com/muthuishere/toolnexus/golang`](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang) | [api/go](https://muthuishere.github.io/toolnexus/api/go/) |
| Java | [`io.github.muthuishere:toolnexus`](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus) | [api/java](https://muthuishere.github.io/toolnexus/api/java/) |
| C# / .NET | [`Toolnexus`](https://www.nuget.org/packages/Toolnexus) (NuGet) | [api/csharp](https://muthuishere.github.io/toolnexus/api/csharp/) |
| Elixir | [`toolnexus`](https://hex.pm/packages/toolnexus) (Hex) | [api/elixir](https://muthuishere.github.io/toolnexus/api/elixir/) |
| Clojure | [`net.clojars.muthuishere/toolnexus`](https://clojars.org/net.clojars.muthuishere/toolnexus) | [api/clojure](https://muthuishere.github.io/toolnexus/api/clojure/) |
| Rust | `toolnexus` (crates.io) · [`rust/`](rust/README.md) | [api/rust](https://muthuishere.github.io/toolnexus/api/rust/) |

Each port's own README covers install and a quick start; everything beyond that is on the docs site.

## Documentation

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions (`Classifier`)](https://muthuishere.github.io/toolnexus/judge/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Prompt cache & tool tiers](https://muthuishere.github.io/toolnexus/cookbook/prompt-cache-and-tool-tiers/) · [API key by host](https://muthuishere.github.io/toolnexus/cookbook/api-key-by-host/) · [Host hooks](https://muthuishere.github.io/toolnexus/cookbook/host-hooks/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |
| **Numbers** | [Performance](https://muthuishere.github.io/toolnexus/performance/) · [Comparison](https://muthuishere.github.io/toolnexus/comparison/) · [Judge, live](https://muthuishere.github.io/toolnexus/harness/judge-live/) |

## The contract

[`SPEC.md`](SPEC.md) is the cross-language contract — §0 is the one-page conformance suite every
port must satisfy. A behaviour change lands in all eight ports or it is not done; the shared
fixtures in [`examples/`](examples/) are what "identical" is measured against.

Changes go through [OpenSpec](openspec/) before code. See [`CLAUDE.md`](CLAUDE.md) for the
workflow, [`CHANGELOG.md`](CHANGELOG.md) for what shipped, and [`PUBLISHING.md`](PUBLISHING.md)
for how each port is released.

## Community

[Join the AgentNexus Discord](https://discord.gg/V9C2kvHC8D).

## License

MIT — see [LICENSE](LICENSE).
