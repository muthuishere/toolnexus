# toolnexus

**We wrote one agent SDK and ported it, by hand, to seven languages — JS, Python, Go, Java,
C#, Elixir, Clojure — until the same conformance suite passes byte-identical output in every
one of them.**

[![CI](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml/badge.svg)](https://github.com/muthuishere/toolnexus/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/toolnexus?logo=npm&label=npm)](https://www.npmjs.com/package/toolnexus)
[![PyPI](https://img.shields.io/pypi/v/toolnexus?logo=pypi&logoColor=white&label=PyPI)](https://pypi.org/project/toolnexus/)
[![NuGet](https://img.shields.io/nuget/v/Toolnexus?logo=nuget&label=NuGet)](https://www.nuget.org/packages/Toolnexus)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.muthuishere/toolnexus?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus)
[![Hex.pm](https://img.shields.io/hexpm/v/toolnexus?logo=elixir&label=Hex)](https://hex.pm/packages/toolnexus)
[![Go Reference](https://pkg.go.dev/badge/github.com/muthuishere/toolnexus/golang.svg)](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang)
[![license](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Discord](https://img.shields.io/badge/AgentNexus-join%20the%20community-5865F2?logo=discord&logoColor=white)](https://discord.gg/V9C2kvHC8D)

### Your LLM, with MCP tools and agent skills built in — in 3 lines, in 7 languages.

Point toolnexus at an `mcp.json` and a `skills/` folder and you get a **working agent**: the
tool-calling loop, skills injection, six unified tool sources, and conversation memory — all
included. Vendor-neutral, byte-identical across **JavaScript · Python · Go · Java · C# · Elixir ·
Clojure** — the Clojure port is one `.cljc` tree that runs on both the JVM and cljgo, held
to the same `full` tier as the other six and published to Clojars like the rest.

> **Right-sized.** Not a framework — no builders, advisors, runnables, or config to wade through.
> Not a toy that falls over the moment you need streaming or a retry. Exactly what a real agent
> needs — MCP, skills, native + HTTP + built-in tools, remote A2A agents, in-process sub-agents,
> the loop, hooks, streaming, retries, memory — and nothing it doesn't.

```sh
npm i toolnexus                                   # JS / TypeScript
pip install toolnexus                             # Python
go get github.com/muthuishere/toolnexus/golang    # Go
dotnet add package Toolnexus                       # C#
{:toolnexus, "~> 0.18"}                             # Elixir (mix.exs deps)
# Java (Maven): io.github.muthuishere:toolnexus:0.19.0
# Clojure (deps.edn): net.clojars.muthuishere/toolnexus {:mvn/version "0.19.0"} — JVM and cljgo
```

The insight (borrowed from [opencode](https://github.com/anomalyco/opencode)): MCP server
tools, agent skills, your own functions, remote HTTP endpoints, the built-in shell/file
tools, remote A2A agents, and in-process **sub-agents** are all *the same thing* to an LLM — a
named, described, schema'd callable. toolnexus unifies **every tool source** behind one `Tool`
interface and drives **any** model with them.

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

The same three steps, in the same shape, in every port — see your language's
[API reference](https://muthuishere.github.io/toolnexus/) below.

## The seven ports

| language | package | API reference |
|---|---|---|
| JavaScript / TypeScript | [`toolnexus`](https://www.npmjs.com/package/toolnexus) (npm) | [api/javascript](https://muthuishere.github.io/toolnexus/api/javascript/) |
| Python | [`toolnexus`](https://pypi.org/project/toolnexus/) (PyPI) | [api/python](https://muthuishere.github.io/toolnexus/api/python/) |
| Go | [`github.com/muthuishere/toolnexus/golang`](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang) | [api/go](https://muthuishere.github.io/toolnexus/api/go/) |
| Java | [`io.github.muthuishere:toolnexus`](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus) | [api/java](https://muthuishere.github.io/toolnexus/api/java/) |
| C# / .NET | [`Toolnexus`](https://www.nuget.org/packages/Toolnexus) (NuGet) | [api/csharp](https://muthuishere.github.io/toolnexus/api/csharp/) |
| Elixir | [`toolnexus`](https://hex.pm/packages/toolnexus) (Hex) | [api/elixir](https://muthuishere.github.io/toolnexus/api/elixir/) |
| Clojure | [`net.clojars.muthuishere/toolnexus`](https://clojars.org/net.clojars.muthuishere/toolnexus) | [api/clojure](https://muthuishere.github.io/toolnexus/api/clojure/) |

Each port's own README covers install and a quick start; everything beyond that is on the docs site.

## Documentation

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions (`Classifier`)](https://muthuishere.github.io/toolnexus/judge/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |
| **Numbers** | [Performance](https://muthuishere.github.io/toolnexus/performance/) · [Comparison](https://muthuishere.github.io/toolnexus/comparison/) · [Judge, live](https://muthuishere.github.io/toolnexus/harness/judge-live/) |

## The contract

[`SPEC.md`](SPEC.md) is the cross-language contract — §0 is the one-page conformance suite every
port must satisfy. A behaviour change lands in all seven ports or it is not done; the shared
fixtures in [`examples/`](examples/) are what "identical" is measured against.

Changes go through [OpenSpec](openspec/) before code. See [`CLAUDE.md`](CLAUDE.md) for the
workflow, [`CHANGELOG.md`](CHANGELOG.md) for what shipped, and [`PUBLISHING.md`](PUBLISHING.md)
for how each port is released.

## Community

[Join the AgentNexus Discord](https://discord.gg/V9C2kvHC8D).

## License

MIT — see [LICENSE](LICENSE).
