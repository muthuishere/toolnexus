package toolnexus

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

func TestParseFunctionGemmaCalls(t *testing.T) {
	cases := []struct {
		raw  string
		want []FunctionGemmaCall
	}{
		{"<start_function_call>call:add_to_order{product:<escape>raw honey<escape>,quantity:2}<end_function_call>",
			[]FunctionGemmaCall{{"add_to_order", map[string]any{"product": "raw honey", "quantity": int64(2)}}}},
		{"call:faq{question:<escape>Can I return, an item?<escape>}",
			[]FunctionGemmaCall{{"faq", map[string]any{"question": "Can I return, an item?"}}}},
		{"call:set{on:true,ratio:0.5,x:null,word:abc}",
			[]FunctionGemmaCall{{"set", map[string]any{"on": true, "ratio": 0.5, "x": nil, "word": "abc"}}}},
		{"call:a{x:1}<end_function_call><start_function_call>call:b{y:<escape>z<escape>}",
			[]FunctionGemmaCall{{"a", map[string]any{"x": int64(1)}}, {"b", map[string]any{"y": "z"}}}},
		{"<start_function_call>call:hello<escape>}", []FunctionGemmaCall{{"hello", map[string]any{}}}},
		{"call:q{text:<escape>unterminated", []FunctionGemmaCall{{"q", map[string]any{"text": "unterminated"}}}},
		{"I apologize, I cannot help with that.", nil},
		{"", nil},
	}
	for _, c := range cases {
		got := ParseFunctionGemmaCalls(c.raw)
		if !reflect.DeepEqual(got, c.want) {
			t.Errorf("%q:\n got %#v\nwant %#v", c.raw, got, c.want)
		}
	}
}

func TestFunctionGemmaMessages(t *testing.T) {
	got := FunctionGemmaMessages([]any{
		map[string]any{"role": "system", "content": "Shop context: honey."},
		map[string]any{"role": "user", "content": "hi"},
	})
	dev := got[0].(map[string]any)
	if dev["role"] != "developer" || dev["content"] != FunctionGemmaDeveloperPrompt+"\nShop context: honey." || len(got) != 2 {
		t.Fatalf("got %#v", got)
	}
	got = FunctionGemmaMessages([]any{map[string]any{"role": "user", "content": "hi"}})
	if got[0].(map[string]any)["content"] != FunctionGemmaDeveloperPrompt {
		t.Fatalf("plain: %#v", got)
	}
}

// fakeLlama mimics llama-server: it does not parse FunctionGemma calls, it returns them as content.
func fakeLlama(t *testing.T, reply string, seen *map[string]any) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("path %s", r.URL.Path)
		}
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		*seen = body
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{
			"message": map[string]any{"role": "assistant", "content": reply}, "finish_reason": "stop"}}})
	}))
}

func TestLocalFunctionGemmaRunsTool(t *testing.T) {
	var seen map[string]any
	// llama-server returns only the continuation after the prefill.
	srv := fakeLlama(t, "add_to_order{product:<escape>raw honey<escape>,quantity:2}", &seen)
	defer srv.Close()
	ctx := context.Background()
	tk, err := CreateToolkit(ctx, Options{Builtins: false})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	type in struct {
		Product  string `json:"product"`
		Quantity int    `json:"quantity"`
	}
	var got in
	tk.Register(NativeToolReflect("add_to_order", "Add a product to the order", func(_ context.Context, a in) (string, error) {
		got = a
		return "added", nil
	}))
	c, err := CreateLocalClient(LocalOptions{BaseURL: srv.URL + "/v1", Format: "functiongemma", Prefill: true, MaxTurns: 1, SystemPrompt: "Shop context: honey."})
	if err != nil {
		t.Fatal(err)
	}
	res, err := c.Run(ctx, "add 2 raw honey", tk)
	if err != nil {
		t.Fatal(err)
	}
	if got.Product != "raw honey" || got.Quantity != 2 || res.ToolCallCount != 1 || res.ToolCalls[0].Output != "added" {
		t.Fatalf("tool not run: %+v %+v", got, res.ToolCalls)
	}
	msgs := seen["messages"].([]any)
	first := msgs[0].(map[string]any)
	last := msgs[len(msgs)-1].(map[string]any)
	if first["role"] != "developer" || !strings.HasPrefix(first["content"].(string), FunctionGemmaDeveloperPrompt+"\n") {
		t.Fatalf("developer line: %#v", first)
	}
	if last["role"] != "assistant" || last["content"] != "<start_function_call>call:" {
		t.Fatalf("prefill: %#v", last)
	}
	if stop := seen["stop"].([]any); stop[0] != "<end_function_call>" || seen["temperature"].(float64) != 0 || seen["tools"] == nil {
		t.Fatalf("body: %#v", seen)
	}
}

func TestLocalFunctionGemmaPlainText(t *testing.T) {
	var seen map[string]any
	srv := fakeLlama(t, "I cannot help with the weather.", &seen)
	defer srv.Close()
	ctx := context.Background()
	tk, _ := CreateToolkit(ctx, Options{Builtins: false})
	defer tk.Close()
	c, _ := CreateLocalClient(LocalOptions{BaseURL: srv.URL + "/v1", Format: "functiongemma"})
	res, err := c.Run(ctx, "weather?", tk)
	if err != nil {
		t.Fatal(err)
	}
	if res.ToolCallCount != 0 || res.Text != "I cannot help with the weather." {
		t.Fatalf("%+v", res)
	}
	if _, ok := seen["messages"].([]any)[len(seen["messages"].([]any))-1].(map[string]any)["content"].(string); !ok {
		t.Fatal("bad messages")
	}
}

func TestLocalOpenAINeedsNoKey(t *testing.T) {
	var seen map[string]any
	srv := fakeLlama(t, "hello", &seen)
	defer srv.Close()
	ctx := context.Background()
	tk, _ := CreateToolkit(ctx, Options{Builtins: false})
	defer tk.Close()
	c, err := CreateLocalClient(LocalOptions{BaseURL: srv.URL + "/v1"})
	if err != nil {
		t.Fatal(err)
	}
	res, err := c.Run(ctx, "hi", tk)
	if err != nil || res.Text != "hello" {
		t.Fatalf("%v %+v", err, res)
	}
	if _, err := CreateLocalClient(LocalOptions{BaseURL: srv.URL, Format: "nope"}); err == nil {
		t.Fatal("unknown format accepted")
	}
}
