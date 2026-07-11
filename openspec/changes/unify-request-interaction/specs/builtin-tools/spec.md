## ADDED Requirements

### Requirement: The question builtin suspends via a kind:"question" Request
The built-in `question` tool SHALL treat asking the human as a §10 suspension, not an immediate
result. On its **first** execution (no resolution present in context) it SHALL return a
suspension — a `ToolResult` whose `metadata.pending` is a `Request` with `kind: "question"`, a
human-readable `prompt` rendered from the questions, and `data.questions` carrying the structured
questions array unchanged. When the loop re-executes the tool after the host's `waitFor` resolved
the request affirmatively (`answer.ok == true`, per the §10 loop rule), the tool SHALL return a
non-error `ToolResult` whose output is the resolution payload (`context.answer.data`), forwarded
verbatim without interpretation — the resolution *is* the answer, as with `kind: "input"`. When no
`waitFor` is configured, the run SHALL halt durably (`RunResult.status == "pending"`) carrying the
`question` request, resumable by a later `run`, exactly as for any other suspension `kind`.

The tool's name (`question`) and input schema SHALL be unchanged; only the return value changes
from an immediate result to a suspension. `kind: "question"` is a convention over the open §10
`kind` vocabulary, not a new closed type. This behavior SHALL be identical across all five ports.

#### Scenario: First call suspends instead of answering
- **WHEN** the `question` tool is executed with a `questions` array and no prior resolution in context
- **THEN** it returns a `ToolResult` with `metadata.pending` present, `pending.kind == "question"`,
  a non-empty `pending.prompt`, and `pending.data.questions` equal to the supplied questions —
  and it does NOT return an immediate non-error result carrying the questions as output

#### Scenario: Resolved re-execute returns the human's answer
- **WHEN** a `waitFor` resolves the question request with `Answer{ok: true, data: <picks>}` and the
  loop re-executes the `question` tool once with that answer in context
- **THEN** the tool returns a non-error `ToolResult` whose output is `<picks>` (the answer data)
  forwarded verbatim, and that output is fed back to the model

#### Scenario: No waitFor halts durably with the question request
- **WHEN** the `question` tool suspends and no `waitFor` is configured on the client
- **THEN** the run does not hang; it returns a `RunResult` with `status == "pending"` whose
  `pending` is the `kind: "question"` request, resumable by calling `run` again later

#### Scenario: A guard-raised Request is honored identically to a tool-raised one
- **WHEN** a `beforeTool` hook returns `{ result: pending({ kind: "approval", prompt: ... }) }`
  so that no underlying tool code runs
- **THEN** the loop detects the suspension downstream of the hook and resolves it through the same
  one `waitFor` (or halts durably when none is set) — identically to a suspension returned by a
  tool such as `question`
