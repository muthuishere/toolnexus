package toolnexus

// Prompt-cache replay (change add-prompt-cache, phase 1). A Karya-shaped
// session — fixed core tools, a ~6.6k-token skills catalog in the system
// prompt, several turns, a deferred tool schema loaded mid-session as a tool
// result — runs against a mock anthropic server that SIMULATES the provider's
// prefix cache: a request reads the longest prefix that ends at a breakpoint an
// earlier request wrote. The engine passes if cache reads start on turn 2 and
// never drop to zero afterwards, including after the deferred load.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func stripCacheControl(v any) any {
	switch t := v.(type) {
	case map[string]any:
		out := map[string]any{}
		for k, x := range t {
			if k != "cache_control" {
				out[k] = stripCacheControl(x)
			}
		}
		return out
	case []any:
		out := make([]any, len(t))
		for i, x := range t {
			out[i] = stripCacheControl(x)
		}
		return out
	case string:
		// a cached string system/content becomes a one-block array; normalize
		return t
	}
	return v
}

func hasCacheControl(v any) bool {
	b, _ := json.Marshal(v)
	return strings.Contains(string(b), `"cache_control"`)
}

type cacheSim struct {
	mu      sync.Mutex
	written map[string]bool
	reads   []int
	bodies  []map[string]any
	turn    int
}

// segments: tools, system, then each message — canonical JSON without cache_control.
func segs(body map[string]any) (out []string, marks []bool) {
	add := func(v any) {
		b, _ := json.Marshal(stripCacheControl(v))
		out = append(out, string(b))
		marks = append(marks, hasCacheControl(v))
	}
	add(body["tools"])
	add(body["system"])
	for _, m := range body["messages"].([]any) {
		add(m)
	}
	return
}

func (s *cacheSim) handle(w http.ResponseWriter, r *http.Request) {
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	s.mu.Lock()
	sg, marks := segs(body)
	read := 0
	for i := len(sg); i > 0; i-- {
		if s.written[strings.Join(sg[:i], "\x00")] {
			read = len(strings.Join(sg[:i], "")) / 4
			break
		}
	}
	write := 0
	for i := range sg {
		if marks[i] {
			key := strings.Join(sg[:i+1], "\x00")
			if !s.written[key] {
				s.written[key] = true
				write = len(strings.Join(sg[:i+1], ""))/4 - read
			}
		}
	}
	s.reads = append(s.reads, read)
	s.bodies = append(s.bodies, body)
	msgs := body["messages"].([]any)
	last := msgs[len(msgs)-1].(map[string]any)
	s.mu.Unlock()

	// script: a user turn first calls a tool; after a tool result, answer.
	isToolResult := false
	if blocks, ok := last["content"].([]any); ok {
		for _, b := range blocks {
			if bm, _ := b.(map[string]any); bm["type"] == "tool_result" {
				isToolResult = true
			}
		}
	}
	var content []any
	if isToolResult {
		content = []any{map[string]any{"type": "text", "text": "ok"}}
	} else {
		s.mu.Lock()
		s.turn++
		n := s.turn
		s.mu.Unlock()
		name := "read_notes"
		if n == 2 {
			name = "tool_search" // turn 2 loads a deferred schema
		}
		content = []any{map[string]any{"type": "tool_use", "id": fmt.Sprintf("t%d", n), "name": name, "input": map[string]any{}}}
	}
	w.Header().Set("content-type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"content":     content,
		"stop_reason": map[bool]string{true: "end_turn", false: "tool_use"}[isToolResult],
		"usage": map[string]any{"input_tokens": 10, "output_tokens": 5,
			"cache_read_input_tokens": read, "cache_creation_input_tokens": write},
	})
}

func karyaToolkit(t *testing.T) *Toolkit {
	t.Helper()
	notes := NativeTool("read_notes", "Read the notes.", nil, func(context.Context, map[string]any) (string, error) { return "notes", nil })
	search := NativeTool("tool_search", "Load a deferred tool's schema.", nil, func(context.Context, map[string]any) (string, error) {
		return `{"name":"deploy","description":"Deploy.","inputSchema":{"type":"object"}}`, nil
	})
	tk, err := CreateToolkit(context.Background(), Options{Builtins: true, ExtraTools: []Tool{notes, search}})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func runCacheSession(t *testing.T, cache *PromptCacheOptions) *cacheSim {
	sim := &cacheSim{written: map[string]bool{}}
	srv := httptest.NewServer(http.HandlerFunc(sim.handle))
	defer srv.Close()
	catalog := strings.Repeat("skill: does a thing in a stable way. ", 700) // ~6.6k tokens
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleAnthropic, Model: "m", APIKey: "k",
		SystemPrompt: "You are Karya.\n\n" + catalog, PromptCache: cache, Store: NewInMemoryConversationStore()})
	tk := karyaToolkit(t)
	var total Usage
	for i, p := range []string{"first", "second", "third", "fourth"} {
		res, err := c.Ask(context.Background(), p, tk, "session-1")
		if err != nil {
			t.Fatalf("turn %d: %v", i+1, err)
		}
		total.CacheReadTokens += res.Usage.CacheReadTokens
		total.CacheWriteTokens += res.Usage.CacheWriteTokens
	}
	if total.CacheReadTokens == 0 && cache != nil {
		t.Fatal("usage never reported cache-read tokens")
	}
	return sim
}

