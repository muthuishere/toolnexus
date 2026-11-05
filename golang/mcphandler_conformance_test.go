package toolnexus

// SPEC §7C.1 cross-port conformance: replays ../conformance/mcp_handler/cases.json
// (harness contract in conformance/mcp_handler/README.md).

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
)

type mhcFixtureTool struct {
	Name         string         `json:"name"`
	Description  string         `json:"description"`
	InputSchema  JSONSchema     `json:"inputSchema"`
	OutputSchema JSONSchema     `json:"outputSchema"`
	Annotations  map[string]any `json:"annotations"`
	Hints        *ToolHints     `json:"hints"`
	VisibleTo    []string       `json:"visibleTo"`
	Behavior     string         `json:"behavior"`
	Result       *mhcResult     `json:"result"`
	Error        string         `json:"error"`
	Decision     *mhcDecision   `json:"decision"`
}

type mhcResult struct {
	Output     string         `json:"output"`
	IsError    bool           `json:"isError"`
	Structured any            `json:"structured"`
	Meta       map[string]any `json:"meta"`
}

func (r *mhcResult) toolResult() ToolResult {
	return ToolResult{Output: r.Output, IsError: r.IsError, Structured: r.Structured, Meta: r.Meta}
}

type mhcDecision struct {
	Kind    string     `json:"kind"`
	Message string     `json:"message"`
	Result  *mhcResult `json:"result"`
}

type mhcStep struct {
	Request struct {
		Method      string            `json:"method"`
		Body        json.RawMessage   `json:"body"`
		RawBody     *string           `json:"rawBody"`
		ContentType string            `json:"contentType"`
		Accept      string            `json:"accept"`
		Token       string            `json:"token"`
		Session     bool              `json:"session"`
		SessionID   string            `json:"sessionId"`
		Version     string            `json:"version"`
		MCP2026     bool              `json:"mcp2026"`
		Headers     map[string]string `json:"headers"`
		Target      string            `json:"target"`
	} `json:"request"`
	Capture string `json:"capture"`
	Expect  struct {
		Status         int               `json:"status"`
		Headers        map[string]string `json:"headers"`
		HeadersPresent []string          `json:"headersPresent"`
		HeadersAbsent  []string          `json:"headersAbsent"`
		JSON           json.RawMessage   `json:"json"`
		JSONPath       map[string]any    `json:"jsonPath"`
	} `json:"expect"`
}

type mhcScenario struct {
	Name   string `json:"name"`
	Config struct {
		Auth               bool     `json:"auth"`
		Name               string   `json:"name"`
		Title              string   `json:"title"`
		Version            string   `json:"version"`
		Instructions       string   `json:"instructions"`
		Stateless          bool     `json:"stateless"`
		StatelessSessionID string   `json:"statelessSessionId"`
		ProtocolVersions   []string `json:"protocolVersions"`
		Skills             bool     `json:"skills"`
		ServerAuth         bool     `json:"serverAuth"`
	} `json:"config"`
	Token          string           `json:"token"`
	Steps          []mhcStep        `json:"steps"`
	ExpectExecuted *[]string        `json:"expectExecuted"`
	ExpectOnCall   []map[string]any `json:"expectOnCall"`
}

type mhcFile struct {
	Fixture struct {
		Auth struct {
			Tokens          map[string]string `json:"tokens"`
			WWWAuthenticate string            `json:"wwwAuthenticate"`
		} `json:"auth"`
		Tools      []mhcFixtureTool `json:"tools"`
		ServerAuth struct {
			Issuer     string              `json:"issuer"`
			Audience   string              `json:"audience"`
			Scopes     map[string][]string `json:"scopes"`
			Principals map[string]struct {
				Subject string   `json:"subject"`
				Scopes  []string `json:"scopes"`
			} `json:"principals"`
		} `json:"serverAuth"`
	} `json:"fixture"`
	Scenarios []mhcScenario `json:"scenarios"`
}

