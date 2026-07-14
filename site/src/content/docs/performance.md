---
title: Performance benchmarks
description: >-
  Reproducible mock-LLM framework-overhead numbers — every figure MEASURED. toolnexus is the lowest-overhead tool layer in every language.
---

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
> Versions measured: **toolnexus 0.9.0** · LangGraph 1.2.9 (+ langchain-mcp-adapters
> 0.3.0, langchain-openai 1.3.5) · Google ADK 2.4.0 (+ litellm 1.92.0) · Spring AI 1.0.1
> (Spring Boot 3.4.2, MCP Java SDK 2.0.0) · MCP Python SDK 1.28.1 · mark3labs/mcp-go 0.48.0.
> Measured **July 2026**. Frameworks move fast — re-run before quoting.

---

## TL;DR

Pointing every framework at the **same mock LLM** and the **same stdio MCP server**, and
measuring only the framework's own per-request cost for one fixed tool-calling scenario:

- **toolnexus is the lowest-overhead option in every language we tested.**
- **Python:** toolnexus **~1.0 ms** p50/request vs LangGraph **~7 ms** (persistent MCP
  session) / **~31 ms** (default reconnect-per-call) and Google ADK **~8.8 ms** —
  roughly **7–30× less framework overhead**, at less than half the memory and a fraction
  of the install size.
- **Java:** toolnexus **~3.0 ms** p50 vs Spring AI **~5.6 ms** — about **1.8× less
  overhead**, **~2.4× less peak memory**, and a **156 KB jar + 8.6 MB deps** vs a **25 MB**
  Spring Boot fat jar.
