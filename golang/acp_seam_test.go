// Hermetic replay of conformance/acp-client/cases.json (SPEC §8C): the plain
// ACP client (passthrough, agent exit, unknown requests, incremental streaming)
// against a fake ACP agent, and Toolkit.AsMCPServer. The test binary plays the
// fake agent when TN_FAKE_ACP=1 (chosen in TestMain, rag_consumer_test.go).
package toolnexus

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"testing"
	"time"

	mcpclient "github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/client/transport"
	"github.com/mark3labs/mcp-go/mcp"
)

func acpSeamHelperMain() bool {
	if os.Getenv("TN_FAKE_ACP") == "1" {
		fakeSeamACPAgent()
		return true
	}
	return false
}

// fakeSeamACPAgent records every received method + params (one JSON line per
// message, to TN_FAKE_REC) and honours TN_FAKE_DRIP, TN_FAKE_NO_CONFIG_OPTION, TN_FAKE_REJECT_MODE,
// TN_FAKE_EXIT_ON_PROMPT and TN_FAKE_FS_REQUEST.
func fakeSeamACPAgent() {
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 0, 1<<16), 1<<24)
	out := json.NewEncoder(os.Stdout)
	rec := func(method string, params json.RawMessage) {
		if p := os.Getenv("TN_FAKE_REC"); p != "" {
			f, _ := os.OpenFile(p, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
			b, _ := json.Marshal(map[string]any{"method": method, "params": params})
			_, _ = f.Write(append(b, '\n'))
			_ = f.Close()
		}
	}
	reply := func(id json.RawMessage, result any) {
		_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
	}
	update := func(u map[string]any) {
		_ = out.Encode(map[string]any{"jsonrpc": "2.0", "method": "session/update", "params": map[string]any{"sessionId": "s1", "update": u}})
	}
	text := func(kind, s string) map[string]any {
		return map[string]any{"sessionUpdate": kind, "content": map[string]any{"type": "text", "text": s}}
	}
	for in.Scan() {
		var m struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
		}
		_ = json.Unmarshal(in.Bytes(), &m)
		rec(m.Method, m.Params)
		switch m.Method {
		case "initialize":
			reply(m.ID, map[string]any{"protocolVersion": 1, "agentCapabilities": map[string]any{}})
		case "session/new":
			reply(m.ID, map[string]any{"sessionId": "s1"})
		case "session/set_config_option":
			if os.Getenv("TN_FAKE_NO_CONFIG_OPTION") == "1" {
				_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "error": map[string]any{"code": -32601, "message": "nope"}})
			} else {
				reply(m.ID, map[string]any{})
			}
		case "session/set_model":
			reply(m.ID, map[string]any{})
		case "session/set_mode":
			if os.Getenv("TN_FAKE_REJECT_MODE") == "1" {
				_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": m.ID, "error": map[string]any{"code": -32602, "message": "unknown mode"}})
			} else {
				reply(m.ID, map[string]any{})
			}
		case "session/prompt":
			switch {
			case os.Getenv("TN_FAKE_EXIT_ON_PROMPT") == "1":
				os.Exit(0)
			case os.Getenv("TN_FAKE_DRIP") == "1":
				update(text("agent_thought_chunk", "pondering"))
				for i, chunk := range []string{"drip-1 ", "drip-2 ", "drip-3"} {
					if i > 0 {
						time.Sleep(200 * time.Millisecond)
					}
					update(text("agent_message_chunk", chunk))
				}
			case os.Getenv("TN_FAKE_TOOLUPDATE") == "1":
				var cs acpCases
				raw, _ := os.ReadFile("../conformance/acp-client/cases.json")
				_ = json.Unmarshal(raw, &cs)
				for _, u := range cs.Stream.AcpToolUpdate.Updates {
					update(u)
				}
			case os.Getenv("TN_FAKE_FS_REQUEST") == "1":
				_ = out.Encode(map[string]any{"jsonrpc": "2.0", "id": 9001, "method": "fs/read_text_file",
					"params": map[string]any{"sessionId": "s1", "path": "/x"}})
				for in.Scan() {
					var r struct {
						ID    int `json:"id"`
						Error *struct {
							Code int `json:"code"`
						} `json:"error"`
					}
					if json.Unmarshal(in.Bytes(), &r) == nil && r.ID == 9001 {
						if r.Error != nil && r.Error.Code == -32601 {
							update(text("agent_message_chunk", "ok"))
						} else {
							update(text("agent_message_chunk", "bad answer"))
						}
						break
					}
				}
			default:
				update(text("agent_message_chunk", "hello"))
			}
			reply(m.ID, map[string]any{"stopReason": "end_turn"})
		}
	}
}

