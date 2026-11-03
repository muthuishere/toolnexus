package toolnexus

import (
	"bytes"
	"context"
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
	// GenerateStream (golang-first) is a ctx-aware, streaming Generate: ctx is
	// the run's context (cancel/timeout), and emit reports the answer as it is
	// produced — {Type:"text", Delta} events become the stream's text deltas
	// (and must add up to the returned Content); any other event (e.g. a
	// "tool_call"/"tool_result" for a tool the MODEL ran itself, as an ACP
	// agent does) is passed to the stream's consumer as-is and never executed.
	// When set it is used for Run too (emit is then a no-op), and Stream
	// works instead of refusing. ACPClient.GenerateStream has this shape.
	GenerateStream func(ctx context.Context, req InProcessRequest, emit func(StreamEvent)) (InProcessResponse, error)

	// OnError, when set, classifies a failed Generate exactly as it would a failed HTTP
	// attempt. Unset ⇒ every failure is FINAL (see CreateInProcessClient) — set this to
	// opt back into retries for a model that is genuinely flaky, e.g. a GPU that OOMs.
	OnError            func(ErrorInfo) Tier
	SystemPrompt       string
	MaxTurns           int
	MaxToolConcurrency int
	Hooks              *Hooks
	TimeoutMs          int
	Store              ConversationStore
	OnMetric           func(MetricEvent)
	WaitFor            func(Request) (Answer, error)
	WaitForCtx         func(context.Context, Request) (Answer, error)
	RequestParams      map[string]any
	BodyTransform      func(map[string]any) map[string]any
	Headers            map[string]string
}

// inProcessBaseURL is a sentinel. It is never dialled — the round tripper below
// answers every request before the network is reached — but the client builds a URL
// string internally, so it must be syntactically valid. `.invalid` is reserved by
// RFC 2606 precisely so a name can never resolve.
const inProcessBaseURL = "http://in-process.invalid/v1"

// InProcessTransport builds the SAME round tripper CreateInProcessClient uses, as a
// plain http.RoundTripper — for a host that needs to hand a sub-agent runtime
// (agents.Options.Transport) the identical in-process model a top-level client was
// built with, without re-implementing the wire assembly (ADR 0030 / issue #95).
func InProcessTransport(generate func(InProcessRequest) (InProcessResponse, error)) http.RoundTripper {
	return &inProcessRoundTripper{generate: generate}
}

type inProcessRoundTripper struct {
	generate       func(InProcessRequest) (InProcessResponse, error)
	generateStream func(context.Context, InProcessRequest, func(StreamEvent)) (InProcessResponse, error)
}

// streamSideKey carries the stream's event sink (set by StreamWithID) to the
// in-process round tripper, so non-text events from GenerateStream reach the
// consumer without passing through the OpenAI wire (where a tool call would
// be executed by the loop).
type streamSideKey struct{}

