# Tasks — add-cli-agent-runtime

Go-only (the CLI ships only in `golang/cmd/toolnexus`). No six-port parity: the CLI wires
already-shipped library behavior. Reconciled 2026-07-21 (see design.md). **Close the 5 OPEN
FORKS in design.md before starting §1.** Land in order; each group independently testable.

## 0. Contract

- [ ] 0.1 Rewrite `SPEC.md §9`: config schema; verbs (`create`/`up`/`send`/`ps`/`stop`/`run`/
      `tools`/`install`/`uninstall`); listener = A2A + ask-back; global skills home + fan-out;
      `--json`; npm + binary distribution; secrets invariant. Match §7 style.
- [ ] 0.2 Runnable fixture `examples/cli-agent/agent.json` (root persona + 2-member team incl. a
      scoped verifier + a `serve` block) reusing the shared `examples/` mcp.

## 1. Config loader + flag overlay
- [ ] 1.1 `config.go`: typed schema structs; JSON load; flag overlay (flag wins); `extends`
      merge (base → overlay). Unit tests for override precedence + no-config back-compat.
- [ ] 1.2 Build helpers: config → `Toolkit`; config → root `*Agent` (FromDir vs inline New);
      config → `team []*Agent` (recursive, tool-scoped); config → `Budget`; `serve` →
      `ServeOptions`. `maxWallMs` → `time.Duration`.

## 2. Global skills home + install/uninstall (fan-out)
- [ ] 2.1 `${TOOLNEXUS_SKILLS_DIR:-~/.toolnexus/skills}` resolution; `run`/`up`/`tools` always load it.
- [ ] 2.2 `install --skills <src>`: canonical placement (copy or `--link` per FORK 1); fan-out
      symlinks to `~/.claude/skills` + `~/.agents/skills` (codex rule per FORK 2); collision-safe
      (own-link-only replace; warn+skip foreign); prints each action; Windows copy/junction fallback.
- [ ] 2.3 `uninstall --skills <name>`: remove owned mirror links then canonical.
- [ ] 2.4 Tests (env-redirected roots, hermetic): fan-out links created; foreign dir survives;
      uninstall symmetric; install runs no code.

## 3. run (agent runtime + human loop + json)
- [ ] 3.1 `run` builds+runs the root agent via `agents.New(...).Run(Options{LLM,...}, prompt)`
      when config/agent/team present; else the bare client loop.
- [ ] 3.2 `--json`: `{text,status,usage}` to stdout, logs to stderr. `--once` + REPL.
- [ ] 3.3 Human loop: REPL prompts on stdin; `--once`/`--json` returns `status:"pending"` + request.
- [ ] 3.4 Wire `agent.compactAt` (compactor on beforeLLM) + `agent.heartbeatMs`.
- [ ] 3.5 Tests, scripted `http.RoundTripper` (mock LLM, zero network): team delegation via
      `task`, budget stop → `incomplete`, `--json` shape, pending surfaced, no-team ⇒ no `task`,
      secret never printed.

## 4. up / send / ps / stop (the listener core)
- [ ] 4.1 `up`: build agent, `Toolkit.Serve` (A2A, loopback default), write registry
      `$TOOLNEXUS_HOME/agents/<name>.json` (0600), block on SIGINT/SIGTERM, `ServeHandle.Stop()`.
- [ ] 4.2 `send <name> "msg"`: registry lookup → A2A `message/send` (per FORK 4: library
      `A2ASend` vs CLI JSON-RPC); print reply; on `input-required` prompt + resume by contextId.
- [ ] 4.3 `ps` / `stop`.
- [ ] 4.4 Tests: two agents on `127.0.0.1:0`, A sends B, B suspends (`input-required`), A answers,
      B resumes — scripted mock LLM, zero network. Clean shutdown.

## 5. create (authoring + modified agent)
- [ ] 5.1 `create <name>`: scaffold `SOUL.md` + `agent.json`. `--dir`.
- [ ] 5.2 `--from <base>` (`extends`) + `--add-mcp n=cmd` compose a modified agent (per FORK 3).
- [ ] 5.3 Tests: created agent runs; `extends` merge resolves.

## 6. Binary distribution (release.yml)
- [ ] 6.1 `main.version` (default `"dev"`), `toolnexus --version`/`version`.
- [ ] 6.2 `cli-binaries` job (release event only, gated `ENABLE_GO`): matrix build, name
      `toolnexus_<v>_<os>_<arch>[.exe]`, `SHA256SUMS`, `gh release upload vX.Y.Z …`.
- [ ] 6.3 Do NOT touch the five-manifest preflight (CLI has no manifest; keys off the release tag).

## 7. npm CLI wrapper
- [ ] 7.1 `@muthuishere/toolnexus-cli` (separate from the `toolnexus` JS library):
      `bin:{toolnexus}`; postinstall downloads + checksum-verifies the matching release binary;
      loud failure with the releases URL when no prebuilt binary exists.
- [ ] 7.2 npm publish job in `release.yml` (gated `ENABLE_GO`, OIDC), version tracks the release.

## 8. Docs
- [ ] 8.1 New site page `cli.mdx`: install (npm + binary + `go install`); the `agent.json` schema
      (annotated); all verbs; the listener/resident model + `send`; `install --skills` fan-out;
      `--json` for programmatic use; the polyglot-bridge framing. Real examples + when-to-use.
- [ ] 8.2 `golang/README.md` + `golang/GUIDE.md`: CLI section refreshed. Root `README.md` one-liner.
- [ ] 8.3 `CHANGELOG.md` Unreleased entry.

## 9. Verify
- [ ] 9.1 `cd golang && go build ./... && go vet ./... && go test -race ./...` green.
- [ ] 9.2 Build the binary; run `examples/cli-agent/agent.json` end to end vs OpenRouter — root
      delegates to the team, `--json` parses, `up`+`send` round-trip on loopback.
- [ ] 9.3 `openspec validate add-cli-agent-runtime --strict`.
- [ ] 9.4 Site build clean; `openspec/` diff + code in one PR.
