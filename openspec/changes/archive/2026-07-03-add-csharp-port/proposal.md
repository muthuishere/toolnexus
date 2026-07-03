## Why

toolnexus ships four ports (js / python / golang / java) at `0.1.0`, all live-verified against
the shared `examples/` fixtures. C#/.NET is the most-requested missing ecosystem; adding it makes
the "any language" claim true for the .NET world and exercises the conformance contract from a
fifth angle.

## What Changes

- Add a new `csharp/` port: a .NET class library `Toolnexus` implementing the full contract in
  `SPEC.md` — core types, MCP source, skill source, toolkit + OpenAI/Anthropic/Gemini adapters,
  native (attribute) tools, HTTP tools, and the unified LLM client (loop, hooks, streaming,
  resilience, memory).
- MCP transport via the official `ModelContextProtocol` NuGet SDK (currently preview).
- Hermetic test suite (`dotnet test`) mirroring the other ports — no network, no live LLM.
- Examples mirroring the others, including the live `Openrouter` round trip.
- A CI job (`csharp`) added to `.github/workflows/ci.yml`.
- Packaging metadata for NuGet (`Toolnexus`), version `0.1.0`.

No change to `SPEC.md` behavior — this is a conforming implementation, not a contract change.

## Capabilities

### New Capabilities
- `csharp-port`: the C#/.NET implementation of the toolnexus contract, conforming to `SPEC.md §0`
  and at parity with the other four ports against the shared `examples/`.

### Modified Capabilities
<!-- none — SPEC.md behavior is unchanged -->

## Impact

- New `csharp/` directory (library + tests + examples + `.csproj`/solution).
- `.github/workflows/ci.yml` gains a `csharp` job.
- `PUBLISHING.md` gains a NuGet section.
- Dependency: `ModelContextProtocol` (preview) + a JSON stack (System.Text.Json).
