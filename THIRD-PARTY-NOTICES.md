# Third-party notices

toolnexus is MIT licensed (see [`LICENSE`](LICENSE)). This file inventories the third-party
dependencies each port declares, and their licenses.

**Nothing here is vendored.** No port bundles, shades, or redistributes third-party source or
binaries — every entry below is a *declared dependency* that the consumer's own package manager
resolves and fetches. `js/package.json` has no `bundledDependencies`; `clojure/` enforces this
mechanically via `deps-purity-check.sh`, which fails the build if the default transitive classpath
carries anything beyond Clojure + spec + koine. Consumers therefore receive these packages directly
from their upstreams, under those upstreams' own licenses and notices.

Every license below was read from the registry metadata for the pinned version, not from memory.
Last verified: 2026-08-16, against toolnexus 0.14.0.

## js — `toolnexus` (npm)

| dependency | version | license |
|---|---|---|
| `@modelcontextprotocol/sdk` | ^1.12.0 | MIT |
| `yaml` | ^2.9.0 | ISC |

## python — `toolnexus` (PyPI)

| dependency | version | license |
|---|---|---|
| `mcp` | >=1.0.0,<2.0.0 | MIT |
| `pyyaml` | >=6.0 | MIT |

## golang — `github.com/muthuishere/toolnexus/golang`

| dependency | version | license |
|---|---|---|
| `github.com/mark3labs/mcp-go` | v0.48.0 | MIT |
| `github.com/google/uuid` | v1.6.0 | BSD-3-Clause |
| `gopkg.in/yaml.v3` | v3.0.1 | MIT (portions Apache-2.0) |

Indirect, pulled in by the above: `github.com/google/jsonschema-go`, `github.com/spf13/cast`,
`github.com/yosida95/uritemplate/v3`.

## java — `io.github.muthuishere:toolnexus` (Maven Central)

| dependency | version | license |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp` | 2.0.0 | MIT |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.2 | Apache-2.0 |
| `org.yaml:snakeyaml` | 2.3 | Apache-2.0 |

## csharp — `Toolnexus` (NuGet)

| dependency | version | license |
|---|---|---|
| `ModelContextProtocol` | 1.4.0 | **Apache-2.0** |
| `YamlDotNet` | 16.3.0 | MIT |

`ModelContextProtocol` is Apache-2.0, unlike the MCP SDKs the other ports use — worth knowing if
you are assembling a per-language license report and assumed MCP is uniformly MIT.

## elixir — `toolnexus` (Hex)

The MCP client here is in-house (`Toolnexus.Mcp.*`), so there is no MCP SDK dependency.

| dependency | version | license |
|---|---|---|
| `jason` | ~> 1.4 | Apache-2.0 |
| `req` | ~> 0.5 | Apache-2.0 |
| `plug` | ~> 1.16 | Apache-2.0 |
| `bandit` | ~> 1.5 | MIT |
| `yaml_elixir` | ~> 2.9 | MIT |

Dev/test only, not shipped to consumers: `excoveralls`, `ex_doc`.

## clojure — `net.clojars.muthuishere/toolnexus` (Clojars)

| dependency | version | license |
|---|---|---|
| `org.clojure/clojure` | 1.12.5 | **EPL-1.0** |
| `net.clojars.muthuishere/koine` | 0.11.0 | MIT |

Clojure itself is Eclipse Public License 1.0 — the only copyleft license in the tree. It is a
declared dependency of a Clojure library, which is the ordinary arrangement for the ecosystem and
does not extend EPL terms to toolnexus.

Release tooling (`tools.build`) is reachable only through the `:build` alias and is never on a
consumer's classpath.

## Keeping this current

Re-verify when a dependency is added, removed, or upgraded across a major version. Read the license
from the registry for the pinned version rather than assuming continuity — an upstream can relicense
between releases, and the C# MCP SDK above is a reminder that sibling SDKs need not agree.
