package toolnexus

// Relay (declaration-only) tools — SPEC.md §10, ADR-0010.
//
// A relay tool carries a schema but no host-side behavior: the model emits a call, the
// call is surfaced to the host as a §10 suspension, the HOST executes it, and the host's
// output is fed back to the model as that call's tool_result. Nothing runs here. This is
// what lets toolnexus act as a pure translator (a proxy) while still supporting standard
// OpenAI function calling.
//
// Relay is a *use* of the suspension primitive, not a second mechanism — see
// SPEC.md:1248 ("there is no auth subsystem; there is a suspend/resume primitive").

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
)

// RelayKind is the reserved Request.Kind for a relayed tool call (§10). Pinned across
// all ports — it travels on the wire.
const RelayKind = "tool_call"

// Relay data keys, pinned across all ports (they serialize and cross agent boundaries).
const (
	// relayCallsKey is the Request.Data key holding the turn's relayed calls.
	relayCallsKey = "calls"
	// RelayOutputKey is the Answer.Data key holding the host's tool output.
	RelayOutputKey = "output"
	// RelayIsErrorKey is the Answer.Data key marking the host's tool as failed.
	RelayIsErrorKey = "isError"
)

// RelayCall is one relayed tool call as it appears in Request.Data["calls"]. Keys are
// pinned: "id", "name", "input", "arguments".
type RelayCall struct {
	// ID is the provider's tool-call id — the correlation key for the tool_result.
	ID string `json:"id"`
	// Name is the tool name the model called.
	Name string `json:"name"`
	// Input is the parsed arguments object.
	Input map[string]any `json:"input"`
	// Arguments is the raw arguments JSON string, so an OpenAI-shaped caller can echo
	// it back byte-for-byte.
	Arguments string `json:"arguments"`
}

// RelayTool returns a declaration-only tool: it is never executed here. When the model
// calls it, the run suspends with a Request of kind "tool_call" carrying the call, and
// the host supplies the result via Answer.Data["output"] (with Answer.Data["isError"]
// set when the host's tool failed). Mirrors js relayTool().
//
// Use it when toolnexus is a translator and the CALLER executes tools — the standard
// OpenAI function-calling flow. Builtins stay off in that posture; a relay tool whose
// name collides with a builtin's is rejected at toolkit construction (unconditionally,
// even when builtins are disabled) so a future builtin cannot silently capture it.
func RelayTool(name, description string, schema JSONSchema) Tool {
	if schema == nil {
		schema = JSONSchema{"type": "object", "properties": map[string]any{}}
	}
	return Tool{
		Name:        name,
		Description: description,
		InputSchema: schema,
		Source:      SourceRelay,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			// Post-resume retry: the host executed the call; hand its output back.
			if tctx != nil && tctx.Answer != nil {
				return relayResultFromAnswer(name, tctx.Answer), nil
			}
			// First call: suspend, carrying this call. The loop merges every relay call
			// of the turn into the single surfaced Request (see mergeRelayCalls).
			if args == nil {
				args = map[string]any{}
			}
			raw, _ := json.Marshal(args)
			return Pending(Request{
				Kind:   RelayKind,
				Prompt: "relay tool call " + name + " to the caller for execution",
				Data: map[string]any{
					relayCallsKey: []any{map[string]any{
						"id":        "", // stamped by the loop, which owns the provider id
						"name":      name,
						"input":     args,
						"arguments": string(raw),
					}},
				},
			}), nil
		},
	}
}

// relayResultFromAnswer converts the host's Answer into the tool_result the model sees.
// A host-side tool failure is an error result (the model recovers); a declined relay is
// likewise an error result — neither aborts the run (§10, ADR-0010 D6).
func relayResultFromAnswer(name string, a *Answer) ToolResult {
	if !a.Ok {
		reason := a.Reason
		if reason == "" {
			reason = "declined"
		}
		return ToolResult{Output: "relay declined for " + name + ": " + reason, IsError: true}
	}
	out, _ := a.Data[RelayOutputKey].(string)
	isErr, _ := a.Data[RelayIsErrorKey].(bool)
	return ToolResult{Output: out, IsError: isErr}
}

