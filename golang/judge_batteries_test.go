package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Shared battery fixtures: examples/judge/batteries/*.json (add-judge-batteries).

type batteryCase struct {
	Name    string         `json:"name"`
	Options map[string]any `json:"options"`
	Input   map[string]any `json:"input"`
	Calls   []batteryCall  `json:"calls"`
	Error   bool           `json:"error"`
	Want    map[string]any `json:"want"`
}

type batteryCall struct {
	State     map[string]any            `json:"state"`
	Questions map[string]map[string]any `json:"questions"`
	Response  json.RawMessage           `json:"response"`
}

func loadBattery(t *testing.T, name string) []batteryCase {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "examples", "judge", "batteries", name))
	if err != nil {
		t.Fatal(err)
	}
	var fx struct {
		Cases []batteryCase `json:"cases"`
	}
	if err := json.Unmarshal(b, &fx); err != nil {
		t.Fatal(err)
	}
	if len(fx.Cases) == 0 {
		t.Fatalf("%s: no cases", name)
	}
	return fx.Cases
}

// batteryClassifier: static over the recorded calls; a failing custom one for
// error cases; one that fails the test if called when calls is empty.
func batteryClassifier(t *testing.T, c batteryCase) *Classifier {
	t.Helper()
	if c.Error {
		cl, _ := CreateClassifier(ClassifierOptions{Style: StyleCustom, Evaluate: func(context.Context, any, map[string]Question) (Decision, error) {
			return Decision{}, errors.New("boom")
		}})
		return cl
	}
	if len(c.Calls) == 0 {
		cl, _ := CreateClassifier(ClassifierOptions{Style: StyleCustom, Evaluate: func(context.Context, any, map[string]Question) (Decision, error) {
			t.Errorf("%s: classifier must not be called", c.Name)
			return Decision{}, errors.New("unexpected call")
		}})
		return cl
	}
	var recs []RecordedDecision
	for _, k := range c.Calls {
		qm := map[string]Question{}
		for name, q := range k.Questions {
			qm[name] = wireQuestion(t, q)
		}
		recs = append(recs, Recorded(k.State, qm, []byte(k.Response)))
	}
	cl, err := StaticClassifier(recs...)
	if err != nil {
		t.Fatal(err)
	}
	return cl
}

func optBands(o map[string]any) Bands {
	if b, ok := o["bands"].(map[string]any); ok {
		return Bands{Low: b["low"].(float64), High: b["high"].(float64)}
	}
	return Bands{}
}

func optStr(o map[string]any, k string) string { s, _ := o[k].(string); return s }

func items(v any) []Item {
	var out []Item
	for _, x := range v.([]any) {
		m := x.(map[string]any)
		out = append(out, Item{m["name"].(string), m["description"].(string)})
	}
	return out
}

// assertVerdict compares want (fixture) against the verdict's JSON form.
func assertVerdict(t *testing.T, want map[string]any, verdict any) {
	t.Helper()
	b, _ := json.Marshal(verdict)
	got := map[string]any{}
	_ = json.Unmarshal(b, &got)
	for k, w := range want {
		if k == "error" {
			_, has := got["error"]
			if has != w.(bool) {
				t.Errorf("error present = %v, want %v (verdict %s)", has, w, b)
			}
			continue
		}
		g, ok := got[k]
		if !ok {
			t.Errorf("verdict lacks %q (verdict %s)", k, b)
			continue
		}
		// normalise: empty slices vs [] are both []any{}
		if !reflect.DeepEqual(g, w) {
			t.Errorf("%s = %#v, want %#v", k, g, w)
		}
	}
}

func TestBatteries_ToolGuard(t *testing.T) {
	for _, c := range loadBattery(t, "tool-guard.json") {
		t.Run(c.Name, func(t *testing.T) {
			o := ToolGuardOptions{OnError: OnError(optStr(c.Options, "onError")), Bands: optBands(c.Options), Role: optStr(c.Options, "role")}
			if v, ok := c.Options["askAt"].(float64); ok {
				o.AskAt = &v
			}
			if v, ok := c.Options["denyAt"].(float64); ok {
				o.DenyAt = &v
			}
			g, err := NewToolGuard(batteryClassifier(t, c), o)
			if err != nil {
				t.Fatal(err)
			}
			args, _ := c.Input["arguments"].(map[string]any)
			v := g.Check(context.Background(), GuardedCall{Name: c.Input["name"].(string), Arguments: args, Description: optStr(c.Input, "description")})
			assertVerdict(t, c.Want, v)
		})
	}
}

func TestBatteries_Relevance(t *testing.T) {
	for _, f := range []string{"tool-relevance.json", "skill-relevance.json"} {
		for _, c := range loadBattery(t, f) {
			t.Run(f+"/"+c.Name, func(t *testing.T) {
				o := RelevanceOptions{OnError: OnError(optStr(c.Options, "onError")), Bands: optBands(c.Options), Role: optStr(c.Options, "role")}
				cl := batteryClassifier(t, c)
				var v RelevanceVerdict
				if f == "tool-relevance.json" {
					r, err := NewToolRelevance(cl, o)
					if err != nil {
						t.Fatal(err)
					}
					v = r.Select(context.Background(), c.Input["prompt"].(string), items(c.Input["tools"]))
				} else {
					r, err := NewSkillRelevance(cl, o)
					if err != nil {
						t.Fatal(err)
					}
					v = r.Select(context.Background(), c.Input["prompt"].(string), items(c.Input["skills"]))
				}
				assertVerdict(t, c.Want, v)
			})
		}
	}
}

func TestBatteries_ToolResultFilter(t *testing.T) {
	for _, c := range loadBattery(t, "tool-result-filter.json") {
		t.Run(c.Name, func(t *testing.T) {
			f, err := NewToolResultFilter(batteryClassifier(t, c), RelevanceOptions{OnError: OnError(optStr(c.Options, "onError")), Bands: optBands(c.Options)})
			if err != nil {
				t.Fatal(err)
			}
			var chunks []string
			for _, x := range c.Input["chunks"].([]any) {
				chunks = append(chunks, x.(string))
			}
			assertVerdict(t, c.Want, f.Filter(context.Background(), c.Input["query"], chunks))
		})
	}
}

func TestBatteries_IsComplete(t *testing.T) {
	for _, c := range loadBattery(t, "is-complete.json") {
		t.Run(c.Name, func(t *testing.T) {
			ic, err := NewIsComplete(batteryClassifier(t, c), RelevanceOptions{OnError: OnError(optStr(c.Options, "onError")), Bands: optBands(c.Options)})
			if err != nil {
				t.Fatal(err)
			}
			assertVerdict(t, c.Want, ic.Check(context.Background(), c.Input["task"].(string), c.Input["answer"].(string)))
		})
	}
}

func agentNodes(v any) []AgentNode {
	var out []AgentNode
	for _, x := range v.([]any) {
		m := x.(map[string]any)
		n := AgentNode{Name: m["name"].(string), Description: m["description"].(string)}
		if sub, ok := m["agents"]; ok {
			n.Agents = agentNodes(sub)
		}
		out = append(out, n)
	}
	return out
}

func TestBatteries_AgentRouter(t *testing.T) {
	for _, c := range loadBattery(t, "agent-router.json") {
		t.Run(c.Name, func(t *testing.T) {
			r := NewAgentRouter(batteryClassifier(t, c), RouterOptions{Bands: optBands(c.Options)})
			v := r.Pick(context.Background(), c.Input["task"].(string), agentNodes(c.Input["agents"]), c.Input["fallback"].(string))
			assertVerdict(t, c.Want, v)
		})
	}
}

func TestBatteries_ContentGuard(t *testing.T) {
	for _, c := range loadBattery(t, "content-guard.json") {
		t.Run(c.Name, func(t *testing.T) {
			o := ContentGuardOptions{OnError: OnError(optStr(c.Options, "onError")), Bands: optBands(c.Options)}
			if ds, ok := c.Options["dimensions"].([]any); ok {
				for _, d := range ds {
					m := d.(map[string]any)
					o.Dimensions = append(o.Dimensions, Dimension{m["name"].(string), m["instructions"].(string)})
				}
			}
			g, err := NewContentGuard(batteryClassifier(t, c), o)
			if err != nil {
				t.Fatal(err)
			}
			assertVerdict(t, c.Want, g.Check(context.Background(), c.Input["text"].(string)))
		})
	}
}

func TestBatteries_ModelRouter(t *testing.T) {
	for _, c := range loadBattery(t, "model-router.json") {
		t.Run(c.Name, func(t *testing.T) {
			var ms []ModelOption
			for _, x := range c.Input["models"].([]any) {
				m := x.(map[string]any)
				ms = append(ms, ModelOption{m["id"].(string), m["description"].(string)})
			}
			r := NewModelRouter(batteryClassifier(t, c), ms, RouterOptions{Bands: optBands(c.Options)})
			assertVerdict(t, c.Want, r.Pick(context.Background(), c.Input["prompt"].(string), c.Input["fallback"].(string)))
		})
	}
}

func TestBatteries_UserText(t *testing.T) {
	b, err := os.ReadFile(filepath.Join("..", "examples", "judge", "batteries", "user-text-cases.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fx struct {
		Cases []struct {
			Name     string `json:"name"`
			Messages []any  `json:"messages"`
			Want     string `json:"want"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(b, &fx); err != nil || len(fx.Cases) == 0 {
		t.Fatal("bad fixture", err)
	}
	for _, c := range fx.Cases {
		if got := LatestUserText(c.Messages); got != c.Want {
			t.Errorf("%s: got %q want %q", c.Name, got, c.Want)
		}
	}
}

func TestBatteries_OnErrorRequired(t *testing.T) {
	cl, _ := StaticClassifier()
	if _, err := NewToolGuard(cl, ToolGuardOptions{}); err == nil || !strings.Contains(err.Error(), "onError") {
		t.Fatalf("want onError error, got %v", err)
	}
	for _, f := range []func() error{
		func() error { _, e := NewToolRelevance(cl, RelevanceOptions{}); return e },
		func() error { _, e := NewSkillRelevance(cl, RelevanceOptions{}); return e },
		func() error { _, e := NewToolResultFilter(cl, RelevanceOptions{OnError: "maybe"}); return e },
		func() error { _, e := NewIsComplete(cl, RelevanceOptions{}); return e },
		func() error { _, e := NewContentGuard(cl, ContentGuardOptions{}); return e },
	} {
		if err := f(); err == nil || !strings.Contains(err.Error(), "onError") {
			t.Fatalf("want onError error, got %v", err)
		}
	}
}

// ---- hooks ----

// fixed classifier: every evaluate answers with the given answers map.
func fixedClassifier(t *testing.T, answers string) *Classifier {
	t.Helper()
	cl, err := CreateClassifier(ClassifierOptions{Style: StyleCustom, Evaluate: func(context.Context, any, map[string]Question) (Decision, error) {
		var d Decision
		err := json.Unmarshal([]byte(`{"model":"m","answers":`+answers+`}`), &d)
		return d, err
	}})
	if err != nil {
		t.Fatal(err)
	}
	return cl
}

const riskAns = `{"risk":{"type":"score","score":%s,"confidence":0.9,"probabilities":{"0":0.25,"1":0.25,"2":0.25,"3":0.25},"legend":{"0":"a","1":"b","2":"c","3":"d"}}}`

func riskClassifier(t *testing.T, score string) *Classifier {
	return fixedClassifier(t, strings.Replace(riskAns, "%s", score, 1))
}

func TestBatteries_ToolGuardHook(t *testing.T) {
	ran := false
	tk := pbToolkit(t)
	next := func(ctx context.Context, ev BeforeToolEvent) (*ToolOverride, error) { ran = true; return nil, nil }

	// ask: halts pending with the guard's Request; the tool never runs, next not called.
	g, _ := NewToolGuard(riskClassifier(t, "1.8"), ToolGuardOptions{OnError: OnErrorClosed})
	r, err := pbClient(t, &Hooks{BeforeTool: g.AsHook(next)}, nil).Run(context.Background(), "ship it", tk)
	if err != nil {
		t.Fatal(err)
	}
	if r.Status != "pending" || r.Pending == nil || r.Pending.ID != "toolguard:c1" || r.Pending.Kind != "approval" {
		t.Fatalf("ask: got status %q pending %+v", r.Status, r.Pending)
	}
	if r.Pending.Prompt != "Approve the call to deploy? (medium risk)" || r.Pending.Data["tool"] != "deploy" || r.Pending.Data["reason"] != "medium risk" {
		t.Fatalf("ask request: %+v", r.Pending)
	}
	if ran {
		t.Fatal("next must not run on ask")
	}

	// deny: short-circuits, the tool's output never appears.
	g, _ = NewToolGuard(riskClassifier(t, "2.9"), ToolGuardOptions{OnError: OnErrorClosed})
	r, err = pbClient(t, &Hooks{BeforeTool: g.AsHook(next)}, nil).Run(context.Background(), "ship it", tk)
	if err != nil {
		t.Fatal(err)
	}
	if ran || len(r.ToolCalls) != 1 || r.ToolCalls[0].Output != "denied by tool guard: high risk" {
		t.Fatalf("deny: ran=%v calls=%+v", ran, r.ToolCalls)
	}

	// allow: next runs and the tool runs.
	g, _ = NewToolGuard(riskClassifier(t, "0.1"), ToolGuardOptions{OnError: OnErrorClosed})
	r, err = pbClient(t, &Hooks{BeforeTool: g.AsHook(next)}, nil).Run(context.Background(), "ship it", tk)
	if err != nil {
		t.Fatal(err)
	}
	if !ran || r.ToolCalls[0].Output != "DEPLOYED" {
		t.Fatalf("allow: ran=%v calls=%+v", ran, r.ToolCalls)
	}

	// approved through waitFor: the tool runs once, no re-ask.
	g, _ = NewToolGuard(riskClassifier(t, "1.8"), ToolGuardOptions{OnError: OnErrorClosed})
	r, err = pbClient(t, &Hooks{BeforeTool: g.AsHook(nil)}, func(q Request) (Answer, error) { return Answer{ID: q.ID, Ok: true}, nil }).Run(context.Background(), "ship it", tk)
	if err != nil {
		t.Fatal(err)
	}
	if r.Status == "pending" || r.ToolCalls[0].Output != "DEPLOYED" {
		t.Fatalf("approved: status %q calls %+v", r.Status, r.ToolCalls)
	}
}

// bodyRecorder is a scripted openai endpoint that records every request body.
type bodyRecorder struct {
	bodies []map[string]any
	turn   int
}

func (s *bodyRecorder) RoundTrip(r *http.Request) (*http.Response, error) {
	b, _ := io.ReadAll(r.Body)
	m := map[string]any{}
	_ = json.Unmarshal(b, &m)
	s.bodies = append(s.bodies, m)
	s.turn++
	body := `{"choices":[{"message":{"role":"assistant","content":"done"}}]}`
	if s.turn == 1 {
		body = `{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
		  {"id":"c1","type":"function","function":{"name":"deploy","arguments":"{}"}}]}}]}`
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: http.Header{"Content-Type": []string{"application/json"}}}, nil
}

func recClient(rec *bodyRecorder, hooks *Hooks) *Client {
	return CreateClient(ClientOptions{BaseURL: "http://x/v1", Style: "openai", Model: "configured", APIKey: "k",
		HTTPClient: &http.Client{Transport: rec}, Hooks: hooks, MaxTurns: 4})
}

func TestBeforeLLM_ModelOverrideIsPerTurn(t *testing.T) {
	var seen []string
	rec := &bodyRecorder{}
	hooks := &Hooks{
		BeforeLLM: func(ctx context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
			if ev.Turn == 0 {
				return &LLMOverride{Model: "small-fast"}, nil
			}
			return nil, nil
		},
		AfterLLM: func(ctx context.Context, ev AfterLLMEvent) error { seen = append(seen, ev.Model); return nil },
	}
	if _, err := recClient(rec, hooks).Run(context.Background(), "go", pbToolkit(t)); err != nil {
		t.Fatal(err)
	}
	if len(rec.bodies) != 2 || rec.bodies[0]["model"] != "small-fast" || rec.bodies[1]["model"] != "configured" {
		t.Fatalf("bodies: %v", rec.bodies)
	}
	if !reflect.DeepEqual(seen, []string{"small-fast", "configured"}) {
		t.Fatalf("afterLLM models: %v", seen)
	}
}

func TestBatteries_ModelRouterHook(t *testing.T) {
	models := []ModelOption{{"small-fast", "cheap"}, {"large-reasoning", "dear"}}
	sure := `{"model":{"type":"choice","choice":"small-fast","confidence":0.91,"probabilities":{"small-fast":0.91,"large-reasoning":0.09}}}`
	unsure := `{"model":{"type":"choice","choice":"small-fast","confidence":0.6,"probabilities":{"small-fast":0.6,"large-reasoning":0.4}}}`
	for _, tc := range []struct{ ans, want string }{{sure, "small-fast"}, {unsure, "configured"}} {
		rec := &bodyRecorder{}
		r := NewModelRouter(fixedClassifier(t, tc.ans), models, RouterOptions{})
		if _, err := recClient(rec, &Hooks{BeforeLLM: r.AsHook(nil)}).Run(context.Background(), "capital of France?", pbToolkit(t)); err != nil {
			t.Fatal(err)
		}
		for _, b := range rec.bodies {
			if b["model"] != tc.want {
				t.Fatalf("want model %q, got %v", tc.want, b["model"])
			}
		}
	}
	// unsure, or sure of the configured model itself: no override at all.
	ev := BeforeLLMEvent{Model: "small-fast", Messages: []any{map[string]any{"role": "user", "content": "x"}}}
	for _, a := range []string{unsure, sure} {
		ov, err := NewModelRouter(fixedClassifier(t, a), models, RouterOptions{}).AsHook(nil)(context.Background(), ev)
		if err != nil || ov != nil {
			t.Fatalf("want no override, got %+v %v", ov, err)
		}
	}
	// no router attached: verbatim.
	rec := &bodyRecorder{}
	if _, err := recClient(rec, nil).Run(context.Background(), "x", pbToolkit(t)); err != nil {
		t.Fatal(err)
	}
	if rec.bodies[0]["model"] != "configured" {
		t.Fatal("no router must transmit the configured model")
	}
	// next's model wins over the router's.
	r := NewModelRouter(fixedClassifier(t, sure), models, RouterOptions{})
	rec = &bodyRecorder{}
	var nextSaw string
	next := func(ctx context.Context, ev BeforeLLMEvent) (*LLMOverride, error) {
		nextSaw = ev.Model
		return &LLMOverride{Model: "pinned"}, nil
	}
	if _, err := recClient(rec, &Hooks{BeforeLLM: r.AsHook(next)}).Run(context.Background(), "x", pbToolkit(t)); err != nil {
		t.Fatal(err)
	}
	if nextSaw != "small-fast" || rec.bodies[0]["model"] != "pinned" {
		t.Fatalf("merge: next saw %q, body %v", nextSaw, rec.bodies[0]["model"])
	}
}

func TestBatteries_ToolRelevanceHook(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, ExtraTools: []Tool{
		{Name: "deploy", Description: "deploy", Source: SourceCustom, InputSchema: JSONSchema{"type": "object"},
			Execute: func(map[string]any, *ToolContext) (ToolResult, error) { return ToolResult{Output: "D"}, nil }},
		{Name: "send_email", Description: "email", Source: SourceCustom, InputSchema: JSONSchema{"type": "object"},
			Execute: func(map[string]any, *ToolContext) (ToolResult, error) { return ToolResult{Output: "E"}, nil }},
	}})
	if err != nil {
		t.Fatal(err)
	}
	rel, _ := NewToolRelevance(fixedClassifier(t, `{"deploy":{"type":"noul","noul":0.9},"send_email":{"type":"noul","noul":0.05}}`), RelevanceOptions{OnError: OnErrorOpen})
	rec := &bodyRecorder{}
	if _, err := recClient(rec, &Hooks{BeforeLLM: rel.AsHook(nil)}).Run(context.Background(), "ship it", tk); err != nil {
		t.Fatal(err)
	}
	tools := rec.bodies[0]["tools"].([]any)
	if len(tools) != 1 || providerTool(tools[0]).Name != "deploy" {
		t.Fatalf("tools: %v", tools)
	}
}

func TestBatteries_ContentGuardHook(t *testing.T) {
	g, _ := NewContentGuard(fixedClassifier(t, `{"harmful":{"type":"noul","noul":0.96},"prompt_injection":{"type":"noul","noul":0.9}}`), ContentGuardOptions{OnError: OnErrorClosed})
	rec := &bodyRecorder{}
	_, err := recClient(rec, &Hooks{BeforeLLM: g.AsHook(nil)}).Run(context.Background(), "idiot", pbToolkit(t))
	if err == nil || !strings.Contains(err.Error(), "content guard blocked: harmful, prompt_injection") || len(rec.bodies) != 0 {
		t.Fatalf("want block before any request, got %v (%d requests)", err, len(rec.bodies))
	}
	called := false
	g, _ = NewContentGuard(fixedClassifier(t, `{"harmful":{"type":"noul","noul":0.5},"prompt_injection":{"type":"noul","noul":0.1}}`), ContentGuardOptions{OnError: OnErrorClosed})
	h := g.AsHook(func(context.Context, BeforeLLMEvent) (*LLMOverride, error) { called = true; return nil, nil })
	if _, err := h(context.Background(), BeforeLLMEvent{Messages: []any{map[string]any{"role": "user", "content": "meh"}}}); err != nil || !called {
		t.Fatalf("review must delegate: err %v called %v", err, called)
	}
	gErr, _ := NewContentGuard(fixedErr(t), ContentGuardOptions{OnError: OnErrorClosed})
	if _, err := gErr.AsHook(nil)(context.Background(), BeforeLLMEvent{Messages: []any{map[string]any{"role": "user", "content": "x"}}}); err == nil || err.Error() != "content guard blocked: classifier error" {
		t.Fatalf("closed error: %v", err)
	}
}

func fixedErr(t *testing.T) *Classifier {
	cl, _ := CreateClassifier(ClassifierOptions{Style: StyleCustom, Evaluate: func(context.Context, any, map[string]Question) (Decision, error) {
		return Decision{}, errors.New("boom")
	}})
	return cl
}

func TestBatteries_ToolResultFilterHook(t *testing.T) {
	f, _ := NewToolResultFilter(fixedClassifier(t, `{"0":{"type":"noul","noul":0.9},"1":{"type":"noul","noul":0.05},"2":{"type":"noul","noul":0.5}}`), RelevanceOptions{OnError: OnErrorOpen})
	var nextSaw string
	h := f.AsHook(func(ctx context.Context, ev AfterToolEvent) (*ToolOverride, error) {
		nextSaw = ev.Result.Output
		return nil, nil
	})
	ov, err := h(context.Background(), AfterToolEvent{Name: "t", Result: ToolResult{Output: "a\n\nb\n\nc"}})
	if err != nil || ov == nil || ov.Result.Output != "a\n\nc" || nextSaw != "a\n\nc" {
		t.Fatalf("filtered: %+v %v next %q", ov, err, nextSaw)
	}
	// single chunk, error result, parts: untouched, no call.
	fe, _ := NewToolResultFilter(fixedErr(t), RelevanceOptions{OnError: OnErrorClosed})
	for _, r := range []ToolResult{{Output: "one"}, {Output: "a\n\nb", IsError: true}, {Output: "a\n\nb", Parts: []ContentPart{{Type: "image"}}}} {
		ov, err := fe.AsHook(nil)(context.Background(), AfterToolEvent{Name: "t", Result: r})
		if err != nil || ov != nil {
			t.Fatalf("must pass through %+v: %+v %v", r, ov, err)
		}
	}
}

// A RunParts prompt sits in the transcript as native []ContentPart, not []any.
func TestLatestUserTextNativeParts(t *testing.T) {
	msgs := []any{userMessage([]ContentPart{Text("a"), {Type: PartImage, URL: "https://x/y.png"}, Text("b")})}
	if got := LatestUserText(msgs); got != "a\nb" {
		t.Fatalf("got %q", got)
	}
	mixed := []any{map[string]any{"role": "user", "content": []any{Text("c")}}}
	if got := LatestUserText(mixed); got != "c" {
		t.Fatalf("got %q", got)
	}
}
