# suspension — spec delta

## ADDED Requirements

### Requirement: Request carries the suspended handle path in data.path
When a suspension propagates across an agent boundary, the Request SHALL carry the
suspended handle's deterministic id path as `data.path` (array of path segments) —
never as an ad-hoc field grafted onto the Request object. Each level that relays the
pending stamps its own handle path; because child ids are parent-prefixed, the deepest
relayed path always identifies the suspended leaf's subtree. Ports with closed Request
shapes (Go/Java/C#/Elixir structs and records) use the same `data.path` location, so
the wire shape is byte-identical across languages.

#### Scenario: Path identifies the leaf
- **WHEN** a grandchild suspends durably under parent and root with no interpreter
- **THEN** the root-level pending Request's `data.path` is a prefix chain ending in
  the suspended subtree, sufficient for the runtime to route the Answer to the deepest
  suspended handle

### Requirement: The one waitFor slot serves agent escalation unchanged
Escalation across agent boundaries SHALL reuse the existing §10 contract verbatim: a
suspending agent's pending is delivered as a normal tool pending (`metadata.pending`)
on the delegating tool's result; waitFor remains the single interpreter slot; the
Answer feeds the §10 retry-with-Context.answer path at the suspended leaf. No second
suspension mechanism, event type, or resolver interface is introduced for agents.

#### Scenario: Same machinery, deeper tree
- **WHEN** the elicitation-bridge fixture and the subagent-escalation fixture both
  suspend with kind "approval"
- **THEN** both produce byte-identical Request wire shapes (modulo data.path presence)
  and both resolve through the same waitFor slot
