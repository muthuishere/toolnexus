// Conversation memory: a Conversation retains history across Send calls.
// Mirrors js/examples/memory.ts.
//
//	OPENROUTER_API_KEY=... go run ./examples/memory
package main

import (
	"context"
	"fmt"
	"os"
	"regexp"
	"strings"

	"github.com/muthuishere/toolnexus/golang"
)

func main() {
	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{})
	if err != nil {
		fmt.Println("toolkit error:", err)
		os.Exit(1)
	}
	defer tk.Close()

	key := os.Getenv("OPENROUTER_API_KEY")
	if key == "" {
		fmt.Println("no OPENROUTER_API_KEY — skipping")
		return
	}
	model := os.Getenv("OPENROUTER_MODEL")
	if model == "" {
		model = "openai/gpt-4o-mini"
	}

	agent := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: "https://openrouter.ai/api/v1",
		Style:   toolnexus.StyleOpenAI,
		Model:   model,
		APIKey:  key,
	})

	convo := agent.Conversation(tk)

	a, err := convo.Send(ctx, "My name is Muthu and my favorite number is 7. Reply with just 'noted'.")
	if err != nil {
		fmt.Println("turn 1 error:", err)
		os.Exit(1)
	}
	fmt.Printf("turn 1: %s | messages: %d\n", trunc(a.Text, 60), len(convo.Messages))

	b, err := convo.Send(ctx, "What is my name and favorite number?")
	if err != nil {
		fmt.Println("turn 2 error:", err)
		os.Exit(1)
	}
	fmt.Printf("turn 2: %s | messages: %d\n", trunc(b.Text, 80), len(convo.Messages))

	remembered := regexp.MustCompile(`(?i)muthu`).MatchString(b.Text) &&
		regexp.MustCompile(`(?i)7|seven`).MatchString(b.Text)
	if !remembered {
		fmt.Println("conversation did not retain memory across turns")
		os.Exit(1)
	}
	fmt.Println("\nconversation memory verified — turn 2 recalled facts from turn 1")
}

func trunc(s string, n int) string {
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) > n {
		return s[:n]
	}
	return s
}
