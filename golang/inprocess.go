package toolnexus

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// ---------------------------------------------------------------------------
// In-process models (SPEC §8 Gap 2, semantic form).
// ---------------------------------------------------------------------------

// InProcessToolCall is one tool call an in-process model asks for. Flat on purpose:
// the nested `function: {}` wrapper is a wire detail, not something a model author
// should have to type. Arguments may be any value (encoded for you) or a
// pre-encoded json.RawMessage / string, which is passed through unchanged.
type InProcessToolCall struct {
	ID        string
	Name      string
	Arguments any
}

// InProcessRequest is what an in-process model is handed: the assembled request for
// THIS call.
type InProcessRequest struct {
	Messages []any
	Tools    []any
	Model    string
	// Body is every key the client assembled, for a model that wants them.
	Body map[string]any
}

// InProcessUsage is optional token reporting. Absent ⇒ the run reports zero rather
// than failing.
type InProcessUsage struct {
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
}

// InProcessResponse is exactly one assistant message. Set Content to finish, or
// ToolCalls to ask for tools — never both.
type InProcessResponse struct {
	Content   string
	ToolCalls []InProcessToolCall
	Usage     *InProcessUsage
}

// InProcessOptions is every client option EXCEPT the three that describe a wire —
// BaseURL, APIKey and Style — plus Generate.
type InProcessOptions struct {
	// Model is the model id reported in metrics and handed to Generate.
	Model string
	// Generate is your model: it receives the assembled request and returns one
	// assistant message.
	Generate func(InProcessRequest) (InProcessResponse, error)

	SystemPrompt   string
	MaxTurns       int
	Hooks          *Hooks
	TimeoutMs      int
	Store          ConversationStore
	OnMetric       func(MetricEvent)
	WaitFor        func(Request) (Answer, error)
	RequestParams  map[string]any
	BodyTransform  func(map[string]any) map[string]any
	Headers        map[string]string
}

// inProcessBaseURL is a sentinel. It is never dialled — the round tripper below
// answers every request before the network is reached — but the client builds a URL
// string internally, so it must be syntactically valid. `.invalid` is reserved by
// RFC 2606 precisely so a name can never resolve.
const inProcessBaseURL = "http://in-process.invalid/v1"

type inProcessRoundTripper struct {
	generate func(InProcessRequest) (InProcessResponse, error)
}

func (rt *inProcessRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	var body map[string]any
	if req.Body != nil {
		raw, _ := io.ReadAll(req.Body)
		_ = json.Unmarshal(raw, &body)
	}

	// A Generate returns a whole answer, so a stream would be one chunk pretending to
	// be many. Content and delta-count assertions cannot tell that from a real stream,
	// so refuse instead of degrading silently.
	if stream, _ := body["stream"].(bool); stream {
		return nil, fmt.Errorf("toolnexus: CreateInProcessClient does not support streaming — " +
			"Generate returns a complete answer. Use Run, or supply an HTTPClient that streams")
	}

	msgs, _ := body["messages"].([]any)
	tools, _ := body["tools"].([]any)
	model, _ := body["model"].(string)

	answer, err := rt.generate(InProcessRequest{Messages: msgs, Tools: tools, Model: model, Body: body})
	if err != nil {
		return nil, err
	}

	message := map[string]any{"role": "assistant"}
	if len(answer.ToolCalls) > 0 {
		calls := make([]any, 0, len(answer.ToolCalls))
		for i, c := range answer.ToolCalls {
			id := c.ID
			if id == "" {
				id = fmt.Sprintf("call_%d", i)
			}
			calls = append(calls, map[string]any{
				"id": id, "type": "function",
				"function": map[string]any{"name": c.Name, "arguments": encodeArgs(c.Arguments)},
			})
		}
		message["tool_calls"] = calls
	} else {
		message["content"] = answer.Content
	}

	finish := "stop"
	if _, ok := message["tool_calls"]; ok {
		finish = "tool_calls"
	}

	var prompt, completion, total int
	if answer.Usage != nil {
		prompt, completion, total = answer.Usage.PromptTokens, answer.Usage.CompletionTokens, answer.Usage.TotalTokens
	}
	if total == 0 {
		total = prompt + completion
	}

	out, _ := json.Marshal(map[string]any{
		"choices": []any{map[string]any{"index": 0, "message": message, "finish_reason": finish}},
		"usage": map[string]any{
			"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": total,
		},
	})
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(out)),
		Request:    req,
	}, nil
}

// encodeArgs passes an already-encoded value through and encodes anything else, so a
// model author never has to think about the wire.
func encodeArgs(v any) string {
	switch a := v.(type) {
	case nil:
		return "{}"
	case string:
		return a
	case json.RawMessage:
		return string(a)
	case []byte:
		return string(a)
	default:
		b, err := json.Marshal(a)
		if err != nil {
			return "{}"
		}
		return string(b)
	}
}

// CreateInProcessClient builds a client backed by a model running IN THIS PROCESS —
// no server, no socket, and no HTTP types to construct.
//
// This is a second constructor, not a second seam: it builds on the same injectable
// HTTPClient transport, so the tool-calling loop, MCP servers, skills, sub-agents,
// hooks, metrics and the completion gate behave identically.
//
// Streaming is refused rather than faked — see the round tripper above.
func CreateInProcessClient(opts InProcessOptions) *Client {
	if opts.Generate == nil {
		panic("toolnexus: CreateInProcessClient requires a Generate function")
	}
	return CreateClient(ClientOptions{
		BaseURL:       inProcessBaseURL,
		Style:         StyleOpenAI,
		Model:         opts.Model,
		HTTPClient:    &http.Client{Transport: &inProcessRoundTripper{generate: opts.Generate}},
		SystemPrompt:  opts.SystemPrompt,
		MaxTurns:      opts.MaxTurns,
		Hooks:         opts.Hooks,
		TimeoutMs:     opts.TimeoutMs,
		Store:         opts.Store,
		OnMetric:      opts.OnMetric,
		WaitFor:       opts.WaitFor,
		RequestParams: opts.RequestParams,
		BodyTransform: opts.BodyTransform,
		Headers:       opts.Headers,
	})
}
