# toolnexus (Java)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.muthuishere/toolnexus?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus)

**Every tool an LLM can call — MCP servers, agent skills, your own methods, and
remote HTTP endpoints — behind one uniform `Tool`, in one `Toolkit`, with a
built-in agent loop.** Point a client at any OpenAI- or Anthropic-style endpoint
and run.

```java
Toolkit tk = Toolkit.create(new Toolkit.Options());   // 10 built-in tools, on by default
LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl("https://openrouter.ai/api/v1").style("openai").model("openai/gpt-4o-mini"));
System.out.println(agent.run("List the files here, then read the largest one.", tk).text);
```

> **Right-sized.** toolnexus is a *library*, not a framework — no runtime, no DI
> container, no annotations you must adopt. Bring your own model endpoint; keep
> your own control flow. Use the whole agent loop, or take only the adapters and
> drive your own. It gets out of the way.

**Same library, five languages.** toolnexus is ported byte-for-byte across
**[JavaScript](../js) · [Python](../python) · [Go](../golang) · Java · C#** — the
same `examples/` fixtures produce the same behavior in every port (see
[`../SPEC.md`](../SPEC.md)). This is the Java port (Java 21), built on the
official **MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`).

---

## Install

Maven Central coordinate: **`io.github.muthuishere:toolnexus:0.10.0`**. Requires **Java 21+**.

**Gradle** (`build.gradle`):

```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'io.github.muthuishere:toolnexus:0.10.0'
}
```

**Maven** (`pom.xml`):

```xml
<dependency>
  <groupId>io.github.muthuishere</groupId>
  <artifactId>toolnexus</artifactId>
  <version>0.10.0</version>
</dependency>
```

---

## Zero to agent in 3 steps

### 1. A toolkit

`Toolkit.create` with default options gives you the **10 built-in tools**
(`bash`, `read`, `write`, `edit`, `grep`, `glob`, `webfetch`, `question`,
`todowrite`, `apply_patch`) — on by default, so an agent can act with zero wiring.

```java
import io.github.muthuishere.toolnexus.*;

Toolkit tk = Toolkit.create(new Toolkit.Options());
```

### 2. A client

`LlmClient.create` takes a base URL and a `style` (`"openai"` or `"anthropic"`).
The example uses **OpenRouter**, which speaks the OpenAI wire format for hundreds
of models:

```java
LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl("https://openrouter.ai/api/v1")
        .style("openai")
        .model("openai/gpt-4o-mini")
        .systemPrompt("You are a precise coding agent."));
```

> **API key** — read from `apiKey(...)` if set, else from the environment in this
> order: `OPENROUTER_API_KEY`, then `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` (by
> style). Export `OPENROUTER_API_KEY=...` and you're set. Keys are use-only and
> never logged.

### 3. Run

```java
LlmClient.RunResult res = agent.run("What is 21 + 21?", tk);

System.out.println(res.text);            // "...42..."
System.out.println(res.toolCalls);       // every tool the model called
System.out.println(res.usage.totalTokens);
```

`run` drives the full loop: send prompt → model may call tools → tools execute
(in parallel within a turn) → results fed back → repeat until the model answers
or `maxTurns` (default 10) is hit. `RunResult` also carries `messages` (the full
transcript), `turns`, and `model`.

`Toolkit` is `AutoCloseable` — use try-with-resources (or call `tk.close()`) to
shut down MCP subprocesses:

```java
try (Toolkit tk = Toolkit.create(new Toolkit.Options())) {
    agent.run("...", tk);
}
```

---

## Add MCP servers + agent skills

The two headline dynamic sources. Point `mcpConfig` at an `mcp.json` and
`skillsDir` at a folder of `**/SKILL.md` skills:

```java
Toolkit tk = Toolkit.create(new Toolkit.Options()
        .mcpConfig("mcp.json")        // path, raw JSON string, or a parsed Map
        .skillsDir("./skills"));      // one or more skill roots

