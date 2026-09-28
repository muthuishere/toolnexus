## ADDED Requirements

### Requirement: Batteries are built on a Classifier and never authorise

Each battery (`ToolGuard`, `ToolRelevance`, `SkillRelevance`, `ToolResultFilter`, `IsComplete`,
`AgentRouter`, `ContentGuard`, `ModelRouter`) SHALL take a `Classifier` (never a vendor, URL or
model), SHALL build `State(role, data)` with each question naming the state field it judges, and
SHALL build exactly the state and questions recorded in `examples/judge/batteries/`. A battery's
verdict is advisory and SHALL NOT be described as a security control.

#### Scenario: Default question text is pinned by the static corpus

- **WHEN** a port runs any case of `examples/judge/batteries/*.json` on a `static` classifier
  built from that case's `calls`
- **THEN** the battery's requests hit the corpus (no miss) and the verdict equals `want`

#### Scenario: No items means no classifier call

- **WHEN** ToolRelevance, SkillRelevance or ToolResultFilter get an empty list, or ModelRouter
  gets no models
- **THEN** the classifier is not called and the verdict is the empty / fallback verdict

### Requirement: Error outcome is a required host decision

ToolGuard, ToolRelevance, SkillRelevance, ToolResultFilter, IsComplete and ContentGuard SHALL
require `onError` ∈ {`open`, `closed`} at construction with no default. A classifier error SHALL
NOT propagate from a standalone method; the verdict SHALL carry the error and
`calibrated: false`, and take the `onError` outcome. AgentRouter and ModelRouter SHALL use their
`fallback` as the error outcome.

#### Scenario: Missing onError is rejected

- **WHEN** a host constructs ToolGuard without `onError`
- **THEN** construction fails with an error naming `onError`

#### Scenario: Fail-closed ToolGuard denies on error

- **WHEN** the classifier fails and `onError` is `closed`
- **THEN** `check` returns `deny` with reason `classifier error`, `risk` null and an error

### Requirement: ToolGuard rates a call and maps it to allow, ask or deny

ToolGuard SHALL ask one `score` question keyed `risk` and decide, in order: missing ⇒ ask
(`missing answer`); not sure ⇒ ask (`uncertain`); value < `askAt` (1.5) ⇒ allow (`low risk`);
value < `denyAt` (2.5) ⇒ ask (`medium risk`); else deny (`high risk`). As a `beforeTool` hook,
allow SHALL delegate to `next`; deny SHALL short-circuit with output
`denied by tool guard: <reason>`; ask SHALL short-circuit with a §10 `approval` Request with id
`toolguard:<call id>`, and `next` SHALL NOT be called on deny or ask.

#### Scenario: Exactly askAt asks

- **WHEN** the sure risk score is exactly 1.5
- **THEN** the action is `ask` with reason `medium risk`

#### Scenario: Ask suspends the run through path B

- **WHEN** the hook's verdict is `ask` and no `waitFor` is configured
- **THEN** the run halts with status `pending` carrying Request id `toolguard:<call id>`,
  kind `approval`, and the guarded tool never runs

#### Scenario: Allow delegates to next

- **WHEN** the verdict is `allow` and a `next` hook is given
- **THEN** `next` runs and its override (if any) is the hook's result

### Requirement: Relevance and filter batteries drop only confident noes

ToolRelevance and SkillRelevance SHALL ask one `noul` per item keyed by the item name;
ToolResultFilter one `noul` per chunk keyed by its decimal index. Only a band `no` answer drops an
item; yes, uncertain and missing keep it. Error: `open` keeps all, `closed` keeps none. The
ToolRelevance `beforeLLM` hook SHALL override `tools` with the kept provider entries in their
original order only when something was dropped; the ToolResultFilter `afterTool` hook SHALL filter
only a non-error text output with ≥ 2 chunks split on `"\n\n"`.

#### Scenario: Exactly the low cut-point is kept

- **WHEN** a chunk's noul is exactly 0.30 under default bands
- **THEN** that chunk is kept

#### Scenario: Tool relevance hook swaps tools

- **WHEN** the hook drops `send_email` from three offered tools
- **THEN** the model request carries only the other two tools, in their original order

### Requirement: IsComplete, AgentRouter and ContentGuard verdicts

IsComplete SHALL report `complete` iff its `complete` noul is band yes. AgentRouter SHALL ask one
`choice` keyed `agent` per level, descend into a picked group, and return `fallback` when a level
is unsure, missing or errors. ContentGuard SHALL ask one `noul` per dimension and return `block`
if any is band yes, else `review` if any is uncertain or missing, else `allow`; its `beforeLLM`
hook SHALL raise `content guard blocked: <flagged names joined by ", ">` on block.

#### Scenario: A router falls back on a near-uniform pick

- **WHEN** the agent choice has confidence 0.9 but is nearUniform
- **THEN** `agent` is the fallback and `sure` is false

#### Scenario: Block wins over review

- **WHEN** one dimension is yes and another uncertain
- **THEN** the action is `block`, flagged names the yes dimension and uncertain the other

### Requirement: ModelRouter is opt-in and falls back to the configured model

ModelRouter SHALL ask one `choice` keyed `model` over user-supplied `{id, description}` options
and return the pick only when sure; otherwise the fallback. Its `beforeLLM` hook SHALL use the
turn's configured model as fallback and return a `model` override only when routed to a different
id. With no ModelRouter attached the configured model SHALL be transmitted verbatim.

#### Scenario: Unsure pick keeps the configured model

- **WHEN** the router's pick has confidence 0.6
- **THEN** the verdict's model is the configured model and `routed` is false

#### Scenario: Sure pick routes the turn

- **WHEN** the hook's router is sure of `small-fast` and the configured model is `m`
- **THEN** that turn's request body carries `model: "small-fast"`

### Requirement: Hooks judge the latest user text

The `beforeLLM` batteries SHALL judge the latest user text as pinned by
`examples/judge/batteries/user-text-cases.json`, and SHALL delegate to `next` without a classifier
call when there is none.

#### Scenario: Tool results are skipped

- **WHEN** the last user message holds only `tool_result` blocks
- **THEN** the text comes from the previous user message with text
