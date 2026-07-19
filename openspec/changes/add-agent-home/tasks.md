# Tasks — add-agent-home

Reference: branch `spike-agent-home` (`js/spike/home.ts` + `home-demo.ts`, 15 checks).
Built entirely on the shipped §7D runtime — no runtime changes. Rewrite to shipped quality
per port; reconcile the `add-subagents` fromDir/heartbeat drift.

## 0. Contract + fixtures (before any port)

- [x] 0.1 SPEC.md §7E (agent home): bootstrap order + 2 MB cap + `## <file>` injection;
      `memory` builtin (add/replace/remove, self/user, frozen-snapshot, loud miss, opt-out);
      heartbeat (post-tick→wake, HEARTBEAT_OK silent, injectable clock, coalesce). Match §7A–D
      house style.
- [x] 0.2 SPEC.md §4A note: the file-backed `memory` tool is opt-in, not a default builtin.
- [x] 0.3 Shared fixture `examples/persona-agent/`: a real bootstrap dir (SOUL.md + USER.md +
      HEARTBEAT.md + MEMORY.md), scripted mock-LLM turns (soul-echo, memory write, next-session
      recall, heartbeat due/not-due), and expected behavior — the §0 conformance artifact.

## 1. js (reference)

- [x] 1.1 `js/src/agents/home.ts`: `fromDir`, `memoryTool`, `startAgent`, `HEARTBEAT_OK`,
      `BOOTSTRAP_ORDER`, `composeSoul`; export under the `agents` namespace.
- [x] 1.2 Port the 15 spike checks (H1–H7) into `js/test/` against `examples/persona-agent/`;
      `npm test` green.

## 2. python  (reconcile: agent_from_dir already shipped in surface.py — align to §7E)

- [x] 2.1 `toolnexus/agents/home.py` (or fold into surface.py): fromDir/memory/heartbeat per §7E.
- [x] 2.2 15 checks as pytest against the shared fixture; full suite green.

## 3. golang

- [x] 3.1 `golang/agents/home.go`: FromDir/MemoryTool/StartAgent per §7E.
- [x] 3.2 15 checks as go tests (-race) against the fixture; full suite green.

## 4. java  (reconcile: agentFromDir already in Agents.java — align to §7E)

- [x] 4.1 `agents/Home.java` (or fold into Agents): fromDir/memory/heartbeat per §7E.
- [x] 4.2 15 checks as JUnit against the fixture; full suite green.

## 5. csharp

- [x] 5.1 `Toolnexus/Agents/Home.cs`: FromDir/MemoryTool/StartAgent per §7E.
- [x] 5.2 15 checks as xUnit against the fixture; full suite green.

## 6. elixir

- [ ] 6.1 `toolnexus/agents/home.ex`: from_dir/memory_tool/start_agent per §7E (heartbeat via
      the runtime's injectable clock; :timer only in the host recipe).
- [ ] 6.2 15 checks as ExUnit against the fixture; full suite green; coverage ≥ 95%.

## 7. Real deliverables (owner steer: production, not college projects)

- [ ] 7.1 Docs page `site/.../persona-agents.mdx`: fromDir + memory + heartbeat, 6-language
      tabs from the shipped code; a **"when to use which surface"** table (`agent()` for a
      one-shot worker · `fromDir` for a filesystem persona · raw verbs for a custom host loop).
- [ ] 7.2 Recipes (docs, zero new API): **dream/consolidation** (a scheduled agent whose
      HEARTBEAT.md folds notes into MEMORY.md) and **channel-driven assistant** (host inbound
      → `post`/`wake`). Each with a runnable snippet.
- [ ] 7.3 Runnable `examples/persona-agent/` wired to a real example entrypoint per port
      (mirrors the existing per-port `examples/` runnables); README mention.
- [ ] 7.4 Cross-port conformance: identical behavior against the shared fixture; note allowed
      divergences (filesystem paths only).
- [ ] 7.5 CHANGELOG: agent-home (fromDir + memory builtin + heartbeat), additive.
- [ ] 7.6 Remove `js/spike/` once js src + tests are green (branch history preserves it).
