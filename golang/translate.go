package toolnexus

// Single-turn translation — the translator path (SPEC.md §11, ADR-0011).
//
// `Translate` is toolnexus used as a pure wire-format translator, with NO agent loop:
// OpenAI-shaped messages and tools go in, exactly ONE provider call happens, and an
// OpenAI-shaped result comes back. No tool is executed, nothing suspends, no conversation
// is remembered — so a stateless HTTP proxy needs no state between turns.
//
// This is the right path when the CALLER owns the conversation and executes tools itself
// (the standard OpenAI function-calling flow, where every request carries the full history
// including prior tool results). For the case where toolnexus owns the conversation, use
// the agent loop with relay tools + `RunWithAnswer` instead (§10).
//
// Provider knowledge lives here on purpose: a caller hands over OpenAI shapes and never
// builds provider-native payloads itself.

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// TranslateRequest is an OpenAI-shaped chat request, handed over verbatim.
type TranslateRequest struct {
	// Messages is the OpenAI `messages` array, verbatim — including assistant turns
	// carrying `tool_calls` and `tool`-role results carrying `tool_call_id`. Entries may
	// be `map[string]any` or any JSON-marshalable struct.
	Messages []any
	// Tools is the OpenAI `tools` array, verbatim
	// (`{type:"function",function:{name,description,parameters}}`). Declaration-only —
	// nothing here is ever executed. Empty means no tools.
	Tools []any
	// Toolkit declares an ordinary toolkit's tools to the provider WITHOUT executing any
	// of them — MCP tools, skills, native functions, A2A agents, builtins, all of it. Use
	// this when you have a toolkit and want the model's tool CALLS handed back to you to
	// dispatch yourself, instead of the agent loop running them. Composes with Tools;
	// toolkit declarations come first. Nil means none.
	//
	// This is the other half of the adapters: `ToOpenAI`/`ToAnthropic`/`ToGemini` send a
	// toolkit's declarations OUT, and Translate reads the provider's tool calls back IN.
	Toolkit *Toolkit
	// ToolChoice is the OpenAI `tool_choice`, verbatim. Nil omits it.
	ToolChoice any
	// System overrides the system prompt. Empty uses any system message found in
	// Messages (and the client's SystemPrompt for providers that take it separately).
	System string
	// MaxTokens overrides the per-provider default. 0 uses the default.
	MaxTokens int
}

// TranslatedToolCall is one tool call the model asked for, in OpenAI shape.
type TranslatedToolCall struct {
	// ID is the tool-call id the caller must echo on its `tool` result message.
	ID string `json:"id"`
	// Name is the function name.
	Name string `json:"name"`
	// Arguments is the arguments as a JSON **string**, which is what the OpenAI wire
	// format uses — so a caller can hand it to a conforming client byte-for-byte.
	Arguments string `json:"arguments"`
}

// TranslateResult is an OpenAI-shaped single-turn result.
type TranslateResult struct {
	// Text is the assistant text content ("" when the model only called tools).
	Text string `json:"text"`
	// ToolCalls are the tool calls the model emitted, in provider order.
	ToolCalls []TranslatedToolCall `json:"toolCalls"`
	// FinishReason is the OpenAI finish reason: "stop" | "tool_calls" | "length" |
	// "content_filter". Mapped from the provider's native stop reason.
	FinishReason string `json:"finishReason"`
	// Usage is this single call's token usage.
	Usage Usage `json:"usage"`
	// Model is the model that answered.
	Model string `json:"model"`
	// Raw is the provider's decoded response, for callers that need a field this struct
	// does not model. Nil when decoding it failed.
	Raw map[string]any `json:"-"`
}

