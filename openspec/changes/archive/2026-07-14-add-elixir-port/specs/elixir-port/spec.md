# Delta for elixir-port

## ADDED Requirements

### Requirement: Elixir port satisfies the §0 conformance contract

The repository SHALL contain an Elixir port at `elixir/` (Hex package `toolnexus`) that
satisfies every numbered item of `SPEC.md §0` (items 1–12), producing outputs that match the
other five ports when run against the shared `examples/` fixtures. Byte-exact surfaces —
the `skill` tool output block, `skillsPrompt()`, MCP tool naming (`sanitize(server)_sanitize(tool)`),
adapter schema shapes, `Request`/`Answer` JSON keys, and the `metrics()` Prometheus text — SHALL
be identical to the existing ports byte for byte.

#### Scenario: Shared fixtures produce matching output

- **WHEN** the Elixir port builds a toolkit from `examples/mcp.json` and `examples/skills/` and
  invokes the `skill` tool for `hello-world`
- **THEN** the emitted `<skill_content>` block and the `skillsPrompt()` string are byte-identical
  to the output of the JS reference port on the same fixtures

#### Scenario: MCP tool naming and isolation match

- **WHEN** the Elixir port loads an MCP config containing one healthy server and one server whose
  command fails to start
- **THEN** the healthy server's tools are exposed as `sanitize(server)_sanitize(tool)`, the failed
  server has status `failed`, and the load returns without error

### Requirement: BEAM-idiomatic internals with unchanged observable behavior

The Elixir port SHALL use OTP primitives internally — supervised processes for MCP connections,
`Task`-based concurrency for parallel server loads and parallel tool calls — while keeping every
observable output identical to the shared contract. Idiom SHALL NOT leak into the wire surface:
error text, status values, JSON key spellings, and output templates follow `SPEC.md`, not Elixir
conventions (no `:ok`/`:error` tuples in serialized output).

#### Scenario: Parallel tool calls preserve contract ordering

- **WHEN** the client loop receives a model turn containing multiple tool calls
- **THEN** the calls execute concurrently and their results are fed back in the provider's
  tool-result shape in the original call order, as §8 requires

### Requirement: In-house MCP client with full elicitation bridge

The Elixir port SHALL implement the MCP client tool-use path itself — JSON-RPC 2.0 codec, stdio
transport (Erlang Port), and streamable-HTTP transport with SSE handling — with no third-party
MCP SDK dependency. Because the client is owned in-house, the §10 elicitation bridge SHALL
support **both** form mode (`Request{kind:"input"}`) and URL mode (`Request{kind:"authorization"}`).

#### Scenario: Elicitation form mode round-trips

- **WHEN** a connected MCP server sends `elicitation/create` with a requested schema during a
  tool call and the host supplied a `waitFor`
- **THEN** the bridge surfaces a `Request` with `kind:"input"` carrying the schema, and the
  `waitFor` answer is returned to the server as the elicitation result

#### Scenario: stdio child survives connect and dies with close

- **WHEN** a stdio server is connected and the toolkit later closes
- **THEN** the child process is alive between connect and close (ADR-0007 regression) and is
  terminated after close, with no orphaned process

### Requirement: Test coverage of at least 95 percent

The Elixir port SHALL enforce a minimum of 95% line coverage over `elixir/lib/` in CI via the
coverage tool's minimum-coverage gate, using hermetic in-repo stub servers for MCP stdio and
HTTP paths.

#### Scenario: Coverage gate fails below threshold

- **WHEN** the Elixir CI job runs the suite and line coverage falls below 95%
- **THEN** the job fails

### Requirement: CI runs a hermetic Elixir suite

`.github/workflows/ci.yml` SHALL run the Elixir test suite (`mix test`) hermetically — no
network, no live LLM — alongside the five existing suites, using the shared `examples/`
fixtures.

#### Scenario: CI includes the sixth suite

- **WHEN** CI runs on a pull request
- **THEN** an `elixir` job builds the mix project and runs `mix test` with the same hermetic
  constraints as the other five jobs

### Requirement: Hex.pm publishing wired but owner-gated

`release.yml` SHALL gain an Elixir publish job that publishes `toolnexus` to Hex.pm when the
repo variable `ENABLE_ELIXIR` is `true`, authenticating with a `HEX_API_KEY` secret that is
use-only and never logged. The preflight job SHALL verify the `elixir/mix.exs` version matches
the release version. The port SHALL NOT publish until the owner supplies the key and enables
the variable.

#### Scenario: Preflight blocks version drift

- **WHEN** a release `vX.Y.Z` is cut and `elixir/mix.exs` declares a different version
- **THEN** the preflight job fails the run before any port publishes
