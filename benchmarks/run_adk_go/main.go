// Google ADK for Go (google.golang.org/adk) benchmark runner.
//
// Same mock LLM + same shared stdio MCP server as the other runners.
// ADK Go ships only Gemini/Apigee model.LLM implementations, so this file
// contains a minimal model.LLM adapter (openAILLM, below) that speaks the
// OpenAI /chat/completions wire to the mock. That adapter is part of what is
// measured; it is a plain net/http + encoding/json translation, no retries.
//
//	cold init   = mcptoolset (stdio CommandTransport -> shared MCP server)
//	              + llmagent + runner + in-memory session service.
//	              NOTE: ADK's mcptoolset connects LAZILY (first request), so
//	              the MCP connect + tools/list lands in warmup, not init_ms.
//	per-request = new session + runner.Run(question) drained to the final event.
//
// Tools: REAL MCP (get_weather/add/echo from benchmarks/mcp_server.py).
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"iter"
	"net/http"
	"os"
	"os/exec"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
	"google.golang.org/genai"

	"google.golang.org/adk/agent"
	"google.golang.org/adk/agent/llmagent"
	"google.golang.org/adk/model"
	"google.golang.org/adk/runner"
	"google.golang.org/adk/session"
	"google.golang.org/adk/tool"
	"google.golang.org/adk/tool/mcptoolset"
)

const question = "What's the weather in Paris and what is 2+2?"

var toolCount atomic.Int64

// ---- minimal OpenAI-compatible model.LLM adapter ----

type openAILLM struct {
	url    string
	client *http.Client
}

func (m *openAILLM) Name() string { return "mock-model" }

func (m *openAILLM) GenerateContent(ctx context.Context, req *model.LLMRequest, stream bool) iter.Seq2[*model.LLMResponse, error] {
	return func(yield func(*model.LLMResponse, error) bool) {
		resp, err := m.call(ctx, req)
		yield(resp, err)
	}
}

