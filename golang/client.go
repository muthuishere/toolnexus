// Unified LLM client — the host loop. Give it a base URL + a style
// ("openai" | "anthropic"), and it runs the tool-calling agent loop against a
// Toolkit. Mirrors js/src/client.ts and SPEC.md §8.
package toolnexus

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
)

// ClientStyle selects the wire format.
type ClientStyle string

const (
	StyleOpenAI    ClientStyle = "openai"
	StyleAnthropic ClientStyle = "anthropic"
)

// ClientOptions configures CreateClient.
type ClientOptions struct {
	BaseURL string
	Style   ClientStyle
	Model   string
	// APIKey; if empty, read from env (OPENROUTER_API_KEY / OPENAI_API_KEY /
	// ANTHROPIC_API_KEY). Never printed.
	APIKey string
	// Headers are extra request headers.
	Headers map[string]string
	// SystemPrompt is prepended to the toolkit's SkillsPrompt().
	SystemPrompt string
	// MaxTurns caps the tool-calling loop; 0 ⇒ 10.
	MaxTurns int
	// Hooks are optional lifecycle callbacks around the loop. A nil Hooks (or any
	// nil field) is skipped. Any hook returning an error aborts the run.
	Hooks *Hooks
}

// Hooks are lifecycle callbacks around the agent loop. Each may observe; the
// noted ones may mutate or short-circuit. A nil field is skipped, and any hook
// returning an error aborts the run with that error. Mirrors js Hooks
// (src/client.ts) and SPEC.md §8.
type Hooks struct {
	// BeforeLLM runs before each model call. Return a non-nil LLMOverride to
	// replace messages and/or tools (trim/inject history, swap tools).
	BeforeLLM func(ctx context.Context, ev BeforeLLMEvent) (*LLMOverride, error)
	// AfterLLM runs after each model call (observe: logging, cost, tracing).
	AfterLLM func(ctx context.Context, ev AfterLLMEvent) error
	// BeforeTool runs before a tool executes. Return an override with Result set
	// to SHORT-CIRCUIT (deny / cache hit / dry-run — the real tool never runs),
	// or with Args set to rewrite the call.
	BeforeTool func(ctx context.Context, ev BeforeToolEvent) (*ToolOverride, error)
	// AfterTool runs after a tool executes. Return an override with Result set to
	// replace (redact / annotate) the output.
	AfterTool func(ctx context.Context, ev AfterToolEvent) (*ToolOverride, error)
}

// BeforeLLMEvent is passed to Hooks.BeforeLLM.
type BeforeLLMEvent struct {
	Messages []any
	Tools    []any
	Model    string
	Turn     int
}

// AfterLLMEvent is passed to Hooks.AfterLLM. Response is the raw, decoded
// provider payload (carries usage).
type AfterLLMEvent struct {
	Response map[string]any
	Model    string
	Turn     int
}

// BeforeToolEvent is passed to Hooks.BeforeTool.
type BeforeToolEvent struct {
	Name string
	Args map[string]any
	ID   string
	Turn int
}

// AfterToolEvent is passed to Hooks.AfterTool.
type AfterToolEvent struct {
	Name   string
	Args   map[string]any
	Result ToolResult
	ID     string
	Turn   int
}

// LLMOverride is returned by Hooks.BeforeLLM. A nil field leaves that value
// unchanged; a non-nil (even empty) slice replaces it.
type LLMOverride struct {
	Messages []any
	Tools    []any
}

// ToolOverride is returned by Hooks.BeforeTool / Hooks.AfterTool. In BeforeTool,
// a non-nil Result short-circuits the tool; otherwise a non-nil Args rewrites
// the call. In AfterTool, a non-nil Result replaces the result.
type ToolOverride struct {
	Args   map[string]any
	Result *ToolResult
}

// ToolCall records a tool the model asked for, plus its result + metadata.
type ToolCall struct {
	Name     string         `json:"name"`
	Args     map[string]any `json:"args"`
	Output   string         `json:"output"`
	IsError  bool           `json:"isError"`
	Metadata map[string]any `json:"metadata,omitempty"`
}

