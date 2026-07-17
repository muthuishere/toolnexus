# Agent-fundamentals audit — toolnexus vs the field (2026-07-17)

Seven-agent research pass: **opencode, codex, vscode-copilot-chat, enterprisewebagent**
(coding archetype), **openclaw, hermes-agent** (persona/automation archetype), plus a
**toolnexus self-audit** (main, JS reference port). Companion doc:
`coding-agent-research-2026-07-17.md` (tool-level detail from the first four).

**Goal:** toolnexus should be the fundamentals layer for BOTH archetypes — anyone building
a coding agent OR an openclaw-style persona agent should start from toolnexus.

---

## The audit matrix

| Fundamental | toolnexus today (shipped) | Field standard | Verdict |
|---|---|---|---|
| **Tool substrate** | ✅ Complete: MCP/skills/native/HTTP/A2A unified, adapters, allowlists (§0–§7) | — | **Strength. Done.** |
| **Builtins** | ✅ All 10 exist (§4A): bash/read/write/edit/grep/glob/webfetch/question/apply_patch/todowrite | opencode limits (read 2000L/50KB, glob/grep 100-cap, bash 2min), edit 9-strategy fuzzy cascade, universal truncation w/ spillover, doom-loop guard | **Exists — needs HARDENING, not creation** |
| **Loop** | ✅ maxTurns 10, hooks×4, streaming, retries/onError, §10 suspension (open kinds: authorization/approval/input/question), conversation memory by id | + `incomplete` status, continue-nudge at limit (copilot limit×1.5), doom-loop breaker | Strong; small §8 additions |
| **Soul / identity** | ❌ `systemPrompt` string only; skillsPrompt appended; NO instructions-file loading | openclaw: ordered bootstrap files (AGENTS/SOUL/TOOLS/IDENTITY/USER/HEARTBEAT/MEMORY.md), 2MB/file cap, budget+truncation notice, injected as `##` sections. hermes: 3-tier prompt (stable/context/volatile) built ONCE per session for cache stability; SOUL.md slot; guidance blocks injected only when the matching tool is loaded | **GAP — the persona blocker #3** |
| **Memory: session** | ✅ ConversationStore (get/save by id, in-memory default, pluggable) | — | Done |
| **Memory: long-term** | ❌ Nothing: no memory tool, no persistent impl, no file convention | hermes: MEMORY.md+USER.md bounded curated files, one `memory` tool (add/replace/remove), **frozen-snapshot injection** (session-start only, cache-safe). openclaw: MEMORY.md + daily `memory/YYYY-MM-DD.md` + memory_search/get tools | **GAP — persona blocker #2** |
| **Memory: consolidation / learning** | ❌ Nothing | openclaw: dreaming (light→REM→deep, only deep writes MEMORY.md). hermes: post-turn background review fork (warm cache/cheap model digest replay), nudges every 10 iters, idle curator promoting experience→skills | GAP — but a **recipe** over subagents+scheduler, not new API |
| **Compaction / context lifecycle** | ❌ Nothing — raw append until overflow | copilot: 3-tier ladder, BudgetExceeded typed error, 0.9×(window−toolTokens). codex: mid-turn auto-compact, cache-prefix-aware scope. openclaw: tool-pair-safe split + pre-compact "flush to memory" nudge + prune tuned to cache TTL | **GAP — coding blocker #1, persona killer** ("dies within days") |
| **Scheduler / heartbeat** | ❌ Nothing; library cannot wake itself | openclaw: 30-min heartbeat running the NORMAL loop w/ synthetic prompt, merges cron+exec events, HEARTBEAT_OK silent-ack sentinel; agent-callable `cron` tool. hermes: gateway tick()/60s, file-lock single-runner, NL cron jobs, results delivered to any platform | **GAP — persona blocker #1** |
| **Events / onEvent** | ⚠️ serve() = A2A JSON-RPC + MCP gateway only; onTask/onCall callbacks | openclaw gateway: envelope→binding→per-(channel,peer) session→turn→reply. hermes gateway: 8 platforms, idle-only completion-queue injection | GAP — but channels stay OUT of lib (messenger is its own product); lib needs the **trigger/queue seam** only |
| **Subagents / delegation** | ❌ Nothing shipped (A2A outbound only; `add-agent-pipeline` design branch + ADR-0005 on hold) | Universal convergent design: child run of same loop, fresh transcript, final-text-only return. hermes: DELEGATE_BLOCKED_TOOLS frozenset (no recursion/memory-write/clarify for children), concurrency cap, background→completion-queue. codex v2: spawn/send_message/followup_task/wait/close handles. opencode: deny task-to-subagents by default | **GAP — coding blocker #2** |
| **Toolset scoping / permissions** | ⚠️ Static allowlists at construction (per-server tools, skillsFilter, builtins map, disableTools); beforeTool hook as seam | opencode: merged (permission,pattern)→allow\|ask\|deny ruleset. hermes: named composable toolsets + check_fn availability gating. ewa: ToolFilter chain (deny→mode→permission) | Partial — registry-level filter/ruleset would unify agent scoping + plan mode + approvals |

