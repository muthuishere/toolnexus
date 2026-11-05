// Hermetic tests for the CLI client (SPEC §8C): no network, no real agent CLI.
// The test binary plays three parts, chosen in TestMain (rag_consumer_test.go)
// via cliTestHelperMain: a fake ACP agent (TN_FAKE_ACP=1), a fake one-shot CLI
// (TN_FAKE_CLI=<mode>), and the stdio MCP bridge (argv `mcp-bridge <url>`).
package toolnexus

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func cliTestHelperMain() bool {
	if len(os.Args) == 3 && os.Args[1] == "mcp-bridge" {
		if err := RunMCPBridge(os.Args[2], os.Getenv(CliMCPTokenEnv), os.Stdin, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return true
	}
	if os.Getenv("TN_FAKE_ACP") == "1" && len(os.Args) >= 2 && os.Args[1] == "acp" {
		fakeACPAgent()
		return true
	}
	if m := os.Getenv("TN_FAKE_CLI"); m != "" {
		fakeOneShotCLI(m)
		return true
	}
	return false
}

// ---------------------------------------------------------------------------
// Fake one-shot CLI. Prompt from stdin, or the arg after "--p", or the file
// after "--f". Writes the answer to stdout, or to the file after "--o".
// ---------------------------------------------------------------------------

func fakeOneShotCLI(mode string) {
	args := os.Args[1:]
	prompt, outFile := "", ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--p":
			i++
			prompt = args[i]
		case "--f":
			i++
			b, _ := os.ReadFile(args[i])
			prompt = string(b)
		case "--o":
			i++
			outFile = args[i]
		case "--argv":
			fmt.Print(`{"content":` + cliJSON(strings.Join(args, " ")) + `}`)
			return
		default:
			// Style-agnostic detection, so every built-in style's real argv
			// works: the prompt as an arg, the {{file}} temp file, the {{out}}
			// temp path (not created yet).
			a := args[i]
			switch {
			case strings.HasPrefix(a, CliPreamble[:20]):
				prompt = a
			case strings.Contains(filepath.Base(a), "toolnexus-cli-"):
				if b, err := os.ReadFile(a); err == nil {
					prompt = string(b)
				} else {
					outFile = a
				}
			}
		}
	}
	if prompt == "" {
		b, _ := io.ReadAll(os.Stdin)
		prompt = string(b)
	}
	if rec := os.Getenv("TN_FAKE_REC"); rec != "" {
		f, _ := os.OpenFile(rec, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
		_, _ = f.WriteString(prompt + "\n=====\n")
		_ = f.Close()
	}
	var reply string
	switch mode {
	case "exit":
		fmt.Fprint(os.Stderr, "boom: bad login")
		os.Exit(3)
	case "empty":
		return
	case "sleep":
		time.Sleep(10 * time.Second)
	case "nooutfile":
		fmt.Print("progress only")
		return
	case "tool":
		if strings.Contains(prompt, "BADGE-77Q") {
			reply = "Your badge is BADGE-77Q.\n"
			reply = `{"content": ` + cliJSON(strings.TrimSpace(reply)) + `}`
		} else {
			// fenced and with string arguments: exercises the tolerant parser
			reply = "```json\n" + `{"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_badge_number","arguments":"{}"}}]}` + "\n```\n\n"
		}
	default:
		reply = `{"content":"hello"}`
	}
	if outFile != "" {
		fmt.Print("progress on stdout\n")
		_ = os.WriteFile(outFile, []byte(reply), 0o600)
		return
	}
	fmt.Print(reply)
}

func cliJSON(v any) string { b, _ := json.Marshal(v); return string(b) }

// ---------------------------------------------------------------------------
// Fake ACP agent. FAKE_HTTP=1 advertises mcpCapabilities.http. FAKE_FAIL_INIT=1
// exits before answering initialize. FAKE_NO_CONFIG=1 rejects
// session/set_config_option. FAKE_PROJECT=1 reads the MCP server from
// <cwd>/.mcp.json instead of session/new. TN_FAKE_REC records every method.
// ---------------------------------------------------------------------------

