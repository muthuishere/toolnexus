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
> toolnexus **0.26.0** for the feature matrix, eight ports (JS · Python · Go · Java · C# · Elixir · Clojure · Rust).
> The feature matrix was checked on **2026-11-07**. The performance tables are a quiet re-run on **2026-11-08** (load < 4, five interleaved rounds) of toolnexus `main` (the 0.28.0 candidate) next to 0.27.0 and every competitor. Frameworks ship weekly; re-verify before quoting.

## What we do NOT claim

> - **Not the fastest everywhere.** LangChain4j still beats us on the JVM over MCP (2.17 vs 2.28 ms p50, and
>   less memory); Eino in Go is level with us, not behind; AutoAgents in Rust uses less memory than we do.
>   [MEASURED]
> - **Not a JavaScript speed-up.** The 0.28.0 candidate is faster than 0.27.0 in Python, Java, C# and Rust, but
>   our harness shows no JS per-request change (3.43 → 3.40 ms). [MEASURED]
> - **Clojure is our slowest port**: 6.9–7.8 ms per request over MCP, ~20× the Rust port; the fix is
>   deferred (issue #168). [MEASURED]
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
> - **No RAG, no vector store, no embeddings.** toolnexus has no document loaders, chunking,
>   embedding models, vector-DB connectors or retrievers. Spring AI (`VectorStore`), LangChain /
>   LangChain4j (embedding stores), Mastra (`@mastra/rag`), Semantic Kernel, Eino, rig and swiftide
>   ship that; we don't, by design. Retrieval is our sister project
>   [citenexus](https://muthuishere.github.io/citenexus/): evidence-first RAG, built S3-first (the
>   index lives in object storage, no vector database to run; pgvector is also supported), that
>   answers only from cited quotes and abstains when the evidence is weak. Today you plug it into toolnexus as a tool
>   (MCP or native), not as a built-in. [QUALITATIVE]
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

**What the matrix says.** [QUALITATIVE] toolnexus covers every column *of this matrix*; the matrix has no RAG column because we have nothing there (see above). For most competitors several
cells are still unverified, so read this as our coverage, not their absence. toolnexus is the only
entry in eight languages with one contract. That's the real edge. It is *not* that any single
column is ours alone: skills, ACP, evals, A2A and media each exist elsewhere now, and LangChain4j is
the closest match in breadth on one runtime.

---

## Performance — fake model, quiet re-run for 0.28.0 (2026-11-08)

**Method** (unchanged from the [performance page](/toolnexus/performance/)): every framework points at the
same deterministic OpenAI-compatible mock and the same stdio MCP server. Scenario: *"What's the weather in
Paris and what is 2+2?"*, which means 2 LLM round-trips and 2 parallel tool calls. Each row is **25 runs after 5
warmup**. Peak RSS comes from `/usr/bin/time -l`. Cold init is measured in-process, once per process, so
it is the noisiest column. $0 spent. [MEASURED]

**This sitting** (2026-11-08, afternoon IST): **five alternating rounds**. In each round toolnexus 0.27.0 (tag
`v0.27.0`) and `main` at `e5ef449a` (the 0.28.0 candidate) ran back to back, MCP and native, in Python, JS, Go,
Java, C# and Rust, with the order flipped every round; then every competitor in those six languages ran. Every row
started only when the 1-minute load average was **below 4** on 18 cores (it was 2.95–3.63 at every start). **Every
number below is the median of the five rounds**; the per-round values are on each row in
[`results-2026-11.json`](https://github.com/muthuishere/toolnexus/blob/main/benchmarks/results-2026-11.json) under
`run: "2026-11-08-quiet-0.28"`. Same harness (`benchmarks/run_all.py`) and same keep-alive mock
(`benchmarks/mock_llm.py`) as the earlier sittings. The JS runners start their own in-process mock, so JS absolute
numbers are higher than the other languages'; compare JS rows with each other. **Elixir and Clojure were not
re-run** (nothing in them changed since 0.27.0); their tables below are from the earlier quiet sitting
(`run: "2026-11-08-quiet"`, toolnexus `main` at `f6edfbeb`).

**Machine:** Apple M5 Pro (18 cores, 48 GB), macOS 26.4 · Go 1.26.3 · OpenJDK 26.0.1 (Java 21 toolchain) ·
.NET 10.0.301 · Node 24.18.0 · CPython 3.11.15 · Elixir 1.20.2/OTP 29 · rustc 1.99.0 `--release` ·
Clojure CLI 1.12.5 + cljgo 0.9.0.

### 0.27.0 → main (0.28.0 candidate), same sitting

Medians of five interleaved rounds, MCP config (init ms / p50 ms / RSS MB), then the native p50. The change on
`main` that touches these numbers is the request-path speed-up
([#185](https://github.com/muthuishere/toolnexus/pull/185), `openspec/changes/speed-up-request-path`): a kept-open
LLM connection in Python and JS, no thread hand-off per MCP stdio line, a cached proxy lookup, deferred SDK
loading in Java and C#, and Rust's own stdio MCP client in place of rmcp's. [MEASURED]

| port | 0.27.0 init | main init | 0.27.0 p50 | main p50 | 0.27.0 RSS | main RSS | native p50 0.27.0 → main |
|---|--:|--:|--:|--:|--:|--:|--:|
| Python | 21.9 | **19.9** | 1.378 | **0.924** | 61.9 | 61.9 | 0.727 → **0.533** |
| JavaScript | 19.1 | 17.9 | 3.43 | 3.40 | 125.5 | 126.5 | 3.28 → 3.27 |
| Go | 13.4 | 13.8 | 0.370 | 0.367 | 16.8 | 16.8 | 0.272 → 0.274 |
| Java | 361 | **195** | 2.437 | **2.282** | 165.8 | **153.2** | 1.609 → **1.496** (init 100.5 → **24.0**) |
| C# | 89.0 | **61.2** | 0.558 | 0.550 | 73.9 | 75.1 | 0.414 → **0.379** |
| Rust | 21.1 | **18.2** | 0.376 | **0.342** | 16.6 | 16.8 | 0.295 → 0.300 |

**What moved.** Python per-request time fell a third (1.38 → 0.92 ms over MCP, 0.73 → 0.53 ms native). Java's cold
init over MCP nearly halved (361 → 195 ms; native 100 → 24 ms) and its MCP p50 fell 6%. C#'s MCP cold init fell
from 89 to 61 ms. Rust's MCP p50 fell 9% on its own stdio client. **Go did not move**, as expected (#185 left it
alone). **JavaScript did not move in this harness** (3.43 → 3.40 ms): the CHANGELOG's JS figure (3.28 → ~0.55 ms)
came from a different setup and does not reproduce here, so we do not claim it. Rust's MCP RSS (16.8 MB) is
higher than the 13.5 MB of the earlier sitting in both 0.27.0 and `main`, so it is not new on `main`. [MEASURED]

### Python

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** | main · mcp 1.30.0 | native | 0.1 | **0.533** | 0.60 | **60.7** | **33 MB** |
| **toolnexus** | main · mcp 1.30.0 | MCP | **19.9** | **0.924** | 1.04 | 61.9 | **33 MB** |
| **toolnexus** (full: MCP + skill + native) | main | MCP + skill | 17.4 | 0.925 | 1.07 | 61.9 | 33 MB |
| MS Agent Framework | agent-framework-core 1.24.0 | MCP | 209.0 | 2.42 | 2.70 | 90.2 | 62 MB |
| Agno | 3.1.12 · fastmcp 4.1.6 | MCP | 177.7 | 2.85 | 3.04 | 116.2 | 125 MB |
| smolagents | 1.26.0 | MCP | 169.2 | 3.14 | 3.31 | 99.0 | — |
| OpenAI Agents | 0.24.0 | MCP | 20.3 | 4.55 | 5.08 | 102.5 | 67 MB |
| Pydantic AI | pydantic-ai-slim 2.66.0 | MCP | 37.6 | 4.72 | 5.12 | 127.6 | 109 MB |
| LangGraph (persistent session) | 1.2.20 · mcp-adapters 0.3.2 | MCP | 38.5 | 4.95 | 5.24 | 114.1 | 87 MB |
| Google ADK | 2.14.0 · litellm 1.108.2 | MCP | 24.5 | 6.17 | 7.01 | 300.9 | 300 MB |
| CrewAI | 1.15.37 | MCP | 49.7 | 10.29 | 12.24 | 249.1 | 790 MB |
| LangGraph (default, reconnect) | 1.2.20 | MCP | 61.2 | 23.41 | 24.66 | 113.7 | 87 MB |

**Win.** Over MCP we are 2.6× lower than the next framework, Microsoft Agent Framework (0.92 vs 2.42 ms), with the
lowest memory and smallest install. Our MCP cold init (19.9 ms) is now level with OpenAI Agents (20.3 ms), the
fastest starter before. No Python competitor has a native-tools row in this harness.

### JavaScript / TypeScript

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** | main | native | 0.2 | **3.27** | 3.40 | 124.8 | 1 MB + 51 MB deps |
| **toolnexus** | main | MCP | 17.9 | **3.40** | 3.60 | 126.5 | 1 MB + 51 MB deps |
| **toolnexus** (full) | main | MCP + skill | 26.1 | 3.46 | 3.65 | 129.3 | |
| Vercel AI SDK | ai 7.0.164 | native | **0.1** | 3.53 | 3.82 | 125.9 | **30 MB** |
| Vercel AI SDK | ai 7.0.164 · @ai-sdk/mcp 2.0.92 | MCP | **16.6** | 3.61 | 3.86 | 126.5 | **30 MB** |
| OpenAI Agents JS | 0.21.0 | MCP | 36.9 | 4.24 | 4.79 | 160.6 | 83 MB |
| LangGraph.js | 1.7.2 | MCP | 37.0 | 4.74 | 6.10 | 190.2 | 113 MB |
| LangChain.js | 1.6.3 · mcp-adapters 2.0.2 | native | 2.2 | 4.94 | 5.32 | 246.0 | 117 MB |
| LangChain.js | 1.6.3 · mcp-adapters 2.0.2 | MCP | 34.4 | 5.15 | 5.45 | 248.7 | 117 MB |
| Mastra | core 1.82.0 · mcp 2.8.0 | native | 0.4 | 5.49 | 6.62 | 237.5 | 217 MB |
| Mastra | core 1.82.0 · mcp 2.8.0 | MCP | 33.8 | 5.85 | 7.03 | 251.7 | 217 MB |

**Narrow win over Vercel AI SDK** on p50, native and MCP (6–7%, about 0.2 ms). Vercel still starts a little faster
(16.6 vs 17.9 ms), uses the same memory and installs smaller. Treat the p50 gap as small; we are 1.2–1.7× lower
than the rest.

### Go

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | binary |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** (native) | main | native | 0.0 | **0.274** | 0.31 | **14.5** | **11.7 MB** |
| Eino | 0.9.22 · eino-ext mcp 0.0.9 | MCP | 14.6 | **0.363** | 0.49 | 29.0 | 24.8 MB |
| **toolnexus** | main · mcp-go 0.48.0 | MCP | **13.8** | 0.367 | **0.43** | **16.8** | **11.7 MB** |
| **toolnexus** (full) | main | MCP + skill | 11.6 | 0.379 | 0.43 | 16.8 | 11.7 MB |
| langchaingo | 0.1.15 | native | 0.1 | 0.423 | 0.72 | 21.1 | 16.8 MB |
| Google ADK Go | adk 1.9.0 · go-sdk 1.4.1 ¹ | MCP | 0.1 ¹ | 0.477 | 0.69 | 24.5 | 22.7 MB |
| Genkit Go | 1.14.0 | MCP | 33.4 | 2.111 | 2.42 | 33.8 | 28.5 MB |

**Level with Eino over MCP** (0.367 vs 0.363 ms median, 1%). Our five per-round p50s (0.33–0.41 ms) straddle
Eino's (0.35–0.38 ms), so we no longer call it a loss, nor a win. We start faster (13.8 vs 14.6 ms), have a lower
p95, and use 42% less memory with half the binary. Our native row is the fastest Go row, 35% below langchaingo
native. ¹ ADK Go has no OpenAI-compatible model, so the runner adds a ~110-line adapter; it also connects to MCP
lazily, so its init leaves out the MCP connect.

### JVM (Java, Kotlin)

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** (native) | main | native | **24.0** | **1.50** | 1.98 | **118.5** | 11 MB |
| LangChain4j | 1.23.0 · mcp 1.23.0-beta33 | MCP | 253.8 | **2.17** | 2.80 | **139.6** | **8.9 MB** |
| **toolnexus** (full: MCP + `skill` + native) | main | MCP + skill | **173.6** | 2.20 | 2.64 | 155.9 | 11 MB |
| **toolnexus** | main · MCP Java SDK 2.0.0 | MCP | **195.3** | 2.28 | 2.80 | 153.2 | 11 MB |
| Spring AI | 2.0.2 · Boot 4.1.2 | MCP | 1224 ⁴ | 3.30 | 4.79 | 567.2 | 123 MB jar |
| Spring AI + SkillsTool | 2.0.2 · spring-ai-agent-utils 0.13.0 | MCP + skill | 1084 ⁴ | 3.34 | 5.01 | 579.7 | 123 MB jar |
| Koog (Kotlin) | 1.3.0 | MCP | 279.1 | 4.10 | 5.25 | 178.2 | 36 MB |
| Google ADK Java | 1.14.0 + LangChain4j bridge ² | MCP | 312.5 ² | 5.01 | 6.55 | 239.3 | 174 MB |

**Still behind LangChain4j over MCP on p50 and memory, but the gap shrank** from 37% (2.97 vs 2.16 ms in the
earlier quiet sitting) to **5%** (2.28 vs 2.17 ms), with the same p95. We now **start faster** than LangChain4j
(195 vs 254 ms) and are the fastest-starting JVM MCP row; we still use 10% more memory (153 vs 140 MB). We beat
Spring AI at about 27% of its memory, Koog and ADK Java. **Skills:** our `full` row (MCP + skill + one native
tool) is lower than Spring AI + SkillsTool (2.20 vs 3.34 ms) at 27% of the memory; both advertise the shared
`hello-world` skill and the scenario does not call it. No JVM competitor has a native row. ² ADK Java connects to
MCP lazily. ⁴ Spring's init is Spring Boot context start including MCP discovery.

### .NET (C#)

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** (native) | main | native | **12.2** | **0.379** | **0.45** | **63.1** | **4.0 MB** |
| Semantic Kernel | 1.82.0 | native | 49.8 | 0.458 | 0.58 | 76.6 | 9.5 MB |
| Microsoft.Extensions.AI | 10.11.0 | native | 29.9 | 0.488 | 0.58 | 74.7 | 7.9 MB |
| **toolnexus** | main · ModelContextProtocol 1.4.0 | MCP | **61.2** | **0.550** | **0.64** | **75.1** | **4.0 MB** |
| **toolnexus** (full) | main | MCP + skill | 83.6 | 0.581 | 0.66 | 78.9 | 4.0 MB |
| MS Agent Framework | Microsoft.Agents.AI 1.28.0 · MCP 2.4.0 | MCP | 88.5 | 0.653 | 0.78 | 85.7 | 12 MB |

**Win on native tools** against Semantic Kernel and MEAI (17% and 22% lower p50, lowest init and RSS), and **a win
on MCP** against Microsoft Agent Framework (0.550 vs 0.653 ms, 16% lower, less memory). **The MCP cold-init loss is
gone:** 61.2 ms against MAF's 88.5 ms (it was 121.6 vs 101.8 ms in the earlier sitting).

### Elixir

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB |
|---|---|---|--:|--:|--:|--:|
| **toolnexus** | main | native | 8.6 | **0.351** | 0.41 | 111.5 |
| **toolnexus** | main (in-house MCP) | MCP | 29.6 | 0.438 | 0.51 | 114.7 |
| brainlid/langchain ⁵ | 0.15.8 | native | 0.0 | 0.442 | 0.53 | 111.7 |

**Win.** toolnexus is 21% lower when both run native tools. brainlid/langchain has no MCP client, so it has no
MCP row. ⁵ re-run after the quiet window at 1-min load 5.1 (its deps needed a fetch).

### Clojure

| framework | host | tools | init ms | p50 ms | p95 ms | RSS MB |
|---|---|---|--:|--:|--:|--:|
| toolnexus main · koine 0.11.0 | cljgo 0.9.0 binary | MCP | 27 | 6.9 | 7.0 | 35.0 |
| toolnexus main · koine 0.11.0 | cljgo 0.9.0 binary | MCP + skill | 19 | 7.4 | 7.5 | 35.2 |
| toolnexus main · koine 0.11.0 | JVM (Clojure 1.12.5) | MCP | 25 | 7.8 | 9.6 | 526.5 |
| toolnexus main | cljgo | native | 0 | 1.4 | 1.5 | 33.6 |
| toolnexus main | JVM | native | 1 | 1.8 | 3.7 | 679.8 |

**No competitor exists, and this is still our slowest port.** The MCP path costs about 5.5 ms more than native
tools. The cause is a polling read loop in our in-house MCP client; the fix is deferred and tracked in
[issue #168](https://github.com/muthuishere/toolnexus/issues/168), so these numbers do not include it.

### Rust

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | binary |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** (native) | main | native | 0.1 | **0.300** | 0.34 | **11.8** | 18.3 MB |
| **toolnexus** | main · own stdio MCP client | MCP | 18.2 | **0.342** | **0.39** | 16.8 | 18.3 MB |
| **toolnexus** (full) | main | MCP + skill | **11.7** | 0.363 | 0.46 | 16.8 | 18.3 MB |
| AutoAgents | 0.4.0 | MCP | 19.0 | 0.368 | 0.44 | **13.2** | — |
| swiftide | 0.34.0 · rmcp 2.2.0 ³ | MCP | 16.2 | 0.436 | 0.55 | 16.9 | 17.5 MB |
| rig | rig-core 0.46.0 · rmcp 3.9.0 | MCP | 21.7 | 0.437 | 0.51 | 23.0 | **15.7 MB** |

**The AutoAgents loss is gone on latency:** over MCP we are now 7% lower (0.342 vs 0.368 ms), after `main` moved
stdio MCP servers onto our own client (rmcp stays for HTTP servers and the serve side). We beat rig and swiftide by
about 22%. **AutoAgents still uses less memory** (13.2 vs 16.8 MB), and swiftide starts a little faster over MCP
(16.2 vs 18.2 ms). Our binary is larger than rig's. ³ swiftide builds a fresh agent inside each timed request,
because its agents keep history.

### Could not run, and why

smolagents and AutoAgents now run (above): the keep-alive mock from #165 handles smolagents' role-`user` tool
results and `final_answer` stop, and stops sending the duplicate `input_tokens` key AutoAgents rejected.

| framework | version | reason |
|---|---|---|
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
3. **JVM MCP overhead.** We still lose to LangChain4j on the flagship MCP path (2.17 vs 2.28 ms p50, 140 vs
   153 MB), though the gap fell from 37% to 5% and we now start faster. Go is level with Eino. [MEASURED]
4. **Rust memory vs AutoAgents** (16.8 vs 13.2 MB RSS over MCP). Latency is now ours (0.342 vs 0.368 ms). [MEASURED]
5. **JS request path.** The keep-alive change did not show up in this harness; per-request time is where it was. [MEASURED]
6. **Clojure MCP path** at 6.9–7.8 ms against 1.4–1.8 ms native: the polling read loop in our in-house MCP
   client, fix deferred to [issue #168](https://github.com/muthuishere/toolnexus/issues/168). [MEASURED]
7. **Eval story.** Five competitors now ship eval *runners* (`pydantic_evals`, `adk eval`, `crewai eval`,
   FoundryEvals, LangChain4j judges). We ship a typed classifier and judge but no runner over a dataset. [FROM DOCS]
8. **Rust Gemini client style**, the last named gap in the Rust port. [FROM DOCS]

We deliberately do **not** list graph orchestration or a managed runtime. They are out of scope for
a library (ADRs 0016/0017). [QUALITATIVE]