## Cache-stability doctrine (cross-cutting, from hermes + codex + openclaw)

The persona agents are engineered around prompt-cache economics as an *invariant*:
prompt built once per session; frozen memory snapshots (mid-session writes hit disk, not
prompt); deterministic tool call ids; idle-only event injection (never splice into a live
turn); compaction scope `body_after_prefix`. Any fundamentals design toolnexus ships MUST
preserve this: **soul/memory injection happens at session start; events/results enter as
fresh turns at idle; nothing mutates past context.** This becomes a SPEC §-level rule.

## The two archetype examples (acceptance criteria for "done")

- **`examples/coding-agent/`** — AGENTS.md + hardened builtins + task subagent (explore
  role) + compaction: a working mini-Claude-Code in each of the 6 languages.
- **`examples/persona-agent/`** — SOUL.md + USER.md + MEMORY.md + memory tool + heartbeat
  (HEARTBEAT_OK sentinel) + a dream/consolidation recipe wired to a trigger: a working
  mini-openclaw core (channels supplied by the host, e.g. messenger).

When both examples run byte-identically across ports, toolnexus is "agent-ready".

## Revised change stack

1. **`harden-builtins`** (small) — bring the 10 shipped builtins to field contracts:
   read limits/offset-footer/near-miss suggestions, edit fuzzy cascade + ambiguity error,
   bash tail-ring truncation, universal output truncation (2000L/50KB + spillover),
   doom-loop guard in the loop, `incomplete` RunStatus. Optional: websearch builtin.
2. **`add-subagents`** — reconcile `add-agent-pipeline` + ADR-0005 (one change):
   `task` builtin + agent registry (`subagents` key + markdown defs riding SKILL.md
   parser) + isolation + limits; Tier-2 agent handles (spawn/send/followup/wait/close,
   codex-v2) + hermes' child-blocklist rule (children never get task/memory-write) +
   completion-queue for background children (results enter as fresh idle turns).
3. **`add-agent-home`** — the workspace bootstrap contract: ordered persona files
   (AGENTS.md/SOUL.md/IDENTITY.md/USER.md/TOOLS.md/HEARTBEAT.md/MEMORY.md), per-file cap +
   prompt budget + truncation notices, 3-tier cache-stable prompt composition
   (stable/context/volatile); `memory` builtin (add/replace/remove over MEMORY.md/USER.md,
   frozen-snapshot injection) + optional daily-notes + memory_search; **trigger primitive**:
   `on("timer", every, prompt)` + `on("event", name)` + agent-callable `cron` tool +
   HEARTBEAT_OK sentinel. Channels/gateway explicitly out of scope (host wires messenger).
4. **`add-compaction`** — context lifecycle: typed budget-exceeded event, summarization
   pass (tool-pair-safe split), pre-compact memory-flush nudge, cache-prefix-aware scope,
   token-visible truncation. Needed by BOTH archetypes; separate because it touches the
   loop's core invariants and needs careful §0 parity treatment.

