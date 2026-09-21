package toolnexus

// Hermetic ACP tests: no network, no real devin/gemini/zed agent. The "fake
// ACP agent" is this very test binary, re-invoked as a subprocess with
// -test.run=TestACPHelperProcess and GO_WANT_ACP_HELPER=1 set (the classic
// os/exec TestHelperProcess pattern) — so there is no `go build` subprocess
// step and no extra testdata binary to maintain. It speaks the same
// JSON-RPC-2.0-one-object-per-line framing as spikes/acp/fakeagent, scripted
// per ACP_SCENARIO to exercise exactly the ADR 0025 gate items / spec
// scenarios this file covers, and reports what it observed by appending
// newline-delimited JSON events to ACP_OUT_FILE for the parent test to read
// back after ACPClient.Close().

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// TestACPHelperProcess: the fake ACP agent, run as `go test -run
// TestACPHelperProcess` inside a subprocess spawned by LoadACP. It is a
// no-op under the normal `go test` run (GO_WANT_ACP_HELPER unset).
// ---------------------------------------------------------------------------

func TestACPHelperProcess(t *testing.T) {
	if os.Getenv("GO_WANT_ACP_HELPER") != "1" {
		return
	}
	runACPFakeServer()
	os.Exit(0)
}

type acpEvent struct {
	Type string         `json:"type"`
	Data map[string]any `json:"data,omitempty"`
}

