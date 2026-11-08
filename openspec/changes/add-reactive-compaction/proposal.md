## Why

Karya (go-agent-cli, issue #194) runs long agentic sessions. The §7F compactor is proactive and
works from an estimate (`ceil(chars/4)`); when the estimate is low, or one tool result is huge, the
provider rejects the request with "prompt is too long" / `context_length_exceeded` and the whole
run dies, even though compacting and retrying would have succeeded. Karya does this itself:
compact on `prompt_too_long`, and give up after 3 rapid refills so a transcript that cannot be
made to fit does not loop forever.

## What Changes

- New client option `compactOnOverflow` (a `beforeLLM`-shaped hook, usually a `compactor(...)`
  with a tighter budget). Absent ⇒ byte-identical.
- New exported predicate `isContextOverflow(status, body)`: status 400/413 AND one of six pinned
  lowercase markers in the body.
- On an overflow the loop compacts via the hook and re-sends the same turn; a breaker fails the
  run with a typed `ContextOverflowError` after 3 consecutive compactions that each bought fewer
  than 2 successful LLM calls ("rapid refill"), or at once when the hook makes no progress.
- Applies to `run` and `stream`, both styles, resume paths, all eight ports.
- SPEC.md §7F.1 (new) and a pointer in §8 Resilience. Shared fixture
  `conformance/reactive_compaction/cases.json`. Option row in `conformance/options_manifest.json`.

## Impact

Client loop of every port; no change to `compactor`, to `onError`, or to the retry set.
