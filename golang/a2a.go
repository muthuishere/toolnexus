// A2A (agent-to-agent) — OUTBOUND: call remote A2A agents.
//
// A remote agent publishes an Agent Card at `/.well-known/agent-card.json`
// describing its skills. Each advertised skill becomes a uniform Tool
// (source:"a2a") whose Execute performs one JSON-RPC SendMessage (non-blocking)
// to get a Task id, then polls GetTask until the Task reaches a terminal state —
// mapping the Task's artifact/message text to a ToolResult.
//
// This is a genuine subset of real A2A (JSON-RPC 2.0 over one HTTP POST
// endpoint), so a toolnexus agent interoperates with real A2A peers. See
// ../SPEC.md §7A and openspec/changes/add-a2a-agents. Reuses the ${ENV} header
// expansion from http.go; secrets live in the environment and are never logged.

package toolnexus

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

const (
	a2aDefaultTimeout   = 300_000 * time.Millisecond
	a2aDefaultPollEvery = 1_000 * time.Millisecond
)

// terminal reports whether a task state stops the poll loop.
func terminal(state string) bool {
	return state == "completed" || state == "failed" || state == "canceled"
}

// ---------------------------------------------------------------------------
// Wire types (the A2A subset we speak — verified against a2a-python).
// ---------------------------------------------------------------------------

// A2APart is one content part of a message or artifact. Text is a pointer so a
// missing `text` (nil) is distinguished from an explicit empty string.
type A2APart struct {
	Kind string  `json:"kind"`
	Text *string `json:"text"`
}

// A2AMessage is a role-tagged list of parts.
type A2AMessage struct {
	Role      string    `json:"role"`
	MessageID string    `json:"messageId,omitempty"`
	Parts     []A2APart `json:"parts,omitempty"`
}

// A2AArtifact is a task output artifact.
type A2AArtifact struct {
	// ArtifactID identifies the artifact (set on served completed tasks).
	ArtifactID string    `json:"artifactId,omitempty"`
	Parts      []A2APart `json:"parts,omitempty"`
}

// A2ATaskStatus carries the task state plus an optional status message.
type A2ATaskStatus struct {
	State   string      `json:"state"`
	Message *A2AMessage `json:"message,omitempty"`
}

// A2ATask is the unit of work returned by SendMessage / GetTask.
type A2ATask struct {
	ID string `json:"id"`
	// ContextID groups a peer's turns into one conversation (A2A contextId).
	ContextID string        `json:"contextId,omitempty"`
	Status    A2ATaskStatus `json:"status"`
	Artifacts []A2AArtifact `json:"artifacts,omitempty"`
	History   []A2AMessage  `json:"history,omitempty"`
}

// A2ASkill is one advertised skill from an Agent Card.
type A2ASkill struct {
	ID          string   `json:"id,omitempty"`
	Name        string   `json:"name,omitempty"`
	Description string   `json:"description,omitempty"`
	Tags        []string `json:"tags,omitempty"`
	Examples    []string `json:"examples,omitempty"`
}

// AgentCard is the descriptor published at /.well-known/agent-card.json.
type AgentCard struct {
	Name               string         `json:"name"`
	Description        string         `json:"description,omitempty"`
	Version            string         `json:"version,omitempty"`
	ProtocolVersion    string         `json:"protocolVersion,omitempty"`
	Capabilities       map[string]any `json:"capabilities,omitempty"`
	DefaultInputModes  []string       `json:"defaultInputModes,omitempty"`
	DefaultOutputModes []string       `json:"defaultOutputModes,omitempty"`
	Skills             []A2ASkill     `json:"skills,omitempty"`
	// URL is the JSON-RPC endpoint the agent listens on.
	URL string `json:"url,omitempty"`
}

// ---------------------------------------------------------------------------
// Agent descriptor.
// ---------------------------------------------------------------------------

