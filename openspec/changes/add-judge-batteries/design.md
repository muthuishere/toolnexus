# Design — add-judge-batteries

Builds on `add-judge-adapters` (SPEC §8B *Simple judgments*). Contract data:
`examples/judge/batteries/` (see its README). Each fixture's `note` is normative text.

## Decisions

- **D1 Owner decision on ADR 0035 D6: ModelRouter is opt-in.** No router ⇒ the configured
  `model` is transmitted verbatim, pinned by the existing routing conformance tests. A router is
  a value the user constructs from a user-supplied, ordered list of `{id, description}` model
  options and attaches as a `beforeLLM` hook. It routes only on a *sure* pick (confidence > high
  and not nearUniform); unsure, missing and error all fall back to the configured model.
- **D2 The one loop change: `beforeLLM` may return `model`.** The override applies to **that
  turn only**: the request body's `model` and the `afterLLM` event's `model` carry it. Absent,
  null or empty ⇒ the configured model verbatim. Metric events are unchanged (configured model).
  Every client loop (openai + anthropic, run + stream) honours it.
- **D3 Common shape.** Constructor takes a `Classifier` plus options. Every battery accepts
  `bands` (default 0.30/0.70) and `role` (overrides the default role sentence). A battery whose
  error outcome is a policy choice (ToolGuard, ToolRelevance, SkillRelevance, ToolResultFilter,
  IsComplete, ContentGuard) takes a **required** `onError: "open" | "closed"`, no default; a
  missing or other value is a constructor error. The routers take no `onError`: their `fallback`
  is the error outcome. A classifier error never propagates from a standalone method: the verdict
  carries `error` (message string) and `calibrated: false`.
- **D4 State and questions.** Every battery builds `State(role, data)` and names the state field
  each question judges (ADR 0035 D7). Default role and question text is pinned by the fixtures;
  exact strings are in the fixtures, not repeated here.
- **D5 Seams.**

  | battery | standalone | hook |
  |---|---|---|
  | ToolGuard | `check({name, arguments, description?})` | beforeTool |
  | ToolRelevance | `select(prompt, [{name, description}])` | beforeLLM (override `tools`) |
  | SkillRelevance | `select(prompt, [{name, description}])` | none: skills live in the system prompt, outside `messages` in the anthropic style; feed `selected` into the S2 allowlist instead (an empty `selected` must not be passed as an allowlist: empty ⇒ all) |
  | ToolResultFilter | `filter(query, chunks)` | afterTool |
  | IsComplete | `check(task, answer)` | none (ADR 0035) |
  | AgentRouter | `pick(task, agents, fallback)` | none: no uniform subagent seam across ports; the host dispatches on `agent` |
  | ContentGuard | `check(text)` | beforeLLM (block ⇒ error) |
  | ModelRouter | `pick(prompt, fallback)` (models given at construction) | beforeLLM (override `model`) |

- **D6 `asHook(next)` composition.** `next` may be absent. The battery never discards it.
  - beforeTool (ToolGuard): allow ⇒ return `next(ev)` (or no override). deny ⇒ short-circuit with
    `{output: "denied by tool guard: <reason>", isError: true}`. ask ⇒ short-circuit with
    `{output: "approval required: <name>", isError: true, metadata: {pending: Request}}`,
    `Request = {id: "toolguard:<call id>", kind: "approval", prompt: "Approve the call to <name>? (<reason>)",
    data: {tool, arguments, reason, risk}}` (`risk` null when absent). `next` is not called on
    deny/ask (the tool must not be reached). On approval §10 runs the tool directly (it does not
    re-enter beforeTool), so there is no re-ask loop.
  - beforeLLM (ToolRelevance, ContentGuard, ModelRouter): the battery computes its own override,
    calls `next` with the event as the override would leave it, and merges field by field:
    whatever `next` returns wins; fields `next` leaves absent keep the battery's.
    No user text, no tools (relevance) or no models (router) ⇒ plain delegation, no classifier call.
  - afterTool (ToolResultFilter): only a non-error result whose text output splits into ≥ 2
    chunks on the exact separator `"\n\n"` is filtered; a result that also carries non-text
    content parts is passed through untouched. Kept chunks re-join with `"\n\n"`. The query is
    `{tool: name, arguments: args}`. `next` sees the filtered result; its override wins.
- **D7 Latest user text** (`user-text-cases.json`): walk messages from the end; the first
  `role: "user"` message with text wins; string content is the text; array content joins every
  `{type: "text"}` part's `text` with `"\n"`; a user message with no text (tool_result only) is
  skipped.
- **D8 Decisions stay advisory.** ToolGuard's `deny` is a policy aid, not a security control;
  allowlists stay in code in front of the battery (SPEC §8B, ADR 0035 D4). Numeric thresholds
  (`askAt`, `denyAt`) are compared in code; the classifier only rates.
- **D9 Hooks re-judge every turn.** A hook has no run identity to cache on; with a
  near-deterministic backend the same user text yields the same verdict. Cost: one classifier
  call per turn per attached battery.

## Verdict fields (fixture `want` keys; idiomatic casing per port)

- ToolGuard: `action`, `reason` (`low risk` | `medium risk` | `high risk` | `uncertain` |
  `missing answer` | `classifier error`), `risk` (number | null), `sure`, `calibrated`, `error`.
- ToolRelevance / SkillRelevance: `selected`, `dropped` (names, input order), `calibrated`, `error`.
- ToolResultFilter: `kept`, `dropped` (indices), `calibrated`, `error`.
- IsComplete: `complete`, `p` (number | null), `band`, `calibrated`, `error`.
- AgentRouter: `agent`, `path`, `sure`, `probabilities` (map | null), `calibrated`, `error`.
- ContentGuard: `action`, `flagged`, `uncertain`, `scores`, `calibrated`, `error`.
- ModelRouter: `model`, `routed`, `sure`, `probabilities` (map | null), `calibrated`, `error`.

In fixtures `error` is a boolean: whether the verdict carries an error.

## Risks

- A threshold tuned on `systemone` does not transfer to `llm` (§8B); verdicts carry `calibrated`.
- A hook that judges every turn adds latency per turn; hosts that care attach it selectively.
- ModelRouter can route a hard task to a cheap model on a confident wrong pick; the fallback only
  covers unsure picks. That is the opt-in trade the owner accepted.
