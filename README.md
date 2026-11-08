# toolnexus

**The simplest agent library, and the fastest in most languages we've measured.** Build in the
language you're comfortable with: JavaScript, Python, Go, Java, C#, Elixir, Clojure or Rust, with the
same behaviour in each. MCP servers, agent skills, your own functions and media generation, for any LLM.

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

One vendor-neutral agent library, ported by hand to **JavaScript, Python, Go, Java, C#, Elixir,
Clojure and Rust**, held to one conformance contract ([`SPEC.md`](SPEC.md)) so the shared
[`examples/`](examples/) produce the same output in every port.

## The idea: it is all the same thing to an LLM

An MCP server tool, an agent skill, your own function, a remote HTTP endpoint, a built-in
shell/file tool, a remote A2A agent and an in-process sub-agent are all *the same thing* to a
model: a named, described, schema'd callable (an insight borrowed from
[opencode](https://github.com/anomalyco/opencode)). toolnexus puts every one of them behind one
`Tool` interface, emits the schema in OpenAI / Anthropic / Gemini shape, and drives any model with
them through one client loop.

## Install (0.28.0)

```sh
npm i toolnexus                                   # JavaScript / TypeScript (npm)
pip install toolnexus                             # Python (PyPI)
go get github.com/muthuishere/toolnexus/golang    # Go
dotnet add package Toolnexus                       # C# / .NET (NuGet)
{:toolnexus, "~> 0.28"}                             # Elixir (mix.exs deps, Hex)
# Java (Maven Central): io.github.muthuishere:toolnexus:0.28.0
# Clojure (deps.edn, Clojars): net.clojars.muthuishere/toolnexus {:mvn/version "0.28.0"} (JVM and cljgo)
cargo add toolnexus                                # Rust (crates.io)
```

## Zero to agent

Point it at an `mcp.json` (the standard Claude-desktop-style config) and a `skills/` folder of
`SKILL.md` files, then run:

```ts
import { createToolkit, createClient } from "toolnexus"

const tk = await createToolkit({ mcpConfig: "./mcp.json", skillsDir: "./skills" })
const agent = createClient({ baseUrl: "https://openrouter.ai/api/v1", style: "openai", model: "openai/gpt-4o-mini" })

const { text } = await agent.run("Refund order 4021 using the policy.", { toolkit: tk })
```

The key comes from `OPENROUTER_API_KEY` because the host is `openrouter.ai`: a key is only ever
sent to the host it belongs to. A local server or proxy needs `apiKey` or `apiKeyEnv`
([recipe](https://muthuishere.github.io/toolnexus/cookbook/api-key-by-host/)). The same shape in every language:
[Quickstart](https://muthuishere.github.io/toolnexus/quickstart/).

## What you get

- **MCP, 2026-07-28 and legacy** — client for stdio and streamable-HTTP servers, and a server:
  expose your toolkit over MCP. Elicitation, OAuth and per-server readiness included; Rust runs stdio servers on its own client. [MCP](https://muthuishere.github.io/toolnexus/mcp/)
- **Agent skills** — the open `SKILL.md` standard, loaded on demand through one `skill` tool. [Skills](https://muthuishere.github.io/toolnexus/skills/)
- **Native, HTTP and built-in tools** — your functions, remote endpoints, shell/file tools. [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/)
- **A2A** — call remote agents as tools, or serve your toolkit as one. [A2A](https://muthuishere.github.io/toolnexus/a2a/)
- **ACP and the in-process client** — drive a local coding agent over ACP, or any function, as the client's model; tool-call arguments stream as events, and `setMode` / `setModel` switch a running agent without a restart. [In-process client](https://muthuishere.github.io/toolnexus/in-process-client/)
- **Typed decisions** — `createClassifier` and judge adapters: yes/no, pick-one and score answers as typed values, on any System One endpoint or OpenAI's Decisions API. [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) · [Backends](https://muthuishere.github.io/toolnexus/judge/backends/)
- **Media generation** — images, video and speech from a JSON style, as typed calls and as agent tools. [Media](https://muthuishere.github.io/toolnexus/media/)
- **Human in the loop** — any tool can suspend on a `Request`; a `waitFor` host resolves it, or the run returns `pending` and resumes later. [Suspension](https://muthuishere.github.io/toolnexus/suspension/)
- **The loop** — parallel and chained tool calls, hooks (including an `afterRound` hook), streaming with retry events, retries and resilience, opt-in model fallback on overload, stop-reason recovery, reactive compaction on context overflow, prompt caching and tool tiers, sub-agents, observability. [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Sub-agents](https://muthuishere.github.io/toolnexus/subagents/)
- **Memory** — a memory hierarchy for persona agents, with built-in dreaming: a pass that checks memory against the transcripts and fixes or prunes it. [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Dreaming](https://muthuishere.github.io/toolnexus/dreaming/)
- **Run without a model** — a key-free local mock server for every wire toolnexus speaks: chat, streaming, tool calls, classifiers, media and faults. [Mock server](https://muthuishere.github.io/toolnexus/cookbook/mock-server/)

Exact per-port spellings and any port-specific gaps are in [`CHANGELOG.md`](CHANGELOG.md) and each
port's API reference.

## Benchmarks

Measured against 21 frameworks on a zero-cost mock model, per language. Honest summary: **lowest
per-request overhead over MCP in Python, JavaScript, C# and Rust, level with Eino in Go, and on the JVM 5% behind
LangChain4j in the five-round table but ahead of it (1.63 vs 1.84 ms p50) in a separate 7-round sitting after
the in-house Java stdio MCP client (#204)**. Every row, loss included: [Nov 2026 standing](https://muthuishere.github.io/toolnexus/standing-2026-11/).

## What it is not

- **Not a graph orchestrator.** No graph, builder or workflow DSL; compose agents as tools.
- **Not a hosted platform.** A library you embed; nothing to sign up for.
- **Not a RAG / vector store.** That is the sister project
  [citenexus](https://github.com/muthuishere/citenexus) (S3-first, with pgvector support).

## The eight ports

| language | package | docs |
|---|---|---|
| JavaScript / TypeScript | [`toolnexus`](https://www.npmjs.com/package/toolnexus) | [`js/`](js/README.md) · [API](https://muthuishere.github.io/toolnexus/api/javascript/) |
| Python | [`toolnexus`](https://pypi.org/project/toolnexus/) | [`python/`](python/README.md) · [API](https://muthuishere.github.io/toolnexus/api/python/) |
| Go | [`github.com/muthuishere/toolnexus/golang`](https://pkg.go.dev/github.com/muthuishere/toolnexus/golang) | [`golang/`](golang/README.md) · [API](https://muthuishere.github.io/toolnexus/api/go/) |
| Java | [`io.github.muthuishere:toolnexus`](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus) | [`java/`](java/README.md) · [API](https://muthuishere.github.io/toolnexus/api/java/) |
| C# / .NET | [`Toolnexus`](https://www.nuget.org/packages/Toolnexus) | [`csharp/`](csharp/README.md) · [API](https://muthuishere.github.io/toolnexus/api/csharp/) |
| Elixir | [`toolnexus`](https://hex.pm/packages/toolnexus) | [`elixir/`](elixir/README.md) · [API](https://muthuishere.github.io/toolnexus/api/elixir/) |
| Clojure | [`net.clojars.muthuishere/toolnexus`](https://clojars.org/net.clojars.muthuishere/toolnexus) | [`clojure/`](clojure/README.md) · [API](https://muthuishere.github.io/toolnexus/api/clojure/) |
| Rust | [`toolnexus`](https://crates.io/crates/toolnexus) | [`rust/`](rust/README.md) · [API](https://muthuishere.github.io/toolnexus/api/rust/) |

## Built on toolnexus

- **[wfnexus](https://muthuishere.github.io/wfnexus/)**: typed pipelines for coding agents. Each step is a whole agent with scoped tools, a budget, guardrails and a JSON-schema output.

Built something on toolnexus? Open a PR adding one line here.

## Docs and contract

- **Docs site:** [muthuishere.github.io/toolnexus](https://muthuishere.github.io/toolnexus/) — quickstart, concepts, cookbook, API reference per language.
- **[`SPEC.md`](SPEC.md)** — the cross-language contract; §0 is the one-page conformance suite. A behaviour change lands in all eight ports or it is not done.
- **[`CHANGELOG.md`](CHANGELOG.md)** — what shipped. Changes go through [OpenSpec](openspec/) first; see [`CLAUDE.md`](CLAUDE.md) and [`PUBLISHING.md`](PUBLISHING.md).

## Community

[Join the AgentNexus Discord](https://discord.gg/V9C2kvHC8D).

## License

MIT — see [LICENSE](LICENSE).