// Agent points at a remote Agent Card. Headers support ${ENV_VAR} expansion at
// call time and are never logged.
type Agent struct {
	// Card is the URL of the Agent Card (/.well-known/agent-card.json).
	Card string `json:"card"`
	// Headers are static request headers; ${ENV_VAR} values expand at call time.
	Headers map[string]string `json:"headers,omitempty"`
	// Timeout is the overall poll budget in ms (default 300000).
	Timeout int `json:"timeout,omitempty"`
	// PollEvery is the interval between GetTask polls in ms (default 1000).
	PollEvery int `json:"pollEvery,omitempty"`
}

func (ag Agent) timeout() time.Duration {
	if ag.Timeout > 0 {
		return time.Duration(ag.Timeout) * time.Millisecond
	}
	return a2aDefaultTimeout
}

func (ag Agent) pollEvery() time.Duration {
	if ag.PollEvery > 0 {
		return time.Duration(ag.PollEvery) * time.Millisecond
	}
	return a2aDefaultPollEvery
}

// ---------------------------------------------------------------------------
// Config parsing — an `agents` block mirroring `mcpServers` (§2).
// ---------------------------------------------------------------------------

// AgentConfig is a single entry in a config-file `agents` block.
type AgentConfig struct {
	Card      string            `json:"card"`
	Headers   map[string]string `json:"headers"`
	Timeout   int               `json:"timeout"`
	PollEvery int               `json:"pollEvery"`
	Enabled   *bool             `json:"enabled"`
	Disabled  *bool             `json:"disabled"`
}

// AgentsConfig maps an identifier -> AgentConfig.
type AgentsConfig map[string]AgentConfig

// agentConfigEnabled applies MCP isEnabled precedence: disabled:true wins, then
// enabled:false.
func agentConfigEnabled(cfg AgentConfig) bool {
	if cfg.Disabled != nil && *cfg.Disabled {
		return false
	}
	if cfg.Enabled != nil && !*cfg.Enabled {
		return false
	}
	return true
}

// ParseAgentsConfig turns an `agents` block into Agent descriptors, skipping
// disabled entries. The config key is only an identifier — a tool's name prefix
// comes from the fetched card's name, not the key.
func ParseAgentsConfig(block AgentsConfig) []Agent {
	if block == nil {
		return nil
	}
	var out []Agent
	for _, cfg := range block {
		if cfg.Card == "" {
			continue
		}
		if !agentConfigEnabled(cfg) {
			continue
		}
		out = append(out, Agent{
			Card:      cfg.Card,
			Headers:   cfg.Headers,
			Timeout:   cfg.Timeout,
			PollEvery: cfg.PollEvery,
		})
	}
	return out
}

// ---------------------------------------------------------------------------
// JSON-RPC transport (single POST) + card fetch. Reuses http.go's ${ENV} header
// expansion, context timeout, and non-2xx → error mapping.
// ---------------------------------------------------------------------------

// jsonRPC POSTs one JSON-RPC 2.0 request and returns the raw `result` (error on
// error/non-2xx). budget bounds this single request.
func jsonRPC(parent context.Context, endpoint, method string, params any, headers map[string]string, budget time.Duration) (json.RawMessage, error) {
	ctx, cancel := context.WithTimeout(parent, budget)
	defer cancel()

	envelope := map[string]any{
		"jsonrpc": "2.0",
		"id":      uuid.NewString(),
		"method":  method,
		"params":  params,
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(raw))
	}
	// safeParse: a body that is not valid JSON yields a nil result (no throw).
	var payload struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
			Code    *int   `json:"code"`
		} `json:"error"`
	}
	_ = json.Unmarshal(raw, &payload)
	if payload.Error != nil {
		msg := payload.Error.Message
		if msg == "" {
			code := ""
			if payload.Error.Code != nil {
				code = strconv.Itoa(*payload.Error.Code)
			}
			msg = strings.TrimSpace("JSON-RPC error " + code)
		}
		return nil, errors.New(msg)
	}
	return payload.Result, nil
}

