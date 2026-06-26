## 1. Project scaffold

- [ ] 1.1 Create `csharp/` solution: `Toolnexus` library, `Toolnexus.Tests` (xUnit), `Toolnexus.Examples`
- [ ] 1.2 Add `ModelContextProtocol` (preview) + System.Text.Json packages; target net8.0+

## 2. Core contract (SPEC §1–§4)

- [ ] 2.1 `ITool` / `ToolResult` / `ToolContext` + `Sanitize`
- [ ] 2.2 MCP source — config parse, stdio + remote, isolation, `${ENV_VAR}` headers, naming
- [ ] 2.3 Skill source — glob `**/SKILL.md`, frontmatter, byte-exact `skill` tool output
- [ ] 2.4 Native (`[ToolMethod]`/`[Param]`) tools via reflection
- [ ] 2.5 HTTP tools (url + headers, path/query/body params)
- [ ] 2.6 `Toolkit` aggregator + `ToOpenAI`/`ToAnthropic`/`ToGemini` adapters + `SkillsPrompt`

## 3. Unified client (SPEC §8)

- [ ] 3.1 `LlmClient` host loop (OpenAI + Anthropic styles), `RunResult` with usage/turns/toolCalls
- [ ] 3.2 Hooks (before/after LLM + tool), streaming, resilience (retries/backoff/timeout), memory

## 4. Tests (hermetic)

- [ ] 4.1 Port the other suites' coverage: sanitize, MCP config, skill output, toolkit, adapters, native, http, client resilience
- [ ] 4.2 `dotnet test` green with no network / no live LLM

## 5. Examples

- [ ] 5.1 Basic, Agent, Hooks, Streaming, Memory, Advanced mirroring the other ports
- [ ] 5.2 `Openrouter` live example (everything_echo via the unified client)

## 6. Verify + integrate

- [ ] 6.1 `dotnet build` + `dotnet test` pass
- [ ] 6.2 Live: run `Openrouter` with `OPENROUTER_API_KEY` (never printed) — assert everything_echo call
- [ ] 6.3 Add `csharp` job to `.github/workflows/ci.yml`
- [ ] 6.4 Add NuGet section to `PUBLISHING.md`; set package id `Toolnexus` v0.1.0