func fakeACPAgent() {
	if os.Getenv("FAKE_FAIL_INIT") == "1" {
		fmt.Fprint(os.Stderr, "unknown flag: acp")
		os.Exit(2)
	}
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 0, 1<<16), 1<<24)
	out := json.NewEncoder(os.Stdout)
	rec := func(line string) {
		if p := os.Getenv("TN_FAKE_REC"); p != "" {
			f, _ := os.OpenFile(p, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
			_, _ = f.WriteString(line + "\n")
			_ = f.Close()
		}
	}
	var entry map[string]any
	var cwd string
	id := 1000
	ask := func(callID, title string) string {
		id++
		_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": id, "method": "session/request_permission", "params": map[string]any{
			"sessionId": "s1", "toolCall": map[string]any{"toolCallId": callID, "title": title},
			"options": []any{map[string]any{"optionId": "yes", "kind": "allow_once"}, map[string]any{"optionId": "no", "kind": "reject_once"}}}})
		for in.Scan() {
			var r struct {
				ID     int `json:"id"`
				Result struct {
					Outcome struct {
						OptionID string `json:"optionId"`
					} `json:"outcome"`
				} `json:"result"`
			}
			if json.Unmarshal(in.Bytes(), &r) == nil && r.ID == id {
				return r.Result.Outcome.OptionID
			}
		}
		return ""
	}
	update := func(u map[string]any) {
		_ = out.Encode(map[string]any{"jsonrpc": "2.0", "method": "session/update", "params": map[string]any{"sessionId": "s1", "update": u}})
	}
	for in.Scan() {
		var m struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		_ = json.Unmarshal(in.Bytes(), &m)
		rec(m.Method + " " + string(m.Params))
		switch m.Method {
		case "initialize":
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"protocolVersion": 1,
				"agentCapabilities": map[string]any{"mcpCapabilities": map[string]any{"http": os.Getenv("FAKE_HTTP") == "1"}}}})
		case "session/new":
			var p struct {
				Cwd        string           `json:"cwd"`
				MCPServers []map[string]any `json:"mcpServers"`
			}
			_ = json.Unmarshal(m.Params, &p)
			cwd = p.Cwd
			if len(p.MCPServers) == 1 {
				entry = p.MCPServers[0]
			}
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"sessionId": "s1"}})
		case "session/set_config_option":
			if os.Getenv("FAKE_NO_CONFIG") == "1" {
				_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "error": map[string]any{"code": -32601, "message": "nope"}})
			} else {
				_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{}})
			}
		case "session/set_model":
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{}})
		case "session/prompt":
			if os.Getenv("TN_FAKE_DRIP") == "1" {
				// conformance/cli-client/cases.json stream.acpDrip
				update(map[string]any{"sessionUpdate": "agent_thought_chunk", "content": map[string]any{"type": "text", "text": "pondering"}})
				for i, chunk := range []string{"drip-1 ", "drip-2 ", "drip-3"} {
					if i > 0 {
						time.Sleep(200 * time.Millisecond)
					}
					update(map[string]any{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": chunk}})
				}
				_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"stopReason": "end_turn"}})
				continue
			}
			if os.Getenv("FAKE_PROJECT") == "1" {
				b, _ := os.ReadFile(filepath.Join(cwd, ".mcp.json"))
				var pf struct {
					MCPServers map[string]map[string]any `json:"mcpServers"`
				}
				_ = json.Unmarshal(b, &pf)
				e := pf.MCPServers["toolnexus"]
				hdr := []any{}
				if h, ok := e["headers"].(map[string]any); ok {
					for k, v := range h {
						hdr = append(hdr, map[string]any{"name": k, "value": v})
					}
				}
				entry = map[string]any{"type": "http", "url": e["url"], "headers": hdr}
			}
			// devin-like: the name is only on the tool_call update; the
			// permission request has an empty title.
			update(map[string]any{"sessionUpdate": "tool_call", "toolCallId": "tc1", "title": "Calling get_badge_number from toolnexus"})
			ours := ask("tc1", "")
			update(map[string]any{"sessionUpdate": "tool_call", "toolCallId": "tc2", "title": "Bash"})
			own := ask("tc2", "Bash")
			// a lookalike title on a new id: substring of ours, must be rejected
			fake := ask("tc3", "my_get_badge_number_wrapper")
			// the same id again with a different title: the decision is per id
			again := ask("tc1", "Bash")
			text := fmt.Sprintf("perm ours=%s own=%s fake=%s again=%s; ", ours, own, fake, again) + callServed(entry)
			update(map[string]any{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": text}})
			_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "result": map[string]any{"stopReason": "end_turn"}})
		}
	}
}

// callServed calls get_badge_number and load_skill on the handed MCP server
// (http with the given headers, or by spawning the stdio entry), and also
// probes the http endpoint without a token.
func callServed(entry map[string]any) string {
	reqs := []string{
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"fake","version":"0"}}}`,
		`{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}`,
		`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_badge_number","arguments":{}}}`,
		`{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"load_skill","arguments":{"name":"hello-world"}}}`,
	}
	var replies []string
	noauth := 0
	if entry["type"] == "http" {
		url := entry["url"].(string)
		hdr := map[string]string{}
		for _, h := range entry["headers"].([]any) {
			x := h.(map[string]any)
			hdr[x["name"].(string)] = fmt.Sprint(x["value"])
		}
		for _, r := range reqs {
			req, _ := http.NewRequest("POST", url, strings.NewReader(r))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Accept", "application/json, text/event-stream")
			for k, v := range hdr {
				req.Header.Set(k, v)
			}
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				return "http error"
			}
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			replies = append(replies, string(b))
		}
		req, _ := http.NewRequest("POST", url, strings.NewReader(reqs[0]))
		req.Header.Set("Content-Type", "application/json")
		if resp, err := http.DefaultClient.Do(req); err == nil {
			noauth = resp.StatusCode
			resp.Body.Close()
		}
	} else if entry != nil {
		var args []string
		for _, a := range entry["args"].([]any) {
			args = append(args, a.(string))
		}
		cmd := exec.Command(entry["command"].(string), args...)
		cmd.Env = os.Environ()
		for _, e := range entry["env"].([]any) {
			x := e.(map[string]any)
			cmd.Env = append(cmd.Env, fmt.Sprint(x["name"])+"="+fmt.Sprint(x["value"]))
		}
		cmd.Stdin = strings.NewReader(strings.Join(reqs, "\n") + "\n")
		b, err := cmd.Output()
		if err != nil {
			return "bridge error"
		}
		replies = strings.Split(strings.TrimSpace(string(b)), "\n")
	}
	return fmt.Sprintf("entry=%v noauth=%d replies=%s", entry["type"], noauth, strings.Join(replies, " | "))
}

