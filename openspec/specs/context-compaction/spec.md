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

### Requirement: A compactor is attachable to a §7D agent run

The `compactor(options)` helper each port provides SHALL be attachable to an agent run by
supplying it as the `beforeLLM` hook through the `§7D` runtime's or an agent definition's `hooks`
value. Its behavior on that path SHALL be identical to its behavior on a directly constructed
client: a no-op at or below `maxTokens`, and above it a transcript whose leading system message
(the agent's composed soul) survives verbatim and whose retained tail preserves tool-pair
integrity.

Because a definition's `hooks` replace the runtime's, a caller SHALL be able to give different
agents different compaction budgets in one runtime.

#### Scenario: A long-lived agent stays under budget

- **WHEN** an agent whose transcript is estimated above `maxTokens` runs a turn with a compactor
  attached through its definition's `hooks`
- **THEN** the transcript sent to the LLM is smaller than the stored one, begins with the agent's
  soul verbatim, and contains a summary system message for the older portion

#### Scenario: The same agent run without a compactor is byte-identical

- **WHEN** the same fixture agent run executes with no compactor attached at either level
- **THEN** its transcript, trace and result are byte-identical to the run recorded before
  compaction was attachable

#### Scenario: A compacted turn that suspends is rewound with everything else

- **WHEN** an agent turn compacts its transcript and then returns `pending` under `§10`
- **THEN** the runtime restores that handle's stored transcript to its full pre-turn checkpoint,
  the compaction is discarded, and the resumed replay compacts again — a port that persists the
  compacted head across a suspension is non-conformant

#### Scenario: Two agents, two budgets

- **WHEN** two agent definitions in one runtime attach compactors with different `maxTokens`
- **THEN** each agent's turns compact at its own budget and neither is affected by the other's

