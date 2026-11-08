## MODIFIED Requirements

### Requirement: A tool may return content parts

`ToolResult` SHALL gain an optional `parts` field. `output` SHALL remain required and SHALL remain
what the transcript, compaction, token counting, and text-only providers see. A `ToolResult`
setting no `parts` SHALL behave byte-identically to today.

A run SHALL surface the parts its tools returned: each `RunResult.toolCalls[]` entry SHALL carry
`parts` when that tool returned parts (absent otherwise), and `RunResult.files` SHALL list every
non-text part of every tool call in the run, in call order, each tagged with `from` (the tool name)
and `callId` (the model's tool-call id); `files` SHALL always be present (`[]` when none). The
final `text` SHALL NOT receive markup for them. `files` SHALL be consistent with `toolCalls` in
every loop path (run and stream, both styles, the resume paths) and SHALL accumulate exactly as
`toolCalls` does across completion-gate retries (§7D).

#### Scenario: A tool returning an image also returns describing text

- **WHEN** a tool returns `output:"screenshot, 1280x720 png"` and one `image` part
- **THEN** the transcript's tool-result text is the `output` string, and the image is delivered
  to the provider on the same exchange

#### Scenario: parts does not collide with suspension

- **WHEN** a `ToolResult` carries both `parts` and `metadata.pending`
- **THEN** the run suspends per §10 exactly as it would without `parts`

#### Scenario: Run result lists the produced parts

- **WHEN** a tool returns one `image` part during a run
- **THEN** that call's `RunResult.toolCalls[]` entry carries the part in `parts`, and
  `RunResult.files` holds the image with `from` = the tool name and `callId` = the call id

#### Scenario: No parts produced

- **WHEN** no tool in the run returns parts
- **THEN** `RunResult.files` is `[]` and no `toolCalls[]` entry carries `parts`

#### Scenario: Files survive a completion-gate retry

- **WHEN** an agent with a completion gate generates an image in attempt 1, fails verification,
  and makes no files in attempt 2
- **THEN** the final `RunResult.files` still holds the image from attempt 1, alongside the
  accumulated `toolCalls`
