# stop-reason-recovery

## ADDED Requirements

### Requirement: Stop-reason recovery is opt-in
The client SHALL accept a boolean option `stopRecovery` (idiomatic spelling per port) that defaults to false, and with it false or absent the loop MUST behave byte-identically to a client without the option.

#### Scenario: Default off leaves a thinking-only reply alone
- **WHEN** recovery is not enabled and the model replies with reasoning and no text and stop reason `end_turn`
- **THEN** the run makes exactly one provider call and returns `text: ""`

### Requirement: Empty answer is nudged once
With `stopRecovery` on, a response with no tool calls, a non-truncation stop reason and text that is empty after trimming SHALL be removed from the transcript and followed by the user message `Your previous response contained no answer. Continue and give your answer.`, at most once per run call.

#### Scenario: Thinking-only end_turn is nudged
- **WHEN** the first reply is thinking-only and the second reply is `answer`
- **THEN** the second request ends with the NUDGE user message and the run returns `answer` after 2 turns

#### Scenario: Nudge happens only once
- **WHEN** two consecutive replies are empty
- **THEN** the run ends after 2 turns with `text: ""`

### Requirement: Truncated responses are continued up to three times
With `stopRecovery` on, a response with no tool calls whose stop reason is OpenAI `length` or Anthropic `max_tokens` SHALL stay in the transcript and be followed by the user message `Your previous response was cut off at the output token limit. Continue exactly where it stopped, without repeating anything.`, at most 3 times per run call, and the final text MUST be the concatenation of the continued texts and the final text.

#### Scenario: One continuation joins the text
- **WHEN** the first reply is `Hel` truncated and the second is `lo`
- **THEN** the run returns `Hello` after 2 turns

#### Scenario: Continuation is capped at three
- **WHEN** four consecutive replies `a`,`b`,`c`,`d` are truncated
- **THEN** the run returns `abcd` after 4 turns with 3 CONTINUE messages

### Requirement: Malformed tool calls are corrected once
With `stopRecovery` on, a response whose tool calls include arguments that are not a JSON object SHALL run no tool, be removed from the transcript, and be followed by the user message `Your previous tool call could not be used: its arguments were not a valid JSON object. Call the tool again with arguments that are a single valid JSON object matching its input schema.`, at most once per run call; afterwards malformed arguments MUST be treated as `{}` as before.

#### Scenario: Corrected call runs with the new arguments
- **WHEN** the first reply calls `echo` with `{bad` and the second calls it with `{"x":1}`
- **THEN** `echo` runs once with `{"x":1}` and the second request ends with the CORRECTIVE message

### Requirement: Recoveries are bounded turns
Every recovery call SHALL count as a turn, fire the LLM hooks and be bounded by `maxTurns`; on the last permitted turn no recovery MUST be taken. Recovery SHALL apply identically in `run` and `stream` and in both styles.

#### Scenario: No recovery on the last turn
- **WHEN** `maxTurns` is 1 and the reply is empty
- **THEN** the run makes one call and returns `text: ""` with no pinned message appended
