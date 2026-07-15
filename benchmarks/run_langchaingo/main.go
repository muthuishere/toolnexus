// langchaingo (github.com/tmc/langchaingo) benchmark runner.
//
// Same mock LLM + same fixed scenario as the other runners, so this measures
// langchaingo's framework overhead only (no model latency, no cost).
//
//	cold init   = openai.New (BaseURL -> mock) + OpenAI-functions agent + executor
//	per-request = chains.Run(executor, question)  (the tool-calling loop)
//
// TOOLS: NATIVE (labeled). langchaingo's MCP support is not part of its stable
// tools API, so we register native tools with the SAME names/behavior as the
// shared stdio MCP server (get_weather + add). The mock LLM emits tool calls by
// bare name, so this is behaviorally identical to the MCP path from the loop's
// point of view; only tool discovery differs (in-process vs stdio).
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"time"

	"github.com/tmc/langchaingo/agents"
	"github.com/tmc/langchaingo/chains"
	"github.com/tmc/langchaingo/llms/openai"
	"github.com/tmc/langchaingo/tools"
)

const question = "What's the weather in Paris and what is 2+2?"

// --- native tools (same names/behavior as benchmarks/mcp_server.py) ---

type weatherTool struct{}

func (weatherTool) Name() string        { return "get_weather" }
func (weatherTool) Description() string  { return "Get the current weather for a city." }
func (weatherTool) Call(_ context.Context, input string) (string, error) {
	var a struct {
		City string `json:"city"`
	}
	_ = json.Unmarshal([]byte(input), &a)
	return fmt.Sprintf("The weather in %s is Sunny, 22C.", a.City), nil
}

type addTool struct{}

func (addTool) Name() string       { return "add" }
func (addTool) Description() string { return "Add two integers." }
func (addTool) Call(_ context.Context, input string) (string, error) {
	var a struct {
		A int `json:"a"`
		B int `json:"b"`
	}
	_ = json.Unmarshal([]byte(input), &a)
	return fmt.Sprintf("%d", a.A+a.B), nil
}

func main() {
	runs := flag.Int("runs", 30, "measured runs")
	warmup := flag.Int("warmup", 5, "warmup runs")
	flag.Parse()

	mockURL := os.Getenv("MOCK_URL")
	if mockURL == "" {
		mockURL = "http://127.0.0.1:8900"
	}

	ctx := context.Background()

	// ---- cold init ----
	t0 := time.Now()
	llm, err := openai.New(
		openai.WithToken("sk-mock-not-a-real-key"),
		openai.WithModel("mock-model"),
		openai.WithBaseURL(mockURL),
	)
	if err != nil {
		fmt.Fprintln(os.Stderr, "openai llm error:", err)
		os.Exit(1)
	}
	toolset := []tools.Tool{weatherTool{}, addTool{}}
	agent := agents.NewOpenAIFunctionsAgent(llm, toolset)
	executor := agents.NewExecutor(agent)
	initMs := float64(time.Since(t0).Microseconds()) / 1000.0

	// ---- warmup ----
	for i := 0; i < *warmup; i++ {
		if _, err := chains.Run(ctx, executor, question); err != nil {
			fmt.Fprintln(os.Stderr, "warmup error:", err)
			os.Exit(1)
		}
	}

	// ---- measured ----
	lat := make([]float64, 0, *runs)
	finalText := ""
	for i := 0; i < *runs; i++ {
		s := time.Now()
		out, err := chains.Run(ctx, executor, question)
		if err != nil {
			fmt.Fprintln(os.Stderr, "run error:", err)
			os.Exit(1)
		}
		lat = append(lat, float64(time.Since(s).Microseconds())/1000.0)
		finalText = out
	}

	emit(map[string]any{
		"name":       "langchaingo",
		"framework":  "langchaingo-go-native",
		"language":   "go",
		"init_ms":    round(initMs),
		"tool_count": len(toolset),
		"mean_ms":    round(mean(lat)),
		"p50_ms":     round(pct(lat, 50)),
		"p95_ms":     round(pct(lat, 95)),
		"n":          len(lat),
		"final_ok":   startsWith(finalText, "The weather in Paris"),
	})
}

func emit(m map[string]any) {
	b, _ := json.Marshal(m)
	fmt.Println(string(b))
}

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
	if k < 0 {
		k = 0
	}
	if k >= len(s) {
		k = len(s) - 1
	}
	return s[k]
}

func round(v float64) float64 { return float64(int64(v*1000+0.5)) / 1000 }

func startsWith(s, prefix string) bool {
	return len(s) >= len(prefix) && s[:len(prefix)] == prefix
}
