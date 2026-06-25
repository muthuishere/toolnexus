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
System.out.println(tk.skillsPrompt()); // ## Available Skills ...

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
