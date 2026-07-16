// Provider adapters — turn the uniform tool list into each LLM's tool schema.
// Execution is identical for every provider: read the tool name + args the model
// returned, call Toolkit.Execute(name, args), feed output back.

package toolnexus

// OpenAITool is one entry in the OpenAI `tools` array.
type OpenAITool struct {
	Type     string         `json:"type"`
	Function OpenAIFunction `json:"function"`
}

// OpenAIFunction is the function descriptor inside an OpenAITool.
type OpenAIFunction struct {
	Name        string     `json:"name"`
	Description string     `json:"description"`
	Parameters  JSONSchema `json:"parameters"`
}

// AnthropicTool is one entry in the Anthropic `tools` array.
type AnthropicTool struct {
	Name        string     `json:"name"`
	Description string     `json:"description"`
	InputSchema JSONSchema `json:"input_schema"`
}

// GeminiFunctionDeclaration is one function in a Gemini tool.
type GeminiFunctionDeclaration struct {
	Name        string     `json:"name"`
	Description string     `json:"description"`
	Parameters  JSONSchema `json:"parameters"`
}

// GeminiTool wraps all function declarations (Gemini emits a single element).
type GeminiTool struct {
	FunctionDeclarations []GeminiFunctionDeclaration `json:"functionDeclarations"`
}

// ToOpenAI maps tools to the OpenAI tool schema. Returns []any so it slots
// straight into a JSON request body.
func ToOpenAI(tools []Tool) []any {
	out := make([]any, 0, len(tools))
	for _, t := range tools {
		out = append(out, OpenAITool{
			Type: "function",
			Function: OpenAIFunction{
				Name:        t.Name,
				Description: t.Description,
				Parameters:  t.InputSchema,
			},
		})
	}
	return out
}

// ToAnthropic maps tools to the Anthropic tool schema.
func ToAnthropic(tools []Tool) []any {
	out := make([]any, 0, len(tools))
	for _, t := range tools {
		out = append(out, AnthropicTool{
			Name:        t.Name,
			Description: t.Description,
			InputSchema: t.InputSchema,
		})
	}
	return out
}

// ToGemini maps tools to the Gemini tool schema (one element wrapping all).
func ToGemini(tools []Tool) []any {
	decls := make([]GeminiFunctionDeclaration, 0, len(tools))
	for _, t := range tools {
		decls = append(decls, GeminiFunctionDeclaration{
			Name:        t.Name,
			Description: t.Description,
			Parameters:  t.InputSchema,
		})
	}
	return []any{GeminiTool{FunctionDeclarations: decls}}
}