type acpCases struct {
	Stream struct {
		AcpDrip struct {
			FakeEnv        map[string]string `json:"fakeEnv"`
			Chunks         []string          `json:"chunks"`
			MinLeadMs      int               `json:"minLeadMs"`
			ExpectDoneText string            `json:"expectDoneText"`
			Thought        string            `json:"thought"`
		} `json:"acpDrip"`
		AcpToolUpdate struct {
			FakeEnv        map[string]string `json:"fakeEnv"`
			Updates        []map[string]any  `json:"updates"`
			ExpectEvents   []map[string]any  `json:"expectEvents"`
			ExpectDoneText string            `json:"expectDoneText"`
		} `json:"acpToolUpdate"`
	} `json:"stream"`
	Passthrough struct {
		Options struct {
			MCPServers     []map[string]any `json:"mcpServers"`
			SessionMeta    map[string]any   `json:"sessionMeta"`
			ClientCapsMeta map[string]any   `json:"clientCapsMeta"`
			Model          string           `json:"model"`
		} `json:"options"`
		ExpectInitializeClientCapabilities map[string]any `json:"expectInitializeClientCapabilities"`
		ExpectSetConfigOption              map[string]any `json:"expectSetConfigOption"`
		FallbackSetModel                   struct {
			FakeEnv        map[string]string `json:"fakeEnv"`
			ExpectSetModel map[string]any    `json:"expectSetModel"`
		} `json:"fallbackSetModel"`
		BareExpect struct {
			MCPServers                []any `json:"mcpServers"`
			SessionNewHasMeta         bool  `json:"sessionNewHasMeta"`
			ClientCapabilitiesHasMeta bool  `json:"clientCapabilitiesHasMeta"`
		} `json:"bareExpect"`
	} `json:"passthrough"`
	AgentExit struct {
		FakeEnv     map[string]string `json:"fakeEnv"`
		ExpectError string            `json:"expectError"`
	} `json:"agentExit"`
	UnknownRequest struct {
		FakeEnv    map[string]string `json:"fakeEnv"`
		ExpectText string            `json:"expectText"`
	} `json:"unknownRequest"`
	AsMcpServer struct {
		URLPattern         string            `json:"urlPattern"`
		TokenPattern       string            `json:"tokenPattern"`
		NoTokenStatus      int               `json:"noTokenStatus"`
		NoTokenHeader      map[string]string `json:"noTokenHeader"`
		WrongTokenStatus   int               `json:"wrongTokenStatus"`
		GetWithTokenStatus int               `json:"getWithTokenStatus"`
		OtherPathStatus    int               `json:"otherPathStatus"`
		ToolsListContains  []string          `json:"toolsListContains"`
		CallEcho           struct {
			Name       string         `json:"name"`
			Arguments  map[string]any `json:"arguments"`
			ExpectText string         `json:"expectText"`
		} `json:"callEcho"`
	} `json:"asMcpServer"`
}

