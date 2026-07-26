## 1. Contract first

- [x] 1.1 `SPEC.md §7D`: add `hooks` and `onMetric` to the agent-runtime options table, and the
  same two to the agent-definition table, with the replace-never-merge precedence rule
- [x] 1.2 `SPEC.md §7D`: state that the runtime forwards both verbatim and that neither is a
  route to alter `systemPrompt` / `waitFor` / the HTTP seam / `store`
- [x] 1.3 `SPEC.md §7F`: replace the dangling "canonical use of the §8 `beforeLLM` hook" claim
  with a pointer to how a compactor is attached to an agent run (§7D `hooks`)
- [x] 1.4 `SPEC.md §8`: note the second entry point — hook and metric semantics are identical
  whether the client is constructed directly or by the §7D runtime

## 2. Shared fixture

- [x] 2.1 Added `examples/agent-hooks/fixture.json` — six scenarios (H1-H6) + the four
  invariants, in the repo's shared-fixture form; every port's test file cites it
- [x] 2.2 Add a runtime-wide-vs-def-override case to the fixture (runtime hook set, agent A
  overrides, agent B inherits) so precedence is pinned cross-language
- [x] 2.3 Record run B's expected trace/transcript from current `main` **before** any port
  changes, so the byte-identical claim is checked against pre-change output — use the spike's
  method (`git worktree add <tmp> main`, run the same golden capture in both trees, diff)
- [x] 2.4 Add a fixture case for the §10 interaction: a turn that compacts and then suspends is
  rewound to its full pre-turn transcript (spike finding S3 — currently accidental, unspecified)

## 3. golang/

- [x] 3.1 Add `Hooks *tn.Hooks` and `OnMetric func(tn.MetricEvent)` to `agents.Options`
  (`golang/agents/runtime.go:256`) and to `agents.Def`
- [x] 3.2 Resolve def-over-runtime and forward both into the `tn.ClientOptions` at
  `golang/agents/runtime.go:1100`
- [x] 3.3 Tests: hook runs inside a turn; metric events fire; def overrides runtime; unset is
  byte-identical against the §2.3 recording; compaction-then-pending rewind (§2.4)
  — all six already written and passing on `spike/agent-runtime-hooks`
  (`golang/agents/spike_hooks_test.go`); promote them rather than rewriting
- [x] 3.4 `go build ./... && go vet ./... && go test -race ./...`

## 4. js/

- [x] 4.1 Add `hooks?` and `onMetric?` to `RuntimeOptions` (`js/src/agents/runtime.ts:254`) and
  to `AgentDef`
- [x] 4.2 Resolve and forward both into `createClient` at `js/src/agents/runtime.ts:741`
- [x] 4.3 Tests in `js/test/` covering the four §3.3 cases
- [x] 4.4 `npm test`

## 5. python/

- [x] 5.1 Add `hooks` and `on_metric` to the runtime options and to `AgentDef`
  (`python/src/toolnexus/agents/runtime.py`)
- [x] 5.2 Resolve and forward both into `create_client` at `python/src/toolnexus/agents/runtime.py:756`
- [x] 5.3 Tests in `python/tests/` covering the four §3.3 cases
- [x] 5.4 `python -m pytest -q`

## 6. csharp/

- [x] 6.1 Add `Hooks` and `OnMetric` to `RuntimeOptions` (`csharp/src/Toolnexus/Agents/AgentTypes.cs:101`)
  and to `AgentDef`
- [x] 6.2 **Copy both fields in `RuntimeOptions.CloneWithRegistry`** — a missed field there is a
  silent per-subtree drop (design D5)
- [x] 6.3 Resolve and forward both into `LlmClient.Create` at `csharp/src/Toolnexus/Agents/AgentRuntime.cs:492`
- [x] 6.4 Tests covering the four §3.3 cases **plus** a clone-preserves-both test
- [x] 6.5 `dotnet build && dotnet test`

## 7. java/

- [x] 7.1 Add `hooks` / `onMetric` fields and fluent setters to
  `java/src/main/java/io/github/muthuishere/toolnexus/agents/RuntimeOptions.java` and to `AgentDef`
- [x] 7.2 Resolve and forward both into the `LlmClient.Options` built at
  `java/src/main/java/io/github/muthuishere/toolnexus/agents/AgentRuntime.java:534`
- [x] 7.3 Tests in `java/src/test/.../agents/` covering the four §3.3 cases
- [x] 7.4 `./gradlew build --no-daemon`

## 8. elixir/

- [x] 8.1 Accept `:hooks` and `:on_metric` in the runtime's keyword options
  (`elixir/lib/toolnexus/agents/runtime.ex`) and in an agent def
- [x] 8.2 Resolve def-over-runtime and forward both into `Client.create` at
  `elixir/lib/toolnexus/agents/handle.ex:143`, leaving the injected shared `MetricsRegistry`
  behavior untouched (design D4)
- [x] 8.3 Tests in `elixir/test/agents/` covering the four §3.3 cases plus registry-then-sink
  ordering — five already written and passing on the spike branch, promote them
- [x] 8.4 Arity trap (spike finding S4) DECIDED: raise loudly. `client.ex` now raises
  `ArgumentError` on a `before_llm` that is a function of any arity but 1, instead of
  silently falling through to the no-op branch. Pinned by a test
- [x] 8.5 Watch the `:registry` collision (spike finding S5): the agent runtime's `:registry` is
  the AGENT registry, the client's `:registry` is the MetricsRegistry
- [x] 8.6 `mix test && mix coveralls` (gate ≥ 95%)

## 9. Docs and close-out

- [x] 9.1 Update the site's subagents / persona-agents pages to show attaching a compactor to an
  agent run, with six-language synced tabs
- [x] 9.2 Update `docs/adr/0008-...md`: mark Accepted, correct the five-ports claim to six, note
  the D1 scope adjustment (per-def override) versus the ADR's runtime-only proposal, and record
  that the seam was spiked in golang + elixir before acceptance
- [x] 9.3 Re-run all six suites and confirm the shared fixture's run B matches the §2.3 recording
  in every port
- [ ] 9.4 Open the PR with the change folder + code in one diff; archive only after merge

## 10. Found during implementation

- [x] 10.1 `java` and `csharp` rebuild defs/options field-by-field in **two** places each
  (`withBudget` + `copyWithRegistry`; `CloneWith` + `CloneWithRegistry`). Both are on the
  spawn / Level-1 path, so a missed field is a silent per-spawn drop. Both carried; pinned by
  a clone test in csharp. Verified NOT a risk in the other four: `golang` copies `Def` by
  value, `js` spreads, `python` uses `dataclasses.replace`, `elixir` uses `Map.put`
- [ ] 10.2 Follow-up (not this change): `golang`'s `Runtime` has no public conversation-store
  accessor, unlike `java`'s `conversationStore()` and `js`'s `store`. Tests must inject their
  own store to observe transcripts. Parity gap in the §7D surface, unrelated to this seam
