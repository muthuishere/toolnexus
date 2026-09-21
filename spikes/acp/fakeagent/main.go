// Command fakeagent is a minimal, scripted ACP (Agent Client Protocol) server.
// It speaks JSON-RPC 2.0, one object per line, over its own stdin/stdout —
// exactly the shape a real ACP agent (devin acp, gemini, zed agents) uses.
//
// It is NOT a general ACP implementation. It is a deterministic script,
// selected with -scenario, built to exercise one failure mode each:
//
//	stale      - stateful session; answers a near-duplicate prompt with a
//	             STALE answer unless the new prompt carries a "supersedes"
//	             marker, in which case it answers fresh.
//	hang       - never answers session/request_permission (spike drives its
//	             own timeout; the point is the fake agent does nothing).
//	permission - like hang, but DOES send session/request_permission and
//	             waits for the client's response before finishing the turn.
//	noisy      - emits agent_thought_chunk + tool-call narration BEFORE the
//	             real agent_message_chunk, to prove naive accumulation
//	             corrupts structured output.
//	warm       - trivial instant echo, used only to measure per-call
//	             overhead of a warm session vs a cold process launch.
//
// Protocol subset implemented: initialize, session/new, session/prompt
// (request/response) and session/update, session/request_permission
// (server->client notifications/requests interleaved on the SAME stream).
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
	"sync"
	"sync/atomic"
)

type rpcMsg struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

var (
	out       = bufio.NewWriter(os.Stdout)
	outMu     sync.Mutex
	nextReqID int64
)

// send is called from both the main read loop and per-prompt goroutines
// (session/prompt handling runs concurrently so the main loop can keep
// reading stdin — see the "permission" scenario, which needs to read the
// client's reply to session/request_permission WHILE a prompt handler is
// still in flight). outMu keeps each JSON line atomic on the wire.
func send(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	outMu.Lock()
	defer outMu.Unlock()
	out.Write(b)
	out.WriteByte('\n')
	out.Flush()
}

// sendNotification writes a server->client notification (no id).
func sendNotification(method string, params any) {
	p, _ := json.Marshal(params)
	send(rpcMsg{JSONRPC: "2.0", Method: method, Params: p})
}

// sendRequest writes a server->client REQUEST (has an id, expects a reply on
// the same stream) and returns the id used, so the caller can match the
// reply when it arrives on stdin.
func sendRequest(method string, params any) string {
	id := fmt.Sprintf("srv-%d", atomic.AddInt64(&nextReqID, 1))
	p, _ := json.Marshal(params)
	idRaw, _ := json.Marshal(id)
	send(rpcMsg{JSONRPC: "2.0", ID: idRaw, Method: method, Params: p})
	return id
}

func reply(id json.RawMessage, result any) {
	r, _ := json.Marshal(result)
	send(rpcMsg{JSONRPC: "2.0", ID: id, Result: r})
}

