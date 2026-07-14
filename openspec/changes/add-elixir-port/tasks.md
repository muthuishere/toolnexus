# Tasks: add-elixir-port

Porting order follows design.md (JS is the reference; conformance tests land per stage).

## 1. Project scaffold
- [ ] 1.1 `elixir/` mix project (`:toolnexus`, Elixir ≥1.16/OTP 26), deps: yaml_elixir, req,
      jason, plug, bandit, excoveralls (test); `coveralls.json` minimum_coverage 95
- [ ] 1.2 Core structs: `Tool`, `ToolResult`, `Context`, `Request`, `Answer` (§1, §10 keys pinned)

## 2. Skill source (§3)
- [ ] 2.1 Discovery: glob `**/SKILL.md`, YAML frontmatter (name required), first-wins dedupe,
      symlink-cycle guard
- [ ] 2.2 `skill` tool: byte-exact output template, sibling sampling (cap 10), `skillsPrompt()`
- [ ] 2.3 S1–S5: skills-as-data/provider, allowlist, `list/1` inventory with typed skips,
      logical base, sample cap option
- [ ] 2.4 Conformance test vs `examples/skills/hello-world` golden bytes

## 3. Adapters + native + http
- [ ] 3.1 `to_openai/1`, `to_anthropic/1`, `to_gemini/1` (§0.7 shapes)
- [ ] 3.2 `define_tool/1` (§6): string⇒output, raise⇒isError
- [ ] 3.3 http tools (§7): `{ph}` substitution, `${ENV}` headers (never logged), non-2xx⇒isError

## 4. MCP source — in-house client (§2, D2)
- [ ] 4.1 `Mcp.Protocol`: JSON-RPC 2.0 codec; initialize/initialized, paginated tools/list,
      tools/call, ping, elicitation/create (inbound)
- [ ] 4.2 `Mcp.Transport.Stdio`: Erlang Port, newline-delimited JSON, env per config, child
      lifetime = connection process (ADR-0007 regression test)
- [ ] 4.3 `Mcp.Transport.StreamableHttp`: req POST + incremental SSE parser, session header,
      legacy SSE fallback
- [ ] 4.4 `Mcp.Connection` GenServer + DynamicSupervisor; per-phase timeouts (30s default),
      caller-deadline cancellation (gap 3), per-server isolation
- [ ] 4.5 Config parsing (`mcpServers|servers|mcp`), sanitize naming, per-server `tools`
      allowlist (gap 7), `list_tools/2` inventory (gap 6)
- [ ] 4.6 Elicitation bridge → §10: form→input AND url→authorization (both modes)
- [ ] 4.7 Hermetic stub servers in `test/support/` (stdio script + Bandit HTTP stub)

## 5. Toolkit (§4) + built-ins (§4A)
- [ ] 5.1 Aggregator: mcp+skills+builtins+agents+extra, first-wins dedupe, `close/1`
- [ ] 5.2 All ten builtins (bash, read, write, edit, grep, glob, webfetch, question,
      apply_patch, todowrite) with §4A schemas; global + per-tool toggles
- [ ] 5.3 DisableTools/DisableSkills equivalents (ADR-0001 shipped surface)

## 6. Client loop (§8) + suspension (§10)
- [ ] 6.1 run/ask (openai + anthropic styles), parallel ordered tool calls, maxTurns 10
- [ ] 6.2 Hooks, retries, conversation store + accessor (gap 4), streaming events
- [ ] 6.3 ADR-0001 shaping: request_params merge, body_transform, injectable HTTP, omit empty
      tools (gap 5)
- [ ] 6.4 §10: pending detection, waitFor re-execute-once, no-waitFor⇒status pending; G2/G3
      hardening semantics (pending≠tool-error, first-in-order)
- [ ] 6.5 `metrics/1` Prometheus text byte-exact
- [ ] 6.6 Mock-LLM loop tests (httptest-style Bandit stub)

## 7. Inbound serve (§7A/§7B/§7C)
- [ ] 7.1 A2A outbound: agent card fetch, SendMessage→poll GetTask subset
- [ ] 7.2 A2A inbound serve: Plug router, agent card, JSON-RPC, input-required on suspension
- [ ] 7.3 MCP inbound serve: streamable-HTTP profile over the same toolkit

## 8. Repo wiring
- [ ] 8.1 CI job (erlef/setup-beam, mix test + coverage gate ≥95%, hermetic)
- [ ] 8.2 release.yml: hex publish job gated on ENABLE_ELIXIR + HEX_API_KEY (use-only);
      preflight checks mix.exs version
- [ ] 8.3 `elixir/README.md`; SPEC.md five→six wording; CLAUDE.md layout row; root README pitch
- [ ] 8.4 Full-suite run + cross-port conformance spot-check (skill bytes, naming, adapters)

## Parity checklist (this change IS the parity)
- [ ] elixir — full §0
- [x] js / python / golang / java / csharp — unchanged (reference only)