// fetchCard GETs and parses the Agent Card at its URL.
func fetchCard(parent context.Context, cardURL string, headers map[string]string, budget time.Duration) (*AgentCard, error) {
	ctx, cancel := context.WithTimeout(parent, budget)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, cardURL, nil)
	if err != nil {
		return nil, err
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(raw))
	}
	var card AgentCard
	if err := json.Unmarshal(raw, &card); err != nil {
		return nil, err
	}
	return &card, nil
}

// aborted reports whether the context is already done (cancel/timeout).
func aborted(ctx context.Context) bool {
	if ctx == nil {
		return false
	}
	select {
	case <-ctx.Done():
		return true
	default:
		return false
	}
}

// sleepCtx sleeps for d, returning early (without error) if ctx is done.
func sleepCtx(ctx context.Context, d time.Duration) {
	if ctx == nil {
		time.Sleep(d)
		return
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

// extractOutput concatenates a task's text output: artifact text parts, else the
// last agent message.
func extractOutput(task A2ATask) string {
	var parts []string
	for _, artifact := range task.Artifacts {
		for _, p := range artifact.Parts {
			if p.Kind == "text" && p.Text != nil {
				parts = append(parts, *p.Text)
			}
		}
	}
	if len(parts) > 0 {
		return strings.Join(parts, "\n")
	}
	// Fallback: the last agent message in history.
	for i := len(task.History) - 1; i >= 0; i-- {
		msg := task.History[i]
		if msg.Role != "agent" {
			continue
		}
		var texts []string
		for _, p := range msg.Parts {
			if p.Kind == "text" && p.Text != nil {
				texts = append(texts, *p.Text)
			}
		}
		if text := strings.Join(texts, "\n"); text != "" {
			return text
		}
	}
	return ""
}

// statusMessageText returns the text of a task's status.message (used for
// failed/canceled error output).
func statusMessageText(task A2ATask) string {
	msg := task.Status.Message
	if msg == nil {
		return ""
	}
	var texts []string
	for _, p := range msg.Parts {
		if p.Kind == "text" && p.Text != nil {
			texts = append(texts, *p.Text)
		}
	}
	return strings.Join(texts, "\n")
}

// originOf returns scheme://host for a URL (the card origin fallback endpoint).
func originOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return raw
	}
	return u.Scheme + "://" + u.Host
}

// ---------------------------------------------------------------------------
// Skill → Tool.
// ---------------------------------------------------------------------------

// taskSchema is the fixed input schema every A2A tool advertises.
func taskSchema() JSONSchema {
	return JSONSchema{
		"type": "object",
		"properties": map[string]any{
			"task": map[string]any{
				"type":        "string",
				"description": "The task to send to the agent, in natural language.",
			},
		},
		"required":             []string{"task"},
		"additionalProperties": false,
	}
}

