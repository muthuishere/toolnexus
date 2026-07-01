# toolnexus (Java)

A small, provider-agnostic library that gives **any LLM** the two dynamic
capabilities opencode has:

1. **Dynamic MCP servers** — read an MCP config file, connect to every server
   (local stdio + remote streamable-HTTP), and expose each server tool as a
   uniform `Tool`.
2. **Dynamic agent skills** — read a skills folder (`**/SKILL.md`) and expose a
   single `skill` tool that loads a skill's instructions + resources on demand
   (progressive disclosure).

Plus **native (annotation) tools**, **HTTP/REST tools**, provider **adapters**
(OpenAI / Anthropic / Gemini schemas), and a **unified LLM client** host loop.

This is the Java port of the shared `toolnexus` contract (see `../SPEC.md`); it is
byte-for-byte behavior-identical to the `js/`, `python/`, and `golang/` ports.
Built on the official **MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`).

## Install

Gradle (`build.gradle`):

```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'io.github.muthuishere:toolnexus:0.1.0'
}
```

Requires Java 21+.

## Quickstart

```java
import io.github.muthuishere.toolnexus.*;
import java.util.Map;

Toolkit tk = Toolkit.create(new Toolkit.Options()
        .mcpConfig("mcp.json")        // path, raw JSON, or a parsed Map
        .skillsDir("./skills"));

System.out.println(tk.mcpStatus());   // {everything=connected, ...}
System.out.println(tk.skillsPrompt()); // skills catalog; opens with a preamble telling the model to use the skill tool

var tools = tk.toOpenAI();            // or toAnthropic() / toGemini()

// the model returns a tool call { name, args } ->
ToolResult res = tk.execute(name, args);
// feed res.output() back to the model, loop.

tk.close();
```

## Native (annotation) tools — the Spring-AI `@Tool` feel, vendor-neutral

Annotate plain methods with `@ToolMethod` (and optionally `@Param`); the JSON input
schema is inferred from the method's parameter types. Compile with `-parameters`
(this build does) so parameter names are available without `@Param(name=...)`.

```java
import io.github.muthuishere.toolnexus.Tools;
import io.github.muthuishere.toolnexus.annotations.ToolMethod;
import io.github.muthuishere.toolnexus.annotations.Param;

public class MathTools {
    @ToolMethod(name = "add", description = "Add two numbers and return the sum.")
    public String add(@Param(description = "first addend") double a,
                      @Param(description = "second addend") double b) {
        return String.valueOf(a + b);
    }
}

// reflect over @ToolMethod methods -> List<Tool> (source "native")
tk.register(Tools.fromObject(new MathTools()).toArray(new Tool[0]));

// or pass annotated objects straight into the toolkit:
Toolkit tk = Toolkit.create(new Toolkit.Options()
        .annotatedObjects(new MathTools()));
```

Programmatic form (no annotations):

```java
import io.github.muthuishere.toolnexus.NativeTool;

tk.register(NativeTool.of("echo", "Echo a message", schema,
        args -> "you said: " + args.get("message")));
```

## Built-in tools

A fifth source ships **10 built-in tools** — `bash`, `read`, `write`, `edit`,
`grep`, `glob`, `webfetch`, `question`, `apply_patch`, `todowrite`
(names + input schemas match opencode) — so an agent can act with zero wiring.
They appear in the tool schema (`toOpenAI()`/`toAnthropic()`/`toGemini()`), like
MCP tools — not the system prompt. **On by default.** One global toggle turns the
whole source off, or a per-tool `tools` map disables individual builtins on the
all-on baseline:

```java
Toolkit tk = Toolkit.create(new Toolkit.Options()
        .mcpConfig("mcp.json")
        .builtins(false));   // also accepts Map.of("disabled", true) / Map.of("enabled", false)

// per-tool: drop bash, keep the other nine (unknown names ignored; whole-source-off still wins)
Toolkit tk2 = Toolkit.create(new Toolkit.Options()
        .mcpConfig("mcp.json")
        .builtins(Map.of("tools", Map.of("bash", false))));
