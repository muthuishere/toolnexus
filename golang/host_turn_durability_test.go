package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Host turn durability (openspec/changes/add-host-turn-durability): interrupted
// stateful turns are saved, killGraceMs, edit diffs.

// twoCallLLM answers turn 1 with two tool calls (fast c1, slow c2) and later
// turns with "done"; status != 0 fails every call with that HTTP status.
func twoCallLLM(t *testing.T, status int) *httptest.Server {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := map[string]any{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if status != 0 {
			w.WriteHeader(status)
			fmt.Fprint(w, `{"error":{"message":"bad request"}}`)
			return
		}
		anth := strings.HasSuffix(r.URL.Path, "/messages")
		stream, _ := body["stream"].(bool)
		sse := func(v any) { fmt.Fprintf(w, "data: %s\n\n", mustJSON(v)) }
		switch {
		case !anth && !stream:
			w.Header().Set("content-type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{
				"role": "assistant", "content": nil, "tool_calls": []any{
					map[string]any{"id": "c1", "type": "function", "function": map[string]any{"name": "fast", "arguments": "{}"}},
					map[string]any{"id": "c2", "type": "function", "function": map[string]any{"name": "slow", "arguments": "{}"}},
				}}}}})
		case anth && !stream:
			w.Header().Set("content-type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"content": []any{
				map[string]any{"type": "tool_use", "id": "c1", "name": "fast", "input": map[string]any{}},
				map[string]any{"type": "tool_use", "id": "c2", "name": "slow", "input": map[string]any{}},
			}, "stop_reason": "tool_use"})
		case !anth && stream:
			w.Header().Set("content-type", "text/event-stream")
			sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{
				map[string]any{"index": 0, "id": "c1", "type": "function", "function": map[string]any{"name": "fast", "arguments": "{}"}},
				map[string]any{"index": 1, "id": "c2", "type": "function", "function": map[string]any{"name": "slow", "arguments": "{}"}},
			}}}}})
			sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{}, "finish_reason": "tool_calls"}}})
			fmt.Fprint(w, "data: [DONE]\n\n")
		default:
			w.Header().Set("content-type", "text/event-stream")
			for i, u := range [][2]string{{"c1", "fast"}, {"c2", "slow"}} {
				sse(map[string]any{"type": "content_block_start", "index": i, "content_block": map[string]any{"type": "tool_use", "id": u[0], "name": u[1]}})
				sse(map[string]any{"type": "content_block_delta", "index": i, "delta": map[string]any{"type": "input_json_delta", "partial_json": "{}"}})
				sse(map[string]any{"type": "content_block_stop", "index": i})
			}
			sse(map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "tool_use"}})
			sse(map[string]any{"type": "message_stop"})
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

// fastSlowToolkit: fast returns at once; slow signals it started (after fast
// finished) and blocks until cancelled.
func fastSlowToolkit(t *testing.T, cancel context.CancelFunc) *Toolkit {
	fastDone := make(chan struct{})
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{
		NativeTool("fast", "fast", nil, func(ctx context.Context, _ map[string]any) (string, error) {
			close(fastDone)
			return "FAST", nil
		}),
		NativeTool("slow", "slow", nil, func(ctx context.Context, _ map[string]any) (string, error) {
			<-fastDone
			go func() { time.Sleep(50 * time.Millisecond); cancel() }()
			<-ctx.Done()
			return "", ctx.Err()
		}),
	}})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func runStateful(c *Client, ctx context.Context, tk *Toolkit, stream bool, id string) error {
	if !stream {
		_, err := c.Ask(ctx, "go", tk, id)
		return err
	}
	ch, err := c.StreamWithID(ctx, "go", tk, id)
	if err != nil {
		return err
	}
	var serr error
	for ev := range ch {
		if ev.Type == "error" {
			serr = ev.Err
		}
	}
	return serr
}

