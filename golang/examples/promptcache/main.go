// Prompt cache + tool tiers, hermetic: a local mock Anthropic server stands in
// for the provider and simulates its prompt cache, so this runs with no key and
// no network.
//
//	go run ./examples/promptcache
//
// What it shows:
//   - PromptCache puts cache_control breakpoints on the stable prefix;
//   - ToolTiers sends only the core schemas plus tool_search / call_tool, so the
//     tools array — and therefore the cached prefix — is identical on every request;
//   - the model loads a listed tool with tool_search and calls it via call_tool,
//     and the BeforeTool hook sees the REAL tool name;
//   - Usage reports CacheReadTokens / CacheWriteTokens, and under anthropic the
//     real context size is PromptTokens + CacheReadTokens + CacheWriteTokens.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"

	"github.com/muthuishere/toolnexus/golang"
)

func main() {
	ctx := context.Background()

	noArgs := toolnexus.JSONSchema{"type": "object", "properties": map[string]any{}}
	city := toolnexus.JSONSchema{"type": "object", "properties": map[string]any{"city": map[string]any{"type": "string"}}}
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{ExtraTools: []toolnexus.Tool{
		toolnexus.NativeTool("clock", "Current time", noArgs,
			func(context.Context, map[string]any) (string, error) { return "12:00", nil }),
		toolnexus.NativeTool("weather", "Current weather for a city", city,
			func(_ context.Context, a map[string]any) (string, error) { return fmt.Sprint("sunny in ", a["city"]), nil }),
		toolnexus.NativeTool("stocks", "Latest stock quote", noArgs,
			func(context.Context, map[string]any) (string, error) { return "ACME 42", nil }),
	}})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	srv := newMockAnthropic()
	defer srv.Close()

	var gated []string
	client := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: srv.URL, Style: toolnexus.StyleAnthropic, Model: "mock-claude",
		APIKey:       "YOUR_KEY_HERE", // a local host needs APIKey or APIKeyEnv (0.22.0)
		SystemPrompt: "You are a concise assistant.",
		PromptCache:  &toolnexus.PromptCacheOptions{},
		ToolTiers:    &toolnexus.ToolTierOptions{Core: []string{"clock"}, Deferred: []string{"stocks"}},
		Hooks: &toolnexus.Hooks{BeforeTool: func(_ context.Context, ev toolnexus.BeforeToolEvent) (*toolnexus.ToolOverride, error) {
			gated = append(gated, ev.Name)
			return nil, nil
		}},
	})

	fmt.Println("tier plan:", client.ToolTierPlan(tk))

	res, err := client.Ask(ctx, "What is the weather in Paris?", tk, "session-1")
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("answer:   ", res.Text)
	fmt.Println("gated:    ", gated, "(the hook saw weather, not call_tool)")

	res2, err := client.Ask(ctx, "And now?", tk, "session-1")
	if err != nil {
		log.Fatal(err)
	}
	u := res2.Usage
	fmt.Printf("turn 2:    prompt=%d cacheRead=%d cacheWrite=%d -> context=%d tokens\n",
		u.PromptTokens, u.CacheReadTokens, u.CacheWriteTokens, u.PromptTokens+u.CacheReadTokens+u.CacheWriteTokens)

	if !srv.toolsStable() {
		log.Fatal("the tools array changed between requests — the cached prefix would break")
	}
	if len(gated) != 1 || gated[0] != "weather" || u.CacheReadTokens == 0 {
		log.Fatalf("unexpected result: gated=%v usage=%+v", gated, u)
	}
	fmt.Println("ok: tools array identical on all", srv.requests(), "requests; cache hits from request 2")
}

// mockAnthropic answers like the Messages API and simulates a prefix cache: the
// first request writes the tools+system prefix, every later one reads it.
type mockAnthropic struct {
	*httptest.Server
	mu    sync.Mutex
	tools []string
	n     int
}

func newMockAnthropic() *mockAnthropic {
	m := &mockAnthropic{}
	m.Server = httptest.NewServer(http.HandlerFunc(m.handle))
	return m
}

func (m *mockAnthropic) handle(w http.ResponseWriter, r *http.Request) {
	raw, _ := io.ReadAll(r.Body)
	var body map[string]any
	_ = json.Unmarshal(raw, &body)
	toolsJSON, _ := json.Marshal(body["tools"])
	prefix := len(toolsJSON) / 4

	m.mu.Lock()
	m.n++
	n := m.n
	m.tools = append(m.tools, string(toolsJSON))
	m.mu.Unlock()

	usage := map[string]any{"input_tokens": 20, "output_tokens": 5}
	if n == 1 {
		usage["cache_creation_input_tokens"] = prefix
	} else {
		usage["cache_read_input_tokens"] = prefix
	}

	var content []any
	switch {
	case n == 1: // load the listed weather tool
		content = []any{map[string]any{"type": "tool_use", "id": "t1", "name": "tool_search", "input": map[string]any{"query": "select:weather"}}}
	case n == 2: // call it through call_tool
		content = []any{map[string]any{"type": "tool_use", "id": "t2", "name": "call_tool",
			"input": map[string]any{"name": "weather", "arguments": map[string]any{"city": "Paris"}}}}
	case n == 3:
		content = []any{map[string]any{"type": "text", "text": "It is sunny in Paris."}}
	default:
		content = []any{map[string]any{"type": "text", "text": "Still sunny."}}
	}
	stop := "end_turn"
	if n <= 2 {
		stop = "tool_use"
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"content": content, "stop_reason": stop, "usage": usage})
}

func (m *mockAnthropic) toolsStable() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, t := range m.tools {
		if t != m.tools[0] || !strings.Contains(t, "tool_search") {
			return false
		}
	}
	return len(m.tools) > 1
}

func (m *mockAnthropic) requests() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.n
}
