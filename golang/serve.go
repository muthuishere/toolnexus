// A2A (agent-to-agent) — INBOUND: serve a toolkit as a remote A2A agent.
//
// Toolkit.Serve(addr, ServeOptions{Client, A2A}) stands up a minimal HTTP server
// that, when the A2A profile is present, exposes:
//
//	GET  /.well-known/agent-card.json  → an Agent Card built from the toolkit's
//	     SKILLS (SKILL.md name+description), never raw tools.
//	POST /                             → JSON-RPC 2.0: SendMessage (submit a Task,
//	     fulfil it asynchronously via Client.Run) + GetTask (poll).
//
// This is the inbound counterpart to a2a.go (outbound) and speaks the same wire
// subset (A2ATask/A2AArtifact/A2AMessage/A2APart are reused verbatim). Task
// persistence is a pluggable TaskStore (in-memory default; file / custom
// selectable via A2AConfig.Store). See ../SPEC.md §7B and
// openspec/changes/add-a2a-agents.
package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

const (
	cardPath             = "/.well-known/agent-card.json"
	serveProtocolVersion = "0.3.0"
	serveDefaultVersion  = "0.1.0"
)

// ---------------------------------------------------------------------------
// TaskStore adapter — pluggable persistence for served Tasks.
// ---------------------------------------------------------------------------

// TaskStore is pluggable Task persistence. A nil *A2ATask from Get signals an
// unknown id (the JS `undefined`); Get returns an error only on an unexpected
// failure a custom store wants to surface. The-factory can plug NATS/JetStream
// the same way.
type TaskStore interface {
	Get(id string) (*A2ATask, error)
	Save(task A2ATask) error
}

// InMemoryTaskStore is the default store — Tasks live only for the process
// lifetime. Safe for concurrent use.
type InMemoryTaskStore struct {
	mu    sync.Mutex
	tasks map[string]A2ATask
}

// NewInMemoryTaskStore builds an empty in-memory store.
func NewInMemoryTaskStore() *InMemoryTaskStore {
	return &InMemoryTaskStore{tasks: map[string]A2ATask{}}
}

// Get returns the Task by id, or (nil, nil) when absent.
func (s *InMemoryTaskStore) Get(id string) (*A2ATask, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.tasks[id]
	if !ok {
		return nil, nil
	}
	clone := t
	return &clone, nil
}

// Save persists the Task by its id.
func (s *InMemoryTaskStore) Save(task A2ATask) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tasks[task.ID] = task
	return nil
}

// FileTaskStore is a file-backed store — one JSON file per Task id, under dir.
type FileTaskStore struct {
	dir string
}

// NewFileTaskStore builds a file store rooted at dir (created if missing).
func NewFileTaskStore(dir string) *FileTaskStore {
	_ = os.MkdirAll(dir, 0o755)
	return &FileTaskStore{dir: dir}
}

func (s *FileTaskStore) file(id string) string {
	return filepath.Join(s.dir, Sanitize(id)+".json")
}

// Get reads the Task from its file, or (nil, nil) when the file is missing or
// unreadable (mirrors the JS store, which swallows read errors as undefined).
func (s *FileTaskStore) Get(id string) (*A2ATask, error) {
	data, err := os.ReadFile(s.file(id))
	if err != nil {
		return nil, nil
	}
	var t A2ATask
	if err := json.Unmarshal(data, &t); err != nil {
		return nil, nil
	}
	return &t, nil
}

// Save writes the Task to its file.
func (s *FileTaskStore) Save(task A2ATask) error {
	data, err := json.Marshal(task)
	if err != nil {
		return err
	}
	return os.WriteFile(s.file(task.ID), data, 0o644)
}

