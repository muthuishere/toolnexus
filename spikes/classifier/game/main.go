package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

const (
	orBase   = "https://openrouter.ai/api/v1"
	jevModel = "typesafe/jev-1.13"
	bigModel = "anthropic/claude-sonnet-4"
)

func newClassifier() *Classifier {
	return &Classifier{BaseURL: orBase, Model: jevModel, APIKeyEnv: "OPENROUTER_API_KEY",
		HTTP: &http.Client{Timeout: 20 * time.Second}}
}
func newLLM() *tn.Client {
	return tn.CreateClient(tn.ClientOptions{BaseURL: orBase, Style: "openai", Model: bigModel,
		APIKey: os.Getenv("OPENROUTER_API_KEY"), MaxTurns: 1, TimeoutMs: 60000})
}

func main() {
	seed := flag.Int64("seed", 7, "")
	moves := flag.Int("moves", 40, "")
	only := flag.String("only", "", "run one player")
	out := flag.String("out", "", "write results json")
	flag.Parse()

	players := map[string]func() Player{
		"heuristic":  func() Player { return Heuristic{} },
		"judge-only": func() Player { return &JudgePlayer{C: newClassifier()} },
		"llm-only":   func() Player { return &LLMPlayer{Client: newLLM()} },
		"hybrid":     func() Player { return &Hybrid{Judge: &JudgePlayer{C: newClassifier()}, Client: newLLM(), Every: 10} },
	}
	order := []string{"heuristic", "judge-only", "llm-only", "hybrid"}
	if *only != "" {
		order = []string{*only}
	}
	var results []Result
	for _, name := range order {
		r := Play(players[name](), *seed, *moves, true)
		results = append(results, r)
	}
	fmt.Printf("\n%-11s %7s %7s %7s %9s  %s\n", "PLAYER", "SCORE", "MAX", "MOVES", "WALL", "COST / CALLS")
	for _, r := range results {
		fmt.Printf("%-11s %7d %7d %7d %8.1fs  %s\n", r.Player, r.Score, r.MaxTile, r.Moves,
			float64(r.WallMs)/1000, r.Stats)
	}
	if *out != "" {
		b, _ := json.MarshalIndent(results, "", " ")
		os.WriteFile(*out, b, 0644)
		fmt.Println("\nwrote", *out)
	}
}
