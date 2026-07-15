# toolnexus performance benchmarks

A **real, reproducible** micro-benchmark that compares **framework overhead** —
not model latency — of toolnexus against **LangGraph**, **Google ADK**, and
**Spring AI** on one fixed agent scenario that exercises **MCP + tools**.

The trick to a fair, zero-cost, deterministic comparison: point every framework at
the **same local mock LLM** and the **same stdio MCP server**, and measure only the
framework's own cost. Real model latency (100 ms–seconds) would swamp the thing we
actually want to measure — the framework's tax on top of the model.

Results and full methodology: [`../docs/performance-benchmarks.md`](../docs/performance-benchmarks.md).

## What's here

| File | Role |
|------|------|
| `mock_llm.py` | Deterministic OpenAI-compatible `/chat/completions` server. Turn 1 → two `tool_call`s (`get_weather`, `add`); turn 2 → a final answer. Zero network, zero cost. Picks tool names from each framework's advertised tool list (so Spring AI's `spring_ai_mcp_client_*` prefixing works). Handles both `Content-Length` and chunked request bodies. |
| `mcp_server.py` | The **shared** stdio MCP server (raw JSON-RPC, stdlib-only). Exposes `get_weather` / `add` / `echo`. Used by **every** framework so tool-discovery cost is identical. Echoes the client's requested protocol version so any MCP SDK connects. |
| `run_toolnexus.py` | toolnexus **Python** runner (`--config mcp` \| `full`). |
| `run_langgraph.py` | LangGraph runner (`--config default` \| `session`). |
| `run_adk.py` | Google ADK runner. |
| `run_toolnexus_go/` | toolnexus **Go** runner (composite `replace` → local module). |
| `run_toolnexus_java/` | toolnexus **Java** runner (Gradle composite `includeBuild` → local port). |
| `run_springai/` | Spring AI runner (Spring Boot + MCP-client starter). |
| `run_langchain4j/` | LangChain4j runner (Gradle; `AiServices` + `langchain4j-mcp` StdioMcpTransport → shared MCP server). |
| `run_toolnexus_csharp/` | toolnexus **C#** runner (`--config mcp` \| `full`; ProjectReference → local `csharp/` port; official MCP SDK). |
| `run_semantic_kernel/` | Microsoft Semantic Kernel runner (OpenAI connector via custom `HttpClient`; **native** KernelFunctions). |
| `run_ms_extensions_ai/` | Microsoft.Extensions.AI runner (official OpenAI .NET client + `UseFunctionInvocation`; **native** AIFunctions). |
| `run_all.py` | Orchestrator: runs each runner under `/usr/bin/time -l` (peak RSS), aggregates mean / p50 / p95, writes `results.json`. |

Each runner measures **cold init** (build toolkit / agent incl. MCP connect + tool
discovery) and **per-request wall time** for the fixed scenario over N runs, and
prints one JSON line. `run_all.py` adds peak RSS.

## The scenario

One fixed question — **"What's the weather in Paris and what is 2+2?"** — which the
mock answers by emitting exactly one tool-calling turn (two parallel tool calls),
then a final message. So every run = **2 LLM round-trips + 2 tool executions**
through the framework's own agent loop.

## Reproduce