// IsRelayRequest reports whether a Request is a relayed tool call (§10).
func IsRelayRequest(req *Request) bool {
	return req != nil && req.Kind == RelayKind
}

// RelayCallsOf reads the relayed calls off a Request, in tool-call order. Returns nil
// when the Request is not a relay request. Mirrors js relayCallsOf().
func RelayCallsOf(req *Request) []RelayCall {
	if !IsRelayRequest(req) || req.Data == nil {
		return nil
	}
	raw, ok := req.Data[relayCallsKey].([]any)
	if !ok {
		return nil
	}
	out := make([]RelayCall, 0, len(raw))
	for _, e := range raw {
		m, ok := e.(map[string]any)
		if !ok {
			continue
		}
		id, _ := m["id"].(string)
		name, _ := m["name"].(string)
		args, _ := m["arguments"].(string)
		in, _ := m["input"].(map[string]any)
		out = append(out, RelayCall{ID: id, Name: name, Input: in, Arguments: args})
	}
	return out
}

// stampRelayCallID sets the provider tool-call id on a single-call relay suspension. The
// tool cannot know its own call id; the loop does, so the loop stamps it.
func stampRelayCallID(req *Request, id string) {
	if !IsRelayRequest(req) || req.Data == nil {
		return
	}
	calls, ok := req.Data[relayCallsKey].([]any)
	if !ok {
		return
	}
	for _, e := range calls {
		if m, ok := e.(map[string]any); ok {
			if cur, _ := m["id"].(string); cur == "" {
				m["id"] = id
			}
		}
	}
}

// mergeRelayCalls folds every relay suspension raised in one assistant turn into the
// FIRST one, so a single surfaced Request carries all N calls in tool-call order
// (ADR-0010 F2-a). §10's first-in-order halt rule is untouched: the run still halts on
// the first suspension; that suspension simply carries the whole turn.
//
// halted is indexed by tool-call position, with nil for calls that did not suspend.
// Returns the index of the first relay suspension, or -1 when there is none.
func mergeRelayCalls(halted []*Request) int {
	first := -1
	for i, r := range halted {
		if !IsRelayRequest(r) {
			continue
		}
		if first < 0 {
			first = i
			continue
		}
		calls, ok := r.Data[relayCallsKey].([]any)
		if !ok {
			continue
		}
		existing, _ := halted[first].Data[relayCallsKey].([]any)
		halted[first].Data[relayCallsKey] = append(existing, calls...)
	}
	return first
}

// ---- collision guard (ADR-0010 D5) ----

// builtinToolNames is the set of built-in tool names, used by the relay collision guard.
// Computed from CreateBuiltinTools so it cannot drift from the real builtin set.
func builtinToolNames() map[string]bool {
	out := map[string]bool{}
	for _, t := range CreateBuiltinTools() {
		out[t.Name] = true
	}
	return out
}

// checkRelayCollisions fails when a relay tool's name collides with a builtin's name.
// Unconditional by design: it must hold even when builtins are OFF, because the guard
// exists to stop a FUTURE builtin from capturing a declaration-only tool on a proxy —
// and a proxy always runs with builtins off.
func checkRelayCollisions(tools []Tool) error {
	builtins := builtinToolNames()
	var bad []string
	for _, t := range tools {
		if t.Source == SourceRelay && builtins[t.Name] {
			bad = append(bad, t.Name)
		}
	}
	if len(bad) == 0 {
		return nil
	}
	sort.Strings(bad)
	return fmt.Errorf("toolnexus: relay tool name collides with a built-in tool: %v — "+
		"rename the relay tool (the guard applies even with builtins disabled, so a future "+
		"builtin cannot capture a declaration-only tool)", bad)
}

