// Context compaction (SPEC §7F) acceptance — C1–C6 (11 checks), the Go port of
// js/spike/compaction-demo.ts, run against the shared examples/compaction/fixture.json
// generator (systemPrompt "You are Ava. SOUL." + userTurns groups of
// user / assistant+tool_calls / tool / assistant). Hermetic: the C6 end-to-end run
// uses an in-process mock LLM (zero network, zero cost).
package agents

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// transcript builds the fixture transcript deterministically (mirrors the JS
// transcript() and examples/compaction/fixture.json generator).
func transcript(userTurns int) []any {
	pad := strings.Repeat("pad ", 40)
	data := strings.Repeat("data ", 40)
	m := []any{map[string]any{"role": "system", "content": "You are Ava. SOUL."}}
	for i := 0; i < userTurns; i++ {
		id := "c" + itoa(i)
		m = append(m,
			map[string]any{"role": "user", "content": "question " + itoa(i) + " " + pad},
			map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
				map[string]any{"id": id, "type": "function", "function": map[string]any{"name": "lookup", "arguments": "{}"}},
			}},
			map[string]any{"role": "tool", "tool_call_id": id, "content": "result " + itoa(i) + " " + data},
			map[string]any{"role": "assistant", "content": "answer " + itoa(i)},
		)
	}
	return m
}

func itoa(i int) string {
	b, _ := json.Marshal(i)
	return string(b)
}

// deterministic summarizer: "summarized <N> messages" (N = count of older messages).
func summarize(older []any) (string, error) {
	return "summarized " + itoa(len(older)) + " messages", nil
}

func TestCompaction(t *testing.T) {
	ctx := context.Background()
	pass, fail := 0, 0
	check := func(name string, cond bool, detail ...string) {
		if cond {
			pass++
		} else {
			fail++
			d := ""
			if len(detail) > 0 {
				d = detail[0]
			}
			t.Errorf("FAIL %s %s", name, d)
		}
	}

	// C1 — no-op below budget (byte-identical) --------------------------------
	{
		hook := Compactor(CompactorOptions{MaxTokens: 100_000, Summarize: summarize})
		out, err := hook(ctx, tn.BeforeLLMEvent{Messages: transcript(3)})
		if err != nil {
			t.Fatal(err)
		}
		check("C1 returns nil override when under budget", out == nil)
	}

	// C2 — compacts above budget: summarize head, keep tail -------------------
	{
		msgs := transcript(30)
		before := EstimateTokens(msgs)
		hook := Compactor(CompactorOptions{MaxTokens: 2000, KeepTail: 800, Summarize: summarize})
		out, err := hook(ctx, tn.BeforeLLMEvent{Messages: msgs})
		if err != nil {
			t.Fatal(err)
		}
		check("C2 compacts when over budget", out != nil)
		check("C2 result is smaller than the original", out != nil && EstimateTokens(out.Messages) < before)
		hasSummary := false
		if out != nil {
			for _, m := range out.Messages {
				if strings.HasPrefix(contentStr(m), "[Summary of earlier conversation]") {
					hasSummary = true
				}
			}
		}
		check("C2 a summary system message is inserted", hasSummary)
	}

	// C3 — tool-pair safety: no orphaned tool result --------------------------
	{
		hook := Compactor(CompactorOptions{MaxTokens: 2000, KeepTail: 800, Summarize: summarize})
		out, err := hook(ctx, tn.BeforeLLMEvent{Messages: transcript(30)})
		if err != nil || out == nil {
			t.Fatalf("C3 expected compaction, err=%v out=%v", err, out)
		}
		tl := out.Messages
		safe := true
		for i, m := range tl {
			if roleOf(m) != "tool" {
				continue
			}
			id := stringField(m, "tool_call_id")
			parent := false
			for _, prev := range tl[:i] {
				if roleOf(prev) == "assistant" && toolCallHasID(prev, id) {
					parent = true
				}
			}
			if !parent {
				safe = false
			}
		}
		check("C3 no tool message is orphaned from its tool_calls", safe)
		firstNonSystem := ""
		for _, m := range tl {
			if roleOf(m) != "system" {
				firstNonSystem = roleOf(m)
				break
			}
		}
		check("C3 the tail begins at a clean user turn", firstNonSystem == "user")
	}

	// C4 — leading system prompt preserved verbatim ---------------------------
	{
		hook := Compactor(CompactorOptions{MaxTokens: 2000, Summarize: summarize})
		out, err := hook(ctx, tn.BeforeLLMEvent{Messages: transcript(30)})
		if err != nil || out == nil {
			t.Fatalf("C4 expected compaction, err=%v out=%v", err, out)
		}
		first := out.Messages[0]
		check("C4 original SOUL system prompt is still first",
			roleOf(first) == "system" && strings.Contains(contentStr(first), "SOUL"))
	}

	// C5 — flushToMemory injects a pre-compact reminder -----------------------
	{
		hook := Compactor(CompactorOptions{MaxTokens: 2000, Summarize: summarize, FlushToMemory: true})
		out, err := hook(ctx, tn.BeforeLLMEvent{Messages: transcript(30)})
		if err != nil || out == nil {
			t.Fatalf("C5 expected compaction, err=%v out=%v", err, out)
		}
		found := false
		for _, m := range out.Messages {
			if strings.Contains(contentStr(m), "save it with the memory tool") {
				found = true
			}
		}
		check("C5 a 'save with the memory tool' reminder is present", found)
	}

	// C6 — end-to-end through the SHIPPED client loop via beforeLLM -----------
	{
		mock := &compactionMock{}
		pad := tn.Tool{
			Name:        "pad",
			Description: "pads",
			InputSchema: tn.JSONSchema{"type": "object", "properties": map[string]any{}},
			Source:      tn.SourceCustom,
			Execute: func(_ map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
				return tn.ToolResult{Output: strings.Repeat("x ", 600)}, nil
			},
		}
		toolkit, err := tn.CreateToolkit(nil, tn.Options{Builtins: false, ExtraTools: []tn.Tool{pad}})
		if err != nil {
			t.Fatal(err)
		}
		client := tn.CreateClient(tn.ClientOptions{
			BaseURL:      "http://mock.local",
			Style:        tn.StyleOpenAI,
			Model:        "m",
			APIKey:       "spike",
			MaxTurns:     20,
			SystemPrompt: "You are Ava. SOUL.",
			HTTPClient:   &http.Client{Transport: mock},
			Hooks: &tn.Hooks{
				BeforeLLM: Compactor(CompactorOptions{MaxTokens: 600, KeepTail: 250, Summarize: summarize}),
			},
		})
		r, err := client.Run(ctx, "start", toolkit)
		if err != nil {
			t.Fatal(err)
		}
		check("C6 run completes to a final answer despite mid-run compaction",
			r.Status == "done" && strings.HasPrefix(r.Text, "final"), r.Text)
		check("C6 compaction actually fired during the run",
			strings.Contains(r.Text, "compacted=true"), r.Text)
		check("C6 final transcript is bounded, not the full raw history",
			EstimateTokens(r.Messages) < 4000, itoa(EstimateTokens(r.Messages)))
	}

	t.Logf("compaction acceptance: %d passed, %d failed", pass, fail)
}

