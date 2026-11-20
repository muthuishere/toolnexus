package toolnexus

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
)

// Local models (SPEC §8 "Local models"). Two shapes of local endpoint:
//
//   - an OpenAI-compatible server (llama-server, vLLM, Ollama's /v1) that parses tool calls itself:
//     CreateLocalClient with Format "" or "openai" — a plain CreateClient that needs no API key;
//   - a tiny model that emits its own tool-call text the server does not parse, today FunctionGemma:
//     Format "functiongemma" — an in-process client whose generate talks to the endpoint, pins the
//     model's developer line, optionally prefills the call prefix, and parses the raw call text.

// FunctionGemmaDeveloperPrompt is the exact developer line FunctionGemma was trained on. It is
// always the first line of the developer message; any system prompt follows it on later lines.
const FunctionGemmaDeveloperPrompt = "You are a model that can do function calling with the following functions"

const (
	functionGemmaStart  = "<start_function_call>"
	functionGemmaEnd    = "<end_function_call>"
	functionGemmaEscape = "<escape>"
	functionGemmaPrefix = functionGemmaStart + "call:"
)

// LocalOptions configures CreateLocalClient.
type LocalOptions struct {
	// BaseURL of the OpenAI-compatible endpoint, including /v1 (e.g. "http://127.0.0.1:8080/v1").
	BaseURL string
	// Model id sent to the endpoint (llama-server ignores it). Default "local".
	Model string
	// Format: "" or "openai" (the server parses tool calls) or "functiongemma".
	Format string
	// APIKey for a local server that wants one. Default: a placeholder (local servers need none).
	APIKey string
	// Prefill (functiongemma) starts the assistant turn with "<start_function_call>call:", so the
	// model always answers with a tool call. Off by default.
	Prefill bool
	// MaxTokens (functiongemma) per turn. Default 128.
	MaxTokens int
	// HTTPClient (functiongemma) for the endpoint. Default http.DefaultClient.
	HTTPClient *http.Client

	SystemPrompt       string
	MaxTurns           int
	MaxToolConcurrency int
	Hooks              *Hooks
	TimeoutMs          int
	Store              ConversationStore
	OnMetric           func(MetricEvent)
}

// CreateLocalClient builds a client for a local model endpoint. See LocalOptions.Format.
func CreateLocalClient(opts LocalOptions) (*Client, error) {
	if opts.BaseURL == "" {
		return nil, fmt.Errorf("toolnexus: CreateLocalClient requires a BaseURL")
	}
	if opts.Model == "" {
		opts.Model = "local"
	}
	switch strings.ToLower(opts.Format) {
	case "", "openai":
		key := opts.APIKey
		if key == "" {
			key = "local"
		}
		return CreateClient(ClientOptions{
			BaseURL: opts.BaseURL, Style: StyleOpenAI, Model: opts.Model, APIKey: key,
			SystemPrompt: opts.SystemPrompt, MaxTurns: opts.MaxTurns, MaxToolConcurrency: opts.MaxToolConcurrency,
			Hooks: opts.Hooks, TimeoutMs: opts.TimeoutMs, Store: opts.Store, OnMetric: opts.OnMetric,
		}), nil
	case "functiongemma":
		return CreateInProcessClient(InProcessOptions{
			Model:          opts.Model,
			GenerateStream: FunctionGemmaGenerate(opts),
			SystemPrompt:   opts.SystemPrompt, MaxTurns: opts.MaxTurns, MaxToolConcurrency: opts.MaxToolConcurrency,
			Hooks: opts.Hooks, TimeoutMs: opts.TimeoutMs, Store: opts.Store, OnMetric: opts.OnMetric,
		}), nil
	default:
		return nil, fmt.Errorf("toolnexus: unknown local format %q (want openai or functiongemma)", opts.Format)
	}
}

// FunctionGemmaMessages rewrites OpenAI-shaped messages for FunctionGemma: every system message is
// folded into one leading developer message whose first line is FunctionGemmaDeveloperPrompt.
func FunctionGemmaMessages(messages []any) []any {
	var sys []string
	var rest []any
	for _, m := range messages {
		mm, ok := m.(map[string]any)
		if ok {
			if role, _ := mm["role"].(string); role == "system" || role == "developer" {
				if s, _ := mm["content"].(string); s != "" && s != FunctionGemmaDeveloperPrompt {
					sys = append(sys, s)
				}
				continue
			}
		}
		rest = append(rest, m)
	}
	dev := FunctionGemmaDeveloperPrompt
	if len(sys) > 0 {
		dev += "\n" + strings.Join(sys, "\n")
	}
	return append([]any{map[string]any{"role": "developer", "content": dev}}, rest...)
}