// Translate performs exactly ONE provider call and returns the turn in OpenAI shape.
// It executes no tools, drives no loop, and stores no conversation — every call is
// self-contained, so a caller may run it statelessly and horizontally scale it.
//
// Retries/backoff, request-param merging, and the LLM observability event are shared with
// the agent loop. `BeforeLLM`/`AfterLLM` hooks fire (once); tool hooks do not, because no
// tool runs.
func (c *Client) Translate(ctx context.Context, req TranslateRequest) (TranslateResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	ctx, cancel := c.withDeadline(ctx)
	defer cancel()
	if c.opts.Style == StyleAnthropic {
		return c.translateAnthropic(ctx, req)
	}
	return c.translateOpenAI(ctx, req)
}

// ---- OpenAI-style upstream: near-passthrough ----

func (c *Client) translateOpenAI(ctx context.Context, req TranslateRequest) (TranslateResult, error) {
	key, err := c.resolveKey()
	if err != nil {
		return TranslateResult{}, err
	}
	base := strings.TrimRight(c.opts.BaseURL, "/")
	endpoint := base + "/chat/completions"
	if !strings.HasSuffix(base, "/v1") && !strings.Contains(base, "/chat/") {
		endpoint = base + "/v1/chat/completions"
	}
	declared := req.Tools
	if req.Toolkit != nil {
		declared = append(req.Toolkit.ToOpenAI(), declared...)
	}
	messages, tools := c.translateHooks(ctx, normalizeMessages(req.Messages), declared)

	body := map[string]any{"model": c.opts.Model, "messages": messages}
	if sys := c.translateSystem(req); sys != "" && !hasSystemMessage(messages) {
		body["messages"] = append([]any{map[string]any{"role": "system", "content": sys}}, messages...)
	}
	if len(tools) > 0 {
		body["tools"] = tools
	}
	if req.ToolChoice != nil {
		body["tool_choice"] = req.ToolChoice
	}
	if req.MaxTokens > 0 {
		body["max_tokens"] = req.MaxTokens
	}
	body = c.finalizeBody(body)

	t0 := time.Now()
	raw, err := c.postJSON(ctx, endpoint, map[string]string{"authorization": "Bearer " + key}, body)
	if err != nil {
		c.emitLLM("error", t0, 0, 0)
		return TranslateResult{}, err
	}
	var data struct {
		Choices []struct {
			Message struct {
				Content   string `json:"content"`
				ToolCalls []struct {
					ID       string `json:"id"`
					Function struct {
						Name      string `json:"name"`
						Arguments string `json:"arguments"`
					} `json:"function"`
				} `json:"tool_calls"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
		Usage map[string]any `json:"usage"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		c.emitLLM("error", t0, 0, 0)
		return TranslateResult{}, err
	}
	p, cp := perCall(data.Usage, string(StyleOpenAI))
	c.emitLLM("ok", t0, p, cp)
	out := TranslateResult{Model: c.opts.Model, Raw: decodeResponse(raw)}
	addUsage(&out.Usage, data.Usage, string(StyleOpenAI))
	c.afterLLMHook(ctx, out.Raw)
	if len(data.Choices) == 0 {
		out.FinishReason = "stop"
		return out, nil
	}
	ch := data.Choices[0]
	out.Text = ch.Message.Content
	for _, tc := range ch.Message.ToolCalls {
		out.ToolCalls = append(out.ToolCalls, TranslatedToolCall{
			ID: tc.ID, Name: tc.Function.Name, Arguments: tc.Function.Arguments,
		})
	}
	out.FinishReason = ch.FinishReason
	if out.FinishReason == "" {
		out.FinishReason = finishReasonFor(len(out.ToolCalls) > 0, "")
	}
	return out, nil
}

// ---- Anthropic-style upstream: the real translation ----

func (c *Client) translateAnthropic(ctx context.Context, req TranslateRequest) (TranslateResult, error) {
	key, err := c.resolveKey()
	if err != nil {
		return TranslateResult{}, err
	}
	base := strings.TrimRight(c.opts.BaseURL, "/")
	endpoint := base + "/v1/messages"
	if strings.HasSuffix(base, "/v1") {
		endpoint = base + "/messages"
	}

	msgs, system := openAIMessagesToAnthropic(normalizeMessages(req.Messages))
	if s := c.translateSystem(req); s != "" {
		system = s
	}
	declared := openAIToolsToAnthropic(req.Tools)
	if req.Toolkit != nil {
		declared = append(req.Toolkit.ToAnthropic(), declared...)
	}
	messages, tools := c.translateHooks(ctx, msgs, declared)

	maxTokens := req.MaxTokens
	if maxTokens <= 0 {
		maxTokens = 4096
	}
	body := map[string]any{"model": c.opts.Model, "max_tokens": maxTokens, "messages": messages}
	if system != "" {
		body["system"] = system
	}
	if len(tools) > 0 {
		body["tools"] = tools
	}
	if tc := openAIToolChoiceToAnthropic(req.ToolChoice); tc != nil {
		body["tool_choice"] = tc
	}
	body = c.finalizeBody(body)

	t0 := time.Now()
	raw, err := c.postJSON(ctx, endpoint, map[string]string{
		"x-api-key": key, "anthropic-version": "2023-06-01",
	}, body)
	if err != nil {
		c.emitLLM("error", t0, 0, 0)
		return TranslateResult{}, err
	}
	var data struct {
		Content []struct {
			Type  string         `json:"type"`
			Text  string         `json:"text"`
			ID    string         `json:"id"`
			Name  string         `json:"name"`
			Input map[string]any `json:"input"`
		} `json:"content"`
		StopReason string         `json:"stop_reason"`
		Usage      map[string]any `json:"usage"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		c.emitLLM("error", t0, 0, 0)
		return TranslateResult{}, err
	}
	p, cp := perCall(data.Usage, string(StyleAnthropic))
	c.emitLLM("ok", t0, p, cp)
	out := TranslateResult{Model: c.opts.Model, Raw: decodeResponse(raw)}
	addUsage(&out.Usage, data.Usage, string(StyleAnthropic))
	c.afterLLMHook(ctx, out.Raw)

	var text []string
	for _, b := range data.Content {
		switch b.Type {
		case "text":
			text = append(text, b.Text)
		case "tool_use":
			args, err := json.Marshal(b.Input)
			if err != nil || b.Input == nil {
				args = []byte("{}")
			}
			out.ToolCalls = append(out.ToolCalls, TranslatedToolCall{
				ID: b.ID, Name: b.Name, Arguments: string(args),
			})
		}
	}
	out.Text = strings.Join(text, "")
	out.FinishReason = finishReasonFor(len(out.ToolCalls) > 0, data.StopReason)
	return out, nil
}

// ---- inbound translation: OpenAI shapes → Anthropic-native ----

// openAIMessagesToAnthropic converts an OpenAI `messages` array into Anthropic-native
// messages plus the extracted system prompt. It preserves tool-call structure that a
// naive text flattening would destroy:
//
//   - an assistant turn's `tool_calls` become `tool_use` content blocks (id/name/input),
//   - a `tool`-role result becomes a `tool_result` block keyed by `tool_call_id`, merged
//     into a single user turn when consecutive (Anthropic wants one user message
//     carrying all of the preceding turn's results),
//   - `system` messages are hoisted out, because Anthropic takes system separately.
func openAIMessagesToAnthropic(messages []any) ([]any, string) {
	var out []any
	var systemParts []string
	var pendingResults []any

	flushResults := func() {
		if len(pendingResults) > 0 {
			out = append(out, map[string]any{"role": "user", "content": pendingResults})
			pendingResults = nil
		}
	}

	for _, raw := range messages {
		m, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		role, _ := m["role"].(string)
		switch role {
		case "system", "developer":
			flushResults()
			if s := contentText(m["content"]); s != "" {
				systemParts = append(systemParts, s)
			}
		case "tool", "function":
			id, _ := m["tool_call_id"].(string)
			block := map[string]any{"type": "tool_result", "content": contentText(m["content"])}
			if id != "" {
				block["tool_use_id"] = id
			}
			pendingResults = append(pendingResults, block)
		case "assistant":
			flushResults()
			var blocks []any
			if s := contentText(m["content"]); s != "" {
				blocks = append(blocks, map[string]any{"type": "text", "text": s})
			}
			for _, tc := range toolCallsOf(m) {
				blocks = append(blocks, map[string]any{
					"type": "tool_use", "id": tc.ID, "name": tc.Name,
					"input": safeJSONArgs(tc.Arguments),
				})
			}
			if len(blocks) == 0 {
				continue // an empty assistant turn would be rejected
			}
			out = append(out, map[string]any{"role": "assistant", "content": blocks})
		default: // user and anything else
			flushResults()
			if s := contentText(m["content"]); s != "" {
				out = append(out, map[string]any{"role": "user", "content": s})
			} else if blocks, ok := m["content"].([]any); ok && len(blocks) > 0 {
				out = append(out, map[string]any{"role": "user", "content": blocks})
			}
		}
	}
	flushResults()
	return out, strings.Join(systemParts, "\n\n")
}

// openAIToolsToAnthropic converts an OpenAI `tools` array into Anthropic tool
// declarations. Entries that are not function declarations are skipped.
func openAIToolsToAnthropic(tools []any) []any {
	var out []any
	for _, raw := range tools {
		m, ok := asJSONMap(raw)
		if !ok {
			continue
		}
		fn, ok := asJSONMap(m["function"])
		if !ok {
			// Already provider-native, or an unknown tool type — pass it through.
			if _, hasName := m["name"]; hasName {
				out = append(out, m)
			}
			continue
		}
		name, _ := fn["name"].(string)
		if name == "" {
			continue
		}
		decl := map[string]any{"name": name}
		if d, _ := fn["description"].(string); d != "" {
			decl["description"] = d
		}
		if params, ok := asJSONMap(fn["parameters"]); ok {
			decl["input_schema"] = params
		} else {
			decl["input_schema"] = map[string]any{"type": "object", "properties": map[string]any{}}
		}
		out = append(out, decl)
	}
	return out
}

// openAIToolChoiceToAnthropic maps OpenAI tool_choice onto Anthropic's shape. Returns nil
// for "auto"/absent (the provider default) and for anything unrecognized.
func openAIToolChoiceToAnthropic(choice any) any {
	switch v := choice.(type) {
	case string:
		switch v {
		case "required", "any":
			return map[string]any{"type": "any"}
		case "none":
			return map[string]any{"type": "none"}
		}
		return nil
	case map[string]any:
		if fn, ok := asJSONMap(v["function"]); ok {
			if name, _ := fn["name"].(string); name != "" {
				return map[string]any{"type": "tool", "name": name}
			}
		}
	}
	return nil
}

// ---- small shared helpers ----

// finishReasonFor maps a provider stop reason onto an OpenAI finish_reason. Tool calls
// win: a turn that emitted any tool call is always "tool_calls" to a conforming client.
func finishReasonFor(hasToolCalls bool, providerStop string) string {
	if hasToolCalls {
		return "tool_calls"
	}
	switch providerStop {
	case "max_tokens", "length":
		return "length"
	case "refusal", "content_filter":
		return "content_filter"
	}
	return "stop"
}

// normalizeMessages coerces arbitrary JSON-marshalable entries into map form so the
// translators can inspect them uniformly. Entries that cannot be coerced are dropped.
func normalizeMessages(messages []any) []any {
	out := make([]any, 0, len(messages))
	for _, m := range messages {
		if mm, ok := asJSONMap(m); ok {
			out = append(out, mm)
		}
	}
	return out
}

// asJSONMap returns v as a map, round-tripping structs through JSON when needed.
func asJSONMap(v any) (map[string]any, bool) {
	if m, ok := v.(map[string]any); ok {
		return m, true
	}
	if v == nil {
		return nil, false
	}
	raw, err := json.Marshal(v)
	if err != nil {
		return nil, false
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil || m == nil {
		return nil, false
	}
	return m, true
}

// contentText flattens an OpenAI `content` value to text. It handles the plain-string
// form and the content-parts array form; non-text parts are ignored.
func contentText(content any) string {
	switch v := content.(type) {
	case string:
		return v
	case []any:
		var parts []string
		for _, p := range v {
			pm, ok := asJSONMap(p)
			if !ok {
				continue
			}
			if t, _ := pm["text"].(string); t != "" {
				parts = append(parts, t)
			}
		}
		return strings.Join(parts, "")
	case nil:
		return ""
	}
	return ""
}

// toolCallsOf reads an assistant message's OpenAI `tool_calls`.
func toolCallsOf(m map[string]any) []TranslatedToolCall {
	raw, ok := m["tool_calls"].([]any)
	if !ok {
		return nil
	}
	var out []TranslatedToolCall
	for _, e := range raw {
		tc, ok := asJSONMap(e)
		if !ok {
			continue
		}
		id, _ := tc["id"].(string)
		fn, ok := asJSONMap(tc["function"])
		if !ok {
			continue
		}
		name, _ := fn["name"].(string)
		args, _ := fn["arguments"].(string)
		if args == "" {
			// Some clients send arguments as an object rather than a JSON string.
			if obj, ok := asJSONMap(fn["arguments"]); ok {
				if b, err := json.Marshal(obj); err == nil {
					args = string(b)
				}
			}
		}
		out = append(out, TranslatedToolCall{ID: id, Name: name, Arguments: args})
	}
	return out
}

func hasSystemMessage(messages []any) bool {
	for _, m := range messages {
		if mm, ok := m.(map[string]any); ok {
			if r, _ := mm["role"].(string); r == "system" || r == "developer" {
				return true
			}
		}
	}
	return false
}

// translateSystem picks the system prompt: the request's override wins, else the client's
// configured SystemPrompt.
func (c *Client) translateSystem(req TranslateRequest) string {
	if req.System != "" {
		return req.System
	}
	return c.opts.SystemPrompt
}

// translateHooks runs BeforeLLM for the single call, honoring message/tool overrides.
func (c *Client) translateHooks(ctx context.Context, messages, tools []any) ([]any, []any) {
	if c.opts.Hooks == nil || c.opts.Hooks.BeforeLLM == nil {
		return messages, tools
	}
	ov, err := c.opts.Hooks.BeforeLLM(ctx, BeforeLLMEvent{
		Messages: messages, Tools: tools, Model: c.opts.Model, Turn: 0,
	})
	if err != nil || ov == nil {
		return messages, tools
	}
	if ov.Messages != nil {
		messages = ov.Messages
	}
	if ov.Tools != nil {
		tools = ov.Tools
	}
	return messages, tools
}

// afterLLMHook fires AfterLLM for the single call. A hook error is not fatal to a
// translation — the call already happened and the caller needs its result.
func (c *Client) afterLLMHook(ctx context.Context, raw map[string]any) {
	if c.opts.Hooks == nil || c.opts.Hooks.AfterLLM == nil {
		return
	}
	_ = c.opts.Hooks.AfterLLM(ctx, AfterLLMEvent{Response: raw, Model: c.opts.Model, Turn: 0})
}

// ToolCallsJSON renders the result's tool calls as an OpenAI `tool_calls` array, ready to
// place on an assistant message. Convenience for a proxy assembling a response envelope.
func (r TranslateResult) ToolCallsJSON() []any {
	out := make([]any, 0, len(r.ToolCalls))
	for _, tc := range r.ToolCalls {
		out = append(out, map[string]any{
			"id": tc.ID, "type": "function",
			"function": map[string]any{"name": tc.Name, "arguments": tc.Arguments},
		})
	}
	return out
}

// String makes a TranslatedToolCall readable in logs without leaking arguments wholesale.
func (t TranslatedToolCall) String() string {
	return fmt.Sprintf("%s(%s) id=%s", t.Name, truncate(t.Arguments, 64), t.ID)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
