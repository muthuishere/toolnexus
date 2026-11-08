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
> The feature matrix was checked on **2026-11-07**. The performance tables are a quiet re-run on **2026-11-08** (load < 4, keep-alive mock) of toolnexus `main` (the 0.27.0 candidate) next to 0.26.0 and every competitor. Frameworks ship weekly; re-verify before quoting.

## What we do NOT claim

> - **Not the fastest everywhere.** Eino beats us in Go over MCP, LangChain4j beats us on the JVM,
>   AutoAgents edges us in Rust over MCP, and our .NET MCP cold init is still slower than Microsoft
>   Agent Framework's. [MEASURED]
> - **Not a Java speed-up yet.** The 0.27.0 candidate starts faster than 0.26.0 in seven ports, but our
>   quiet re-run did not show #171's Java cold-start gain. [MEASURED]
> - **Clojure is our slowest port**: 6.9–7.8 ms per request over MCP, ~17× the Rust port; the fix is
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
>   ship that; we don't, by design. Retrieval is our sibling project
>   [citenexus](https://muthuishere.github.io/citenexus/): evidence-first RAG that answers only from
>   cited quotes and abstains when the evidence is weak. Today you plug it into toolnexus as a tool
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

## Performance — fake model, quiet re-run 2026-11-08

**Method** (unchanged from the [performance page](/toolnexus/performance/)): every framework points at the
same deterministic OpenAI-compatible mock and the same stdio MCP server. Scenario: *"What's the weather in
Paris and what is 2+2?"*, which means 2 LLM round-trips and 2 parallel tool calls. **N = 30 runs after 5
warmup** per row. Peak RSS comes from `/usr/bin/time -l`. Cold init is measured in-process, once per process, so
it is the noisiest column. $0 spent. [MEASURED]

**This sitting** (2026-11-08, 04:00–04:20 IST): the whole suite in one pass, every row started only when the
1-minute load average was **below 4** on 18 cores (the harness waited ~28 minutes for that; one row, brainlid/langchain,
was re-run afterwards at load 5.1 and is marked). The mock now speaks **HTTP/1.1 keep-alive** (#165), so
frameworks that pool connections reuse them as they would against a real provider. toolnexus rows are `main`
at `f6edfbeb`, the 0.27.0 candidate. The JS runners start their own in-process mock, so JS absolute numbers are
higher than the other languages'; compare JS rows with each other. Earlier sittings (2026-11-07, and the busy
2026-11-08 native/skills sitting) stay in `results-2026-11.json` under their own `run` labels.

**Machine:** Apple M5 Pro (18 cores, 48 GB), macOS 26.4 · Go 1.26.3 · OpenJDK 26.0.1 (Java 21 toolchain) ·
.NET 10.0.301 · Node 24.18.0 · CPython 3.11.15 · Elixir 1.20.2/OTP 29 · rustc 1.99.0 `--release` ·
Clojure CLI 1.12.5 + cljgo 0.9.0.

### 0.26.0 → main (0.27.0 candidate), same sitting

Each 0.26.0 row was built from tag `v0.26.0` with `main`'s runner source and run right next to `main`'s row
(MCP config; init ms / p50 ms / RSS MB). Changes on `main` since 0.26.0 that touch these numbers:
[#167](https://github.com/muthuishere/toolnexus/pull/167) reuses the stdio `server/discover` probe child instead of
spawning the MCP server twice (all ports), [#166](https://github.com/muthuishere/toolnexus/pull/166) lazy-loads
JS modules and cuts Go per-turn allocations, [#171](https://github.com/muthuishere/toolnexus/pull/171) defers Java's
schema validator and C#'s `HttpClient`, and [#165](https://github.com/muthuishere/toolnexus/pull/165) is the
keep-alive mock both rows share. [MEASURED]

| port | 0.26.0 init | main init | 0.26.0 p50 | main p50 | 0.26.0 RSS | main RSS |
|---|--:|--:|--:|--:|--:|--:|
| Python | 54.1 | **37.5** | 1.32 | 1.45 | 73.7 | **71.8** |
| JavaScript | 33.1 | **19.5** | 3.39 | 3.34 | 133.7 | **127.0** |
| Go | 28.8 | **13.9** | 0.341 | 0.356 | 17.4 | **16.8** |
| Java | **413** | 462 | **2.64** | 2.97 | 174.5 | **161.6** |
| C# | 135.7 | **121.6** | 0.530 | 0.522 | 79.8 | **74.4** |
| Elixir | 49.4 | **29.6** | 0.446 | 0.438 | 117.7 | **114.7** |
| Clojure (cljgo) | 32 | **27** | 6.8 | 6.9 | 35.0 | 35.0 |
| Clojure (JVM) | 44 | **25** | 8.0 | 7.8 | **473.7** | 526.5 |
| Rust | 27.1 | **13.6** | 0.399 | 0.395 | 16.8 | **13.5** |

**What moved.** Cold init over MCP dropped 14–20 ms in Python, JS, Go, Elixir, Rust and Clojure: that is the second
MCP-server spawn #167 removed (our test server is Python, so one spawn costs about that much). Per-request p50 did
not move outside noise in any port, which is expected: none of these changes touch the request loop. Native-tool
rows are unchanged within noise in every port (raw rows in the JSON). **Java did not improve in this sitting**:
`main`'s init (462 vs 413 ms) and p50 (2.97 vs 2.64 ms) came out *worse* than 0.26.0's, with lower RSS. Init is one
sample per process, so we do not claim a Java regression either, but we cannot claim #171's Java cold-start win from
this run. C# init fell 14 ms. [MEASURED]

### Python

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** | main · mcp 1.30.0 | native | 0.1 | **0.84** | 2.44 | **61.0** | **33 MB** |
| **toolnexus** | main · mcp 1.30.0 | MCP | 37.5 | **1.45** | 1.60 | 71.8 | **33 MB** |
| **toolnexus** (full: MCP + skill + native) | main | MCP + skill | 25.7 | 1.39 | 1.54 | 61.6 | 33 MB |
| MS Agent Framework | agent-framework-core 1.24.0 | MCP | 237.6 | 2.77 | 3.13 | 90.1 | 62 MB |
| Agno | 3.1.12 · fastmcp 4.1.6 | MCP | 206.5 | 3.35 | 3.96 | 116.4 | 125 MB |
| smolagents | 1.26.0 | MCP | 236.7 | 3.67 | 3.94 | 99.0 | — |
| OpenAI Agents | 0.24.0 | MCP | **23.7** | 5.22 | 5.61 | 102.5 | 67 MB |
| Pydantic AI | pydantic-ai-slim 2.66.0 | MCP | 46.0 | 5.57 | 6.91 | 127.6 | 109 MB |
| LangGraph (persistent session) | 1.2.20 · mcp-adapters 0.3.2 | MCP | 43.5 | 5.63 | 6.03 | 114.5 | 87 MB |
| Google ADK | 2.14.0 · litellm 1.108.2 | MCP | 24.4 | 7.08 | 7.35 | 301.0 | 300 MB |
| CrewAI | 1.15.37 | MCP | 57.9 | 11.91 | 20.99 | 250.2 | 790 MB |
| LangGraph (default, reconnect) | 1.2.20 | MCP | 69.8 | 25.03 | 26.33 | 113.5 | 87 MB |

**Win.** Over MCP we are 1.9× lower than the next framework, Microsoft Agent Framework, with the lowest memory and
smallest install. smolagents now runs (the keep-alive mock handles its role-`user` tool results and `final_answer`
stop) and lands mid-table. Our cold init (37.5 ms) is still not the lowest: OpenAI Agents and ADK start faster.
No Python competitor has a native-tools row in this harness.

### JavaScript / TypeScript

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** | main | native | 0.2 | **3.21** | 3.43 | 124.5 | 1 MB + 51 MB deps |
| **toolnexus** | main | MCP | 19.5 | **3.34** | 3.53 | 127.0 | 1 MB + 51 MB deps |
| **toolnexus** (full) | main | MCP + skill | 44.0 | 3.37 | 3.56 | 129.8 | |
| Vercel AI SDK | ai 7.0.164 | native | **0.1** | 3.50 | 3.87 | 126.5 | **30 MB** |
| Vercel AI SDK | ai 7.0.164 · @ai-sdk/mcp 2.0.92 | MCP | **17.3** | 3.57 | 3.87 | **125.9** | **30 MB** |
| OpenAI Agents JS | 0.21.0 | MCP | 38.5 | 4.19 | 4.89 | 161.0 | 83 MB |
| LangGraph.js | 1.7.2 | MCP | 36.1 | 4.97 | 6.21 | 190.6 | 113 MB |
| LangChain.js | 1.6.3 · mcp-adapters 2.0.2 | native | 2.3 | 5.03 | 5.68 | 248.3 | 117 MB |
| LangChain.js | 1.6.3 · mcp-adapters 2.0.2 | MCP | 35.9 | 5.61 | 6.24 | 248.6 | 117 MB |
| Mastra | core 1.82.0 · mcp 2.8.0 | native | 0.4 | 5.71 | 7.19 | 247.2 | 217 MB |
| Mastra | core 1.82.0 · mcp 2.8.0 | MCP | 35.1 | 6.12 | 7.19 | 263.0 | 217 MB |

**Narrow win over Vercel AI SDK** on p50, native and MCP (about 7%, 0.2–0.3 ms), and #166 halved our MCP init to
19.5 ms. Vercel still starts a little faster (17.3 ms), uses about 1 MB less memory and installs smaller. Treat the
p50 gap as small; we are 1.3–1.8× lower than the rest.

### Go

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | binary |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** (native) | main | native | 0.0 | **0.266** | 0.29 | **14.6** | **11.7 MB** |
| Eino | 0.9.22 · eino-ext mcp 0.0.9 | MCP | 14.9 | **0.326** | 0.46 | 31.6 | 24.8 MB |
| **toolnexus** (full) | main | MCP + skill | 13.0 | 0.353 | 0.38 | 16.8 | 11.7 MB |
| **toolnexus** | main · mcp-go 0.48.0 | MCP | **13.9** | 0.356 | 0.41 | **16.8** | **11.7 MB** |
| langchaingo | 0.1.15 | native | 0.1 | 0.382 | 0.61 | 20.6 | 16.8 MB |
| Google ADK Go | adk 1.9.0 · go-sdk 1.4.1 ¹ | MCP | 0.2 ¹ | 0.448 | 0.73 | 24.4 | 22.7 MB |
| Genkit Go | 1.14.0 | MCP | 33.7 | 2.081 | 2.29 | 33.3 | 28.5 MB |

**Still a loss on MCP latency to Eino** (0.356 vs 0.326 ms, 9%). We now start faster than Eino (13.9 vs 14.9 ms)
and use 47% less memory with half the binary. Our native row is the fastest Go row and beats langchaingo native by
30%. ¹ ADK Go has no OpenAI-compatible model, so the runner adds a ~110-line adapter; it also connects to MCP
lazily, so its init leaves out the MCP connect.

### JVM (Java, Kotlin)

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** (native) | main | native | **101.5** | **1.59** | 2.07 | **117.1** | 11 MB |
| LangChain4j | 1.23.0 · mcp 1.23.0-beta33 | MCP | 249.8 | 2.16 | 3.13 | 142.8 | **8.9 MB** |
| **toolnexus** (full: MCP + `skill` + native) | main | MCP + skill | 341.6 | 2.50 | 3.19 | 169.9 | 11 MB |
| **toolnexus** | main · MCP Java SDK 2.0.0 | MCP | 462.4 | 2.97 | 4.61 | 161.6 | 11 MB |
| Spring AI + SkillsTool | 2.0.2 · spring-ai-agent-utils 0.13.0 | MCP + skill | 1109 ⁴ | 3.34 | 5.09 | 604.7 | 123 MB jar |
| Spring AI | 2.0.2 · Boot 4.1.2 | MCP | 1212 ⁴ | 3.37 | 4.59 | 560.5 | 123 MB jar |
| Koog (Kotlin) | 1.3.0 | MCP | 299.4 | 4.38 | 5.93 | 198.3 | 36 MB |
| Google ADK Java | 1.14.0 + LangChain4j bridge ² | MCP | 338.5 ² | 5.12 | 6.88 | 231.5 | 174 MB |

**Still a loss to LangChain4j over MCP** on init, p50 and RSS (2.97 vs 2.16 ms). We beat Spring AI with about 28%
of its memory, Koog and ADK Java. **Skills:** our `full` row (MCP + skill + one native tool) is lower than Spring
AI + SkillsTool (2.50 vs 3.34 ms) at 28% of the memory; both advertise the shared `hello-world` skill and the
scenario does not call it. No JVM competitor has a native row. ² ADK Java connects to MCP lazily. ⁴ Spring's init
is Spring Boot context start including MCP discovery.

### .NET (C#)

| framework | version | tools | init ms | p50 ms | p95 ms | RSS MB | install |
|---|---|---|--:|--:|--:|--:|---|
| **toolnexus** (native) | main | native | **12.0** | **0.369** | **0.48** | **62.9** | **4.0 MB** |
| Semantic Kernel | 1.82.0 | native | 58.1 | 0.426 | 0.50 | 76.7 | 9.5 MB |
| Microsoft.Extensions.AI | 10.11.0 | native | 32.7 | 0.456 | 0.65 | 75.1 | 7.9 MB |
| **toolnexus** | main · ModelContextProtocol 1.4.0 | MCP | 121.6 | **0.522** | 0.65 | **74.4** | **4.0 MB** |
| **toolnexus** (full) | main | MCP + skill | 118.9 | 0.558 | 0.66 | 77.8 | 4.0 MB |
| MS Agent Framework | Microsoft.Agents.AI 1.28.0 · MCP 2.4.0 | MCP | **101.8** | 0.623 | 0.78 | 86.1 | 12 MB |

**Win on native tools** against Semantic Kernel and MEAI (13% and 19% lower p50, lowest init and RSS), and **a win
on MCP** against Microsoft Agent Framework this time (0.522 vs 0.623 ms, 16% lower, less memory). On 2026-11-07 the
MCP pair was a tie, so read it as "level or ahead". We **still lose on MCP cold init** (121.6 vs 101.8 ms), even
after #167/#171 took 14 ms off.

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
| **toolnexus** (native) | main | native | 0.1 | **0.285** | 0.31 | **11.1** | 18.3 MB |
| AutoAgents | 0.4.0 | MCP | 16.7 | **0.365** | 0.45 | **13.3** | — |
| **toolnexus** (full) | main | MCP + skill | 12.2 | 0.393 | 0.45 | 13.8 | 18.3 MB |
| **toolnexus** | main · rmcp 3.9.0 | MCP | 13.6 | 0.395 | 0.44 | 13.5 | 18.3 MB |
| rig | rig-core 0.46.0 · rmcp 3.9.0 | MCP | 16.6 | 0.436 | 0.49 | 22.9 | **15.7 MB** |
| swiftide | 0.34.0 · rmcp 2.2.0 ³ | MCP | **13.3** | 0.461 | 0.51 | 16.8 | 17.5 MB |

**New loss: AutoAgents is lower over MCP** (0.365 vs 0.395 ms, 8%) and level on memory. We still beat rig (9%)
and swiftide, and our cold init is now level with swiftide and below rig and AutoAgents. Our binary is larger
than rig's. toolnexus-rust native, at 0.285 ms, ties toolnexus-go native as the fastest rows in the run.
³ swiftide builds a fresh agent inside each timed request, because its agents keep history.

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
3. **JVM and Go overhead.** We still lose to LangChain4j (2.16 vs 2.97 ms) and Eino (0.326 vs 0.356 ms) on the
   flagship MCP path, and Java's cold init did not improve in the quiet re-run. Both are profile-and-fix jobs. [MEASURED]
4. **Rust MCP latency vs AutoAgents** (0.365 vs 0.395 ms). Small, but a loss. [MEASURED]
5. **C# MCP cold init** of 121.6 ms against Microsoft Agent Framework's 101.8 ms. [MEASURED]
6. **Clojure MCP path** at 6.9–7.8 ms against 1.4–1.8 ms native: the polling read loop in our in-house MCP
   client, fix deferred to [issue #168](https://github.com/muthuishere/toolnexus/issues/168). [MEASURED]
7. **Eval story.** Five competitors now ship eval *runners* (`pydantic_evals`, `adk eval`, `crewai eval`,
   FoundryEvals, LangChain4j judges). We ship a typed classifier and judge but no runner over a dataset. [FROM DOCS]
8. **Rust Gemini client style**, the last named gap in the Rust port. [FROM DOCS]

We deliberately do **not** list graph orchestration or a managed runtime. They are out of scope for
a library (ADRs 0016/0017). [QUALITATIVE]
