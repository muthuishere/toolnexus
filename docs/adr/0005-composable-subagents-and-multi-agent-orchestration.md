# ADR 0005 — Composable sub-agents: split → delegate → aggregate, as a default tool

- **Status:** **Superseded by `openspec/changes/add-subagents` (2026-07-18)** — the
  six-port spike on branch `spike-agent-runtime` (276/276 checks) resolved this ADR's
  G1–G4 and QG1–QG5 into SPEC §7D; see
  `docs/references/agent-fundamentals-audit-2026-07-17.md`. Previously: On hold —
  pending owner discussion (2026-07-14). A composition plan already
  exists: the **`add-agent-pipeline`** OpenSpec change (branch `propose-agent-pipeline`,
  design-only, commit ed445bf) defines the layer this ADR would sit on — `Agent` as a
  first-class `Tool` with its own scoped toolkit, a local `agent(name, {does, uses})` factory,
  and an `orchestrate` combinator for dynamic split→delegate→reduce with context isolation and
  §10 resumability. This ADR's G1 (`task` builtin) and G2 (registry) largely re-express that
  surface as builtins; G3 (context isolation) is a pipeline property there already. Before any
  promotion, reconcile the two — this ADR builds **on top of** the pipeline layer, not beside
  it. G4's `"incomplete"` RunStatus is the one piece independently useful regardless of the
  outcome. Originally: a direction proposal to accept or reject, not a defect record.
- **Date:** 2026-07-14
- **Driver:** the "next level" of toolnexus — a composable toolkit where an agent can **split a
  goal into tasks, delegate each to a scoped sub-agent, and aggregate the results**, with the
  sub-agent's tool-output noise kept *out* of the parent's context. Modeled on VS Code Copilot's
  in-process sub-agent path (`RunSubagentTool`, `src/vs/workbench/contrib/chat/common/tools/builtinTools/runSubagentTool.ts:68-589`;
  driven from `ToolCallingLoop.run` with `subAgentInvocationId` / `parentRequestId` trace context,
  `extensions/copilot/src/extension/intents/node/toolCallingLoop.ts:936-1123`). The owner has said:
  Copilot-style is the target.
- **Honesty note:** this is **reference-architecture-verified and design-forward**, not
  consumer-verified. Every toolnexus fact below is line-cited; the proposal is new capability, so
  there is no existing workaround to point at. The whole point of writing it as a spec is that
  toolnexus can adopt, trim, or reject each piece.
