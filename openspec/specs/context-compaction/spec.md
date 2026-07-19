# context-compaction Specification

## Purpose
TBD - created by archiving change add-compaction. Update Purpose after archive.
## Requirements
### Requirement: The compactor is a beforeLLM helper that is a no-op below budget
Each port SHALL provide a `compactor(options)` that returns a `beforeLLM` hook. When the
estimated token count of the transcript is at or below `maxTokens`, the hook SHALL return
nothing and the run SHALL behave byte-identically to a run with no compactor. The default
token estimate SHALL be `ceil(chars/4)` summed over messages, overridable via `countTokens`.

#### Scenario: Below budget is byte-identical
- **WHEN** a compactor with `maxTokens` N is applied to a transcript estimated at ≤ N tokens
- **THEN** the hook returns nothing and the transcript is unchanged

#### Scenario: Above budget compacts
- **WHEN** the transcript is estimated above `maxTokens`
- **THEN** the hook returns a transcript smaller than the original, containing a summary
  system message for the older portion

### Requirement: Compaction preserves tool-pair integrity
The retained recent tail SHALL begin at a `user` message, so that no `tool` message in the
compacted transcript is ever without a preceding `assistant` message carrying its
`tool_call_id`. If no user boundary fits within `keepTail`, the tail SHALL extend back to the
most recent user turn (favoring safety over size). The compacted result SHALL never orphan a
tool result.

#### Scenario: No orphaned tool result
- **WHEN** a transcript containing assistant→tool_calls / tool groups is compacted
- **THEN** every `tool` message in the result is preceded by an `assistant` message carrying
  its `tool_call_id`, and the first non-system message of the tail is a `user` message

### Requirement: The leading system prompt is preserved verbatim
A leading `system` message (identity / soul / skills) SHALL be kept unchanged at the head of
the compacted transcript; only the conversational body between it and the tail is summarized.

#### Scenario: System prompt survives compaction
- **WHEN** a transcript whose first message is a system prompt is compacted
- **THEN** that exact system message is still first in the result

### Requirement: Summary and token counting are pluggable; memory flush is optional
The summary SHALL be produced by a host-supplied `summarize(older) → string` (which MAY call
an LLM — the library makes no model call on the host's behalf by default). When
`flushToMemory` is set, a system reminder instructing the model to persist durable facts via
the memory tool SHALL be injected before the summary (composing with the §7E memory builtin).

#### Scenario: Flush-to-memory reminder
- **WHEN** a compactor with `flushToMemory` set compacts a transcript
- **THEN** the result contains a system message instructing the model to save anything worth
  keeping with the memory tool, positioned before the retained tail

#### Scenario: End-to-end run across a compaction
- **WHEN** a client is configured with a `compactor` beforeLLM hook and a run grows the
  transcript past `maxTokens` mid-run
- **THEN** the run continues to a normal terminal result and the final `RunResult.messages`
  is bounded (compacted), not the full raw history