// Usage is token usage, summed across every LLM round trip in the run.
type Usage struct {
	PromptTokens     int `json:"promptTokens"`
	CompletionTokens int `json:"completionTokens"`
	TotalTokens      int `json:"totalTokens"`
}

// RunResult is the outcome of a Run.
type RunResult struct {
	Text      string     `json:"text"`
	Messages  []any      `json:"messages"`
	ToolCalls []ToolCall `json:"toolCalls"`
	// ToolCallCount is the total number of tool calls (= len(ToolCalls)).
	ToolCallCount int `json:"toolCallCount"`
	// Turns is the number of LLM round trips.
	Turns int `json:"turns"`
	// Usage is the aggregated token usage across all turns.
	Usage Usage `json:"usage"`
	// Model is the model used.
	Model string `json:"model"`
}

// addUsage sums one response's usage object into acc. Numbers arrive from JSON
// decode as float64. openai style reads prompt_tokens/completion_tokens/
// total_tokens; anthropic reads input_tokens→prompt, output_tokens→completion,
// total = input + output.
func addUsage(acc *Usage, raw map[string]any, style string) {
	if raw == nil {
		return
	}
	num := func(k string) int {
		if v, ok := raw[k].(float64); ok {
			return int(v)
		}
		return 0
	}
	if style == string(StyleAnthropic) {
		in, out := num("input_tokens"), num("output_tokens")
		acc.PromptTokens += in
		acc.CompletionTokens += out
		acc.TotalTokens += in + out
		return
	}
	prompt, completion := num("prompt_tokens"), num("completion_tokens")
	acc.PromptTokens += prompt
	acc.CompletionTokens += completion
	if total := num("total_tokens"); total != 0 {
		acc.TotalTokens += total
	} else {
		acc.TotalTokens += prompt + completion
	}
}

// Client runs the agent loop against a Toolkit.
type Client struct {
	opts ClientOptions
	http *http.Client
}

// CreateClient builds a Client.
func CreateClient(opts ClientOptions) *Client {
	return &Client{opts: opts, http: http.DefaultClient}
}

func (c *Client) maxTurns() int {
	if c.opts.MaxTurns > 0 {
		return c.opts.MaxTurns
	}
	return 10
}

// resolveKey picks the API key from opts or the environment. The value is never
// logged.
func (c *Client) resolveKey() (string, error) {
	if c.opts.APIKey != "" {
		return c.opts.APIKey, nil
	}
	candidates := []string{os.Getenv("OPENROUTER_API_KEY")}
	if c.opts.Style == StyleAnthropic {
		candidates = append(candidates, os.Getenv("ANTHROPIC_API_KEY"), os.Getenv("OPENAI_API_KEY"))
	} else {
		candidates = append(candidates, os.Getenv("OPENAI_API_KEY"), os.Getenv("ANTHROPIC_API_KEY"))
	}
	for _, k := range candidates {
		if k != "" {
			return k, nil
		}
	}
	return "", fmt.Errorf("no API key: set APIKey or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY")
}

func (c *Client) system(tk *Toolkit) string {
	parts := []string{}
	if c.opts.SystemPrompt != "" {
		parts = append(parts, c.opts.SystemPrompt)
	}
	if sp := tk.SkillsPrompt(); sp != "" {
		parts = append(parts, sp)
	}
	return strings.Join(parts, "\n\n")
}

