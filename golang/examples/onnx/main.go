// An ONNX model running IN THIS PROCESS as the LLM, with no server and no socket.
//
// The point is not that a local model exists — it is that toolnexus needs ONE
// option changed to use one. HTTPClient replaces the wire call; the agent loop,
// MCP servers, skills and every other tool source keep working untouched.
//
// Setup (see README.md for the copy-paste version):
//
//	ONNX_MODEL_DIR=/path/to/qwen-int8 \
//	ORT_DYLIB_PATH=/path/to/libonnxruntime.dylib \
//	CGO_LDFLAGS="-L/path/to/libtokenizers-dir" \
//	go run .
//
// Deliberately NOT hiding the work: the chat template, the KV-cache plumbing and
// the tool-call parsing are all visible here, because that is the code you
// actually own when you move a model in-process.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"

	"github.com/daulet/tokenizers"
	tn "github.com/muthuishere/toolnexus/golang"
	ort "github.com/yalue/onnxruntime_go"
)

// ---------------------------------------------------------------------------
// The model. Greedy decoding over a raw ONNX graph with the KV cache threaded
// through by hand — `--task text-generation-with-past` is what produces those
// past_key_values/present pairs, and skipping the cache makes generation
// quadratic rather than merely slow.
// ---------------------------------------------------------------------------

type modelConfig struct {
	NumHiddenLayers   int `json:"num_hidden_layers"`
	NumKeyValueHeads  int `json:"num_key_value_heads"`
	NumAttentionHeads int `json:"num_attention_heads"`
	HiddenSize        int `json:"hidden_size"`
	EosTokenID        any `json:"eos_token_id"`
}

type OnnxModel struct {
	tok      *tokenizers.Tokenizer
	sess     *ort.DynamicAdvancedSession
	inNames  []string
	outNames []string
	layers   int
	kvHeads  int
	headDim  int
	stop     map[int]bool
}

func NewOnnxModel(dir string) (*OnnxModel, error) {
	raw, err := os.ReadFile(dir + "/config.json")
	if err != nil {
		return nil, err
	}
	var cfg modelConfig
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil, err
	}
	kv := cfg.NumKeyValueHeads
	if kv == 0 {
		kv = cfg.NumAttentionHeads
	}

	m := &OnnxModel{
		layers:  cfg.NumHiddenLayers,
		kvHeads: kv,
		headDim: cfg.HiddenSize / cfg.NumAttentionHeads,
		stop:    map[int]bool{},
	}
	// eos_token_id is an int in some configs and a list in others.
	switch v := cfg.EosTokenID.(type) {
	case float64:
		m.stop[int(v)] = true
	case []any:
		for _, e := range v {
			if f, ok := e.(float64); ok {
				m.stop[int(f)] = true
			}
		}
	}

	m.inNames = []string{"input_ids", "attention_mask", "position_ids"}
	m.outNames = []string{"logits"}
	for i := 0; i < m.layers; i++ {
		m.inNames = append(m.inNames,
			fmt.Sprintf("past_key_values.%d.key", i), fmt.Sprintf("past_key_values.%d.value", i))
		m.outNames = append(m.outNames,
			fmt.Sprintf("present.%d.key", i), fmt.Sprintf("present.%d.value", i))
	}

	if m.tok, err = tokenizers.FromFile(dir + "/tokenizer.json"); err != nil {
		return nil, err
	}
	if m.sess, err = ort.NewDynamicAdvancedSession(dir+"/model.onnx", m.inNames, m.outNames, nil); err != nil {
		return nil, err
	}
	return m, nil
}

func (m *OnnxModel) Close() {
	if m.sess != nil {
		m.sess.Destroy()
	}
	if m.tok != nil {
		m.tok.Close()
	}
}

func tensorI64(shape []int64, data []int64) (*ort.Tensor[int64], error) {
	return ort.NewTensor(ort.NewShape(shape...), data)
}