func TestInterruptedTurnCancelledMidToolIsSaved(t *testing.T) {
	for _, e := range parityRunEntries {
		t.Run(e.name, func(t *testing.T) {
			srv := twoCallLLM(t, 0)
			c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: e.style, Model: "m", APIKey: "k"})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			tk := fastSlowToolkit(t, cancel)
			err := runStateful(c, ctx, tk, e.stream, "conv")
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("err = %v, want the cancellation", err)
			}
			msgs, _ := c.ConversationStore().Get("conv")
			raw := mustJSON(msgs)
			// user prompt, assistant tool-call message, then the results
			if e.style == StyleOpenAI {
				if len(msgs) != 4 {
					t.Fatalf("want 4 messages, got %d: %s", len(msgs), raw)
				}
				u := msgs[0].(map[string]any)
				if u["role"] != "user" || !strings.Contains(mustJSON(u["content"]), "go") {
					t.Fatalf("first message should be the prompt: %s", raw)
				}
				if a := msgs[1].(map[string]any); a["role"] != "assistant" || len(anySlice(a["tool_calls"])) != 2 {
					t.Fatalf("second message should be the tool-call message: %s", raw)
				}
				r1, r2 := msgs[2].(map[string]any), msgs[3].(map[string]any)
				if r1["tool_call_id"] != "c1" || r1["content"] != "FAST" {
					t.Fatalf("first result lost: %s", raw)
				}
				if r2["role"] != "tool" || r2["tool_call_id"] != "c2" || r2["content"] != "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first." {
					t.Fatalf("second call should be sealed interrupted: %s", raw)
				}
				return
			}
			if len(msgs) != 3 {
				t.Fatalf("want 3 messages, got %d: %s", len(msgs), raw)
			}
			if msgs[0].(map[string]any)["role"] != "user" || msgs[1].(map[string]any)["role"] != "assistant" {
				t.Fatalf("want prompt + assistant: %s", raw)
			}
			blocks := anySlice(msgs[2].(map[string]any)["content"])
			if len(blocks) != 2 {
				t.Fatalf("want 2 tool_result blocks: %s", raw)
			}
			b1, b2 := blocks[0].(map[string]any), blocks[1].(map[string]any)
			if b1["tool_use_id"] != "c1" || b1["content"] != "FAST" {
				t.Fatalf("first result lost: %s", raw)
			}
			if b2["tool_use_id"] != "c2" || b2["content"] != "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first." || b2["is_error"] != true {
				t.Fatalf("second call should be sealed interrupted: %s", raw)
			}
		})
	}
}

func TestInterruptedTurnProviderErrorSavesPrompt(t *testing.T) {
	for _, e := range parityRunEntries {
		t.Run(e.name, func(t *testing.T) {
			srv := twoCallLLM(t, 400)
			c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: e.style, Model: "m", APIKey: "k"})
			prior := []any{
				map[string]any{"role": "user", "content": "earlier"},
				map[string]any{"role": "assistant", "content": "ok"},
			}
			_ = c.ConversationStore().Save("conv", prior)
			tk := fastSlowToolkit(t, func() {})
			err := runStateful(c, context.Background(), tk, e.stream, "conv")
			if err == nil {
				t.Fatal("the provider error must surface")
			}
			msgs, _ := c.ConversationStore().Get("conv")
			if len(msgs) != 3 {
				t.Fatalf("want prior(2) + prompt, got %s", mustJSON(msgs))
			}
			last := msgs[2].(map[string]any)
			if last["role"] != "user" || !strings.Contains(mustJSON(last["content"]), "go") {
				t.Fatalf("last message should be the new prompt: %s", mustJSON(msgs))
			}
		})
	}
}

func TestInterruptedTurnFailureBeforeLoopSavesPrompt(t *testing.T) {
	c := CreateClient(ClientOptions{BaseURL: "http://127.0.0.1:1", Model: "m", APIKey: "k",
		Hooks: &Hooks{BeforeLLM: func(context.Context, BeforeLLMEvent) (*LLMOverride, error) { return nil, errors.New("boom") }}})
	tk := fastSlowToolkit(t, func() {})
	if _, err := c.Ask(context.Background(), "hi", tk, "x"); err == nil {
		t.Fatal("want the hook error")
	}
	msgs, _ := c.ConversationStore().Get("x")
	if len(msgs) != 1 || msgs[0].(map[string]any)["role"] != "user" {
		t.Fatalf("want just the prompt, got %s", mustJSON(msgs))
	}
}