System.out.println(tk.mcpStatus());   // {everything=connected, ...}
```

- **MCP** — connects to every server in the config (local stdio + remote
  streamable-HTTP); each server tool becomes a uniform `Tool`. A failing server
  is isolated, never fatal. `mcpStatus()` reports `connected` / `failed` /
  `disabled` per server.
- **Skills** — globs `**/SKILL.md` and exposes **one** `skill` tool that loads a
  skill's instructions + resources on demand (progressive disclosure). The
  catalog is injected into the system prompt automatically (`tk.skillsPrompt()`).

The `examples/mcp.json` fixture spawns `npx @modelcontextprotocol/server-everything`
— no API key needed to inspect the tool list.

---

## Conversations & memory

`run` is stateless — each call starts fresh. **`ask` adds durable memory:** give
it a conversation `id` and the client remembers that thread through a
`ConversationStore`.

```java
LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl("https://openrouter.ai/api/v1").style("openai").model("openai/gpt-4o-mini"));

// Turn 1 — establish a fact under id "user-42":
agent.ask("My name is Muthu. Remember it.", tk, "user-42");

// Turn 2 — same id, the model still knows:
LlmClient.RunResult r = agent.ask("What's my name?", tk, "user-42");
System.out.println(r.text);   // "...Muthu..."
```

**How `ask` behaves** (`RunResult ask(String prompt, Toolkit toolkit, String id)`):

| `id`              | Behavior                                                                             |
|-------------------|--------------------------------------------------------------------------------------|
| non-null, non-empty | **Remembered.** Loads that id's transcript from the store → runs → saves the updated transcript. |
| `null` or `""`    | **Stateless.** Identical to `run(prompt, toolkit)` — nothing is stored.               |

### Bring your own store

The default is `LlmClient.InMemoryConversationStore` (transcripts live for the
client's lifetime). To persist across processes, implement the two-method
`LlmClient.ConversationStore` interface and pass it via `Options.store(...)`:

```java
public interface ConversationStore {
    List<Object> get(String id);              // stored transcript for id, or null if none
    void save(String id, List<Object> messages);  // persist the updated transcript
}
```

```java
LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl("https://openrouter.ai/api/v1").style("openai").model("openai/gpt-4o-mini")
        .store(myFileBackedStore));   // e.g. a JSON-per-id or JDBC implementation
```

Wire it to files, Redis, Postgres — anything keyed by a string id.

> **A2A memory reuses the same store.** When you `serve` your toolkit as an agent
> (below), an inbound message's A2A `contextId` is passed straight to
> `client.ask` — so a *peer* agent's multi-turn conversation is remembered
> through the exact same `ConversationStore`. One memory model, inbound and out.

*(For an ephemeral in-process thread without a store, `agent.conversation(tk)`
returns a `Conversation` whose `send(prompt)` retains history automatically.)*

### Streaming with memory

The `id` also works while streaming. `ask` has an overload that takes an
`onText` callback — it runs the streaming loop, forwards each assistant text
delta to `onText`, and **still returns the final `RunResult`** (memory keyed by
`id` exactly as the non-streaming `ask`). `stream` gains an `id` overload: the
thread is loaded before streaming and saved once the terminal `done` fires.

```java
// block-style: stream deltas to stdout, still get the RunResult — remembered under "user-42"
LlmClient.RunResult r = agent.ask("Draft a reply.", tk, "user-42", System.out::print);