```

`bash`/`write`/`edit`/`apply_patch` run commands and mutate the filesystem — the
toggle is the off-switch for locked-down hosts.

## HTTP / REST tools

```java
import io.github.muthuishere.toolnexus.HttpTool;

HttpTool.Options o = new HttpTool.Options();
o.name = "get_post";
o.description = "Fetch a placeholder blog post by id.";
o.method = "GET";
o.url = "https://jsonplaceholder.typicode.com/posts/{id}"; // {id} from args
o.inputSchema = /* {type:object, properties:{id:{type:number}}, required:[id]} */;
tk.register(HttpTool.of(o));
```

`{placeholder}` URL substitution, `query` arg names → querystring, body
`json|form|raw`, and `${ENV}` header expansion from `System.getenv` (never logged).
Non-2xx ⇒ `HTTP <status>: <body>` error.

## Unified LLM client (the host loop)

```java
import io.github.muthuishere.toolnexus.LlmClient;

LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl("https://openrouter.ai/api/v1")
        .style("openai")               // "openai" | "anthropic"
        .model("openai/gpt-4o-mini")
        .systemPrompt("You are a precise agent."));
        // apiKey from opts or env: OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY

LlmClient.RunResult res = agent.run(
        "What is 21 + 21? Use the add tool.", tk);
System.out.println(res.text);          // "...42..."
System.out.println(res.toolCalls);     // [add, ...]
```

The system prompt is `systemPrompt + "\n\n" + toolkit.skillsPrompt()`. The loop
runs to `maxTurns` (default 10).

## A2A agents (agent-to-agent)

Call **remote A2A agents** (each of their skills becomes a tool) and serve your own toolkit as an
agent other A2A peers can call. A genuine, minimal subset of real A2A (JSON-RPC 2.0; Agent Card at
`/.well-known/agent-card.json`; `SendMessage` → poll `GetTask`). No streaming / push / auth in v1.

**Outbound — call a remote agent.** Each advertised skill becomes a tool named `<agent>_<skill>`
(source `"a2a"`):

```java
import io.github.muthuishere.toolnexus.A2A;

Toolkit tk = Toolkit.create(new Toolkit.Options()
        .agents(A2A.agent("https://researcher.example.com/.well-known/agent-card.json")));

// or add one at runtime (an A2A.Agent, or a bare card URL):
tk.addAgent("https://writer.example.com/.well-known/agent-card.json");
```

`A2A.agent(card[, headers, timeout, pollEvery])` — `headers` support `${ENV}` expansion (never
logged); `timeout` / `pollEvery` are milliseconds (300000 / 1000 defaults). A config file can also
carry an `agents` block (mirrors `mcpServers`). A failing agent is isolated — contributes no tools,
never fatal.

**Inbound — serve your toolkit as an agent.** The Agent Card is built from your **SKILL.md skills**
(never raw tools):

```java
import io.github.muthuishere.toolnexus.A2AServer;

A2AServer.ServeHandle handle = tk.serve("127.0.0.1:0", new Toolkit.ServeOptions()
        .client(agent)                         // the LlmClient host loop, above
        .a2a(new A2AServer.A2AConfig()
                .name("research-agent")
                .description("Answers research questions.")
                // .skills(List.of("hello-world"))  // subset of skills to advertise; omit ⇒ all
                .store("memory")));                 // "memory" (default) | "file:<dir>" | a custom TaskStore

System.out.println(handle.url());   // GET /.well-known/agent-card.json ; POST / (SendMessage / GetTask)
handle.stop();
```

`serve(addr, ServeOptions)` fulfils each inbound `SendMessage` task via `client.run`. Task
persistence is a pluggable `TaskStore` — in-memory default, `"file:<dir>"`, or your own.

## Examples

```bash
# MCP + skills (no API key needed) — spawns npx @modelcontextprotocol/server-everything
./gradlew runBasic

# native + http + mcp + skills, live loop (needs a key)
OPENROUTER_API_KEY=... ./gradlew runAgent
```

## Build

```bash
./gradlew build      # compiles + assembles the jar (Java 21 toolchain)
```

The `maven-publish` plugin is configured (group `io.github.muthuishere`, artifact
`toolnexus`, version `0.1.0`) but nothing is published by default.
