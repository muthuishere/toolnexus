---
title: Where toolnexus stands — Nov 2026
description: >-
  toolnexus 0.26.0 against 25+ agent frameworks in eight languages: a re-checked feature matrix, fresh fake-model overhead benchmarks with pinned versions, where we lose, and the gaps we should close next.
---

> Same honesty rules as the [comparison page](/toolnexus/comparison/). Every claim is tagged:
> **[MEASURED]** — run in this repository on the machine below, harness committed under
> [`benchmarks/`](https://github.com/muthuishere/toolnexus/tree/main/benchmarks), raw results in
> [`benchmarks/results-2026-11.json`](https://github.com/muthuishere/toolnexus/blob/main/benchmarks/results-2026-11.json);
> **[FROM DOCS]** — from the project's own docs or release notes, linked; **[QUALITATIVE]** — a
> judgement call. A feature cell we could not confirm from an official source today says
> **unverified** — that means *not checked*, never *no*.
>
> toolnexus **0.26.0**, eight ports (JS · Python · Go · Java · C# · Elixir · Clojure · Rust).
> Everything was checked or measured on **2026-11-07**. Frameworks ship weekly; re-verify before quoting.

## What we do NOT claim

> - **Not the fastest everywhere.** Eino beats us in Go over MCP, LangChain4j beats us on the JVM,
>   and in .NET we tie Microsoft Agent Framework and have the slowest cold init. [MEASURED]
> - **Clojure is our slowest port** — 7.4–8.3 ms per request, ~10× the Rust port. [MEASURED]
> - **Not the only library that drives coding-agent CLIs over ACP.** Mastra ships first-party
>   [`@mastra/acp`](https://mastra.ai/docs/agents/acp); rig ships an ACP *agent*
>   ([v0.46.0](https://github.com/0xPlaygrounds/rig/releases/tag/v0.46.0)). [FROM DOCS]
> - **Agent skills are no longer rare.** Spring AI 2, Google ADK (Py/Go/TS), LangChain4j, OpenAI
>   Agents, CrewAI, Microsoft Agent Framework, Mastra and Eino all load skills now. [FROM DOCS]
> - **MCP 2026-07-28 client auth (CIMD + pre-registered clients) as a toolnexus edge is
>   [QUALITATIVE].** We ship it; we could not confirm from official docs whether each competitor does,
>   so we do not claim they don't.
> - **Fake-model numbers are framework overhead, not end-to-end latency.** With a real model the
>   100 ms–seconds model call dominates. These tables show the tax a framework adds on top.
> - **Not an orchestration engine, not a managed platform.** No graph runtime, no hosted deploy, no
>   eval product. LangGraph, ADK and Mastra are the better choice for those.
> - **Smaller and younger** than every framework on this page.

---

## Feature matrix (checked 2026-11-07)

Legend: ✅ yes · 🟡 partial / adapter / pre-release · ❌ no · **?** unverified (not checked from an
official source today). toolnexus cells are from this repo's
[`CHANGELOG.md`](https://github.com/muthuishere/toolnexus/blob/main/CHANGELOG.md) and `SPEC.md`.

| Framework (latest, date) | MCP client | MCP server | MCP 2026-07-28 | A2A | Agent skills | ACP | Typed judge / eval | Media gen | Languages |
|---|---|---|---|---|---|---|---|---|---|
| **toolnexus 0.26.0** (2026-11-06) | ✅ stdio + HTTP | 🟡 HTTP only | ✅ client + server, OAuth login (CIMD/pre-registered) | 🟡 subset (no streaming/push) | ✅ byte-exact | ✅ client | ✅ `createClassifier` + judge | ✅ §7G verbs + tools | **8** |
| Mastra 1.82.0 (11-04) | ✅ | ✅ | ? | ? | ✅ [rel](https://github.com/mastra-ai/mastra/releases/tag/%40mastra%2Fcore%401.82.0) | ✅ [docs](https://mastra.ai/docs/agents/acp) | ✅ `@mastra/evals` | ? | TS |
| Google ADK py 2.14.0 · java 1.14.0 · go 2.7.1 · ts 2.4.0 | ✅ | ✅ | ? | ✅ stable in py [rel](https://github.com/google/adk-python/releases/tag/v2.14.0) | ✅ py/go/ts [go](https://github.com/google/adk-go/releases/tag/v2.6.0) | ? | ✅ `adk eval` | ? | 4 (parity varies) |
| Spring AI 2.0.2 GA (2.1.0-RC2) | ✅ | ✅ [docs](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) | 🟡 server in RC only [rel](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-RC2) | ✅ [blog](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/) | ✅ [blog](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/) | ? | ? | ? | JVM |
| LangGraph py 1.2.20 · js 1.7.2 | 🟡 [adapters](https://github.com/langchain-ai/langchain-mcp-adapters) | ? | ? | 🟡 [Agent Server](https://docs.langchain.com/langsmith/server-a2a) | ✅ [Deep Agents](https://docs.langchain.com/oss/python/deepagents/skills) | ? | ? | ? | Py, TS |
| OpenAI Agents py 0.24.0 · js 0.21.0 | ✅ [docs](https://openai.github.io/openai-agents-python/mcp/) | ? | ✅ js client [rel](https://github.com/openai/openai-agents-js/releases) | ? | ✅ [rel](https://github.com/openai/openai-agents-python/releases/tag/v0.23.0) | ? | ? | 🟡 image tool, TTS | Py, TS |
| Pydantic AI 2.66.0 | ✅ + elicitation [docs](https://ai.pydantic.dev/mcp/client/) | ? | ? | ? | ? | ❌ left above core [issue](https://github.com/pydantic/pydantic-ai/issues/3255) | ✅ `pydantic_evals` [rel](https://github.com/pydantic/pydantic-ai/releases/tag/v2.64.0) | 🟡 realtime speech | Py |
| CrewAI 1.15.37 | ✅ (via mcpadapt) | ? | ? | ? | ✅ [rel](https://github.com/crewAIInc/crewAI/releases/tag/1.15.34) | ? | ✅ `crewai eval` | ? | Py |
| LangChain4j 1.23.0 | ✅ | ? | ✅ client [rel](https://github.com/langchain4j/langchain4j/releases/tag/1.19.0) | ✅ client [rel](https://github.com/langchain4j/langchain4j/releases/tag/1.23.0) | ✅ (1.23.0) | ? | ✅ LLM judges (1.23.0) | 🟡 Gemini TTS | JVM |
| MS Agent Framework .NET 1.28.0 · py 1.24.0 | ✅ | ? | ? | ✅ [rel](https://github.com/microsoft/agent-framework/releases/tag/dotnet-1.28.0) | ✅ py [rel](https://github.com/microsoft/agent-framework/releases/tag/python-1.24.0) | ❌ open request [issue](https://github.com/microsoft/agent-framework/issues/3521) | ✅ FoundryEvals | 🟡 image options (M.E.AI) | .NET, Py |
| Semantic Kernel .NET 1.82.0 | ? | ? | ? | ✅ [rel](https://github.com/microsoft/semantic-kernel/releases/tag/dotnet-1.66.0) | ? | ? | ? | ? | .NET, Py, Java |
| Eino 0.9.22 | ✅ (eino-ext) | ? | ? | ? | ✅ [rel](https://github.com/cloudwego/eino/releases/tag/v0.9.3) | ? | ? | ? | Go |
| rig 0.46.0 | ✅ rmcp [rel](https://github.com/0xPlaygrounds/rig/releases/tag/v0.46.0-rc.1) | ? | ? | ? | ? | 🟡 ACP *agent*, not client [rel](https://github.com/0xPlaygrounds/rig/releases/tag/v0.46.0) | ? | ✅ image, TTS, voice clone | Rust |
| swiftide 0.34.0 | ✅ rmcp | ? | ? | ? | 🟡 examples [rel](https://github.com/bosun-ai/swiftide/releases/tag/v0.34.0) | ? | ? | ? | Rust |
| Vercel AI 7.0.164 · LangChain.js 1.6.3 · langchaingo 0.1.15 · brainlid/langchain 0.15.8 | ✅ / ✅ / ❌ / ❌ as run in the benchmark below | ? | ? | ? | ? | ? | ? | 🟡 brainlid: text-to-image | — |

MCP-client cells without a link are [MEASURED]: the framework connected to our shared stdio MCP server
in the benchmark below. Streaming is supported by every framework above and is not a differentiator, so
it has no column. [QUALITATIVE]

**What the matrix says.** [QUALITATIVE] toolnexus covers every column. For most competitors several
cells are still unverified, so read this as our coverage, not their absence. toolnexus is the only
entry in eight languages with one contract. That's the real edge. It is *not* that any single
column is ours alone: skills, ACP, evals, A2A and media each exist elsewhere now, and LangChain4j is
the closest match in breadth on one runtime.

---

## Performance — fake model, Nov 2026

**Method** (unchanged from the [performance page](/toolnexus/performance/)): every framework points at the
same deterministic OpenAI-compatible mock and the same stdio MCP server. Scenario: *"What's the weather in
Paris and what is 2+2?"*, which means 2 LLM round-trips and 2 parallel tool calls. **N = 30 runs after 5
warmup** per row. Peak RSS comes from `/usr/bin/time -l`. Cold init is measured in-process. $0 spent. [MEASURED]

**Machine:** Apple M5 Pro (18 cores, 48 GB), macOS 26.4 · Go 1.26.3 · OpenJDK 26.0.1 (Java 21 toolchain) ·
.NET 10.0.301 · Node 24.18.0 · CPython 3.11.15 · Elixir 1.20.2/OTP 29 · rustc 1.99.0 `--release` ·
Clojure CLI 1.12.5 + cljgo 0.9.0.

Compare rows **within** this run, not against the August tables: every version and toolchain moved.
Two of our own ports got slower since August and we have not investigated why: Java (2.19 → 3.33 ms) and
Clojure (5.5 → 7.4–8.3 ms).

### Python

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** | 0.26.0 · mcp 1.30.0 | MCP | 42.8 | **1.23** | 1.81 | **61.7** | **33 MB** |
| MS Agent Framework | agent-framework-core 1.24.0 | MCP | 249.9 | 3.05 | 3.72 | 90.4 | 62 MB |
| Agno | 3.1.12 · fastmcp 4.1.6 | MCP | 225.2 | 3.59 | 5.54 | 116.4 | 125 MB |
| OpenAI Agents | 0.24.0 | MCP | 36.4 | 5.27 | 6.39 | 119.6 | 67 MB |
| Pydantic AI | pydantic-ai-slim 2.66.0 | MCP | 251.8 | 5.99 | 7.63 | 208.9 | 109 MB |
| LangGraph (persistent session) | 1.2.20 · mcp-adapters 0.3.2 | MCP | 41.7 | 6.14 | 10.57 | 114.3 | 87 MB |
| Google ADK | 2.14.0 · litellm 1.108.2 | MCP | 26.0 | 7.47 | 11.66 | 301.1 | 300 MB |
| CrewAI | 1.15.37 | MCP | 55.8 | 11.51 | 12.72 | 249.7 | 790 MB |
| LangGraph (default, reconnect) | 1.2.20 | MCP | 77.6 | 27.98 | 32.53 | 113.8 | 87 MB |

**Win.** 2.5× lower than the next framework, Microsoft Agent Framework. Lowest memory and smallest install. Our cold init (42.8 ms) is not the lowest: ADK and OpenAI Agents start faster.

### JavaScript / TypeScript

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** | 0.26.0 | MCP | 33.6 | **1.12** | 1.64 | 136.6 | 1 MB + 51 MB deps |
| Vercel AI SDK | ai 7.0.164 · @ai-sdk/mcp 2.0.92 | MCP | 17.1 | 1.23 | 2.13 | **128.2** | **30 MB** |
| LangGraph.js | 1.7.2 | MCP | 39.5 | 2.83 | 3.86 | 195.1 | 113 MB |
| LangChain.js | 1.6.3 · mcp-adapters 2.0.2 | MCP | 35.5 | 3.02 | 4.01 | 250.1 | 117 MB |
| OpenAI Agents JS | 0.21.0 | MCP | 38.0 | 3.06 | 5.12 | 161.7 | 83 MB |
| Mastra | core 1.82.0 · mcp 2.8.0 | MCP | 41.2 | 3.68 | 5.84 | 263.3 | 217 MB |

**Tie with Vercel AI SDK.** The 0.11 ms gap is inside noise. Vercel wins on init, RSS and install
size. We are about 2.5–3× lower than the rest.

### Go

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | binary |
|---|---|---|--:|--:|--:|--:|---|
| Eino | 0.9.22 · eino-ext mcp 0.0.9 | MCP | 16.3 | **0.55** | 0.79 | 31.7 | 24.8 MB |
| **toolnexus** | 0.26.0 · mcp-go 0.48.0 | MCP | 28.1 | 0.63 | 0.90 | **18.0** | **11.7 MB** |
| Google ADK Go | adk 1.9.0 · go-sdk 1.4.1 ¹ | MCP | 0.4 ¹ | 0.88 | 1.63 | 25.8 | 22.7 MB |
| Genkit Go | 1.14.0 | MCP | 40.2 | 2.52 | 3.05 | 34.9 | 28.5 MB |
| langchaingo | 0.1.15 | native | 0.0 | 0.65 | 1.12 | 21.1 | 16.8 MB |
| toolnexus (native) | 0.26.0 | native | 0.0 | 0.48 | 0.61 | 15.7 | 11.7 MB |

**Loss on MCP latency to Eino** (0.63 vs 0.55 ms). We win on footprint: 43% less RSS and half the
binary size. Our native-tool row is the fastest Go row. ¹ ADK Go has no OpenAI-compatible model, so
the runner adds a ~110-line adapter. It also connects to MCP lazily, so its init time leaves out the
MCP connect.

### JVM (Java, Kotlin)

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| LangChain4j | 1.23.0 · mcp 1.23.0-beta33 | MCP | **285** | **2.79** | 3.68 | **144.4** | **8.9 MB** |
| **toolnexus** | 0.26.0 · MCP Java SDK 2.0.0 | MCP | 473 | 3.33 | 4.00 | 177.1 | 11 MB |
| Spring AI | 2.0.2 · Boot 4.1.2 | MCP | 1286 | 4.36 | 6.47 | 545.8 | 123 MB jar |
| Koog (Kotlin) | 1.3.0 | MCP | 328 | 5.68 | 7.40 | 184.4 | 36 MB |
| Google ADK Java | 1.14.0 + LangChain4j bridge ² | MCP | 390 ² | 6.05 | 10.05 | 272.5 | 174 MB |

**Loss to LangChain4j** on every metric. We beat Spring AI (with about a third of its memory), Koog
and ADK Java. ² ADK Java connects to MCP lazily, so its init time leaves out the MCP connect.

### .NET (C#)

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| MS Agent Framework | Microsoft.Agents.AI 1.28.0 · MCP 2.4.0 | MCP | **123** | **0.958** | 1.11 | 87.1 | 12 MB |
| **toolnexus** | 0.26.0 · ModelContextProtocol 1.4.0 | MCP | 158 | 0.966 | 1.18 | **80.0** | **4.0 MB** |
| Microsoft.Extensions.AI | 10.11.0 | native | 34.5 | 0.653 | 0.83 | 75.9 | 7.9 MB |
| Semantic Kernel | 1.82.0 | native | 60.0 | 0.664 | 1.12 | 78.2 | 9.5 MB |

**Tie on MCP** with Microsoft Agent Framework: 0.966 vs 0.958 ms. We **lose on cold init**, the slowest
on this table. MEAI and Semantic Kernel are faster, but they ran native tools, not MCP.

### Elixir

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB |
|---|---|---|--:|--:|--:|--:|
| **toolnexus** | 0.26.0 (in-house MCP) | MCP | 53.9 | 0.672 | 0.83 | 112.2 |
| **toolnexus** | 0.26.0 | native | 10.0 | **0.601** | 0.73 | 110.5 |
| brainlid/langchain | 0.15.8 | native | 0.0 | 0.780 | 1.05 | 111.9 |

**Win.** toolnexus is 23% lower when both run native tools. brainlid/langchain has no MCP client, so it has no MCP row.

### Clojure

| framework | host | tools | init ms | p50 ms | p95 ms | RSS MB |
|---|---|---|--:|--:|--:|--:|
| toolnexus 0.26.0 · koine 0.11.0 | cljgo 0.9.0 binary | MCP | 32 | 7.4 | 7.7 | 36.0 |
| toolnexus 0.26.0 · koine 0.11.0 | JVM (Clojure 1.12.5) | MCP | 45 | 8.3 | 10.7 | 532.0 |
| toolnexus 0.26.0 | cljgo | native | 0 | 1.8 | 2.2 | 34.6 |

**No competitor exists, and this is our slowest port.** The MCP path costs about 5.6 ms more than
native tools, so MCP is where to look first.

### Rust (new port)

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | binary |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** | 0.26.0 · rmcp 3.9.0 | MCP | 35.4 | **0.529** | 0.63 | **16.8** | 18.3 MB |
| rig | rig-core 0.46.0 · rmcp 3.9.0 | MCP | 21.9 | 0.584 | 0.73 | 23.0 | **15.7 MB** |
| swiftide | 0.34.0 · rmcp 2.2.0 ³ | MCP | **17.7** | 0.668 | 1.02 | 16.9 | 17.5 MB |
| toolnexus (native) | 0.26.0 | native | 0.1 | 0.436 | 0.49 | 11.2 | 18.3 MB |

**Win on latency**, by 10% over rig. We **lose on cold init** to both rig and swiftide, and our binary
is larger than rig's. toolnexus-rust native, at 0.436 ms, is the fastest row in the whole run. ³ swiftide
builds a fresh agent inside each timed request, because its agents keep history.

### Could not run, and why

| framework | version | reason |
|---|---|---|
| smolagents | 1.26.0 | It sends tool results back as role `user` and stops only on a `final_answer` tool call. Our shared mock finishes only after a role `tool` message, so every run ends with "Reached max steps." Running it needs a change to the shared mock. |
| AutoAgents | 0.4.0 | `duplicate field 'prompt_tokens'`: its parser treats `input_tokens` as another name for `prompt_tokens`, and the mock sends both. It worked against a patched mock, but that run is not recorded. |
| Google ADK TS | 2.4.0 | It has no OpenAI-compatible model or base-URL option. A hand-written adapter would benchmark our adapter, not ADK. |
| Embabel | — | Not attempted. It is a goal-planning framework, not a tool-calling loop over a chat model. |

---

## What changed since 0.19.0

All from [`CHANGELOG.md`](https://github.com/muthuishere/toolnexus/blob/main/CHANGELOG.md), in all eight ports unless named:

- **Rust port** (0.23.0): the eighth port, published on crates.io at tier `full`. It has no Gemini client style yet.
- **Media generation** (0.24.0): `createMedia(style)` adds typed `image` / `video` / `speech` /
  `transcribe` / `cloneVoice` / `talking` verbs. The same verbs are agent tools, and each provider is a JSON style.
- **In-process client + ACP** (0.22.0–0.25.0): `createInProcessClient` can stream. A plain ACP client drives
  coding-agent CLIs, and the toolkit can be exposed to those agents as MCP. Each vendor's setup stays a docs recipe, not library code (ADR 0041).
- **MCP 2026-07-28** (0.25.0–0.26.0): the client tries 2026-07-28 first and falls back to the frozen older
  client if the server doesn't support it. It can log in to OAuth servers using CIMD or a pre-registered
  client, with no dynamic registration. `MCPHandler` serves 2026-07-28 with OAuth metadata and skills over
  MCP. Not done yet: live GitHub login per port (it needs the owner's browser consent).
- **Classifier backends** (0.26.0): `backend` can be `typesafe | openrouter | cloudflare | vercel | custom`, with images in state, a cache and reply validation.

---

## Gaps we should close, ranked by value

1. **MCP server breadth.** We serve over HTTP only, with no stdio, resources, prompts or sampling. Spring AI and ADK
   serve the full surface, and "expose my toolkit to Claude Desktop/Cursor over stdio" is the most common
   ask. [QUALITATIVE]
2. **A2A beyond submit→poll.** We have no streaming, push or auth. ADK Python just made A2A stable, and
   LangChain4j, Spring AI and MAF all ship it. This matters most for agent-to-agent interop. [FROM DOCS]
3. **JVM and Go overhead.** We lose to LangChain4j (2.79 vs 3.33 ms) and Eino (0.55 vs 0.63 ms) on the
   flagship MCP path. Java also regressed since August. Both are profile-and-fix jobs, so they are cheap to try. [MEASURED]
4. **C# cold init** of 158 ms, the slowest on the .NET table. [MEASURED]
5. **Clojure MCP path** at 7.4–8.3 ms. The native path is 1.8 ms, so the cost is in our in-house MCP client. [MEASURED]
6. **Eval story.** Five competitors now ship eval *runners* (`pydantic_evals`, `adk eval`, `crewai eval`,
   FoundryEvals, LangChain4j judges). We ship a typed classifier and judge but no runner over a dataset. [FROM DOCS]
7. **Benchmark harness compatibility.** Fix the mock so smolagents and AutoAgents can run: return role-`tool`
   results that smolagents accepts, and stop sending duplicate usage keys. [MEASURED]
8. **Rust Gemini client style**, the last named gap in the Rust port. [FROM DOCS]

We deliberately do **not** list graph orchestration or a managed runtime. They are out of scope for
a library (ADRs 0016/0017). [QUALITATIVE]
