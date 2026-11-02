## Why

Every port runs all tool calls of one model response concurrently, with no cap and no notion of
safety. Two `write`/`edit` calls on one file race, and a response with 40 reads opens 40 at once.
A host (Karya, via go-agent-cli) asked for the scheduling a coding agent uses: read-only calls
overlap, mutating calls run alone in the order the model emitted them, under a cap.

The same request asked for a background-agent primitive: a subagent run that returns an id at once
and calls back when it finishes. The §7D runtime already has it (`spawn` + `wake` return at once,
`wait` delivers the completion); what was missing was saying so and pinning it.

## What Changes

- Client option `maxToolConcurrency` (idiomatic per port; also on the in-process client):
  positive option > env `TOOLNEXUS_MAX_TOOL_CONCURRENCY` > 10.
- A tool MAY carry `concurrencySafe(args) -> bool`. Without it a tool is safe only when its hints
  say `readOnly`; no hints ⇒ unsafe. `call_tool` takes the named tool's safety; `tool_search` is
  safe. Relay tools and the §7D `task` tool are always safe.
- The calls of one response run as consecutive runs: safe runs overlap under the cap, each
  unsafe call runs alone, in emitted order. Results keep emitted order. Applies to run and stream,
  both styles, resume paths and agent runs.
- SPEC §7D gains "Background runs": the spawn/wake/wait recipe, with a test per port. No new API.
- **Behaviour change:** unlabelled tools (no hints, no predicate) that used to run in parallel now
  run one at a time. A host that wants them parallel marks them `readOnly` or adds a predicate.

## Impact

- All eight ports' client loops (every tool-dispatch site), the Tool type, client options,
  conformance/options_manifest.json, SPEC §8 and §7D, docs site, CHANGELOG.
