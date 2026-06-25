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
}

// ToolCall records a tool the model asked for.
type ToolCall struct {
	Name string         `json:"name"`
	Args map[string]any `json:"args"`
}

// RunResult is the outcome of a Run.
type RunResult struct {
	Text      string     `json:"text"`
	Messages  []any      `json:"messages"`
	ToolCalls []ToolCall `json:"toolCalls"`
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

	for turn := 0; turn < c.maxTurns(); turn++ {
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
		}
		if err := json.Unmarshal(raw, &data); err != nil {
			return RunResult{}, err
		}
		if len(data.Choices) == 0 {
			return RunResult{Text: "", Messages: messages, ToolCalls: toolCalls}, nil
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
			return RunResult{Text: msg.Content, Messages: messages, ToolCalls: toolCalls}, nil
		}

		for _, tc := range msg.ToolCalls {
			args := safeJSONArgs(tc.Function.Arguments)
			toolCalls = append(toolCalls, ToolCall{Name: tc.Function.Name, Args: args})
			result, _ := tk.Execute(ctx, tc.Function.Name, args)
			messages = append(messages, map[string]any{
				"role":         "tool",
				"tool_call_id": tc.ID,
				"content":      result.Output,
			})
		}
	}
	return RunResult{Text: "", Messages: messages, ToolCalls: toolCalls}, nil
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

	headers := map[string]string{
		"x-api-key":         key,
		"anthropic-version": "2023-06-01",
	}

	for turn := 0; turn < c.maxTurns(); turn++ {
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
		}
		if err := json.Unmarshal(raw, &data); err != nil {
			return RunResult{}, err
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
			return RunResult{Text: strings.Join(textParts, ""), Messages: messages, ToolCalls: toolCalls}, nil
		}

		var results []any
		for _, u := range uses {
			in := u.Input
			if in == nil {
				in = map[string]any{}
			}
			toolCalls = append(toolCalls, ToolCall{Name: u.Name, Args: in})
			result, _ := tk.Execute(ctx, u.Name, in)
			results = append(results, map[string]any{
				"type":        "tool_result",
				"tool_use_id": u.ID,
				"content":     result.Output,
				"is_error":    result.IsError,
			})
		}
		messages = append(messages, map[string]any{"role": "user", "content": results})
	}
	return RunResult{Text: "", Messages: messages, ToolCalls: toolCalls}, nil
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