func (m *openAILLM) call(ctx context.Context, req *model.LLMRequest) (*model.LLMResponse, error) {
	msgs := []map[string]any{}
	if req.Config != nil && req.Config.SystemInstruction != nil {
		var sb strings.Builder
		for _, p := range req.Config.SystemInstruction.Parts {
			sb.WriteString(p.Text)
		}
		msgs = append(msgs, map[string]any{"role": "system", "content": sb.String()})
	}
	for _, c := range req.Contents {
		var text strings.Builder
		var calls []map[string]any
		for _, p := range c.Parts {
			switch {
			case p.FunctionCall != nil:
				a, _ := json.Marshal(p.FunctionCall.Args)
				calls = append(calls, map[string]any{"id": p.FunctionCall.ID, "type": "function",
					"function": map[string]any{"name": p.FunctionCall.Name, "arguments": string(a)}})
			case p.FunctionResponse != nil:
				r, _ := json.Marshal(p.FunctionResponse.Response)
				msgs = append(msgs, map[string]any{"role": "tool", "tool_call_id": p.FunctionResponse.ID, "content": string(r)})
			default:
				text.WriteString(p.Text)
			}
		}
		if c.Role == "model" {
			m := map[string]any{"role": "assistant", "content": text.String()}
			if len(calls) > 0 {
				m["tool_calls"] = calls
			}
			msgs = append(msgs, m)
		} else if text.Len() > 0 {
			msgs = append(msgs, map[string]any{"role": "user", "content": text.String()})
		}
	}
	tools := []map[string]any{}
	if req.Config != nil {
		for _, t := range req.Config.Tools {
			for _, fd := range t.FunctionDeclarations {
				var params any = fd.ParametersJsonSchema
				if params == nil && fd.Parameters != nil {
					params = fd.Parameters
				}
				if params == nil {
					params = map[string]any{"type": "object"}
				}
				tools = append(tools, map[string]any{"type": "function", "function": map[string]any{
					"name": fd.Name, "description": fd.Description, "parameters": params}})
			}
		}
	}
	toolCount.Store(int64(len(tools)))
	body := map[string]any{"model": "mock-model", "messages": msgs}
	if len(tools) > 0 {
		body["tools"] = tools
	}
	b, _ := json.Marshal(body)
	hr, _ := http.NewRequestWithContext(ctx, "POST", m.url+"/v1/chat/completions", bytes.NewReader(b))
	hr.Header.Set("Content-Type", "application/json")
	hr.Header.Set("Authorization", "Bearer sk-mock-not-a-real-key")
	res, err := m.client.Do(hr)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	var out struct {
		Choices []struct {
			Message struct {
				Content   *string `json:"content"`
				ToolCalls []struct {
					ID       string `json:"id"`
					Function struct {
						Name      string `json:"name"`
						Arguments string `json:"arguments"`
					} `json:"function"`
				} `json:"tool_calls"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, err
	}
	if len(out.Choices) == 0 {
		return nil, fmt.Errorf("no choices (HTTP %d)", res.StatusCode)
	}
	msg := out.Choices[0].Message
	content := &genai.Content{Role: "model"}
	if msg.Content != nil && *msg.Content != "" {
		content.Parts = append(content.Parts, &genai.Part{Text: *msg.Content})
	}
	for _, tc := range msg.ToolCalls {
		args := map[string]any{}
		_ = json.Unmarshal([]byte(tc.Function.Arguments), &args)
		content.Parts = append(content.Parts, &genai.Part{FunctionCall: &genai.FunctionCall{ID: tc.ID, Name: tc.Function.Name, Args: args}})
	}
	return &model.LLMResponse{Content: content, TurnComplete: true}, nil
}

func main() {
	runs := flag.Int("runs", 30, "measured runs")
	warmup := flag.Int("warmup", 5, "warmup runs")
	flag.Parse()

	repo := os.Getenv("BENCH_REPO")
	mcpPy := os.Getenv("MCP_PYTHON")
	mockURL := os.Getenv("MOCK_URL")
	if mockURL == "" {
		mockURL = "http://127.0.0.1:8900"
	}
	ctx := context.Background()
	die := func(what string, err error) { fmt.Fprintln(os.Stderr, what+":", err); os.Exit(1) }

	// ---- cold init ----
	t0 := time.Now()
	ts, err := mcptoolset.New(mcptoolset.Config{
		Transport: &mcp.CommandTransport{Command: exec.Command(mcpPy, repo+"/benchmarks/mcp_server.py")},
	})
	if err != nil {
		die("mcptoolset", err)
	}
	a, err := llmagent.New(llmagent.Config{
		Name:        "bench_agent",
		Model:       &openAILLM{url: mockURL, client: &http.Client{}},
		Instruction: "You are a helpful assistant.",
		Toolsets:    []tool.Toolset{ts},
	})
	if err != nil {
		die("llmagent", err)
	}
	svc := session.InMemoryService()
	r, err := runner.New(runner.Config{AppName: "bench", Agent: a, SessionService: svc})
	if err != nil {
		die("runner", err)
	}
	initMs := float64(time.Since(t0).Microseconds()) / 1000.0

	once := func() (string, error) {
		s, err := svc.Create(ctx, &session.CreateRequest{AppName: "bench", UserID: "u"})
		if err != nil {
			return "", err
		}
		final := ""
		for ev, err := range r.Run(ctx, "u", s.Session.ID(), genai.NewContentFromText(question, genai.RoleUser), agent.RunConfig{}) {
			if err != nil {
				return "", err
			}
			if ev.Content != nil {
				for _, p := range ev.Content.Parts {
					if p.Text != "" {
						final = p.Text
					}
				}
			}
		}
		return final, nil
	}

	for i := 0; i < *warmup; i++ {
		if _, err := once(); err != nil {
			die("warmup error", err)
		}
	}
	lat := make([]float64, 0, *runs)
	finalText := ""
	for i := 0; i < *runs; i++ {
		s := time.Now()
		out, err := once()
		if err != nil {
			die("run error", err)
		}
		lat = append(lat, float64(time.Since(s).Microseconds())/1000.0)
		finalText = out
	}

	emit(map[string]any{
		"name":       "adk_go",
		"framework":  "adk-go-mcp",
		"language":   "go",
		"init_ms":    round(initMs),
		"tool_count": toolCount.Load(),
		"mean_ms":    round(mean(lat)),
		"p50_ms":     round(pct(lat, 50)),
		"p95_ms":     round(pct(lat, 95)),
		"n":          len(lat),
		"final_ok":   strings.HasPrefix(finalText, "The weather in Paris"),
	})
}

func emit(m map[string]any) { b, _ := json.Marshal(m); fmt.Println(string(b)) }

func mean(x []float64) float64 {
	if len(x) == 0 {
		return 0
	}
	s := 0.0
	for _, v := range x {
		s += v
	}
	return s / float64(len(x))
}

func pct(x []float64, p int) float64 {
	if len(x) == 0 {
		return 0
	}
	s := append([]float64(nil), x...)
	sort.Float64s(s)
	k := int(float64(p)/100.0*float64(len(s)-1) + 0.5)
	if k >= len(s) {
		k = len(s) - 1
	}
	return s[k]
}

func round(v float64) float64 { return float64(int64(v*1000+0.5)) / 1000 }