func (m *OnnxModel) Generate(prompt string, maxNewTokens int) (string, int, int, error) {
	ids32, _ := m.tok.Encode(prompt, false)
	cur := make([]int64, len(ids32))
	for i, v := range ids32 {
		cur[i] = int64(v)
	}
	promptTokens := len(cur)

	past := make([]ort.Value, 2*m.layers)
	for i := range past {
		t, err := ort.NewEmptyTensor[float32](ort.NewShape(1, int64(m.kvHeads), 0, int64(m.headDim)))
		if err != nil {
			return "", 0, 0, err
		}
		past[i] = t
	}
	defer func() {
		for _, p := range past {
			p.Destroy()
		}
	}()

	produced := []uint32{}
	pos := int64(0)
	for step := 0; step < maxNewTokens; step++ {
		n := int64(len(cur))
		mask := make([]int64, pos+n)
		for i := range mask {
			mask[i] = 1
		}
		positions := make([]int64, n)
		for i := range positions {
			positions[i] = pos + int64(i)
		}

		idsT, err := tensorI64([]int64{1, n}, cur)
		if err != nil {
			return "", 0, 0, err
		}
		maskT, err := tensorI64([]int64{1, pos + n}, mask)
		if err != nil {
			return "", 0, 0, err
		}
		posT, err := tensorI64([]int64{1, n}, positions)
		if err != nil {
			return "", 0, 0, err
		}

		inputs := append([]ort.Value{idsT, maskT, posT}, past...)
		outputs := make([]ort.Value, len(m.outNames))
		if err := m.sess.Run(inputs, outputs); err != nil {
			return "", 0, 0, err
		}

		logits := outputs[0].(*ort.Tensor[float32])
		shape := logits.GetShape()
		vocab := shape[len(shape)-1]
		data := logits.GetData()
		row := data[int64(len(data))-vocab:] // last position only
		best, bestVal := 0, row[0]
		for i, v := range row {
			if v > bestVal {
				best, bestVal = i, v
			}
		}
		logits.Destroy()
		idsT.Destroy()
		maskT.Destroy()
		posT.Destroy()

		if m.stop[best] {
			for _, o := range outputs[1:] {
				o.Destroy()
			}
			break
		}
		produced = append(produced, uint32(best))

		for _, p := range past {
			p.Destroy()
		}
		past = outputs[1:] // this turn's present becomes next turn's past
		pos += n
		cur = []int64{int64(best)}
	}
	return m.tok.Decode(produced, true), promptTokens, len(produced), nil
}

// ---------------------------------------------------------------------------
// OpenAI request -> ChatML prompt -> OpenAI response.
//
// This is the whole job of an in-process transport, and it is where the work
// actually is: toolnexus speaks the OpenAI wire shape, the model speaks ChatML
// plus Qwen's <tool_call> convention, and something has to translate. Doing it
// here keeps it out of the agent loop.
// ---------------------------------------------------------------------------

var toolCallRe = regexp.MustCompile(`(?s)<tool_call>\s*(\{.*?\})\s*</tool_call>`)

func toChatML(body map[string]any) string {
	system := "You are a helpful assistant."
	var rest []map[string]any
	msgs, _ := body["messages"].([]any)
	for _, raw := range msgs {
		m, _ := raw.(map[string]any)
		if m["role"] == "system" {
			if s, ok := m["content"].(string); ok && s != "" {
				system = s
			}
			continue
		}
		rest = append(rest, m)
	}

	if tools, ok := body["tools"].([]any); ok && len(tools) > 0 {
		var schemas []string
		for _, t := range tools {
			tm, _ := t.(map[string]any)
			if fn, ok := tm["function"]; ok {
				b, _ := json.Marshal(fn)
				schemas = append(schemas, string(b))
			}
		}
		system += "\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n" +
			"You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n" +
			strings.Join(schemas, "\n") +
			"\n</tools>\n\nFor each function call, return a json object with function name and arguments " +
			"within <tool_call></tool_call> XML tags:\n<tool_call>\n" +
			`{"name": <function-name>, "arguments": <args-json-object>}` + "\n</tool_call>"
	}

	var b strings.Builder
	fmt.Fprintf(&b, "<|im_start|>system\n%s<|im_end|>\n", system)
	for _, m := range rest {
		role, _ := m["role"].(string)
		content, _ := m["content"].(string)
		switch {
		case role == "tool":
			// A tool result goes back as a user turn carrying <tool_response>.
			fmt.Fprintf(&b, "<|im_start|>user\n<tool_response>\n%s\n</tool_response><|im_end|>\n", content)
		case role == "assistant" && m["tool_calls"] != nil:
			calls, _ := m["tool_calls"].([]any)
			fmt.Fprint(&b, "<|im_start|>assistant\n")
			for _, c := range calls {
				cm, _ := c.(map[string]any)
				fn, _ := cm["function"].(map[string]any)
				argsStr, _ := fn["arguments"].(string)
				var args any
				if json.Unmarshal([]byte(argsStr), &args) != nil {
					args = map[string]any{}
				}
				out, _ := json.Marshal(map[string]any{"name": fn["name"], "arguments": args})
				fmt.Fprintf(&b, "<tool_call>\n%s\n</tool_call>\n", out)
			}
			fmt.Fprint(&b, "<|im_end|>\n")
		default:
			fmt.Fprintf(&b, "<|im_start|>%s\n%s<|im_end|>\n", role, content)
		}
	}
	fmt.Fprint(&b, "<|im_start|>assistant\n")
	return b.String()
}

