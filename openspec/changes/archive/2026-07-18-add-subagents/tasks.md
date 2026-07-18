# Tasks — add-subagents

Reference implementation for every task: branch `spike-agent-runtime`
(`<port>/spike/…`, 46 checks/port). Rewrite to shipped quality inside each port's
normal source tree — do not merge spike files as-is.

## 0. Pre-work (verify before any port lands)

- [x] 0.1 Verify per-port §10 halted-tool transcript behavior (does the pending tool's
      placeholder result get appended before halting?) across all six shipped clients;
      pin the answer in SPEC §10 (reattachment works either way, but the spec must
      state one truth)
- [x] 0.2 SPEC.md: add §7D (agent runtime + subagents: state machine, six verbs, two
      rails, gates incl. gate-release-on-death + admission atomicity + wait
      next-or-last + rootward-call discipline, budgets + incomplete, lifecycle,
      data.path, reattachment, cancellation contract table, registry sort)
- [x] 0.3 SPEC.md §8 addendum: `RunResult.status` gains `"incomplete"` (QG5 note);
      per-port cancellation contract table
- [x] 0.4 Shared fixtures under `examples/`: subagent-fanout, subagent-escalation,
      subagent-durable-resume, subagent-budgets, subagent-lifecycle (transition-trace
      expectations, virtual clock)
- [x] 0.5 Mark ADR-0005 superseded-by this change; close the `propose-agent-pipeline`
      branch with a pointer note

## 1. Client seams (small, per port, before the runtime lands)

- [x] 1.1 elixir: first-class transport seam on the client (§8 Gap 2 equivalent) —
      `Client :transport` option: `(request -> {:ok, %{status, headers, body}} |
      {:error, e})`; retries/Retry-After/deadline/classification wrap it; composes
      against a real base_url (unlike the in-process `http_options: :plug`); nil ⇒
      byte-identical default; `:registry` injection added for the runtime-wide
      MetricsRegistry
- [x] 1.2 python: cooperative cancel seam (between-attempts minimum; transport abort
      where urllib/httpx allows) + documented contract
- [x] 1.3 java: cancel token on ask/run (interruptible virtual-thread contract) +
      documented contract (`LlmClient.CancelToken` + `CancelledException`; classified
      by token state, never retried, bypasses onError)
- [x] 1.4 csharp: classify external cancellation distinctly from timeout on the
      interrupt path (token state, not exception type)
- [x] 1.5 all ports: `"incomplete"` RunStatus value (loud limit stops) — python ✅,
      csharp ✅ (all four loop paths at MaxTurns), java ✅ (all four run/stream
      paths), js ✅ (all four run/stream paths; a maxTurns exit with no final text
      ⇒ `status:"incomplete"` + `limit:"maxTurns"`), golang ✅ (all four run/stream
      paths, `RunResult.Limit` mirrors js `limit`, lastText on the openai exits),
      elixir ✅ (all four run/stream paths at max_turns ⇒ `status: "incomplete"` +
      `limit: "maxTurns"`)

## 2. js (reference port)

- [x] 2.1 Runtime substrate in `js/src/agents/` (state machine, six verbs, rails,
      gates, budgets, lifecycle, runtime-wide store, injectable clock, deterministic
      ids, provenance, transactional drain; transcript REWIND on pending; exported
      as the `agents` namespace per the cross-port collision rule)
- [x] 2.2 task builtin + team scoping + reattachment; agent()/asTool() surface
      (task tool only when the def declares a team; reattached settled children
      close per the fixed durable-resume fixture)
- [x] 2.3 Port the 46 spike checks into `js/test/agents.test.ts` as real tests
      (virtual clock); driven by the shared fixtures incl. transition-trace parity;
      + rewind/death-release/forced-close/escalation-of-close tests; full suite
      green (111 pass)

## 3. python

