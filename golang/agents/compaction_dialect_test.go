package agents

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Under the anthropic dialect tool results are appended as a `user` message
// carrying tool_result blocks, so a "clean user boundary" can be the tool-result
// carrier itself — orphaning it from the assistant `tool_use` that gets
// summarized away.
func TestAnthropicToolResultNotUsedAsTailBoundary(t *testing.T) {
	pad := func(n int) string { return strings.Repeat("x", n) }

	msgs := []any{
		map[string]any{"role": "system", "content": "soul"},
		map[string]any{"role": "user", "content": "q1 " + pad(400)},
		map[string]any{"role": "assistant", "content": "a1 " + pad(400)},
		// a genuine user turn — the boundary the tail SHOULD extend back to
		map[string]any{"role": "user", "content": "q2"},
		map[string]any{"role": "assistant", "content": []any{
			map[string]any{"type": "tool_use", "id": "tu_1", "name": "echo", "input": map[string]any{"v": 1}},
		}},
		// anthropic carries the RESULT in a user message — NOT a valid boundary
		map[string]any{"role": "user", "content": []any{
			map[string]any{"type": "tool_result", "tool_use_id": "tu_1", "content": "echoed"},
		}},
		map[string]any{"role": "assistant", "content": "done"},
	}

	hook := Compactor(CompactorOptions{
		MaxTokens: 50,
		KeepTail:  20,
		Summarize: func(older []any) (string, error) { return "SUMMARY", nil },
	})

	ov, err := hook(context.Background(), tn.BeforeLLMEvent{Messages: msgs})
	if err != nil {
		t.Fatalf("hook error: %v", err)
	}
	if ov == nil || ov.Messages == nil {
		t.Fatal("expected compaction to fire")
	}

	b, _ := json.Marshal(ov.Messages)
	out := string(b)
	t.Logf("compacted: %s", out)

	// "tool_use_id" contains "tool_use" as a substring — match on the type field.
	hasResult := strings.Contains(out, `"type":"tool_result"`)
	hasUse := strings.Contains(out, `"type":"tool_use"`)
	if hasResult && !hasUse {
		t.Fatal("ORPHANED: tail carries tool_result with no matching tool_use — invalid for the Anthropic API")
	}
	if len(ov.Messages) < 3 {
		t.Fatalf("tail was dropped entirely: %s", out)
	}
	if !strings.Contains(out, `"content":"done"`) {
		t.Fatal("most recent turn was lost")
	}
}

// The OpenAI dialect must be completely unaffected: every user message stays a
// valid boundary.
func TestOpenAIDialectBoundariesUnchanged(t *testing.T) {
	pad := func(n int) string { return strings.Repeat("x", n) }
	msgs := []any{
		map[string]any{"role": "system", "content": "soul"},
		map[string]any{"role": "user", "content": "q1 " + pad(400)},
		map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
			map[string]any{"id": "tc_1", "type": "function"},
		}},
		map[string]any{"role": "tool", "tool_call_id": "tc_1", "content": "echoed"},
		map[string]any{"role": "user", "content": "q2"},
		map[string]any{"role": "assistant", "content": "done"},
	}
	hook := Compactor(CompactorOptions{
		MaxTokens: 50, KeepTail: 20,
		Summarize: func(older []any) (string, error) { return "SUMMARY", nil },
	})
	ov, err := hook(context.Background(), tn.BeforeLLMEvent{Messages: msgs})
	if err != nil {
		t.Fatalf("hook error: %v", err)
	}
	if ov == nil {
		t.Fatal("expected compaction to fire")
	}
	b, _ := json.Marshal(ov.Messages)
	// the tail must start at the plain user turn q2
	if !strings.Contains(string(b), `"content":"q2"`) {
		t.Fatalf("openai boundary moved: %s", string(b))
	}
}

// No valid boundary anywhere ⇒ the whole body is summarized with an EMPTY tail.
// That cannot orphan anything (nothing is retained) and is how a long agentic run
// stays bounded. Asserted here so the property is deliberate, not incidental.
func TestNoSafeBoundarySummarizesWithEmptyTail(t *testing.T) {
	pad := func(n int) string { return strings.Repeat("x", n) }
	msgs := []any{
		map[string]any{"role": "system", "content": "soul"},
		map[string]any{"role": "assistant", "content": "a " + pad(400)},
		map[string]any{"role": "user", "content": []any{
			map[string]any{"type": "tool_result", "tool_use_id": "tu_1", "content": "echoed"},
		}},
		map[string]any{"role": "assistant", "content": "done"},
	}
	hook := Compactor(CompactorOptions{
		MaxTokens: 10, KeepTail: 5,
		Summarize: func(older []any) (string, error) { return "SUMMARY", nil },
	})
	ov, err := hook(context.Background(), tn.BeforeLLMEvent{Messages: msgs})
	if err != nil {
		t.Fatalf("hook error: %v", err)
	}
	if ov == nil {
		t.Fatal("expected compaction to fire")
	}
	b, _ := json.Marshal(ov.Messages)
	out := string(b)
	if strings.Contains(out, `"type":"tool_result"`) {
		t.Fatalf("retained an orphaned tool_result: %s", out)
	}
	if !strings.Contains(out, "SUMMARY") {
		t.Fatalf("expected the body summarized: %s", out)
	}
}