// event stream with memory: id ⇒ load before, save on DONE
agent.stream("And summarise it.", tk, ev -> {
    if (ev.type() == LlmClient.StreamEvent.Kind.TEXT) System.out.print(ev.delta());
    else if (ev.type() == LlmClient.StreamEvent.Kind.DONE) System.out.println("\n" + ev.result().usage);
}, "user-42");
```

---

## Observability & metrics

Zero-dependency, two outputs from one internal instrumentation — both opt-in, no
cost when unused.

**`onMetric` — a semantic event feed.** `Options.onMetric(Consumer<MetricEvent>)`
receives a readable `MetricEvent` (a sealed interface) at each significant point:
`MetricEvent.Llm(model, status, ms, promptTokens, completionTokens)` per model
call, `MetricEvent.Tool(tool, source, isError, ms)` per tool call, and a terminal
`MetricEvent.Run(model, turns, toolCalls, totalTokens, ms, error)` per `run`/`ask`
(`event()` yields the `"llm"`/`"tool"`/`"run"` string). Forward it anywhere —
statsd, logs, OpenTelemetry.

```java
LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl(baseUrl).style("openai").model(model)
        .onMetric(ev -> System.out.println("[metric] " + ev.event() + " " + ev)));
```

**`agent.metrics()` — built-in Prometheus text.** The same events feed a tiny
in-memory registry that renders the Prometheus text exposition format (no
third-party dep). Mount it at `GET /metrics`:

```java
HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);
server.createContext("/metrics", exchange -> {
    byte[] body = agent.metrics().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
    exchange.sendResponseHeaders(200, body.length);
    try (var os = exchange.getResponseBody()) { os.write(body); }
});
server.start();
```

Series: `toolnexus_llm_requests_total{model,status}`,
`toolnexus_llm_tokens_total{type}`,
`toolnexus_tool_calls_total{tool,source,is_error}`,
`toolnexus_run_errors_total{model}`, plus the
`toolnexus_llm_request_duration_seconds` and `toolnexus_tool_duration_seconds`
histograms. The rendered text is byte-identical across all five ports; OTLP push
is a planned future companion.

---

## Add your own tools

### Native tools (a plain function)

`NativeTool.of` wraps any function as a `Tool`. Return a `String` (wrapped as
output) or a full `ToolResult`; a thrown exception becomes an error result.

```java
import io.github.muthuishere.toolnexus.NativeTool;
import java.util.Map;

Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", Map.of("message", Map.of("type", "string")),
        "required", java.util.List.of("message"));

tk.register(NativeTool.of("echo", "Echo a message back.", schema,
        args -> "you said: " + args.get("message")));
```

Prefer annotations? Annotate methods with `@ToolMethod` / `@Param` and pass the
object to `Options.annotatedObjects(...)` (or `Tools.fromObject(obj)`); the input
schema is inferred from parameter types.

### HTTP / REST tools

Declare a remote endpoint as a tool — `{placeholder}` URL substitution, `query`
arg → querystring, JSON/form/raw bodies, and `${ENV}` header expansion (read from
`System.getenv`, never logged). Non-2xx becomes an `HTTP <status>: <body>` error.

```java
import io.github.muthuishere.toolnexus.HttpTool;

HttpTool.Options o = new HttpTool.Options();
o.name = "get_post";
o.description = "Fetch a placeholder blog post by id.";
o.method = "GET";
o.url = "https://jsonplaceholder.typicode.com/posts/{id}";   // {id} filled from args
o.inputSchema = Map.of("type", "object",
        "properties", Map.of("id", Map.of("type", "number")),
        "required", java.util.List.of("id"));

tk.register(HttpTool.of(o));
```

You can also seed both at construction time via
`Options.extraTools(Tool...)`.

---

## Built-in tools

A dedicated source ships **10 built-in tools** whose names + input schemas match
opencode:

`bash` · `read` · `write` · `edit` · `grep` · `glob` · `webfetch` · `question` ·
`todowrite` · `apply_patch`

They appear in the tool schema (`toOpenAI()` / `toAnthropic()` / `toGemini()`),
like MCP tools — not in the system prompt. **On by default.** Because
`bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem,
there are two off-switches:

```java
// Whole source off (locked-down host):
Toolkit tk = Toolkit.create(new Toolkit.Options()
        .builtins(false));   // also: Map.of("disabled", true) / Map.of("enabled", false)

// Per-tool: drop bash, keep the other nine (unknown names ignored; whole-off still wins):
Toolkit tk2 = Toolkit.create(new Toolkit.Options()
        .builtins(Map.of("tools", Map.of("bash", false))));
```

