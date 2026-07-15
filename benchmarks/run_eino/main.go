// Eino (github.com/cloudwego/eino) benchmark runner.
//
// Same mock LLM + same shared stdio MCP server as the other runners, so this
// measures Eino's framework overhead only (no model latency, no cost).
//
//	cold init   = stdio MCP client connect + tools/list (eino-ext mcp GetTools)
//	              + openai ChatModel (BaseURL -> mock) + react.NewAgent
//	per-request = agent.Generate(ctx, [user message])
//
// Tools come from the shared stdio MCP server via eino-ext's mcp tool component
// (github.com/cloudwego/eino-ext/components/tool/mcp), which wraps a
// mark3labs/mcp-go client -> REAL MCP, same wire as every other framework.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"time"

	einomodel "github.com/cloudwego/eino-ext/components/model/openai"
	einomcp "github.com/cloudwego/eino-ext/components/tool/mcp"
	"github.com/cloudwego/eino/compose"
	"github.com/cloudwego/eino/flow/agent/react"
	"github.com/cloudwego/eino/schema"
	mcpclient "github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
)

const question = "What's the weather in Paris and what is 2+2?"

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

	// ---- cold init ----
	t0 := time.Now()

	// 1. shared stdio MCP server -> mark3labs client -> initialize.
	cli, err := mcpclient.NewStdioMCPClient(mcpPy, nil, repo+"/benchmarks/mcp_server.py")
	if err != nil {
		fmt.Fprintln(os.Stderr, "mcp client error:", err)
		os.Exit(1)
	}
	initReq := mcp.InitializeRequest{}
	initReq.Params.ProtocolVersion = mcp.LATEST_PROTOCOL_VERSION
	initReq.Params.ClientInfo = mcp.Implementation{Name: "eino-bench", Version: "1.0.0"}
	if _, err := cli.Initialize(ctx, initReq); err != nil {
		fmt.Fprintln(os.Stderr, "mcp initialize error:", err)
		os.Exit(1)
	}

	// 2. discover tools over MCP (get_weather + add + echo).
	tools, err := einomcp.GetTools(ctx, &einomcp.Config{Cli: cli})
	if err != nil {
		fmt.Fprintln(os.Stderr, "mcp GetTools error:", err)
		os.Exit(1)
	}

	// 3. openai chat model pointed at the mock.
	cm, err := einomodel.NewChatModel(ctx, &einomodel.ChatModelConfig{
		APIKey:  "sk-mock-not-a-real-key",
		BaseURL: mockURL,
		Model:   "mock-model",
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "chat model error:", err)
		os.Exit(1)
	}

	// 4. react agent (tool-calling loop).
	agent, err := react.NewAgent(ctx, &react.AgentConfig{
		ToolCallingModel: cm,
		ToolsConfig:      compose.ToolsNodeConfig{Tools: tools},
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "react agent error:", err)
		os.Exit(1)
	}
	initMs := float64(time.Since(t0).Microseconds()) / 1000.0
	toolCount := len(tools)

	input := []*schema.Message{schema.UserMessage(question)}

	// ---- warmup ----
	for i := 0; i < *warmup; i++ {
		if _, err := agent.Generate(ctx, input); err != nil {
			fmt.Fprintln(os.Stderr, "warmup error:", err)
			os.Exit(1)
		}
	}

	// ---- measured ----
	lat := make([]float64, 0, *runs)
	finalText := ""
	for i := 0; i < *runs; i++ {
		s := time.Now()
		out, err := agent.Generate(ctx, input)
		if err != nil {
			fmt.Fprintln(os.Stderr, "run error:", err)
			os.Exit(1)
		}
		lat = append(lat, float64(time.Since(s).Microseconds())/1000.0)
		finalText = out.Content
	}

	emit(map[string]any{
		"name":       "eino",
		"framework":  "eino-go-mcp",
		"language":   "go",
		"init_ms":    round(initMs),
		"tool_count": toolCount,
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
