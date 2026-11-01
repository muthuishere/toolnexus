package toolnexus

// Prompt caching (change add-prompt-cache, phase 1). The engine keeps the
// request prefix stable and marks it for the provider's cache. golang only for
// now; other ports pending.

// PromptCacheOptions configures ClientOptions.PromptCache.
type PromptCacheOptions struct {
	// Key groups requests that share a prefix (one per session). Sent as
	// openai prompt_cache_key. Ignored by anthropic, which caches by prefix.
	Key string
	// LongTTL asks for the provider's longer cache lifetime where one exists
	// (anthropic ttl "1h"; default is the provider's short lifetime).
	LongTTL bool
}

// applyPromptCache marks the cacheable prefix in an assembled body, in place.
//   - anthropic: cache_control breakpoints on the last tool, the system prompt
//     and the last content block of the latest message (3 of the 4 allowed).
//     The transcript's own maps are never mutated: touched entries are copied.
//   - openai: prompt_cache_key = Key (when set). The prefix is cached automatically.
//   - gemini: nothing yet (explicit cachedContent is a separate resource; deferred).
func applyPromptCache(body map[string]any, style ClientStyle, o *PromptCacheOptions) {
	switch style {
	case StyleAnthropic:
		mark := map[string]any{"type": "ephemeral"}
		if o.LongTTL {
			mark["ttl"] = "1h"
		}
		if tools, ok := body["tools"].([]any); ok && len(tools) > 0 {
			out := append([]any(nil), tools...)
			out[len(out)-1] = withCacheControl(out[len(out)-1], mark)
			body["tools"] = out
		}
		switch sys := body["system"].(type) {
		case string:
			if sys != "" {
				body["system"] = []any{map[string]any{"type": "text", "text": sys, "cache_control": mark}}
			}
		case []any:
			if len(sys) > 0 {
				out := append([]any(nil), sys...)
				out[len(out)-1] = withCacheControl(out[len(out)-1], mark)
				body["system"] = out
			}
		}
		if msgs, ok := body["messages"].([]any); ok && len(msgs) > 0 {
			out := append([]any(nil), msgs...)
			if last, ok := out[len(out)-1].(map[string]any); ok {
				m := copyMap(last)
				switch content := m["content"].(type) {
				case string:
					m["content"] = []any{map[string]any{"type": "text", "text": content, "cache_control": mark}}
				case []any:
					if len(content) > 0 {
						blocks := append([]any(nil), content...)
						blocks[len(blocks)-1] = withCacheControl(blocks[len(blocks)-1], mark)
						m["content"] = blocks
					}
				}
				out[len(out)-1] = m
			}
			body["messages"] = out
		}
	case StyleOpenAI:
		if o.Key != "" {
			body["prompt_cache_key"] = o.Key
		}
	}
}

func withCacheControl(v any, mark map[string]any) any {
	m, ok := v.(map[string]any)
	if !ok {
		return v
	}
	c := copyMap(m)
	c["cache_control"] = mark
	return c
}
