# api-reference-docs Specification

## MODIFIED Requirements

### Requirement: Published docs carry per-symbol API reference pages

The published documentation site SHALL document the public surface of every port (js, python,
golang, java, csharp, elixir) as **one page per top-level entry point per port**, addressable at
`/api/<lang>/<group>/<member>`. The documented surface SHALL be the top-level public API defined by
`SPEC.md` §1–§11 — core types, MCP load and inventory, skill load and inventory, toolkit
construction, built-in tools, native tools, HTTP tools, A2A outbound and inbound, MCP inbound,
sub-agents and the agent runtime, personas, compaction, the unified client and its loop seams,
suspension, and single-turn translation.

Data shapes that are not themselves entry points (option bags, result records) SHALL be documented
as field tables on the page of the entry point that consumes them, rather than receiving their own
URLs.

Each page SHALL name and link its five sibling-language equivalents, so a reader on any port can
navigate to the corresponding symbol.

#### Scenario: Every entry point has its own addressable page

- **WHEN** the docs site is built
- **THEN** every top-level entry point in `SPEC.md` §1–§11 resolves to a page at `/api/<lang>/<group>/<member>` for all six ports

#### Scenario: A documented symbol links its cross-language equivalents

- **WHEN** a reader views the page for the skill-inventory entry point in any port
- **THEN** the page names the equivalent symbol in the other five ports and links to each of their pages

### Requirement: API reference stays in six-language parity

The API Reference SHALL be kept in parity across the six ports: when a change adds, renames, or
removes a public entry point in one port, the same change SHALL update the reference for every port
that carries that entry point, so the documented surface never drifts between languages.

#### Scenario: A new public entry point is documented for all ports that ship it

- **WHEN** a change introduces a new public toolkit option in all six ports
- **THEN** the API Reference documents that option for all six ports in the same change, with no port left undocumented

## ADDED Requirements

### Requirement: Every API reference page explains when and why to use the entry point

Each API reference page SHALL carry, in addition to the signature: a **when to use it** statement
naming the situation the entry point is for, a **why** statement naming the alternative and when to
prefer it instead, and **three worked examples** ordered from simplest to most complete.

Examples SHALL be derived from code that exists in this repository — the shared `examples/`
fixtures, the per-port `examples/` programs, or the port test suites — rather than invented for the
documentation.

#### Scenario: A page without when/why fails review

- **WHEN** a page is added for an entry point but omits the when-to-use or why sections
- **THEN** the docs coverage gate reports the page as incomplete and the build fails

#### Scenario: Examples are ordered by increasing completeness

- **WHEN** a reader opens any entry point page
- **THEN** it shows three examples, the first minimal and the last exercising the entry point's full documented surface

### Requirement: Documentation examples are compiled and executed in CI

Every example on an API reference page SHALL be extracted into a real per-language project,
compiled, and executed in CI. Execution SHALL be hermetic — no network and no live LLM — running
against the shared `examples/` fixtures with a mock LLM.

An example that fails to compile or fails at runtime SHALL fail the build, so documentation cannot
drift from the API it describes.

#### Scenario: A renamed symbol breaks the docs build

- **WHEN** a port renames a public entry point without updating its API reference pages
- **THEN** the extracted example for that page fails to compile and CI fails

#### Scenario: Examples run without network or a live LLM

- **WHEN** the docs example suite runs in CI
- **THEN** every example completes using only the shared `examples/` fixtures and the mock LLM, making no network calls

### Requirement: A coverage gate proves every entry point is documented

The docs build SHALL derive the required page set from a manifest of `SPEC.md`'s top-level entry
points and SHALL fail if any entry point lacks a page in any of the six ports, or if a page exists
for an entry point absent from the manifest.

#### Scenario: A missing port page fails the build

- **WHEN** an entry point has pages for five ports but not the sixth
- **THEN** the coverage gate names the missing port and entry point and the build fails

#### Scenario: An orphaned page fails the build

- **WHEN** a page exists at `/api/<lang>/<group>/<member>` with no corresponding manifest entry
- **THEN** the coverage gate reports the orphaned page and the build fails