// compactionMock: makes 6 pad tool calls (growing the transcript), then a final
// answer. It reports whether the request it received was already compacted.
type compactionMock struct {
	mu    sync.Mutex
	calls int
}

func (m *compactionMock) RoundTrip(req *http.Request) (*http.Response, error) {
	b, _ := io.ReadAll(req.Body)
	var body struct {
		Messages []map[string]any `json:"messages"`
	}
	_ = json.Unmarshal(b, &body)
	compacted := false
	for _, msg := range body.Messages {
		if c, _ := msg["content"].(string); strings.HasPrefix(c, "[Summary") {
			compacted = true
		}
	}
	m.mu.Lock()
	done := m.calls >= 6
	var message map[string]any
	if done {
		message = map[string]any{"role": "assistant", "content": "final (compacted=" + boolStr(compacted) + ")"}
	} else {
		id := "c" + itoa(m.calls)
		m.calls++
		message = map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
			map[string]any{"id": id, "type": "function", "function": map[string]any{"name": "pad", "arguments": "{}"}},
		}}
	}
	m.mu.Unlock()

	respBody := map[string]any{
		"choices": []any{map[string]any{"message": message}},
		"usage":   map[string]any{"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60},
	}
	rb, _ := json.Marshal(respBody)
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(rb)),
	}, nil
}