// ResolveStore maps a store selector to a concrete TaskStore:
// nil / "" / "memory" ⇒ in-memory; "file:<dir>" ⇒ file store; a TaskStore value
// ⇒ used as-is. Any other string is an error.
func ResolveStore(store any) (TaskStore, error) {
	switch v := store.(type) {
	case nil:
		return NewInMemoryTaskStore(), nil
	case TaskStore:
		return v, nil
	case string:
		if v == "" || v == "memory" {
			return NewInMemoryTaskStore(), nil
		}
		if strings.HasPrefix(v, "file:") {
			return NewFileTaskStore(strings.TrimPrefix(v, "file:")), nil
		}
		return nil, fmt.Errorf("unknown A2A store: %q (expected \"memory\" or \"file:<dir>\")", v)
	default:
		return nil, fmt.Errorf("unknown A2A store type: %T", store)
	}
}

// ---------------------------------------------------------------------------
// A2A serve config + handle.
// ---------------------------------------------------------------------------

// A2AProvider identifies the organization publishing the agent.
type A2AProvider struct {
	Organization string `json:"organization"`
	URL          string `json:"url"`
}

// A2AConfig is the opt-in A2A profile for Serve — it configures the Agent Card
// and the Task store.
type A2AConfig struct {
	Name        string       `json:"name,omitempty"`
	Description string       `json:"description,omitempty"`
	Version     string       `json:"version,omitempty"`
	Provider    *A2AProvider `json:"provider,omitempty"`
	// Skills is the subset of the toolkit's skill names to advertise; nil ⇒ all,
	// non-nil (even empty) ⇒ filter to exactly this set.
	Skills []string `json:"skills,omitempty"`
	// Store is "memory" (default) | "file:<dir>" | a custom TaskStore.
	Store any `json:"store,omitempty"`
}

// OnTaskEvent is surfaced on each Task's terminal state, carrying the RunResult
// telemetry.
type OnTaskEvent struct {
	ID     string
	Skill  string
	Task   A2ATask
	Result *RunResult
	State  string
}

// OnTask is the terminal-state callback passed to Serve.
type OnTask func(ev OnTaskEvent)

// ServeOptions configures Toolkit.Serve. Client fulfils Tasks via its run loop;
// A2A (or the toolkit's top-level a2a config block) enables the A2A routes.
type ServeOptions struct {
	Client *Client
	A2A    *A2AConfig
	OnTask OnTask
}

// ServeHandle is returned by Serve — the base URL plus a stop/close method.
type ServeHandle struct {
	// URL is the base URL of the server, e.g. http://127.0.0.1:PORT.
	URL string

	server *http.Server
	once   sync.Once
	err    error
}

// Stop shuts the server down (idempotent).
func (h *ServeHandle) Stop() error {
	h.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		h.err = h.server.Shutdown(ctx)
	})
	return h.err
}

// Close is an alias for Stop.
func (h *ServeHandle) Close() error { return h.Stop() }

// ---------------------------------------------------------------------------
// Card builder.
// ---------------------------------------------------------------------------

// agentCardCapabilities is the fixed capability block of a served card. A struct
// (not a map) so the JSON field order and empty values match the JS reference
// byte-for-byte.
type agentCardCapabilities struct {
	Streaming         bool `json:"streaming"`
	PushNotifications bool `json:"pushNotifications"`
}

// agentCardSkill is one advertised skill on a served card ({id, name, description}
// — always all three, even when description is empty).
type agentCardSkill struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
}

// servedAgentCard is the exact wire shape published at cardPath. A dedicated
// struct (rather than the outbound AgentCard, whose omitempty tags would drop
// description:"" and whose map capabilities would reorder keys) so the served
// card is byte-identical to the JS reference (SPEC §7B).
type servedAgentCard struct {
	Name               string                `json:"name"`
	Description        string                `json:"description"`
	Version            string                `json:"version"`
	ProtocolVersion    string                `json:"protocolVersion"`
	Capabilities       agentCardCapabilities `json:"capabilities"`
	DefaultInputModes  []string              `json:"defaultInputModes"`
	DefaultOutputModes []string              `json:"defaultOutputModes"`
	Skills             []agentCardSkill      `json:"skills"`
	URL                string                `json:"url"`
	Provider           *A2AProvider          `json:"provider,omitempty"`
}

