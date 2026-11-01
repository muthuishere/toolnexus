## ADDED Requirements

### Requirement: Compaction notice
The compactor SHALL accept an `onCompact(ctx, info)` callback, called after each compaction with tokensBefore, tokensAfter, messagesBefore, messagesAfter, summarized and summary, and never called when the compactor is a no-op.

#### Scenario: Over budget
- **WHEN** the transcript exceeds the budget and is compacted
- **THEN** `onCompact` is called once with token counts from `countTokens` and tokensAfter below tokensBefore

#### Scenario: Under budget
- **WHEN** the transcript is within budget
- **THEN** `onCompact` is not called

### Requirement: The latest user prompt is never summarized
When no clean user boundary exists after the first message following the system prompt, and that message is a user prompt, the compactor SHALL keep that prompt verbatim and summarize only the messages after it, producing `[system?, prompt, summary]`.

#### Scenario: One prompt followed by a long agentic run
- **WHEN** a transcript is one user prompt followed only by assistant tool calls and tool results, over budget
- **THEN** the output is the system prompt, the user prompt verbatim, then the summary

### Requirement: Compaction never grows the transcript
The compactor SHALL return no override, and SHALL NOT call `onCompact`, when the compacted transcript's token count is not smaller than the input's.

#### Scenario: Summary longer than what it replaces
- **WHEN** the summary makes the result at least as large as the input
- **THEN** the compactor is a no-op

### Requirement: Folded messages are reported
`onCompact` info SHALL include `folded`: the summarized messages, verbatim and in order, so a host can persist them.

#### Scenario: Folded matches the input
- **WHEN** a compaction happens
- **THEN** `folded` equals the summarized input messages and its length equals `summarized`
