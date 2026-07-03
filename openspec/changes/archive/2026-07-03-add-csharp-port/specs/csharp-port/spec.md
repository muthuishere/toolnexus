## ADDED Requirements

### Requirement: C# port conforms to the toolnexus contract
The `csharp/` port SHALL implement the full contract in `SPEC.md` and produce the same observable
behavior as the other ports when run against the shared `examples/` fixtures (`mcp.json` +
`skills/hello-world/`).

#### Scenario: Conformance against shared fixtures
- **WHEN** the C# port loads `examples/mcp.json` and `examples/skills/`
- **THEN** MCP tool names match `sanitize(server)_sanitize(tool)`, the `skill` tool emits the
  byte-exact `<skill_content>` / `<skill_files>` block defined in `SPEC.md §3`, and the
  OpenAI/Anthropic/Gemini adapter schemas match the other ports

### Requirement: C# port unifies all four tool sources
The port SHALL expose MCP tools, agent skills, native (attribute-annotated) methods, and HTTP
endpoints behind one `Tool` abstraction, aggregated by a `Toolkit`.

#### Scenario: All sources usable through one toolkit
- **WHEN** a caller builds a toolkit from `mcp.json`, a skills dir, a native tool, and an HTTP tool
- **THEN** all appear in `toolkit.Tools()` with the correct `source`, and `toolkit.Execute(name, args)`
  runs any of them, returning a `ToolResult { output, isError, metadata }`

### Requirement: C# unified client drives a real LLM
The port SHALL provide a unified client that runs the host loop over an OpenAI-style endpoint:
inject skills, call the model, execute requested tool calls, feed results back, and loop.

#### Scenario: Live OpenRouter round trip
- **WHEN** the `Openrouter` example runs with `OPENROUTER_API_KEY` in the environment
- **THEN** the model calls the `everything_echo` MCP tool, the toolkit executes it, and the final
  assistant message reflects the echoed string — never printing the API key

### Requirement: C# port has a hermetic test suite and CI
The port SHALL ship a `dotnet test` suite that runs with no network and no live LLM, and a CI job
that builds and tests it on push and PR.

#### Scenario: Hermetic CI run
- **WHEN** CI runs the `csharp` job
- **THEN** `dotnet build` and `dotnet test` pass using only local HTTP stubs and the shared
  `examples/` fixtures, with no outbound LLM or registry calls