Built-ins are the lowest-precedence source — a host tool of the same name (via
`register` / `extraTools`) shadows a built-in.

---

## A2A agents (agent-to-agent)

A minimal but genuine subset of real [A2A](https://a2a-protocol.org) (JSON-RPC
2.0; Agent Card at `/.well-known/agent-card.json`; `SendMessage` → poll
`GetTask`) — so toolnexus interoperates with real A2A peers.

### Outbound — call a remote agent

Each of the remote agent's advertised skills becomes a tool named
`<agent>_<skill>` (source `"a2a"`):

```java
import io.github.muthuishere.toolnexus.A2A;

Toolkit tk = Toolkit.create(new Toolkit.Options()
        .agents(A2A.agent("https://researcher.example.com/.well-known/agent-card.json")));

// or add one at runtime — an A2A.Agent, or a bare card URL:
tk.addAgent("https://writer.example.com/.well-known/agent-card.json");
```

`A2A.agent(card[, headers, timeout, pollEvery])` — `headers` support `${ENV}`
expansion (never logged); `timeout` / `pollEvery` are milliseconds (300000 / 1000
defaults). A config Map may also carry an `agents` block (mirrors `mcpServers`). A
failing agent contributes no tools and is never fatal.

### Inbound — serve your toolkit as an agent

