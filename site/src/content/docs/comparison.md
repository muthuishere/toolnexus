---
title: Comparison — Spring AI, LangGraph, ADK
description: >-
  An honest, metrics-first capability and standards-compliance comparison. Measured where possible, cited otherwise, and explicit about where toolnexus is only partial.
---

> A metrics-first, deliberately honest comparison. Where a number is **measured** from this
> repository it is tagged **[MEASURED]**; where it comes from a competitor's own docs it is
> **[FROM DOCS]** with a link; a judgement call is **[QUALITATIVE]**. We do **not** publish
> latency/throughput numbers — we did not run a controlled head-to-head, and inventing one would
> defeat the entire purpose of this page.
>
> **Read the [What we do NOT claim](#what-we-do-not-claim--partial-compliance) box first.** toolnexus
> is a small, vendor-neutral, five-language *library*. On raw MCP/A2A protocol surface, **Spring AI
> and Google ADK cover more of the spec than we do.** Our edge is unification, cross-language parity,
> and footprint — not being the deepest protocol implementation. If you need graph orchestration,
> a managed runtime, or the fullest MCP server, one of the others is the better tool, and we say so
> below.
>
> Versions referenced: toolnexus **0.8.0** (all five ports; `js/package.json`, `python/pyproject.toml`,
> `golang/go.mod`, `java/build.gradle`, `csharp/src/Toolnexus/Toolnexus.csproj`). Competitor facts
> were checked in **July 2026**; frameworks move fast — re-verify before quoting.

---

## Summary matrix

Legend: ✅ full / first-class · 🟡 partial / via-adapter / community · ❌ none. Numbers are measured
only where tagged.

| Capability / metric | toolnexus | Spring AI | LangGraph | Google ADK |
|---|---|---|---|---|
| **MCP client** (stdio) | ✅ | ✅ | 🟡 via `langchain-mcp-adapters` | ✅ |
| **MCP client** (streamable-HTTP / SSE) | ✅ | ✅ | 🟡 | ✅ |
| **MCP elicitation** (server→client input) | 🟡 form mode all ports; URL mode where SDK supports | 🟡 | ❌ | 🟡 |
| **MCP authorization** (static + interactive) | 🟡 static bearer (`${ENV}`) **+ interactive URL authz** via §10 suspension; no built-in OAuth token client | 🟡 | ❌ | 🟡 |
| **MCP server** (expose your tools) | 🟡 streamable-HTTP only; no stdio, no resources/prompts/sampling | ✅ stdio + streamable-HTTP + stateless, resources/prompts | ❌ | ✅ |
| **A2A outbound** (call remote agents) | ✅ (submit→poll subset) | ✅ (A2A Java SDK) | 🟡 platform/community | ✅ native |
| **A2A inbound** (be an agent, Agent Card) | ✅ (subset) | ✅ | 🟡 (LangGraph Platform) | ✅ native |
| **A2A streaming / push / auth** | ❌ (v1 subset) | ✅ (SDK) | 🟡 | ✅ |
| **Agent skills** (`SKILL.md`, progressive disclosure) | ✅ byte-exact loader | 🟡 community `spring-ai-agent-utils` | ✅ Deep Agents | 🟡 emerging |
| **Native function tools** | ✅ | ✅ | ✅ | ✅ |
| **HTTP / REST tools** | ✅ (+ best-effort OpenAPI) | ✅ | ✅ | ✅ |
| **Built-in shell/file toolset** | ✅ 10 tools, on by default | 🟡 community | 🟡 Deep Agents | 🟡 code-exec |
| **Human-in-the-loop / suspension** | ✅ `Pending`/`waitFor`, durable | 🟡 | ✅ (LangGraph's strength) | 🟡 |
| **Graph / state-machine orchestration** | ❌ (linear loop only) | 🟡 | ✅ **its whole point** | ✅ workflow agents |
| **Managed runtime / deploy / eval** | ❌ (it's a library) | 🟡 Spring Boot ecosystem | 🟡 LangGraph Platform | ✅ **Vertex AI Agent Engine** |
| **Vendor-neutral (base-URL, any provider)** | ✅ OpenAI + Anthropic styles, 3 schema adapters | ✅ 20+ providers | ✅ | 🟡 model-agnostic but Google-centric |
| **Languages** | **5, byte-identical** (JS · Python · Go · Java · C#) [MEASURED] | JVM only | Python + JS/TS | Python · Go · Java · TS (parity/maturity vary) |
| **Direct runtime deps (minimal agent)** | **2–3 per port** [MEASURED] | Spring Boot + starters (heavy) [QUALITATIVE] | LangChain tree (heavy) [QUALITATIVE] | ADK + Google libs [QUALITATIVE] |
| **Single-binary deploy** | ✅ Go port [MEASURED] | ❌ | ❌ | ❌ |

---

## The honesty centerpiece: standards compliance

We asked the same question of all four: *for MCP and A2A, which parts are actually implemented?*

### toolnexus — a deliberate, spec-pinned subset

Grounded in [`SPEC.md`](../SPEC.md) (§2 MCP, §7A/B/C A2A + MCP inbound, §10 suspension):

- **MCP client** — stdio + streamable-HTTP with **SSE fallback**; `${ENV}` header auth; per-server
  failure isolation; 30 s default timeout; ctx-aware cancellation (`SPEC.md §2`). **Full for the
  client tool-use path.**
- **MCP elicitation** — bridged onto the one suspension primitive: **form mode ships in all five
  ports; URL mode ships where the port's MCP SDK supports it** (`SPEC.md §2`, "Elicitation bridge").
  This is a genuine 🟡 — it's not uniformly complete across ports.
- **MCP authorization** — **partial, and more than a static token.** Two real mechanisms
  [MEASURED against the repo]: (1) **static** bearer/API-key auth via remote-server `headers` with
  `${ENV_VAR}` expansion (never logged, `SPEC.md §2`); (2) **interactive authorization** — an MCP
  server's `elicitation/create` in **URL mode** is bridged onto the one §10 suspension primitive as a
  `Request{kind:"authorization", url}`, and the host's `waitFor` completes it out-of-band (open the
  URL → user authorizes, including driving an **OAuth consent flow**) → the tool resumes with the
  answer (`SPEC.md §2` elicitation bridge + `§10`; `elicitationToRequest` in `js/src/mcp.ts:28-31`,
  `ElicitationToRequest` in `golang/mcp.go:44-46`). **What we do *not* have:** a built-in automatic
  **OAuth 2.0 client** that runs the MCP authorization-spec token handshake/refresh for you — the
  host/user completes auth; toolnexus bridges the *requirement*, it does not negotiate tokens. And per
  `SPEC.md §2`, **URL mode ships where the port's MCP SDK supports it; form mode ships in all five
  ports.** So: 🟡, not ❌.
- **MCP server (inbound)** — expose the toolkit as an MCP server, but **streamable-HTTP only**;
  **stdio, resources, prompts, sampling, and completion are out of scope in v1** (`SPEC.md §7C`
  "Deferred"). 🟡 — narrower than Spring AI / ADK on purpose.
- **A2A** — "a genuine, minimal **subset** of real A2A, verified against `a2a-python`": JSON-RPC 2.0,
  Agent Card at `/.well-known/agent-card.json`, `SendMessage` → poll `GetTask`. Task lifecycle
  `submitted|working|completed|failed|canceled`, plus `input-required` when a run suspends
  (`SPEC.md §7A/§7B/§10`). **No streaming, push, gRPC, or auth in core.** 🟡, and we label it a subset
  in our own README.

### Spring AI — fuller MCP + A2A via official SDKs [FROM DOCS]

- **MCP**: client starters cover **STDIO, Servlet + WebFlux Streamable-HTTP, Stateless
  Streamable-HTTP, and SSE**; a **server** boot starter exposes tools/resources/prompts. Broader
  transport + server coverage than toolnexus.
  ([MCP overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html),
  [server starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html))
- **A2A**: integrates the **A2A Java SDK** through Boot autoconfiguration — Agent Card, expose agents
  as A2A servers, richer than our submit→poll subset.
  ([Spring AI A2A pattern](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/))
- **Agent skills** are a **community** module (`spring-ai-community/spring-ai-agent-utils`,
  "Claude-Code-inspired tools and agent skills"), not core.
  ([repo](https://github.com/spring-ai-community/spring-ai-agent-utils))

### LangGraph — MCP by adapter, A2A mostly via platform [FROM DOCS]

- **MCP**: **client-side, via `langchain-mcp-adapters`** — converts MCP tools to LangChain tools,
  multi-server client. No first-class MCP *server*.
  ([langchain-mcp-adapters](https://github.com/langchain-ai/langchain-mcp-adapters))
- **A2A**: native in **LangGraph Platform / LangSmith** (managed); self-hosted OSS relies on community
  adapters/examples.
  ([A2A in Agent Server](https://docs.langchain.com/langsmith/server-a2a),
  [community example](https://github.com/n-sviridenko/langgraph-a2a-mcp-example))
- **Skills**: **Deep Agents** supports `SKILL.md`-style progressive disclosure.
  ([Deep Agents skills](https://docs.langchain.com/oss/python/deepagents/skills))

### Google ADK — A2A is native, MCP client + server [FROM DOCS]

- **MCP**: agent can be an **MCP client**, and ADK tools can be **exposed via an MCP server**.
- **A2A**: "**every agent supports A2A by default**" — the most turnkey A2A of the four.
  ([ADK/A2A/MCP codelab](https://codelabs.developers.google.com/codelabs/currency-agent),
  [ADK + A2A enhancements](https://developers.googleblog.com/agents-adk-agent-engine-a2a-enhancements-google-io/))

**Honest takeaway:** on protocol *depth*, ranking is roughly **Spring AI ≈ ADK > toolnexus > LangGraph
(OSS)** for MCP-server + A2A completeness. toolnexus is intentionally a lean, spec-pinned subset that
is **identical in five languages** — that's the trade.

---

## Footprint [MEASURED for toolnexus]

Measured from the manifests + a real Gradle resolve in this repo. Competitor weights are
**[QUALITATIVE]** — we did **not** resolve their trees, and we won't fake a number.

| Port | Direct runtime deps | Notes |
|---|---|---|
| JS/TS | **2** — `@modelcontextprotocol/sdk`, `yaml` | `js/package.json` [MEASURED] |
| Python | **2** — `mcp`, `pyyaml` | `python/pyproject.toml` [MEASURED] |
| Go | **3** direct + 3 indirect (**6 modules total**) | `golang/go.mod` [MEASURED] |
| Java | **3** declared — `mcp:2.0.0`, `jackson-databind`, `snakeyaml`; **~15–18 jars on the resolved runtime classpath** | `java/build.gradle` + `./gradlew dependencies --configuration runtimeClasspath` [MEASURED] |
| C# | **2** — `ModelContextProtocol`, `YamlDotNet` | `Toolnexus.csproj` [MEASURED] |

The Java runtime classpath resolves to roughly **15–18 artifacts** (MCP SDK core + json + reactor +
jackson 2/3 + slf4j + snakeyaml) [MEASURED]. For comparison, a Spring AI agent is a **Spring Boot**
application (auto-configuration, DI, starters, the reactor/web stack) — materially heavier — and a
LangGraph agent pulls the **LangChain** dependency tree; both are well-documented as ecosystem-scale
rather than single-library installs [QUALITATIVE / FROM DOCS]. **Go's single-binary story is the
sharpest footprint contrast:** `go build ./cmd/toolnexus` yields one static binary with no runtime,
which none of the three competitors offer [MEASURED].

---

## Language coverage / parity — the genuine differentiator

- **toolnexus: 5 languages, byte-identical.** JS · Python · Go · Java · C#, pinned by
  [`SPEC.md §0`](../SPEC.md) — the `skill`-tool loader output and the `metrics()` Prometheus text are
  **byte-for-byte** across ports, enforced by hermetic conformance tests against shared
  `examples/` fixtures. [MEASURED / structural]
- **Spring AI: JVM only.** Deep and idiomatic there; nothing outside the JVM. [FROM DOCS]
- **LangGraph: Python + JS/TS.** No Go, no JVM, no .NET. [FROM DOCS]
- **Google ADK: Python, Go, Java, TypeScript.** Actually *four* languages, all **1.0 GA** (April
  2026) — but **near-parity, not byte-identical**, and Python-first (maturity skews Python > Go >
  Java > TS). See the [per-language ADK table](#adk-per-language-vs-our-matching-ports-from-docs).
  [FROM DOCS]

**Fair framing:** ADK reaches a similar language *count*, and several other frameworks span multiple
languages too — **we did not invent this category** (see the [next section](#who-else-already-spans-multiple-languages-the-real-field)).
What no competitor offers is a **single contract that keeps five ports substitutable** — same config,
same tool naming, byte-identical skill-loader output and `metrics()` text, held by a shared
conformance suite. If you ship the same agent behavior into a TS frontend service, a Python data
pipeline, a Go binary, a Spring service, and a .NET app, that *tested* parity is the reason to pick
toolnexus.

---

## Who else already spans multiple languages — the real field

**We did not invent the multi-language agent framework.** Several mature projects already ship in more
than one language. The honest, defensible toolnexus claim is narrower and testable: **byte-identical
conformance across five languages, pinned by a shared spec ([`SPEC.md §0`](../SPEC.md)) and verified
by a shared conformance suite** — not merely "an SDK exists in language X." Almost everyone else ships
**separate idiomatic SDKs with best-effort, flagship-first parity and documented feature gaps.** Here
is the field, positioned fairly. [FROM DOCS]

| Framework | Languages | Cross-language promise | Byte-identical + shared conformance suite? |
|---|---|---|---|
| **toolnexus** | JS · Python · Go · Java · C# (**5**) | One `SPEC.md` contract; shared `examples/` fixtures | ✅ **Yes** — the explicit design goal [MEASURED / structural] |
| **Microsoft Semantic Kernel** | C# · Python · Java (**3**) | Aims for parity; **not at complete parity** — per-language feature tables, Java lags, GA-parity only for C#+Python | 🟡 Separate idiomatic SDKs, documented gaps — **not byte-identical** |
| **Google ADK** | Python · Go · Java · TS (**4**) | "Near-complete parity," shared runtime contract, Python-first | 🟡 Separate SDKs, maturity skew — no byte-exact conformance suite |
| **LangChain / LangGraph** | Python · JS/TS (**2**) | Two ecosystems; JS trails Python | 🟡 Separate ports, partial parity |
| **LlamaIndex** | Python · TS (**2**) | LlamaIndexTS "aims to support all core features" of Python | 🟡 Separate port, partial parity |
| **OpenAI Agents SDK** | Python · JS/TS (**2**) | "Equal features," but two SDKs (Pydantic vs Zod validation) | 🟡 Two separate SDKs — no shared byte-exact spec |
| **Vercel AI SDK** | TS (Python = community re-impl) | TS-first; Python is a separate community project, not official parity | ❌ Effectively single-language (official) |
| **Mastra** | TS only (**1**) | — | ❌ Single-language |
| **Pydantic AI** | Python only (**1**) | — | ❌ Single-language |

**The nearest neighbor is Semantic Kernel** — three first-class languages (C#/Python/Java), 1.0+,
genuinely multi-language and enterprise-grade. But Microsoft is explicit that the SDKs are **not at
complete feature parity**: there are per-language feature tables with partial/unavailable markers, a
dedicated effort to bring **Java to parity**, and a stated GA-parity goal for **C# + Python only**
([SK supported languages](https://learn.microsoft.com/en-us/semantic-kernel/get-started/supported-languages),
[Java parity issue #10101](https://github.com/microsoft/semantic-kernel/issues/10101),
[SK overview](https://learn.microsoft.com/en-us/semantic-kernel/overview/)). That is the standard
industry posture: **separate idiomatic ports, best-effort parity, flagship language first.**

**Where that leaves toolnexus (honestly):**
- We are **not** the first or the biggest multi-language framework — SK, ADK, LangChain, LlamaIndex,
  and OpenAI Agents SDK all got there, several with far more resources and mileage.
- Our **distinct** claim is the *mechanism and the bar*, not the mere fact of multiple SDKs: a single
  shared `SPEC.md` + shared `examples/` fixtures producing **byte-identical** outputs (the skill-loader
  block and the Prometheus `metrics()` text are pinned byte-for-byte), enforced by a conformance suite
  every port must pass. To our knowledge no listed competitor makes — or tests — a byte-identical
  cross-language guarantee; they promise "available in N languages," which is a weaker, untested claim.
- The trade is **surface for strictness**: toolnexus is a small tool layer, so holding five ports
  byte-identical is tractable. SK/ADK/LangChain cover vastly more (orchestration, memory subsystems,
  eval, managed runtime) across their languages — which is exactly *why* they can't promise byte
  identity. Bigger surface, looser parity vs. smaller surface, tested parity. Pick accordingly.

---

## Time-to-first-agent / ergonomics

### toolnexus [MEASURED — from the repo]

An MCP-backed, tool-calling agent with **human-approval** built in. Two files + a short main; the
loop, skills injection, built-in tools, and suspension are already included (`README.md`;
`js/examples/pending.ts` for the `waitFor` path):

```python
# 1. mcp.json + skills/ folder on disk (the two "config" files)
# 2. one main:
tk    = await create_toolkit(mcp_config="./mcp.json", skills_dir="./skills")
agent = create_client(
    base_url="https://openrouter.ai/api/v1", style="openai", model="openai/gpt-4o-mini",
    wait_for=my_approval,   # <- human-in-the-loop: called when a tool suspends (login/approve)
)
result = await agent.run("Refund order 1234 for the customer.", tk)
```

`my_approval(request) -> Answer` is the *only* behavior you write for human-approval — open a browser,
message a channel, or forward to another agent; the engine resolves and retries the tool
transparently (`SPEC.md §10`). No graph, no builders, no runtime. `create_toolkit()` with **no config
at all** still returns a working agent (the 10 built-in tools are on by default). The identical shape
works in JS, Go, Java, and C#.

### Competitors [FROM DOCS — links, not invented LOC]

- **Spring AI**: a Spring Boot app + the MCP client starter + `application.yml` provider config; you
  inject a `ChatClient` and register tools. Idiomatic and DI-driven, but it's a Boot application, not
  a two-file script.
  ([getting started with MCP](https://docs.spring.io/spring-ai/reference/guides/getting-started-mcp.html))
- **LangGraph**: define a graph/state machine (nodes, edges, state), attach MCP tools via
  `langchain-mcp-adapters`, compile, invoke. More upfront structure — which is exactly what buys you
  the orchestration power.
  ([langchain-mcp-adapters](https://github.com/langchain-ai/langchain-mcp-adapters))
- **Google ADK**: define an `Agent`/`LlmAgent`, attach tools (incl. MCP), run via a `Runner`; deploy
  to Vertex AI Agent Engine for the managed path.
  ([ADK docs](https://docs.cloud.google.com/gemini-enterprise-agent-platform/build/adk))

We deliberately **do not** publish a "toolnexus = N lines vs competitor = M lines" table — a fair LOC
count depends on what you include, and a rigged one is exactly the dishonesty this page exists to
avoid. Follow the links and judge the minimal examples yourself.

---

## Per-competitor: where each wins

### vs. Spring AI (↔ the Java port)

**Where toolnexus wins**
- **Not JVM-locked.** Same agent in Python, Go, JS, C#, Java — byte-identical.
- **Far smaller footprint.** ~15–18 jars [MEASURED] vs a Spring Boot application.
- **Library, not a framework.** No Boot context, DI graph, or autoconfiguration to adopt; drop it
  into any `main`.
- **Built-in shell/file toolset + durable suspension** are first-class and on by default.

**Where Spring AI wins**
- **Deeper MCP** (more transports, a full server with resources/prompts) and **A2A via the official
  Java SDK**. [FROM DOCS]
- **The Spring ecosystem**: DI, Boot autoconfig, Micrometer observability, 20+ provider integrations,
  Spring Security — none of which toolnexus tries to replace.
- Battle-tested in large enterprise Java shops; far bigger community.

**Honest compliance status:** Spring AI implements **more** of MCP and A2A than we do. We win on
reach + weight, not protocol depth.

### vs. LangGraph (↔ the Python port)

**Where toolnexus wins**
- **Five languages** vs Python+JS.
- **Unified tool sources + built-in tools + A2A inbound/outbound + MCP server** in one small library;
  LangGraph's MCP is a **client-only adapter** and its A2A leans on the platform. [FROM DOCS]
- **Lighter dependency footprint** than the LangChain tree. [QUALITATIVE]

**Where LangGraph wins — decisively, on its home turf**
- **Graph / state-machine orchestration**: cycles, branches, checkpointing, **durable execution**,
  and mature **human-in-the-loop** — LangGraph's entire reason to exist. toolnexus has a **linear
  tool-calling loop with suspension**, *not* a graph engine. If your agent is a stateful workflow with
  branching and rollback, use LangGraph.
- Much larger community, ecosystem, and tutorial surface; LangSmith tracing/eval.

**Honest compliance status:** we do **not** compete on orchestration. Our suspension primitive
(`Pending`/`waitFor`) is durable and cross-language, but it is a suspend/resume seam, **not** a
checkpointed graph.

### vs. Google ADK (↔ toolnexus overall)

**Where toolnexus wins**
- **Vendor-neutral by construction** — a plain base URL + `openai`/`anthropic` style + OpenAI/
  Anthropic/Gemini **schema** adapters; ADK is model-agnostic but Google/Gemini-centric in ergonomics
  and lands best on Vertex AI. [QUALITATIVE / FROM DOCS]
- **A library with no runtime dependency** — no managed platform to deploy into; runs anywhere,
  including a single Go binary. [MEASURED]
- **Byte-identical parity** across five languages (ADK reaches four, without a parity guarantee).

**Where ADK wins**
- **Managed runtime + lifecycle**: **Vertex AI Agent Engine** for deploy/scale, plus first-class
  **eval** and debugging tooling. toolnexus ships **none** of this — it's the LLM-facing tool layer
  only. [FROM DOCS]
- **Native A2A on every agent** and a broad Google-backed tool ecosystem. [FROM DOCS]
- Backed by Google; larger org and momentum.

**Honest compliance status:** for a team standardizing on Google Cloud that wants deploy + eval + A2A
out of the box, ADK is the more complete product. toolnexus is the better fit when you want a
**thin, portable, vendor-neutral** tool layer you own end to end.

#### ADK per language vs. our matching ports [FROM DOCS]

ADK is the closest competitor on *language count* — it graduated to **1.0 GA across Python,
TypeScript, Java, and Go at Google Cloud Next, April 2026**
([ADK docs](https://google.github.io/adk-docs/)). The crux of our claim is **parity**, so the fair
question is not "how many languages" but "**does ADK behave identically across them?**" Per Google's
own framing and third-party surveys, ADK offers **near-complete feature parity with a shared runtime
contract** — but it is **separate idiomatic SDKs that mature at different rates**, Python-first, *not*
a byte-identical spec verified by a shared conformance suite.

| ADK language ↔ our port | ADK maturity / depth [FROM DOCS] | Consistency vs other ADK langs [FROM DOCS] |
|---|---|---|
| **ADK-Python** ↔ `python/` | Built first; most features, integrations, testing. The reference implementation. | The source of truth — every feature lands here first and most completely. |
| **ADK-Go** ↔ `golang/` | 1.0 (["ADK Go 1.0 Arrives"](https://developers.googleblog.com/adk-go-10-arrives/)); optimized for Gemini + concurrency. | Trails Python; "for anything beyond Gemini, Python is the only kitchen where everything is stocked." |
| **ADK-Java** ↔ `java/` | Reached 1.0 (["ADK for Java Just Hit 1.0"](https://medium.com/@sergey.prusov/googles-adk-for-java-just-hit-1-0-4293b02e8e44)); enterprise-focused, still maturing. | Trails Python; feature set converging over time, not guaranteed identical. |
| **ADK-TS** ↔ `js/` | Ported last (Dec 2025), still maturing; "same runtime contract" as Python for A2A composition ([TS guide](https://baeseokjae.github.io/posts/google-adk-typescript-guide-2026/)). | Newest; shares the runtime contract with Python but is the least complete of the four. |

**Honest read:** ADK's four-language story is real and 1.0-GA — a genuine peer to us on reach, and
*ahead* of us on managed runtime, eval, and A2A depth. Where we differ is the **parity guarantee**:
ADK promises "available in four languages with near-parity, Python-first"; toolnexus promises
"**byte-identical output across five languages, pinned by [`SPEC.md §0`](../SPEC.md) and enforced by a
shared conformance suite** against the same `examples/` fixtures." Those are different promises.
ADK's is backed by Google's scale; ours is a narrower surface held to a stricter, testable bar.
([Google's ADK 1.0 / parity framing](https://fast.io/resources/google-adk-vs-openai-agents-sdk/))

---

## What we do NOT claim / partial compliance

> **Read this before quoting anything above.**
>
> - **No performance numbers.** We publish **no** latency, throughput, or tokens/sec figures. We did
>   not run a controlled head-to-head; any such number here would be invented, so there is none.
> - **MCP authorization is partial, not absent.** Static bearer (`${ENV}` headers) **and** interactive
>   URL authorization (server URL-elicitation → §10 `kind:"authorization"` suspension → host completes
>   out-of-band, incl. OAuth consent → resume) both work (`SPEC.md §2`/§10). What's missing is a
>   built-in **OAuth 2.0 token client** (no automatic handshake/refresh) — the host completes auth, we
>   bridge the requirement. URL mode ships where the port's MCP SDK supports it; form mode in all five.
> - **MCP elicitation is partial:** **form mode in all five ports; URL mode only where the port's MCP
>   SDK supports it** — genuinely uneven across ports (`SPEC.md §2`).
> - **MCP server is streamable-HTTP only.** No stdio; **no resources, prompts, sampling, or
>   completion** (`SPEC.md §7C`). Spring AI and ADK expose more.
> - **A2A is an explicit *subset*:** Agent Card + JSON-RPC `SendMessage`/`GetTask` submit→poll. **No
>   streaming, push notifications, gRPC, or auth** in core (`SPEC.md §7A/B`). We call it a subset in
>   our own README.
> - **No graph/state-machine orchestration.** A linear tool-calling loop with durable suspension —
>   not LangGraph-style branching/checkpointing.
> - **No managed runtime / eval / deploy tooling.** No Vertex-AI-Agent-Engine equivalent. It's a
>   library; you host it.
> - **No deep DI / Spring-ecosystem / enterprise-observability integration** beyond a zero-dep
>   `on_metric` feed + a Prometheus-text endpoint.
> - **OpenAPI bulk import** is best-effort / a follow-up (`README.md` status ⏳).
> - **Younger and smaller.** Version 0.8.0, a small community, far less production mileage than
>   Spring AI / LangGraph / ADK. Fewer eyes, fewer integrations, less battle-testing.
> - **Competitor footprint numbers are [QUALITATIVE], not measured.** We measured *our* dependency
>   counts; we did **not** resolve theirs.
> - **Competitors move fast.** Checked July 2026; MCP/A2A support in all four is actively changing.

---

## Verdict

toolnexus is **not** the deepest MCP/A2A implementation, **not** an orchestration engine, and **not**
a managed platform — and it doesn't try to be. It is the only option here that unifies **every tool
source** (MCP · skills · native · HTTP · built-ins · A2A, inbound and outbound) behind one `Tool`
interface, **byte-identical across five languages**, vendor-neutral, in a **2–3-dependency** library
with a **single-binary** Go story. If you need graph workflows pick LangGraph; if you're all-in on
Google Cloud pick ADK; if you're a Spring shop that wants the fullest JVM MCP/A2A pick Spring AI. If
you want a small, portable, honest tool layer you can drop into any of five language stacks and fully
own — that's toolnexus, and the trade-offs above are exactly what you're buying.

---

### Sources

Repo (this codebase): [`SPEC.md`](../SPEC.md) · [`README.md`](../README.md) ·
[`docs/adr/0001-rag-go-consumer-needs.md`](adr/0001-rag-go-consumer-needs.md) ·
[`docs/adr/0002-skills-consumer-needs.md`](adr/0002-skills-consumer-needs.md) · manifests
(`js/package.json`, `python/pyproject.toml`, `golang/go.mod`, `java/build.gradle`,
`csharp/src/Toolnexus/Toolnexus.csproj`).

Spring AI — [MCP overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) ·
[MCP server starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) ·
[MCP client starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) ·
[getting started with MCP](https://docs.spring.io/spring-ai/reference/guides/getting-started-mcp.html) ·
[A2A agentic pattern](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration/) ·
[agent skills pattern](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/) ·
[spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils) ·
[Spring AI 2.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/).

LangGraph / LangChain —
[langchain-mcp-adapters](https://github.com/langchain-ai/langchain-mcp-adapters) ·
[A2A in Agent Server](https://docs.langchain.com/langsmith/server-a2a) ·
[Deep Agents skills](https://docs.langchain.com/oss/python/deepagents/skills) ·
[LangGraph A2A/MCP example](https://github.com/n-sviridenko/langgraph-a2a-mcp-example).

Google ADK —
[ADK/A2A/MCP codelab](https://codelabs.developers.google.com/codelabs/currency-agent) ·
[ADK docs](https://docs.cloud.google.com/gemini-enterprise-agent-platform/build/adk) ·
[ADK + Agent Engine + A2A enhancements](https://developers.googleblog.com/agents-adk-agent-engine-a2a-enhancements-google-io/) ·
[ADK announcement](https://developers.googleblog.com/en/agent-development-kit-easy-to-build-multi-agent-applications/).

Protocol / skills background —
[Claude Agent Skills](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview).