// BuildAgentCard builds an Agent Card from the toolkit's skills. skills are the
// SkillSource's SkillInfos (never raw tools); filtered to cfg.Skills when given.
// url is the JSON-RPC endpoint peers POST to.
func BuildAgentCard(cfg *A2AConfig, skills []SkillInfo, url string) servedAgentCard {
	selected := skills
	if cfg != nil && cfg.Skills != nil {
		wanted := make(map[string]bool, len(cfg.Skills))
		for _, name := range cfg.Skills {
			wanted[name] = true
		}
		selected = selected[:0:0]
		for _, s := range skills {
			if wanted[s.Name] {
				selected = append(selected, s)
			}
		}
	}
	cardSkills := make([]agentCardSkill, 0, len(selected))
	for _, s := range selected {
		cardSkills = append(cardSkills, agentCardSkill{ID: s.Name, Name: s.Name, Description: s.Description})
	}

	name := "toolnexus-agent"
	description := ""
	version := serveDefaultVersion
	var provider *A2AProvider
	if cfg != nil {
		if cfg.Name != "" {
			name = cfg.Name
		}
		if cfg.Description != "" {
			description = cfg.Description
		}
		if cfg.Version != "" {
			version = cfg.Version
		}
		provider = cfg.Provider
	}

	return servedAgentCard{
		Name:               name,
		Description:        description,
		Version:            version,
		ProtocolVersion:    serveProtocolVersion,
		Capabilities:       agentCardCapabilities{Streaming: false, PushNotifications: false},
		DefaultInputModes:  []string{"text"},
		DefaultOutputModes: []string{"text"},
		Skills:             cardSkills,
		URL:                url,
		Provider:           provider,
	}
}

// ---------------------------------------------------------------------------
// Server.
// ---------------------------------------------------------------------------

// startServerOptions is the internal config for startA2AServer (Toolkit.Serve
// delegates here).
type startServerOptions struct {
	addr    string
	a2a     *A2AConfig
	skills  []SkillInfo
	runTask func(text string) (RunResult, error)
	onTask  OnTask
}

// rpcError is the JSON-RPC 2.0 error object.
type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// rpcResponse is a JSON-RPC 2.0 response envelope. A struct (not a map) so the
// field order — jsonrpc, id, then result|error — matches the JS reference.
type rpcResponse struct {
	JSONRPC string    `json:"jsonrpc"`
	ID      any       `json:"id"`
	Result  any       `json:"result,omitempty"`
	Error   *rpcError `json:"error,omitempty"`
}

// messageText extracts the concatenated text of a SendMessage message's parts.
func messageText(params json.RawMessage) string {
	var p struct {
		Message struct {
			Parts []struct {
				Kind string `json:"kind"`
				Text string `json:"text"`
			} `json:"parts"`
		} `json:"message"`
	}
	if err := json.Unmarshal(params, &p); err != nil {
		return ""
	}
	var b strings.Builder
	for _, part := range p.Message.Parts {
		if part.Kind == "text" {
			b.WriteString(part.Text)
		}
	}
	return b.String()
}

