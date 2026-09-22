# toolnexus (Java)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.muthuishere/toolnexus?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus)

**Every tool an LLM can call — MCP servers, agent skills, your own methods, and remote HTTP
endpoints — behind one uniform `Tool`, in one `Toolkit`, with a built-in agent loop.** Point a
client at any OpenAI- or Anthropic-style endpoint and run.

> **Right-sized.** A *library*, not a framework — no runtime, no DI container, no annotations you
> must adopt. Bring your own model endpoint; keep your own control flow. Use the whole agent loop,
> or take only the adapters and drive your own.

The Java port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, C#, Elixir and Clojure**. Built on the official
**MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`). Requires **Java 21+**.

## Install

Maven Central coordinate: **`io.github.muthuishere:toolnexus:0.20.0`**

```gradle
implementation 'io.github.muthuishere:toolnexus:0.20.0'
```

```xml
<dependency>
  <groupId>io.github.muthuishere</groupId>
  <artifactId>toolnexus</artifactId>
  <version>0.20.0</version>
</dependency>
```

## Quick start

```java
Toolkit tk = Toolkit.create(new Toolkit.Options());   // 10 built-in tools, on by default
LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl("https://openrouter.ai/api/v1").style("openai").model("openai/gpt-4o-mini"));
System.out.println(agent.run("List the files here, then read the largest one.", tk).text);
```

Add real tool sources by pointing at them:

```java
Toolkit tk = Toolkit.create(new Toolkit.Options()
        .mcpConfig("mcp.json")
        .skillsDir(List.of("skills")));
```

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | **[Java](https://muthuishere.github.io/toolnexus/api/java/)** |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all seven ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
