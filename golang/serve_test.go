// A2A (inbound) tests — Toolkit.Serve stood up on an ephemeral listener, with a
// mock LLM (httptest, no real network / no real model). The headline test is a
// full A2A round-trip: an in-process toolkit is SERVED, then this port's OWN
// outbound AgentTools calls it end-to-end. Mirrors js/test serve tests.
// Run: go test -race .
package toolnexus

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// mockOpenAI returns an httptest server that answers /chat/completions with a
// fixed assistant message (no tool calls) — the LLM the served toolkit runs.
func mockOpenAI(content string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{
				"message": map[string]any{"role": "assistant", "content": content},
			}},
			"usage": map[string]any{"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7},
		})
	}))
}

// rpcPost fires one JSON-RPC 2.0 request at the served endpoint and decodes the
// envelope.
func rpcPost(t *testing.T, url, method string, params any) map[string]any {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
	resp, err := http.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("rpcPost %s: %v", method, err)
	}
	defer resp.Body.Close()
	var out map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("rpcPost %s decode: %v", method, err)
	}
	return out
}

// TestServeA2ARoundTrip is the key parity test: serve a toolkit, then this port's
// OWN outbound A2A client resolves the served card and drives SendMessage/GetTask
// to completion, getting the LLM's answer back through the artifact.
func TestServeA2ARoundTrip(t *testing.T) {
	llm := mockOpenAI("TRANSCRIBED")
	defer llm.Close()

	tk, err := CreateToolkit(context.Background(), Options{
		Builtins:  false,
		SkillsDir: []string{"../examples/skills"},
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{
		Client: client,
		A2A:    &A2AConfig{Name: "video-desk", Skills: []string{"hello-world"}},
	})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()

	// Outbound: fetch the served card + resolve its skill into a Tool.
	tools, err := AgentTools(context.Background(), Agent{
		Card:      handle.URL + "/.well-known/agent-card.json",
		PollEvery: 5,
	})
	if err != nil {
		t.Fatalf("AgentTools: %v", err)
	}
	var hello *Tool
	for i := range tools {
		if tools[i].Name == "video-desk_hello-world" {
			hello = &tools[i]
		}
	}
	if hello == nil {
		names := make([]string, len(tools))
		for i, tl := range tools {
			names[i] = tl.Name
		}
		t.Fatalf("tool video-desk_hello-world not found; got %v", names)
	}

	res, err := hello.Execute(map[string]any{"task": "do it"}, &ToolContext{Ctx: context.Background()})
	if err != nil {
		t.Fatalf("Execute: %v", err)
	}
	if res.IsError {
		t.Fatalf("unexpected error: %s", res.Output)
	}
	if res.Output != "TRANSCRIBED" {
		t.Fatalf("output = %q, want %q", res.Output, "TRANSCRIBED")
	}
	if res.Metadata["state"] != "completed" {
		t.Fatalf("metadata.state = %v, want completed", res.Metadata["state"])
	}
}

// TestServeCardShape asserts the served card advertises SKILLS (not raw tools),
// reports streaming:false, and carries the byte-exact default fields.
func TestServeCardShape(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{
		SkillsDir: []string{"../examples/skills"}, // builtins ON (bash/read exist)
	})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	// Ensure builtin tools really are present in the toolkit (so the card's
	// exclusion of them is meaningful).
	if _, ok := tk.Get("bash"); !ok {
		t.Fatal("expected builtin bash tool present in toolkit")
	}

	client := CreateClient(ClientOptions{BaseURL: "http://unused", Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{Client: client, A2A: &A2AConfig{}})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()

	resp, err := http.Get(handle.URL + "/.well-known/agent-card.json")
	if err != nil {
		t.Fatalf("GET card: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("card status = %d, want 200", resp.StatusCode)
	}
	var card map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&card); err != nil {
		t.Fatalf("decode card: %v", err)
	}

	if card["name"] != "toolnexus-agent" {
		t.Fatalf("name = %v, want toolnexus-agent", card["name"])
	}
	if card["description"] != "" {
		t.Fatalf("description = %v, want empty string", card["description"])
	}
	if card["version"] != "0.1.0" {
		t.Fatalf("version = %v, want 0.1.0", card["version"])
	}
	if card["protocolVersion"] != "0.3.0" {
		t.Fatalf("protocolVersion = %v, want 0.3.0", card["protocolVersion"])
	}
	if card["url"] != handle.URL+"/" {
		t.Fatalf("url = %v, want %s/", card["url"], handle.URL)
	}
	if _, ok := card["provider"]; ok {
		t.Fatal("provider should be absent when unset")
	}

	caps, _ := card["capabilities"].(map[string]any)
	if caps == nil || caps["streaming"] != false || caps["pushNotifications"] != false {
		t.Fatalf("capabilities = %v, want {streaming:false, pushNotifications:false}", card["capabilities"])
	}

	skills, _ := card["skills"].([]any)
	found := map[string]bool{}
	for _, s := range skills {
		m, _ := s.(map[string]any)
		found[m["name"].(string)] = true
	}
	if !found["hello-world"] {
		t.Fatalf("skills should list hello-world; got %v", skills)
	}
	if found["bash"] || found["read"] || found["skill"] {
		t.Fatalf("skills must NOT list raw tools; got %v", skills)
	}
}

// TestServeProviderIncludedWhenSet verifies provider is emitted only when set.
func TestServeProviderIncludedWhenSet(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, SkillsDir: []string{"../examples/skills"}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: "http://unused", Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{
		Client: client,
		A2A:    &A2AConfig{Provider: &A2AProvider{Organization: "acme", URL: "https://acme.example"}},
	})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()

	resp, err := http.Get(handle.URL + "/.well-known/agent-card.json")
	if err != nil {
		t.Fatalf("GET card: %v", err)
	}
	defer resp.Body.Close()
	var card map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&card)
	prov, ok := card["provider"].(map[string]any)
	if !ok {
		t.Fatalf("provider missing; card=%v", card)
	}
	if prov["organization"] != "acme" || prov["url"] != "https://acme.example" {
		t.Fatalf("provider = %v", prov)
	}
}

