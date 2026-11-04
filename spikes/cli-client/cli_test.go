package main

import (
	"context"
	"encoding/json"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

func fake(t *testing.T) string {
	p, err := filepath.Abs("fake-cli.sh")
	if err != nil {
		t.Fatal(err)
	}
	return p
}

// custom runs the fake CLI through `sh <fake> <args...>`.
func custom(t *testing.T, prompt string, args ...any) CliOptions {
	return CliOptions{Style: "custom", Command: "sh", Args: append([]any{fake(t)}, args...), Prompt: prompt, Answer: "stdout"}
}

func addToolkit(t *testing.T) *tn.Toolkit {
	add := tn.NativeTool("add", "Add two numbers.", tn.JSONSchema{
		"type": "object", "properties": map[string]any{"a": map[string]any{"type": "number"}, "b": map[string]any{"type": "number"}},
		"required": []any{"a", "b"},
	}, func(_ context.Context, args map[string]any) (string, error) {
		a, _ := args["a"].(float64)
		b, _ := args["b"].(float64)
		return jsonNum(a + b), nil
	})
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{ExtraTools: []tn.Tool{add}, Builtins: false})
	if err != nil {
		t.Fatal(err)
	}
	return tk
}

func jsonNum(f float64) string { b, _ := json.Marshal(f); return string(b) }

func TestParseReply(t *testing.T) {
	cases := []struct {
		in      string
		content string
		calls   []string
	}{
		{`{"content": "hello"}`, "hello", nil},
		{"```json\n{\"content\":\"fenced\"}\n```", "fenced", nil},
		{"Sure:\n{\"content\":\"inner\"}\nbye", "inner", nil},
		{`{"choices":[{"message":{"content":"wrapped"}}]}`, "wrapped", nil},
		{`{"message":{"tool_calls":[{"function":{"name":"add","arguments":"{}"}}]}}`, "", []string{"add"}},
		{`{"tool_calls":[{"name":"add","arguments":{"a":1}}]}`, "", []string{"add"}},
		{`{"content":null}`, "", nil},
		{`{"content":42}`, "42", nil},
		{`{"answer":true}`, `{"answer":true}`, nil},
		{`{"tool_calls":[{"function":{"arguments":"{}"}}]}`, `{"tool_calls":[{"function":{"arguments":"{}"}}]}`, nil},
		{"plain words", "plain words", nil},
	}
	for _, c := range cases {
		r := ParseReply(c.in)
		var names []string
		for _, tc := range r.ToolCalls {
			names = append(names, tc.Name)
		}
		if r.Content != c.content || !reflect.DeepEqual(names, c.calls) {
			t.Errorf("ParseReply(%q) = %q %v, want %q %v", c.in, r.Content, names, c.content, c.calls)
		}
	}
}

func TestBuiltinStylesLoadAndValidate(t *testing.T) {
	all, err := BuiltinStyles()
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 7 {
		t.Fatalf("styles: %d", len(all))
	}
	for name, s := range all {
		s.Binary = "x" // styles carry no binary; the caller always supplies Command
		elems, err := normalize(s.Args)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if err := validate(s, elems, ""); err != nil {
			t.Errorf("%s without model: %v", name, err)
		}
	}
}

func TestUnknownStyleListsKnown(t *testing.T) {
	_, err := CreateCliClient(CliOptions{Style: "nope", Command: "x"}, nil)
	want := `cli: unknown style "nope" (known: claude, codex, copilot, custom, devin, gemini, goose, opencode)`
	if err == nil || err.Error() != want {
		t.Fatalf("got %v", err)
	}
}

func TestModelRules(t *testing.T) {
	if _, err := CreateCliClient(CliOptions{Style: "goose", Command: "x", InProcessOptions: tn.InProcessOptions{Model: "x"}}, nil); err == nil || err.Error() != `cli: style "goose" takes no model` {
		t.Fatalf("goose+model: %v", err)
	}
	o := custom(t, "stdin", "argv", []any{"--model", "{{model}}"}, "x")
	o.Model = "m1"
	c, err := CreateCliClient(o, nil)
	if err != nil {
		t.Fatal(err)
	}
	r, err := c.Run(context.Background(), "hi", addToolkit(t))
	if err != nil || r.Text != "--model m1 x" {
		t.Fatalf("model group: %q %v", r.Text, err)
	}
}

