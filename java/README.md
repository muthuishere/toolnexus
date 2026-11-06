# toolnexus (Java)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.muthuishere/toolnexus?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.muthuishere/toolnexus)

**Every tool an LLM can call — MCP servers, agent skills, your own methods, and remote HTTP
endpoints — behind one uniform `Tool`, in one `Toolkit`, with a built-in agent loop.** Point a
client at any OpenAI- or Anthropic-style endpoint and run.

> **Right-sized.** A *library*, not a framework — no runtime, no DI container, no annotations you
> must adopt. Bring your own model endpoint; keep your own control flow. Use the whole agent loop,
> or take only the adapters and drive your own.

The Java port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, C#, Elixir, Clojure and Rust**. Built on the official
**MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`). Requires **Java 21+**.

## Install

Maven Central coordinate: **`io.github.muthuishere:toolnexus:0.25.0`**

```gradle
implementation 'io.github.muthuishere:toolnexus:0.25.0'
```

```xml
<dependency>
  <groupId>io.github.muthuishere</groupId>
  <artifactId>toolnexus</artifactId>
  <version>0.25.0</version>
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

## API key by host (breaking in 0.22.0)

With no `apiKey`, the client reads `apiKeyEnv` (the NAME of an env var) if set, else ONLY the env
var of the `baseUrl` host over https: `api.openai.com` → `OPENAI_API_KEY`, `api.anthropic.com` →
`ANTHROPIC_API_KEY`, `openrouter.ai` → `OPENROUTER_API_KEY`. A local or custom `baseUrl` (llama.cpp,
Ollama, a proxy) must set one of the two; the old cross-provider env fallback is gone.

```java
LlmClient local = LlmClient.create(new LlmClient.Options()
        .baseUrl("http://localhost:8080/v1").model("local-model")
        .apiKeyEnv("LOCAL_LLM_KEY"));   // or .apiKey("YOUR_KEY_HERE")
```

## Prompt cache and tool tiers

```java
LlmClient agent = LlmClient.create(new LlmClient.Options()
        .baseUrl("https://api.anthropic.com").style("anthropic").model("claude-sonnet-4-5")
        .promptCache(new PromptCache())                               // cache breakpoints; openai: .key("session-1")
        .toolTiers(new ToolTiers().core("read", "grep", "edit")));    // the rest via tool_search / call_tool
var res = agent.run("Fix the failing test.", tk);
long context = res.usage.promptTokens + res.usage.cacheReadTokens + res.usage.cacheWriteTokens; // anthropic
agent.toolTierPlan(tk);    // name -> CORE | LISTED | DEFERRED, frozen per toolkit
agent.resetToolTiers(tk);  // rebuild at the next session start
```

Runnable, hermetic: `./gradlew runPromptCache`.

## Host additions (0.22.0)

`Toolkit.register` is safe mid-turn; `McpSource.load(cfg, new McpSource.LoadOptions().onServer(...))`
reports each server as it is ready; `Options.waitForCtx((request, cancelToken) -> answer)` ends a
pending ask with its turn; `Compaction.Options.onCompact` reports each compaction; tools carry
`ToolHints` (`Tool.withHints`, `BeforeToolEvent.hints()`) for permission gates in `beforeTool`;
opt-in builtins via the builtins config keys `background` (a `BackgroundShells`), `webSearch` (a
`WebSearch`) and `notebookEdit`; `McpSource.resourceTools()`; `grep` `context`/`before`/`after` and
truncation metadata; `Agent.asToolWith(ctx -> runtimeOptions)` with `RuntimeOptions.waitFor`;
`ToolContext.callId()`; `LlmClient.listModels(options)`. The ACP host hooks (permission callback,
update stream, cancel) are Go-only for now; ACP streaming is `AcpClient.generateStream`.

## Simple judgments

A thin layer over `Classifier` (SPEC.md §8B); the wire is unchanged.

```java
import static io.github.muthuishere.toolnexus.Judge.*;

var state = State.of("You are Donkey Kong, you want to win.",
        Map.of("message_received", "jump off the stage now"));
var a = ask(classifier, state, List.of(
        noul("is_appropriate", "Does message_received contain insults or harmful topics?"),
        noul("does_this_help", "Does message_received help donkey kong win?")));
a.get("is_appropriate").band();   // YES | NO | UNCERTAIN (cut-points 0.30 / 0.70, exclusive)

Outcome o = gate(classifier, state, questions, List.of(
        Rule.below("fixable", 0.3, "fail"),
        Rule.is("component", "pricing", "skip_to", "fix-pricing")));
// unsure or missing answer -> o.action() == "needs_input", o.request() is a §10 input Request
```

