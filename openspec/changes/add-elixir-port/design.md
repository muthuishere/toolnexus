# Design: add-elixir-port

## Context

Sixth port of a byte-pinned contract. The JS port is the reference; Go is the closest structural
guide for a non-JS port (explicit concurrency, no classes). The port must be idiomatic Elixir
inside and byte-identical outside.

## Goals / Non-Goals

**Goals**
- Full `SPEC.md §0` (items 1–12) conformance at `elixir/`, tested against shared `examples/`.
- BEAM-idiomatic internals: supervised MCP connections, Task-based fan-out, no global state.
- CI + release wiring in the same PR; publish itself stays owner-gated.

**Non-Goals**
- No new behavior, no contract changes — a pure port.
- No agent-pipeline work (that builds on this later).
- No GenServer-per-toolkit resident API beyond what the contract needs (that is pipeline-era
  design; here a toolkit is a value + supervised connection processes, like the other ports).

## Decisions

### D1 — Package layout and naming

Mix project `elixir/`, app `:toolnexus`, module namespace `Toolnexus`. Public surface mirrors the
Go port's flat entry points, spelled Elixir-style:

| Contract | Elixir |
|---|---|
| `createToolkit` | `Toolnexus.create_toolkit(opts)` |
| `loadMcp` / `ListMcpTools` | `Toolnexus.Mcp.load/2` · `Toolnexus.Mcp.list_tools/2` |
| `loadSkills` / `ListSkills` | `Toolnexus.Skill.load/1` · `Toolnexus.Skill.list/1` |
| `defineTool` | `Toolnexus.define_tool/1` |
| adapters | `Toolnexus.Adapters.to_openai/1` etc. |
| `CreateClient` / run / ask / stream | `Toolnexus.Client` |
| serve (A2A + MCP inbound) | `Toolnexus.Serve` (Plug + Bandit) |

`Tool`, `ToolResult`, `Context`, `Request`, `Answer` are structs with `@derive Jason.Encoder`
where they serialize; JSON key spellings pinned by the contract, not by Elixir naming.

### D2 — MCP client: **our own implementation** (owner decision 2026-07-15)

No third-party Elixir MCP SDK. The community options (hermes_mcp → anubis_mcp fork) are young,
maintainer-churned, and would put the elicitation bridge at the mercy of someone else's handler
surface. toolnexus needs only the MCP **client tool-use path** (§2), which is a small, stable
protocol slice — so the Elixir port owns it end to end, `Toolnexus.Mcp.*`:

- **`Protocol`** — JSON-RPC 2.0 codec + the MCP methods we use: `initialize` (+ 
  `notifications/initialized`), paginated `tools/list`, `tools/call`, inbound
  `elicitation/create`, `ping`. Protocol version pinned to the same revision the other ports'
  SDKs negotiate; unknown server capabilities ignored.
- **`Transport.Stdio`** — an Erlang `Port` (`:spawn_executable`, binary, line-buffered on our
  side; MCP stdio framing is newline-delimited JSON). Child env built per config (`${ENV}`
  use-only). Port owned by the connection GenServer; child dies with the owner — ADR-0007's bug
  class is structurally impossible, and the regression test asserts child liveness anyway.
- **`Transport.StreamableHttp`** — `req`/Finch POST with `Accept: application/json, text/event-stream`;
  our own incremental SSE parser for streamed responses; session id header carried per spec;
  legacy SSE fallback implemented the same way the JS/Go ports do it (GET stream + POST endpoint).
- **`Connection`** — one GenServer per server: owns the transport, correlates request ids,
  enforces per-phase timeouts, dispatches inbound `elicitation/create` to the §10 bridge. All
  connections sit under a `DynamicSupervisor` owned by the McpSource; `close()` terminates them.
- **Elicitation** — because we own the client, **both form and URL mode ship** — the Elixir port
  has no SDK-gap asterisk (first port for which that's true).

Upside: zero SDK dependency, full §10 bridge, BEAM-native lifetimes. Cost: we maintain a protocol
slice (~small: two transports + codec) — priced in by the owner ("totally ours").

### D2a — Test coverage gate: ≥ 95%

`excoveralls` wired as the coverage tool; CI enforces **≥ 95% line coverage** on `elixir/lib/`
(`mix coveralls --min-coverage 95` equivalent via `coveralls.json` minimum_coverage). The
protocol/transport layer we now own is exactly the code that must earn that bar: every codec
branch, timeout path, isolation path, and elicitation mode has a test. In-repo stdio + HTTP stub
servers (test/support) keep it hermetic.

### D3 — Concurrency map

- **Server fan-out at load**: `Task.async_stream(servers, &connect/1, timeout: :infinity)` with
  per-task `cfg.timeout` enforcement inside — mirrors Go's WaitGroup + per-phase timeout.
- **Parallel tool calls in the loop**: `Task.async_stream(calls, &execute/1, ordered: true)` —
  concurrency plus original-order results, which §8 requires.
- **Cancellation**: the load takes an optional deadline; connect/init/list run under
  `Task.yield/shutdown` so a caller timeout kills in-flight work (ADR-0001 gap 3 semantics).
- **stdio child lifetime**: owned by the anubis client process, killed on client termination —
  ADR-0007's bug class is structurally absent, but the regression test is ported anyway.

### D4 — Byte-exact surfaces

The three high-scrutiny templates — `skill` tool output (§3), `skillsPrompt()`, `metrics()`
Prometheus text (§8) — are implemented as literal heredoc templates copied from the JS reference,
with the conformance tests asserting equality against golden strings taken from the shared
fixtures. String building uses iodata but the joined result is compared byte-for-byte.

### D5 — HTTP stack

`req` (on Finch) for the client LLM path and http tools; injectable per ADR-0001 gap 2 by
accepting a `req` request struct/options override. `plug` + `bandit` for inbound serve (§7B/§7C)
— Bandit is pure-Elixir, keeps the dependency tree small, and `serve` returns a supervised
listener the caller can stop (the other ports' `close()`).

### D6 — Frontmatter and JSON

`yaml_elixir` for SKILL.md frontmatter (real YAML, scalar-flattening per current contract);
`jason` for JSON (Elixir 1.18's built-in JSON is close but jason keeps min-version at 1.16).
Map key order: everywhere the contract compares serialized JSON, tests compare decoded terms or
pinned key-ordered encoding, never accidental map order.

### D7 — CI and release

- CI: `erlef/setup-beam` (Elixir 1.16.x / OTP 26), `mix deps.get --only test` from cache,
  `mix test`. Hermetic: stdio fixture server is an Elixir script in `test/support/`, mirroring
  the other ports' in-repo fixture servers.
- Release: `mix hex.publish --yes` with `HEX_API_KEY` env (use-only, never echoed), job gated on
  `ENABLE_ELIXIR == 'true'`, preflight greps `elixir/mix.exs` version. Hex has no OIDC trusted
  publishing — the key is the one non-OIDC secret besides Maven's.

## Risks / Trade-offs

- **Owning the MCP protocol slice** → we maintain codec + two transports ourselves; bounded by
  the fact that toolnexus only uses the client tool-use path, and paid back by full elicitation
  control and zero SDK churn. The ≥95% coverage gate (D2a) is the guardrail.
- **Port size** → mitigated by strict JS-reference porting order (types → skills → adapters →
  native/http → toolkit → builtins → MCP → client → suspension → serve) with conformance tests
  landed per stage, not at the end.
- **Sixth-port maintenance cost** → accepted deliberately by the owner; every future behavior
  change now carries a six-language checklist.

## Open Questions

- None blocking.