// ---- durable resume (ADR-0010 F1-a / D3, D4) ----

// resumeNoPrompt is the internal sentinel prompt meaning "continue from the transcript;
// do NOT append a user turn". Only the answer-carrying resume entry points use it; no
// real prompt can collide with it.
const resumeNoPrompt = "\x00toolnexus:resume\x00"

// RelayResult is one executed tool result the host hands back on resume. It rides
// Answer.Data["results"] as an array, keyed to the relayed call's id.
type RelayResult struct {
	ID      string `json:"id"`
	Output  string `json:"output"`
	IsError bool   `json:"isError"`
}

// relayResultsKey is the Answer.Data key holding the host's per-call results.
const relayResultsKey = "results"

// RelayAnswer builds the Answer a durable host returns on resume: one result per relayed
// call, keyed by the call id from Request.Data["calls"]. Sugar over Answer — any Answer
// with the same shape works.
func RelayAnswer(requestID string, results []RelayResult) Answer {
	arr := make([]any, 0, len(results))
	for _, r := range results {
		arr = append(arr, map[string]any{"id": r.ID, "output": r.Output, "isError": r.IsError})
	}
	return Answer{ID: requestID, Ok: true, Data: map[string]any{relayResultsKey: arr}}
}

// relayResultsOf reads the host's per-call results off an Answer, indexed by call id.
// Falls back to the single-call shape (data.output / data.isError), which applies to the
// first outstanding call — so a one-call relay needs no array.
func relayResultsOf(a Answer) (byID map[string]RelayResult, single *RelayResult) {
	byID = map[string]RelayResult{}
	if a.Data == nil {
		return byID, nil
	}
	if arr, ok := a.Data[relayResultsKey].([]any); ok {
		for _, e := range arr {
			m, ok := e.(map[string]any)
			if !ok {
				continue
			}
			id, _ := m["id"].(string)
			out, _ := m["output"].(string)
			isErr, _ := m["isError"].(bool)
			byID[id] = RelayResult{ID: id, Output: out, IsError: isErr}
		}
		return byID, nil
	}
	if out, ok := a.Data[RelayOutputKey].(string); ok {
		isErr, _ := a.Data[RelayIsErrorKey].(bool)
		return byID, &RelayResult{Output: out, IsError: isErr}
	}
	return byID, nil
}

// haltedTurn locates the last assistant turn that issued tool calls in a halted
// transcript. It returns that message's index, the tool-call ids in order, and whether
// the transcript uses Anthropic-native content blocks (vs OpenAI tool_calls).
func haltedTurn(history []any) (idx int, ids []string, anthropic bool) {
	idx = -1
	for i := len(history) - 1; i >= 0; i-- {
		m, ok := history[i].(map[string]any)
		if !ok || m["role"] != "assistant" {
			continue
		}
		if tcs, ok := m["tool_calls"].([]any); ok && len(tcs) > 0 {
			for _, tc := range tcs {
				if t, ok := tc.(map[string]any); ok {
					id, _ := t["id"].(string)
					ids = append(ids, id)
				}
			}
			return i, ids, false
		}
		if blocks, ok := m["content"].([]any); ok {
			for _, b := range blocks {
				if bb, ok := b.(map[string]any); ok && bb["type"] == "tool_use" {
					id, _ := bb["id"].(string)
					ids = append(ids, id)
				}
			}
			if len(ids) > 0 {
				return i, ids, true
			}
		}
	}
	return idx, nil, false
}

