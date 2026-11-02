package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// schedLLM answers turn 1 with one call per name (in that order), turn 2 with
// "done", in all four wire modes (OpenAI/Anthropic × run/stream).
func schedLLM(t *testing.T, names []string) *httptest.Server {
	t.Helper()
	var mu sync.Mutex
	turn := 0
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		turn++
		first := turn == 1
		mu.Unlock()
		anth := strings.Contains(r.URL.Path, "messages")
		stream, _ := body["stream"].(bool)
		sse := func(v any) {
			b, _ := json.Marshal(v)
			fmt.Fprintf(w, "data: %s\n\n", b)
		}
		switch {
		case !anth && !stream:
			msg := map[string]any{"role": "assistant", "content": "done"}
			if first {
				var calls []any
				for i, n := range names {
					calls = append(calls, map[string]any{"id": fmt.Sprintf("c%d", i+1), "type": "function",
						"function": map[string]any{"name": n, "arguments": "{}"}})
				}
				msg = map[string]any{"role": "assistant", "content": nil, "tool_calls": calls}
			}
			w.Header().Set("content-type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": msg}}})
		case anth && !stream:
			resp := map[string]any{"content": []any{map[string]any{"type": "text", "text": "done"}}, "stop_reason": "end_turn"}
			if first {
				var blocks []any
				for i, n := range names {
					blocks = append(blocks, map[string]any{"type": "tool_use", "id": fmt.Sprintf("c%d", i+1), "name": n, "input": map[string]any{}})
				}
				resp = map[string]any{"content": blocks, "stop_reason": "tool_use"}
			}
			w.Header().Set("content-type", "application/json")
			_ = json.NewEncoder(w).Encode(resp)
		case !anth && stream:
			w.Header().Set("content-type", "text/event-stream")
			if first {
				for i, n := range names {
					sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{map[string]any{
						"index": i, "id": fmt.Sprintf("c%d", i+1), "type": "function", "function": map[string]any{"name": n, "arguments": "{}"}}}}}}})
				}
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "tool_calls"}}})
			} else {
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": "done"}}}})
				sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "stop"}}})
			}
			fmt.Fprint(w, "data: [DONE]\n\n")
		default:
			w.Header().Set("content-type", "text/event-stream")
			if first {
				for i, n := range names {
					sse(map[string]any{"type": "content_block_start", "index": i, "content_block": map[string]any{"type": "tool_use", "id": fmt.Sprintf("c%d", i+1), "name": n}})
					sse(map[string]any{"type": "content_block_delta", "index": i, "delta": map[string]any{"type": "input_json_delta", "partial_json": "{}"}})
					sse(map[string]any{"type": "content_block_stop", "index": i})
				}
				sse(map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "tool_use"}})
			} else {
				sse(map[string]any{"type": "content_block_start", "index": 0, "content_block": map[string]any{"type": "text"}})
				sse(map[string]any{"type": "content_block_delta", "index": 0, "delta": map[string]any{"type": "text_delta", "text": "done"}})
				sse(map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "end_turn"}})
			}
			sse(map[string]any{"type": "message_stop"})
		}
	}))
}

// tracker records how many calls overlap and the start/end order.
type tracker struct {
	mu       sync.Mutex
	inflight int
	peak     int
	log      []string
}

func (tr *tracker) tool(name string, readOnly bool) Tool {
	var hints *ToolHints
	if readOnly {
		hints = &ToolHints{ReadOnly: true}
	}
	return Tool{
		Name: name, Description: name, Source: SourceCustom, Hints: hints,
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{}},
		Execute: func(map[string]any, *ToolContext) (ToolResult, error) {
			tr.mu.Lock()
			tr.inflight++
			if tr.inflight > tr.peak {
				tr.peak = tr.inflight
			}
			tr.log = append(tr.log, "start "+name)
			tr.mu.Unlock()
			time.Sleep(60 * time.Millisecond)
			tr.mu.Lock()
			tr.inflight--
			tr.log = append(tr.log, "end "+name)
			tr.mu.Unlock()
			return ToolResult{Output: "out " + name}, nil
		},
	}
}

type schedMode struct {
	name   string
	style  ClientStyle
	stream bool
}

var schedModes = []schedMode{
	{"openai-run", StyleOpenAI, false},
	{"anthropic-run", StyleAnthropic, false},
	{"openai-stream", StyleOpenAI, true},
	{"anthropic-stream", StyleAnthropic, true},
}

// runSched runs one turn of calls (names) against tools in the given mode and
// returns the tool names in RunResult order.
func runSched(t *testing.T, m schedMode, names []string, tools []Tool, max int) []string {
	t.Helper()
	llm := schedLLM(t, names)
	defer llm.Close()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: tools})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	c := CreateClient(ClientOptions{BaseURL: llm.URL, Style: m.style, Model: "m", APIKey: "k", MaxToolConcurrency: max})
	var got []string
	if !m.stream {
		res, err := c.Run(context.Background(), "go", tk)
		if err != nil {
			t.Fatal(err)
		}
		for _, tc := range res.ToolCalls {
			got = append(got, tc.Name)
		}
		return got
	}
	ch, err := c.Stream(context.Background(), "go", tk)
	if err != nil {
		t.Fatal(err)
	}
	for ev := range ch {
		if ev.Type == "error" {
			t.Fatalf("stream error: %v", ev.Err)
		}
		if ev.Type == "done" && ev.Result != nil {
			for _, tc := range ev.Result.ToolCalls {
				got = append(got, tc.Name)
			}
		}
	}
	return got
}

