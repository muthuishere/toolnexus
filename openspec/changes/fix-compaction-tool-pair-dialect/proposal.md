# Compaction's tool-pair rule is dialect-blind and orphans tool results under Anthropic

## Why

`context-compaction` already requires that "the compacted result SHALL never orphan a tool result".
The mechanism it specifies to achieve that — "the retained recent tail SHALL begin at a `user`
message" (`SPEC.md:961-964`) — is stated in **OpenAI vocabulary** and does not hold for the Anthropic
dialect.

Under `style:"anthropic"` the client carries tool results in a **`user`** message
(`golang/client.go:1256` appends `{"role":"user","content":blocks}` where the blocks are
`tool_result`). So a "clean user boundary" can be the tool-result carrier itself, and the
`assistant` message holding the matching `tool_use` is summarized into the dropped head.

The code asserts the opposite in a comment — `golang/agents/compaction.go:100-102`: "Scanning to a
user turn guarantees tool-pair safety — a tail starting at a user message can never orphan a `tool`
result from its tool_calls." True for OpenAI, false here.

Reproduced against the shipped Go compactor (`golang/agents/orphan_repro_test.go`). An
Anthropic-shaped transcript compacts to:

```json
[{"role":"system","content":"soul"},
 {"role":"system","content":"[Summary of earlier conversation]\nSUMMARY"},
 {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu_1","content":"echoed"}]},
 {"role":"assistant","content":"done"}]
```

The tail opens with `tool_result` for `tu_1` and no `tool_use` for `tu_1` survives anywhere. The
Anthropic API rejects a `tool_result` without its matching `tool_use`, so this transcript is
undeliverable — a long-running Anthropic agent breaks at exactly the moment compaction first fires.

Filed but not fixed by `docs/adr/0013-model-free-tool-result-pruning-as-a-compaction-stage.md`, which
deferred it to a separate change rather than bundling it.

## What Changes

- The tool-pair invariant SHALL be restated in **dialect-neutral** terms: the tail must begin at a
  user turn that is *not* a tool-result carrier.
- The tail scan in every port SHALL skip a `user` message whose content carries `tool_result` blocks,
  continuing back to a genuine user turn — the same "safety over size" fallback the rule already uses
  when no boundary fits `keepTail`.
- A transcript in which no safe boundary exists SHALL remain a no-op, as today.
- **Not a breaking change.** For OpenAI-dialect transcripts no boundary changes, so output is
  byte-identical. For Anthropic-dialect transcripts the split moves earlier — which is the fix, and
  the only transcripts affected are ones that are currently undeliverable.

## Relationship to `add-canonical-transcript`

That change makes a tool result a first-class message kind, which removes the ambiguity permanently —
"a user turn" stops being able to mean "a tool result". This change is the **narrow correctness fix
that does not wait for it**: it is one predicate per port, it does not touch the message
representation, and it can land and ship while the larger change is still in review. When
`add-canonical-transcript` lands, the predicate simplifies to a kind check rather than disappearing.

## Capabilities

### Modified Capabilities

- `context-compaction`: the "Compaction preserves tool-pair integrity" requirement keeps its promise
  and gains a dialect-neutral mechanism plus a scenario covering the Anthropic carrier shape.

## Impact

- **Code**: the tail-scan in each port's compaction module — `golang/agents/compaction.go:99-122`,
  `js/src/agents/compaction.ts`, `python/src/toolnexus/agents/compaction.py`,
  `java/.../Compaction.java`, `csharp/src/Toolnexus/Agents/Compaction.cs`,
  `elixir/lib/toolnexus/agents/compaction.ex`, `clojure/src/toolnexus/agents/compaction.cljc`.
  Seven ports — §7F ships in all of them.
- **Contract**: `SPEC.md:961-964`, whose wording is the origin of the defect.
- **Risk**: low. The change is a narrowing of which messages qualify as a boundary; the failure mode
  of getting it wrong is keeping a larger tail, which the rule already permits ("safety over size").
- **Clojure note**: `clojure/src/toolnexus/agents/compaction.cljc` is constrained to `clojure.core`
  only across four hosts (ADR 0009), so the predicate must avoid host interop.
