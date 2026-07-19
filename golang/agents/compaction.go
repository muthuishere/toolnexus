package agents

// Context compaction (SPEC §7F) — a pure messages→messages helper wired through
// the shipped §8 beforeLLM seam. NO core loop change: the loop already lets a
// BeforeLLM hook REPLACE the working transcript (it sets `messages = ov.Messages`),
// and that array flows into RunResult.Messages and the ConversationStore. So a
// long-lived agent stays under budget with one opt-in hook.
//
// Below MaxTokens it is a no-op (returns nil override → byte-identical to a run
// with no compactor). Above it, it summarizes the older head and keeps a recent
// tail that ALWAYS begins at a user turn (tool-pair safety — a `tool` message is
// never orphaned from the `assistant` carrying its tool_call_id). A leading system
// prompt (identity / soul / skills) is preserved verbatim.

import (
	"bytes"
	"context"
	"encoding/json"

	tn "github.com/muthuishere/toolnexus/golang"
)

// EstimateTokens is the default, deterministic token estimate: for each message,
// ceil(len(JSON-serialized message) / 4), summed over messages. It is an estimator,
// not a tokenizer — exactness is the host's call (override via CompactorOptions.CountTokens).
//
// Serialization parity with the JS port: JS uses JSON.stringify(m); this encoder
// disables Go's default HTML escaping (<, >, & → <…) so the byte length matches
// JS for the same message. (Go sorts map keys and JS preserves insertion order, but
// key order does not change the total length, so the estimate is identical.)
func EstimateTokens(messages []any) int {
	n := 0
	for _, m := range messages {
		n += ceilDiv4(marshalLen(m))
	}
	return n
}

func marshalLen(m any) int {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(m); err != nil {
		return 0
	}
	// Encoder appends a trailing newline; JSON.stringify does not.
	return buf.Len() - 1
}

func ceilDiv4(n int) int {
	return (n + 3) / 4
}

// CompactorOptions configures Compactor.
type CompactorOptions struct {
	// MaxTokens: compact only when the estimate exceeds this many tokens; at or
	// below ⇒ no-op, byte-identical to no compactor.
	MaxTokens int
	// KeepTail: keep at least this many tokens of the most recent tail.
	// Default MaxTokens/2.
	KeepTail int
	// Summarize produces a summary of the older messages. It MAY call an LLM —
	// the library makes no model call on the host's behalf by default. Required.
	Summarize func(older []any) (string, error)
	// CountTokens is the token estimator; default EstimateTokens (ceil(chars/4)).
	CountTokens func(messages []any) int
	// FlushToMemory, when set, injects a pre-compact system reminder to persist
	// durable facts via the §7E memory tool before the head is summarized. Off by default.
	FlushToMemory bool
}

// Compactor builds a BeforeLLM hook that compacts the transcript when it grows
// past MaxTokens. It returns a nil override (no-op) while under budget. The
// compacted transcript is [leading system prompt (verbatim), summary system
// message, (flush reminder?), …tail].
func Compactor(opts CompactorOptions) func(context.Context, tn.BeforeLLMEvent) (*tn.LLMOverride, error) {
	count := opts.CountTokens
	if count == nil {
		count = EstimateTokens
	}
	keepTail := opts.KeepTail
	if keepTail == 0 {
		keepTail = opts.MaxTokens / 2
	}

	return func(_ context.Context, ev tn.BeforeLLMEvent) (*tn.LLMOverride, error) {
		msgs := ev.Messages
		if count(msgs) <= opts.MaxTokens {
			return nil, nil // under budget → byte-identical no-op
		}

		// Preserve a leading system prompt verbatim (identity/soul/skills — never summarized).
		head0 := 0
		if len(msgs) > 0 && roleOf(msgs[0]) == "system" {
			head0 = 1
		}
		system := msgs[:head0]

		// Find the split: the LARGEST tail (from a clean USER boundary) that fits
		// keepTail. Scanning to a user turn guarantees tool-pair safety — a tail
		// starting at a user message can never orphan a `tool` result from its
		// tool_calls.
		split := len(msgs)
		for i := len(msgs) - 1; i > head0; i-- {
			if roleOf(msgs[i]) == "user" && count(msgs[i:]) <= keepTail {
				split = i
			}
			if count(msgs[i:]) > keepTail {
				break
			}
		}
		// If no clean user boundary fit in range, fall back to the most recent user
		// turn so we still never split a tool group (may keep more than keepTail —
		// safety over size).
		if split == len(msgs) {
			for i := len(msgs) - 1; i > head0; i-- {
				if roleOf(msgs[i]) == "user" {
					split = i
					break
				}
			}
		}
		if split <= head0 {
			return nil, nil // nothing safely compactible
		}

		older := msgs[head0:split]
		tail := msgs[split:]
		if len(older) == 0 {
			return nil, nil
		}

		summary, err := opts.Summarize(older)
		if err != nil {
			return nil, err
		}
		summaryMsg := map[string]any{
			"role":    "system",
			"content": "[Summary of earlier conversation]\n" + summary,
		}

		out := make([]any, 0, len(system)+2+len(tail))
		out = append(out, system...)
		out = append(out, summaryMsg)
		if opts.FlushToMemory {
			out = append(out, map[string]any{
				"role":    "system",
				"content": "Before continuing: if anything from earlier is worth keeping, save it with the memory tool now — the earlier transcript is about to be summarized.",
			})
		}
		out = append(out, tail...)
		return &tn.LLMOverride{Messages: out}, nil
	}
}

// roleOf extracts the "role" field of a message, tolerating the map shapes the
// loop uses (map[string]any) as well as map[string]string.
func roleOf(m any) string {
	switch v := m.(type) {
	case map[string]any:
		if r, ok := v["role"].(string); ok {
			return r
		}
	case map[string]string:
		return v["role"]
	}
	return ""
}