// skillTool builds one source:"a2a" Tool for a single advertised skill.
func skillTool(agentName, endpoint string, skill A2ASkill, ag Agent) Tool {
	skillID := skill.ID
	if skillID == "" {
		skillID = skill.Name
	}
	name := Sanitize(agentName) + "_" + Sanitize(skillID)
	description := skill.Description
	if description == "" {
		description = skill.Name
	}
	if description == "" {
		description = skillID
	}
	defTimeout := ag.timeout()
	pollEvery := ag.pollEvery()

	return Tool{
		Name:        name,
		Description: description,
		InputSchema: taskSchema(),
		Source:      SourceA2A,
		Execute: func(args map[string]any, tctx *ToolContext) (result ToolResult, _ error) {
			start := time.Now()
			budget := defTimeout
			parent := context.Background()
			if tctx != nil {
				if tctx.Timeout > 0 {
					budget = time.Duration(tctx.Timeout) * time.Millisecond
				}
				if tctx.Ctx != nil {
					parent = tctx.Ctx
				}
			}
			budgetMs := int(budget / time.Millisecond)
			headers := expandHeaders(ag.Headers)
			taskText := toStr(args["task"])

			polls := 0
			taskID := ""
			state := "submitted"
			meta := func() map[string]any {
				return map[string]any{
					"agent":  agentName,
					"taskId": taskID,
					"state":  state,
					"polls":  polls,
					"ms":     int(time.Since(start).Milliseconds()),
				}
			}
			// recover() guard: no panic ever crosses the tool boundary.
			defer func() {
				if r := recover(); r != nil {
					result = ToolResult{Output: fmt.Sprintf("%v", r), IsError: true, Metadata: meta()}
				}
			}()

			// 1. SendMessage (non-blocking) → a submitted Task.
			sendParams := map[string]any{
				"message": map[string]any{
					"role":      "user",
					"messageId": uuid.NewString(),
					"parts":     []any{map[string]any{"kind": "text", "text": taskText}},
				},
				"configuration": map[string]any{"blocking": false},
			}
			raw, err := jsonRPC(parent, endpoint, "SendMessage", sendParams, headers, budget)
			if err != nil {
				return ToolResult{Output: err.Error(), IsError: true, Metadata: meta()}, nil
			}
			var task A2ATask
			_ = json.Unmarshal(raw, &task)
			taskID = task.ID
			if task.Status.State != "" {
				state = task.Status.State
			}

			// 2. Poll GetTask until terminal / timeout / cancel.
			for !terminal(state) {
				if aborted(parent) {
					state = "canceled"
					return ToolResult{Output: fmt.Sprintf("A2A task %s canceled", taskID), IsError: true, Metadata: meta()}, nil
				}
				if time.Since(start) >= budget {
					return ToolResult{
						Output:   fmt.Sprintf("A2A task %s timed out after %dms (state=%s)", taskID, budgetMs, state),
						IsError:  true,
						Metadata: meta(),
					}, nil
				}
				sleepCtx(parent, pollEvery)
				// Cancelled during the wait → stop before another GetTask.
				if aborted(parent) {
					state = "canceled"
					return ToolResult{Output: fmt.Sprintf("A2A task %s canceled", taskID), IsError: true, Metadata: meta()}, nil
				}
				raw, err := jsonRPC(parent, endpoint, "GetTask", map[string]any{"id": taskID}, headers, budget)
				if err != nil {
					// A cancellation that lands mid-request is still a cancel, not
					// a transport error (§7A: ctx abort ⇒ "A2A task <id> canceled").
					if aborted(parent) {
						state = "canceled"
						return ToolResult{Output: fmt.Sprintf("A2A task %s canceled", taskID), IsError: true, Metadata: meta()}, nil
					}
					return ToolResult{Output: err.Error(), IsError: true, Metadata: meta()}, nil
				}
				var next A2ATask
				_ = json.Unmarshal(raw, &next)
				task = next
				polls++
				if next.Status.State != "" {
					state = next.Status.State
				}
			}

			// 3. Map the terminal Task → ToolResult.
			if state == "completed" {
				return ToolResult{Output: extractOutput(task), IsError: false, Metadata: meta()}, nil
			}
			detail := statusMessageText(task)
			out := fmt.Sprintf("A2A task %s %s", taskID, state)
			if detail != "" {
				out += ": " + detail
			}
			return ToolResult{Output: out, IsError: true, Metadata: meta()}, nil
		},
	}
}

// AgentTools resolves an Agent to its tools: fetch the card, read skills[], and
// produce one Tool per skill. The agent name prefix comes from the card's name.
func AgentTools(ctx context.Context, ag Agent) ([]Tool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	timeout := ag.timeout()
	headers := expandHeaders(ag.Headers)
	card, err := fetchCard(ctx, ag.Card, headers, timeout)
	if err != nil {
		return nil, err
	}
	agentName := card.Name
	if agentName == "" {
		agentName = "agent"
	}
	// The card's url is the JSON-RPC endpoint; fall back to the card origin.
	endpoint := card.URL
	if endpoint == "" {
		endpoint = originOf(ag.Card)
	}
	tools := make([]Tool, 0, len(card.Skills))
	for _, skill := range card.Skills {
		tools = append(tools, skillTool(agentName, endpoint, skill, ag))
	}
	return tools, nil
}
