// Live OpenRouter tool-calling round trip — proves the toolkit drives a real LLM.
// Reads OPENROUTER_API_KEY from the environment (never hardcode it).
//
//	OPENROUTER_API_KEY=... go run ./examples/openrouter
//
// Flow: give the model the toolkit's OpenAI tool schema, ask it to echo a string
// via the `everything_echo` MCP tool, run the tool call locally, feed the result
// back, and print the final assistant answer.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/muthuishere/toolnexus/golang"
)

const endpoint = "https://openrouter.ai/api/v1/chat/completions"

type chatMessage struct {
	Role       string     `json:"role"`
	Content    string     `json:"content,omitempty"`
	ToolCallID string     `json:"tool_call_id,omitempty"`
	ToolCalls  []toolCall `json:"tool_calls,omitempty"`
}

type toolCall struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

type chatRequest struct {
	Model      string        `json:"model"`
	Messages   []chatMessage `json:"messages"`
	Tools      []any         `json:"tools"`
	ToolChoice string        `json:"tool_choice"`
}

type chatResponse struct {
	Choices []struct {
		Message struct {
			Role      string     `json:"role"`
			Content   string     `json:"content"`
			ToolCalls []toolCall `json:"tool_calls"`
		} `json:"message"`
	} `json:"choices"`
}

func main() {
	key := os.Getenv("OPENROUTER_API_KEY")
	if key == "" {
		log.Fatal("OPENROUTER_API_KEY not set")
	}
	model := os.Getenv("OPENROUTER_MODEL")
	if model == "" {
		model = "openai/gpt-4o-mini"
	}

	_, thisFile, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples")

	ctx := context.Background()
	tk, err := toolnexus.CreateToolkit(ctx, toolnexus.Options{
		McpConfig: filepath.Join(root, "mcp.json"),
		SkillsDir: []string{filepath.Join(root, "skills")},
	})
	if err != nil {
		log.Fatal(err)
	}
	defer tk.Close()

	httpClient := &http.Client{Timeout: 60 * time.Second}

	chat := func(messages []chatMessage) (*chatResponse, error) {
		body, err := json.Marshal(chatRequest{
			Model:      model,
			Messages:   messages,
			Tools:      tk.ToOpenAI(),
			ToolChoice: "auto",
		})
		if err != nil {
			return nil, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer "+key)
		req.Header.Set("Content-Type", "application/json")
		resp, err := httpClient.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("OpenRouter %d: %s", resp.StatusCode, string(raw))
		}
		var parsed chatResponse
		if err := json.Unmarshal(raw, &parsed); err != nil {
			return nil, err
		}
		return &parsed, nil
	}

	messages := []chatMessage{
		{Role: "system", Content: "You are a tool-using agent. Use the provided tools when helpful.\n\n" + tk.SkillsPrompt()},
		{Role: "user", Content: `Use the everything_echo tool to echo the exact string "toolnexus works". Then tell me what it returned.`},
	}

	final := ""
	for turn := 0; turn < 5; turn++ {
		data, err := chat(messages)
		if err != nil {
			log.Fatal(err)
		}
		if len(data.Choices) == 0 {
			break
		}
		msg := data.Choices[0].Message
		messages = append(messages, chatMessage{Role: "assistant", Content: msg.Content, ToolCalls: msg.ToolCalls})
		if len(msg.ToolCalls) == 0 {
			final = msg.Content
			break
		}
		for _, call := range msg.ToolCalls {
			var args map[string]any
			if call.Function.Arguments != "" {
				_ = json.Unmarshal([]byte(call.Function.Arguments), &args)
			}
			argJSON, _ := json.Marshal(args)
			fmt.Printf("-> model called %s(%s)\n", call.Function.Name, string(argJSON))
			result, err := tk.Execute(ctx, call.Function.Name, args)
			if err != nil {
				log.Fatal(err)
			}
			status := "ok"
			if result.IsError {
				status = "ERROR"
			}
			preview := result.Output
			if len(preview) > 120 {
				preview = preview[:120]
			}
			fmt.Printf("<- %s: %s\n", status, preview)
			messages = append(messages, chatMessage{Role: "tool", ToolCallID: call.ID, Content: result.Output})
		}
	}

	fmt.Println("\nFINAL ASSISTANT:\n" + final)

	if !strings.Contains(strings.ToLower(final), "toolnexus works") {
		log.Fatal("expected echoed string in final answer")
	}
	fmt.Println("\nGo OpenRouter round trip OK")
}
