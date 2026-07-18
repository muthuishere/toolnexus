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

- [ ] 1.1 elixir: first-class transport seam on the client (§8 Gap 2 equivalent)
- [x] 1.2 python: cooperative cancel seam (between-attempts minimum; transport abort
      where urllib/httpx allows) + documented contract
- [x] 1.3 java: cancel token on ask/run (interruptible virtual-thread contract) +
      documented contract (`LlmClient.CancelToken` + `CancelledException`; classified
      by token state, never retried, bypasses onError)
- [x] 1.4 csharp: classify external cancellation distinctly from timeout on the
      interrupt path (token state, not exception type)
- [ ] 1.5 all ports: `"incomplete"` RunStatus value (loud limit stops) — python ✅
      (remaining: js, golang, java, csharp, elixir)
      (csharp ✅ — all four loop paths return `"incomplete"` at MaxTurns)
      — java DONE (maxTurns-with-tool-calls ⇒ `status:"incomplete"` on all four
      run/stream paths); js/python/golang/csharp/elixir pending

## 2. js (reference port)

- [ ] 2.1 Runtime substrate in `js/src/agents/` (state machine, six verbs, rails,
      gates, budgets, lifecycle, runtime-wide store, injectable clock, deterministic
      ids, provenance, transactional drain)
- [ ] 2.2 task builtin + team scoping + reattachment; agent()/asTool() surface
- [ ] 2.3 Port the 46 spike checks into `js/test/` as real tests (virtual clock);
      run against shared fixtures; full suite green

## 3. python

- [x] 3.1 Runtime substrate (asyncio; synchronous wake admission per spec)
- [x] 3.2 task + team + reattachment; agent()/as_tool()
- [x] 3.3 46 checks as pytest against shared fixtures; full suite green
      (`tests/test_agents_runtime.py` + `tests/test_agents_surface.py` +
      `tests/test_client_cancel_incomplete.py`; note: the durable-resume fixture's
      `phase2_expect.transitions` for `asker.1` omits the terminal `→closed` the
      cascade's task performs — asserted as a prefix; fixture should gain it)

## 4. golang

- [ ] 4.1 Runtime substrate (mutex-atomic admission, slot-transfer dequeue,
      RoundTripper turn gate w/ death release)
- [ ] 4.2 task + team + reattachment; Agent()/AsTool()
- [ ] 4.3 46 checks as go tests (-race) against shared fixtures; full suite green

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

- [ ] 7.1 Runtime substrate as OTP (Handle GenServer w/ inbox-as-state,
      DynamicSupervisor, monitor-released turn gate, rootward-call discipline,
      runtime-owned client lifecycle — no leaked store/metrics Agents)
- [ ] 7.2 task + team + reattachment; agent/2, as_tool/2
- [ ] 7.3 46 checks as ExUnit against shared fixtures; coverage gate ≥95% held

## 8. Parity + docs

- [ ] 8.1 Cross-port conformance: identical transition traces for all shared fixtures
      on the virtual clock (§0 method); document any allowed divergence (abort
      latency only)
- [ ] 8.2 Docs site: subagents page (Level-1 UX + task + teams recipes: orchestrate/
      map as userland patterns); README example
- [ ] 8.3 Release notes: `"incomplete"` status call-out (QG5)
- [ ] 8.4 Remove `<port>/spike/` directories once each port's real implementation +
      tests are green (the branch history preserves them)