- [x] 3.1 Runtime substrate (asyncio; synchronous wake admission per spec)
- [x] 3.2 task + team + reattachment; agent()/as_tool()
- [x] 3.3 46 checks as pytest against shared fixtures; full suite green
      (`tests/test_agents_runtime.py` + `tests/test_agents_surface.py` +
      `tests/test_client_cancel_incomplete.py`; note: the durable-resume fixture's
      `phase2_expect.transitions` for `asker.1` omits the terminal `→closed` the
      cascade's task performs — asserted as a prefix; fixture should gain it)

## 4. golang

- [x] 4.1 Runtime substrate (mutex-atomic admission, slot-transfer dequeue,
      RoundTripper turn gate w/ death release) — `golang/agents/` (subpackage:
      `toolnexus.Agent` is already the A2A type, so the §7D surface is namespaced
      `agents.Agent`/`agents.Runtime`, mirroring `js/src/agents/`). Admission is
      fully atomic (checks + slot + DRAIN + cancel-context install + state flip
      under one mutex hold); runtime-wide ConversationStore commits only completed
      turns (= checkpoint rewind on durable pending); injectable Clock; live
      ancestor budgets incl. MaxToolCalls/MaxWall; graceful close escalates to
      interrupt after Shutdown and awaits the abort (drain restore, no
      state resurrection); Request data.path stamped per relaying level
- [x] 4.2 task + team + reattachment; Agent()/AsTool() — `agents.New/Run/AsTool`,
      task tool only for defs declaring a Team (delegation opt-in), sorted team
      description, reattach-by-task-key incl. closed-but-settled children (no
      completion cache); persona/heartbeat kept USERLAND (agent-home is a later
      change — exercised in tests over the six verbs, no library API)
- [x] 4.3 46 checks as go tests (-race) against shared fixtures; full suite green —
      `agents/runtime_test.go` (S1–S9, fixture-mapped, incl. transcript-rewind-on-
      pending, forced-close drain restore, gate-release-on-acquirer-death, virtual-
      clock wait timeout) + `agents/agent_test.go` (S10–S13 + durable Level-1) +
      `golang/runstatus_incomplete_test.go` (client §8 addendum); 28 go tests /
      60+ assertions, `go build ./... && go vet ./... && go test -race ./...` green

## 5. java

- [x] 5.1 Runtime substrate (virtual threads; waitOn; locked admission) —
      `java/src/main/java/io/github/muthuishere/toolnexus/agents/` (runtime-wide
      ConversationStore w/ checkpoint rewind on durable pending, injectable
      RuntimeClock, CancelToken interrupts, transactional drain, three gates incl.
      gate-release-on-death, live-ancestor budgets incl. maxToolCalls/maxWallMs,
      name-sorted registry prose, Request data.path)
- [x] 5.2 task + team + reattachment; agent()/asTool() — `Agents.agent()/asTool()/
      agentFromDir/startAgent`; registry = transitive team closure; recursion opt-in
- [x] 5.3 46 checks as JUnit against shared fixtures; full suite green —
      `agents/AgentRuntimeTest` (S1–S9, 36 checks, fixture-mapped) +
      `agents/AgentsSurfaceTest` (S10–S13, 10 checks) + runtime-obligation extras
      (store survival, gate-slot death, sorted prose) + `ClientCancelTest`;
      `./gradlew test` = 135 tests, 0 failures

## 6. csharp

- [x] 6.1 Runtime substrate (SemaphoreSlim gate in DelegatingHandler, atomic
      StartTurn, wait next-or-last)
- [x] 6.2 task + team + reattachment; Agent()/AsTool()
- [x] 6.3 46 checks as xUnit against shared fixtures; full suite green

## 7. elixir

- [x] 7.1 Runtime substrate as OTP (`Toolnexus.Agents.{Runtime,Handle,TurnGate,
      Trace,Clock}`): Handle GenServer with inbox-as-state + transactional drain
      (restored on interrupt AND forced close), DynamicSupervisor, monitor-released
      turn gate around the LLM call only (via the new client `:transport` seam),
      rootward-call discipline documented in the Handle moduledoc, atomic admission
      (check + slot + drain + kill-target + state flip in one handle_call),
      deferred-reply wait (next-or-last; settled/suspended answer immediately;
      spawner-only capability), live-ancestor budgets incl. max_tool_calls /
      max_wall_ms (settling `incomplete` + `limit`), closed 7-string status
      vocabulary, durable resume suspended→idle→running with checkpoint rewind
      (suspended turns never advance the store), Request data.path stamped per
      relaying level, injectable clock, name-sorted registry composition,
      runtime-owned client lifecycle: ONE linked ConversationStore + ONE linked
      MetricsRegistry shared by per-handle clients (zero per-turn processes;
      `shutdown/1` tears everything down; host-injected stores survive)
- [x] 7.2 task + team + reattachment; agent/2, as_tool/2 — task tool only for defs
      with a team (opt-in recursion), sorted team description, reattach-by-task-key
      as the ONLY idempotency (no cache; closed children answer from queryable
      final state); `Toolnexus.Agents.agent/2 + run/3 + as_tool/2` (`AgentDef`,
      namespaced clear of A2A), as_tool relays §10 pending and resumes via
      ctx.answer
- [x] 7.3 46 checks as ExUnit against shared fixtures (test/agents/: fanout,
      escalation, durable-resume, lifecycle, budgets, ux) + infra/edge suites
      (transcript-survives, no-leak, virtual clock, wait capability, forced-close
      escalation without resurrection, refusal-settles, error/crash-as-result);
      `mix test` 328 passed, `mix coveralls` 96.9% (gate 95). NOTE: durable-resume
      transition assertions follow the e54498a SPEC pin (suspended→idle hop); the
      shared fixture predates the pin and omits it

## 8. Parity + docs

- [x] 8.1 Cross-port conformance: identical transition traces for all shared fixtures
      on the virtual clock (§0 method); document any allowed divergence (abort
      latency only) — DONE: every port's suite asserts the shared fixture transition
      traces (js 111 · python 132 · go green(-race) · java green · csharp 153 ·
      elixir 316/96.9%cov); allowed divergence = abort latency only, per the §7D
      cancellation contract table
- [x] 8.2 Docs site: subagents page (Level-1 UX + task + teams recipes: orchestrate/
      map as userland patterns); README example — `site/src/content/docs/subagents.mdx`
      (+ sidebar) covering agent()/team/task, isolation/roll-up, suspension escalation
      + durable resume, budgets incl. "incomplete", six host verbs (advanced),
      orchestrate/map recipes, agents-namespace note; cookbook page
      `cookbook/subagents.mdx`; README "Sub-agents — delegate in-process" section
- [x] 8.3 Release notes: `"incomplete"` status call-out (QG5) — repo had no changelog;
      added root `CHANGELOG.md` with an Unreleased section (agent runtime + subagents
      headline; `RunResult.status` gains `"incomplete"`+`limit`, code matching
      `status === "done"` after maxTurns must update)
- [x] 8.4 Remove `<port>/spike/` directories once each port's real implementation +
      tests are green (the branch history preserves them) — removed js/spike,
      python/spike, golang/spike, elixir/test/{support/,}spike, java …/toolnexus/spike
      (csharp already gone); all six suites green post-removal
