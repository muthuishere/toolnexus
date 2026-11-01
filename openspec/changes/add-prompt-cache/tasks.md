## 1. Phase 1 — golang (done 2026-11-01)
- [x] 1.1 Usage.CacheReadTokens / CacheWriteTokens (anthropic + openai)
- [x] 1.2 ClientOptions.PromptCache + applyPromptCache in finalizeBody (all 4 loops)
- [x] 1.3 prompt_cache_test.go: simulated-cache replay, off-by-default, openai key, no transcript mutation

## 2. Phase 2 — golang
- [x] 2.1 Tool tiers core/listed/deferred + tool_search builtin (schemas appended as a message; deferred tools callable once loaded)
- [x] 2.2 Schema token budget (host-set, default ~5% window) + least-used demotion from host stats; tier moves at session start only
- [ ] 2.3 DEFERRED (Karya, 2026-11-01): Gemini explicit cachedContent. Implicit prefix caching via the stable prefix is enough; no native Gemini style until a consumer needs one.
- [x] 2.4 (Karya: keep cacheReadTokens/cacheWriteTokens unless go-agent-cli-57 objects)
- [x] 2.5 Document anthropic total input = prompt + cacheRead + cacheWrite
- [x] 2.6 tool_tiers_test.go (plan, late register, budget demotion, call_tool unwrap + stable array)
- [ ] 2.7 (was 2.4) Align usage field names with go-agent-cli's usage frame (go-agent-cli-57)

## 3. Other ports (pending — before the next release)
- [ ] 3.1 js  - [x] 3.2 python  - [ ] 3.3 java  - [ ] 3.4 csharp  - [ ] 3.5 elixir  - [ ] 3.6 clojure