Prereqs: Python ≥ 3.11 + [`uv`](https://github.com/astral-sh/uv), Go ≥ 1.23, JDK 21,
Gradle 9, and macOS/Linux (`/usr/bin/time -l` on macOS, `-v` on Linux — adjust the
RSS regex in `run_all.py` for Linux).

```sh
export REPO=/path/to/toolnexus
export VENVS=/tmp/bench-venvs           # anywhere outside the repo
```

### 1. Install each framework in an isolated venv

```sh
# toolnexus (installed from the local repo)
uv venv "$VENVS/toolnexus" -p 3.11 && uv pip install --python "$VENVS/toolnexus/bin/python" -e "$REPO/python"
# LangGraph
uv venv "$VENVS/langgraph" -p 3.11 && uv pip install --python "$VENVS/langgraph/bin/python" langgraph langchain-mcp-adapters langchain-openai mcp
# Google ADK
uv venv "$VENVS/adk" -p 3.11 && uv pip install --python "$VENVS/adk/bin/python" google-adk litellm orjson mcp
```

### 2. Build the compiled runners

```sh
# Go (single binary)
( cd "$REPO/benchmarks/run_toolnexus_go" && go build -o "$VENVS/bench_go" . )
# toolnexus Java (composite build → local java port)
( cd "$REPO/benchmarks/run_toolnexus_java" && gradle --no-daemon installDist -q )
# Spring AI (Spring Boot fat jar)
( cd "$REPO/benchmarks/run_springai" && gradle --no-daemon bootJar -q )
# LangChain4j (composite dist)
( cd "$REPO/benchmarks/run_langchain4j" && gradle --no-daemon installDist -q )
# C# runners (toolnexus ProjectReference → local port; SK / MEAI competitors)
( cd "$REPO/benchmarks/run_toolnexus_csharp" && dotnet build -c Release -v q )
( cd "$REPO/benchmarks/run_semantic_kernel"  && dotnet build -c Release -v q )
( cd "$REPO/benchmarks/run_ms_extensions_ai" && dotnet build -c Release -v q )
```

### 3. Start the mock LLM (any python)

```sh
"$VENVS/toolnexus/bin/python" "$REPO/benchmarks/mock_llm.py" --port 8900 &
```

### 4. Run everything

```sh
export BENCH_REPO="$REPO" MOCK_URL="http://127.0.0.1:8900" BENCH_RUNS=30 BENCH_WARMUP=5
export MCP_PYTHON="$VENVS/toolnexus/bin/python"          # launches the shared MCP server for all
export TN_PY="$VENVS/toolnexus/bin/python"
export LG_PY="$VENVS/langgraph/bin/python"
export ADK_PY="$VENVS/adk/bin/python"
export GO_BIN="$VENVS/bench_go"
export JAVA_APP="$REPO/benchmarks/run_toolnexus_java/build/install/run-toolnexus-java/bin/run-toolnexus-java"
export SPRING_JAR="$REPO/benchmarks/run_springai/build/libs/run_springai.jar"
export LC4J_APP="$REPO/benchmarks/run_langchain4j/build/install/run-langchain4j/bin/run-langchain4j"
export TN_CSHARP_DLL="$REPO/benchmarks/run_toolnexus_csharp/bin/Release/net10.0/run_toolnexus_csharp.dll"
export SK_DLL="$REPO/benchmarks/run_semantic_kernel/bin/Release/net10.0/run_semantic_kernel.dll"
export MEAI_DLL="$REPO/benchmarks/run_ms_extensions_ai/bin/Release/net10.0/run_ms_extensions_ai.dll"

python3 "$REPO/benchmarks/run_all.py"
```

Only the frameworks whose env var is set are run, so you can benchmark a subset.

## Fairness notes

- **Same mock, same MCP server, same scenario** for every framework — the *work*
  the framework does is identical.
- The **MCP server is launched with one fixed python** (`MCP_PYTHON`) for every
  framework, so it's byte-identical across all of them.
- **JVM warmup** is handled with explicit warmup iterations; the reported Java /
  Spring per-request numbers are post-warmup. Cold-JVM first calls are higher.
- **Go MCP caveat:** the toolnexus Go port cannot currently hold a *live* stdio
  MCP session — its `newLocalClient` does `defer cancel()` on the same context
  that owns the subprocess (`mark3labs/mcp-go` uses `exec.CommandContext`), so the
  MCP child is `SIGKILL`ed immediately after connect and `tools/list` hits a dead
  pipe. The Go number here therefore uses **native tools** (loop overhead only),
  clearly labelled. See the docs for detail — this is a real bug worth fixing.
- **MCP vs native across runners.** `toolnexus-java`, `toolnexus-csharp`,
  `langchain4j`, and `spring-ai` all discover their tools over the **shared stdio
  MCP server** (identical wire). `semantic-kernel` and `ms-extensions-ai` are
  driven with **native** in-process tools (same names/behavior — `get_weather` +
  `add`), labelled `-native`: neither ships a first-class stdio-MCP tool path as
  trivial as their native `KernelFunction` / `AIFunction` surface, so the native
  form is the honest, framework-idiomatic comparison. `toolnexus-go` is native for
  the SDK-bug reason above.
- Nothing here is committed automatically and no registry token is used; the
  runners only *import/install* the libraries, they never modify a port's `src/`.
