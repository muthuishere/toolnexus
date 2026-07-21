# Tasks — add-cli-agent-runtime

Go-only (the CLI ships only in `golang/cmd/toolnexus`). No six-port parity: the CLI wires
already-shipped library behavior. Land in the order below; each group is independently testable.

## 0. Contract

- [ ] 0.1 Rewrite `SPEC.md §9`: config schema, commands (`run`/`serve`/`tools`/`install`),
      `--json`, team-declaration format, global skills home + resolution order, npm + binary
      distribution, secrets invariant. Match the §7 style.
- [ ] 0.2 Add a runnable fixture `examples/cli-agent/agent.json` (root persona + a 2-member
      team incl. a scoped verifier + a `serve` block) reusing the shared `examples/` mcp/skills.

## 1. Config loader + flag overlay

- [ ] 1.1 `config.go` in `cmd/toolnexus`: typed structs for the schema; JSON load; flag-overlay
      (explicit flag wins over file field). Unit tests for override precedence + no-config
      back-compat.
- [ ] 1.2 Build helpers: config → `Toolkit` (mcp/skills/builtins), config → root `*Agent`
      (FromDir vs inline New), config → `team []*Agent` (recursive, tool-scoped), config →
      `Budget`, `serve` → `ServeOptions`. Map `maxWallMs` → `time.Duration`.

## 2. run (agent runtime + human loop + json)

- [ ] 2.1 `run` builds and runs the root agent via `agents.New(...).Run(Options{LLM,...}, prompt)`
      when a config/agent/team is present; falls back to the bare client loop otherwise.
- [ ] 2.2 `--json`: emit `{text,status,usage}` to stdout, logs to stderr. `--once` and REPL.
- [ ] 2.3 Human loop: a CLI `WaitFor` — interactive prompts on stdin; `--once`/`--json` returns
      `status:"pending"` + request without blocking.
- [ ] 2.4 Wire `agent.compactAt` (compactor on beforeLLM) and `agent.heartbeatMs` (heartbeat).
- [ ] 2.5 Tests with a scripted `http.RoundTripper` (mock LLM, zero network): team delegation
      via `task`, budget stop → `incomplete`, `--json` shape, pending surfaced, no-team ⇒ no
      `task`, secret never printed.

## 3. serve

- [ ] 3.1 `serve` command: build toolkit(+client), `Toolkit.Serve(addr, ServeOptions{A2A,MCP})`,
      print bound address, block on SIGINT/SIGTERM, `handle.Stop()`. `--a2a`/`--mcp` override.
- [ ] 3.2 Test: serve on `127.0.0.1:0`, hit the A2A agent card / a tool, then stop.

## 4. Global skills + install

- [ ] 4.1 `$TOOLNEXUS_HOME` (default `~/.toolnexus`) resolution; `skills/` under it.
- [ ] 4.2 `install --skills <src>`: copy `**/SKILL.md` trees into the global home, idempotent.
- [ ] 4.3 Global-first skill load in `run`/`serve`/`tools`; local shadows global; `--no-global-skills`.
- [ ] 4.4 Tests: install → tools sees it; local shadows global; opt-out; install runs no code.

## 5. Binary distribution (release.yml)

- [ ] 5.1 Add `main.version` (default `"dev"`), `toolnexus --version`/`version`.
- [ ] 5.2 `cli-binaries` job in `release.yml` (release event only, gated `ENABLE_GO`):
      matrix build `CGO_ENABLED=0 -trimpath -ldflags "-s -w -X main.version=<v>"`, name
      `toolnexus_<v>_<os>_<arch>[.exe]`, `SHA256SUMS`, `gh release upload vX.Y.Z …`.
- [ ] 5.3 Do NOT bump the five-manifest preflight (CLI has no manifest of its own); the job keys
      off the release tag version.

## 6. npm CLI wrapper

- [ ] 6.1 `cli-npm/` (or `golang/npm/`) package `toolnexus-cli`: `bin: {toolnexus}`, postinstall
      downloads + checksum-verifies the matching release binary; loud failure with the releases
      URL when no prebuilt binary exists for the platform.
- [ ] 6.2 `npm` publish job for the wrapper in `release.yml` (gated `ENABLE_GO`, OIDC), version
      tracks the release. Keep it separate from the `toolnexus` library npm job.

## 7. Docs

- [ ] 7.1 New site page `cli.mdx` (Tool sources or a dedicated "CLI" section): install (npm +
      binary + `go install`), the `agent.json` schema with a full annotated example, all four
      commands, team declaration, `install --skills`, `--json` for programmatic use, `serve`,
      the polyglot-bridge framing ("call it from Python/shell/Node"). Real examples + when-to-use.
- [ ] 7.2 `golang/README.md` + `golang/GUIDE.md`: CLI section refreshed to the new surface.
- [ ] 7.3 Root `README.md`: add the CLI/npm one-liner. `CHANGELOG.md` Unreleased entry.

## 8. Verify

- [ ] 8.1 `cd golang && go build ./... && go vet ./... && go test -race ./...` green.
- [ ] 8.2 Build the binary; run `examples/cli-agent/agent.json` end to end against a real
      endpoint (OpenRouter) — root delegates to the team, `--json` parses, `serve` binds.
- [ ] 8.3 `openspec validate add-cli-agent-runtime --strict`.
- [ ] 8.4 Site build clean; `openspec/` diff + code in one PR.
