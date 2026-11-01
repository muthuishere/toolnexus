## Why

Karya (go-agent-cli) re-sends a large stable prefix every request: a fixed core
tool array and a ~6.6k-token skills catalog. Cache-hit rate is an engine quality
bar (owner); toolnexus had no prompt-cache support and its usage did not even
report cache tokens, so a regression was invisible.

Go first (owner rule, confirmed by the Karya session 2026-11-01); the other six
ports follow before the next release. The design covers all seven.

## What Changes

Phase 1 (done in golang):
- `Usage.cacheReadTokens` / `cacheWriteTokens` (anthropic cache_read_input_tokens /
  cache_creation_input_tokens; openai prompt_tokens_details.cached_tokens).
- `ClientOptions.promptCache {key, longTTL}`: anthropic gets cache_control
  breakpoints on the last tool, the system prompt and the latest message (3 of 4);
  openai gets `prompt_cache_key`. Absent ⇒ byte-identical. Applied before
  requestParams/bodyTransform.
- Hermetic replay test with a simulated prefix cache.

Phase 2 (design here, not built): tool tiers (core / listed / deferred) and a
`tool_search` builtin that returns full schemas as a message (the tools array
never changes mid-session), a host-set schema token budget (default ~5% of the
window) that demotes least-used items by host-supplied stats, tier moves only at
session start (mid-session: append only), skills already load bodies on demand.
Gemini explicit cachedContent: deferred.

## Impact

golang only today. SPEC.md §8 "Prompt caching". Known prefix breaker left for
phase 2: `Toolkit.Register` mid-session appends to the tools array, which
invalidates the cached tools prefix — hosts that register late (MCP arrival)
should load those tools as deferred once tiers exist.