// ---- killGraceMs ----

func groupGone(pgid int) bool {
	return syscall.Kill(-pgid, 0) == syscall.ESRCH
}

// TestKillGraceMsShortGrace: a job that ignores SIGTERM AND has a TERM-ignoring
// background descendant is gone within 1 s of cancel with killGraceMs=100.
// Elapsed cancel→group-gone is measured over 10 runs and logged.
func TestKillGraceMsShortGrace(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX signals")
	}
	bash := builtinNamed(t, BuiltinsConfig{KillGraceMs: 100}, "bash")
	var times []time.Duration
	for run := 0; run < 10; run++ {
		dir := t.TempDir()
		pidFile := filepath.Join(dir, "pid")
		cmd := "trap '' TERM; (trap '' TERM; while :; do sleep 0.05; done) & echo $$ > " + pidFile + "; while :; do sleep 0.05; done"
		ctx, cancel := context.WithCancel(context.Background())
		resCh := make(chan ToolResult, 1)
		go func() {
			r, _ := bash.Execute(map[string]any{"command": cmd, "timeout": 60000}, &ToolContext{Ctx: ctx})
			resCh <- r
		}()
		var pgid int
		deadline := time.Now().Add(5 * time.Second)
		for pgid == 0 && time.Now().Before(deadline) {
			if b, err := os.ReadFile(pidFile); err == nil {
				fmt.Sscanf(strings.TrimSpace(string(b)), "%d", &pgid)
			}
			time.Sleep(10 * time.Millisecond)
		}
		if pgid == 0 {
			t.Fatal("job never started")
		}
		time.Sleep(100 * time.Millisecond) // let the descendant install its trap
		if groupGone(pgid) {
			t.Fatal("control: group not alive before cancel")
		}
		start := time.Now()
		cancel()
		for !groupGone(pgid) {
			if time.Since(start) > 3*time.Second {
				t.Fatalf("run %d: group %d still alive after 3s", run, pgid)
			}
			time.Sleep(time.Millisecond)
		}
		el := time.Since(start)
		times = append(times, el)
		if el > time.Second {
			t.Fatalf("run %d: group gone after %v, want < 1s", run, el)
		}
		<-resCh
	}
	sort.Slice(times, func(i, j int) bool { return times[i] < times[j] })
	t.Logf("cancel→group-gone over %d runs: min=%v median=%v max=%v", len(times), times[0], times[len(times)/2], times[len(times)-1])
}

// ---- edit diffs ----

func diffFiles(t *testing.T, res ToolResult) []map[string]any {
	t.Helper()
	raw, _ := res.Metadata["files"].([]any)
	var out []map[string]any
	for _, f := range raw {
		out = append(out, f.(map[string]any))
	}
	return out
}

func TestEditDiffModifiedOneLine(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "a.txt"), []byte("one\ntwo\nthree\n"), 0o644)
	edit := builtinNamed(t, BuiltinsConfig{BaseDir: dir}, "edit")
	res := runTool(t, edit, map[string]any{"path": "a.txt", "oldString": "two", "newString": "TWO"})
	if res.IsError || res.Output != "Edited a.txt (1 replacement)" {
		t.Fatalf("output changed: %q", res.Output)
	}
	f := diffFiles(t, res)
	if len(f) != 1 || f[0]["path"] != "a.txt" || f[0]["status"] != "modified" || f[0]["additions"] != 1 || f[0]["deletions"] != 1 {
		t.Fatalf("files = %v", f)
	}
	d, _ := res.Metadata["diff"].(string)
	for _, want := range []string{"--- a/a.txt\n", "+++ b/a.txt\n", "@@ -1,3 +1,3 @@\n", "\n-two\n", "\n+TWO\n"} {
		if !strings.Contains(d, want) {
			t.Fatalf("diff lacks %q:\n%s", want, d)
		}
	}
}