func TestPromptCacheReplayNoRegression(t *testing.T) {
	sim := runCacheSession(t, &PromptCacheOptions{Key: "session-1"})
	if len(sim.reads) != 8 {
		t.Fatalf("requests = %d, want 8 (4 turns x 2)", len(sim.reads))
	}
	t.Logf("cache reads per request: %v", sim.reads)
	if sim.reads[0] != 0 {
		t.Fatalf("first request cannot read the cache: %v", sim.reads)
	}
	// from the 2nd request on (inside turn 1, and every later turn, including
	// after the deferred schema loaded in turn 2) the prefix is served from cache
	// and the read never shrinks below the stable tools+system prefix.
	floor := sim.reads[1]
	if floor < 6000 {
		t.Fatalf("2nd request read %d tokens, want the ~6.6k stable prefix", floor)
	}
	for i := 1; i < len(sim.reads); i++ {
		if sim.reads[i] < floor {
			t.Fatalf("cache read regressed at request %d: %v", i+1, sim.reads)
		}
		if i > 0 && sim.reads[i] < sim.reads[i-1] {
			t.Fatalf("cache read shrank at request %d: %v", i+1, sim.reads)
		}
	}
	// tools and system bytes never change across the session
	t0, _ := json.Marshal(sim.bodies[0]["tools"])
	s0, _ := json.Marshal(sim.bodies[0]["system"])
	for i, b := range sim.bodies {
		ti, _ := json.Marshal(b["tools"])
		si, _ := json.Marshal(b["system"])
		if string(ti) != string(t0) || string(si) != string(s0) {
			t.Fatalf("request %d changed the tools or system prefix", i+1)
		}
	}
}

func TestPromptCacheOffIsByteIdentical(t *testing.T) {
	sim := runCacheSession(t, nil)
	for i, b := range sim.bodies {
		if hasCacheControl(b) {
			t.Fatalf("request %d has cache_control without PromptCache", i+1)
		}
		if _, ok := b["system"].(string); !ok {
			t.Fatalf("request %d: system must stay a string", i+1)
		}
	}
}

func TestPromptCacheOpenAIKeyAndUsage(t *testing.T) {
	body := map[string]any{"messages": []any{}}
	applyPromptCache(body, StyleOpenAI, &PromptCacheOptions{Key: "s1"})
	if body["prompt_cache_key"] != "s1" {
		t.Fatalf("body = %v", body)
	}
	var u Usage
	addUsage(&u, map[string]any{"prompt_tokens": 100.0, "completion_tokens": 5.0, "prompt_tokens_details": map[string]any{"cached_tokens": 80.0}}, "openai")
	if u.CacheReadTokens != 80 || u.PromptTokens != 100 {
		t.Fatalf("usage = %+v", u)
	}
}

func TestPromptCacheDoesNotMutateTranscript(t *testing.T) {
	msg := map[string]any{"role": "user", "content": "hi"}
	tool := map[string]any{"name": "x"}
	body := map[string]any{"tools": []any{tool}, "system": "s", "messages": []any{msg}}
	applyPromptCache(body, StyleAnthropic, &PromptCacheOptions{LongTTL: true})
	if _, ok := msg["content"].(string); !ok || tool["cache_control"] != nil {
		t.Fatal("caller's maps were mutated")
	}
	b, _ := json.Marshal(body)
	if strings.Count(string(b), `"ttl":"1h"`) != 3 {
		t.Fatalf("want 3 breakpoints with 1h ttl: %s", b)
	}
}

// The last tool carries the anthropic breakpoint in the request bytes. ToAnthropic
// returns structs, which withCacheControl skipped silently in 0.22.0.
func TestPromptCacheMarksLastToolInRequestJSON(t *testing.T) {
	var raw []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ = io.ReadAll(r.Body)
		w.Header().Set("content-type", "application/json")
		_, _ = w.Write([]byte(`{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}`))
	}))
	defer srv.Close()
	a := NativeTool("a", "A.", nil, func(context.Context, map[string]any) (string, error) { return "", nil })
	b := NativeTool("b", "B.", nil, func(context.Context, map[string]any) (string, error) { return "", nil })
	tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{a, b}})
	if err != nil {
		t.Fatal(err)
	}
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleAnthropic, Model: "m", APIKey: "k", PromptCache: &PromptCacheOptions{}})
	if _, err := c.Ask(context.Background(), "hi", tk, ""); err != nil {
		t.Fatal(err)
	}
	var body struct {
		Tools []map[string]any `json:"tools"`
	}
	if err := json.Unmarshal(raw, &body); err != nil || len(body.Tools) < 2 {
		t.Fatalf("tools = %s (%v)", raw, err)
	}
	last := len(body.Tools) - 1
	for i, tool := range body.Tools[:last] {
		if _, ok := tool["cache_control"]; ok {
			t.Fatalf("only the last tool is a breakpoint, tool %d has one: %v", i, tool)
		}
	}
	got, _ := json.Marshal(body.Tools[last])
	want := `{"cache_control":{"type":"ephemeral"},"description":"B.","input_schema":{"additionalProperties":false,"properties":{},"type":"object"},"name":"b"}`
	if string(got) != want {
		t.Fatalf("last tool =\n %s\nwant\n %s", got, want)
	}
}
