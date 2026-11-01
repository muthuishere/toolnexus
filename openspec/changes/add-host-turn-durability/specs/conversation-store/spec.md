## ADDED Requirements

### Requirement: Interrupted stateful turns are saved

When a stateful run (`ask` with an id, or streaming with an id) ends in an error or a cancellation,
the client SHALL save the transcript as it stood: the user prompt and every complete
assistant/tool message. For every assistant tool call without a result, it SHALL first append a
result with `isError` true and output `interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.`, so every tool call keeps its result in both
dialects. No rollback is claimed and nothing re-executes the call. The client SHALL still return or emit the original error. A failure before any message
was added SHALL still save the user prompt.

#### Scenario: Cancelled mid-tool keeps prompt and finished pairs

- **WHEN** a turn with an id is cancelled while its second tool call runs
- **THEN** the store holds the user prompt, the assistant tool-call message, the first tool's result, and an error result for the second whose output is the interrupted text above
- **AND** the caller receives the cancellation error

#### Scenario: Provider error saves the prompt

- **WHEN** the first model call fails for an `ask` with an id
- **THEN** the store holds the prior transcript plus the new user prompt
