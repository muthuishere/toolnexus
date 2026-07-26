## ADDED Requirements

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
