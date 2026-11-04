## 1. Contract and shared data

- [x] 1.1 ADR 0037 accepted decisions recorded (owner, 2026-11-04); spike on `spike/cli-client`.
- [x] 1.2 `SPEC.md §8C` CLI client: enum, styles, transports, served toolkit, permissions, envelope, errors.
- [x] 1.3 `conformance/cli-styles/` — seven styles + `preamble.txt`, `acp` launches, verified notes.
- [ ] 1.4 Cross-port conformance cases (argv rendering, parser table, pinned errors) as a JSON fixture replayed by every port (Go asserts these in `cli_test.go` today).

## 2. golang (first port)

- [x] 2.1 `acp.go`: `MCPServers`, `ClientCapsMeta`, `Model`, `Stderr`, `terminal:false`, process group, fail-fast on exit, -32601 for unknown requests (spike follow-up d).
- [x] 2.2 `cli.go`: `CliStyles`/`CliTransports` enums, embedded styles, `CreateCliClient`, envelope render + parser, one-shot invoke with pinned errors.
- [x] 2.3 `cli_acp.go`: served toolkit with per-session bearer token (follow-up a), `load_skill` (follow-up b), per-toolCallId permissions (follow-up c), project MCP file, hooks at the server boundary, `RunMCPBridge`.
- [x] 2.4 `toolnexus mcp-bridge <url>` subcommand.
- [x] 2.5 Hermetic tests (fake ACP agent, fake CLI, bridge — the test binary): `go build ./... && go vet ./... && go test -race ./...`.
- [x] 2.6 Live check `golang/examples/cliclient` (results in `live-results.json`): claude + opencode over ACP and one-shot; copilot ACP; codex one-shot; devin ACP (needs the server named in the prompt).

## 3. Parity checklist (a port that has not shipped stays UNCHECKED)

- [ ] 3.1 js
- [ ] 3.2 python
- [x] 3.3 golang
- [ ] 3.4 java
- [ ] 3.5 csharp
- [ ] 3.6 elixir
- [ ] 3.7 clojure
- [ ] 3.8 rust

Each port: embed `conformance/cli-styles/` byte-identically (test-asserted), the enum (never a string),
required command, ACP with served toolkit + token + `load_skill` + per-id permissions, the one-shot
fallback with the pinned errors, and a stdio bridge story (the Go CLI's `mcp-bridge` is usable by all).

## 4. Documentation

- [x] 4.1 `CHANGELOG.md` `## Unreleased` entry naming the seven ports not done.
- [ ] 4.2 Per-port README sections once each port ships; `golang/README.md` / `GUIDE.md` section.
- [ ] 4.3 Owner: devin MCP-lookup hint (design "Open"); verify gemini one-shot and goose.
- [ ] 4.4 `conformance/options_manifest.json` row for the CLI client options once a second port lands.
