// Unit/integration tests (no network, no LLM). Mirrors js/test/unit.test.ts.
// Run: go test ./...
package toolnexus

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
)

func TestSanitize(t *testing.T) {
	if got := Sanitize("a b/c.d:e"); got != "a_b_c_d_e" {
		t.Fatalf("Sanitize(%q) = %q, want %q", "a b/c.d:e", got, "a_b_c_d_e")
	}
	if got := Sanitize("keep-_OK1"); got != "keep-_OK1" {
		t.Fatalf("Sanitize(%q) = %q, want %q", "keep-_OK1", got, "keep-_OK1")
	}
}

func TestParseMcpConfig(t *testing.T) {
	// A single server map; ServerConfig.Command is the distinguishing field.
	server := map[string]any{"foo": map[string]any{"command": []any{"x"}}}

	check := func(name string, input any) {
		t.Helper()
		cfg, err := ParseMcpConfig(input)
		if err != nil {
			t.Fatalf("%s: unexpected error: %v", name, err)
		}
		foo, ok := cfg["foo"]
		if !ok {
			t.Fatalf("%s: expected server %q in parsed config, got %v", name, "foo", cfg)
		}
		if len(foo.Command) != 1 || foo.Command[0] != "x" {
			t.Fatalf("%s: expected command [x], got %v", name, foo.Command)
		}
	}

	check("mcpServers", map[string]any{"mcpServers": server})
	check("servers", map[string]any{"servers": server})
	check("mcp", map[string]any{"mcp": server})
	check("raw", server)
}

func TestExpandEnvHeaders(t *testing.T) {
	t.Setenv("TN_TEST_TOKEN", "secret123")

	out := ExpandEnvHeaders(map[string]string{
		"Authorization": "Bearer ${TN_TEST_TOKEN}",
		"X":             "plain",
	})
	if out["Authorization"] != "Bearer secret123" {
		t.Fatalf("Authorization = %q, want %q", out["Authorization"], "Bearer secret123")
	}
	if out["X"] != "plain" {
		t.Fatalf("X = %q, want %q", out["X"], "plain")
	}

	if got := ExpandEnvHeaders(nil); got != nil {
		t.Fatalf("ExpandEnvHeaders(nil) = %v, want nil", got)
	}

	// Returned map must be a new map, not the input.
	in := map[string]string{"K": "v"}
	got := ExpandEnvHeaders(in)
	got["K"] = "mutated"
	if in["K"] != "v" {
		t.Fatalf("ExpandEnvHeaders mutated the input map")
	}
}

func TestSkills(t *testing.T) {
	src := LoadSkills("../examples/skills")

	if _, ok := src.Skills["hello-world"]; !ok {
		t.Fatalf("hello-world not discovered; skills: %v", src.Skills)
	}

	prompt := src.Prompt()
	if !strings.Contains(prompt, "## Available Skills") {
		t.Fatalf("prompt missing header; got:\n%s", prompt)
	}
	if !strings.Contains(prompt, "hello-world") {
		t.Fatalf("prompt missing hello-world; got:\n%s", prompt)
	}

	res, err := src.Tool.Execute(map[string]any{"name": "hello-world"}, nil)
	if err != nil {
		t.Fatalf("skill execute error: %v", err)
	}
	if res.IsError {
		t.Fatalf("expected non-error, got IsError with output: %s", res.Output)
	}
	if !strings.HasPrefix(res.Output, `<skill_content name="hello-world">`) {
		t.Fatalf("output does not start with skill_content tag; got:\n%s", res.Output)
	}
	for _, want := range []string{
		"# Skill: hello-world",
		"Base directory for this skill: file://",
		"<skill_files>",
	} {
		if !strings.Contains(res.Output, want) {
			t.Fatalf("output missing %q; got:\n%s", want, res.Output)
		}
	}
	if !strings.HasSuffix(res.Output, "</skill_content>") {
		t.Fatalf("output does not end with </skill_content>; got:\n%s", res.Output)
	}

	miss, err := src.Tool.Execute(map[string]any{"name": "nope"}, nil)
	if err != nil {
		t.Fatalf("skill miss execute error: %v", err)
	}
	if !miss.IsError {
		t.Fatalf("expected IsError for unknown skill")
	}
	if !strings.Contains(miss.Output, "not found") {
		t.Fatalf("expected 'not found' in output; got: %s", miss.Output)
	}
}