// ---------------------------------------------------------------------------
// Helpers.
// ---------------------------------------------------------------------------

func cliBadgeToolkit(t *testing.T) *Toolkit {
	t.Helper()
	badge := NativeTool("get_badge_number", "Returns the badge number.", JSONSchema{"type": "object", "properties": map[string]any{}},
		func(context.Context, map[string]any) (string, error) { return "BADGE-77Q", nil })
	tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{badge}, Builtins: false, SkillsDir: []string{"../examples/skills"}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tk.Close)
	return tk
}

func fakeCLIOpts(mode string, extra ...string) CliOptions {
	return CliOptions{Style: CliStyles.Custom, Command: os.Args[0], Args: anyArgs(extra), Prompt: "stdin", Answer: "stdout",
		Env: map[string]string{"TN_FAKE_CLI": mode}, Transport: CliTransports.CLI}
}

func anyArgs(s []string) []any {
	out := make([]any, 0, len(s))
	for _, x := range s {
		out = append(out, x)
	}
	return out
}

func runCli(t *testing.T, o CliOptions, tk *Toolkit) (RunResult, error) {
	t.Helper()
	c, err := CreateCliClient(o)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c.Run(context.Background(), "What is my badge number?", tk)
}

// ---------------------------------------------------------------------------
// Shared data: styles and preamble are byte-identical to conformance/.
// ---------------------------------------------------------------------------

func TestCliStylesMatchConformance(t *testing.T) {
	ents, err := os.ReadDir("../conformance/cli-styles")
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, e := range ents {
		if e.IsDir() || e.Name() == "README.md" {
			continue
		}
		want, _ := os.ReadFile("../conformance/cli-styles/" + e.Name())
		got, err := cliStylesFS.ReadFile("clistyles/" + e.Name())
		if e.Name() == "preamble.txt" {
			got, err = []byte(CliPreamble), nil
		}
		if err != nil || !bytes.Equal(got, want) {
			t.Errorf("golang/clistyles/%s differs from conformance/cli-styles (copy it)", e.Name())
		}
		n++
	}
	if n != 8 {
		t.Fatalf("expected 7 styles + preamble, found %d files", n)
	}
	for _, s := range []CliStyle{CliStyles.Claude, CliStyles.Codex, CliStyles.Copilot, CliStyles.Opencode, CliStyles.Devin, CliStyles.Gemini, CliStyles.Goose} {
		d, ok := CliStyleDefFor(s)
		if !ok || d.Name != s.String() {
			t.Errorf("style %v: not embedded", s)
			continue
		}
		elems, err := normalizeCliArgs(d.Args)
		if err == nil {
			err = validateCliStyle(d, elems, "")
		}
		if err != nil {
			t.Errorf("style %s invalid: %v", d.Name, err)
		}
		if d.Verified == "" {
			t.Errorf("style %s has no verified note", d.Name)
		}
	}
	if d, _ := CliStyleDefFor(CliStyles.Codex); d.ACP != nil {
		t.Error("codex must have no ACP launch")
	}
	if d, _ := CliStyleDefFor(CliStyles.Devin); d.ACP == nil || d.ACP.ProjectMCPFile != ".mcp.json" {
		t.Error("devin needs projectMcpFile .mcp.json")
	}
	if d, _ := CliStyleDefFor(CliStyles.Goose); !strings.HasPrefix(d.Verified, "UNVERIFIED") {
		t.Error("goose must be marked UNVERIFIED")
	}
}

// ---------------------------------------------------------------------------
// Construction errors.
// ---------------------------------------------------------------------------