func boolStr(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

func contentStr(m any) string {
	mm, ok := m.(map[string]any)
	if !ok {
		return ""
	}
	s, _ := mm["content"].(string)
	return s
}

func stringField(m any, key string) string {
	mm, ok := m.(map[string]any)
	if !ok {
		return ""
	}
	s, _ := mm[key].(string)
	return s
}

func toolCallHasID(m any, id string) bool {
	mm, ok := m.(map[string]any)
	if !ok {
		return false
	}
	calls, ok := mm["tool_calls"].([]any)
	if !ok {
		return false
	}
	for _, c := range calls {
		cm, ok := c.(map[string]any)
		if ok {
			if cid, _ := cm["id"].(string); cid == id {
				return true
			}
		}
	}
	return false
}

func TestCompactorOnCompact(t *testing.T) {
	var got []CompactInfo
	hook := Compactor(CompactorOptions{MaxTokens: 50, KeepTail: 20, Summarize: summarize,
		OnCompact: func(_ context.Context, info CompactInfo) { got = append(got, info) }})
	msgs := transcript(10)
	out, err := hook(context.Background(), tn.BeforeLLMEvent{Messages: msgs})
	if err != nil || out == nil {
		t.Fatalf("expected a compaction, out=%v err=%v", out, err)
	}
	if len(got) != 1 {
		t.Fatalf("OnCompact calls = %d, want 1", len(got))
	}
	i := got[0]
	if i.MessagesBefore != len(msgs) || i.MessagesAfter != len(out.Messages) || i.Summarized == 0 ||
		i.TokensBefore != EstimateTokens(msgs) || i.TokensAfter != EstimateTokens(out.Messages) ||
		i.TokensAfter >= i.TokensBefore || i.Summary == "" {
		t.Fatalf("info = %+v", i)
	}
	// under budget ⇒ no callback
	big := Compactor(CompactorOptions{MaxTokens: 1 << 30, Summarize: summarize,
		OnCompact: func(context.Context, CompactInfo) { t.Fatal("OnCompact on a no-op") }})
	if o, _ := big(context.Background(), tn.BeforeLLMEvent{Messages: msgs}); o != nil {
		t.Fatal("expected no-op")
	}
}

// go-agent-cli ask #6 (a): one prompt followed by a long agentic run — the
// prompt is kept verbatim, only the work after it is summarized.
func TestCompactorKeepsSoleUserPrompt(t *testing.T) {
	data := strings.Repeat("data ", 40)
	prompt := map[string]any{"role": "user", "content": "do the task"}
	msgs := []any{map[string]any{"role": "system", "content": "SOUL"}, prompt}
	for i := 0; i < 8; i++ {
		id := "c" + itoa(i)
		msgs = append(msgs,
			map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
				map[string]any{"id": id, "type": "function", "function": map[string]any{"name": "lookup", "arguments": "{}"}}}},
			map[string]any{"role": "tool", "tool_call_id": id, "content": data})
	}
	var info CompactInfo
	hook := Compactor(CompactorOptions{MaxTokens: 100, KeepTail: 20, Summarize: summarize,
		OnCompact: func(_ context.Context, i CompactInfo) { info = i }})
	out, err := hook(context.Background(), tn.BeforeLLMEvent{Messages: msgs})
	if err != nil || out == nil {
		t.Fatalf("out=%v err=%v", out, err)
	}
	if len(out.Messages) != 3 || out.Messages[1].(map[string]any)["content"] != "do the task" {
		t.Fatalf("want [system, prompt, summary], got %v", out.Messages)
	}
	if len(info.Folded) != len(msgs)-2 {
		t.Fatalf("folded = %d, want %d", len(info.Folded), len(msgs)-2)
	}
}

// ask #6 (b): a compaction that would not shrink the transcript is a no-op.
func TestCompactorNoOpWhenNotSmaller(t *testing.T) {
	msgs := transcript(2)
	long := func(older []any) (string, error) { return strings.Repeat("long summary ", 500), nil }
	hook := Compactor(CompactorOptions{MaxTokens: 10, KeepTail: 5, Summarize: long,
		OnCompact: func(context.Context, CompactInfo) { t.Fatal("OnCompact on a no-op") }})
	out, err := hook(context.Background(), tn.BeforeLLMEvent{Messages: msgs})
	if err != nil || out != nil {
		t.Fatalf("want no-op, got out=%v err=%v", out, err)
	}
}

// ask #6 (c): Folded carries the summarized messages verbatim.
func TestCompactorFoldedIsVerbatim(t *testing.T) {
	msgs := transcript(10)
	var info CompactInfo
	hook := Compactor(CompactorOptions{MaxTokens: 50, KeepTail: 20, Summarize: summarize,
		OnCompact: func(_ context.Context, i CompactInfo) { info = i }})
	if out, _ := hook(context.Background(), tn.BeforeLLMEvent{Messages: msgs}); out == nil {
		t.Fatal("expected a compaction")
	}
	if len(info.Folded) != info.Summarized || len(info.Folded) == 0 {
		t.Fatalf("folded=%d summarized=%d", len(info.Folded), info.Summarized)
	}
	for i, m := range info.Folded {
		if fmt.Sprint(m) != fmt.Sprint(msgs[1+i]) {
			t.Fatalf("folded[%d] differs from input[%d]", i, 1+i)
		}
	}
}