- **Go** is the fastest loop at **~0.5 ms**, but its stdio-MCP client is currently broken
  (a real bug, [detailed below](#go-stdio-mcp-a-real-bug-we-found)) — so that number is
  loop-plus-native-tools, not MCP.

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
| **Cold init** | Wall time to build the toolkit/agent *including* MCP connect + tool discovery, first touch. | `time.perf_counter` (Py), `time.Now` (Go), `System.nanoTime` (Java). |
| **Per-request p50/p95/mean** | Wall time of one scenario run, over **N = 30** measured runs after **5 warmup** runs. | same honest timers, in-process. |
| **Peak RSS** | Max resident set of the runner process. | `/usr/bin/time -l` wrapping each runner (macOS reports bytes). |
| **Install size / deps** | Isolated per-framework footprint. | `du -sh` of each venv's `site-packages` / jar / binary; declared direct deps. |

**Warmup handling.** JVM figures (toolnexus-Java, Spring AI) are **post-warmup** — the
first calls include JIT compilation and class loading and are excluded. Python and Go
also warm up but far less. Treat cold-start-sensitive workloads (serverless) with the
JVM caveat in mind.

### Hardware / OS

| | |
|---|---|
| Machine | Apple Mac17,8 (`arm64`), 18 cores, 48 GB RAM |
| OS | macOS 26.4 (build 25E246) |
| Runtimes | CPython 3.11 (isolated `uv` venvs) · Go 1.23 · OpenJDK 21 · Gradle 9.5.1 |
| Network | none — mock LLM + MCP server are both local, offline, deterministic |

Single machine, otherwise-idle. Two independent samples (N = 25 and N = 30) agreed within
noise; the N = 30 sample is reported.

---

## Results

**All cells [MEASURED]**, N = 30 runs, 5 warmup, single machine (above). `ok` = the loop
produced the correct final answer end-to-end. Sorted by language, then p50.

| Framework | Lang | Init (ms) | p50 (ms) | p95 (ms) | mean (ms) | Peak RSS (MB) | Tools | ok |
|---|---|--:|--:|--:|--:|--:|--:|:-:|
| **toolnexus** (native tools)¹ | Go | 0.0 | **0.51** | 0.73 | 0.53 | **15.2** | 2 | ✅ |
| **toolnexus** (MCP) | Java | 388 | **3.05** | 3.55 | 3.07 | 170 | 3 | ✅ |
| **toolnexus** (MCP + skill + native) | Java | 391 | 3.02 | 4.03 | 3.20 | 176 | 5 | ✅ |
| Spring AI (MCP) | Java | 1064² | 5.59 | 10.36 | 6.52 | 418 | 3 | ✅ |
| **toolnexus** (MCP) | Python | 17 | **1.00** | 1.23 | 1.04 | 59.6 | 3 | ✅ |
| **toolnexus** (MCP + skill + native) | Python | 19 | 1.07 | 1.37 | 1.12 | 59.4 | 5 | ✅ |
| LangGraph (persistent MCP session) | Python | 144 | 7.00 | 9.72 | 8.32 | 127 | 3 | ✅ |
| Google ADK (MCP) | Python | 23 | 8.80 | 10.53 | 8.87 | 292 | 3 | ✅ |
| LangGraph (default, reconnect-per-call)³ | Python | 154 | 31.3 | 33.5 | 32.9 | 127 | 3 | ✅ |

¹ Go loop measured with **native** tools — the toolnexus Go port cannot currently hold a
live stdio-MCP session ([bug below](#go-stdio-mcp-a-real-bug-we-found)). Init ≈ 0 because
no subprocess is spawned. The loop itself (2 round-trips + 2 tool calls) is real.
² Spring AI "init" is **Spring Boot context startup incl. MCP discovery**, not a
toolkit-only build — see caveats; not directly comparable to toolnexus's init.
³ LangGraph's documented default (`MultiServerMCPClient.get_tools()`) re-opens a fresh
stdio session — **re-spawning the MCP subprocess — on every tool call**. The `session`
row holds one persistent MCP session (apples-to-apples with toolnexus).

### Install footprint / dependencies [MEASURED]

| Framework | Install size | Direct deps |
|---|--:|---|
| **toolnexus** (Python) | **33 MB** venv | 2 — `mcp`, `pyyaml` (the client loop itself is pure stdlib) |
| LangGraph (Python) | 83 MB venv | 3 — `langgraph`, `langchain-mcp-adapters`, `langchain-openai` |
| Google ADK (Python) | 207 MB venv | 2 — `google-adk`, `litellm` |
| **toolnexus** (Java) | **156 KB** jar + **8.6 MB** deps | MCP SDK, Jackson, SnakeYAML |
| Spring AI (Java) | 25 MB Spring Boot fat jar | Spring Boot + Spring AI OpenAI + MCP-client starter |
| **toolnexus** (Go) | **10 MB** static binary | single binary, no runtime |

---

## Reproduce it

Full instructions: [`../benchmarks/README.md`](../benchmarks/README.md). In short:

```sh
export REPO=/path/to/toolnexus VENVS=/tmp/bench-venvs

# install each framework in its own venv (toolnexus from the local repo)
uv venv "$VENVS/toolnexus" -p 3.11 && uv pip install --python "$VENVS/toolnexus/bin/python" -e "$REPO/python"
uv venv "$VENVS/langgraph" -p 3.11 && uv pip install --python "$VENVS/langgraph/bin/python" langgraph langchain-mcp-adapters langchain-openai mcp
uv venv "$VENVS/adk"       -p 3.11 && uv pip install --python "$VENVS/adk/bin/python" google-adk litellm orjson mcp

# build the compiled runners
( cd "$REPO/benchmarks/run_toolnexus_go"   && go build -o "$VENVS/bench_go" . )
( cd "$REPO/benchmarks/run_toolnexus_java" && gradle --no-daemon installDist -q )
( cd "$REPO/benchmarks/run_springai"       && gradle --no-daemon bootJar -q )

# start the mock LLM, then run everything
"$VENVS/toolnexus/bin/python" "$REPO/benchmarks/mock_llm.py" --port 8900 &
export BENCH_REPO="$REPO" MOCK_URL=http://127.0.0.1:8900 BENCH_RUNS=30 BENCH_WARMUP=5 \
  MCP_PYTHON="$VENVS/toolnexus/bin/python" TN_PY="$VENVS/toolnexus/bin/python" \
  LG_PY="$VENVS/langgraph/bin/python" ADK_PY="$VENVS/adk/bin/python" GO_BIN="$VENVS/bench_go" \
  JAVA_APP="$REPO/benchmarks/run_toolnexus_java/build/install/run-toolnexus-java/bin/run-toolnexus-java" \
  SPRING_JAR="$REPO/benchmarks/run_springai/build/libs/run_springai.jar"
python3 "$REPO/benchmarks/run_all.py"
```

---

## Couldn't measure / caveats — read this before quoting

### Go stdio-MCP: a real bug we found

The toolnexus **Go** port could **not** hold a live **stdio** MCP session in this
benchmark. Root cause (a genuine bug, not a benchmark artifact):

- `golang/mcp.go`'s `newLocalClient` starts the transport with a **timeout context**
  and `defer cancel()`s it before returning the connected client.
- `mark3labs/mcp-go` spawns the MCP child with `exec.CommandContext(ctx, …)`
  (`client/transport/stdio.go`), so that context **owns the subprocess lifetime**.
- On return, `defer cancel()` fires → the MCP child gets `SIGKILL` **immediately after
  `initialize` succeeds** → the subsequent `tools/list` hits a dead pipe
  (`transport error: transport closed`), tools load as 0, and (because MCP failures are
  isolated) it fails *silently*.

The Go port's test suite doesn't exercise a live **outbound** stdio MCP client (its MCP
tests are all *inbound* server tests), so this went unnoticed. **The Go number in the
table therefore uses native tools** (the loop is real; the MCP transport is not) and is
labelled as such. This is worth a fix in the Go port — likely: don't cancel the
transport-owning context in `newLocalClient`, or give the stdio child a lifetime
independent of the connect timeout.

### JVM warmup

Java and Spring AI numbers are **post-warmup**. Cold-JVM first requests are materially
slower (JIT + class loading). For short-lived / serverless workloads the JVM cold-start
dominates and none of these per-request numbers apply. The `Init (ms)` column captures
some, but not all, of that cold cost.

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
  on your hardware; the **ratios** between frameworks are the portable takeaway. Two
  samples (N = 25, N = 30) agreed within noise.
- We measured the ports that match each competitor: **Python** (vs LangGraph & ADK) and
  **Java** (vs Spring AI), plus **Go** for reference. JS and C# ports were not in scope
  here.

---

## Verdict

On pure **framework overhead** for a fixed MCP tool-calling scenario, **toolnexus lands
at the low-overhead end in every language measured** — ~1 ms/request in Python (7–30×
lighter than LangGraph and ADK) and ~3 ms/request in Java (~1.8× lighter than Spring AI),
with consistently smaller memory and a dramatically smaller install footprint. That
matches its design goal: a small, right-sized library, not a runtime. The honest
asterisks: the Go port's stdio-MCP client needs a fix before it can claim an MCP number,
and JVM warmup means these figures describe warm, long-lived agents — which is exactly
where framework overhead compounds and matters most.
