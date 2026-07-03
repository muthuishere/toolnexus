## Reference port

Use the **Java port as the structural reference** (closest analog: statically typed, class-based,
`Toolkit.create(...)` / `LlmClient` / attribute tools) and **`SPEC.md` as the contract**. Match
behavior, not syntax — idiomatic C#, not transliterated Java.

## Layout

```
csharp/
├── Toolnexus.sln
├── src/Toolnexus/Toolnexus.csproj        # the library (NuGet id: Toolnexus)
│   ├── Tool.cs ToolResult.cs ToolContext.cs
│   ├── McpSource.cs SkillSource.cs NativeTool.cs HttpTool.cs
│   ├── Toolkit.cs Adapters.cs LlmClient.cs Json.cs
│   └── Annotations.cs                     # [ToolMethod] / [Param]
├── tests/Toolnexus.Tests/                 # xUnit, hermetic
└── examples/Toolnexus.Examples/           # Basic, Agent, Openrouter, ...
```

## Key mappings (Java → C#)

| Concept | C# shape |
|---|---|
| `Tool` interface | `interface ITool { string Name; string Description; IDictionary<string,object?> InputSchema; string Source; Task<ToolResult> ExecuteAsync(IDictionary<string,object?> args, ToolContext? ctx); }` |
| `ToolResult` | `record ToolResult(string Output, bool IsError, IDictionary<string,object?>? Metadata)` |
| `@ToolMethod`/`@Param` | `[ToolMethod]` / `[Param]` attributes + reflection collector `Tools.FromObject(obj)` |
| MCP transport | official `ModelContextProtocol` SDK (preview) — stdio for `command`, HTTP for `url` |
| JSON | `System.Text.Json` (one `Json` helper for parse/serialize/pretty) |
| async | `Task`-based throughout (`ExecuteAsync`, `RunAsync`) |

## Conformance specifics (must match `SPEC.md`)

- `Sanitize(x)`: replace `[^a-zA-Z0-9_-]` with `_`; MCP tool name = `Sanitize(server)_Sanitize(tool)`.
- MCP config: accept `mcpServers` | `servers` | `mcp`; `url`⇒remote, `command`⇒local;
  `disabled`/`enabled:false` ⇒ skip; default timeout 30000ms; failed server isolated (status
  `failed`), never fatal; remote `headers` expand `${ENV_VAR}` and are never logged.
- Skill `skill` tool output is **byte-exact** (`SPEC.md §3`) — copy the format string exactly.
- Adapters: `ToOpenAI()` / `ToAnthropic()` / `ToGemini()` mapping per `SPEC.md §4`.

## Verification

- `dotnet build` + `dotnet test` (hermetic: local `HttpListener` stubs for HTTP/client tests,
  shared `examples/` for skills/MCP).
- Live: `Openrouter` example via `OPENROUTER_API_KEY` (inherited from the shell, never printed),
  asserting an `everything_echo` tool call — same proof the other four passed.

## Out of scope

- Changing `SPEC.md` behavior.
- NuGet publish automation (manual, like the others).