// TestServeNoA2A404 verifies that without an A2A profile no routes mount.
func TestServeNoA2A404(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: "http://unused", Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{Client: client}) // no A2A
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()

	resp, err := http.Get(handle.URL + "/.well-known/agent-card.json")
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", resp.StatusCode)
	}
}

// TestServeFulfilmentErrorFailsTaskAndSurvives drives a serve whose LLM always
// 500s: the Task must land in `failed` and the server must keep serving.
func TestServeFulfilmentErrorFailsTaskAndSurvives(t *testing.T) {
	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":"boom"}`))
	}))
	defer llm.Close()

	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, SkillsDir: []string{"../examples/skills"}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	// Small retry backoff so the (retried) 500s resolve quickly.
	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k", RetryBaseMs: 1})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{Client: client, A2A: &A2AConfig{}})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()
	endpoint := handle.URL + "/"

	sent := rpcPost(t, endpoint, "SendMessage", map[string]any{
		"message": map[string]any{"parts": []any{map[string]any{"kind": "text", "text": "do it"}}},
	})
	result, _ := sent["result"].(map[string]any)
	if result == nil {
		t.Fatalf("SendMessage result missing: %v", sent)
	}
	id, _ := result["id"].(string)
	if id == "" {
		t.Fatalf("SendMessage returned no id: %v", sent)
	}
	if st, _ := result["status"].(map[string]any); st == nil || st["state"] != "submitted" {
		t.Fatalf("initial state = %v, want submitted", result["status"])
	}

	// Poll to the terminal state.
	var state string
	for i := 0; i < 200; i++ {
		got := rpcPost(t, endpoint, "GetTask", map[string]any{"id": id})
		res, _ := got["result"].(map[string]any)
		if res == nil {
			t.Fatalf("GetTask error: %v", got)
		}
		st, _ := res["status"].(map[string]any)
		state, _ = st["state"].(string)
		if state == "failed" {
			break
		}
		time.Sleep(15 * time.Millisecond)
	}
	if state != "failed" {
		t.Fatalf("final state = %q, want failed", state)
	}

	// Server still serves (card GET succeeds after the failed fulfilment).
	resp, err := http.Get(handle.URL + "/.well-known/agent-card.json")
	if err != nil {
		t.Fatalf("card GET after failure: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("card status after failure = %d, want 200", resp.StatusCode)
	}
}