- **Design principle (per this repo's rules): reuse, don't rebuild.** toolnexus already ships the
  three ingredients a sub-agent needs — this ADR mostly *wires* them, it doesn't invent new
  subsystems:
  1. **A composable, filterable toolkit** — `tools()` aggregates MCP → skill → builtin →
     extraTools with a per-source allowlist already (`SPEC.md §4`, §4A). A sub-agent is just a
     *scoped view* of that toolkit.
  2. **A run loop with real parallel tool calls** — the loop executes every tool call in a turn
     concurrently via `Promise.all` (`js/src/client.ts:549`, `SPEC.md §8`). Fan-out is therefore
     **free**: if the model emits N `task` calls in one turn, they already run in parallel.
  3. **Agent delegation** — outbound A2A already calls a *remote* agent and exposes its skills as
     tools (`SPEC.md §7A`, source `"a2a"`). This ADR adds the **in-process, same-toolkit** sibling.

## Context — what exists vs what's missing

Today the only way one toolnexus agent invokes another is **A2A over HTTP** (`§7A` outbound,
`§7B` inbound `serve`). That is the right tool for *cross-process / cross-team* delegation, but it
is heavy for the common case: "spin up a scoped helper in this same process, hand it a subtask,
get one answer back." There is:

- no **default tool** a model can call to spawn a nested run (the loop stops on no-tool-calls at
  `client.ts:547`; `maxTurns` default 10 at `:530`);
- no **agent registry** — a named role = (system prompt + tool subset + model) that a delegate
  can target (the toolkit is assembled once, flat, per `Client`);
- no **context isolation** — every tool result is pushed straight into the parent transcript
  (`client.ts:567`), so a research subtask that reads 40 files would dump all 40 into the parent's
  window. Copilot's whole reason for `RunSubagentTool` is that the sub-run's transcript stays in
  the sub-run; only its *final message* returns.

The ten built-ins (`§4A`) already include `todowrite` (a decomposition/planning surface) and
`question` (human-in-loop) — so the **planning** half of "split" exists. The **execution** half —
delegating a split-out task to an isolated worker — is the gap this ADR fills.

Priority order: **G1 the `task` delegate tool → G2 the agent registry → G3 context-isolated
aggregation → G4 continuation/limits.** G1+G2 are the feature; G3 is what makes it *worth it*;
G4 is safety.

---

## Gap G1 — A default `task` (delegate) built-in tool (priority 1)

### Motivation
A model can plan with `todowrite` but cannot *execute* a plan item in isolation — it has to do
every subtask inline, polluting its own context and serializing work it could fan out. Copilot's
`RunSubagentTool` is a first-class builtin (`Id = 'runSubagent'`) that the model calls to spawn a
nested agent. toolnexus should ship the same as an **eleventh built-in** (source `"builtin"`,
opt-in-able through the existing `builtins.tools` map, `§4A`).

### Proposed API (additive) — new builtin `task`

```
| name | inputSchema (required unless ?) | behavior |
| task | agent:string, prompt:string, context?:string, model?:string |
    Spawn a NESTED run: build a scoped Toolkit + Client for the named agent (G2), run its own
    loop to completion, and return ONLY its final text as this tool's `output`. The sub-run's
    transcript never enters the parent transcript (G3). `agent` must name a registered agent
    (else isError, listing available names — mirrors the `skill` tool's not-found path, §3).
    `context` is optional extra grounding prepended to `prompt`. `model` overrides the agent's
    default model. Errors/timeouts return isError:true (uniform ToolResult, §1) — a failed
    sub-agent never throws across the boundary. metadata = {agent, turns, toolCalls, totalTokens}.
```

Because the loop already runs tool calls concurrently (`§8`), a parent that emits several `task`
calls in one turn fans them out in parallel with **zero extra machinery** — that is the whole
"split and execute" story, for free.

### Acceptance tests
- A parent prompt that needs two independent subtasks emits two `task` calls in one turn; both
  sub-runs execute concurrently; parent transcript gains exactly two `tool` messages (the two
  final answers), not the sub-runs' intermediate steps.
- `task` with an unknown `agent` returns `isError:true` and lists registered agents.
- A sub-agent that itself calls `task` is allowed to depth N (G4 cap); a cycle is bounded, not
  infinite.
- `builtins.tools = { task: false }` removes it; default is on (per `§4A` toggle semantics).

### Cross-language parity
Moves `SPEC.md §4A` (new row) and adds a sub-section for the nesting contract; byte-identical
input schema across all five ports. Shared fixture `examples/subagent-fanout/`.

---

## Gap G2 — An agent registry: name → (prompt + tool scope + model) (priority 2)

### Motivation
`task` needs targets. A "sub-agent" in Copilot/Claude-Code terms is a **named role**: a system
prompt, a *subset* of the toolkit, and optionally a model. toolnexus already has every filter
needed — per-server MCP `tools`, the skills `filter`, the `builtins.tools` map — so an agent
definition is just those filters plus a prompt, resolved into a scoped `Toolkit` at spawn time.

### Proposed API (additive)

```ts
interface AgentDef {
  name: string
  description: string          // shown to the parent model in the `task` tool description catalog
  systemPrompt: string
  model?: string               // default: inherit parent Client model
  // Tool scope — reuse the EXACT allowlist semantics already specced (§2/§3/§4A):
  tools?: {
    mcp?: Record<string, boolean>       // per the §2 server/tool filter
    skills?: Record<string, boolean>    // per the §3 skills filter
    builtins?: Record<string, boolean>  // per the §4A builtins map
  }
  maxTurns?: number            // default: parent maxTurns
}

// Client/Toolkit options addition
interface ClientOptions {
  agents?: AgentDef[]          // registry the `task` tool resolves against
}
```

Config parity: agents may also be declared in the parsed config object under the **already-reserved
`agents` top-level key** (`SPEC.md §2` reserves `agents` for §7A outbound today — this ADR's
consumer-question QG2 asks whether to reuse or namespace it, e.g. `subagents`). Descriptions from
the registry compose the `task` tool's description so the parent model knows what it can delegate
to — the same way `skillsPrompt()` advertises skills (`§3`).

### Acceptance tests
- An agent with `tools.builtins = { bash:false }` cannot run `bash` in its sub-run even though the
  parent can (scoped toolkit proven by attempting the call).
- Omitting `model` inherits the parent model; setting it overrides for that sub-run only.
- Two agents with the same `name` → load-time error (first-wins + warn, mirroring the §3 duplicate
  rule).

### Cross-language parity
Moves `SPEC.md §4`/new §7D "sub-agents". Reuses the three existing filter contracts verbatim — no
new filter semantics. Shared fixture `examples/agent-registry/`.

---

## Gap G3 — Context-isolated aggregation (the reason this is worth doing) (priority 3)

### Motivation
Without isolation, delegation is pointless — you'd just inline the work. The value is that a
sub-agent burns *its own* context window on its 40 file reads and returns a 200-token summary. In
Copilot the sub-run is a separate `ToolCallingLoop`; only its result crosses back. toolnexus must
guarantee the same: the nested `Client.ask/run` gets a **fresh transcript** (its own
`ConversationStore` scope, not the parent's, cf. `client.ts` history handling), and the `task`
tool returns only `RunResult.text`. The parent's `RunResult.usage` should **aggregate** child
token usage (so cost is still visible) even though child *messages* are not merged.

### Proposed contract
- Sub-run transcript is isolated (new store scope per `task` invocation).
- `task` output = child `RunResult.text` only; child `toolCalls`/`messages` are reachable via
  `metadata` / a debug hook, never auto-merged into the parent transcript.
- Parent `RunResult.usage` += child usage (roll-up); `toolCallCount` gains a `subAgentToolCalls`
  companion so the split is legible (mirrors Copilot keeping subagent metrics separate to avoid
  cardinality blur, `toolCallingLoop.ts` telemetry).

### Acceptance tests
- Parent transcript length is independent of how many tools the sub-agent used.
- `parent.usage.totalTokens` includes the sub-agent's tokens; parent `messages` do not include the
  sub-agent's tool messages.

### Cross-language parity
Moves `SPEC.md §8` (usage roll-up rule) + §7D (isolation rule). Fixture `examples/subagent-isolation/`.

---

## Gap G4 — Depth/turn/tool limits and a continuation policy (priority 4, safety)

### Motivation
Nesting + fan-out needs guardrails or a bad plan spawns unbounded work. The flat loop today just
stops at `maxTurns` (`client.ts:530`) and returns `lastText` **silently** — no signal the goal was
truncated. Copilot layers a goal classifier (`shouldAutopilotContinue`
`toolCallingLoop.ts:411-475`, `advancedAutopilotContinue :485-526`,
`_runAutopilotGoalClassifier :538-598`) to decide *done vs keep-going vs impossible*. toolnexus
needn't ship a classifier, but it must bound recursion and make truncation **explicit**.

### Proposed API (additive)
```ts
interface ClientOptions {
  maxSubAgentDepth?: number     // default 3; task calls beyond depth return isError:true
  maxSubAgents?: number         // default 16; total nested runs per top-level ask()
  shouldContinue?: (r: RunResult) => boolean | Promise<boolean>  // optional continuation hook
}
// RunResult status gains a value so a maxTurns/limit stop is no longer silent:
type RunStatus = "done" | "pending" | "incomplete"   // "incomplete" = hit a limit, not a natural stop
```

`shouldContinue` (when provided) is consulted at the natural stop point (`client.ts:547`) — return
`true` to inject a "continue" nudge and loop again, up to `maxTurns`. Default absent ⇒ today's
behavior exactly. Hitting any limit sets `status:"incomplete"` instead of silently returning.

### Acceptance tests
- A recursive `task` chain stops at `maxSubAgentDepth` with a clear isError, not a stack blow-up.
- A run that exhausts `maxTurns` returns `status:"incomplete"` (was silently `"done"`).
- No options set ⇒ byte-identical to today (`"done"` on natural stop, limit still caps but now
  labeled).

### Cross-language parity
Moves `SPEC.md §8`: the new `RunStatus` value and the two limits. `shouldContinue` is a per-port
hook idiom. Fixture `examples/subagent-limits/`.

---

## Consumer questions (toolnexus decides — this ADR is use-or-reject)

- **QG1 (scope):** ship G1+G2+G3 as one feature, or land G1 (delegate to an *inline* default agent,
  no registry) first and add G2 registry later? The former is the real feature; the latter is a
  faster proof.
- **QG2 (config key):** reuse the reserved `agents` top-level key for sub-agents, or namespace as
  `subagents` to keep it distinct from §7A outbound A2A `agents`? These are different concepts
  (local role vs remote endpoint) sharing a word — naming matters.
- **QG3 (local vs A2A):** should an in-process `task` and a remote A2A call present as the **same**
  tool to the model (one `delegate` surface, transport chosen by whether the target is a local
  `AgentDef` or a remote card), or stay two tools (`task` local, §7A `a2a` remote)? Unifying is
  more elegant and more Copilot-like; splitting is simpler to reason about.
- **QG4 (classifier):** is a pluggable `shouldContinue` hook enough, or does toolnexus want a
  built-in goal classifier like Copilot's autopilot? Recommend hook-only — a classifier is a model
  call toolnexus shouldn't make on the consumer's behalf by default.
- **QG5 (reject candidates):** G4's `"incomplete"` status is technically a **behavior change** for
  anyone matching on `status === "done"` after a `maxTurns` stop. Acceptable minor-version change,
  or must limit-stops keep reporting `"done"`? This is the one piece with a back-compat cost.
