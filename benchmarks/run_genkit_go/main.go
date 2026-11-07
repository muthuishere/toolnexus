// Firebase Genkit for Go benchmark runner.
//
// Same mock LLM + same shared stdio MCP server as the other runners.
//
//	cold init   = genkit.Init with the compat_oai/openai plugin (BaseURL -> mock)
//	              + mcp plugin GenkitMCPClient (stdio connect + initialize)
//	              + GetActiveTools (tools/list -> genkit tools).
//	per-request = genkit.Generate(prompt, tools); Genkit runs the tool loop.
//
// Tools: REAL MCP (get_weather/add/echo from benchmarks/mcp_server.py).
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/firebase/genkit/go/ai"
	"github.com/firebase/genkit/go/genkit"
	oai "github.com/firebase/genkit/go/plugins/compat_oai/openai"
	"github.com/firebase/genkit/go/plugins/mcp"
	"github.com/openai/openai-go/option"
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
	die := func(what string, err error) { fmt.Fprintln(os.Stderr, what+":", err); os.Exit(1) }

	// ---- cold init ----
	t0 := time.Now()
	g := genkit.Init(ctx, genkit.WithPlugins(&oai.OpenAI{
		APIKey: "sk-mock-not-a-real-key",
		Opts:   []option.RequestOption{option.WithBaseURL(mockURL + "/v1/")},
	}))
	cli, err := mcp.NewGenkitMCPClient(mcp.MCPClientOptions{
		Name:  "bench",
		Stdio: &mcp.StdioConfig{Command: mcpPy, Args: []string{repo + "/benchmarks/mcp_server.py"}},
	})
	if err != nil {
		die("mcp client", err)
	}
	tools, err := cli.GetActiveTools(ctx, g)
	if err != nil {
		die("mcp tools", err)
	}
	refs := make([]ai.ToolRef, len(tools))
	for i, t := range tools {
		refs[i] = t
	}
	initMs := float64(time.Since(t0).Microseconds()) / 1000.0

	once := func() (string, error) {
		r, err := genkit.Generate(ctx, g,
			ai.WithModelName("openai/mock-model"),
			ai.WithPrompt(question),
			ai.WithTools(refs...))
		if err != nil {
			return "", err
		}
		return r.Text(), nil
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
		"name":       "genkit_go",
		"framework":  "genkit-go-mcp",
		"language":   "go",
		"init_ms":    round(initMs),
		"tool_count": len(tools),
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