`Policy` adds a default action and `skipUncertain`; `Tape` records live decisions by call name
and replays them offline; `Classifier.fromRecorded(...)` is the one-line hermetic classifier;
`classifier.evaluateBatch(states, questions)` asks the same questions of many states (state order,
fail-closed, 16 in flight). Name the state field each question judges; never copy the role into it.

Eight ready-made batteries (SPEC.md §8B *Batteries*) wrap a `Classifier`: `ToolGuardClassifier`,
`ToolRelevanceClassifier`, `SkillRelevanceClassifier`, `ToolResultFilterClassifier`,
`IsCompleteClassifier`, `AgentRouterClassifier`, `ContentGuardClassifier`, `ModelRouterClassifier`.
Each has a standalone method returning a typed `Verdict` and, where a seam exists, `asHook(next)`:

```java
var guard = new ToolGuardClassifier(classifier, new ToolGuardClassifier.Options().onError(Batteries.OnError.CLOSED));
var router = new ModelRouterClassifier(classifier, List.of(
        new ModelRouterClassifier.Model("small-fast", "Short factual answers; cheapest."),
        new ModelRouterClassifier.Model("large-reasoning", "Multi-step reasoning; most expensive.")));
new LlmClient.Hooks().beforeTool(guard.asHook(null)).beforeLLM(router.asHook(null));
```

A `beforeLLM` hook may return `LLMOverride.withModel(id)`: that turn only is sent to `id`.

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```java
Media media = Media.create(Media.loadStyle("examples/media-styles/a2e.json"),
        new Media.Options().onProgress(p -> System.out.println(p.verb() + " " + p.status())));
var img = media.image(new Media.ImageInput("a red fox in fresh snow", 1024, 768, null, null, null));
var vid = media.video(new Media.VideoInput("the fox blinks", img.files().get(0).url(), 5, null, null));

Toolkit tk = Toolkit.create(new Toolkit.Options().media(media));   // generate_image, …
var res = agent.run("Draw a fox, then animate it.", tk);
for (var f : res.files) System.out.println(f.from() + " " + f.mimeType() + " " + f.url());
```

## Embeddable MCP server

`toolkit.mcpHandler(cfg)` is a streamable-HTTP MCP server as a JDK `HttpHandler`, to mount on your
own `HttpServer` behind your own TLS and auth. Every policy is a per-request hook: `authenticate`
(401 + `WWW-Authenticate`), `listTools` (each caller sees its own tools; a name outside the list is
never executed), `beforeCall` (allow / deny / confirm through MCP elicitation), `onCall`, resources,
prompts and pluggable session ids. The identity, HTTP exchange, session id and request `_meta`
reach every hook and, through `ToolContext.mcp()`, the tool. SPEC §7C.1; run
`./gradlew runMcpHandler` for the full demo.

```java
HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // confirms wait on a second request
server.createContext("/mcp", tk.mcpHandler(new McpHandler.Config()
        .authenticate(ex -> verifyBearer(ex.getRequestHeaders().getFirst("Authorization")))
        .listTools(ctx -> toolsFor(ctx.identity()))
        .beforeCall((ctx, tool, rawArgs) -> tool.annotations() != null
                && Boolean.TRUE.equals(tool.annotations().destructiveHint())
                ? McpHandler.Decision.confirm("Really delete?") : McpHandler.Decision.allow())));
server.start();
```

`ToolResult.structured(...)` / `withMeta(...)` and `Tool.withOutputSchema` / `withAnnotations`
are sent as `structuredContent`, `_meta`, `outputSchema` and `annotations`, also on the existing
`serve(addr, mcp)` path. Without them, the output is the same as before.

### MCP 2026-07-28 (opt-in)