// runTool runs one tool through the BeforeTool/AfterTool hooks: BeforeTool may
// short-circuit (Result) or rewrite (Args), then tk.Execute runs, then AfterTool
// may replace the result. It returns the args actually used and the final
// result. Mirrors js Client.runTool. Hook errors abort the run.
func (c *Client) runTool(ctx context.Context, tk *Toolkit, name string, args map[string]any, id string, turn int) (map[string]any, ToolResult, error) {
	h := c.opts.Hooks
	a := args
	if h != nil && h.BeforeTool != nil {
		ov, err := h.BeforeTool(ctx, BeforeToolEvent{Name: name, Args: a, ID: id, Turn: turn})
		if err != nil {
			return a, ToolResult{}, err
		}
		if ov != nil {
			if ov.Result != nil {
				return a, *ov.Result, nil // short-circuit (deny / cache / dry-run)
			}
			if ov.Args != nil {
				a = ov.Args
			}
		}
	}
	result, err := tk.Execute(ctx, name, a)
	if err != nil {
		return a, result, err
	}
	if h != nil && h.AfterTool != nil {
		ov, err := h.AfterTool(ctx, AfterToolEvent{Name: name, Args: a, Result: result, ID: id, Turn: turn})
		if err != nil {
			return a, result, err
		}
		if ov != nil && ov.Result != nil {
			result = *ov.Result
		}
	}
	return a, result, nil
}

// Run executes the agent loop for one prompt against the toolkit.
func (c *Client) Run(ctx context.Context, prompt string, tk *Toolkit) (RunResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if c.opts.Style == StyleAnthropic {
		return c.runAnthropic(ctx, prompt, tk)
	}
	return c.runOpenAI(ctx, prompt, tk)
}

func (c *Client) postJSON(ctx context.Context, endpoint string, headers map[string]string, body any) ([]byte, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	for k, v := range c.opts.Headers {
		req.Header.Set(k, v)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("LLM %d: %s", resp.StatusCode, string(out))
	}
	return out, nil
}

// ---- OpenAI-style: POST {baseURL}/chat/completions ----

func (c *Client) runOpenAI(ctx context.Context, prompt string, tk *Toolkit) (RunResult, error) {
	key, err := c.resolveKey()
	if err != nil {
		return RunResult{}, err
	}
	endpoint := strings.TrimRight(c.opts.BaseURL, "/") + "/chat/completions"

	var messages []any
	if sys := c.system(tk); sys != "" {
		messages = append(messages, map[string]any{"role": "system", "content": sys})
	}
	messages = append(messages, map[string]any{"role": "user", "content": prompt})
	tools := tk.ToOpenAI()
	var toolCalls []ToolCall
	var usage Usage
	turns := 0

	for turn := 0; turn < c.maxTurns(); turn++ {
		turns++
		if c.opts.Hooks != nil && c.opts.Hooks.BeforeLLM != nil {
			ov, err := c.opts.Hooks.BeforeLLM(ctx, BeforeLLMEvent{Messages: messages, Tools: tools, Model: c.opts.Model, Turn: turn})
			if err != nil {
				return RunResult{}, err
			}
			if ov != nil {
				if ov.Messages != nil {
					messages = ov.Messages
				}
				if ov.Tools != nil {
					tools = ov.Tools
				}
			}
		}
		body := map[string]any{
			"model":       c.opts.Model,
			"messages":    messages,
			"tools":       tools,
			"tool_choice": "auto",
		}
		raw, err := c.postJSON(ctx, endpoint, map[string]string{"Authorization": "Bearer " + key}, body)
		if err != nil {
			return RunResult{}, err
		}
		var data struct {
			Choices []struct {
				Message struct {
					Role      string `json:"role"`
					Content   string `json:"content"`
					ToolCalls []struct {
						ID       string `json:"id"`
						Type     string `json:"type"`
						Function struct {
							Name      string `json:"name"`
							Arguments string `json:"arguments"`
						} `json:"function"`
					} `json:"tool_calls"`
				} `json:"message"`
			} `json:"choices"`
			Usage map[string]any `json:"usage"`
		}
		if err := json.Unmarshal(raw, &data); err != nil {
			return RunResult{}, err
		}
		addUsage(&usage, data.Usage, string(StyleOpenAI))
		if c.opts.Hooks != nil && c.opts.Hooks.AfterLLM != nil {
			if err := c.opts.Hooks.AfterLLM(ctx, AfterLLMEvent{Response: decodeResponse(raw), Model: c.opts.Model, Turn: turn}); err != nil {
				return RunResult{}, err
			}
		}
		if len(data.Choices) == 0 {
			return c.result("", messages, toolCalls, turns, usage), nil
		}
		msg := data.Choices[0].Message

		// Append the assistant message (preserving tool_calls if any).
		asst := map[string]any{"role": "assistant", "content": msg.Content}
		if len(msg.ToolCalls) > 0 {
			var rawCalls []any
			for _, tc := range msg.ToolCalls {
				rawCalls = append(rawCalls, map[string]any{
					"id":   tc.ID,
					"type": "function",
					"function": map[string]any{
						"name":      tc.Function.Name,
						"arguments": tc.Function.Arguments,
					},
				})
			}
			asst["tool_calls"] = rawCalls
		}
		messages = append(messages, asst)

		if len(msg.ToolCalls) == 0 {
			return c.result(msg.Content, messages, toolCalls, turns, usage), nil
		}

		// Execute all tool calls in this turn concurrently (true parallel tool
		// calling). Each result is written to its own slot in results[], so the
		// appended tool messages keep the same order the model emitted them
		// (deterministic output, independent of completion order). toolCalls is
		// appended under a mutex to avoid a data race.
		results := make([]any, len(msg.ToolCalls))
		var wg sync.WaitGroup
		var mu sync.Mutex
		var hookErr error
		for i, tc := range msg.ToolCalls {
			wg.Add(1)
			go func(i int, tc struct {
				ID       string `json:"id"`
				Type     string `json:"type"`
				Function struct {
					Name      string `json:"name"`
					Arguments string `json:"arguments"`
				} `json:"function"`
			}) {
				defer wg.Done()
				args, result, err := c.runTool(ctx, tk, tc.Function.Name, safeJSONArgs(tc.Function.Arguments), tc.ID, turn)
				mu.Lock()
				if err != nil {
					if hookErr == nil {
						hookErr = err
					}
					mu.Unlock()
					return
				}
				toolCalls = append(toolCalls, ToolCall{
					Name:     tc.Function.Name,
					Args:     args,
					Output:   result.Output,
					IsError:  result.IsError,
					Metadata: result.Metadata,
				})
				mu.Unlock()
				results[i] = map[string]any{
					"role":         "tool",
					"tool_call_id": tc.ID,
					"content":      result.Output,
				}
			}(i, tc)
		}
		wg.Wait()
		if hookErr != nil {
			return RunResult{}, hookErr
		}
		messages = append(messages, results...)
	}
	return c.result("", messages, toolCalls, turns, usage), nil
}