Recipes (docs/examples, zero new API): dream mode (trigger + consolidation agent),
agent teams/orchestrator (handles + parallel task), plan mode (toolset filter), learning
loop (post-run review fork), model failover (existing RequestParams/onError seams).

## The runtime substrate v2 — "actor model, amended for turn economics"

Owner direction: OTP actor model is the right fundamental. v1 was accreted Q-by-Q;
this v2 is the huddle-redone unified spec (3 adversarial iterations, 2026-07-17).
Spike 1 builds this.

### Core model

- **`Run`** — one client-loop invocation (§8). The only execution unit; no second engine.
- **`Handle`** — a live agent: `{id, def, state, inbox, budget, children}`. The inbox is
  **agent state, not runtime mailbox** — persistable, checkpointable, observable (the
  reason five non-BEAM ports can implement it, and BEAM can checkpoint it).
- **Two delivery rails** (v1's "nothing enters a live turn" overclaimed):
  - **Solicited** — a tool call's result returns into the live turn (that is what a
    tool call is; sync `task` rides this).
  - **Unsolicited** — everything else (posts, timer ticks, background results, inbound
    events) lands in the inbox and enters as a **fresh turn at idle**, coalesced:
    one wake drains ALL pending items into one context block (timer ticks dedupe to 1).

### State machine + verbs (SPEC pins transitions ONLY — scheduling is unobservable)

```
states  idle → running → (idle | suspended | closed);  suspended → running ONLY via Answer
spawn(def, budget?)        → new handle (child of caller), returned to spawner alone
post(h, msg)               inbox += msg; no transition; after close ⇒ isError("closed")
wake(h, msg?)              idle→running; turn input = drain(inbox); no-op if running
wait(h, timeoutMs?)        block until result | isError | timeout (child keeps running
                           on timeout unless the waiter then interrupts)
interrupt(h)               abort current Run via §8 signal → IDLE (inbox intact,
                           checkpoint at last completed turn). Stops the turn, NOT the
                           agent — v1 conflated this with kill; codex semantics adopted.
close(h, {force?})         graceful: stop accepting → close children leaf-up → running
                           turn finishes bounded by shutdownMs (then escalate to
                           interrupt) → onClose → final checkpoint → closed.
                           Stop-all = close(root); process exit implies it.
                           Close ≠ loss: checkpoint persists; a successor can spawn
                           FROM it (codex resume_agent); discard is separate+explicit.
```
Ordering: per-sender FIFO; drain = arrival order; concurrent suspensions
first-in-tool-call-order (§10, shipped).

### Two surfaces (unasked in v1; the model does NOT get the host API)

- **Host API**: all six verbs + `list()`/`inspect(h)` (read-only views over the handle
  table + metrics — not verbs, no transitions).
- **Model surface**: v1 ships **`task` only** (spawn→wake→wait→close fused, isolated,
  final-text return). Team tools (`spawn_agent`/`send`/`wait`/`close` as model tools,
  codex-v2 style) are an **opt-in toolset per AgentDef** — never default (opencode's
  deny-by-default rule).

### Six laws (deduped from v1's amendments; each exists because a turn is
slow/costly/stochastic where an actor receive is cheap/deterministic)

1. **Turn boundary is the only scheduling point** — batch-per-turn drain; enforcement
   checks (budget) run before turns and before spawns, nowhere else.
2. **Unsolicited input never enters a live turn** (the corrected idle-drain law).
3. **Supervise results, not exits** — failures cross handle boundaries as uniform
   isError results, never exceptions; "restart" = resume-from-checkpoint (§10), never
   re-init (a half-done LLM run is spent money).
4. **`suspended` ≠ `idle`** — only the Answer wakes it; other posts buffer behind it;
   doom-loop/poison items park as §10 approval Requests (suspension = dead-letter queue).