List `"2026-07-28"` in `protocolVersions` and the same handler also speaks the stateless 2026 wire:
`server/discover`, header validation, `resultType` + `_meta` serverInfo, and MRTR instead of
elicitation (a `confirm` decision or a §10 tool suspension comes back as `input_required`; the
client's retry runs the tool with the `Answer` on `ctx.answer()`). Without it the handler is
unchanged, and `initialize` never negotiates 2026-07-28. `listSkills` publishes skills through
the skills extension (`skills/list`, `skills/get`, `resources/read` of `skill://` URIs);
`McpHandler.loadMcpSkills(dir)` builds that list from a skills directory. `McpServerAuth` makes the
handler an OAuth protected resource: `authenticate` verifies RS256 JWTs against the issuer's JWKS
(or your own `verify`), checks per-tool scopes, and answers 401/403 with the exact challenge;
`metadata()` serves the RFC 9728 document. SPEC §7C.2.

```java
McpServerAuth auth = new McpServerAuth("https://login.example")
        .scopes(Map.of("*", List.of("mcp"), "delete_record", List.of("mcp:admin")));
server.createContext("/mcp", tk.mcpHandler(new McpHandler.Config()
        .protocolVersions("2026-07-28", "2025-06-18")      // both wires on one mount
        .authenticate(auth::authenticate)
        .listSkills(ctx -> McpHandler.loadMcpSkills(Path.of("skills")))));
server.createContext("/.well-known/oauth-protected-resource", auth.metadata());
```

Bring your own authorization server: `McpServerAuth` verifies its tokens. The auto-approving dev authorization server the suite uses is test tooling (`src/test/java/.../McpDevAuthServer.java`), not part of the library.

## Agents via ACP

toolnexus ships no per-agent client; it ships three vendor-neutral pieces (SPEC §8C) and driving a
particular agent is a recipe on top of them:

- `toolkit.asMcpServer(cfg?)` serves the toolkit on `127.0.0.1:<ephemeral>/mcp` behind a fresh
  bearer token (`McpServe.LocalServer`: `url()`, `token()`, `close()`).
- `AcpClient.start(new AcpClient.Options()...)` is a plain ACP client: `mcpServers` (raw JSON
  objects, sent verbatim), `sessionMeta`, `clientCapsMeta` and `model` are passed through unchanged.
- `InProcess.Options.generateStream(...)` over `acp.generateStream(req, emit)` makes it a streaming
  `LlmClient` (it throws `IOException`, so wrap it as below; `generate(acp)` works as-is for `run`).

```java
// import io.github.muthuishere.toolnexus.acp.AcpClient;  (the rest are in io.github.muthuishere.toolnexus)
try (Toolkit tk = Toolkit.create(new Toolkit.Options().skillsDir("./skills"));
     McpServe.LocalServer srv = tk.asMcpServer();
     AcpClient acp = AcpClient.start(new AcpClient.Options()
             .command(List.of("my-agent", "acp"))
             .mcpServers(List.of(Map.of("type", "http", "name", "toolnexus", "url", srv.url(),
                     "headers", List.of(Map.of("name", "Authorization", "value", "Bearer " + srv.token())))))
             .timeoutMs(300_000))) { // per request incl. a whole turn; the 10 s default is too short for a real agent
    LlmClient client = InProcess.createClient(new InProcess.Options().model("acp")
            .generateStream((req, emit) -> {
                try { return acp.generateStream(req, emit); } catch (IOException e) { throw new UncheckedIOException(e); }
            }));
    client.stream("What is my badge number?", tk, ev -> { /* TEXT, THOUGHT (via "acp"), DONE ... */ });
}
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

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).

## MCP 2026-07-28 + OAuth

`McpSource` / `Toolkit` speak MCP **2026-07-28** first (SPEC §2): every server gets `server/discover`
(stdio and HTTP). A server that answers it is used statelessly (no `initialize`, no session) with
`_meta` + `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name` headers, JSON or SSE replies, and MRTR
`input_required` rounds routed through `waitFor` (or returned as a `pending` result without one; answer
it by re-running the tool with `ToolContext.answer`). An older server falls back to the frozen
SDK-based path (§2.F), unchanged. A `tools/call` is never re-sent after a timeout, lost connection or 5xx.

Per-server extras in `mcp.json`:

```json
{ "mcpServers": { "docs": {
    "url": "https://mcp.example.com/mcp",
    "toolSearch": true, "description": "the docs server",
    "auth": { "clientId": "my-registered-app", "mode": "auto" }
} } }
```

- `toolSearch` exposes `docs_search_tools` + `docs_call_tool` instead of every tool (BM25, §2.8).
- Skills served via the skills extension join the `skill` tool (§2.7).
- `auth` (§2A.1): PRM → AS metadata → pre-registered `clientId`/`clientIds` or `clientMetadataUrl`
  (CIMD) — no dynamic client registration — PKCE S256 + `resource`, `iss`/`state` checks, single-flight
  refresh, step-up. `mode: "auto"` opens the browser and listens on loopback; `mode: "pause"` (needs
  `redirectUri`) raises a §10 `authorization` request — answer it with
  `new Answer(id, true, Map.of("callbackUrl", "<URL the AS redirected to>"))`.
- Load options: `new McpSource.LoadOptions().tokenStore(store).openUrl(url -> ...)` (same on
  `Toolkit.Options`). Tokens are never logged.

The frozen fallback carries the bearer token too (the SDK's per-request customizer) and runs the same
OAuth flow on a 401/403. Not on the 2026 path yet: `list_mcp_resources`/`read_mcp_resource`
(`resourceTools()`) only see fallback servers; no progress notifications (per §2.9).