func toOpenAIMessage(text string) map[string]any {
	matches := toolCallRe.FindAllStringSubmatch(text, -1)
	var calls []any
	for i, mt := range matches {
		var parsed struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		if json.Unmarshal([]byte(mt[1]), &parsed) != nil {
			continue // a malformed call is dropped, not crashed on
		}
		args := string(parsed.Arguments)
		if args == "" {
			args = "{}"
		}
		calls = append(calls, map[string]any{
			"id": "call_" + strconv.Itoa(i), "type": "function",
			"function": map[string]any{"name": parsed.Name, "arguments": args},
		})
	}
	if len(calls) > 0 {
		return map[string]any{"role": "assistant", "tool_calls": calls}
	}
	return map[string]any{"role": "assistant", "content": strings.TrimSpace(toolCallRe.ReplaceAllString(text, ""))}
}

// localRoundTripper is the whole seam. http.RoundTripper is a real interface, so
// no socket is ever opened — the *http.Client below is a normal client that
// simply never reaches the network.
type localRoundTripper struct{ model *OnnxModel }

func (rt *localRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	var body map[string]any
	if req.Body != nil {
		raw, _ := io.ReadAll(req.Body)
		_ = json.Unmarshal(raw, &body)
	}

	prompt := toChatML(body)
	text, promptTokens, completionTokens, err := rt.model.Generate(prompt, 256)
	if err != nil {
		return nil, err
	}

	message := toOpenAIMessage(text)
	finish := "stop"
	if _, ok := message["tool_calls"]; ok {
		finish = "tool_calls"
	}
	out, _ := json.Marshal(map[string]any{
		"choices": []any{map[string]any{"index": 0, "message": message, "finish_reason": finish}},
		// Real counts, not zeros: the loop reports them and a lie here is a lie
		// in every metric downstream.
		"usage": map[string]any{
			"prompt_tokens":     promptTokens,
			"completion_tokens": completionTokens,
			"total_tokens":      promptTokens + completionTokens,
		},
	})
	return &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(bytes.NewReader(out)),
		Request:    req,
	}, nil
}

func main() {
	dir := os.Getenv("ONNX_MODEL_DIR")
	if dir == "" {
		fmt.Println("ONNX_MODEL_DIR is not set — skipping (see README.md for the export command).")
		return
	}
	ort.SetSharedLibraryPath(os.Getenv("ORT_DYLIB_PATH"))
	if err := ort.InitializeEnvironment(); err != nil {
		fmt.Println("could not initialize onnxruntime:", err)
		fmt.Println("set ORT_DYLIB_PATH to a libonnxruntime shared library — see README.md")
		return
	}
	defer ort.DestroyEnvironment()

	fmt.Printf("loading %s …\n", dir)
	model, err := NewOnnxModel(dir)
	if err != nil {
		fmt.Println("could not load the model:", err)
		os.Exit(1)
	}
	defer model.Close()
	fmt.Printf("loaded: %d layers, %d kv heads, head dim %d\n\n", model.layers, model.kvHeads, model.headDim)

	ctx := context.Background()
	tk, err := tn.CreateToolkit(ctx, tn.Options{})
	if err != nil {
		panic(err)
	}
	defer tk.Close()

	tk.Register(tn.NativeTool("get_weather", "Get the current weather for a city.",
		tn.JSONSchema{
			"type":       "object",
			"properties": map[string]any{"city": map[string]any{"type": "string"}},
			"required":   []string{"city"},
		},
		func(_ context.Context, args map[string]any) (string, error) {
			city, _ := args["city"].(string)
			out, _ := json.Marshal(map[string]any{"city": city, "tempC": 31, "sky": "clear"})
			return string(out), nil
		},
	))

	// THE ONLY LINE THAT DIFFERS from talking to a hosted model.
	client := tn.CreateClient(tn.ClientOptions{
		BaseURL:    "http://in-process.invalid",
		Style:      tn.StyleOpenAI,
		Model:      "qwen2.5-1.5b-instruct-onnx",
		APIKey:     "unused",
		HTTPClient: &http.Client{Transport: &localRoundTripper{model: model}},
	})

	result, err := client.Run(ctx, "What is the weather in Chennai?", tk)
	if err != nil {
		panic(err)
	}
	for _, c := range result.ToolCalls {
		fmt.Printf("tool call : %s(%v) -> %s\n", c.Name, c.Args, c.Output)
	}
	fmt.Printf("answer    : %s\n", result.Text)
	fmt.Printf("turns     : %d | tokens: %d | sockets opened: 0\n", result.Turns, result.Usage.TotalTokens)

	if len(result.ToolCalls) == 0 {
		fmt.Fprintln(os.Stderr, "\nNOTE: the model answered without calling the tool. Smaller models do"+
			" this; it is a property of the model, not of the transport.")
	}
}
