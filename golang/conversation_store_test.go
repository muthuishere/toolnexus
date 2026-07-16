// Conversation memory tests — Client.Ask + ConversationStore, and A2A serve
// remembering a peer's turns by contextId. Hermetic: a mock OpenAI LLM (httptest)
// whose reply is the number of messages it received, so a growing count proves
// history is being loaded. Mirrors the js/test conversation-store tests.
// Run: go test -race .

package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// mockCountingLLM answers /chat/completions with an assistant message whose text
// is the number of messages the request carried. With no skills/system prompt,
// turn 1 sees just the user turn (1); turn 2 of the same conversation sees
// user+assistant+user (3) — proof that history was reloaded.
func mockCountingLLM() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []any `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{
				"message": map[string]any{"role": "assistant", "content": fmt.Sprint(len(body.Messages))},
			}},
			"usage": map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
}

// TestAskRemembersByID: no id ⇒ stateless one-shot; a repeated id remembers;
// distinct ids are independent conversations.
func TestAskRemembersByID(t *testing.T) {
	llm := mockCountingLLM()
	defer llm.Close()

	// builtins false ⇒ no system prompt, so the count is exactly the turns.
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k"})
	ctx := context.Background()

	solo, err := client.Ask(ctx, "hi", tk, "")
	if err != nil {
		t.Fatalf("Ask(no id): %v", err)
	}
	if solo.Text != "1" {
		t.Fatalf("no id ⇒ stateless one-shot: text = %q, want 1", solo.Text)
	}

	a, err := client.Ask(ctx, "first", tk, "c1")
	if err != nil {
		t.Fatalf("Ask(c1 #1): %v", err)
	}
	if a.Text != "1" {
		t.Fatalf("first turn: text = %q, want 1", a.Text)
	}
	b, err := client.Ask(ctx, "second", tk, "c1")
	if err != nil {
		t.Fatalf("Ask(c1 #2): %v", err)
	}
	if b.Text != "3" {
		t.Fatalf("same id remembers (user+assistant+user): text = %q, want 3", b.Text)
	}

	c, err := client.Ask(ctx, "other", tk, "c2")
	if err != nil {
		t.Fatalf("Ask(c2): %v", err)
	}
	if c.Text != "1" {
		t.Fatalf("a different id is independent: text = %q, want 1", c.Text)
	}
}

// recordingConvStore is a custom ConversationStore that records get/save calls,
// proving Ask routes through the host-supplied provider.
type recordingConvStore struct {
	mu      sync.Mutex
	calls   []string
	backing map[string][]any
}

func (s *recordingConvStore) Get(id string) ([]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls = append(s.calls, "get:"+id)
	return s.backing[id], nil
}

func (s *recordingConvStore) Save(id string, messages []any) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls = append(s.calls, "save:"+id)
	s.backing[id] = messages
	return nil
}

// TestCustomConversationStore: a host-supplied ConversationStore is used (get
// then save), and the transcript is persisted through it.
func TestCustomConversationStore(t *testing.T) {
	llm := mockCountingLLM()
	defer llm.Close()

	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	store := &recordingConvStore{backing: map[string][]any{}}
	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k", Store: store})

	if _, err := client.Ask(context.Background(), "hi", tk, "u1"); err != nil {
		t.Fatalf("Ask: %v", err)
	}

	store.mu.Lock()
	calls := append([]string{}, store.calls...)
	_, persisted := store.backing["u1"]
	store.mu.Unlock()

	if len(calls) != 2 || calls[0] != "get:u1" || calls[1] != "save:u1" {
		t.Fatalf("custom store calls = %v, want [get:u1 save:u1]", calls)
	}
	if !persisted {
		t.Fatal("custom store did not persist the transcript for u1")
	}
}

// TestServeRemembersContextID: the A2A inbound serve fulfils each SendMessage via
// Client.Ask keyed on the message's contextId, so a peer's turns are remembered
// across tasks in the same context, and distinct contexts are independent.
func TestServeRemembersContextID(t *testing.T) {
	llm := mockCountingLLM()
	defer llm.Close()

	// no skills ⇒ no system-prompt message, so counts are exactly the turns.
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false})
	if err != nil {
		t.Fatalf("CreateToolkit: %v", err)
	}
	defer tk.Close()

	client := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "x", APIKey: "k"})
	handle, err := tk.Serve("127.0.0.1:0", ServeOptions{Client: client, A2A: &A2AConfig{Name: "mem-desk"}})
	if err != nil {
		t.Fatalf("Serve: %v", err)
	}
	defer handle.Stop()
	endpoint := handle.URL + "/"

	send := func(text, contextID string) string {
		t.Helper()
		sent := rpcPost(t, endpoint, "SendMessage", map[string]any{
			"message": map[string]any{
				"role":      "user",
				"contextId": contextID,
				"parts":     []any{map[string]any{"kind": "text", "text": text}},
			},
		})
		result, _ := sent["result"].(map[string]any)
		id, _ := result["id"].(string)
		if id == "" {
			t.Fatalf("SendMessage returned no id: %v", sent)
		}
		for i := 0; i < 200; i++ {
			got := rpcPost(t, endpoint, "GetTask", map[string]any{"id": id})
			res, _ := got["result"].(map[string]any)
			st, _ := res["status"].(map[string]any)
			state, _ := st["state"].(string)
			if state == "completed" || state == "failed" {
				if state != "completed" {
					t.Fatalf("task %s state = %q, want completed", id, state)
				}
				arts, _ := res["artifacts"].([]any)
				if len(arts) == 0 {
					t.Fatalf("no artifacts on completed task: %v", res)
				}
				parts, _ := arts[0].(map[string]any)["parts"].([]any)
				if len(parts) == 0 {
					t.Fatalf("no parts in artifact: %v", arts[0])
				}
				txt, _ := parts[0].(map[string]any)["text"].(string)
				return txt
			}
			time.Sleep(10 * time.Millisecond)
		}
		t.Fatal("task never reached a terminal state")
		return ""
	}

	if got := send("first", "ctxA"); got != "1" {
		t.Fatalf("first served turn: text = %q, want 1", got)
	}
	if got := send("second", "ctxA"); got != "3" {
		t.Fatalf("same contextId remembers (user+assistant+user): text = %q, want 3", got)
	}
	if got := send("other", "ctxB"); got != "1" {
		t.Fatalf("a different contextId is independent: text = %q, want 1", got)
	}
}