// result assembles a RunResult, filling in the derived telemetry fields.
func (c *Client) result(text string, messages []any, toolCalls []ToolCall, turns int, usage Usage) RunResult {
	return RunResult{
		Text:          text,
		Messages:      messages,
		ToolCalls:     toolCalls,
		ToolCallCount: len(toolCalls),
		Turns:         turns,
		Usage:         usage,
		Model:         c.opts.Model,
	}
}

// ---- Anthropic-style: POST {baseURL}/messages ----

func (c *Client) runAnthropic(ctx context.Context, prompt string, tk *Toolkit) (RunResult, error) {
	key, err := c.resolveKey()
	if err != nil {
		return RunResult{}, err
	}
	base := strings.TrimRight(c.opts.BaseURL, "/")
	endpoint := base + "/v1/messages"
	if strings.HasSuffix(base, "/v1") {
		endpoint = base + "/messages"
	}
	system := c.system(tk)
	messages := []any{map[string]any{"role": "user", "content": prompt}}
	tools := tk.ToAnthropic()
	var toolCalls []ToolCall
	var usage Usage
	turns := 0

	headers := map[string]string{
		"x-api-key":         key,
		"anthropic-version": "2023-06-01",
	}

	for turn := 0; turn < c.maxTurns(); turn++ {
		turns++
		if c.opts.Hooks != nil && c.opts.Hooks.BeforeLLM != nil {
			ov, err := c.opts.Hooks.BeforeLLM(ctx, BeforeLLMEvent{Messages: messages, Tools: tools, Model: c.opts.Model, Turn: turn})
			if err != nil {
				return RunResult{}, err
			}
			if ov != nil {
				if ov.Messages != nil {
					messages = ov.Messages
				}
				if ov.Tools != nil {
					tools = ov.Tools
				}
			}
		}
		body := map[string]any{
			"model":      c.opts.Model,
			"max_tokens": 4096,
			"messages":   messages,
			"tools":      tools,
		}
		if system != "" {
			body["system"] = system
		}
		raw, err := c.postJSON(ctx, endpoint, headers, body)
		if err != nil {
			return RunResult{}, err
		}
		var data struct {
			Content []struct {
				Type  string         `json:"type"`
				Text  string         `json:"text"`
				ID    string         `json:"id"`
				Name  string         `json:"name"`
				Input map[string]any `json:"input"`
			} `json:"content"`
			Usage map[string]any `json:"usage"`
		}
		if err := json.Unmarshal(raw, &data); err != nil {
			return RunResult{}, err
		}
		addUsage(&usage, data.Usage, string(StyleAnthropic))
		if c.opts.Hooks != nil && c.opts.Hooks.AfterLLM != nil {
			if err := c.opts.Hooks.AfterLLM(ctx, AfterLLMEvent{Response: decodeResponse(raw), Model: c.opts.Model, Turn: turn}); err != nil {
				return RunResult{}, err
			}
		}

		// Re-marshal the content blocks as the assistant message.
		var content []any
		var uses []struct {
			ID    string
			Name  string
			Input map[string]any
		}
		var textParts []string
		for _, b := range data.Content {
			switch b.Type {
			case "text":
				content = append(content, map[string]any{"type": "text", "text": b.Text})
				textParts = append(textParts, b.Text)
			case "tool_use":
				content = append(content, map[string]any{
					"type":  "tool_use",
					"id":    b.ID,
					"name":  b.Name,
					"input": b.Input,
				})
				uses = append(uses, struct {
					ID    string
					Name  string
					Input map[string]any
				}{b.ID, b.Name, b.Input})
			}
		}
		messages = append(messages, map[string]any{"role": "assistant", "content": content})

		if len(uses) == 0 {
			return c.result(strings.Join(textParts, ""), messages, toolCalls, turns, usage), nil
		}

		// Execute all tool_use blocks in this turn concurrently. results[i] keeps
		// the original block order so tool_result blocks map deterministically to
		// their tool_use ids; toolCalls is appended under a mutex.
		results := make([]any, len(uses))
		var wg sync.WaitGroup
		var mu sync.Mutex
		var hookErr error
		for i, u := range uses {
			wg.Add(1)
			go func(i int, u struct {
				ID    string
				Name  string
				Input map[string]any
			}) {
				defer wg.Done()
				in := u.Input
				if in == nil {
					in = map[string]any{}
				}
				args, result, err := c.runTool(ctx, tk, u.Name, in, u.ID, turn)
				mu.Lock()
				if err != nil {
					if hookErr == nil {
						hookErr = err
					}
					mu.Unlock()
					return
				}
				toolCalls = append(toolCalls, ToolCall{
					Name:     u.Name,
					Args:     args,
					Output:   result.Output,
					IsError:  result.IsError,
					Metadata: result.Metadata,
				})
				mu.Unlock()
				results[i] = map[string]any{
					"type":        "tool_result",
					"tool_use_id": u.ID,
					"content":     result.Output,
					"is_error":    result.IsError,
				}
			}(i, u)
		}
		wg.Wait()
		if hookErr != nil {
			return RunResult{}, hookErr
		}
		messages = append(messages, map[string]any{"role": "user", "content": results})
	}
	return c.result("", messages, toolCalls, turns, usage), nil
}

// decodeResponse decodes a raw provider response body into a map for AfterLLM.
// Returns nil on failure (the hook still observes the model/turn).
func decodeResponse(raw []byte) map[string]any {
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil
	}
	return out
}

// safeJSONArgs parses a tool-call arguments JSON string into a map, defaulting to
// an empty map on failure.
func safeJSONArgs(s string) map[string]any {
	if s == "" {
		return map[string]any{}
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(s), &out); err != nil || out == nil {
		return map[string]any{}
	}
	return out
}