func TestNativeTool(t *testing.T) {
	// string -> ok
	ok := NativeTool("s", "", nil, func(ctx context.Context, args map[string]any) (string, error) {
		return "hi", nil
	})
	if ok.Source != SourceNative {
		t.Fatalf("source = %q, want native", ok.Source)
	}
	res, err := ok.Execute(map[string]any{}, nil)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}
	if res.IsError || res.Output != "hi" {
		t.Fatalf("got %+v, want {Output:hi IsError:false}", res)
	}

	// returned error -> IsError
	e := NativeTool("e", "", nil, func(ctx context.Context, args map[string]any) (string, error) {
		return "", errBoom{}
	})
	er, err := e.Execute(map[string]any{}, nil)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}
	if !er.IsError {
		t.Fatalf("expected IsError")
	}
	if !strings.Contains(er.Output, "boom") {
		t.Fatalf("expected 'boom' in output; got %q", er.Output)
	}
}

type errBoom struct{}

func (errBoom) Error() string { return "boom" }

func TestNativeToolReflect(t *testing.T) {
	type addArgs struct {
		A    float64 `json:"a"`
		B    float64 `json:"b"`
		Note string  `json:"note,omitempty"`
		Skip string  `json:"-"`
	}

	tool := NativeToolReflect("add", "adds", func(ctx context.Context, in addArgs) (string, error) {
		return jsonNum(in.A + in.B), nil
	})

	if tool.Source != SourceNative {
		t.Fatalf("source = %q, want native", tool.Source)
	}

	// Schema: required excludes ,omitempty and json:"-"; properties present.
	props, _ := tool.InputSchema["properties"].(map[string]any)
	if _, ok := props["a"]; !ok {
		t.Fatalf("schema missing property a; props=%v", props)
	}
	if _, ok := props["b"]; !ok {
		t.Fatalf("schema missing property b; props=%v", props)
	}
	if _, ok := props["note"]; !ok {
		t.Fatalf("schema missing property note; props=%v", props)
	}
	if _, ok := props["Skip"]; ok {
		t.Fatalf("schema should not include json:\"-\" field")
	}

	required := toStringSet(tool.InputSchema["required"])
	if !required["a"] || !required["b"] {
		t.Fatalf("required should contain a and b; got %v", required)
	}
	if required["note"] {
		t.Fatalf("required should NOT contain omitempty field note; got %v", required)
	}

	// Field type mapping: float -> number.
	if at, _ := props["a"].(map[string]any); at["type"] != "number" {
		t.Fatalf("property a type = %v, want number", at["type"])
	}

	// Round-trip: args decode into T and produce output.
	res, err := tool.Execute(map[string]any{"a": 2.0, "b": 3.0}, nil)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}
	if res.IsError || res.Output != "5" {
		t.Fatalf("got %+v, want output 5", res)
	}
}

func jsonNum(f float64) string {
	return strconv.FormatFloat(f, 'f', -1, 64)
}

func toStringSet(v any) map[string]bool {
	out := map[string]bool{}
	switch s := v.(type) {
	case []string:
		for _, x := range s {
			out[x] = true
		}
	case []any:
		for _, x := range s {
			if str, ok := x.(string); ok {
				out[str] = true
			}
		}
	}
	return out
}