func TestCliConstructionErrors(t *testing.T) {
	cases := []struct {
		o    CliOptions
		want string
	}{
		{CliOptions{Style: CliStyles.Claude}, "cli: Command is required (path or name of the CLI binary)"},
		{CliOptions{Command: "x"}, "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"},
		{CliOptions{Style: CliStyles.Goose, Command: "goose", InProcessOptions: InProcessOptions{Model: "m"}, Transport: CliTransports.CLI}, `cli: style "goose" takes no model`},
		{CliOptions{Style: CliStyles.Codex, Command: "codex", Transport: CliTransports.ACP}, `cli: style "codex" has no ACP launch; set ACPCommand or use CliTransports.CLI`},
		{CliOptions{Style: CliStyles.Custom, Command: "x", Args: []any{"{{model}}"}, Prompt: "stdin", Answer: "stdout"}, "cli: the style needs a model: {{model}} is outside an optional group"},
		{CliOptions{Style: CliStyles.Custom, Command: "x", Args: []any{"{{nope}}"}, Prompt: "stdin", Answer: "stdout"}, "cli: unknown placeholder {{nope}}"},
		{CliOptions{Style: CliStyles.Custom, Command: "x", Prompt: "arg", Answer: "stdout"}, `cli: prompt "arg" needs {{prompt}} in args`},
		{CliOptions{Style: CliStyles.Custom, Command: "x", Prompt: "file", Answer: "stdout"}, `cli: prompt "file" needs {{file}} in args`},
		{CliOptions{Style: CliStyles.Custom, Command: "x", Prompt: "stdin", Answer: "out"}, `cli: answer "out" needs {{out}} in args`},
		{CliOptions{Style: CliStyles.Custom, Command: "x", Prompt: "pipe", Answer: "stdout"}, `cli: prompt must be stdin, arg or file, not "pipe"`},
		{CliOptions{Style: CliStyles.Custom, Command: "x", Prompt: "stdin", Answer: "json"}, `cli: answer must be stdout or out, not "json"`},
	}
	for _, c := range cases {
		_, err := CreateCliClient(c.o)
		if err == nil || err.Error() != c.want {
			t.Errorf("got %v, want %q", err, c.want)
		}
	}
}

// ---------------------------------------------------------------------------
// The envelope parser.
// ---------------------------------------------------------------------------

func TestParseCliReply(t *testing.T) {
	cases := []struct {
		in       string
		content  string
		toolName string
	}{
		{`{"content":"hi"}`, "hi", ""},
		{"```json\n{\"content\":\"fenced\"}\n```\n\n", "fenced", ""},
		{`Sure! {"content":"wrapped"} done`, "wrapped", ""},
		{`{"choices":[{"message":{"content":"openai"}}]}`, "openai", ""},
		{`{"message":{"content":"msg"}}`, "msg", ""},
		{`{"tool_calls":[{"id":"a","function":{"name":"t","arguments":"{\"x\":1}"}}]}`, "", "t"},
		{`{"tool_calls":[{"name":"flat","arguments":{"x":1}}]}`, "", "flat"},
		{`plain text answer`, "plain text answer", ""},
		{`{"content":"x"} trailing {`, "x", ""},
		{`{"answer":1}`, `{"answer":1}`, ""},
		{`{"content":"x"} {"content":"y"}`, `{"content":"x"} {"content":"y"}`, ""},
	}
	for _, c := range cases {
		r := ParseCliReply(c.in)
		if c.toolName != "" {
			if len(r.ToolCalls) != 1 || r.ToolCalls[0].Name != c.toolName {
				t.Errorf("%q: tool calls %+v", c.in, r.ToolCalls)
			}
			continue
		}
		if r.Content != c.content || len(r.ToolCalls) != 0 {
			t.Errorf("%q: got %+v", c.in, r)
		}
	}
	if !strings.HasPrefix(RenderCliPrompt(nil, nil), CliPreamble+"\nREQUEST:\n{\"messages\":[],\"tools\":[]}") {
		t.Error("render shape")
	}
}

// ---------------------------------------------------------------------------
// One-shot transport.
// ---------------------------------------------------------------------------

func TestCliOneShotToolRoundTrip(t *testing.T) {
	rec := filepath.Join(t.TempDir(), "rec")
	o := fakeCLIOpts("tool")
	o.Env["TN_FAKE_REC"] = rec
	o.SystemPrompt = "You are a badge clerk."
	tk := cliBadgeToolkit(t)
	res, err := runCli(t, o, tk)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(res.Text, "BADGE-77Q") || len(res.ToolCalls) != 1 || res.ToolCalls[0].Name != "get_badge_number" {
		t.Fatalf("got %q %+v", res.Text, res.ToolCalls)
	}
	b, _ := os.ReadFile(rec)
	prompts := strings.Split(string(b), "\n=====\n")
	if !strings.HasPrefix(prompts[0], CliPreamble+"\nREQUEST:\n") {
		t.Error("prompt must start with the preamble")
	}
	var req struct {
		Messages []map[string]any `json:"messages"`
		Tools    []any            `json:"tools"`
	}
	if err := json.Unmarshal([]byte(strings.SplitN(prompts[0], "\nREQUEST:\n", 2)[1]), &req); err != nil {
		t.Fatal(err)
	}
	if req.Messages[0]["role"] != "system" || !strings.Contains(fmt.Sprint(req.Messages[0]["content"]), "badge clerk") || len(req.Tools) == 0 {
		t.Fatalf("system prompt must be the first message, tools listed: %+v", req.Messages[0])
	}
}

