## ADDED Requirements

### Requirement: A declined durable resume carries the host's reason

On the durable resume path (`runWithAnswer` / `askWithAnswer`), an `Answer` with `ok == false`
SHALL resolve every outstanding tool call of the halted assistant turn with an error
`tool_result` whose content is exactly `relay declined for <name>: <reason>`, where `<reason>`
is `answer.reason` (or `declined` when empty) and `<name>` is the relayed tool's name (or the
call id when unknown). The resume SHALL NOT fail and SHALL NOT use the
`no result supplied on resume for <name>` filler for a declined answer. Any `data` on a declined
answer SHALL be ignored.

#### Scenario: The reason reaches every call

- **WHEN** a run halts on a turn with relay calls `alpha` and `beta` and the host resumes with
  `answerDeclined(pending.id, "user said no")`
- **THEN** the two tool results are `relay declined for alpha: user said no` and
  `relay declined for beta: user said no`, both errors, and the run continues

#### Scenario: An empty reason defaults to declined

- **WHEN** the host resumes with `answerDeclined(pending.id, "")`
- **THEN** each tool result is `relay declined for <name>: declined`