func TestHTTPTool(t *testing.T) {
	t.Setenv("TN_HTTP_TOKEN", "tok")

	var seenURL, seenAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenURL = r.URL.String()
		seenAuth = r.Header.Get("Authorization")
		if strings.HasPrefix(r.URL.Path, "/posts/5") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"id":5,"title":"hello"}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("nope"))
	}))
	defer server.Close()

	ok := HTTPTool(HTTPToolOptions{
		Name:    "get_post",
		Method:  "GET",
		URL:     server.URL + "/posts/{id}",
		Headers: map[string]string{"Authorization": "Bearer ${TN_HTTP_TOKEN}"},
		InputSchema: JSONSchema{
			"type":       "object",
			"properties": map[string]any{"id": map[string]any{"type": "number"}, "q": map[string]any{"type": "string"}},
			"required":   []string{"id"},
		},
	})
	if ok.Source != SourceHTTP {
		t.Fatalf("source = %q, want http", ok.Source)
	}

	r1, err := ok.Execute(map[string]any{"id": 5, "q": "x"}, nil)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}
	if r1.IsError {
		t.Fatalf("expected non-error; got output: %s", r1.Output)
	}
	if !strings.Contains(r1.Output, "hello") {
		t.Fatalf("body not returned; got: %s", r1.Output)
	}
	if seenAuth != "Bearer tok" {
		t.Fatalf("env header not expanded at server; got %q", seenAuth)
	}
	if !strings.Contains(seenURL, "/posts/5") || !strings.Contains(seenURL, "q=x") {
		t.Fatalf("placeholder/querystring wrong; server saw URL %q", seenURL)
	}

	bad := HTTPTool(HTTPToolOptions{
		Name:   "b",
		Method: "GET",
		URL:    server.URL + "/posts/{id}",
		InputSchema: JSONSchema{
			"type":       "object",
			"properties": map[string]any{"id": map[string]any{"type": "number"}},
			"required":   []string{"id"},
		},
	})
	r2, err := bad.Execute(map[string]any{"id": 99}, nil)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}
	if !r2.IsError {
		t.Fatalf("expected IsError for 404")
	}
	if !strings.HasPrefix(r2.Output, "HTTP 404:") {
		t.Fatalf("expected output to start with 'HTTP 404:'; got: %s", r2.Output)
	}
}

func TestAdapters(t *testing.T) {
	tool := NativeTool("t", "d", JSONSchema{"type": "object", "properties": map[string]any{}}, nil)

	// OpenAI
	openai := ToOpenAI([]Tool{tool})
	ob, _ := json.Marshal(openai[0])
	var om map[string]any
	_ = json.Unmarshal(ob, &om)
	if om["type"] != "function" {
		t.Fatalf("openai type = %v, want function", om["type"])
	}
	fn, _ := om["function"].(map[string]any)
	if fn["name"] != "t" || fn["description"] != "d" {
		t.Fatalf("openai function shape wrong: %v", fn)
	}
	if _, ok := fn["parameters"]; !ok {
		t.Fatalf("openai function missing parameters")
	}

	// Anthropic
	anth := ToAnthropic([]Tool{tool})
	ab, _ := json.Marshal(anth[0])
	var am map[string]any
	_ = json.Unmarshal(ab, &am)
	if am["name"] != "t" || am["description"] != "d" {
		t.Fatalf("anthropic shape wrong: %v", am)
	}
	if _, ok := am["input_schema"]; !ok {
		t.Fatalf("anthropic missing input_schema")
	}

	// Gemini
	gem := ToGemini([]Tool{tool})
	gb, _ := json.Marshal(gem[0])
	var gm map[string]any
	_ = json.Unmarshal(gb, &gm)
	decls, ok := gm["functionDeclarations"].([]any)
	if !ok || len(decls) != 1 {
		t.Fatalf("gemini functionDeclarations wrong: %v", gm)
	}
	d0, _ := decls[0].(map[string]any)
	if d0["name"] != "t" {
		t.Fatalf("gemini decl name = %v, want t", d0["name"])
	}
}