func TestToolSchedulingUnsafeCallsSerialize(t *testing.T) {
	for _, m := range schedModes {
		t.Run(m.name, func(t *testing.T) {
			tr := &tracker{}
			names := []string{"w1", "w2"}
			got := runSched(t, m, names, []Tool{tr.tool("w1", false), tr.tool("w2", false)}, 0)
			if tr.peak != 1 {
				t.Fatalf("two unsafe calls must not overlap, peak %d", tr.peak)
			}
			if strings.Join(tr.log, ",") != "start w1,end w1,start w2,end w2" {
				t.Fatalf("unsafe calls must run in emitted order: %v", tr.log)
			}
			if strings.Join(got, ",") != "w1,w2" {
				t.Fatalf("results must keep emitted order: %v", got)
			}
		})
	}
}

func TestToolSchedulingSafeCallsOverlap(t *testing.T) {
	for _, m := range schedModes {
		t.Run(m.name, func(t *testing.T) {
			tr := &tracker{}
			var names []string
			var tools []Tool
			for i := 1; i <= 6; i++ {
				n := fmt.Sprintf("r%d", i)
				names = append(names, n)
				tools = append(tools, tr.tool(n, true))
			}
			got := runSched(t, m, names, tools, 0)
			if tr.peak != 6 {
				t.Fatalf("6 read-only calls under the default cap must all overlap, peak %d", tr.peak)
			}
			if strings.Join(got, ",") != strings.Join(names, ",") {
				t.Fatalf("results must keep emitted order: %v", got)
			}
		})
	}
}

func TestToolSchedulingCapIsHonoured(t *testing.T) {
	for _, m := range schedModes {
		t.Run(m.name, func(t *testing.T) {
			tr := &tracker{}
			var names []string
			var tools []Tool
			for i := 1; i <= 6; i++ {
				n := fmt.Sprintf("r%d", i)
				names = append(names, n)
				tools = append(tools, tr.tool(n, true))
			}
			runSched(t, m, names, tools, 2)
			if tr.peak != 2 {
				t.Fatalf("MaxToolConcurrency 2 must cap the overlap at exactly 2, peak %d", tr.peak)
			}
		})
	}
}

func TestToolSchedulingMixedRunsKeepOrder(t *testing.T) {
	for _, m := range schedModes {
		t.Run(m.name, func(t *testing.T) {
			tr := &tracker{}
			names := []string{"r1", "r2", "w", "r3", "r4"}
			tools := []Tool{tr.tool("r1", true), tr.tool("r2", true), tr.tool("w", false), tr.tool("r3", true), tr.tool("r4", true)}
			got := runSched(t, m, names, tools, 0)
			pos := map[string]int{}
			for i, e := range tr.log {
				pos[e] = i
			}
			for _, r := range []string{"r1", "r2"} {
				if pos["end "+r] > pos["start w"] {
					t.Fatalf("%s must finish before the unsafe call starts: %v", r, tr.log)
				}
			}
			for _, r := range []string{"r3", "r4"} {
				if pos["start "+r] < pos["end w"] {
					t.Fatalf("%s must not start before the unsafe call ends: %v", r, tr.log)
				}
			}
			if tr.peak != 2 {
				t.Fatalf("each safe run (r1,r2) and (r3,r4) overlaps: peak %d", tr.peak)
			}
			if strings.Join(got, ",") != strings.Join(names, ",") {
				t.Fatalf("results must keep emitted order: %v", got)
			}
		})
	}
}

func TestToolSchedulingEnvOverride(t *testing.T) {
	run := func(env string, max int) int {
		t.Setenv("TOOLNEXUS_MAX_TOOL_CONCURRENCY", env)
		tr := &tracker{}
		var names []string
		var tools []Tool
		for i := 1; i <= 6; i++ {
			n := fmt.Sprintf("r%d", i)
			names = append(names, n)
			tools = append(tools, tr.tool(n, true))
		}
		runSched(t, schedModes[0], names, tools, max)
		return tr.peak
	}
	if p := run("3", 0); p != 3 {
		t.Fatalf("env 3 must cap at 3, peak %d", p)
	}
	if p := run("3", 4); p != 4 {
		t.Fatalf("the option must win over the env, peak %d", p)
	}
	for _, bad := range []string{"0", "-2", "abc", ""} {
		if p := run(bad, 0); p != 6 {
			t.Fatalf("env %q must fall back to the default 10, peak %d", bad, p)
		}
	}
}

func TestToolSchedulingSafetyRule(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	for name, want := range map[string]bool{
		"read": true, "grep": true, "glob": true, "webfetch": true,
		"bash": false, "write": false, "edit": false, "apply_patch": false,
	} {
		tool, ok := tk.Get(name)
		if !ok {
			t.Fatalf("builtin %s missing", name)
		}
		if got := IsConcurrencySafe(tool, map[string]any{}); got != want {
			t.Fatalf("%s: safe=%v, want %v", name, got, want)
		}
	}
	noHints := Tool{Name: "x"}
	if IsConcurrencySafe(noHints, nil) {
		t.Fatal("a tool without hints must be unsafe")
	}
	own := Tool{Name: "y", Hints: &ToolHints{ReadOnly: true}, ConcurrencySafe: func(a map[string]any) bool { return a["dry"] == true }}
	if IsConcurrencySafe(own, map[string]any{}) || !IsConcurrencySafe(own, map[string]any{"dry": true}) {
		t.Fatal("a tool's own predicate must win, per input")
	}
	if !IsConcurrencySafe(RelayTool("r", "", nil), nil) {
		t.Fatal("relay tools surface together and must stay concurrent")
	}
}