// repairHaltedTurn rebuilds a durably-halted transcript so that EVERY tool call of the
// halted assistant turn has a tool_result (ADR-0010 D4). The placeholder error result
// the halt wrote is REPLACED, not appended alongside — routsi's binding requirement —
// and the calls whose placeholders never entered the transcript get their slots filled.
//
// A call with no supplied result gets an explicit error result rather than being left
// absent, because a provider rejects an assistant turn with an unanswered tool call.
func repairHaltedTurn(history []any, pending Request, answer Answer) ([]any, error) {
	idx, ids, anthropic := haltedTurn(history)
	if idx < 0 {
		return nil, fmt.Errorf("toolnexus: cannot resume — the transcript has no halted assistant turn with tool calls")
	}
	byID, single := relayResultsOf(answer)

	// Map relayed calls by id so an unsupplied result can name the tool it belongs to.
	relayNames := map[string]string{}
	for _, rc := range RelayCallsOf(&pending) {
		relayNames[rc.ID] = rc.Name
	}

	resultFor := func(i int, id string) RelayResult {
		if r, ok := byID[id]; ok {
			return r
		}
		if single != nil && i == 0 {
			r := *single
			r.ID = id
			return r
		}
		name := relayNames[id]
		if name == "" {
			name = id
		}
		return RelayResult{ID: id, Output: "no result supplied on resume for " + name, IsError: true}
	}

	// Keep the transcript up to and including the halted assistant turn, dropping every
	// result message the halt appended after it (those are the placeholders).
	out := make([]any, 0, idx+2)
	out = append(out, history[:idx+1]...)

	if anthropic {
		blocks := make([]any, 0, len(ids))
		for i, id := range ids {
			r := resultFor(i, id)
			blocks = append(blocks, map[string]any{
				"type": "tool_result", "tool_use_id": id, "content": r.Output, "is_error": r.IsError,
			})
		}
		out = append(out, map[string]any{"role": "user", "content": blocks})
		return out, nil
	}
	for i, id := range ids {
		r := resultFor(i, id)
		out = append(out, map[string]any{
			"role": "tool", "tool_call_id": id, "content": r.Output,
		})
	}
	return out, nil
}

// RunWithAnswer resumes a run that previously halted with status "pending" (§10 durable
// path, ADR-0010 F1-a). The host passes the transcript it persisted, the Request it
// received, and the Answer carrying the results it executed itself. Every tool call of
// the halted turn gets a tool_result — the halt's placeholder is replaced — so the
// transcript sent to the provider is balanced and replayable, and the loop continues
// with no new user turn.
//
// The Answer must echo the Request's id; a mismatch is an error rather than a silent
// continue, so a stale or misrouted answer cannot corrupt a conversation.
func (c *Client) RunWithAnswer(ctx context.Context, tk *Toolkit, history []any, pending Request, answer Answer) (RunResult, error) {
	if answer.ID != pending.ID {
		return RunResult{}, fmt.Errorf("toolnexus: cannot resume — answer id %q does not echo the pending request id %q", answer.ID, pending.ID)
	}
	if len(history) == 0 {
		return RunResult{}, fmt.Errorf("toolnexus: cannot resume — no transcript supplied")
	}
	repaired, err := repairHaltedTurn(history, pending, answer)
	if err != nil {
		return RunResult{}, err
	}
	return c.RunWithHistory(ctx, resumeNoPrompt, tk, repaired)
}

// AskWithAnswer is RunWithAnswer against a stored conversation: it loads the transcript
// for id, resumes it with the answer, and saves the updated transcript back. The stateful
// counterpart for a durable host that keys conversations by id.
func (c *Client) AskWithAnswer(ctx context.Context, tk *Toolkit, id string, pending Request, answer Answer) (RunResult, error) {
	if id == "" {
		return RunResult{}, fmt.Errorf("toolnexus: AskWithAnswer needs a conversation id")
	}
	history, err := c.store.Get(id)
	if err != nil {
		return RunResult{}, err
	}
	res, err := c.RunWithAnswer(ctx, tk, history, pending, answer)
	if err != nil {
		return res, err
	}
	if err := c.store.Save(id, res.Messages); err != nil {
		return res, err
	}
	return res, nil
}