func TestMCPHandler_Conformance(t *testing.T) {
	raw, err := os.ReadFile("../conformance/mcp_handler/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var f mhcFile
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	for _, sc := range f.Scenarios {
		t.Run(sc.Name, func(t *testing.T) {
			var mu sync.Mutex
			executed := []string{}
			onCalls := []map[string]any{}

			tools := make([]Tool, 0, len(f.Fixture.Tools))
			for _, ft := range f.Fixture.Tools {
				ft := ft
				tl := Tool{Name: ft.Name, Description: ft.Description, Source: SourceNative,
					InputSchema: ft.InputSchema, OutputSchema: ft.OutputSchema, Hints: ft.Hints}
				if ft.Annotations != nil {
					a := &ToolAnnotations{}
					if s, ok := ft.Annotations["title"].(string); ok {
						a.Title = s
					}
					for k, dst := range map[string]**bool{"readOnlyHint": &a.ReadOnlyHint, "destructiveHint": &a.DestructiveHint, "idempotentHint": &a.IdempotentHint, "openWorldHint": &a.OpenWorldHint} {
						if b, ok := ft.Annotations[k].(bool); ok {
							*dst = hBoolp(b)
						}
					}
					tl.Annotations = a
				}
				tl.Execute = func(args map[string]any, tc *ToolContext) (ToolResult, error) {
					mu.Lock()
					executed = append(executed, ft.Name)
					mu.Unlock()
					switch ft.Behavior {
					case "echo":
						s, _ := args["text"].(string)
						return ToolResult{Output: s}, nil
					case "ask":
						if a := tc.Answer; a != nil && a.Ok {
							n, _ := a.Data["name"].(string)
							return ToolResult{Output: "Hello, " + n + "!"}, nil
						}
						return Pending(Request{ID: "name", Kind: "input", Prompt: "What is your name?", Data: map[string]any{
							"schema": map[string]any{"type": "object", "properties": map[string]any{"name": map[string]any{"type": "string"}}, "required": []any{"name"}}}}), nil
					case "whoami":
						id := mhcIdentityName(IdentityFrom(tc.Ctx))
						tr, _ := MCPRequestMetaFrom(tc.Ctx)["trace"].(string)
						return ToolResult{Output: id + "|" + tr}, nil
					case "error":
						return ToolResult{}, errors.New(ft.Error)
					}
					return ft.Result.toolResult(), nil
				}
				tools = append(tools, tl)
			}
			byName := map[string]mhcFixtureTool{}
			for _, ft := range f.Fixture.Tools {
				byName[ft.Name] = ft
			}

			cfg := MCPHandlerConfig{
				Name: sc.Config.Name, Title: sc.Config.Title, Version: sc.Config.Version, Instructions: sc.Config.Instructions,
				Stateless: sc.Config.Stateless, StatelessSessionID: sc.Config.StatelessSessionID, ProtocolVersions: sc.Config.ProtocolVersions,
				ListTools: func(ctx context.Context) ([]Tool, error) {
					id := mhcIdentityName(IdentityFrom(ctx))
					out := []Tool{}
					for _, tl := range tools {
						if v := byName[tl.Name].VisibleTo; v != nil {
							ok := false
							for _, x := range v {
								ok = ok || x == id
							}
							if !ok {
								continue
							}
						}
						out = append(out, tl)
					}
					return out, nil
				},
				BeforeCall: func(_ context.Context, tl Tool, _ json.RawMessage) (MCPDecision, error) {
					d := byName[tl.Name].Decision
					if d == nil {
						return MCPDecision{}, nil
					}
					out := MCPDecision{Kind: MCPDecisionKind(d.Kind), Message: d.Message}
					if d.Result != nil {
						r := d.Result.toolResult()
						out.Result = &r
					}
					return out, nil
				},
				OnCall: func(ev MCPCallEvent) {
					mu.Lock()
					onCalls = append(onCalls, map[string]any{"name": ev.Name, "isError": ev.IsError, "decision": string(ev.Decision)})
					mu.Unlock()
				},
			}
			if sc.Config.Auth {
				cfg.Authenticate = func(r *http.Request) (any, error) {
					if id, ok := f.Fixture.Auth.Tokens[r.Header.Get("Authorization")]; ok {
						return id, nil
					}
					return nil, errors.New("unauthorized")
				}
				cfg.WWWAuthenticate = func(*http.Request, error) string { return f.Fixture.Auth.WWWAuthenticate }
			}
			if sc.Config.Skills {
				cfg.ListSkills = func(context.Context) ([]MCPSkill, error) { return LoadMCPSkills("../examples/skills") }
			}
			var meta http.Handler = http.NotFoundHandler()
			if sc.Config.ServerAuth {
				fa := f.Fixture.ServerAuth
				auth := &MCPServerAuth{Issuer: fa.Issuer, Audience: fa.Audience, Scopes: fa.Scopes,
					Verify: func(_ context.Context, token, _ string) (MCPPrincipal, error) {
						p, ok := fa.Principals[token]
						if !ok {
							return MCPPrincipal{}, errors.New("bad token")
						}
						return MCPPrincipal{Subject: p.Subject, Scopes: p.Scopes}, nil
					}}
				cfg.Authenticate = auth.Authenticate
				meta = auth.Metadata()
			}
			srv := httptest.NewServer(newHandlerTK(t).MCPHandler(cfg))
			defer srv.Close()
			metaSrv := httptest.NewServer(meta)
			defer metaSrv.Close()

			sid := ""
			for i, st := range sc.Steps {
				rq := st.Request
				var body io.Reader
				if rq.RawBody != nil {
					body = strings.NewReader(*rq.RawBody)
				} else if len(rq.Body) > 0 {
					body = bytes.NewReader(rq.Body)
				}
				target := srv.URL
				if rq.Target != "" {
					target = metaSrv.URL + rq.Target
				}
				if rq.MCP2026 && len(rq.Body) > 0 {
					rq.Body = mhcStamp2026(t, rq.Body)
					body = bytes.NewReader(rq.Body)
				}
				req, _ := http.NewRequest(rq.Method, target, body)
				if rq.Method == "POST" {
					ct := rq.ContentType
					if ct == "" {
						ct = "application/json"
					}
					req.Header.Set("Content-Type", ct)
				}
				acc := rq.Accept
				if acc == "" {
					acc = "application/json, text/event-stream"
				}
				req.Header.Set("Accept", acc)
				if tok := firstNonEmpty(rq.Token, sc.Token); tok != "" {
					req.Header.Set("Authorization", tok)
				}
				if rq.SessionID != "" {
					req.Header.Set("Mcp-Session-Id", rq.SessionID)
				} else if rq.Session {
					req.Header.Set("Mcp-Session-Id", sid)
				}
				if rq.Version != "" {
					req.Header.Set("MCP-Protocol-Version", rq.Version)
				}
				if rq.MCP2026 {
					for k, v := range mhcHeaders2026(rq.Body) {
						req.Header.Set(k, v)
					}
				}
				for k, v := range rq.Headers {
					if v == "" {
						req.Header.Del(k)
					} else {
						req.Header.Set(k, v)
					}
				}
				resp, err := http.DefaultClient.Do(req)
				if err != nil {
					t.Fatalf("step %d: %v", i, err)
				}
				rb, _ := io.ReadAll(resp.Body)
				resp.Body.Close()
				if rec := os.Getenv("MHC_RECORD"); rec != "" {
					line, _ := json.Marshal(map[string]any{"scenario": sc.Name, "step": i, "status": resp.StatusCode,
						"www": resp.Header.Get("WWW-Authenticate"), "body": string(rb)})
					fh, _ := os.OpenFile(rec, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
					_, _ = fh.Write(append(line, '\n'))
					_ = fh.Close()
				}
				ex := st.Expect
				if ex.Status != 0 && resp.StatusCode != ex.Status {
					t.Fatalf("step %d: status %d want %d (%s)", i, resp.StatusCode, ex.Status, rb)
				}
				for k, v := range ex.Headers {
					if got := resp.Header.Get(k); got != v {
						t.Fatalf("step %d: header %s=%q want %q", i, k, got, v)
					}
				}
				for _, k := range ex.HeadersPresent {
					if resp.Header.Get(k) == "" {
						t.Fatalf("step %d: header %s missing", i, k)
					}
				}
				for _, k := range ex.HeadersAbsent {
					if resp.Header.Get(k) != "" {
						t.Fatalf("step %d: header %s present", i, k)
					}
				}
				if st.Capture == "sid" {
					sid = resp.Header.Get("Mcp-Session-Id")
				}
				if len(ex.JSON) > 0 || len(ex.JSONPath) > 0 {
					var got any
					if err := json.Unmarshal(rb, &got); err != nil {
						t.Fatalf("step %d: body not JSON: %s", i, rb)
					}
					if len(ex.JSON) > 0 {
						var want any
						_ = json.Unmarshal(ex.JSON, &want)
						if !reflect.DeepEqual(got, want) {
							t.Fatalf("step %d: body\n got %s\nwant %s", i, rb, ex.JSON)
						}
					}
					for p, want := range ex.JSONPath {
						if v := mhcPath(got, p); !reflect.DeepEqual(v, want) {
							gb, _ := json.Marshal(v)
							wb, _ := json.Marshal(want)
							t.Fatalf("step %d: %s = %s want %s", i, p, gb, wb)
						}
					}
				}
			}
			if sc.ExpectExecuted != nil && !reflect.DeepEqual(executed, *sc.ExpectExecuted) {
				t.Fatalf("executed %v want %v", executed, *sc.ExpectExecuted)
			}
			if sc.ExpectOnCall != nil && !reflect.DeepEqual(onCalls, sc.ExpectOnCall) {
				t.Fatalf("onCall %v want %v", onCalls, sc.ExpectOnCall)
			}
		})
	}
}

func firstNonEmpty(xs ...string) string {
	for _, x := range xs {
		if x != "" {
			return x
		}
	}
	return ""
}

func mhcPath(v any, path string) any {
	for _, seg := range strings.Split(path, ".") {
		switch x := v.(type) {
		case map[string]any:
			v = x[seg]
		case []any:
			n, err := strconv.Atoi(seg)
			if err != nil || n >= len(x) {
				return nil
			}
			v = x[n]
		default:
			return nil
		}
	}
	return v
}

// mhcIdentityName: a string identity verbatim, an MCPPrincipal's subject, else "".
func mhcIdentityName(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case MCPPrincipal:
		return x.Subject
	}
	return ""
}

// mhcStamp2026 adds params._meta["io.modelcontextprotocol/protocolVersion"] =
// "2026-07-28" unless the body already sets that key (README: `mcp2026`).
func mhcStamp2026(t *testing.T, body json.RawMessage) json.RawMessage {
	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatal(err)
	}
	p, _ := m["params"].(map[string]any)
	if p == nil {
		p = map[string]any{}
	}
	meta, _ := p["_meta"].(map[string]any)
	if meta == nil {
		meta = map[string]any{}
	}
	if _, ok := meta[mcpMetaVersion]; !ok {
		meta[mcpMetaVersion] = MCPProtocol2026
	}
	p["_meta"] = meta
	m["params"] = p
	b, _ := json.Marshal(m)
	return b
}

// mhcHeaders2026: MCP-Protocol-Version, Mcp-Method and (tools/call,
// prompts/get: name; resources/read: uri) Mcp-Name, from the body.
func mhcHeaders2026(body json.RawMessage) map[string]string {
	var m struct {
		Method string         `json:"method"`
		Params map[string]any `json:"params"`
	}
	_ = json.Unmarshal(body, &m)
	h := map[string]string{"MCP-Protocol-Version": MCPProtocol2026, "Mcp-Method": m.Method}
	switch m.Method {
	case "tools/call", "prompts/get":
		h["Mcp-Name"], _ = m.Params["name"].(string)
	case "resources/read":
		h["Mcp-Name"], _ = m.Params["uri"].(string)
	}
	return h
}