func TestCliPromptModesAndOutFile(t *testing.T) {
	tk := cliBadgeToolkit(t)
	for _, o := range []CliOptions{
		{Args: []any{"--p", "{{prompt}}"}, Prompt: "arg", Answer: "stdout"},
		{Args: []any{"--f", "{{file}}"}, Prompt: "file", Answer: "stdout"},
		{Args: []any{"--o", "{{out}}"}, Prompt: "stdin", Answer: "out"},
	} {
		base := fakeCLIOpts("tool")
		base.Args, base.Prompt, base.Answer = o.Args, o.Prompt, o.Answer
		res, err := runCli(t, base, tk)
		if err != nil || !strings.Contains(res.Text, "BADGE-77Q") {
			t.Errorf("%s/%s: %v %q", o.Prompt, o.Answer, err, res.Text)
		}
	}
}

func TestCliModelGroup(t *testing.T) {
	o := fakeCLIOpts("x", "--argv", "-q")
	o.Args = append(o.Args, []any{"--model", "{{model}}"})
	res, err := runCli(t, o, nil)
	if err != nil || strings.Contains(res.Text, "--model") {
		t.Fatalf("no model ⇒ group dropped: %v %q", err, res.Text)
	}
	o.Model = "prov/m-1"
	res, err = runCli(t, o, nil)
	if err != nil || !strings.Contains(res.Text, "--model prov/m-1") {
		t.Fatalf("model passed through: %v %q", err, res.Text)
	}
}

func TestCliPinnedErrors(t *testing.T) {
	cases := []struct {
		o    CliOptions
		want string
	}{
		{fakeCLIOpts("exit"), "cli: exit 3: boom: bad login"},
		{fakeCLIOpts("empty"), "cli: empty reply"},
		{func() CliOptions {
			o := fakeCLIOpts("nooutfile", "--x", "{{out}}")
			o.Answer = "out"
			return o
		}(), "cli: no output file"},
		{func() CliOptions { o := fakeCLIOpts("sleep"); o.CallTimeoutMs = 300; return o }(), "cli: timed out after 300ms"},
		{func() CliOptions { o := fakeCLIOpts("x"); o.Command = "definitely-not-a-cli-xyz"; return o }(), "cli: command not found: definitely-not-a-cli-xyz"},
	}
	for _, c := range cases {
		_, err := runCli(t, c.o, nil)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("got %v, want %q", err, c.want)
		}
	}
}

