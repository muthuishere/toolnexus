## ADDED Requirements

### Requirement: Streaming loops honor the first-in-order suspension halt
The streaming run path (`stream()`) SHALL apply the same concurrent-suspension rule as the
non-streaming path: when more than one tool call in a turn suspends and no `waitFor` is configured,
the stream SHALL halt on the **first** suspension in tool-call order — emitting the `pending` stream
event and a `done` event whose `RunResult.status == "pending"` carries that first request — and SHALL
NOT record the later concurrent suspensions' placeholder results in the transcript. The streaming and
non-streaming paths SHALL be consistent, and identical across all five ports.

#### Scenario: Two concurrent suspensions in a streamed turn halt on the first
- **WHEN** a streamed turn issues two tool calls that both suspend and no `waitFor` is configured
- **THEN** the stream emits a `done` event with `RunResult.status == "pending"` whose `pending` is the
  first tool call's request, and the second suspension's placeholder result is not in the transcript
