// A2A (outbound) tests — an in-process httptest.Server plays a fake A2A agent,
// no external network. Mirrors js/test/unit.test.ts. Run: go test -race .
package toolnexus

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// a2aSeen records what the fake agent observed.
type a2aSeen struct {
	sendMessageCalls int64
	getTaskCalls     int64
	mu               sync.Mutex
	auth             string
}

func (s *a2aSeen) setAuth(a string) { s.mu.Lock(); s.auth = a; s.mu.Unlock() }
func (s *a2aSeen) getAuth() string  { s.mu.Lock(); defer s.mu.Unlock(); return s.auth }

type stubTask struct {
	polls int
	mode  string // "completed" | "failed" | "slow"
}

// startA2AStub stands up a tiny fake A2A agent:
//
//	GET /.well-known/agent-card.json → card { name:"reviewer", url→self,
//	    skills:[review, plan, fail] }
//	POST /  (JSON-RPC) SendMessage → Task {state:"submitted"}; GetTask →
//	    "working" once, then terminal. Behavior keyed on the message text:
//	    "fail" → failed; "slow" → stays "working"; else → completed(REVIEWED).
func startA2AStub() (*httptest.Server, *a2aSeen) {
	seen := &a2aSeen{}
	var mu sync.Mutex
	tasks := map[string]*stubTask{}
	counter := 0
	var srv *httptest.Server

	writeResult := func(w http.ResponseWriter, id any, result any) {
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/.well-known/agent-card.json" {
			w.Header().Set("content-type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"name":               "reviewer",
				"description":        "a code reviewer",
				"version":            "1.0.0",
				"protocolVersion":    "0.3.0",
				"capabilities":       map[string]any{"streaming": false, "pushNotifications": false},
				"defaultInputModes":  []string{"text/plain"},
				"defaultOutputModes": []string{"text/plain"},
				"url":                srv.URL + "/",
				"skills": []any{
					map[string]any{"id": "review", "name": "Review", "description": "Review some code"},
					map[string]any{"id": "plan", "name": "Plan", "description": "Plan a task"},
					map[string]any{"id": "fail", "name": "Fail", "description": "Always fails"},
				},
			})
			return
		}
		if r.Method == http.MethodPost {
			body, _ := io.ReadAll(r.Body)
			seen.setAuth(r.Header.Get("Authorization"))
			var rpc struct {
				ID     any             `json:"id"`
				Method string          `json:"method"`
				Params json.RawMessage `json:"params"`
			}
			_ = json.Unmarshal(body, &rpc)
			switch rpc.Method {
			case "SendMessage":
				atomic.AddInt64(&seen.sendMessageCalls, 1)
				var p struct {
					Message struct {
						Parts []struct {
							Text string `json:"text"`
						} `json:"parts"`
					} `json:"message"`
				}
				_ = json.Unmarshal(rpc.Params, &p)
				text := ""
				for _, pt := range p.Message.Parts {
					text += pt.Text
				}
				mode := "completed"
				if strings.Contains(text, "fail") {
					mode = "failed"
				} else if strings.Contains(text, "slow") {
					mode = "slow"
				}
				mu.Lock()
				counter++
				id := "t" + strconv.Itoa(counter)
				tasks[id] = &stubTask{mode: mode}
				mu.Unlock()
				writeResult(w, rpc.ID, map[string]any{"id": id, "status": map[string]any{"state": "submitted"}})
			case "GetTask":
				atomic.AddInt64(&seen.getTaskCalls, 1)
				var p struct {
					ID string `json:"id"`
				}
				_ = json.Unmarshal(rpc.Params, &p)
				mu.Lock()
				tk := tasks[p.ID]
				tk.polls++
				polls := tk.polls
				mode := tk.mode
				mu.Unlock()
				switch {
				case mode == "slow" || polls < 2:
					writeResult(w, rpc.ID, map[string]any{"id": p.ID, "status": map[string]any{"state": "working"}})
				case mode == "failed":
					writeResult(w, rpc.ID, map[string]any{
						"id": p.ID,
						"status": map[string]any{
							"state":   "failed",
							"message": map[string]any{"role": "agent", "parts": []any{map[string]any{"kind": "text", "text": "boom"}}},
						},
					})
				default:
					writeResult(w, rpc.ID, map[string]any{
						"id":        p.ID,
						"status":    map[string]any{"state": "completed"},
						"artifacts": []any{map[string]any{"parts": []any{map[string]any{"kind": "text", "text": "REVIEWED"}}}},
					})
				}
			default:
				w.Header().Set("content-type", "application/json")
				_ = json.NewEncoder(w).Encode(map[string]any{
					"jsonrpc": "2.0", "id": rpc.ID,
					"error": map[string]any{"code": -32601, "message": "unknown method"},
				})
			}
			return
		}
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("nope"))
	})

	srv = httptest.NewServer(handler)
	return srv, seen
}