// startA2AServer starts the HTTP server. Delegated to by Toolkit.Serve. When a2a
// is absent, the server answers 404 to everything (a minimal base for now).
func startA2AServer(opts startServerOptions) (*ServeHandle, error) {
	host, port := splitAddr(opts.addr)

	var store TaskStore
	if opts.a2a != nil {
		s, err := ResolveStore(opts.a2a.Store)
		if err != nil {
			return nil, err
		}
		store = s
	}

	ln, err := net.Listen("tcp", net.JoinHostPort(host, port))
	if err != nil {
		return nil, err
	}
	actualPort := ln.Addr().(*net.TCPAddr).Port
	base := baseURL(host, actualPort)

	writeRPC := func(w http.ResponseWriter, resp rpcResponse) {
		resp.JSONRPC = "2.0"
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}
	rpcOK := func(w http.ResponseWriter, id any, result any) {
		writeRPC(w, rpcResponse{ID: id, Result: result})
	}
	rpcErr := func(w http.ResponseWriter, id any, code int, message string) {
		writeRPC(w, rpcResponse{ID: id, Error: &rpcError{Code: code, Message: message}})
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// No A2A profile ⇒ no routes.
		if opts.a2a == nil || store == nil {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte("not found"))
			return
		}

		if r.Method == http.MethodGet && r.URL.Path == cardPath {
			w.Header().Set("content-type", "application/json")
			_ = json.NewEncoder(w).Encode(BuildAgentCard(opts.a2a, opts.skills, base+"/"))
			return
		}

		if r.Method == http.MethodPost {
			body, _ := io.ReadAll(r.Body)
			var rpc struct {
				ID     any             `json:"id"`
				Method string          `json:"method"`
				Params json.RawMessage `json:"params"`
			}
			if err := json.Unmarshal(body, &rpc); err != nil {
				rpcErr(w, nil, -32700, "Parse error")
				return
			}
			switch rpc.Method {
			case "SendMessage":
				text := messageText(rpc.Params)
				id := uuid.NewString()
				submitted := A2ATask{ID: id, Status: A2ATaskStatus{State: "submitted"}}
				// Persist submitted, kick fulfilment async, return the id now.
				_ = store.Save(submitted)
				go fulfil(id, text, store, opts.runTask, opts.onTask)
				rpcOK(w, rpc.ID, submitted)
			case "GetTask":
				var p struct {
					ID string `json:"id"`
				}
				_ = json.Unmarshal(rpc.Params, &p)
				task, _ := store.Get(p.ID)
				if task == nil {
					rpcErr(w, rpc.ID, -32001, "Task not found: "+p.ID)
					return
				}
				rpcOK(w, rpc.ID, *task)
			default:
				rpcErr(w, rpc.ID, -32601, "Method not found: "+rpc.Method)
			}
			return
		}

		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("not found"))
	})

	srv := &http.Server{Handler: handler}
	go func() { _ = srv.Serve(ln) }()

	return &ServeHandle{URL: base, server: srv}, nil
}

// fulfil runs background fulfilment: submitted → working → (completed | failed).
// It never crashes the server — a fulfilment error (or panic) becomes a failed
// Task. Mirrors the JS fulfil().
func fulfil(
	id, text string,
	store TaskStore,
	runTask func(text string) (RunResult, error),
	onTask OnTask,
) {
	// A panic in the client loop must never crash the process.
	defer func() { _ = recover() }()

	var task A2ATask
	var result *RunResult
	var state string

	_ = store.Save(A2ATask{ID: id, Status: A2ATaskStatus{State: "working"}})
	rr, err := runTask(text)
	if err != nil {
		detail := err.Error()
		task = A2ATask{
			ID: id,
			Status: A2ATaskStatus{
				State:   "failed",
				Message: &A2AMessage{Role: "agent", Parts: []A2APart{{Kind: "text", Text: &detail}}},
			},
		}
		state = "failed"
	} else {
		result = &rr
		artifactText := rr.Text
		task = A2ATask{
			ID:     id,
			Status: A2ATaskStatus{State: "completed"},
			Artifacts: []A2AArtifact{{
				ArtifactID: uuid.NewString(),
				Parts:      []A2APart{{Kind: "text", Text: &artifactText}},
			}},
		}
		state = "completed"
	}

	// A store write failure must not crash the server.
	_ = store.Save(task)

	if onTask != nil {
		// Host callback errors/panics are isolated.
		func() {
			defer func() { _ = recover() }()
			onTask(OnTaskEvent{ID: id, Task: task, Result: result, State: state})
		}()
	}
}

// ---------------------------------------------------------------------------
// Address helpers.
// ---------------------------------------------------------------------------

// splitAddr splits host:port on the last colon. Missing host ⇒ 127.0.0.1.
func splitAddr(addr string) (host, port string) {
	idx := strings.LastIndex(addr, ":")
	if idx == -1 {
		return "127.0.0.1", addr
	}
	host = addr[:idx]
	if host == "" {
		host = "127.0.0.1"
	}
	return host, addr[idx+1:]
}

// baseURL is the base URL for a listening server; 0.0.0.0 / :: is reported as
// 127.0.0.1 for callers.
func baseURL(host string, port int) string {
	h := host
	if h == "0.0.0.0" || h == "::" {
		h = "127.0.0.1"
	}
	return fmt.Sprintf("http://%s:%d", h, port)
}