func loadACPCases(t *testing.T) acpCases {
	t.Helper()
	b, err := os.ReadFile("../conformance/acp-client/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var c acpCases
	if err := json.Unmarshal(b, &c); err != nil {
		t.Fatal(err)
	}
	return c
}

// fakeACP returns LoadACP options for the fake agent plus its record file.
func fakeACP(t *testing.T, env map[string]string) (ACPOptions, string) {
	t.Helper()
	recFile := filepath.Join(t.TempDir(), "rec.jsonl")
	e := []string{"TN_FAKE_ACP=1", "TN_FAKE_REC=" + recFile}
	for k, v := range env {
		e = append(e, k+"="+v)
	}
	return ACPOptions{Command: os.Args[0], Env: e, RequestTimeout: 30 * time.Second, PermissionTimeout: 30 * time.Second}, recFile
}

// recorded returns the params of every recorded call of method.
func recorded(t *testing.T, recFile, method string) []map[string]any {
	t.Helper()
	b, _ := os.ReadFile(recFile)
	var out []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(string(b)), "\n") {
		var r struct {
			Method string         `json:"method"`
			Params map[string]any `json:"params"`
		}
		if json.Unmarshal([]byte(line), &r) == nil && r.Method == method {
			out = append(out, r.Params)
		}
	}
	return out
}

func roundTrip(t *testing.T, v any) any {
	t.Helper()
	b, _ := json.Marshal(v)
	var out any
	_ = json.Unmarshal(b, &out)
	return out
}

