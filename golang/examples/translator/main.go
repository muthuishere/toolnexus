// A stateless OpenAI-compatible proxy in ~60 lines: OpenAI-shaped requests in,
// translated to an Anthropic upstream, OpenAI-shaped responses out — including
// function calling, where the CLIENT executes the tools.
//
// The point: no agent loop, no toolkit, no conversation state. Every request is
// self-contained, so this scales horizontally and survives restarts. Nothing can
// execute host-side, by construction — Translate takes no toolkit.
//
//	go run ./examples/translator            # then POST to :8080/v1/chat/completions
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

func main() {
	client := toolnexus.CreateClient(toolnexus.ClientOptions{
		BaseURL: "https://api.anthropic.com",
		Style:   toolnexus.StyleAnthropic,
		Model:   "claude-sonnet-4-20250514",
		APIKey:  os.Getenv("ANTHROPIC_API_KEY"), // read from the env, never hardcoded
	})

	http.HandleFunc("/v1/chat/completions", func(w http.ResponseWriter, r *http.Request) {
		// The client's OpenAI request, taken verbatim.
		var req struct {
			Messages   []any `json:"messages"`
			Tools      []any `json:"tools"`
			ToolChoice any   `json:"tool_choice"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		// One provider call. No loop, no tools executed, nothing remembered.
		res, err := client.Translate(r.Context(), toolnexus.TranslateRequest{
			Messages:   req.Messages,
			Tools:      req.Tools,
			ToolChoice: req.ToolChoice,
		})
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}

		// Back out in OpenAI shape. When the model called tools, the client executes
		// them and sends the results back on its NEXT request — which arrives here as
		// another complete, independent history. That is the whole protocol.
		msg := map[string]any{"role": "assistant", "content": res.Text}
		if len(res.ToolCalls) > 0 {
			msg["content"] = nil
			msg["tool_calls"] = res.ToolCallsJSON()
		}
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"object": "chat.completion",
			"model":  res.Model,
			"choices": []any{map[string]any{
				"index": 0, "message": msg, "finish_reason": res.FinishReason,
			}},
			"usage": map[string]any{
				"prompt_tokens":     res.Usage.PromptTokens,
				"completion_tokens": res.Usage.CompletionTokens,
				"total_tokens":      res.Usage.TotalTokens,
			},
		})
	})

	log.Println("stateless translator on :8080 — POST /v1/chat/completions")
	log.Fatal(http.ListenAndServe("127.0.0.1:8080", nil))
}