// TestServeErrorCodes covers the JSON-RPC error mapping.
func TestServeErrorCodes(t *testing.T) {
	llm := mockOpenAI("ok")
	defer llm.Close()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, SkillsDir: []string{"../examples/skills"}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()
	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{Client: client, A2A: &A2AConfig{}})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()
	endpoint := handle.URL + "/"

	// Unknown method → -32601.
	got := rpcPost(t, endpoint, "Nope", map[string]any{})
	if code := errCode(got); code != -32601 {
		t.Fatalf("unknown method code = %v, want -32601", code)
	}
	// Unknown task id → -32001.
	got = rpcPost(t, endpoint, "GetTask", map[string]any{"id": "does-not-exist"})
	if code := errCode(got); code != -32001 {
		t.Fatalf("unknown id code = %v, want -32001", code)
	}
	// Parse error → -32700.
	resp, err := http.Post(endpoint, "application/json", bytes.NewReader([]byte("{not json")))
	if err != nil {
		t.Fatalf("POST: %v", err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	if code := errCode(out); code != -32700 {
		t.Fatalf("parse error code = %v, want -32700", code)
	}
}

func errCode(env map[string]any) int {
	e, _ := env["error"].(map[string]any)
	if e == nil {
		return 0
	}
	c, _ := e["code"].(float64)
	return int(c)
}

// countingStore wraps an in-memory store and counts Save calls — proves the
// host-supplied TaskStore is used for every read/write.
type countingStore struct {
	inner *InMemoryTaskStore
	mu    sync.Mutex
	saves int
	gets  int
}

func (s *countingStore) Get(id string) (*A2ATask, error) {
	s.mu.Lock()
	s.gets++
	s.mu.Unlock()
	return s.inner.Get(id)
}

func (s *countingStore) Save(task A2ATask) error {
	s.mu.Lock()
	s.saves++
	s.mu.Unlock()
	return s.inner.Save(task)
}

// TestServeCustomStore verifies a custom TaskStore receives every write.
func TestServeCustomStore(t *testing.T) {
	llm := mockOpenAI("DONE")
	defer llm.Close()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, SkillsDir: []string{"../examples/skills"}})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	store := &countingStore{inner: NewInMemoryTaskStore()}
	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{Client: client, A2A: &A2AConfig{Store: store}})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()
	endpoint := handle.URL + "/"

	sent := rpcPost(t, endpoint, "SendMessage", map[string]any{
		"message": map[string]any{"parts": []any{map[string]any{"kind": "text", "text": "go"}}},
	})
	id := sent["result"].(map[string]any)["id"].(string)

	var state string
	for i := 0; i < 200; i++ {
		got := rpcPost(t, endpoint, "GetTask", map[string]any{"id": id})
		res, _ := got["result"].(map[string]any)
		st, _ := res["status"].(map[string]any)
		state, _ = st["state"].(string)
		if state == "completed" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if state != "completed" {
		t.Fatalf("final state = %q, want completed", state)
	}
	store.mu.Lock()
	saves, gets := store.saves, store.gets
	store.mu.Unlock()
	// submitted + working + completed = 3 saves through the custom store.
	if saves < 3 {
		t.Fatalf("custom store saves = %d, want >= 3", saves)
	}
	if gets < 1 {
		t.Fatalf("custom store gets = %d, want >= 1", gets)
	}
}

// TestFileTaskStoreRoundTrip round-trips a Task through the file store.
func TestFileTaskStoreRoundTrip(t *testing.T) {
	dir := t.TempDir()
	store := NewFileTaskStore(dir)

	text := "hi there"
	task := A2ATask{
		ID:     "task-123",
		Status: A2ATaskStatus{State: "completed"},
		Artifacts: []A2AArtifact{{
			ArtifactID: "art-1",
			Parts:      []A2APart{{Kind: "text", Text: &text}},
		}},
	}
	if err := store.Save(task); err != nil {
		t.Fatalf("Save: %v", err)
	}

	got, err := store.Get("task-123")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if got == nil {
		t.Fatal("Get returned nil for a saved task")
	}
	if got.ID != "task-123" || got.Status.State != "completed" {
		t.Fatalf("round-trip mismatch: %+v", got)
	}
	if len(got.Artifacts) != 1 || got.Artifacts[0].ArtifactID != "art-1" {
		t.Fatalf("artifact mismatch: %+v", got.Artifacts)
	}
	if len(got.Artifacts[0].Parts) != 1 || got.Artifacts[0].Parts[0].Text == nil || *got.Artifacts[0].Parts[0].Text != text {
		t.Fatalf("part text mismatch: %+v", got.Artifacts[0].Parts)
	}

	// Missing id → (nil, nil).
	miss, err := store.Get("nope")
	if err != nil || miss != nil {
		t.Fatalf("Get(missing) = (%v, %v), want (nil, nil)", miss, err)
	}
}

// TestResolveStore covers the selector → store mapping.
func TestResolveStore(t *testing.T) {
	if s, err := ResolveStore(nil); err != nil {
		t.Fatalf("nil: %v", err)
	} else if _, ok := s.(*InMemoryTaskStore); !ok {
		t.Fatalf("nil → %T, want *InMemoryTaskStore", s)
	}
	if s, err := ResolveStore("memory"); err != nil {
		t.Fatalf("memory: %v", err)
	} else if _, ok := s.(*InMemoryTaskStore); !ok {
		t.Fatalf("memory → %T, want *InMemoryTaskStore", s)
	}
	dir := t.TempDir()
	if s, err := ResolveStore("file:" + dir); err != nil {
		t.Fatalf("file: %v", err)
	} else if _, ok := s.(*FileTaskStore); !ok {
		t.Fatalf("file → %T, want *FileTaskStore", s)
	}
	custom := &countingStore{inner: NewInMemoryTaskStore()}
	if s, err := ResolveStore(custom); err != nil {
		t.Fatalf("custom: %v", err)
	} else if s != custom {
		t.Fatalf("custom store not returned as-is")
	}
	if _, err := ResolveStore("bogus"); err == nil {
		t.Fatal("bogus selector should error")
	}
}