func TestWriteDiffNewFileAdded(t *testing.T) {
	dir := t.TempDir()
	write := builtinNamed(t, BuiltinsConfig{BaseDir: dir}, "write")
	res := runTool(t, write, map[string]any{"path": "sub/n.txt", "content": "a\nb\nc\n"})
	f := diffFiles(t, res)
	if len(f) != 1 || f[0]["path"] != "sub/n.txt" || f[0]["status"] != "added" || f[0]["additions"] != 3 || f[0]["deletions"] != 0 {
		t.Fatalf("files = %v", f)
	}
	if d := res.Metadata["diff"].(string); !strings.Contains(d, "--- /dev/null\n+++ b/sub/n.txt\n@@ -0,0 +1,3 @@\n+a\n+b\n+c\n") {
		t.Fatalf("diff:\n%s", d)
	}
	// overwrite is modified
	res = runTool(t, write, map[string]any{"path": "sub/n.txt", "content": "a\nb\n"})
	if f := diffFiles(t, res); f[0]["status"] != "modified" || f[0]["additions"] != 0 || f[0]["deletions"] != 1 {
		t.Fatalf("overwrite files = %v", f)
	}
}

func TestApplyPatchDiffAddAndDelete(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "old.txt"), []byte("x\ny\n"), 0o644)
	ap := builtinNamed(t, BuiltinsConfig{BaseDir: dir}, "apply_patch")
	patch := "*** Begin Patch\n*** Add File: new.txt\n+hello\n+world\n*** Delete File: old.txt\n*** End Patch"
	res := runTool(t, ap, map[string]any{"patchText": patch})
	if res.IsError {
		t.Fatalf("apply_patch failed: %s", res.Output)
	}
	f := diffFiles(t, res)
	if len(f) != 2 {
		t.Fatalf("files = %v", f)
	}
	if f[0]["path"] != "new.txt" || f[0]["status"] != "added" || f[0]["additions"] != 2 || f[0]["deletions"] != 0 {
		t.Fatalf("add = %v", f[0])
	}
	if f[1]["path"] != "old.txt" || f[1]["status"] != "deleted" || f[1]["additions"] != 0 || f[1]["deletions"] != 2 {
		t.Fatalf("delete = %v", f[1])
	}
	d := res.Metadata["diff"].(string)
	if !strings.Contains(d, "+++ b/new.txt") || !strings.Contains(d, "--- a/old.txt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-x\n-y\n") {
		t.Fatalf("diff:\n%s", d)
	}
}

func TestStreamToolResultCarriesMetadata(t *testing.T) {
	dir := t.TempDir()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "text/event-stream")
		body := map[string]any{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		sse := func(v any) { fmt.Fprintf(w, "data: %s\n\n", mustJSON(v)) }
		if len(anySlice(body["messages"])) <= 1 {
			sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{
				map[string]any{"index": 0, "id": "c1", "type": "function", "function": map[string]any{"name": "write", "arguments": `{"path":"f.txt","content":"z\n"}`}},
			}}}}})
		} else {
			sse(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": "ok"}}}})
		}
		fmt.Fprint(w, "data: [DONE]\n\n")
	}))
	defer srv.Close()
	tk, err := CreateToolkit(context.Background(), Options{Builtins: BuiltinsConfig{BaseDir: dir}})
	if err != nil {
		t.Fatal(err)
	}
	c := CreateClient(ClientOptions{BaseURL: srv.URL, Model: "m", APIKey: "k"})
	ch, err := c.Stream(context.Background(), "w", tk)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	for ev := range ch {
		if ev.Type == "tool_result" {
			got = ev.Metadata
		}
	}
	if f, _ := got["files"].([]any); len(f) != 1 {
		t.Fatalf("tool_result metadata = %v", got)
	}
}