func TestCommandIsTheBinary(t *testing.T) {
	var argv []string
	c, err := CreateCliClient(CliOptions{Style: "claude", Command: "/nonexistent/claude-x"}, func(tr Trace) { argv = tr.Argv })
	if err != nil {
		t.Fatal(err)
	}
	_, err = c.Run(context.Background(), "hi", addToolkit(t))
	if err == nil || !strings.Contains(err.Error(), "cli: command not found: /nonexistent/claude-x") {
		t.Fatalf("err: %v", err)
	}
	if argv[0] != "/nonexistent/claude-x" || argv[1] != "-p" {
		t.Fatalf("argv: %v", argv)
	}
}

func TestToolCallRoundTripWithSystemPrompt(t *testing.T) {
	var prompts []string
	o := custom(t, "stdin", "loop")
	o.SystemPrompt = "Be brief."
	c, err := CreateCliClient(o, func(tr Trace) { prompts = append(prompts, tr.Argv[0]) })
	if err != nil {
		t.Fatal(err)
	}
	r, err := c.Run(context.Background(), "add 2 and 3", addToolkit(t))
	if err != nil {
		t.Fatal(err)
	}
	if r.Text != "finished" || len(r.ToolCalls) != 1 || r.ToolCalls[0].Name != "add" || r.ToolCalls[0].Output != "5" {
		t.Fatalf("result: %q %+v", r.Text, r.ToolCalls)
	}
	if len(prompts) != 2 {
		t.Fatalf("invocations: %d", len(prompts))
	}
}

func TestRenderedRequestCarriesSystemAndTools(t *testing.T) {
	for _, mode := range [][]any{{"stdin"}, {"file", "{{file}}"}, {"arg", "{{prompt}}"}} {
		pm := map[string]string{"stdin": "stdin", "file": "file", "arg": "arg"}[mode[0].(string)]
		o := custom(t, pm, mode...)
		o.SystemPrompt = "Be brief."
		c, err := CreateCliClient(o, nil)
		if err != nil {
			t.Fatal(err)
		}
		r, err := c.Run(context.Background(), "hi", addToolkit(t))
		if err != nil {
			t.Fatal(err)
		}
		head := Preamble + "\nREQUEST:\n"
		if !strings.HasPrefix(r.Text, head) {
			t.Fatalf("%v: no preamble: %.80q", mode, r.Text)
		}
		var req struct {
			Messages []map[string]any `json:"messages"`
			Tools    []any            `json:"tools"`
		}
		if err := json.Unmarshal([]byte(r.Text[len(head):]), &req); err != nil {
			t.Fatalf("%v: %v", mode, err)
		}
		if req.Messages[0]["role"] != "system" || !strings.HasPrefix(req.Messages[0]["content"].(string), "Be brief.") {
			t.Fatalf("%v: system: %+v", mode, req.Messages[0])
		}
		if last := req.Messages[len(req.Messages)-1]; last["role"] != "user" || last["content"] != "hi" || len(req.Tools) != 1 {
			t.Fatalf("%v: last/tools: %+v %d", mode, last, len(req.Tools))
		}
	}
}

func TestErrors(t *testing.T) {
	cases := []struct {
		opts CliOptions
		want string
	}{
		{custom(t, "stdin", "fail"), "cli: exit 3: bad thing"},
		{custom(t, "stdin", "empty"), "cli: empty reply"},
	}
	to := custom(t, "stdin", "sleep")
	to.TimeoutMs = 300
	cases = append(cases, struct {
		opts CliOptions
		want string
	}{to, "cli: timed out after 300ms"})
	for _, c := range cases {
		cl, err := CreateCliClient(c.opts, nil)
		if err != nil {
			t.Fatal(err)
		}
		_, err = cl.Run(context.Background(), "hi", addToolkit(t))
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("want %q, got %v", c.want, err)
		}
	}
	out := custom(t, "stdin", "out", "{{out}}", "from file")
	out.Answer = "out"
	cl, _ := CreateCliClient(out, nil)
	r, err := cl.Run(context.Background(), "hi", addToolkit(t))
	if err != nil || r.Text != "from file" {
		t.Fatalf("out file: %q %v", r.Text, err)
	}
}

func TestCommandRequired(t *testing.T) {
	_, err := CreateCliClient(CliOptions{Style: "claude"}, nil)
	if err == nil || err.Error() != "cli: Command is required (path or name of the CLI binary)" {
		t.Fatalf("err: %v", err)
	}
}
