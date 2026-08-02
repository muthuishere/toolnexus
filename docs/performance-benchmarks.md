# toolnexus performance benchmarks

> **Every number on this page is [MEASURED]** on the machine described below, with a
> reproducible harness committed under [`../benchmarks/`](../benchmarks/). We publish
> **only** figures we actually measured. Where a framework could not be stood up to do
> the same work honestly, we say so and **omit it from the table** rather than guess.
> A partial-but-real table beats a complete-but-fake one.
>
> This is the latency/throughput companion to
> [`comparison-benchmarks.md`](./comparison-benchmarks.md) — that page compares
> **features and spec-compliance** and deliberately publishes *no* performance numbers;
> this page supplies the controlled head-to-head it points at. (We do not edit that file.)
>
> Versions measured: **toolnexus 0.13.0** · LangGraph 1.2.10 (+ langchain-mcp-adapters
> 0.3.1, langchain-openai 1.4.1) · Google ADK 2.6.1 (+ litellm 1.95.0) · CrewAI 1.15.10 ·
> Pydantic AI 2.22.0 · OpenAI Agents SDK 0.19.2 · Vercel AI SDK 7.0.48 · LangChain.js
> 1.5.4 · Mastra 1.55.0 · Eino 0.9.12 · langchaingo 0.1.14 · LangChain4j 1.0.1 ·
> Spring AI 1.0.1 (Spring Boot 3.4.2) · Semantic Kernel 1.65.0 ·
> Microsoft.Extensions.AI 9.9.1 · brainlid/langchain 0.9.2 · MCP Python SDK 1.29.0 ·
> mark3labs/mcp-go 0.48.0 · MCP Java SDK 2.0.0 · ModelContextProtocol (.NET) 1.4.0 ·
> koine 0.11.0 · cljgo 0.9.0.
> Measured **2 August 2026**, on the machine described under
> [Hardware / OS](#hardware--os) — a **different machine and toolchain generation** from
> the July 2026 run this page previously carried. Frameworks move fast — re-run before
> quoting.

---

## TL;DR

Pointing every framework at the **same mock LLM** and the **same stdio MCP server**, and
measuring only the framework's own per-request cost for one fixed tool-calling scenario,
across **39 framework configurations in 7 languages**:

- **toolnexus has the lowest per-request overhead in Python, JavaScript, Go and Java**,
  and is within noise of the fastest in C# and Elixir.
- **Python:** toolnexus **1.00 ms** p50/request vs OpenAI Agents **4.08 ms**, Pydantic AI
  **4.95 ms**, LangGraph **5.51 ms** (persistent MCP session) / **23.2 ms** (default
  reconnect-per-call), Google ADK **5.90 ms**, CrewAI **9.27 ms** — **4–23× less
  framework overhead**, at half the memory and a fraction of the install size.
- **Go:** the July stdio-MCP bug is **fixed**, so Go finally publishes a real MCP row:
  toolnexus **0.49 ms** p50 over live MCP vs Eino **0.55 ms**, in a **10 MB** static
  binary and **17 MB** RSS — the lowest overhead and the smallest footprint on the page.
- **Java:** toolnexus **2.19 ms** p50 vs LangChain4j **2.15 ms** (a dead heat, LangChain4j
  fractionally ahead) and Spring AI **3.25 ms** — with **2.6× less peak memory** than
  Spring AI and a **279 KB jar + 8.6 MB deps** vs a **26 MB** Spring Boot fat jar.
- **Clojure is new to this page, and it is the slowest port here.** Both hosts land at
  **~5.5 ms** p50 over MCP — roughly 5× the Python port and 11× the Go port. That is not
  a rounding artefact and it is not hidden; it is
  [analysed below](#clojure-the-slowest-port-on-this-page-and-why).
- Every competitor that ships an MCP client did **real MCP** here; toolnexus does real
  MCP in all seven languages.

These are **framework-overhead** numbers, not end-to-end latency: with a real model the
100 ms–second model call dominates. This benchmark isolates the tax the framework itself
adds on top.

---

## Methodology

### Why a mock LLM (and not a real one)

The thing we want to compare is **framework overhead** — how much CPU, memory, and wall
time a framework spends building its toolkit, serialising tool schemas, running its
agent loop, dispatching tool calls, and parsing responses. A **real** model call costs
100 ms to several seconds and is provider-bound; it would swamp the framework's own cost
and make the comparison about the model, not the framework. It would also cost money and
be non-deterministic.

So we stand up a tiny local **OpenAI-compatible** server ([`mock_llm.py`](../benchmarks/mock_llm.py))
that returns a **scripted, deterministic** conversation:

1. **Turn 1** (no tool results in the transcript yet) → an assistant message with **two
   parallel `tool_call`s**: `get_weather(city="Paris")` and `add(a=2, b=2)`.
2. **Turn 2** (tool results present) → a final assistant message,
   `"The weather in Paris is Sunny, 22C. And 2 + 2 = 4."`

Every framework points its `base_url` at this server. To keep the *work* identical, the
mock **picks the tool names from the tool list each framework advertises** — so Spring
AI's prefixed `spring_ai_mcp_client_bench_get_weather` and toolnexus's bare `get_weather`
are both handled, and each framework runs its full real tool-calling loop.

### Fixed, shared tools

One **stdio MCP server** ([`mcp_server.py`](../benchmarks/mcp_server.py)) exposes three
tools (`get_weather`, `add`, `echo`). It's a raw JSON-RPC/stdio implementation (stdlib
only) that **echoes back the client's requested protocol version**, so every MCP SDK in
the comparison (Python `mcp`, `mark3labs/mcp-go`, the official Java SDK, Spring AI's
client) connects to the *same binary* and pays the *same* tool-discovery cost. It's
launched with one fixed Python interpreter for all frameworks.

toolnexus additionally supports **agent skills** and **native tools**; where relevant we
report a `full` config (MCP + 1 skill + 1 native tool) alongside the strict MCP-only
config, to show the extra sources don't change the overhead story.

### The scenario

A single fixed question — **"What's the weather in Paris and what is 2+2?"** — forcing
exactly one tool-calling turn. One scenario run = **2 LLM round-trips + 2 tool
executions** through the framework's own loop.

### Metrics and how each was measured

| Metric | How | Tool |
|---|---|---|
| **Cold init** | Wall time to build the toolkit/agent *including* MCP connect + tool discovery, first touch. Measured **in-process** on every port, so it excludes interpreter/JVM boot everywhere and stays comparable. | `time.perf_counter` (Py), `performance.now` (JS), `time.Now` (Go), `System.nanoTime` (Java), `Stopwatch` (C#), `monotonic_time` (Elixir), `koine.time/mono-ms` (Clojure). |
| **Per-request p50/p95/mean** | Wall time of one scenario run, over **N = 30** measured runs after **5 warmup** runs. **Clojure is the one exception** — a sample there is a batch of 10 runs divided by 10, because koine's portable clock is millisecond-resolution; see [the Clojure section](#clojure-the-slowest-port-on-this-page-and-why). | same honest timers, in-process. |
| **Peak RSS** | Max resident set of the runner process. | `/usr/bin/time -l` wrapping each runner (macOS reports bytes). |
| **Install size / deps** | Isolated per-framework footprint. | `du -sh` of each venv's `site-packages` / jar / binary; declared direct deps. |

**Warmup handling.** JVM figures (toolnexus-Java, Spring AI) are **post-warmup** — the
first calls include JIT compilation and class loading and are excluded. Python and Go
also warm up but far less. Treat cold-start-sensitive workloads (serverless) with the
JVM caveat in mind.

### Hardware / OS

| | |
|---|---|
| Machine | Apple Mac17,8 — M5 Pro (`arm64`), 18 cores (6 efficiency + 12 performance), 48 GB RAM |
| OS | macOS 26.4 (Darwin 25.4.0) |
| Date | 2 August 2026 |
| Runtimes | CPython 3.11.15 (isolated `uv` venvs) · Node 24.18.0 · Go 1.26.3 · Temurin JDK 21.0.11 (Gradle-provisioned toolchain; Gradle 9.5.1 on OpenJDK 26) · .NET SDK 10.0.301 · Elixir 1.20.2 / OTP 29 · Clojure 1.12.5 on OpenJDK 26, and cljgo 0.9.0 (Go 1.26.3) |
| Network | none — mock LLM + MCP server are both local, offline, deterministic |

Single machine, otherwise-idle, every row from **one sitting on one day** — which is the
point of re-running the whole suite rather than splicing in a new language. The
**Clojure rows were sampled three independent times**; p50 moved by at most 0.4 ms
(JVM 5.1–5.5 ms, cljgo 5.6–5.8 ms), so treat ±0.5 ms as this harness's noise floor and
do not read differences smaller than that as signal.

**Different day, different toolchain generation.** The hardware profile matches the July
2026 run (same Mac17,8 / 48 GB spec), but every runtime under it moved: Go 1.23 → 1.26.3,
Node 22 → 24.18, the .NET SDK and Elixir/OTP forward a generation, and every competitor
library re-resolved to its current release. Absolute numbers shift for that reason alone
— compare *ratios within this table*, not cells against the July one.

---

## Results

**All cells [MEASURED]**, N = 30 runs, 5 warmup, single machine (above). `ok` = the loop
produced the correct final answer end-to-end. Sorted by language, then p50.

Rows marked **(native)** use in-process tools with the same names and behavior instead of
the shared MCP server. Native skips the subprocess spawn and `tools/list`, so a native row
has an inherent small advantage over an MCP row — the two are kept explicitly labelled
rather than mixed.

| Framework | Lang | Tool source | Init (ms) | p50 (ms) | p95 (ms) | mean (ms) | Peak RSS (MB) | Tools | ok |
|---|---|---|--:|--:|--:|--:|--:|--:|:-:|
| **toolnexus** | Clojure (JVM) | native | 0 | 1.7 | 3.1 | 1.89 | 511⁴ | 2 | ✅ |
| **toolnexus** | Clojure (cljgo) | native | 0 | 1.7 | 1.9 | 1.74 | **29.6** | 2 | ✅ |
| **toolnexus** | Clojure (JVM) | MCP | 24 | **5.5** | 6.8 | 5.55 | 510⁴ | 3 | ✅ |
| **toolnexus** (+ skill + native) | Clojure (JVM) | MCP | 25 | 5.5 | 6.8 | 5.46 | 492⁴ | 5 | ✅ |
| **toolnexus** | Clojure (cljgo) | MCP | 18 | 5.7 | 5.9 | 5.69 | **31.3** | 3 | ✅ |
| **toolnexus** (+ skill + native) | Clojure (cljgo) | MCP | 18 | 6.2 | 6.5 | 6.19 | 30.8 | 5 | ✅ |
| Semantic Kernel | C# | native | 51 | **0.594** | 0.792 | 0.621 | 71.2 | 2 | ✅ |
| **toolnexus** | C# | MCP | 84 | 0.626 | 0.816 | 0.637 | 78.1 | 3 | ✅ |
| Microsoft.Extensions.AI | C# | native | 27 | 0.626 | 0.720 | 0.616 | 68.2 | 2 | ✅ |
| **toolnexus** (+ skill + native) | C# | MCP | 96 | 0.657 | 0.859 | 0.683 | 80.8 | 5 | ✅ |
| **toolnexus** | Elixir | MCP | 27 | **0.602** | 0.664 | 0.605 | 123 | 3 | ✅ |
| **toolnexus** | Elixir | native | 8 | 0.602 | 0.703 | 0.594 | 116 | 2 | ✅ |
| Elixir LangChain | Elixir | native | 0 | 0.646 | 0.749 | 0.659 | 111 | 2 | ✅ |
| **toolnexus** | Go | native | 0 | **0.418** | 0.471 | 0.423 | **14.9** | 2 | ✅ |
| **toolnexus** (+ skill + native) | Go | MCP | 13 | 0.488 | 0.648 | 0.506 | 17.5 | 5 | ✅ |
| **toolnexus** | Go | MCP¹ | 12 | 0.491 | 0.676 | 0.512 | 17.0 | 3 | ✅ |
| Eino | Go | MCP | 14 | 0.545 | 0.628 | 0.548 | 27.6 | 3 | ✅ |
| langchaingo | Go | native | 0 | 0.548 | 1.046 | 0.610 | 21.6 | 2 | ✅ |
| LangChain4j | Java | MCP | 211 | **2.145** | 3.072 | 2.277 | 125 | 3 | ✅ |
| **toolnexus** | Java | MCP | 389 | 2.187 | 3.346 | 2.331 | 155 | 3 | ✅ |
| **toolnexus** (+ skill + native) | Java | MCP | 312 | 2.191 | 2.857 | 2.227 | 171 | 5 | ✅ |
| Spring AI | Java | MCP | 883² | 3.245 | 7.466 | 3.956 | 400 | 3 | ✅ |
| **toolnexus** | JS | native | 0 | **0.76** | 0.89 | 0.77 | 132 | 2 | ✅ |
| **toolnexus** | JS | MCP | 16 | 0.91 | 1.09 | 0.91 | 134 | 3 | ✅ |
| **toolnexus** (+ skill + native) | JS | MCP | 19 | 0.93 | 1.15 | 0.94 | 135 | 5 | ✅ |
| Vercel AI SDK | JS | native | 0 | 1.10 | 1.51 | 1.14 | 139 | 2 | ✅ |
| Vercel AI SDK | JS | MCP | 16 | 1.20 | 1.52 | 1.20 | 139 | 3 | ✅ |
| LangChain.js | JS | native | 2 | 2.26 | 2.95 | 2.32 | 249 | 2 | ✅ |
| LangChain.js | JS | MCP | 20 | 2.42 | 3.43 | 2.46 | 249 | 3 | ✅ |
| Mastra | JS | MCP | 18 | 2.59 | 3.77 | 2.69 | 285 | 3 | ✅ |
| Mastra | JS | native | 0 | 2.61 | 3.61 | 2.69 | 278 | 2 | ✅ |
| **toolnexus** (+ skill + native) | Python | MCP | 17 | **0.976** | 1.138 | 0.996 | 58.8 | 5 | ✅ |
| **toolnexus** | Python | MCP | 14 | 0.998 | 1.100 | 1.004 | 58.8 | 3 | ✅ |
| OpenAI Agents SDK | Python | MCP | 19 | 4.077 | 4.316 | 4.068 | 110 | 3 | ✅ |
| Pydantic AI | Python | MCP | 27 | 4.954 | 5.293 | 4.988 | 139 | 3 | ✅ |
| LangGraph (persistent session) | Python | MCP | 115 | 5.508 | 5.931 | 5.504 | 127 | 3 | ✅ |
| Google ADK | Python | MCP | 17 | 5.896 | 6.730 | 6.005 | 290 | 3 | ✅ |
| CrewAI | Python | MCP | 37 | 9.266 | 10.097 | 9.429 | 213 | 3 | ✅ |
| LangGraph (reconnect-per-call)³ | Python | MCP | 124 | 23.24 | 24.27 | 23.41 | 127 | 3 | ✅ |

¹ **The July stdio-MCP bug is fixed.** Last time this page had to publish a native-only Go
row because the port killed its own MCP child on connect
([the bug, kept for the record](#go-stdio-mcp-the-bug-we-found-and-fixed)). Go now
discovers over a live stdio session like everyone else, and its MCP row costs ~0.07 ms
more than its native row — the honest price of the subprocess.
² Spring AI "init" is **Spring Boot context startup incl. MCP discovery**, not a
toolkit-only build — see caveats; not directly comparable to toolnexus's init.
³ LangGraph's documented default (`MultiServerMCPClient.get_tools()`) re-opens a fresh
stdio session — **re-spawning the MCP subprocess — on every tool call**. The `session`
row holds one persistent MCP session (apples-to-apples with toolnexus).
⁴ **The Clojure-on-JVM RSS numbers are ~500 MB and that is not a typo.** They are the
JVM's default heap sizing on a 48 GB machine (`clojure -M` sets no `-Xmx`), not memory the
port needs — the Java port on the same machine sits at 155 MB because its launcher script
is more conservative. It is peak *resident set*, so it is a real number and it is
published as measured; it is a JVM configuration figure, not a toolnexus one. The same
source compiled by cljgo runs the identical workload in **30 MB**.

### Install footprint / dependencies [MEASURED]

| Framework | Install size | Direct deps |
|---|--:|---|
| **toolnexus** (Python) | **34 MB** venv | 2 — `mcp`, `pyyaml` (the client loop itself is pure stdlib) |
| OpenAI Agents SDK (Python) | 65 MB venv | `openai-agents`, `mcp` |
| LangGraph (Python) | 87 MB venv | 3 — `langgraph`, `langchain-mcp-adapters`, `langchain-openai` |
| Pydantic AI (Python) | 130 MB venv | `pydantic-ai`, `mcp` |
| Google ADK (Python) | 230 MB venv | 2 — `google-adk`, `litellm` |
| CrewAI (Python) | **787 MB** venv | `crewai`, `crewai-tools[mcp]` |
| **toolnexus** (JS) | **26 MB** | `@modelcontextprotocol/sdk` |
| Vercel AI SDK (JS) | 23 MB | `ai`, `@ai-sdk/openai`, `@ai-sdk/mcp`, `zod` |
| LangChain.js (JS) | 106 MB | `langchain`, `@langchain/openai`, `@langchain/mcp-adapters` |
| Mastra (JS) | 144 MB | `@mastra/core`, `@mastra/mcp`, `@ai-sdk/openai`, `zod` |
| **toolnexus** (Go) | **10 MB** static binary | single binary, no runtime |
| langchaingo (Go) | 16 MB binary | `tmc/langchaingo` |
| Eino (Go) | 21 MB binary | `cloudwego/eino` + eino-ext |
| **toolnexus** (Java) | **279 KB** jar + **8.6 MB** deps | MCP SDK, Jackson, SnakeYAML |
| LangChain4j (Java) | 11 MB dist | `langchain4j`, `-open-ai`, `-mcp` |
| Spring AI (Java) | 26 MB Spring Boot fat jar | Spring Boot + Spring AI OpenAI + MCP-client starter |
| **toolnexus** (C#) | **3.2 MB** | `ModelContextProtocol`, YamlDotNet |
| Microsoft.Extensions.AI (C#) | 5.4 MB | `Microsoft.Extensions.AI[.OpenAI]` |
| Semantic Kernel (C#) | 7.8 MB | `Microsoft.SemanticKernel` |
| **toolnexus** (Elixir) | **5.3 MB** deps | in-house MCP client |
| Elixir LangChain (Elixir) | 5.3 MB deps | `langchain` (brainlid) |
| **toolnexus** (Clojure, cljgo) | **15 MB** static binary | 1 — koine (`net.clojars.muthuishere/koine`, a **68 KB** jar), nothing else |
| **toolnexus** (Clojure, JVM) | the port + a **68 KB** koine jar, on the Clojure runtime | same single dependency; the port's own deps-purity gate fails the build if anything else appears on the default classpath |

---

## Reproduce it

**Full instructions, for all 39 configurations:**
[`../benchmarks/README.md`](../benchmarks/README.md) — it lists every venv, every build
command, and the env var that switches each runner on. The shape is always the same:

```sh
export REPO=/path/to/toolnexus VENVS=/tmp/bench-venvs

# 1. install each framework in its own isolated venv / node_modules / module cache
#    (toolnexus always from the LOCAL repo — editable install, `replace`, composite
#     build, ProjectReference, path dep, or a symlinked source tree)
# 2. build the compiled runners (Go, JVM, .NET, and the cljgo AOT binary)
# 3. start the mock LLM
"$VENVS/toolnexus/bin/python" "$REPO/benchmarks/mock_llm.py" --port 8900 &
# 4. export one env var per framework you want measured, then:
python3 "$REPO/benchmarks/run_all.py"
```

Three traps worth naming, all hit while producing this page:

- **Pin `mcp<2`** in the LangGraph and Google ADK venvs. `mcp` 2.0.0 dropped
  `mcp.shared.session` and `mcp.shared.context.RequestContext`, which breaks
  `langchain-mcp-adapters` and ADK's `mcp_toolset` at import time.
- **CrewAI needs `crewai-tools[mcp]`.** Without the extra, `MCPServerAdapter` hits a
  `click.confirm` prompt and aborts on a non-tty. Keep `BENCH_WARMUP` ≥ 2 for CrewAI, too:
  its first call writes a tracing preference and shows up as a ~480 ms outlier.
- For Clojure, build the cljgo leg with the **published** `cljgo` binary
  (`$HOME/go/bin/cljgo`), not a PATH shim that rebuilds the compiler from a local
  checkout — otherwise you are measuring a working tree, not a release.

---

## Couldn't measure / caveats — read this before quoting

### Clojure: the slowest port on this page, and why

The Clojure port is **~5.5 ms** p50 over MCP on both hosts. The Python port does the same
work in **1.0 ms** and the Go port in **0.49 ms**. Clojure is last, by a factor of five,
and the number is published rather than framed away. Three things are worth separating
before anyone quotes it:

**1. It is not the loop — it is the MCP round trip.** With native in-process tools the
Clojure port runs the whole scenario in **1.7 ms** on both hosts, comfortably mid-table.
Swapping those two tools for the same two tools over the shared stdio MCP server adds
**~3.9 ms**, i.e. roughly **2 ms per `tools/call`**. Every other port pays a fraction of
that for the identical wire traffic (Go: +0.07 ms; C#: within noise; Python: ~0). So the
cost sits in this port's stdio JSON-RPC path — its blocking line-read round trip and
pure-Clojure JSON codec — not in the agent loop, the adapters or the toolkit. **That is a
concrete optimisation target, and it is the single biggest one on this page.**

**2. The two hosts agree, which is the point of the port.** JVM 5.5 ms, cljgo 5.7 ms, from
**the same source file** — `benchmarks/run_toolnexus_clojure/src/bench.cljc` is compiled by
cljgo and loaded by `clojure -M` with no reader conditional between them. Parity is the
product; the hosts differing by 0.2 ms is the evidence that it holds under load, not just
in tests.

**3. Where the hosts do *not* agree is memory and startup, and both favour cljgo.**

| | Clojure (JVM) | Clojure (cljgo) |
|---|--:|--:|
| p50 / request (MCP) | 5.5 ms | 5.7 ms |
| in-process cold init | 24 ms | 18 ms |
| **process cold start** (exec → first answer) | **~1.13 s** | **~0.02 s** |
| Peak RSS | ~500 MB (JVM default heap) | **~31 MB** |

**The JVM's ~1.1 s process cold start is real and is reported here rather than excused.**
`init_ms` in the results table is measured *in-process* on every port, so it excludes
interpreter and JVM boot everywhere — that is what keeps the column comparable. But for
Clojure-on-JVM that exclusion hides the dominant cost of a short-lived run: `clojure -M -e nil`
alone is ~0.32 s on this machine, and the full runner to a first answer is ~1.13 s, against
~0.02 s for the cljgo binary (median of 3 each). For a long-lived agent process this is
amortised to nothing. For a CLI, a lambda, or anything that starts per request, it is the
whole story — and it is exactly the case cljgo exists for.

**Measurement caveat, stated because it changes how you read p95.** koine's portable
monotonic clock has millisecond resolution on both hosts, and a sub-millisecond timer would
mean host-specific code in the one file whose entire claim is that it has none. So a
Clojure *sample* is a **batch of 10 consecutive runs divided by 10** (0.1 ms effective
resolution). **Mean is unaffected**; p50 and p95 are percentiles over batch means and are
therefore *smoother* than every other port's per-run percentiles — a single slow request is
averaged across its batch instead of standing alone in the tail. Read the Clojure p95 as
"the 95th-percentile 10-run stretch". Three independent samples put p50 within 0.4 ms.

### Go stdio-MCP: the bug we found, and fixed

For the July 2026 run the toolnexus **Go** port could **not** hold a live **stdio** MCP
session, so that table published a native-tools-only Go row. **That is fixed as of 0.9.2**,
and this run's `toolnexus-go-mcp` row is a real live stdio session (3 tools discovered,
0.491 ms p50). The original diagnosis is kept below because the failure mode — a silent,
isolated MCP failure that looks like "no tools" — is worth recognising anywhere:

- `golang/mcp.go`'s `newLocalClient` starts the transport with a **timeout context**
  and `defer cancel()`s it before returning the connected client.
- `mark3labs/mcp-go` spawns the MCP child with `exec.CommandContext(ctx, …)`
  (`client/transport/stdio.go`), so that context **owns the subprocess lifetime**.
- On return, `defer cancel()` fires → the MCP child gets `SIGKILL` **immediately after
  `initialize` succeeds** → the subsequent `tools/list` hits a dead pipe
  (`transport error: transport closed`), tools load as 0, and (because MCP failures are
  isolated) it fails *silently*.

The Go port's test suite didn't exercise a live **outbound** stdio MCP client (its MCP
tests were all *inbound* server tests), so it went unnoticed until a benchmark asked the
port to do the thing nobody had asked it to do in anger. That is the argument for keeping
this harness: it exercises each port the way a user does, not the way its unit tests do.

### JVM warmup

Java, Spring AI, LangChain4j and **Clojure-on-JVM** numbers are **post-warmup**. Cold-JVM
first requests are materially slower (JIT + class loading). For short-lived / serverless
workloads the JVM cold-start dominates and none of these per-request numbers apply. The
`Init (ms)` column captures some, but not all, of that cold cost — for the Clojure case
the process-level figure is measured and published
[above](#clojure-the-slowest-port-on-this-page-and-why).

### Spring AI "init" is not a like-for-like init

Spring AI's MCP discovery is entangled with **Spring Boot context startup**, so its
`Init` figure (~1.06 s) includes bringing up the whole application context, not just a
toolkit build. It's reported for transparency but **should not** be compared cell-to-cell
against toolnexus's toolkit-only init. The **per-request** figure is the clean,
directly-comparable one.

### Harness detail: chunked request bodies

Spring AI's `RestClient` sends the chat request with `Transfer-Encoding: chunked` and no
`Content-Length`; the mock had to decode chunked bodies for Spring to work at all. That's
a property of the HTTP client, not a Spring *cost*, and doesn't affect the measured
latency (the mock decode is microseconds).

### Mock-vs-real, and single-machine variance

- These are **framework-overhead** numbers. In production, a real model call dwarfs all
  of them; a 5 ms vs 1 ms framework difference is invisible next to a 500 ms model call
  **per request**, but compounds under high concurrency, in tight agent loops, and on
  memory/cost at scale.
- Single machine, single process at a time, otherwise idle. Absolute numbers will differ
  on your hardware; the **ratios** between frameworks are the portable takeaway. The
  Clojure rows were sampled three times and agreed within 0.4 ms.
- **All seven ports are measured** this time, each against the competitors that exist in
  its language. Where a language has no MCP-capable competitor (C#, Elixir, Clojure), the
  table says so rather than inventing a peer: `semantic-kernel` and
  `ms-extensions-ai` are compared on **native** tools, `langchain-elixir` likewise, and
  Clojure has no third-party peer at all.
- **Nothing was skipped in this run.** Every registered runner produced a result; the
  `skipped` list in `benchmarks/results.json` is empty. If a future run cannot stand a
  framework up, it lands in that list rather than quietly vanishing from the table.

---

## Verdict

On pure **framework overhead** for a fixed MCP tool-calling scenario, **toolnexus is the
lowest-overhead option in Python, JavaScript, Go and Java**, and within noise of the
fastest in C# and Elixir — while doing **real MCP in all seven languages** and carrying
the smallest or near-smallest install footprint everywhere. Python is 4–23× lighter than
its competitors; Go now publishes a genuine MCP row at 0.49 ms in a 10 MB binary; Java is
a dead heat with LangChain4j and 1.5× lighter than Spring AI at 2.6× less memory.

The honest asterisks, all of them: **LangChain4j edges the Java port** by 0.04 ms;
**Semantic Kernel edges the C# port** by 0.03 ms on native tools while toolnexus is doing
live MCP; and **the Clojure port is last on this page by a factor of five**, with ~2 ms of
that per MCP tool call sitting in its stdio JSON-RPC path — a specific, findable cost, now
written down where it cannot be forgotten. Clojure-on-JVM also carries a ~1.1 s process
cold start and a ~500 MB default JVM heap; the same source compiled by cljgo starts in
20 ms and holds 31 MB. These figures describe warm, long-lived agents — which is exactly
where framework overhead compounds and matters most.