func TestA2ASuccessRoundTripAndEnvHeader(t *testing.T) {
	srv, seen := startA2AStub()
	defer srv.Close()
	t.Setenv("TN_A2A_TOKEN", "a2a-secret")
	cardURL := srv.URL + "/.well-known/agent-card.json"

	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: false,
		Agents: []Agent{{
			Card:      cardURL,
			PollEvery: 5,
			Headers:   map[string]string{"Authorization": "Bearer ${TN_A2A_TOKEN}"},
		}},
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	review, ok := tk.Get("reviewer_review")
	if !ok {
		t.Fatal("reviewer_review not present")
	}
	if review.Source != SourceA2A {
		t.Fatalf("review.Source = %q, want %q", review.Source, SourceA2A)
	}
	if _, ok := tk.Get("reviewer_plan"); !ok {
		t.Fatal("reviewer_plan not present")
	}

	// Present in the provider schema.
	schema, _ := json.Marshal(tk.ToOpenAI())
	if !strings.Contains(string(schema), "reviewer_review") || !strings.Contains(string(schema), "reviewer_plan") {
		t.Fatalf("provider schema missing a2a tools: %s", schema)
	}

	r, _ := tk.Execute(context.Background(), "reviewer_review", map[string]any{"task": "please review"})
	if r.IsError {
		t.Fatalf("unexpected error: %s", r.Output)
	}
	if r.Output != "REVIEWED" {
		t.Fatalf("output = %q, want %q", r.Output, "REVIEWED")
	}
	if got := atomic.LoadInt64(&seen.sendMessageCalls); got != 1 {
		t.Fatalf("SendMessage calls = %d, want 1", got)
	}
	if got := atomic.LoadInt64(&seen.getTaskCalls); got < 2 {
		t.Fatalf("GetTask calls = %d, want >= 2", got)
	}
	if got := seen.getAuth(); got != "Bearer a2a-secret" {
		t.Fatalf("auth header = %q, want %q", got, "Bearer a2a-secret")
	}
	if r.Metadata["state"] != "completed" {
		t.Fatalf("metadata.state = %v, want completed", r.Metadata["state"])
	}
	if r.Metadata["agent"] != "reviewer" {
		t.Fatalf("metadata.agent = %v, want reviewer", r.Metadata["agent"])
	}
	if id, _ := r.Metadata["taskId"].(string); id == "" {
		t.Fatal("metadata.taskId is empty")
	}
}

func TestA2AFailedTaskIsError(t *testing.T) {
	srv, _ := startA2AStub()
	defer srv.Close()
	cardURL := srv.URL + "/.well-known/agent-card.json"

	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: false,
		Agents:   []Agent{{Card: cardURL, PollEvery: 5}},
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	r, _ := tk.Execute(context.Background(), "reviewer_fail", map[string]any{"task": "please fail"})
	if !r.IsError {
		t.Fatal("expected isError=true for failed task")
	}
	if !strings.Contains(r.Output, "failed") {
		t.Fatalf("output %q should mention failed", r.Output)
	}
	if !strings.Contains(r.Output, "boom") {
		t.Fatalf("output %q should carry the status message text", r.Output)
	}
	if r.Metadata["state"] != "failed" {
		t.Fatalf("metadata.state = %v, want failed", r.Metadata["state"])
	}
}

