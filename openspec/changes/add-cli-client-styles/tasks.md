## 1. Contract and shared data

- [x] 1.1 ADR 0037 accepted decisions recorded (owner, 2026-11-04); spike on `spike/cli-client`.
- [x] 1.2 `SPEC.md §8C` CLI client: enum, styles, transports, served toolkit, permissions, envelope, errors.
- [x] 1.3 `conformance/cli-styles/` — seven styles + `preamble.txt`, `acp` launches, verified notes.
- [x] 1.4 Cross-port conformance cases `conformance/cli-client/cases.json` (argv rendering, parser table, pinned errors, renderEmpty), replayed by all eight ports.

## 2. golang (first port)

- [x] 2.1 `acp.go`: `MCPServers`, `ClientCapsMeta`, `Model`, `Stderr`, `terminal:false`, process group, fail-fast on exit, -32601 for unknown requests (spike follow-up d).
- [x] 2.2 `cli.go`: `CliStyles`/`CliTransports` enums, embedded styles, `CreateCliClient`, envelope render + parser, one-shot invoke with pinned errors.
- [x] 2.3 `cli_acp.go`: served toolkit with per-session bearer token (follow-up a), `load_skill` (follow-up b), per-toolCallId permissions (follow-up c), project MCP file, hooks at the server boundary, `RunMCPBridge`.
- [x] 2.4 `toolnexus mcp-bridge <url>` subcommand.
- [x] 2.5 Hermetic tests (fake ACP agent, fake CLI, bridge — the test binary): `go build ./... && go vet ./... && go test -race ./...`.
- [x] 2.6 Live check `golang/examples/cliclient` (results in `live-results.json`): claude + opencode over ACP and one-shot; copilot ACP; codex one-shot; devin ACP (needs the server named in the prompt).

## 3. Parity checklist (a port that has not shipped stays UNCHECKED)

- [x] 3.1 js
- [x] 3.2 python
- [x] 3.3 golang
- [x] 3.4 java
- [x] 3.5 csharp
- [x] 3.6 elixir
- [x] 3.7 clojure
- [x] 3.8 rust

Each port: embed `conformance/cli-styles/` byte-identically (test-asserted), the enum (never a string),
required command, ACP with served toolkit + token + `load_skill` + per-id permissions, the one-shot
fallback with the pinned errors, and a stdio bridge story (the Go CLI's `mcp-bridge` is usable by all).

## 4. Documentation

- [x] 4.1 `CHANGELOG.md` `## Unreleased` entry naming the seven ports not done.
- [x] 4.2 Per-port README sections (all eight) and docs-site cookbook page `cookbook/agent-cli-as-model`.
- [x] 4.3a devin MCP-lookup hint: automatic, as style data (`acp.systemHint`), tested in every port.
- [ ] 4.3b Owner: verify gemini one-shot and goose (still UNVERIFIED).
- [x] 4.4 `conformance/options_manifest.json` `cliClientOptions` group (14 options, 8 ports) for the CLI client options once a second port lands.

## 5. Known per-port gaps (shipped, tracked)

- [ ] 5.1 Streaming over the ACP transport: Go only; the other seven refuse it with a port-named message.
- [ ] 5.2 Process groups: java, csharp, elixir, clojure kill the process tree instead; clojure's ACP close kills only the agent process (an `npx` adapter's children can outlive it).
- [ ] 5.3 Live checks: Go (all), js + python (claude ACP+one-shot, opencode ACP); java/csharp/elixir/clojure/rust hermetic only.