func TestToolkit(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{SkillsDir: []string{"../examples/skills"}})
	if err != nil {
		t.Fatalf("CreateToolkit error: %v", err)
	}
	defer tk.Close()

	add := NativeTool("add", "", nil, func(ctx context.Context, args map[string]any) (string, error) {
		a, _ := args["a"].(float64)
		b, _ := args["b"].(float64)
		return jsonNum(a + b), nil
	})
	tk.Register(add)
	// duplicate-name keeps first
	tk.Register(NativeTool("add", "dup", nil, func(ctx context.Context, args map[string]any) (string, error) {
		return "SHOULD_NOT_WIN", nil
	}))

	if _, ok := tk.Get("add"); !ok {
		t.Fatalf("expected add registered")
	}
	if _, ok := tk.Get("skill"); !ok {
		t.Fatalf("expected skill tool present")
	}

	r, err := tk.Execute(context.Background(), "add", map[string]any{"a": 2.0, "b": 3.0})
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}
	if r.Output != "5" {
		t.Fatalf("add output = %q, want 5 (duplicate must not win)", r.Output)
	}

	miss, err := tk.Execute(context.Background(), "ghost", map[string]any{})
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}
	if !miss.IsError {
		t.Fatalf("expected IsError for unknown tool")
	}

	// Sanity: the duplicate description never replaced the original.
	got, _ := tk.Get("add")
	if got.Description == "dup" {
		t.Fatalf("duplicate tool overrode the first")
	}
}

// TestClientConcurrentToolCalls drives the OpenAI-style client loop against a
// mock LLM that returns THREE tool calls in one assistant turn, then a final
// answer. It verifies (a) every call is executed, (b) the appended tool-result
// messages preserve the original call order and map to the right tool_call_id,
// and (c) ToolCalls is recorded without loss. Run with -race to confirm the
// concurrent execution is race-free.
func TestClientConcurrentToolCalls(t *testing.T) {
	// A tool whose output echoes its arg, so we can check id<->result mapping.
	echo := NativeTool("echo", "echo n",
		JSONSchema{"type": "object", "properties": map[string]any{"n": map[string]any{"type": "number"}}},
		func(_ context.Context, args map[string]any) (string, error) {
			return strconv.FormatFloat(args["n"].(float64), 'f', -1, 64), nil
		})

	tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{echo}})
	if err != nil {
		t.Fatal(err)
	}

	turn := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if turn == 0 {
			turn++
			_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[
				{"id":"c0","type":"function","function":{"name":"echo","arguments":"{\"n\":10}"}},
				{"id":"c1","type":"function","function":{"name":"echo","arguments":"{\"n\":20}"}},
				{"id":"c2","type":"function","function":{"name":"echo","arguments":"{\"n\":30}"}}
			]}}]}`))
			return
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"done"}}]}`))
	}))
	defer srv.Close()

	c := CreateClient(ClientOptions{BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	res, err := c.Run(context.Background(), "go", tk)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if res.Text != "done" {
		t.Fatalf("text = %q, want done", res.Text)
	}
	if len(res.ToolCalls) != 3 {
		t.Fatalf("ToolCalls len = %d, want 3", len(res.ToolCalls))
	}

	// Collect tool-result messages in appended order; verify deterministic order
	// and that each tool_call_id maps to the matching echoed value.
	want := map[string]string{"c0": "10", "c1": "20", "c2": "30"}
	order := []string{}
	for _, raw := range res.Messages {
		m, ok := raw.(map[string]any)
		if !ok || m["role"] != "tool" {
			continue
		}
		id := m["tool_call_id"].(string)
		order = append(order, id)
		if got := m["content"].(string); got != want[id] {
			t.Fatalf("tool %s content = %q, want %q", id, got, want[id])
		}
	}
	if strings.Join(order, ",") != "c0,c1,c2" {
		t.Fatalf("tool-result order = %v, want [c0 c1 c2]", order)
	}
}