5. **Budgets are hierarchical** — `Budget{maxTurns, maxTokens, maxToolCalls, maxWallMs,
   maxChildren, maxConcurrent, maxDepth}`; child effective = min(own, parent remaining);
   G3 usage roll-up is the accounting, so no child outspends an ancestor by construction.
   Money excluded (price tables are vendor data — host converts in the hook). Exhaustion
   = `status:"incomplete"` + limit named — never a crash; optional
   `onBudget(info)→"stop"|"extend"|"suspend"` (twin of resilience onError; suspend =
   §10 topup→resume). Suspended agents spend nothing.
6. **Handles are capabilities** — post/wake what you HOLD, wait what you SPAWNED, spawn
   returns to spawner alone. Children hold no parent handle (hermes DELEGATE_BLOCKED
   validated; also useless — parent is parked in wait). Teams = coordinator explicitly
   grants sibling handles. Cycles legal only on the non-blocking half ⇒ no deadlock.

### One escalation ladder, two payloads (pending + error — v1 wrote this twice)

Anything a level can't handle surfaces ONE hop up the spawn tree, strictly (no
level-skipping — every intermediate waitFor/model keeps its authority):

- **Pending**: child's waitFor answers inline, else the parent's task tool call returns
  `metadata.pending` carrying the child's Request + handle path (a suspending agent IS a
  suspending tool — §10 unchanged, no new pending type); parent's waitFor answers
  (Answer routes down the path to the leaf cursor) or parent suspends too → root →
  durable. Resume is a cascade of normal child-result deliveries. Parked levels burn
  zero tokens; durable driver lets every process exit.
- **Error**: mechanical retry AT the failing level first (shipped §8 retries/onError +
  resilience-policy suspend tier); exhausted ⇒ isError one hop up, where the parent's
  MODEL decides (reprompt/respawn/reroute/abandon) — an intelligent supervisor at every
  level, which is why law 3 beats OTP restart. Root: throws / `incomplete`.

Down the tree, implicitly (Go-context style): cancellation signal (shipped), budget
carve, config, identity info in the task-source block, ancestry chain. Up: result,
pending, isError — nothing else (telemetry is out-of-band via hooks).

### Runtime obligations (v1 left these unowned)

- **Handle table** — the runtime checkpoints the tree itself (ids, parent links,
  states), or restart cannot rebuild what per-handle checkpoints describe.
- **Deterministic ids** — parent-scoped paths (`root/coord.1/explore.2`), never UUIDs:
  cache-stable (hermes deterministic call-ids), resume-addressable, readable in traces.
- **Injected clock** — all timers (heartbeat, timeouts, backoff) go through a clock
  seam; conformance fixtures run on a virtual clock or they can't be deterministic.
- **Inbox provenance** — every item carries `{from: handle-path|"external", channel}`;
  drain renders non-ancestor/external items inside a marked untrusted block
  (prompt-injection surface — the one real security hole the huddle found).
- **Streaming**: child stream events are NOT relayed by default; opt-in filtered relay
  (copilot's filtered-stream precedent) using the existing §8 event types.

### Backpressure: three admission gates, one rule — loud, never silent

The **sync rail needs none**: a parent parked in `wait` can't emit more work — the spawn
tree is pull-based, backpressure propagates up for free. Machinery exists only for the
unsolicited rail + the provider:

1. **Inbox gate** (delivery): bounded per handle; `post` returns `ok|full`; `full`
   surfaces to the SENDER as isError("inbox full") — an agent sender reads it and adapts
   (cognitive backpressure, the two-brain pattern again); an external sender (serve/
   channels) is rejected so the HOST queues upstream (messenger's job, not the library's).
2. **Concurrency gate** (spawn/wake): `spawn` always succeeds (a handle is a struct);
   the WAKE queues — runtime admits ≤ maxConcurrent running children per parent, queued
   wakes fire FIFO as slots free (codex csv=16 / hermes max_concurrent_children behavior).
3. **Turn gate** (provider, the one that costs money): every Run acquires a slot from a
   global `runtime.maxConcurrentTurns` before each LLM call — the leaky bucket at the
   single point all pressure converges; without it, per-level mechanical retry AMPLIFIES
   a 429 storm (every level backing off into the same limit).

Rule: pressure is always visible — full inboxes signal senders, queued wakes show in
inspect(), turn-gate waits emit a metric. Nothing drops silently (this also settles D3
fundamentally: drop-oldest would HIDE pressure from the control loop meant to react).

### Lifecycle: two callbacks, not three (v1's `onTurn` cut — beforeLLM + the pinned
default drain format cover it; resurrect only if a demo demands it)