func TestACPSeamPassthrough(t *testing.T) {
	cs := loadACPCases(t).Passthrough
	o, rec := fakeACP(t, nil)
	o.MCPServers = cs.Options.MCPServers
	o.SessionMeta = cs.Options.SessionMeta
	o.ClientCapsMeta = cs.Options.ClientCapsMeta
	o.Model = cs.Options.Model
	c, err := LoadACP(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	_ = c.Close()

	inits := recorded(t, rec, "initialize")
	if len(inits) != 1 || !reflect.DeepEqual(inits[0]["clientCapabilities"], roundTrip(t, cs.ExpectInitializeClientCapabilities)) {
		t.Fatalf("initialize %v", inits)
	}
	news := recorded(t, rec, "session/new")
	if len(news) != 1 {
		t.Fatalf("session/new %v", news)
	}
	if !reflect.DeepEqual(news[0]["mcpServers"], roundTrip(t, cs.Options.MCPServers)) {
		t.Fatalf("mcpServers not verbatim/in order: %v", news[0]["mcpServers"])
	}
	if !reflect.DeepEqual(news[0]["_meta"], roundTrip(t, cs.Options.SessionMeta)) {
		t.Fatalf("_meta %v", news[0]["_meta"])
	}
	sets := recorded(t, rec, "session/set_config_option")
	if len(sets) != 1 || sets[0]["sessionId"] != "s1" ||
		sets[0]["configId"] != cs.ExpectSetConfigOption["configId"] || sets[0]["value"] != cs.ExpectSetConfigOption["value"] {
		t.Fatalf("set_config_option %v", sets)
	}
	if got := recorded(t, rec, "session/set_model"); len(got) != 0 {
		t.Fatalf("set_model sent although set_config_option succeeded: %v", got)
	}

	// set_model fallback
	o2, rec2 := fakeACP(t, cs.FallbackSetModel.FakeEnv)
	o2.Model = cs.Options.Model
	c2, err := LoadACP(context.Background(), o2)
	if err != nil {
		t.Fatal(err)
	}
	_ = c2.Close()
	sm := recorded(t, rec2, "session/set_model")
	if len(sm) != 1 || sm[0]["modelId"] != cs.FallbackSetModel.ExpectSetModel["modelId"] || sm[0]["sessionId"] != "s1" {
		t.Fatalf("set_model %v", sm)
	}

	// bare
	o3, rec3 := fakeACP(t, nil)
	c3, err := LoadACP(context.Background(), o3)
	if err != nil {
		t.Fatal(err)
	}
	_ = c3.Close()
	n3 := recorded(t, rec3, "session/new")[0]
	if !reflect.DeepEqual(n3["mcpServers"], roundTrip(t, cs.BareExpect.MCPServers)) {
		t.Fatalf("bare mcpServers %v", n3["mcpServers"])
	}
	if _, has := n3["_meta"]; has != cs.BareExpect.SessionNewHasMeta {
		t.Fatalf("bare session/new _meta present=%v", has)
	}
	caps := recorded(t, rec3, "initialize")[0]["clientCapabilities"].(map[string]any)
	if _, has := caps["_meta"]; has != cs.BareExpect.ClientCapabilitiesHasMeta {
		t.Fatalf("bare clientCapabilities _meta present=%v", has)
	}
	if len(recorded(t, rec3, "session/set_config_option"))+len(recorded(t, rec3, "session/set_model")) != 0 {
		t.Fatal("model calls sent without Model")
	}
}

func TestACPSeamAgentExit(t *testing.T) {
	cs := loadACPCases(t).AgentExit
	o, _ := fakeACP(t, cs.FakeEnv)
	c, err := LoadACP(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	start := time.Now()
	_, err = c.GenerateCtx(context.Background(), acpUserMessage("hi"))
	if err == nil || !strings.Contains(err.Error(), cs.ExpectError) {
		t.Fatalf("err %v", err)
	}
	if time.Since(start) > 10*time.Second {
		t.Fatalf("took %v: waited out a timeout", time.Since(start))
	}
}

func TestACPSeamUnknownRequest(t *testing.T) {
	cs := loadACPCases(t).UnknownRequest
	o, _ := fakeACP(t, cs.FakeEnv)
	c, err := LoadACP(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	res, err := c.GenerateCtx(context.Background(), acpUserMessage("hi"))
	if err != nil || res.Content != cs.ExpectText {
		t.Fatalf("got %q %v", res.Content, err)
	}
}

// TestACPSeamStreamDrip pins stream.acpDrip: an in-process client on
// LoadACP(...).GenerateStream streams each chunk as it is produced.
func TestACPSeamStreamDrip(t *testing.T) {
	cs := loadACPCases(t).Stream.AcpDrip
	o, _ := fakeACP(t, cs.FakeEnv)
	acp, err := LoadACP(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	defer acp.Close()
	client := CreateInProcessClient(InProcessOptions{Model: "m", GenerateStream: acp.GenerateStream})
	tk := bareToolkit(t)
	ch, err := client.Stream(context.Background(), "hi", tk)
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
	if !reflect.DeepEqual(deltas, cs.Chunks) {
		t.Fatalf("deltas %q", deltas)
	}
	if !reflect.DeepEqual(thoughts, []string{cs.Thought}) {
		t.Fatalf("thoughts %q", thoughts)
	}
	if done == nil || done.Text != cs.ExpectDoneText {
		t.Fatalf("done %+v", done)
	}
	if lead := doneAt.Sub(first); lead < time.Duration(cs.MinLeadMs)*time.Millisecond {
		t.Fatalf("first delta only %v before done: not incremental", lead)
	}
	res, err := client.Run(context.Background(), "hi", tk)
	if err != nil || res.Text != cs.ExpectDoneText {
		t.Fatalf("run %q %v", res.Text, err)
	}
}

// TestACPSeamStreamToolUpdate pins stream.acpToolUpdate (issue #188): a
// tool_call_update's rawInput surfaces as tool_call_args and its title
// renames the call for the later tool_result.
func TestACPSeamStreamToolUpdate(t *testing.T) {
	cs := loadACPCases(t).Stream.AcpToolUpdate
	o, _ := fakeACP(t, cs.FakeEnv)
	acp, err := LoadACP(context.Background(), o)
	if err != nil {
		t.Fatal(err)
	}
	defer acp.Close()
	client := CreateInProcessClient(InProcessOptions{Model: "m", GenerateStream: acp.GenerateStream})
	ch, err := client.Stream(context.Background(), "hi", bareToolkit(t))
	if err != nil {
		t.Fatal(err)
	}
	var got []map[string]any
	var done *RunResult
	for ev := range ch {
		switch ev.Type {
		case "error":
			t.Fatalf("stream error: %v", ev.Err)
		case "text", "usage":
		case "done":
			done = ev.Result
		default:
			m := map[string]any{"type": ev.Type, "id": ev.ID, "name": ev.Name, "via": ev.Via}
			if ev.Type == "tool_call_args" {
				m["args"] = ev.Args
			}
			if ev.Type == "tool_result" {
				m["output"], m["isError"] = ev.Output, ev.IsError
			}
			got = append(got, m)
		}
	}
	if !reflect.DeepEqual(got, cs.ExpectEvents) {
		t.Fatalf("events\n got %v\nwant %v", got, cs.ExpectEvents)
	}
	if done == nil || done.Text != cs.ExpectDoneText {
		t.Fatalf("done %+v", done)
	}
}

func TestAsMCPServer(t *testing.T) {
	cs := loadACPCases(t).AsMcpServer
	echo := NativeTool("echo", "Echoes text.", JSONSchema{"type": "object", "properties": map[string]any{"text": map[string]any{"type": "string"}}},
		func(_ context.Context, args map[string]any) (string, error) {
			s, _ := args["text"].(string)
			return s, nil
		})
	tk, err := CreateToolkit(context.Background(), Options{ExtraTools: []Tool{echo}, Builtins: false, SkillsDir: []string{"../examples/skills"}})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	srv, err := tk.AsMCPServer(nil)
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(cs.URLPattern).MatchString(srv.URL) || !regexp.MustCompile(cs.TokenPattern).MatchString(srv.Token) {
		t.Fatalf("url %q token len %d", srv.URL, len(srv.Token))
	}

	do := func(method, url, auth string) *http.Response {
		t.Helper()
		req, _ := http.NewRequest(method, url, strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json, text/event-stream")
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		return resp
	}
	r := do("POST", srv.URL, "")
	if r.StatusCode != cs.NoTokenStatus {
		t.Fatalf("no token: %d", r.StatusCode)
	}
	for k, v := range cs.NoTokenHeader {
		if r.Header.Get(k) != v {
			t.Fatalf("header %s = %q", k, r.Header.Get(k))
		}
	}
	if r := do("POST", srv.URL, "Bearer "+strings.Repeat("0", 64)); r.StatusCode != cs.WrongTokenStatus {
		t.Fatalf("wrong token: %d", r.StatusCode)
	}
	if r := do("GET", srv.URL, "Bearer "+srv.Token); r.StatusCode != cs.GetWithTokenStatus {
		t.Fatalf("GET: %d", r.StatusCode)
	}
	if r := do("POST", strings.TrimSuffix(srv.URL, "/mcp")+"/other", "Bearer "+srv.Token); r.StatusCode != cs.OtherPathStatus {
		t.Fatalf("other path: %d", r.StatusCode)
	}

	// through the port's own MCP client
	c, err := mcpclient.NewStreamableHttpClient(srv.URL, transport.WithHTTPHeaders(map[string]string{"Authorization": "Bearer " + srv.Token}))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx := context.Background()
	if err := c.Start(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Initialize(ctx, mcp.InitializeRequest{}); err != nil {
		t.Fatal(err)
	}
	list, err := c.ListTools(ctx, mcp.ListToolsRequest{})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range cs.ToolsListContains {
		if !contains(toolNames(list.Tools), want) {
			t.Fatalf("tools %v missing %s", toolNames(list.Tools), want)
		}
	}
	var call mcp.CallToolRequest
	call.Params.Name = cs.CallEcho.Name
	call.Params.Arguments = cs.CallEcho.Arguments
	res, err := c.CallTool(ctx, call)
	if err != nil {
		t.Fatal(err)
	}
	if tc, ok := res.Content[0].(mcp.TextContent); !ok || tc.Text != cs.CallEcho.ExpectText {
		t.Fatalf("echo %+v", res.Content)
	}

	// two servers, distinct tokens; close idempotent and refuses afterwards
	srv2, err := tk.AsMCPServer(&MCPServeConfig{Tools: []string{"echo"}})
	if err != nil {
		t.Fatal(err)
	}
	if srv2.Token == srv.Token || srv2.URL == srv.URL {
		t.Fatal("two servers share a token or url")
	}
	_ = srv2.Close()
	if err := srv.Close(); err != nil {
		t.Fatal(err)
	}
	_ = srv.Close()
	if _, err := http.Post(srv.URL, "application/json", strings.NewReader("{}")); err == nil {
		t.Fatal("request after close was served")
	}
}

// TestACPSeamLiveSwitch: SetMode / SetModel on a running client (issue #197,
// conformance liveSwitch).
func TestACPSeamLiveSwitch(t *testing.T) {
	var all struct {
		LiveSwitch struct {
			Mode                  string         `json:"mode"`
			Model                 string         `json:"model"`
			ExpectSetMode         map[string]any `json:"expectSetMode"`
			ExpectSetConfigOption map[string]any `json:"expectSetConfigOption"`
			ExpectInitializeCount int            `json:"expectInitializeCount"`
			ExpectSessionNewCount int            `json:"expectSessionNewCount"`
			ExpectText            string         `json:"expectText"`
			Fallback              struct {
				FakeEnv        map[string]string `json:"fakeEnv"`
				ExpectSetModel map[string]any    `json:"expectSetModel"`
			} `json:"fallback"`
			Reject struct {
				FakeEnv             map[string]string `json:"fakeEnv"`
				ExpectErrorContains string            `json:"expectErrorContains"`
				ExpectText          string            `json:"expectText"`
			} `json:"reject"`
		} `json:"liveSwitch"`
	}
	b, err := os.ReadFile("../conformance/acp-client/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &all); err != nil {
		t.Fatal(err)
	}
	cs := all.LiveSwitch
	ctx := context.Background()
	turn := func(c *ACPClient) string {
		t.Helper()
		r, err := c.Generate(InProcessRequest{Messages: []any{map[string]any{"role": "user", "content": "q"}}})
		if err != nil {
			t.Fatal(err)
		}
		return r.Content
	}

	o, rec := fakeACP(t, nil)
	c, err := LoadACP(ctx, o)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.SetMode(ctx, cs.Mode); err != nil {
		t.Fatal(err)
	}
	if err := c.SetModel(ctx, cs.Model); err != nil {
		t.Fatal(err)
	}
	if got := turn(c); got != cs.ExpectText {
		t.Fatalf("turn %q", got)
	}
	_ = c.Close()
	if n := len(recorded(t, rec, "initialize")); n != cs.ExpectInitializeCount {
		t.Fatalf("initialize x%d", n)
	}
	if n := len(recorded(t, rec, "session/new")); n != cs.ExpectSessionNewCount {
		t.Fatalf("session/new x%d", n)
	}
	sm := recorded(t, rec, "session/set_mode")
	if len(sm) != 1 || sm[0]["sessionId"] != "s1" || sm[0]["modeId"] != cs.ExpectSetMode["modeId"] {
		t.Fatalf("set_mode %v", sm)
	}
	sc := recorded(t, rec, "session/set_config_option")
	if len(sc) != 1 || sc[0]["sessionId"] != "s1" || sc[0]["configId"] != cs.ExpectSetConfigOption["configId"] || sc[0]["value"] != cs.ExpectSetConfigOption["value"] {
		t.Fatalf("set_config_option %v", sc)
	}
	if err := c.SetMode(ctx, cs.Mode); err == nil || !strings.Contains(err.Error(), "closed") {
		t.Fatalf("closed client: %v", err)
	}

	// fallback
	o2, rec2 := fakeACP(t, cs.Fallback.FakeEnv)
	c2, err := LoadACP(ctx, o2)
	if err != nil {
		t.Fatal(err)
	}
	if err := c2.SetModel(ctx, cs.Model); err != nil {
		t.Fatal(err)
	}
	_ = c2.Close()
	fm := recorded(t, rec2, "session/set_model")
	if len(fm) != 1 || fm[0]["sessionId"] != "s1" || fm[0]["modelId"] != cs.Fallback.ExpectSetModel["modelId"] {
		t.Fatalf("set_model %v", fm)
	}

	// reject
	o3, _ := fakeACP(t, cs.Reject.FakeEnv)
	c3, err := LoadACP(ctx, o3)
	if err != nil {
		t.Fatal(err)
	}
	defer c3.Close()
	if err := c3.SetMode(ctx, cs.Mode); err == nil || !strings.Contains(err.Error(), cs.Reject.ExpectErrorContains) {
		t.Fatalf("reject: %v", err)
	}
	if got := turn(c3); got != cs.Reject.ExpectText {
		t.Fatalf("after reject %q", got)
	}
}
