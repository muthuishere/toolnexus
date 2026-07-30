# Changelog

All six ports (js / python / golang / java / csharp / elixir) are versioned and released
together; entries here apply to every port unless a port is named. Releases are cut as
GitHub Releases `vX.Y.Z` via `release.yml` (see `PUBLISHING.md`).

## Unreleased

## 0.12.0 — 2026-07-30

Adds **single-turn translation** (`SPEC.md` §11, ADR-0011) — the inbound half of the format
adapters. Additive: nothing existing changes, and no port behaves differently unless you call
the new entry point.

`SPEC.md §0` item 7 pinned the adapters as *schema only*: `toOpenAI`/`toAnthropic`/`toGemini`
translate tool declarations **outbound**, and nothing read a provider's tool calls back
**inbound**. Every user of those public functions hit the same wall — they could tell a
provider about their tools but not receive the calls it made. So the library served one
posture well ("the library executes tools in a loop") and the majority posture — *"I want
provider-portable tool calling, but **I** execute the tools"*, the premise of the entire
OpenAI function-calling protocol — not at all.

**New: `translate`** (idiomatic naming per port). Exactly **one** provider call, returned in
OpenAI shape. No agent loop, no tool execution, no conversation state — every call is
self-contained, so it can be run statelessly and scaled horizontally.

- **Request** takes the OpenAI `messages`, `tools` and `tool_choice` **verbatim**, so a caller
  never builds provider-native payloads. It also accepts an ordinary **toolkit** — MCP tools,
  skills, native functions, A2A agents, builtins — which is **declared and never executed**.
  The two tool sources compose.
- **Inbound translation preserves tool structure** that a text flattening destroys: an
  assistant turn's `tool_calls` become native tool-use blocks with `arguments` re-parsed from
  its JSON string into an object; a `tool`-role result becomes a tool-result block keyed by
  `tool_call_id`, **merged into one user turn** when consecutive; `system`/`developer` messages
  are hoisted into the provider's separate field; content-parts arrays are flattened. Both
  `arguments` wire forms (JSON string *and* object) are accepted.
- **Outbound** returns `text`, `toolCalls` with `arguments` as a JSON **string** (the wire
  form, echoable byte-for-byte), a mapped `finishReason` — **any tool call wins, giving
  `"tool_calls"`** — plus `usage`, `model` and the raw response. No tool call is dropped or
  truncated.
- **Shares the loop's infrastructure**: retries/backoff, request-param merging and the `llm`
  observability event. `beforeLLM`/`afterLLM` fire **once**; tool hooks never fire, because no
  tool runs.

**Parity verified by byte-diff, not assumed** (spike 0003). One adversarial fixture hitting
every §11 rule at once was run through all six ports and diffed: `js`, `python`, `java`,
`csharp` and `elixir` are **byte-identical to `golang`**, first diff, no corrections needed.
The agreed output is committed at `docs/spikes/0003-translation-parity-fixture.json` so a
future port or refactor can be checked against it directly.

Test counts: js 13 · python 20 · golang 12 · java 13 · csharp 20 · elixir 27. Every port's
full suite green; Elixir coverage 96.9% (gate 95).

Also adds `golang/examples/translator` — a stateless OpenAI-compatible proxy in ~60 lines.

**Known issue, pre-existing:** `python/pyproject.toml` allows `mcp>=1.0.0`, but `mcp` 2.x
renamed `streamablehttp_client` and `mcp_source.py` fails **at import**, so the package will
not load. Pin `mcp<2.0.0` until the constraint is tightened.

## 0.11.0 — 2026-07-26

Makes §7F compaction actually reachable from a §7E persona agent. `SPEC.md §7F` defined
compaction *as* a use of the §8 `beforeLLM` hook, but the §7D agent runtime built each
handle's client internally and forwarded no hooks — so the spec promised a capability its
own runtime could not deliver. All six ports; nothing changes unless you opt in.

### Added

