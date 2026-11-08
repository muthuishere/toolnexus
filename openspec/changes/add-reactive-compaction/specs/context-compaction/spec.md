## ADDED Requirements

### Requirement: Context overflow is detected deterministically
Each port SHALL export `isContextOverflow(status, body)` (idiomatic name). It SHALL return true
if and only if `status` is 400 or 413 AND the lowercased body contains at least one of
`context_length_exceeded`, `maximum context length`, `prompt is too long`, `prompt_too_long`,
`request_too_large`, `context window`.

#### Scenario: OpenAI and Anthropic overflows are detected
- **WHEN** the provider answers 400 with `code: context_length_exceeded`, or 400 with `prompt is too long`, or 413 with `request_too_large`
- **THEN** `isContextOverflow` returns true

#### Scenario: Status without a marker, or a marker under another status, is not an overflow
- **WHEN** the provider answers 413 `Payload Too Large`, or 429 / 500 mentioning the context length
- **THEN** `isContextOverflow` returns false

### Requirement: Reactive compaction is opt-in and retries the same turn
With the client option `compactOnOverflow` set, an LLM call whose final failure is a context
overflow SHALL call that hook with `{messages, tools, model, turn}`; when it returns strictly
fewer messages they SHALL replace the working transcript and the same turn SHALL be re-sent
without running `beforeLLM` again and without consuming a turn. With the option absent the run
SHALL be byte-identical to a run without this feature. This SHALL hold in `run` and `stream`,
both styles, and the resume paths.

#### Scenario: Absent option surfaces the provider error
- **WHEN** `compactOnOverflow` is not set and the provider answers with a context overflow
- **THEN** the run fails with the typed `LlmHttpError` exactly as before

#### Scenario: Compact once and succeed
- **WHEN** `compactOnOverflow` is set, the first call overflows and the retry succeeds
- **THEN** the hook ran once, the retry carried the compacted transcript, and the run returns the answer

#### Scenario: A non-overflow 400 is not compacted
- **WHEN** `compactOnOverflow` is set and the provider answers 400 without an overflow marker
- **THEN** the hook is not called and the run fails with `LlmHttpError`

### Requirement: A breaker stops rapid refills
The loop SHALL count successful LLM calls since the last reactive compaction (`okSince`) and
reactive compactions in the current rapid chain (`streak`). An overflow with `streak > 0` and
`okSince < 2` is a rapid refill; any other overflow SHALL reset `streak` to 0. An overflow with
`streak == 3` SHALL fail the run with `ContextOverflowError` (`compactions` 3) without calling the
hook. A hook that returns nothing or a transcript that is not shorter SHALL fail the run at once.
The error SHALL carry `compactions`, `status`, `body`, and the message
`context overflow: <compactions> reactive compaction(s) did not make the prompt fit`.

#### Scenario: Three rapid refills fail the run
- **WHEN** every call after a reactive compaction overflows again within one successful call
- **THEN** after three compactions the next overflow fails with `ContextOverflowError` and `compactions` 3

#### Scenario: Spaced overflows keep compacting
- **WHEN** each overflow comes at least two successful calls after the previous compaction
- **THEN** the streak resets and the run compacts as often as needed and completes

#### Scenario: No progress fails immediately
- **WHEN** the hook returns nothing on an overflow
- **THEN** the run fails with `ContextOverflowError` and `compactions` 0
