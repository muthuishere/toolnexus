# Third-Party Licenses — toolnexus

toolnexus ships six language ports. Each port is published as a **library** to its
ecosystem's registry (Maven, npm, PyPI, Go modules, NuGet, Hex), so its dependencies are
**resolved at install time, not vendored or bundled** into anything we distribute. No
third-party source is redistributed in this repository.

This file is therefore attribution as good practice and as a licence audit — not the
mandatory redistribution notice that a bundled artifact requires. (By contrast,
`ctx-optimize` embeds third-party code in its binary and its notice *is* mandatory.)

toolnexus itself is MIT — see `LICENSE`.

## Summary

Every direct and indirect dependency across all six ports is permissive.
**No GPL / AGPL / LGPL / SSPL / MPL dependency exists in any port.**

| Licence | Count |
|---|---|
| MIT | 10 |
| Apache-2.0 | 6 |
| BSD-3-Clause | 2 |
| ISC | 1 |
| MIT + Apache-2.0 (dual) | 1 |

## Java (`java/build.gradle`)

| Dependency | Version | Licence |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp` | 2.0.0 | MIT |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.2 | Apache-2.0 |
| `org.yaml:snakeyaml` | 2.3 | Apache-2.0 |

Test-only (not distributed): JUnit 5 / junit-platform-launcher — EPL-2.0.

## JavaScript / TypeScript (`js/package.json`)

| Dependency | Version | Licence |
|---|---|---|
| `@modelcontextprotocol/sdk` | ^1.12.0 | MIT |
| `yaml` | ^2.9.0 | ISC |

Dev-only (not distributed): `typescript` (Apache-2.0), `@types/node` (MIT).

## Python (`python/pyproject.toml`)

| Dependency | Version | Licence |
|---|---|---|
| `mcp` | >=1.0.0 | MIT |
| `pyyaml` | >=6.0 | MIT |

Test-only (not distributed): `pytest`, `pytest-asyncio` — MIT.

## Go (`golang/go.mod`)

| Dependency | Version | Licence |
|---|---|---|
| `github.com/mark3labs/mcp-go` | v0.48.0 | MIT |
| `github.com/google/uuid` | v1.6.0 | BSD-3-Clause |
| `gopkg.in/yaml.v3` | v3.0.1 | MIT **and** Apache-2.0 (dual) |
| `github.com/google/jsonschema-go` _(indirect)_ | v0.4.2 | MIT |
| `github.com/spf13/cast` _(indirect)_ | v1.7.1 | MIT |
| `github.com/yosida95/uritemplate/v3` _(indirect)_ | v3.0.2 | BSD-3-Clause |

`gopkg.in/yaml.v3` states: *"This project is covered by two different licenses: MIT and
Apache."* The libyaml-derived files (`apic.go`, `emitterc.go`, `parserc.go`, `readerc.go`,
`scannerc.go`) remain under their original MIT licence; the remainder is Apache-2.0.
Copyright 2011-2016 Canonical Ltd.

## C# (`csharp/src/Toolnexus/Toolnexus.csproj`)

| Dependency | Version | Licence |
|---|---|---|
| `ModelContextProtocol` | 1.4.0 | Apache-2.0 |
| `YamlDotNet` | 16.3.0 | MIT |

## Elixir (`elixir/mix.exs`)

| Dependency | Version | Licence |
|---|---|---|
| `jason` | ~> 1.4 | Apache-2.0 |
| `yaml_elixir` | ~> 2.9 | MIT |
| `req` | ~> 0.5 | Apache-2.0 |
| `plug` | ~> 1.16 | Apache-2.0 |
| `bandit` | ~> 1.5 | MIT |

Dev/test-only (not distributed): `excoveralls` (MIT), `ex_doc` (Apache-2.0).

## Regenerating

Licences here were verified against each ecosystem's registry and the upstream repository,
not assumed from package names. Re-verify when bumping a dependency:

- Java: `./gradlew dependencies` + Maven Central POM `<licenses>`
- JS: `npx license-checker --production`
- Python: `pip-licenses`
- Go: `go-licenses report ./...`
- C#: `dotnet list package --include-transitive` + nuget.org licence expression
- Elixir: `mix hex.outdated` + hex.pm package metadata
