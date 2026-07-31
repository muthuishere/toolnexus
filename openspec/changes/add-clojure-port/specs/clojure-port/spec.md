## ADDED Requirements

### Requirement: One source, two runtimes

The Clojure port SHALL be a single `.cljc` tree that runs unmodified on Clojure
(JVM) and on cljgo, and SHALL contain no reader conditionals of its own. Every
host-touching operation SHALL go through koine, which is the only place a reader
conditional belongs.

#### Scenario: The same file runs on both runtimes

- **WHEN** the port is run against the shared `examples/` fixtures on Clojure (JVM), on a `cljgo build` AOT binary, and on `cljgo run` interpreted
- **THEN** all three produce byte-identical output apart from the runtime's own name

#### Scenario: No reader conditional in the port's own source

- **WHEN** the port's `.cljc` sources are searched for `#?`
- **THEN** there are no matches, and there are no `java.*` class references and no Go interop

### Requirement: Conformance against the shared fixtures

The Clojure port SHALL satisfy `SPEC.md §0` measured against the shared
`examples/` fixtures — the same `mcp.json` and `skills/` the other six ports run —
and SHALL NOT introduce Clojure-specific fixtures.

#### Scenario: Tool sources unify

- **WHEN** a toolkit is built from the shared `examples/mcp.json` and `examples/skills/`
- **THEN** MCP tools are named `sanitize(server)_sanitize(tool)`, the `skill` tool's output is byte-exact per §0.6, and the OpenAI / Anthropic / Gemini adapters emit the §0.7 shapes

#### Scenario: A failed server is isolated

- **WHEN** one configured MCP server cannot be reached
- **THEN** that server's status is `failed`, every other server's tools remain available, and no exception escapes to the caller

### Requirement: A test suite that cannot be silently empty

The port's test suite SHALL be gated on a nonzero collected-test count and SHALL
fail when zero tests are collected, because on cljgo a process that collects
nothing still exits 0.

#### Scenario: An empty suite fails rather than passing

- **WHEN** the test entry point runs against a target from which zero tests are collected
- **THEN** the gate fails with a named error rather than reporting success

#### Scenario: The same suite runs on both runtimes

- **WHEN** the suite is run on Clojure (JVM) and on cljgo
- **THEN** both report the same test and assertion counts

### Requirement: Frontmatter parsing is strict

The port SHALL parse `SKILL.md` frontmatter with a documented subset parser named
`frontmatter`, never presented as a general YAML parser, and it SHALL throw a
named error on any construct outside the subset rather than misparse it.

#### Scenario: An unsupported construct is rejected

- **WHEN** frontmatter contains a block scalar, an anchor, or a nested mapping
- **THEN** parsing throws a named error identifying the unsupported construct, and no skill is silently registered with wrong metadata
