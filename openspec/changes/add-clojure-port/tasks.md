# Tasks

Per-language parity is not applicable: this change adds a port and alters no
contract, so the other six ports are untouched by design.

## 1. Foundation
- [x] 1.1 `clojure/deps.edn` + `clojure/build.cljgo`, both resolving koine from Clojars
- [x] 1.2 `toolnexus.tool` — Tool / ToolResult / Context, `sanitize`, the toolkit
- [x] 1.3 `toolnexus.frontmatter` — the strict subset parser, throwing outside it

## 2. Tool sources
- [x] 2.1 `toolnexus.mcp` — transport as data, stdio + streamable-HTTP, §2 lifecycle, failure isolation
- [x] 2.2 `toolnexus.skill` — discovery, byte-exact `skill` output, `skillsPrompt`
- [x] 2.3 `toolnexus.native` + `toolnexus.http` — §0.8 / §0.9
- [x] 2.4 `toolnexus.builtin` — the §4A toolset and the §0.11 toggles

## 3. Consumption
- [x] 3.1 `toolnexus.adapter` — OpenAI / Anthropic / Gemini
- [x] 3.2 `toolnexus.client` — the loop, parallel calls, §10 suspension
- [x] 3.3 `toolnexus.a2a` — §7A outbound
- [x] 3.4 `toolnexus.serve` — §7B + §7C inbound
- [x] 3.5 `toolnexus.core` — the public API

## 4. Verification
- [x] 4.1 Dual-host test suite behind the count gate
- [x] 4.2 Conformance run against the shared `examples/` on all three modes, byte-diffed
      — the suite runs against `../examples/` via `TN_EXAMPLES` and asserts the
      byte counts (hello-world = 995 B); `cljgo-gate.sh` diffs the two cljgo
      legs' verdicts against each other.
- [x] 4.3 `clojure/README.md`
- [x] 4.4 `cljgo-gate.sh` — offered to cljgo as a downstream CI gate; proven to
      fail on zero collected tests AND on an aot/interp divergence
- [x] 4.5 `consumer-exit-check.sh` — the property the in-process suite cannot
      observe; proven to fail on the pre-fix tree (61s vs 2s)
- [x] 4.6 `conformance/check_options_parity.py` taught to tokenize kebab-case —
      it previously reported all 23 options missing for this port, of which only
      20 were real

## 5. Parity debt — NOT done, tracked so it cannot drift silently

This port is deliberately NOT yet wired into `conformance/check_options_parity.py`:
it would fail, and a gate you exempt yourself from is worse than one you fail
honestly. Measured gap, after the tokenizer fix:

### 5.1 Client options
- [ ] `hooks`
- [ ] `retries` + `retry-base-ms`
- [ ] `timeout-ms`
- [ ] `store` (conversation memory)
- [ ] `on-metric` (observability)
- [ ] `request-params` + `body-transform`
- [ ] `http-client` (injected client — proxy/credentials)
- [ ] `on-error` (the `add-resilience-policy` change)

### 5.2 Toolkit options
- [ ] `skill-provider`, `skills-filter`, `skill-sample-limit` (the v0.8.0 skill
      source extensions)
- [ ] `agents`
- [ ] `wait-for` on the toolkit (present on the client only)
- [ ] `disable-tools` / `disable-skills`
- [ ] alias `:mcp` -> `mcpConfig`, `:skills` -> `skillsDir`, `:tools` ->
      `extraTools` in the manifest — these capabilities EXIST, only the names
      differ, so they want aliasing rather than implementing

### 5.3 Other absences
- [ ] MCP elicitation bridge (§2) and per-server tool allowlists
- [ ] `ListMcpTools` inventory, context-aware load
- [ ] real SSE streaming (the loop buffers; it emits no text deltas on purpose)
- [ ] durable resume
- [ ] wire this port into `check_options_parity.py` once 5.1/5.2 close