The Agent Card is built from your **SKILL.md skills** (never raw tools). Each
inbound `SendMessage` task is fulfilled by the `LlmClient` loop; an A2A
`contextId` is remembered through the client's `ConversationStore`
(see [Conversations & memory](#conversations--memory)).

```java
import io.github.muthuishere.toolnexus.A2AServer;

A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0", new Toolkit.ServeOptions()
        .client(agent)                         // the LlmClient host loop
        .a2a(new A2AServer.A2AConfig()
                .name("research-agent")
                .description("Answers research questions.")
                // .skills(List.of("hello-world"))  // subset to advertise; omit ⇒ all
                .store("memory")));                 // "memory" (default) | "file:<dir>" | custom TaskStore

System.out.println(handle.url());   // GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
handle.stop();
```

Task persistence is a pluggable `A2AServer.TaskStore` — in-memory default,
`"file:<dir>"`, or your own.

---

## Serve as an MCP server (be a gateway)

The inbound mirror of A2A: expose your **whole toolkit as an MCP server** so any MCP
client — an IDE, another agent, a remote host — can call its tools. Point toolnexus at
N MCP servers + skills + your own functions, then re-expose the union as **one** MCP
server. Unlike A2A, the MCP client *is* the LLM host, so each `tools/call` dispatches
straight to the tool's `execute` — no client, no tasks, no store.

```java
// streamable-HTTP — an embeddable remote server, mounted at POST /mcp beside any A2A routes:
A2AServer.ServeHandle srv = tk.serve("127.0.0.1:0", new Toolkit.ServeOptions()
        .mcp(new McpServe.MCPServeConfig().name("my-gateway"))   // .tools(List.of("echo")) subset; omit ⇒ all
        .onCall(ev -> System.err.println(ev.name() + " " + ev.ms() + " " + ev.isError())));
System.out.println(srv.url() + "/mcp");   // connect any MCP client here
srv.stop();
```

`tools/list` advertises every toolkit tool (name **verbatim**, `inputSchema` = the tool's
parameters); `mcp.tools` narrows the surface. The profile can also live in the config file
as a top-level **`mcpServer`** block (singular — distinct from the client-side `mcpServers`).

---

## Bring your own loop

Don't want the built-in loop? Emit the tool schema in your provider's format,
route the model's tool call through `execute`, and drive it yourself:

```java
var tools = tk.toOpenAI();              // or toAnthropic() / toGemini()

// ... you call the model with `tools`; it returns a tool call { name, args } ...

ToolResult res = tk.execute(name, args);
System.out.println(res.output());       // feed back to the model; res.isError() flags failures
```

Same `Tool` list, three schema dialects, one `execute` router — the adapters and
toolkit are useful even with zero of the client loop.

---

## Sub-agents & teams

An **Agent is a Tool**: a system prompt × a scoped toolkit view × the client loop. One agent
delegates to another **in-process** via one `task` tool — isolated context, one result back,
tokens rolled up, hierarchical budgets, durable suspension (`SPEC.md §7D`). Lives in the
`io.github.muthuishere.toolnexus.agents` package (never the A2A `Agent`):

```java
import static io.github.muthuishere.toolnexus.agents.Agents.agent;
import io.github.muthuishere.toolnexus.agents.*;

Agents.Agent explore = agent("explore", new Agents.AgentSpec()
    .does("read-only research")
    .tools(lookup));
Agents.Agent coder = agent("coder", new Agents.AgentSpec()
    .does("implements changes")
    .soulFile(Path.of("AGENTS.md"))
    .team(explore)                       // team = the task tool's only targets; no team ⇒ no task tool
    .budget(new Budget().maxTokens(10_000)));

TaskResult r = coder.run(new RuntimeOptions()
    .baseUrl("https://openrouter.ai/api/v1")
    .style("openai")
    .defaultModel("openai/gpt-4o-mini"), "fix the failing test");
System.out.println(r.status() + " " + r.text() + " " + r.totalTokens());
```

Full guide: [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/).

## API at a glance

| Call | What it does |
|------|--------------|
| `Toolkit.create(Options)` | Build a toolkit from MCP config, skills dir, built-ins, agents, and custom tools. |
| `tk.register(Tool...)` | Add native/HTTP/custom tools at runtime (first-name-wins). Chainable. |
| `tk.addAgent(agent \| url)` | Register a remote A2A agent's skills as tools. |
| `tk.execute(name, args)` | Route a tool call to its `Tool`; returns a `ToolResult`. |
| `tk.toOpenAI()` / `toAnthropic()` / `toGemini()` | Emit the tool schema in a provider's format. |
| `tk.mcpStatus()` / `skillsPrompt()` | Per-server MCP status; the skills catalog for the system prompt. |
| `tk.serve(addr, ServeOptions)` | Serve the toolkit as an A2A agent; returns a stoppable `ServeHandle`. |
| `tk.close()` | Shut down MCP subprocesses (`AutoCloseable`). |
| `LlmClient.create(Options)` | Build the host loop (baseUrl, style, model, systemPrompt, store, hooks, retries, onMetric…). |
| `agent.run(prompt, tk)` | Stateless one-shot; returns `RunResult` (text, toolCalls, usage, messages, turns). |
| `agent.ask(prompt, tk, id)` | Stateful: remembers the thread via the `ConversationStore` (empty/null id ⇒ `run`). |
| `agent.ask(prompt, tk, id, onText)` | `ask` + a text-delta callback; still returns the final `RunResult`. |
| `agent.stream(prompt, tk, onEvent[, id])` | Same loop, incremental events; `id` overload loads before / saves on `done`. |
| `agent.metrics()` | Prometheus text exposition of cumulative metrics — mount at `GET /metrics`. |
| `LlmClient.ConversationStore` | `get(id)` / `save(id, messages)` — implement for file/db/redis memory. |

---

## Examples & build

```bash
# MCP + skills (no API key) — spawns npx @modelcontextprotocol/server-everything
./gradlew runBasic

# native + http + mcp + skills, live loop (needs a key)
OPENROUTER_API_KEY=... ./gradlew runAgent

# compile + assemble the jar (Java 21 toolchain)
./gradlew build
```

## Links

- [`../SPEC.md`](../SPEC.md) — the cross-language conformance contract.
- [`../README.md`](../README.md) — project overview + the five ports.
- Other ports: [JavaScript](../js) · [Python](../python) · [Go](../golang) · C#.
- [Maven Central](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus)
</content>
</invoke>