func TestA2ACancelStopsPolling(t *testing.T) {
	srv, seen := startA2AStub()
	defer srv.Close()
	cardURL := srv.URL + "/.well-known/agent-card.json"

	tk, err := CreateToolkit(context.Background(), Options{
		Builtins: false,
		Agents:   []Agent{{Card: cardURL, PollEvery: 10}},
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(35 * time.Millisecond)
		cancel()
	}()
	r, _ := tk.Execute(ctx, "reviewer_review", map[string]any{"task": "slow one"})
	if !r.IsError {
		t.Fatal("expected isError=true after cancel")
	}
	if r.Metadata["state"] != "canceled" {
		t.Fatalf("metadata.state = %v, want canceled", r.Metadata["state"])
	}
	// A GetTask already in flight when cancel fired is abandoned by the client but still
	// counted by the stub's handler goroutine — let that straggler land first, then assert
	// no NEW poll starts after cancellation (the real intent; unchanged behavior).
	time.Sleep(50 * time.Millisecond)
	afterAbort := atomic.LoadInt64(&seen.getTaskCalls)
	time.Sleep(80 * time.Millisecond)
	if got := atomic.LoadInt64(&seen.getTaskCalls); got != afterAbort {
		t.Fatalf("GetTask calls after abort = %d, want %d (no further polling)", got, afterAbort)
	}
}

func TestA2AConfigBlockEnabledDisabled(t *testing.T) {
	srv, _ := startA2AStub()
	defer srv.Close()
	cardURL := srv.URL + "/.well-known/agent-card.json"

	off, err := CreateToolkit(context.Background(), Options{
		Builtins:  false,
		McpConfig: map[string]any{"agents": map[string]any{"rev": map[string]any{"card": cardURL, "disabled": true}}},
	})
	if err != nil {
		t.Fatalf("CreateToolkit (off): %v", err)
	}
	if _, ok := off.Get("reviewer_review"); ok {
		t.Fatal("disabled agent should be skipped")
	}
	off.Close()

	on, err := CreateToolkit(context.Background(), Options{
		Builtins:  false,
		McpConfig: map[string]any{"agents": map[string]any{"rev": map[string]any{"card": cardURL, "pollEvery": 5}}},
	})
	if err != nil {
		t.Fatalf("CreateToolkit (on): %v", err)
	}
	if _, ok := on.Get("reviewer_review"); !ok {
		t.Fatal("enabled agent should resolve from config")
	}
	on.Close()
}

func TestA2AAddAgentRuntime(t *testing.T) {
	srv, _ := startA2AStub()
	defer srv.Close()
	cardURL := srv.URL + "/.well-known/agent-card.json"

	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	if _, ok := tk.Get("reviewer_review"); ok {
		t.Fatal("reviewer_review should be absent before AddAgent")
	}
	if _, err := tk.AddAgent(context.Background(), Agent{Card: cardURL, PollEvery: 5}, nil); err != nil {
		t.Fatalf("AddAgent: %v", err)
	}
	if _, ok := tk.Get("reviewer_review"); !ok {
		t.Fatal("reviewer_review should be present after AddAgent")
	}
	if _, ok := tk.Get("reviewer_plan"); !ok {
		t.Fatal("reviewer_plan should be present after AddAgent")
	}
	// Bare card URL form also works (re-adds; first-name-wins dedupe).
	if _, err := tk.AddAgent(context.Background(), cardURL, &Agent{PollEvery: 5}); err != nil {
		t.Fatalf("AddAgent (string form): %v", err)
	}
}

func TestParseAgentsConfigPrecedence(t *testing.T) {
	parsed := ParseAgentsConfig(AgentsConfig{
		"a": {Card: "http://x/1"},
		"b": {Card: "http://x/2", Disabled: boolPtr(true)},
		"c": {Card: "http://x/3", Enabled: boolPtr(false)},
		"d": {Card: "http://x/4", Enabled: boolPtr(true), Disabled: boolPtr(true)},
	})
	if len(parsed) != 1 {
		t.Fatalf("parsed len = %d, want 1", len(parsed))
	}
	if parsed[0].Card != "http://x/1" {
		t.Fatalf("parsed[0].Card = %q, want http://x/1", parsed[0].Card)
	}
}