- **The §8 seams on a §7D agent run** (`SPEC.md §7D` "The §8 seams on an agent run",
  OpenSpec change `expose-agent-runtime-hooks`, driven by `docs/adr/0008`). `hooks` and
  `onMetric` are now optional on **both** the agent runtime and an **individual agent
  definition** — spelled as each port already spells them (`hooks`/`onMetric` in js,
  `hooks`/`on_metric` in python and elixir, `Hooks`/`OnMetric` in golang and csharp,
  `hooks(...)`/`onMetric(...)` on the java builder). Four rules hold identically everywhere:
  resolved **def-over-runtime, replace never merge**, each field independently (so an agent
  may override `hooks` and still inherit the runtime's `onMetric`); **forwarded verbatim**,
  never composed, wrapped, reordered, defaulted or read; **not a route** to alter the
  handle's composed soul, its §10 escalating `waitFor`, its turn-gated HTTP seam or the
  runtime-wide store (which is why it is two typed fields and not a `configureClient` escape
  hatch); and **unset ⇒ byte-identical** to a runtime without the fields.

  Per-agent is the point: two agents in one runtime can now carry **different compaction
  budgets**, and a metric sink can attribute events to the agent that produced them. Ships a
  shared `examples/agent-hooks/fixture.json` conformance fixture (scenarios H1–H6 plus four
  invariants) cited by every port's test file.

- **golang: `Runtime.ConversationStore()`.** The other five ports already exposed the
  runtime-wide conversation store (`store` in js, `conversation_store` in python and elixir,
  `conversationStore()` in java, `ConversationStore` in csharp); Go had no accessor, so a
  caller had to inject its own `Options.Store` just to read a handle's transcript. Returns the
  injected store itself when one was supplied. Read handle only — the store is still chosen at
  construction. The obligation is now stated in `SPEC.md §7D` for all six.

### Specified (behavior was already correct, but unpinned)

- **Compaction × §10 suspension.** A turn that compacts and *then* suspends is rewound with
  the rest of the turn: the stored transcript returns to its **full pre-turn** state, the
  compaction is discarded, and the resumed replay compacts again. Every port already behaved
  this way by accident; it is now a requirement with a scenario and a per-port test, so a port
  cannot "optimize" by persisting the compacted head.

### Fixed

- **elixir: a wrong-arity `before_llm` hook was silently ignored.** The client guarded on
  `is_function(f, 1)` and otherwise fell through to the no-op branch — the hook simply never
  ran, with no error. It now raises `ArgumentError`. Much easier to hit now that hooks can
  arrive from two places.
- **java / csharp: hooks could be silently dropped on spawn.** Both ports rebuild defs and
  options field-by-field in two places each (`withBudget` + `copyWithRegistry`; `CloneWith` +
  `CloneWithRegistry`), all on the spawn / Level-1 path. Both now carry the new fields, pinned
  by a clone test in csharp. Not a risk in the other four (golang copies by value, js spreads,
  python uses `dataclasses.replace`, elixir uses `Map.put`).
- **elixir: de-flaked the parallel-tool-call test.** It proved concurrency by wall clock
  (`elapsed < 280` over two 150ms sleeps) and hit 524ms on a loaded CI runner. Now asserts the
  **peak** number of tools in flight simultaneously, which serialized execution can never
  reach; mutation-verified by forcing `max_concurrency: 1`.

### Not included

Both deferrals from `docs/adr/0008` stand: a `preCompact` hook able to **abort** a compaction
(new control flow across six ports, awaiting downstream evidence) and `cache_control`
breakpoints (a provider-payload change). Each wants its own ADR.

## 0.10.0 — 2026-07-19

The agent release: both agent archetypes — coding (sub-agents) and persona (agent home) —
ship on a shared actor-model runtime, with compaction to keep either alive over long
sessions. All six ports, byte-parity against the shared `examples/` fixtures.

### Added

- **Context compaction in all six ports** (`SPEC.md §7F`, OpenSpec change `add-compaction`).
  An opt-in `beforeLLM` helper that keeps a long-lived or high-tool-volume agent under its
  context window — **additive, no core loop change**. In the `agents` surface:
  `compactor({ maxTokens, keepTail, summarize, countTokens, flushToMemory })` returns a
  `beforeLLM` hook that, once the transcript estimate exceeds `maxTokens`, replaces the older
  body with one summary system message and keeps a recent tail; below budget it is a **no-op,
  byte-identical** to no compactor. Two invariants: the retained tail begins at a `user` turn
  (**tool-pair safety** — a `tool` result is never orphaned from its `tool_call_id`), and a
  leading `system` prompt is preserved verbatim. `summarize(older)` is pluggable and MAY call an
  LLM; `countTokens` defaults to `ceil(chars/4)` (`estimateTokens`, an estimator not a
  tokenizer); `flushToMemory` injects a pre-compact reminder to persist durable facts via the
  §7E `memory` tool before summarizing. Ships a shared `examples/compaction/` conformance
  fixture and a "keep a persona alive for weeks" recipe (compactor + `flushToMemory` + the
  memory builtin) on the persona-agents docs page.

- **Persona agents (agent home) in all six ports** (`SPEC.md §7E`, OpenSpec change
  `add-agent-home`). The persona archetype over the §7D runtime — additive and opt-in, no
  runtime change. In the `agents` namespace: `fromDir(dir)` (Python `agent_from_dir`, Java
  `agentFromDir`) composes the bootstrap files
  `AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md` (in that order, 2 MB/file cap) into a
  frozen soul snapshot at session start; a file-backed `memory` builtin (`memoryTool(dir)`,
  actions `add`/`replace`/`remove` over `MEMORY.md`/`USER.md`) that writes to **disk** and loads
  at the START of the next session — never mutating the live prompt, keeping a long-lived persona
  cache-stable (a missing substring is a loud `isError`; opt out with `memory: false`); and
  `startAgent(agent, …, { everyMs })` — a heartbeat that posts a coalescing tick to the agent's
  own inbox and wakes it to read `HEARTBEAT.md`, where a `HEARTBEAT_OK` reply stays silent.
  Channels stay the host's job (wire inbound to `post`/`wake`). Ships with a runnable
  `examples/persona-agent/` ("Ava") + JS/Python/Go entrypoints, a "when to use which surface"
  guide, and dream/consolidation + channel-assistant recipes (composition, no new API).

- **Agent runtime + sub-agents in all six ports** (`SPEC.md §7D`, OpenSpec change
  `add-subagents`). A new `agents` namespace per port (never colliding with the A2A
  `Agent`): `agent(name, { does, uses, soul/soulFile, team, budget, model, waitFor,
  onSpawn, onClose })` with `.run(prompt)` and `.asTool()` — an Agent IS a Tool. Delegation
  runs through a built-in `task { agent, prompt }` tool (team-scoped, opt-in per
  definition): isolated child transcript, one tool message back, usage roll-up, parallel
  task calls. Underneath: a Handle state machine with six host verbs
  (`spawn/post/wake/wait/interrupt/close`), two delivery rails, three loud backpressure
  gates, hierarchical live-enforced budgets, §10 suspension escalation with durable resume
  by task-key reattachment, and a per-port cancellation contract.

### Changed — action may be required

- **`RunResult.status` gains `"incomplete"`** (QG5). A `maxTurns` stop that still had tool
  calls in flight — on the plain client `run`/`ask`/`stream` loops as well as agent runs —
  now returns `status: "incomplete"` plus `limit: "maxTurns"` (idiomatic casing per port)
  instead of a silent `"done"`. Any limit stop (turns, tokens, tool calls, wall clock) is
  loud and names its limit; partial work and the transcript are preserved. **Code that
  matches `status === "done"` after hitting `maxTurns` must update** to handle
  `"incomplete"`. The full closed status vocabulary is now
  `"done" | "pending" | "incomplete" | "interrupted" | "closed" | "timeout" | "error"`,
  identical strings in all six ports.
