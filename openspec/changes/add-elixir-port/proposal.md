# Proposal: add-elixir-port

## Why

toolnexus exists to make the same agent toolkit substitutable across language stacks. The five
shipped ports (js/python/golang/java/csharp) all target request-scoped or CLI-shaped runtimes.
The **BEAM** (Elixir/OTP) is the runtime the others imitate for the workload agents are heading
toward: **long-running, supervised, fault-tolerant processes**. Supervision trees restart a
crashed MCP connection without taking the host down; lightweight processes make one-agent-per-
conversation cheap; `Task.async_stream` gives the parallel tool-call fan-out the client loop
already specs. The owner's direction (2026-07-15): ship the Elixir port **first**, then build the
agentic-workflow layer (`add-agent-pipeline`) on top — each pipeline stage maps naturally onto an
OTP process, so Elixir is the reference substrate for that future work, not an afterthought.

## What Changes

A sixth, fully conformant language port at `elixir/` — package **`toolnexus`** on Hex.pm —
satisfying the entire `SPEC.md §0` one-page conformance contract against the shared `examples/`
fixtures, byte-identical where the contract demands it (the `skill` tool output, `skillsPrompt()`,
tool naming/sanitization, adapter shapes, `Request`/`Answer` keys, `metrics()` Prometheus text).

Scope (all of `SPEC.md §0`, items 1–12):

- **Core types** — `Tool`, `ToolResult`, `Context` (§1) as Elixir structs.
- **MCP source** (§2) — stdio + streamable-HTTP client implemented **in-house**
  (`Toolnexus.Mcp.Protocol` / `Transport.Stdio` / `Transport.StreamableHttp`): no Elixir MCP SDK
  is mature enough, so the port owns the JSON-RPC 2.0 client slice end to end (owner decision
  2026-07-15). Config parsing (`mcpServers|servers|mcp`), `${ENV_VAR}` header expansion (never
  logged), per-server failure isolation, 30 s default timeout, ctx-aware cancellation, per-server
  `tools` allowlist, `ListMcpTools` inventory, and the elicitation bridge onto §10 with **both
  form and URL mode** — no SDK-gap asterisk, since we control the client.
- **Skill source** (§3) — glob discovery, frontmatter parsing, byte-exact `skill` tool output,
  skills-as-data/provider (S1), allowlist (S2), `ListSkills` inventory (S3), logical base (S4),
  sample cap (S5).
- **Toolkit** (§4) + **built-ins** (§4A, all ten tools) + **native** (§6) + **http** (§7) sources.
- **A2A outbound** (§7A) + **inbound serve** (§7B) + **MCP inbound serve** (§7C).
- **Adapters** (OpenAI / Anthropic / Gemini schema emission).
- **Unified client** (§8) — run/ask/stream loop, hooks, parallel tool calls, retries,
  conversation store, observability `metrics()`, ADR-0001 client shaping (RequestParams,
  BodyTransform, injectable HTTP client, store accessor, omit-empty-tools).
- **Suspension** (§10) — `Pending`/`waitFor`, byte-identical `Request`/`Answer` keys.
- **CI** — a sixth hermetic suite in `.github/workflows/ci.yml` (erlef/setup-beam).
- **Publishing** — a Hex.pm job in `release.yml` gated on `ENABLE_ELIXIR`; Hex has no OIDC
  trusted publishing, so it authenticates with a `HEX_API_KEY` secret (owner-supplied, use-only,
  never logged). Preflight extended to check the `mix.exs` version.
- **Docs** — `SPEC.md` "five languages" → "six" throughout; `CLAUDE.md` repo-layout row;
  `elixir/README.md`; site install/API pages follow later with the docs stream.

## Capabilities

### New Capabilities
- `elixir-port`: the Elixir/OTP implementation of the full §0 conformance contract, including
  BEAM-idiomatic construction (supervised MCP connections, Task-based parallel tool execution)
  with the same observable behavior as the other five ports.

### Modified Capabilities
<!-- No existing capability's requirements change; this adds a sixth implementation of the same
     contract. Cross-cutting docs (SPEC.md language lists, CI matrix) are impact, not spec deltas. -->

## Impact

- New directory `elixir/` (mix project, `lib/`, `test/`, `README.md`).
- `.github/workflows/ci.yml` — new `elixir` job (hermetic, no network).
- `.github/workflows/release.yml` — new Hex publish job + preflight version check.
- `SPEC.md` — language-count wording and port table; no behavioral contract change.
- `CLAUDE.md`, root `README.md` — repo layout and pitch mention the sixth port.
- Dependencies (Elixir): **no MCP SDK** (in-house client); `yaml_elixir` (frontmatter), `req`
  (HTTP), `jason` (JSON), `plug`+`bandit` (inbound serve), `excoveralls` (coverage gate ≥95%,
  test-only). Elixir ≥ 1.16 / OTP 26.
- Shared `examples/` fixtures are consumed unchanged (that is the point).