// FunctionGemmaGenerate returns a streaming generate that calls a llama-server-style endpoint with
// the FunctionGemma conventions and parses the raw call text into tool calls.
func FunctionGemmaGenerate(opts LocalOptions) func(context.Context, InProcessRequest, func(StreamEvent)) (InProcessResponse, error) {
	hc := opts.HTTPClient
	if hc == nil {
		hc = http.DefaultClient
	}
	maxTokens := opts.MaxTokens
	if maxTokens <= 0 {
		maxTokens = 128
	}
	url := strings.TrimRight(opts.BaseURL, "/") + "/chat/completions"
	return func(ctx context.Context, req InProcessRequest, _ func(StreamEvent)) (InProcessResponse, error) {
		msgs := FunctionGemmaMessages(req.Messages)
		if opts.Prefill {
			msgs = append(msgs, map[string]any{"role": "assistant", "content": functionGemmaPrefix})
		}
		body := map[string]any{"model": req.Model, "messages": msgs, "temperature": 0,
			"max_tokens": maxTokens, "stop": []string{functionGemmaEnd}}
		if len(req.Tools) > 0 {
			body["tools"] = req.Tools
		}
		b, _ := json.Marshal(body)
		hr, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(b))
		if err != nil {
			return InProcessResponse{}, err
		}
		hr.Header.Set("Content-Type", "application/json")
		if opts.APIKey != "" {
			hr.Header.Set("Authorization", "Bearer "+opts.APIKey)
		}
		resp, err := hc.Do(hr)
		if err != nil {
			return InProcessResponse{}, err
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		if resp.StatusCode/100 != 2 {
			return InProcessResponse{}, fmt.Errorf("functiongemma: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
		}
		var out struct {
			Choices []struct {
				Message struct {
					Content string `json:"content"`
				} `json:"message"`
			} `json:"choices"`
			Usage *struct {
				PromptTokens     int `json:"prompt_tokens"`
				CompletionTokens int `json:"completion_tokens"`
				TotalTokens      int `json:"total_tokens"`
			} `json:"usage"`
		}
		if err := json.Unmarshal(raw, &out); err != nil {
			return InProcessResponse{}, fmt.Errorf("functiongemma: bad response: %w", err)
		}
		if len(out.Choices) == 0 {
			return InProcessResponse{}, fmt.Errorf("functiongemma: no choices")
		}
		content := out.Choices[0].Message.Content
		// With a prefill some servers return only the continuation.
		if opts.Prefill && !strings.Contains(content, "call:") {
			content = functionGemmaPrefix + content
		}
		res := InProcessResponse{}
		if out.Usage != nil {
			res.Usage = &InProcessUsage{PromptTokens: out.Usage.PromptTokens, CompletionTokens: out.Usage.CompletionTokens, TotalTokens: out.Usage.TotalTokens}
		}
		calls := ParseFunctionGemmaCalls(content)
		if len(calls) == 0 {
			res.Content = strings.TrimSpace(strings.TrimPrefix(content, functionGemmaPrefix))
			return res, nil
		}
		for i, c := range calls {
			res.ToolCalls = append(res.ToolCalls, InProcessToolCall{ID: "fg_" + strconv.Itoa(i), Name: c.Name, Arguments: c.Args})
		}
		return res, nil
	}
}

// FunctionGemmaCall is one parsed call.
type FunctionGemmaCall struct {
	Name string
	Args map[string]any
}

var functionGemmaCallRe = regexp.MustCompile(`call:([A-Za-z_][A-Za-z0-9_.-]*)`)

// ParseFunctionGemmaCalls parses FunctionGemma's raw tool-call text, e.g.
// `<start_function_call>call:add{product:<escape>raw honey<escape>,quantity:2}<end_function_call>`.
// String values sit between <escape> markers; bare values are numbers, true/false/null, or kept as
// strings. Several calls in one text are all returned, in order. Text with no call returns nil.
func ParseFunctionGemmaCalls(raw string) []FunctionGemmaCall {
	var out []FunctionGemmaCall
	for _, loc := range functionGemmaCallRe.FindAllStringSubmatchIndex(raw, -1) {
		name := raw[loc[2]:loc[3]]
		rest := raw[loc[1]:]
		args := map[string]any{}
		if strings.HasPrefix(rest, "{") {
			args = parseFunctionGemmaArgs(rest[1:])
		}
		out = append(out, FunctionGemmaCall{Name: name, Args: args})
	}
	return out
}

func parseFunctionGemmaArgs(s string) map[string]any {
	args := map[string]any{}
	for {
		s = strings.TrimLeft(s, " ,\n\t")
		if s == "" || s[0] == '}' {
			return args
		}
		colon := strings.IndexByte(s, ':')
		if colon < 0 {
			return args
		}
		key := strings.TrimSpace(s[:colon])
		if key == "" || strings.ContainsAny(key, "{}<>") {
			return args
		}
		s = s[colon+1:]
		if strings.HasPrefix(s, functionGemmaEscape) {
			s = s[len(functionGemmaEscape):]
			end := strings.Index(s, functionGemmaEscape)
			if end < 0 {
				args[key] = s
				return args
			}
			args[key] = s[:end]
			s = s[end+len(functionGemmaEscape):]
			continue
		}
		end := strings.IndexAny(s, ",}")
		val := s
		if end >= 0 {
			val, s = s[:end], s[end:]
		} else {
			s = ""
		}
		args[key] = functionGemmaScalar(strings.TrimSpace(val))
	}
}

func functionGemmaScalar(v string) any {
	switch v {
	case "true":
		return true
	case "false":
		return false
	case "null":
		return nil
	}
	if i, err := strconv.ParseInt(v, 10, 64); err == nil {
		return i
	}
	if f, err := strconv.ParseFloat(v, 64); err == nil {
		return f
	}
	return v
}