- `onSpawn(ctx)` — once, pre-first-turn. THE cache-doctrine injection point: soul files
  + frozen memory snapshot (agent-home bootstrap lands here).
- `onClose(ctx, reason: closed|interrupted|budget|error)` — pre-final-checkpoint. The
  memory flush (openclaw pre-compact flush / hermes sync_all).
- Symmetry: onSpawn reads memory in, onClose writes memory out — persona memory
  lifecycle = two hooks around the existing store, not a memory system.

### Compilation + proof port

```
task        = spawn→wake→wait→close (solicited rail)   map  = spawn×N→wake×N→waitAll→close
orchestrate = coordinator's Run emits task calls (parallel tool calls = free fan-out §8)
team        = long-lived handles + granted sibling capabilities + post/wake
heartbeat   = clock posts tick to own inbox (unsolicited rail; ticks coalesce)
on(event)/serve = inbound → post → wake                dream = timer wake of scoped agent
```

**Elixir is the proof port** — GenServer holding the inbox in state is this, natively;
the other five implement the state machine as queue + enum. Conformance = identical
transition traces against shared `examples/` fixtures on the virtual clock (§0 method).

### Open decisions (owner)

- **D1** Model-visible team tools: confirm opt-in-per-AgentDef default-off (reco: yes).
- **D2** `wait` timeout default: none (block forever) vs a default cap (reco: none at
  substrate; `task` sugar sets one from the parent's remaining maxWallMs).
- **D3** Inbox cap + overflow policy default: reject-with-isError vs drop-oldest
  (reco: reject — loud beats lossy).
- **D4** `interrupt` on a `suspended` agent: cancel the pending Request → idle, or
  no-op? (reco: cancel→idle; it's the operator's escape hatch from a stuck approval.)

## The UX (DHH-final, 2026-07-18) — existing API untouched, one new noun

- **Level 0**: today's createToolkit/createClient/ask — byte-identical, forever.
- **Level 1**: `agent(name, {does, uses, soul?, team?, budget?})` then `agent.run(client,
  prompt)`. `team: [explore]` alone wires the task tool (descriptions from `does`, the
  skillsPrompt pattern). ~12 lines = a coding agent. Defaults carry isolation/roll-up/
  escalation (spike-proven).
- **Level 2**: `agent.fromDir("~/mia")` — the directory IS the agent (SOUL.md, MEMORY.md,
  HEARTBEAT.md, mcp.json, skills/, agents/*.md discovered); `runtime({client})` +
  `rt.start(mia, {every:"30m"})` + `rt.on("message", …)` (channels stay host-side).
- **Level 3**: the six verbs on handles — visible only when reached for.
- **Bridge**: `explore.asTool()` drops an agent into the old API's extraTools; both
  directions of the Agent-is-a-Tool axiom. `serve(agent)` = §7B unchanged.
- Structural anti-LangChain: no builders, no YAML pipelines, no graph DSL — everything
  compiles to the six verbs.

## Parked distinctive ideas (own changes later, if ever)

- Virtual-tool grouping 64/96/128 (copilot) or BM25 `tool_search` (codex) — for huge MCP piles.
- Unified exec sessions (`session_id` + `write_stdin`, codex).
- `execute_code` RPC pattern (hermes) — model writes a script calling tools; N round-trips → 1 turn.
- Permission ruleset engine (opencode's (permission,pattern)→allow|ask|deny as a first-class layer).
- Edit healing telemetry / edit-survival tracking (copilot).
- Model failover chains + auth-profile rotation (openclaw) — partially covered by injected transport.