func main() {
	scenario := flag.String("scenario", "warm", "stale|hang|permission|noisy|warm")
	flag.Parse()

	// session state, deliberately tiny and stateful — this IS the thing
	// under test: the fake agent remembers prior prompts in this turn's
	// session, the same way a real ACP agent's session/prompt does.
	var historyMu sync.Mutex
	var history []string
	promptN := 0

	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)

	// pendingPermissionReplies buffers replies to server-initiated
	// session/request_permission calls, keyed by id, so the goroutine-free
	// single-threaded read loop below can pick them up out of band.
	permReplies := make(chan rpcMsg, 8)

	for sc.Scan() {
		line := sc.Text()
		if strings.TrimSpace(line) == "" {
			continue
		}
		var msg rpcMsg
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			continue
		}

		// A reply to a server-initiated request (session/request_permission)
		// arrives with an id we generated and NO method field.
		if msg.Method == "" && len(msg.ID) > 0 {
			permReplies <- msg
			continue
		}

		switch msg.Method {
		case "initialize":
			reply(msg.ID, map[string]any{
				"protocolVersion": 1,
				"agentCapabilities": map[string]any{
					"loadSession": false,
				},
			})

		case "session/new":
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
			promptN++
			historyMu.Lock()
			history = append(history, userText)
			historySnapshot := append([]string(nil), history...)
			historyMu.Unlock()

			// Handled in a goroutine so the main read loop keeps scanning
			// stdin — required by the "permission" scenario, which needs to
			// receive the client's reply to session/request_permission
			// WHILE this prompt's handler is still waiting on it.
			id, sid, ut, hist := msg.ID, params.SessionID, userText, historySnapshot
			switch *scenario {
			case "stale":
				go handleStale(id, ut, hist)
			case "hang":
				// Ask for permission and NEVER answer it ourselves, and the
				// spike client under test also does not answer -> turn hangs.
				sendRequest("session/request_permission", map[string]any{
					"sessionId": sid,
					"options": []any{
						map[string]any{"optionId": "allow-once", "kind": "allow_once", "name": "Allow"},
					},
				})
				// Deliberately never reply to session/prompt: the real bug is
				// the TURN hangs, which is a client-side wait, so the fake
				// server just goes silent after asking permission.
			case "permission":
				go handlePermission(id, sid, ut, permReplies)
			case "noisy":
				go handleNoisy(id, sid, ut)
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
}

// handleStale is the heart of gate item 1. The fake agent's "understanding"
// of what to answer is keyed off the LAST prompt it received UNLESS the new
// prompt is flagged as fresh. It reproduces the failure toolnexus assembling
// a full request every turn would hit: the agent has now seen prompt#1 AND
// prompt#2 (full-history, near-duplicate) in one session, and a naive agent
// keys its answer off substring containment / the earliest match — exactly
// the class of bug the ADR describes ("answering a stale copy").
//
// Detection rule (deliberately simple, mirrors a plausible real agent):
// the agent answers the FIRST prompt in history whose content is a PREFIX
// of the current full-request text, UNLESS the current prompt contains the
// literal marker "SUPERSEDES-ALL-PRIOR", in which case it answers only the
// text after the marker.
func handleStale(id json.RawMessage, userText string, history []string) {
	const marker = "SUPERSEDES-ALL-PRIOR:"
	var answer string
	if idx := strings.Index(userText, marker); idx >= 0 {
		fresh := strings.TrimSpace(userText[idx+len(marker):])
		answer = "FRESH-ANSWER-TO:" + fresh
	} else {
		// naive stateful agent: scan history oldest-first, answer the FIRST
		// one whose question text appears in the current prompt (the classic
		// symptom of a session that never forgets and pattern-matches against
		// its whole memory rather than "the newest thing you said").
		matched := history[0]
		for _, h := range history {
			if strings.Contains(userText, h) {
				matched = h
				break
			}
		}
		answer = "STALE-ANSWER-TO:" + matched
	}
	sendNotification("session/update", map[string]any{
		"sessionId": "sess-1",
		"update": map[string]any{
			"sessionUpdate": "agent_message_chunk",
			"content":       map[string]any{"type": "text", "text": answer},
		},
	})
	reply(id, map[string]any{"stopReason": "end_turn"})
}

func handlePermission(id json.RawMessage, sessionID, userText string, permReplies chan rpcMsg) {
	reqID := sendRequest("session/request_permission", map[string]any{
		"sessionId": sessionID,
		"options": []any{
			map[string]any{"optionId": "reject", "kind": "reject_once", "name": "Reject"},
			map[string]any{"optionId": "allow-once", "kind": "allow_once", "name": "Allow"},
		},
	})
	// Block until the client answers (or the test's own timeout kills the
	// process). This models a real agent that will not proceed with a
	// side-effecting tool call until permission is granted.
	for m := range permReplies {
		var idStr string
		_ = json.Unmarshal(m.ID, &idStr)
		if idStr == reqID {
			break
		}
	}
	sendNotification("session/update", map[string]any{
		"sessionId": sessionID,
		"update": map[string]any{
			"sessionUpdate": "agent_message_chunk",
			"content":       map[string]any{"type": "text", "text": "PERMITTED:" + userText},
		},
	})
	reply(id, map[string]any{"stopReason": "end_turn"})
}

// handleNoisy emits thought + tool-narration chunks around ONE clean
// agent_message_chunk, to prove naive "accumulate every chunk" corrupts
// structured output (the client is expected to build valid JSON from the
// answer) while filtering to agent_message_chunk only stays clean.
func handleNoisy(id json.RawMessage, sessionID, userText string) {
	chunks := []map[string]any{
		{"sessionUpdate": "agent_thought_chunk", "content": map[string]any{"type": "text", "text": "Let me think about this... "}},
		{"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "reading files", "status": "in_progress"},
		{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": `{"answer":`}},
		{"sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "completed"},
		{"sessionUpdate": "agent_thought_chunk", "content": map[string]any{"type": "text", "text": "now double-checking the number... "}},
		{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": `"` + userText + `"}`}},
	}
	for _, c := range chunks {
		sendNotification("session/update", map[string]any{
			"sessionId": sessionID,
			"update":    c,
		})
	}
	reply(id, map[string]any{"stopReason": "end_turn"})
}
