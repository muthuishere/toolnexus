## MODIFIED Requirements

### Requirement: Compaction preserves tool-pair integrity

The retained recent tail SHALL begin at a user turn that does **not** carry a tool result, so that no
tool result in the compacted transcript is ever without the assistant tool call it answers.

This rule SHALL be dialect-neutral. A `user` message whose content carries `tool_result` blocks — the
shape the client produces under `style:"anthropic"` — SHALL NOT be treated as a valid tail boundary,
because the `assistant` message holding the matching `tool_use` would be summarized into the dropped
head. Under `style:"openai"`, where tool results are `tool` messages rather than `user` messages,
every `user` message remains a valid boundary and no boundary changes.

If no valid boundary fits within `keepTail`, the tail SHALL extend back to the most recent valid user
turn (favoring safety over size). If no valid boundary exists at all, the body SHALL be summarized
with an empty tail — which is bounded and cannot orphan anything, since nothing is retained. This is
the existing behavior that keeps a long agentic run (one user prompt followed by many assistant and
tool turns) bounded, and it is unchanged by this requirement.

The compacted result SHALL never orphan a tool result.

#### Scenario: No orphaned tool result

- **WHEN** a transcript containing assistant→tool_calls / tool groups is compacted
- **THEN** every tool result in the result is preceded by the assistant message carrying its
  matching tool call id, and the first non-system message of the tail is a user turn

#### Scenario: An Anthropic tool-result carrier is not a boundary

- **WHEN** an Anthropic-dialect transcript is compacted in which the only user message inside `keepTail` is one carrying `tool_result` blocks
- **THEN** the tail extends back past that message, and the compacted transcript contains no `tool_result` block whose matching `tool_use` was dropped

#### Scenario: OpenAI-dialect output is unchanged

- **WHEN** an OpenAI-dialect transcript containing tool calls is compacted
- **THEN** the compacted transcript is byte-identical to the output before this change

#### Scenario: No safe boundary summarizes with an empty tail

- **WHEN** every candidate user turn in a transcript carries tool results
- **THEN** the body is summarized with an empty tail and the result retains no orphaned tool result