func runACPFakeServer() {
	scenario := os.Getenv("ACP_SCENARIO")

	var outFile *os.File
	if p := os.Getenv("ACP_OUT_FILE"); p != "" {
		if f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
			outFile = f
			defer f.Close()
		}
	}
	var outFileMu sync.Mutex
	emit := func(ev acpEvent) {
		if outFile == nil {
			return
		}
		b, _ := json.Marshal(ev)
		outFileMu.Lock()
		outFile.Write(b)
		outFile.Write([]byte("\n"))
		outFileMu.Unlock()
	}

	out := bufio.NewWriter(os.Stdout)
	var outMu sync.Mutex
	send := func(v any) {
		b, err := json.Marshal(v)
		if err != nil {
			return
		}
		outMu.Lock()
		out.Write(b)
		out.Write([]byte("\n"))
		out.Flush()
		outMu.Unlock()
	}
	var nextReqID int64
	sendNotification := func(method string, params any) {
		p, _ := json.Marshal(params)
		send(acpMsg{JSONRPC: "2.0", Method: method, Params: p})
	}
	sendRequest := func(method string, params any) string {
		id := fmt.Sprintf("srv-%d", atomic.AddInt64(&nextReqID, 1))
		p, _ := json.Marshal(params)
		idRaw, _ := json.Marshal(id)
		send(acpMsg{JSONRPC: "2.0", ID: idRaw, Method: method, Params: p})
		return id
	}
	reply := func(id json.RawMessage, result any) {
		r, _ := json.Marshal(result)
		send(acpMsg{JSONRPC: "2.0", ID: id, Result: r})
	}
	replyErr := func(id json.RawMessage, code int, msg string) {
		send(acpMsg{JSONRPC: "2.0", ID: id, Error: &acpError{Code: code, Message: msg}})
	}

	var historyMu sync.Mutex
	var history []string
	var busy int32

	permReplies := make(chan acpMsg, 8)

	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)

	var wg sync.WaitGroup
	for sc.Scan() {
		line := sc.Text()
		if strings.TrimSpace(line) == "" {
			continue
		}
		var msg acpMsg
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			continue
		}

		// A reply to a server-initiated request (session/request_permission).
		if msg.Method == "" && len(msg.ID) > 0 {
			permReplies <- msg
			continue
		}

		switch msg.Method {
		case "initialize":
			reply(msg.ID, map[string]any{
				"protocolVersion":   1,
				"agentCapabilities": map[string]any{"loadSession": false},
			})

		case "session/new":
			var params map[string]any
			_ = json.Unmarshal(msg.Params, &params)
			emit(acpEvent{Type: "session/new", Data: params})
			reply(msg.ID, map[string]any{"sessionId": "sess-1"})

		case "session/set_mode":
			reply(msg.ID, map[string]any{})

		case "session/prompt":
			var params struct {
				SessionID string `json:"sessionId"`
				Prompt    []struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"prompt"`
			}
			_ = json.Unmarshal(msg.Params, &params)
			var text strings.Builder
			for _, p := range params.Prompt {
				text.WriteString(p.Text)
			}
			userText := text.String()
			emit(acpEvent{Type: "session/prompt", Data: map[string]any{"text": userText}})

			id, sid, ut := msg.ID, params.SessionID, userText
			switch scenario {
			case "stale":
				wg.Add(1)
				go func() {
					defer wg.Done()
					acpHandleStaleForTest(send, reply, &historyMu, &history, id, sid, ut)
				}()
			case "permission":
				wg.Add(1)
				go func() {
					defer wg.Done()
					acpHandlePermissionForTest(sendRequest, reply, emit, id, sid, ut, permReplies)
				}()
			case "noisy":
				wg.Add(1)
				go func() {
					defer wg.Done()
					acpHandleNoisyForTest(sendNotification, reply, id, sid, ut)
				}()
			case "serialize":
				if !atomic.CompareAndSwapInt32(&busy, 0, 1) {
					emit(acpEvent{Type: "violation"})
					replyErr(id, -32000, "reentrant session/prompt")
					continue
				}
				wg.Add(1)
				go func() {
					defer wg.Done()
					// Held long enough that a client which failed to
					// serialise would land a second session/prompt on top
					// of this one and trip the CompareAndSwap check above.
					time.Sleep(150 * time.Millisecond)
					sendNotification("session/update", map[string]any{
						"sessionId": sid,
						"update": map[string]any{
							"sessionUpdate": "agent_message_chunk",
							"content":       map[string]any{"type": "text", "text": "ok:" + ut},
						},
					})
					reply(id, map[string]any{"stopReason": "end_turn"})
					atomic.StoreInt32(&busy, 0)
				}()
			default: // warm
				sendNotification("session/update", map[string]any{
					"sessionId": sid,
					"update": map[string]any{
						"sessionUpdate": "agent_message_chunk",
						"content":       map[string]any{"type": "text", "text": "echo:" + ut},
					},
				})
				reply(id, map[string]any{"stopReason": "end_turn"})
			}

		case "session/cancel":
			reply(msg.ID, map[string]any{})

		default:
			if len(msg.ID) > 0 {
				reply(msg.ID, map[string]any{})
			}
		}
	}
	wg.Wait()
}

// acpHandleStaleForTest mirrors spikes/acp/fakeagent's handleStale: a naive
// stateful agent answers the FIRST history entry that is a substring of the
// current prompt, UNLESS the current prompt carries acpSupersedesMarker, in
// which case it answers only the text after the marker.
func acpHandleStaleForTest(send func(any), reply func(json.RawMessage, any), historyMu *sync.Mutex, history *[]string, id json.RawMessage, sessionID, userText string) {
	historyMu.Lock()
	*history = append(*history, userText)
	snapshot := append([]string(nil), *history...)
	historyMu.Unlock()

	var answer string
	if idx := strings.Index(userText, acpSupersedesMarker); idx >= 0 {
		fresh := strings.TrimSpace(userText[idx+len(acpSupersedesMarker):])
		answer = "FRESH-ANSWER-TO:" + fresh
	} else {
		matched := snapshot[0]
		for _, h := range snapshot {
			if strings.Contains(userText, h) {
				matched = h
				break
			}
		}
		answer = "STALE-ANSWER-TO:" + matched
	}
	send(acpMsg{JSONRPC: "2.0", Method: "session/update", Params: acpMustJSON(map[string]any{
		"sessionId": sessionID,
		"update": map[string]any{
			"sessionUpdate": "agent_message_chunk",
			"content":       map[string]any{"type": "text", "text": answer},
		},
	})})
	reply(id, map[string]any{"stopReason": "end_turn"})
}

func acpHandlePermissionForTest(sendRequest func(string, any) string, reply func(json.RawMessage, any), emit func(acpEvent), id json.RawMessage, sessionID, userText string, permReplies chan acpMsg) {
	reqID := sendRequest("session/request_permission", map[string]any{
		"sessionId": sessionID,
		"options": []any{
			map[string]any{"optionId": "reject", "kind": "reject_once", "name": "Reject"},
			map[string]any{"optionId": "allow-once", "kind": "allow_once", "name": "Allow"},
		},
	})
	for m := range permReplies {
		var idStr string
		_ = json.Unmarshal(m.ID, &idStr)
		if idStr == reqID {
			var res struct {
				Outcome struct {
					Outcome  string `json:"outcome"`
					OptionID string `json:"optionId"`
				} `json:"outcome"`
			}
			_ = json.Unmarshal(m.Result, &res)
			emit(acpEvent{Type: "permission-answer", Data: map[string]any{
				"outcome":  res.Outcome.Outcome,
				"optionId": res.Outcome.OptionID,
			}})
			break
		}
	}
	reply(id, map[string]any{"stopReason": "end_turn"})
	_ = userText
}

func acpHandleNoisyForTest(sendNotification func(string, any), reply func(json.RawMessage, any), id json.RawMessage, sessionID, userText string) {
	chunks := []map[string]any{
		{"sessionUpdate": "agent_thought_chunk", "content": map[string]any{"type": "text", "text": "Let me think about this... "}},
		{"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "reading files", "status": "in_progress"},
		{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": `{"answer":`}},
		{"sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "completed"},
		{"sessionUpdate": "agent_thought_chunk", "content": map[string]any{"type": "text", "text": "now double-checking... "}},
		{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": `true}`}},
	}
	for _, c := range chunks {
		sendNotification("session/update", map[string]any{"sessionId": sessionID, "update": c})
	}
	reply(id, map[string]any{"stopReason": "end_turn"})
	_ = userText
}

func acpMustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

// ---------------------------------------------------------------------------
// Parent-side test helpers.
// ---------------------------------------------------------------------------

func acpTestOptions(t *testing.T, scenario, outFile string) ACPOptions {
	t.Helper()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	env := []string{"GO_WANT_ACP_HELPER=1", "ACP_SCENARIO=" + scenario}
	if outFile != "" {
		env = append(env, "ACP_OUT_FILE="+outFile)
	}
	return ACPOptions{
		Command:           os.Args[0],
		Args:              []string{"-test.run=TestACPHelperProcess"},
		Env:               env,
		Cwd:               wd,
		PermissionTimeout: 3 * time.Second,
		RequestTimeout:    5 * time.Second,
	}
}

func readACPEvents(t *testing.T, path string) []acpEvent {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open events file: %v", err)
	}
	defer f.Close()
	var events []acpEvent
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var ev acpEvent
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			t.Fatalf("bad event line %q: %v", line, err)
		}
		events = append(events, ev)
	}
	return events
}

func acpUserMessage(text string) InProcessRequest {
	return InProcessRequest{Messages: []any{map[string]any{"role": "user", "content": text}}}
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

// 1. Warm session reuse: the agent process starts once, the session is
// created once, and each of several Generate calls is only a session/prompt.
func TestACP_WarmSessionReuse(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	for i := 0; i < 3; i++ {
		resp, err := c.Generate(acpUserMessage(fmt.Sprintf("turn %d", i)))
		if err != nil {
			t.Fatalf("Generate #%d: %v", i, err)
		}
		if !strings.Contains(resp.Content, "echo:") {
			t.Fatalf("Generate #%d: unexpected content %q", i, resp.Content)
		}
	}
	c.Close()

	events := readACPEvents(t, outFile)
	sessionNewCount, promptCount := 0, 0
	for _, ev := range events {
		switch ev.Type {
		case "session/new":
			sessionNewCount++
		case "session/prompt":
			promptCount++
		}
	}
	if sessionNewCount != 1 {
		t.Fatalf("expected exactly 1 session/new, got %d", sessionNewCount)
	}
	if promptCount != 3 {
		t.Fatalf("expected 3 session/prompt calls, got %d", promptCount)
	}
}

// `session/new` params carry an absolute cwd and a (possibly empty) mcpServers
// array — the exact trap real `devin acp` enforces (ADR 0025).
func TestACP_SessionNewCarriesAbsoluteCwdAndMcpServers(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	events := readACPEvents(t, outFile)
	var sessionNew *acpEvent
	for i := range events {
		if events[i].Type == "session/new" {
			sessionNew = &events[i]
			break
		}
	}
	if sessionNew == nil {
		t.Fatal("no session/new event observed")
	}
	cwd, _ := sessionNew.Data["cwd"].(string)
	if cwd == "" || !filepath.IsAbs(cwd) {
		t.Fatalf("session/new cwd not absolute: %q", cwd)
	}
	if _, ok := sessionNew.Data["mcpServers"]; !ok {
		t.Fatal("session/new params missing mcpServers key")
	}
}

// 2. Thought/narration filtered: only agent_message_chunk text is
// accumulated; interleaved agent_thought_chunk / tool_call narration is
// dropped, so the result still parses as valid JSON.
func TestACP_ThoughtAndToolNarrationFiltered(t *testing.T) {
	c, err := LoadACP(context.Background(), acpTestOptions(t, "noisy", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	resp, err := c.Generate(acpUserMessage("what is the answer"))
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if strings.Contains(resp.Content, "Let me think") || strings.Contains(resp.Content, "double-checking") {
		t.Fatalf("thought chunks leaked into content: %q", resp.Content)
	}
	var parsed struct {
		Answer bool `json:"answer"`
	}
	if err := json.Unmarshal([]byte(resp.Content), &parsed); err != nil {
		t.Fatalf("content did not parse as JSON (%q): %v", resp.Content, err)
	}
	if !parsed.Answer {
		t.Fatalf("expected answer:true, got content %q", resp.Content)
	}
}

// 3. A permission request is answered (first allow-kind option) rather than
// awaited: the turn completes quickly, and the server observed the answer.
func TestACP_PermissionAnsweredNotAwaited(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	opts := acpTestOptions(t, "permission", outFile)
	opts.PermissionTimeout = 3 * time.Second // the safety net; the real path finishes well under this
	c, err := LoadACP(context.Background(), opts)
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	start := time.Now()
	resp, err := c.Generate(acpUserMessage("do the side-effecting thing"))
	elapsed := time.Since(start)
	if err != nil {
		t.Fatalf("Generate: %v", err)
	}
	if elapsed > 1*time.Second {
		t.Fatalf("turn took %s — looks like it waited on the safety-net timeout instead of an immediate auto-answer", elapsed)
	}
	_ = resp

	events := readACPEvents(t, outFile)
	var answered bool
	for _, ev := range events {
		if ev.Type == "permission-answer" {
			answered = true
			if outcome, _ := ev.Data["outcome"].(string); outcome != "selected" {
				t.Fatalf("expected outcome selected, got %v", ev.Data["outcome"])
			}
			if optID, _ := ev.Data["optionId"].(string); optID != "allow-once" {
				t.Fatalf("expected first allow-kind option 'allow-once', got %v", ev.Data["optionId"])
			}
		}
	}
	if !answered {
		t.Fatal("fake server never observed a permission answer")
	}
}

// 4. The supersedes marker prevents a stale answer: a second Generate call's
// content reflects the NEW turn, not a near-duplicate answer keyed off the
// first turn still sitting in the (stateful) session's own history.
func TestACP_SupersedesMarkerPreventsStaleAnswer(t *testing.T) {
	c, err := LoadACP(context.Background(), acpTestOptions(t, "stale", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	resp1, err := c.Generate(acpUserMessage("what color is the sky"))
	if err != nil {
		t.Fatalf("Generate #1: %v", err)
	}
	if !strings.HasPrefix(resp1.Content, "FRESH-ANSWER-TO:") || !strings.Contains(resp1.Content, "sky") {
		t.Fatalf("turn 1: unexpected content %q", resp1.Content)
	}

	// Turn 2's FULL assembled request (by construction of InProcessRequest
	// here) does not literally repeat turn 1's text, but the point under
	// test is the marker: the fake server keys its answer off whatever
	// follows acpSupersedesMarker, so it must be turn 2's own text.
	resp2, err := c.Generate(acpUserMessage("what color is grass"))
	if err != nil {
		t.Fatalf("Generate #2: %v", err)
	}
	if !strings.HasPrefix(resp2.Content, "FRESH-ANSWER-TO:") {
		t.Fatalf("turn 2: expected a FRESH answer via the supersedes marker, got %q", resp2.Content)
	}
	if !strings.Contains(resp2.Content, "grass") || strings.Contains(resp2.Content, "sky") {
		t.Fatalf("turn 2: answered from stale turn-1 history instead of the new turn: %q", resp2.Content)
	}
}

// 5. Turns on one session are serialised: two concurrent Generate calls must
// not let the fake server see two session/prompt calls in flight at once.
func TestACP_TurnsSerialised(t *testing.T) {
	outFile := filepath.Join(t.TempDir(), "events.ndjson")
	c, err := LoadACP(context.Background(), acpTestOptions(t, "serialize", outFile))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	var wg sync.WaitGroup
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			resp, err := c.Generate(acpUserMessage(fmt.Sprintf("concurrent turn %d", i)))
			if err != nil {
				errs <- err
				return
			}
			if !strings.HasPrefix(resp.Content, "ok:") {
				errs <- fmt.Errorf("turn %d: unexpected content %q", i, resp.Content)
			}
		}(i)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("concurrent Generate failed: %v", err)
	}
	c.Close()

	for _, ev := range readACPEvents(t, outFile) {
		if ev.Type == "violation" {
			t.Fatal("fake server observed a reentrant session/prompt — turns were not serialised")
		}
	}
}

// 6. Close is idempotent: calling it twice must not panic or hang.
func TestACP_CloseIdempotent(t *testing.T) {
	c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	if _, err := c.Generate(acpUserMessage("hello")); err != nil {
		t.Fatalf("Generate: %v", err)
	}

	done := make(chan struct{})
	go func() {
		_ = c.Close()
		_ = c.Close()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Close() (called twice) did not return — looks hung")
	}
}

// A turn's own cancellation (a failed/errored Generate call) must not make
// the process unusable for subsequent turns.
func TestACP_ProcessOutlivesATurn(t *testing.T) {
	c, err := LoadACP(context.Background(), acpTestOptions(t, "warm", ""))
	if err != nil {
		t.Fatalf("LoadACP: %v", err)
	}
	defer c.Close()

	if _, err := c.Generate(acpUserMessage("first")); err != nil {
		t.Fatalf("Generate #1: %v", err)
	}
	// A second, independent turn on the same warm client must still work.
	resp, err := c.Generate(acpUserMessage("second"))
	if err != nil {
		t.Fatalf("Generate #2 (process should still be usable): %v", err)
	}
	if !strings.Contains(resp.Content, "second") {
		t.Fatalf("unexpected content: %q", resp.Content)
	}
}
