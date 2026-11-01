package toolnexus

// Hermetic tests for the opt-in ACP host hooks (openspec/changes/add-go-host-asks):
// OnPermission, OnUpdate, GenerateCtx cancellation and GenerateStream through a
// streaming in-process Client. Reuses the fake agent in acp_test.go.

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func acpPermissionAnswer(t *testing.T, outFile string) (outcome, optionID string) {
	t.Helper()
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "permission-answer" {
			o, _ := ev.Data["outcome"].(string)
			id, _ := ev.Data["optionId"].(string)
			return o, id
		}
	}
	t.Fatal("fake server never observed a permission answer")
	return "", ""
}

func TestACP_OnPermissionChoosesOption(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	opts := acpTestOptions(t, "permission", outFile)
	var gotParams json.RawMessage
	opts.OnPermission = func(ctx context.Context, params json.RawMessage) (string, error) {
		gotParams = params
		return "reject", nil
	}
	c, err := LoadACP(context.Background(), opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	if _, err := c.Generate(acpUserMessage("do it")); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	c.Close()
	if !strings.Contains(string(gotParams), `"toolCallId":"t9"`) {
		t.Fatalf("OnPermission did not get the raw params: %s", gotParams)
	}
	if o, id := acpPermissionAnswer(t, outFile); o != "selected" || id != "reject" {
		t.Fatalf("expected selected/reject, got %s/%s", o, id)
	}
}

func TestACP_OnPermissionErrorFallsBackToReject(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	opts := acpTestOptions(t, "permission", outFile)
	opts.OnPermission = func(context.Context, json.RawMessage) (string, error) {
		return "", errors.New("host said no")
	}
	c, err := LoadACP(context.Background(), opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	if _, err := c.Generate(acpUserMessage("do it")); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	c.Close()
	if o, id := acpPermissionAnswer(t, outFile); o != "selected" || id != "reject" {
		t.Fatalf("expected selected/reject, got %s/%s", o, id)
	}
}

func TestACP_OnPermissionEmptyWithoutRejectCancels(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	opts := acpTestOptions(t, "permission", outFile)
	opts.Env = append(opts.Env, "ACP_PERM_NO_REJECT=1")
	opts.OnPermission = func(context.Context, json.RawMessage) (string, error) { return "", nil }
	c, err := LoadACP(context.Background(), opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	if _, err := c.Generate(acpUserMessage("do it")); err != nil {
		t.Fatalf("Generate: %v", err)
	}
	c.Close()
	if o, _ := acpPermissionAnswer(t, outFile); o != "cancelled" {
		t.Fatalf("expected cancelled, got %s", o)
	}
}

func TestACP_OnUpdateSeesEveryUpdate(t *testing.T) {
	opts := acpTestOptions(t, "noisy", "")
	var mu sync.Mutex
	var kinds []string
	opts.OnUpdate = func(raw json.RawMessage) {
		var u struct {
			Update struct {
				SessionUpdate string `json:"sessionUpdate"`
			} `json:"update"`
		}
		_ = json.Unmarshal(raw, &u)
		mu.Lock()
		kinds = append(kinds, u.Update.SessionUpdate)
		mu.Unlock()
	}
	c, err := LoadACP(context.Background(), opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()
	resp, err := c.Generate(acpUserMessage("q"))
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if resp.Content != `{"answer":true}` {
		t.Fatalf("content changed by OnUpdate: %q", resp.Content)
	}
	mu.Lock()
	defer mu.Unlock()
	want := "agent_thought_chunk,tool_call,agent_message_chunk,tool_call_update,agent_thought_chunk,agent_message_chunk"
	if got := strings.Join(kinds, ","); got != want {
		t.Fatalf("OnUpdate saw %s, want %s", got, want)
	}
}

func TestACP_GenerateCtxCancelSendsSessionCancel(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	c, err := LoadACP(context.Background(), acpTestOptions(t, "cancel", outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(100 * time.Millisecond); cancel() }()
	start := time.Now()
	_, err = c.GenerateCtx(ctx, acpUserMessage("long job"))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("want context.Canceled, got %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("cancel took %s", time.Since(start))
	}
	// The session stays warm: a later turn on the same client still works.
	// The process stays warm: a second turn is accepted and cancellable too.
	ctx2, cancel2 := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel2()
	if _, err := c.GenerateCtx(ctx2, acpUserMessage("next")); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("follow-up turn: want DeadlineExceeded, got %v", err)
	}
	c.Close()
	var cancels int
	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "session/cancel" {
			cancels++
			if has, _ := ev.Data["hasId"].(bool); has {
				t.Fatal("session/cancel must be a notification (no id)")
			}
		}
	}
	if cancels != 2 {
		t.Fatalf("expected exactly 2 session/cancel, got %d", cancels)
	}
}

func TestACP_StreamThroughInProcessClient(t *testing.T) {
	c, err := LoadACP(context.Background(), acpTestOptions(t, "noisy", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()
	client := CreateInProcessClient(InProcessOptions{Model: "acp", GenerateStream: c.GenerateStream})
	ch, err := client.Stream(context.Background(), "q", bareToolkit(t))
	if err != nil {
		t.Fatalf("Stream: %v", err)
	}
	var deltas []string
	var seq []string
	var done *RunResult
	for ev := range ch {
		switch ev.Type {
		case "text":
			deltas = append(deltas, ev.Delta)
			seq = append(seq, "text")
		case "tool_call":
			if ev.ID != "t1" || ev.Name != "reading files" || ev.Via != "acp" {
				t.Fatalf("bad tool_call event: %+v", ev)
			}
			seq = append(seq, "tool_call")
		case "tool_result":
			if ev.ID != "t1" || ev.Name != "reading files" || ev.IsError {
				t.Fatalf("bad tool_result event: %+v", ev)
			}
			seq = append(seq, "tool_result")
		case "error":
			t.Fatalf("stream error: %v", ev.Err)
		case "done":
			done = ev.Result
		}
	}
	if strings.Join(deltas, "|") != `{"answer":|true}` {
		t.Fatalf("text deltas: %q", deltas)
	}
	if done == nil || done.Text != `{"answer":true}` {
		t.Fatalf("done result: %+v", done)
	}
	n := map[string]int{}
	for _, s := range seq {
		n[s]++
	}
	if n["tool_call"] != 1 || n["tool_result"] != 1 {
		t.Fatalf("tool events: %v", seq)
	}
	// The agent's own tool call is reported, never executed by the loop.
	if len(done.ToolCalls) != 0 {
		t.Fatalf("loop executed the agent's tool: %+v", done.ToolCalls)
	}
}

func TestACP_InProcessGenerateStreamUsedByRun(t *testing.T) {
	c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()
	client := CreateInProcessClient(InProcessOptions{Model: "acp", GenerateStream: c.GenerateStream})
	res, err := client.Run(context.Background(), "hello", bareToolkit(t))
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if !strings.Contains(res.Text, "echo:") {
		t.Fatalf("content: %q", res.Text)
	}
}