func TestCliOneShotStreamsNonIncrementally(t *testing.T) {
	c, err := CreateCliClient(fakeCLIOpts("tool"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ch, err := c.Stream(context.Background(), "What is my badge number?", cliBadgeToolkit(t))
	if err != nil {
		t.Fatal(err)
	}
	var types []string
	text := ""
	for ev := range ch {
		if ev.Type == "error" {
			t.Fatalf("stream must not error: %v", ev.Err)
		}
		if ev.Type == "text" {
			text += ev.Delta
		}
		if ev.Type != "usage" && (len(types) == 0 || types[len(types)-1] != ev.Type) {
			types = append(types, ev.Type)
		}
	}
	got := strings.Join(types, ",")
	if !strings.Contains(got, "tool_call,tool_result") || !strings.HasSuffix(got, "text,done") || !strings.Contains(text, "BADGE-77Q") {
		t.Fatalf("events %s text %q", got, text)
	}
}

// ---------------------------------------------------------------------------
// ACP transport.
// ---------------------------------------------------------------------------

func fakeACPOpts(t *testing.T, env map[string]string) (CliOptions, string) {
	rec := filepath.Join(t.TempDir(), "rec")
	e := map[string]string{"TN_FAKE_ACP": "1", "TN_FAKE_REC": rec}
	for k, v := range env {
		e[k] = v
	}
	d := CliStyleDef{Name: "fake", Args: []any{}, Prompt: "stdin", Answer: "stdout", ACP: &CliACPLaunch{Args: []string{"acp"}}}
	return CliOptions{StyleDef: &d, Command: os.Args[0], Env: e, Toolkit: cliBadgeToolkit(t),
		BridgeCommand: []string{os.Args[0], "mcp-bridge"}}, rec
}

func assertFakeACPRun(t *testing.T, c *CliClient, res RunResult, entry string) {
	t.Helper()
	if c.Transport() != "acp" {
		t.Fatalf("transport %s (%s)", c.Transport(), c.FallbackReason())
	}
	for _, want := range []string{"perm ours=yes own=no fake=no again=yes", "entry=" + entry, "BADGE-77Q", "Hello"} {
		if !strings.Contains(res.Text, want) {
			t.Errorf("missing %q in %q", want, res.Text)
		}
	}
	if !strings.Contains(res.Text, `"name":"load_skill"`) || strings.Contains(res.Text, `"name":"skill"`) {
		t.Error("skill must be served as load_skill, never skill")
	}
	info, ok := c.ACPSession()
	if !ok || len(info.ServedCalls) != 2 {
		t.Fatalf("server must record the executed tools: %+v", info)
	}
}

func TestCliACPHTTPEntryWithBearerToken(t *testing.T) {
	o, rec := fakeACPOpts(t, map[string]string{"FAKE_HTTP": "1"})
	c, err := CreateCliClient(o)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	res, err := c.Run(context.Background(), "badge?", nil)
	if err != nil {
		t.Fatal(err)
	}
	assertFakeACPRun(t, c, res, "http")
	if !strings.Contains(res.Text, "noauth=401") {
		t.Errorf("endpoint must refuse a request without the bearer token: %q", res.Text)
	}
	b, _ := os.ReadFile(rec)
	if !strings.Contains(string(b), `"headers":[{"name":"Authorization","value":"Bearer `) {
		t.Errorf("session/new must carry the bearer header: %s", b)
	}
	info, _ := c.ACPSession()
	if strings.Contains(cliJSON(info), "Bearer ") {
		t.Error("ACPSession must redact the token")
	}
}

func TestCliACPStdioBridgeWhenAgentLacksHTTP(t *testing.T) {
	o, rec := fakeACPOpts(t, map[string]string{"FAKE_HTTP": "0"})
	c, _ := CreateCliClient(o)
	defer c.Close()
	res, err := c.Run(context.Background(), "badge?", nil)
	if err != nil {
		t.Fatal(err)
	}
	assertFakeACPRun(t, c, res, "<nil>")
	b, _ := os.ReadFile(rec)
	if !strings.Contains(string(b), `"env":[{"name":"TOOLNEXUS_MCP_TOKEN"`) || strings.Contains(string(b), `"type":"stdio"`) {
		t.Errorf("stdio entry shape: %s", b)
	}
}

func TestCliACPProjectMCPFile(t *testing.T) {
	o, _ := fakeACPOpts(t, map[string]string{"FAKE_HTTP": "0", "FAKE_PROJECT": "1"})
	o.StyleDef.ACP.ProjectMCPFile = ".mcp.json"
	c, _ := CreateCliClient(o)
	res, err := c.Run(context.Background(), "badge?", nil)
	if err != nil {
		t.Fatal(err)
	}
	assertFakeACPRun(t, c, res, "http")
	info, _ := c.ACPSession()
	if len(info.MCPServersSent) != 0 || info.SessionCwd == "" {
		t.Fatalf("project-file mode sends no session servers and uses a temp cwd: %+v", info)
	}
	if _, err := os.Stat(filepath.Join(info.SessionCwd, ".mcp.json")); err != nil {
		t.Fatal(err)
	}
	_ = c.Close()
	if _, err := os.Stat(info.SessionCwd); !os.IsNotExist(err) {
		t.Error("temp session dir must be removed on Close")
	}
	resp, err := http.Post(info.URL, "application/json", strings.NewReader("{}"))
	if err == nil {
		resp.Body.Close()
		t.Error("served endpoint must stop with the session")
	}
}

func TestCliACPModelPassThrough(t *testing.T) {
	o, rec := fakeACPOpts(t, map[string]string{"FAKE_HTTP": "1", "FAKE_NO_CONFIG": "1"})
	o.Model = "anthropic/claude-x"
	o.Transport = CliTransports.ACP // the fake style's one-shot args take no model
	c, _ := CreateCliClient(o)
	defer c.Close()
	if _, err := c.Run(context.Background(), "badge?", nil); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(rec)
	s := string(b)
	if !strings.Contains(s, `session/set_config_option {"configId":"model","sessionId":"s1","value":"anthropic/claude-x"}`) ||
		!strings.Contains(s, `session/set_model {"modelId":"anthropic/claude-x","sessionId":"s1"}`) {
		t.Errorf("model must go to set_config_option then set_model, unchanged: %s", s)
	}
	if !strings.Contains(s, `"terminal":false`) {
		t.Error("terminal capability must be declared false")
	}
}

func TestCliACPFallsBackToOneShot(t *testing.T) {
	o, _ := fakeACPOpts(t, map[string]string{"FAKE_FAIL_INIT": "1", "TN_FAKE_CLI": "tool"})
	o.StyleDef.Args = []any{}
	c, err := CreateCliClient(o)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if c.Transport() != "auto" {
		t.Fatalf("undecided before the first call, got %s", c.Transport())
	}
	res, err := c.Run(context.Background(), "badge?", o.Toolkit)
	if err != nil {
		t.Fatal(err)
	}
	if c.Transport() != "cli" || c.FallbackReason() == "" || !strings.Contains(res.Text, "BADGE-77Q") {
		t.Fatalf("transport=%s reason=%q text=%q", c.Transport(), c.FallbackReason(), res.Text)
	}
	// Forced ACP never falls back.
	o2, _ := fakeACPOpts(t, map[string]string{"FAKE_FAIL_INIT": "1"})
	o2.Transport = CliTransports.ACP
	c2, _ := CreateCliClient(o2)
	defer c2.Close()
	if _, err := c2.Run(context.Background(), "badge?", nil); err == nil || !strings.Contains(err.Error(), "cli: acp: ") {
		t.Fatalf("forced ACP must fail, got %v", err)
	}
}

func TestCliIsServedCallExactForms(t *testing.T) {
	served := map[string]bool{"get_badge_number": true, "load_skill": true}
	for title, want := range map[string]bool{
		"get_badge_number":                          true,
		"mcp__toolnexus__get_badge_number":          true,
		"toolnexus/get_badge_number":                true,
		"get_badge_number (toolnexus MCP Server)":   true,
		"Calling get_badge_number from toolnexus":   true,
		"load_skill(name: secret-recipe)":           true,
		"skill":                                     false,
		"Bash":                                      false,
		"":                                          false,
		"mcp__other__get_badge_number":              false,
		"run get_badge_number and then rm -rf":      false,
		"Calling get_badge_number from toolnexus2":  false,
		"get_badge_number (evil MCP Server)":        false,
		"mcp__toolnexus__get_badge_number_and_more": false,
	} {
		if got := cliIsServedCall(title, served); got != want {
			t.Errorf("%q: got %v want %v", title, got, want)
		}
	}
}

func TestCliACPSystemHintFromStyle(t *testing.T) {
	d, _ := CliStyleDefFor(CliStyles.Devin)
	if d.ACP == nil || !strings.Contains(d.ACP.SystemHint, `"toolnexus"`) {
		t.Fatalf("devin style must carry a systemHint naming the toolnexus server: %+v", d.ACP)
	}
	o, rec := fakeACPOpts(t, map[string]string{"FAKE_HTTP": "1"})
	o.StyleDef.ACP.SystemHint = "HINT-USE-TOOLNEXUS"
	o.SystemPrompt = "You are terse."
	c, _ := CreateCliClient(o)
	defer c.Close()
	if _, err := c.Run(context.Background(), "badge?", nil); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(rec)
	if !strings.Contains(string(b), `You are terse.\n\nHINT-USE-TOOLNEXUS`) {
		t.Errorf("hint must be appended to the system prompt of the ACP turn: %s", b)
	}
	// No system prompt: the hint becomes the system message on its own.
	if got := cliWithSystemHint([]any{map[string]any{"role": "user", "content": "x"}}, "H"); len(got) != 2 ||
		got[0].(map[string]any)["content"] != "H" {
		t.Errorf("hint without a system message: %+v", got)
	}
	msgs := []any{map[string]any{"role": "system", "content": "S"}}
	_ = cliWithSystemHint(msgs, "H")
	if msgs[0].(map[string]any)["content"] != "S" {
		t.Error("caller's messages must not be mutated")
	}
}

// TestCliConformanceCases replays conformance/cli-client/cases.json, the
// fixture every port runs.
func TestCliConformanceCases(t *testing.T) {
	b, err := os.ReadFile("../conformance/cli-client/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var fx struct {
		Argv []struct {
			Style, Command, Model, Prompt string
			Argv                          []string
			Stdin                         bool
		}
		Parse []struct {
			In       string
			Content  *string
			ToolCall *struct {
				Name, ID  string
				Arguments any
			}
		}
		ConstructionErrors []struct {
			Options map[string]any
			Error   string
		}
		RenderEmpty string
	}
	if err := json.Unmarshal(b, &fx); err != nil {
		t.Fatal(err)
	}
	byName := map[string]CliStyle{}
	for s, n := range cliStyleNames {
		byName[n] = s
	}
	for _, c := range fx.Argv {
		cl, err := CreateCliClient(CliOptions{Style: byName[c.Style], Command: c.Command, Transport: CliTransports.CLI,
			InProcessOptions: InProcessOptions{Model: c.Model}})
		if err != nil {
			t.Fatalf("%s: %v", c.Style, err)
		}
		argv, out, tmp, err := cl.cliArgv(c.Prompt)
		for _, f := range tmp {
			_ = os.Remove(f)
		}
		if err != nil {
			t.Fatal(err)
		}
		for i, a := range argv {
			if out != "" && a == out {
				argv[i] = "<out>"
			} else if a != out && len(tmp) > 0 && a == tmp[0] {
				argv[i] = "<file>"
			}
		}
		if cliJSON(argv) != cliJSON(c.Argv) || (cl.style.Prompt == "stdin") != c.Stdin {
			t.Errorf("%s model=%q: argv %v want %v", c.Style, c.Model, argv, c.Argv)
		}
	}
	norm := func(v any) string {
		if s, ok := v.(string); ok {
			var x any
			if json.Unmarshal([]byte(s), &x) == nil {
				v = x
			}
		}
		var x any
		_ = json.Unmarshal([]byte(cliJSON(v)), &x)
		return cliJSON(x)
	}
	for _, c := range fx.Parse {
		r := ParseCliReply(c.In)
		if c.ToolCall != nil {
			if len(r.ToolCalls) != 1 || r.ToolCalls[0].Name != c.ToolCall.Name || r.ToolCalls[0].ID != c.ToolCall.ID ||
				norm(r.ToolCalls[0].Arguments) != norm(c.ToolCall.Arguments) {
				t.Errorf("%q: %+v", c.In, r.ToolCalls)
			}
			continue
		}
		if r.Content != *c.Content || len(r.ToolCalls) != 0 {
			t.Errorf("%q: got %+v", c.In, r)
		}
	}
	for _, c := range fx.ConstructionErrors {
		o := CliOptions{}
		if s, ok := c.Options["style"].(string); ok {
			o.Style = byName[s]
		}
		o.Command, _ = c.Options["command"].(string)
		o.Model, _ = c.Options["model"].(string)
		o.Prompt, _ = c.Options["prompt"].(string)
		o.Answer, _ = c.Options["answer"].(string)
		if a, ok := c.Options["args"].([]any); ok {
			o.Args = a
		}
		switch c.Options["transport"] {
		case "cli":
			o.Transport = CliTransports.CLI
		case "acp":
			o.Transport = CliTransports.ACP
		}
		if _, err := CreateCliClient(o); err == nil || err.Error() != c.Error {
			t.Errorf("%v: got %v, want %q", c.Options, err, c.Error)
		}
	}
	if want := strings.Replace(fx.RenderEmpty, "<PREAMBLE>", CliPreamble, 1); RenderCliPrompt(nil, nil) != want {
		t.Error("renderEmpty")
	}
}

// TestCliStreamEveryStyleBothTransports: streaming never errors on a CLI
// client, for every built-in style and custom, over the one-shot CLI and ACP.
func TestCliStreamEveryStyleBothTransports(t *testing.T) {
	styles := []CliStyle{CliStyles.Claude, CliStyles.Codex, CliStyles.Copilot, CliStyles.Opencode,
		CliStyles.Devin, CliStyles.Gemini, CliStyles.Goose, CliStyles.Custom}
	for _, st := range styles {
		for _, tr := range []CliTransport{CliTransports.CLI, CliTransports.ACP} {
			t.Run(st.String()+"/"+tr.String(), func(t *testing.T) {
				tk := cliBadgeToolkit(t)
				o := CliOptions{Style: st, Command: os.Args[0], Transport: tr, Toolkit: tk,
					Env: map[string]string{"TN_FAKE_CLI": "tool"}}
				if st == CliStyles.Custom {
					o.Args, o.Prompt, o.Answer = []any{}, "stdin", "stdout"
				}
				if tr == CliTransports.ACP {
					fo, _ := fakeACPOpts(t, map[string]string{"FAKE_HTTP": "1"})
					o.Env, o.BridgeCommand = fo.Env, fo.BridgeCommand
					o.ACPCommand = []string{os.Args[0], "acp"}
				}
				c, err := CreateCliClient(o)
				if err != nil {
					t.Fatal(err)
				}
				defer c.Close()
				ch, err := c.Stream(context.Background(), "What is my badge number?", tk)
				if err != nil {
					t.Fatal(err)
				}
				text, last, sawTool := "", "", false
				for ev := range ch {
					switch ev.Type {
					case "error":
						t.Fatalf("stream must not error: %v", ev.Err)
					case "text":
						text += ev.Delta
					case "tool_call":
						sawTool = true
					}
					last = ev.Type
				}
				if last != "done" || !strings.Contains(text, "BADGE-77Q") {
					t.Fatalf("last=%s text=%q", last, text)
				}
				if tr == CliTransports.CLI && !sawTool {
					t.Error("one-shot stream must carry the loop's tool_call event")
				}
				if c.Transport() != tr.String() {
					t.Errorf("transport %s", c.Transport())
				}
			})
		}
	}
}

// TestCliACPStreamsIncrementally pins conformance/cli-client/cases.json
// stream.acpDrip: over ACP the chunks arrive as separate deltas, as they are
// produced — a buffered or single-chunk stream fails the lead-time check.
func TestCliACPStreamsIncrementally(t *testing.T) {
	o, _ := fakeACPOpts(t, map[string]string{"FAKE_HTTP": "1", "TN_FAKE_DRIP": "1"})
	o.Transport = CliTransports.ACP
	c, err := CreateCliClient(o)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ch, err := c.Stream(context.Background(), "hi", o.Toolkit)
	if err != nil {
		t.Fatal(err)
	}
	var deltas, thoughts []string
	var first, doneAt time.Time
	var done *RunResult
	for ev := range ch {
		switch ev.Type {
		case "error":
			t.Fatalf("stream error: %v", ev.Err)
		case "text":
			if first.IsZero() {
				first = time.Now()
			}
			deltas = append(deltas, ev.Delta)
		case "thought":
			if ev.Via != "acp" {
				t.Errorf("thought via %q", ev.Via)
			}
			thoughts = append(thoughts, ev.Delta)
		case "done":
			doneAt, done = time.Now(), ev.Result
		}
	}
	if strings.Join(deltas, "|") != "drip-1 |drip-2 |drip-3" {
		t.Fatalf("deltas %q", deltas)
	}
	if strings.Join(thoughts, "|") != "pondering" {
		t.Fatalf("thoughts %q", thoughts)
	}
	if done == nil || done.Text != "drip-1 drip-2 drip-3" {
		t.Fatalf("done %+v", done)
	}
	if lead := doneAt.Sub(first); lead < 300*time.Millisecond {
		t.Fatalf("first delta only %v before done: not incremental", lead)
	}
	res, err := c.Run(context.Background(), "hi", o.Toolkit)
	if err != nil || res.Text != "drip-1 drip-2 drip-3" {
		t.Fatalf("run %q %v", res.Text, err)
	}
}