func withStreamSide(ctx context.Context, emit func(StreamEvent)) context.Context {
	return context.WithValue(ctx, streamSideKey{}, emit)
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
	stream, _ := body["stream"].(bool)
	if stream && rt.generateStream != nil {
		return rt.roundTripStream(req, body)
	}
	if stream {
		return nil, fmt.Errorf("toolnexus: CreateInProcessClient does not support streaming — " +
			"Generate returns a complete answer. Use Run, or supply an HTTPClient that streams")
	}

	msgs, _ := body["messages"].([]any)
	tools, _ := body["tools"].([]any)
	model, _ := body["model"].(string)

	ipReq := InProcessRequest{Messages: msgs, Tools: tools, Model: model, Body: body}
	var answer InProcessResponse
	var err error
	if rt.generate != nil {
		answer, err = rt.generate(ipReq)
	} else {
		answer, err = rt.generateStream(req.Context(), ipReq, func(StreamEvent) {})
	}
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

// roundTripStream runs GenerateStream and answers with an OpenAI SSE body:
// text events become content deltas as they arrive, the returned tool calls
// (if any) become one tool_calls delta, then finish_reason, usage and [DONE].
func (rt *inProcessRoundTripper) roundTripStream(req *http.Request, body map[string]any) (*http.Response, error) {
	msgs, _ := body["messages"].([]any)
	tools, _ := body["tools"].([]any)
	model, _ := body["model"].(string)
	side, _ := req.Context().Value(streamSideKey{}).(func(StreamEvent))

	pr, pw := io.Pipe()
	chunk := func(v map[string]any) {
		b, _ := json.Marshal(v)
		_, _ = pw.Write([]byte("data: " + string(b) + "\n\n"))
	}
	go func() {
		sawText := false
		answer, err := rt.generateStream(req.Context(),
			InProcessRequest{Messages: msgs, Tools: tools, Model: model, Body: body},
			func(ev StreamEvent) {
				if ev.Type == "text" {
					if ev.Delta != "" {
						sawText = true
						chunk(map[string]any{"choices": []any{map[string]any{"index": 0, "delta": map[string]any{"content": ev.Delta}}}})
					}
					return
				}
				if side != nil {
					side(ev)
				}
			})
		if err != nil {
			pw.CloseWithError(err)
			return
		}
		finish := "stop"
		if len(answer.ToolCalls) > 0 {
			calls := make([]any, 0, len(answer.ToolCalls))
			for i, c := range answer.ToolCalls {
				id := c.ID
				if id == "" {
					id = fmt.Sprintf("call_%d", i)
				}
				calls = append(calls, map[string]any{"index": i, "id": id, "type": "function",
					"function": map[string]any{"name": c.Name, "arguments": encodeArgs(c.Arguments)}})
			}
			chunk(map[string]any{"choices": []any{map[string]any{"index": 0, "delta": map[string]any{"tool_calls": calls}}}})
			finish = "tool_calls"
		} else if !sawText && answer.Content != "" {
			chunk(map[string]any{"choices": []any{map[string]any{"index": 0, "delta": map[string]any{"content": answer.Content}}}})
		}
		chunk(map[string]any{"choices": []any{map[string]any{"index": 0, "delta": map[string]any{}, "finish_reason": finish}}})
		if u := answer.Usage; u != nil {
			total := u.TotalTokens
			if total == 0 {
				total = u.PromptTokens + u.CompletionTokens
			}
			chunk(map[string]any{"choices": []any{}, "usage": map[string]any{
				"prompt_tokens": u.PromptTokens, "completion_tokens": u.CompletionTokens, "total_tokens": total}})
		}
		_, _ = pw.Write([]byte("data: [DONE]\n\n"))
		pw.Close()
	}()
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"text/event-stream"}},
		Body:       pr,
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
// Streaming is refused rather than faked for a plain Generate — see the round
// tripper above. With GenerateStream (and no Generate) it streams for real.
func CreateInProcessClient(opts InProcessOptions) *Client {
	if opts.Generate == nil && opts.GenerateStream == nil {
		panic("toolnexus: CreateInProcessClient requires a Generate (or GenerateStream) function")
	}
	// SPIKE (ADR-0029): every failure is FINAL by default via the GENERAL
	// Retries mechanism now that -1 is a real "explicit zero" spelling --
	// no more forcing OnError=TierFail as a workaround. opts.OnError (nil
	// unless the host sets one) passes through untouched; it is moot when the
	// budget is 0 (attempt==retries fires on the first and only attempt
	// regardless of tier), and if this constructor later grows its own
	// `Retries` field for a host that wants to opt back in, OnError keeps
	// working independently of it.
	return CreateClient(ClientOptions{
		OnError: opts.OnError,
		Retries: -1,
		BaseURL: inProcessBaseURL,
		Style:   StyleOpenAI,
		// An in-process model has no endpoint to authenticate to, so the host must never need a key — but the client resolves one from the environment and fails when it finds none. A sentinel keeps that resolution from ever running. Caught by CI, which has no OPENROUTER_API_KEY; every local run passed because a developer shell has one.
		APIKey:             "in-process",
		Model:              opts.Model,
		HTTPClient:         &http.Client{Transport: &inProcessRoundTripper{generate: opts.Generate, generateStream: opts.GenerateStream}},
		SystemPrompt:       opts.SystemPrompt,
		MaxTurns:           opts.MaxTurns,
		MaxToolConcurrency: opts.MaxToolConcurrency,
		Hooks:              opts.Hooks,
		TimeoutMs:          opts.TimeoutMs,
		Store:              opts.Store,
		OnMetric:           opts.OnMetric,
		WaitFor:            opts.WaitFor,
		WaitForCtx:         opts.WaitForCtx,
		RequestParams:      opts.RequestParams,
		BodyTransform:      opts.BodyTransform,
		Headers:            opts.Headers,
	})
}
