package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

// SPEC §7G conformance: replays ../conformance/media/{cases,schemas,verbs}.json
// (the shared fixtures, never copies) against an in-process fake server.

type mediaRecorded struct {
	Method  string
	Path    string
	Body    any
	Headers http.Header
}

type mediaScriptEntry struct {
	Status  int             `json:"status"`
	Body    json.RawMessage `json:"body"`
	RawBody *string         `json:"rawBody"`
}

type mediaCase struct {
	Name           string             `json:"name"`
	Env            map[string]string  `json:"env"`
	Style          json.RawMessage    `json:"style"`
	Verb           string             `json:"verb"`
	Args           map[string]any     `json:"args"`
	Script         []mediaScriptEntry `json:"script"`
	ExpectHeaders  map[string]string  `json:"expectHeaders"`
	ExpectRequests []map[string]any   `json:"expectRequests"`
	ExpectProgress []string           `json:"expectProgress"`
	Result         any                `json:"result"`
	Error          *string            `json:"error"`
	Tool           map[string]any     `json:"tool"`
	raw            map[string]any
}

func readConformance(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("../conformance/media/" + name)
	if err != nil {
		t.Fatalf("read conformance fixture: %v", err)
	}
	return b
}

// fakeMediaServer serves script in order (the last entry repeats) and records requests.
type fakeMediaServer struct {
	*httptest.Server
	mu     sync.Mutex
	script []mediaScriptEntry
	n      int
	reqs   []mediaRecorded
}

func newFakeMediaServer(script []mediaScriptEntry) *fakeMediaServer {
	f := &fakeMediaServer{script: script}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var body any
		if len(raw) > 0 {
			_ = json.Unmarshal(raw, &body)
		}
		f.mu.Lock()
		f.reqs = append(f.reqs, mediaRecorded{Method: r.Method, Path: r.URL.EscapedPath(), Body: body, Headers: r.Header.Clone()})
		e := f.script[min(f.n, len(f.script)-1)]
		f.n++
		f.mu.Unlock()
		w.WriteHeader(e.Status)
		if e.RawBody != nil {
			_, _ = io.WriteString(w, *e.RawBody)
		} else {
			_, _ = w.Write(e.Body)
		}
	}))
	return f
}

func (f *fakeMediaServer) reset() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.n, f.reqs = 0, nil
}

func (f *fakeMediaServer) requests() []mediaRecorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]mediaRecorded(nil), f.reqs...)
}

// jsonNorm round-trips v through JSON so typed structs compare to fixture maps.
func jsonNorm(t *testing.T, v any) any {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	var out any
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func mediaJSON(v any) string { b, _ := json.Marshal(v); return string(b) }

func loadMediaCases(t *testing.T) []mediaCase {
	t.Helper()
	raw := readConformance(t, "cases.json")
	var doc struct {
		Cases []mediaCase `json:"cases"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	var rawDoc struct {
		Cases []map[string]any `json:"cases"`
	}
	_ = json.Unmarshal(raw, &rawDoc)
	for i := range doc.Cases {
		doc.Cases[i].raw = rawDoc.Cases[i]
	}
	if len(doc.Cases) == 0 {
		t.Fatal("no cases")
	}
	return doc.Cases
}

func (c mediaCase) style(t *testing.T, server string) MediaStyle {
	t.Helper()
	var s MediaStyle
	if err := json.Unmarshal([]byte(strings.ReplaceAll(string(c.Style), "{server}", server)), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func (c mediaCase) assertRequests(t *testing.T, face string, got []mediaRecorded) {
	t.Helper()
	if _, has := c.raw["expectRequests"]; has {
		if len(got) != len(c.ExpectRequests) {
			t.Fatalf("%s: %d requests, want %d: %+v", face, len(got), len(c.ExpectRequests), got)
		}
		for i, want := range c.ExpectRequests {
			g := got[i]
			if g.Method != want["method"] || g.Path != want["path"] {
				t.Errorf("%s: request %d = %s %s, want %v %v", face, i, g.Method, g.Path, want["method"], want["path"])
			}
			if wb, ok := want["body"]; ok {
				if !reflect.DeepEqual(g.Body, wb) {
					t.Errorf("%s: request %d body = %s, want %s", face, i, mediaJSON(g.Body), mediaJSON(wb))
				}
				if ct := g.Headers.Get("Content-Type"); ct != "application/json" {
					t.Errorf("%s: request %d Content-Type = %q", face, i, ct)
				}
			} else if g.Body != nil {
				t.Errorf("%s: request %d has unexpected body %s", face, i, mediaJSON(g.Body))
			}
		}
	}
	for name, want := range c.ExpectHeaders {
		for i, g := range got {
			if v := g.Headers.Get(name); v != want {
				t.Errorf("%s: request %d header %s = %q, want %q", face, i, name, v, want)
			}
		}
	}
}

func TestMediaConformanceCases(t *testing.T) {
	for _, c := range loadMediaCases(t) {
		t.Run(c.Name, func(t *testing.T) {
			for k, v := range c.Env {
				t.Setenv(k, v)
			}
			srv := newFakeMediaServer(c.Script)
			defer srv.Close()
			style := c.style(t, srv.URL)

			// Function face.
			progress := []string{}
			m := CreateMedia([]MediaStyle{style}, MediaOptions{
				HTTPClient: srv.Client(),
				OnProgress: func(p MediaProgress) {
					if p.Verb != c.Verb {
						t.Errorf("progress verb = %q", p.Verb)
					}
					progress = append(progress, p.Status)
				},
			})
			res, err := m.Generate(context.Background(), c.Verb, c.Args, nil)
			if c.Error != nil {
				if err == nil || err.Error() != *c.Error {
					t.Fatalf("error = %v, want %q", err, *c.Error)
				}
			} else {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if got := jsonNorm(t, res); !reflect.DeepEqual(got, c.Result) {
					t.Fatalf("result = %s\nwant     %s", mediaJSON(got), mediaJSON(c.Result))
				}
			}
			if _, has := c.raw["expectProgress"]; has && !reflect.DeepEqual(progress, c.ExpectProgress) {
				t.Errorf("progress = %v, want %v", progress, c.ExpectProgress)
			}
			c.assertRequests(t, "function", srv.requests())

			// Tool face.
			if c.Tool == nil {
				return
			}
			srv.reset()
			var tool *Tool
			for _, tl := range m.Tools() {
				if tl.Name == c.Tool["name"] {
					tool = &tl
				}
			}
			if tool == nil {
				t.Fatalf("no tool %v", c.Tool["name"])
			}
			if tool.Source != SourceMedia {
				t.Errorf("source = %q", tool.Source)
			}
			tr, terr := tool.Execute(c.Args, &ToolContext{Ctx: context.Background()})
			if terr != nil {
				t.Fatalf("tool Execute returned error: %v", terr)
			}
			got := map[string]any{"name": tool.Name, "isError": tr.IsError, "output": tr.Output}
			if tr.Parts != nil {
				got["parts"] = tr.Parts
			}
			if tr.Metadata != nil {
				got["metadata"] = tr.Metadata
			}
			if g := jsonNorm(t, got); !reflect.DeepEqual(g, jsonNorm(t, c.Tool)) {
				t.Fatalf("tool = %s\nwant   %s", mediaJSON(g), mediaJSON(c.Tool))
			}
			c.assertRequests(t, "tool", srv.requests())
		})
	}
}

func TestMediaConformanceSchemas(t *testing.T) {
	var doc struct {
		Style MediaStyle       `json:"style"`
		Tools []map[string]any `json:"tools"`
	}
	if err := json.Unmarshal(readConformance(t, "schemas.json"), &doc); err != nil {
		t.Fatal(err)
	}
	tools := CreateMedia([]MediaStyle{doc.Style}, MediaOptions{}).Tools()
	got := jsonNorm(t, tools)
	if want := jsonNorm(t, doc.Tools); !reflect.DeepEqual(got, want) {
		t.Fatalf("tools = %s\nwant    %s", mediaJSON(got), mediaJSON(want))
	}
}

func TestMediaVerbsTableMatchesFixture(t *testing.T) {
	var doc struct {
		Verbs []struct {
			Verb        string `json:"verb"`
			Tool        string `json:"tool"`
			Description string `json:"description"`
			Args        []struct {
				Name        string `json:"name"`
				Type        string `json:"type"`
				Items       string `json:"items"`
				Description string `json:"description"`
				Required    bool   `json:"required"`
			} `json:"args"`
		} `json:"verbs"`
	}
	if err := json.Unmarshal(readConformance(t, "verbs.json"), &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Verbs) != len(mediaVerbs) {
		t.Fatalf("verbs = %d, want %d", len(mediaVerbs), len(doc.Verbs))
	}
	for i, v := range doc.Verbs {
		g := mediaVerbs[i]
		if g.verb != v.Verb || g.tool != v.Tool || g.desc != v.Description || len(g.args) != len(v.Args) {
			t.Fatalf("verb %d = %+v, want %+v", i, g, v)
		}
		for j, a := range v.Args {
			ga := g.args[j]
			if ga.name != a.Name || ga.typ != a.Type || ga.items != a.Items || ga.desc != a.Description || ga.required != a.Required {
				t.Fatalf("%s arg %d = %+v, want %+v", v.Verb, j, ga, a)
			}
		}
	}
}

func asyncTestStyle(base string, intervalMs int) MediaStyle {
	return MediaStyle{BaseURL: base, Verbs: map[string]MediaVerb{"image": {
		Mode: "async", Kind: "image", Start: MediaStart{Path: "/s"},
		Args: map[string]string{"prompt": "prompt"}, IDPath: "id",
		Poll: &MediaPoll{Path: "/p/{id}", StatusPath: "st", Done: []string{"completed"}, Failed: []string{"failed"},
			IntervalMs: intervalMs, TimeoutMs: 600000},
		ResultPath: "u"}}}
}

func TestMediaCancellationStopsPolling(t *testing.T) {
	srv := newFakeMediaServer([]mediaScriptEntry{
		{Status: 200, Body: json.RawMessage(`{"id":"j9"}`)},
		{Status: 200, Body: json.RawMessage(`{"st":"processing"}`)},
	})
	defer srv.Close()
	ctx, cancel := context.WithCancel(context.Background())
	m := CreateMedia([]MediaStyle{asyncTestStyle(srv.URL, 5)}, MediaOptions{
		HTTPClient: srv.Client(),
		OnProgress: func(MediaProgress) { cancel() },
	})
	done := make(chan error, 1)
	go func() {
		_, err := m.Image(ctx, ImageInput{Prompt: "x"})
		done <- err
	}()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("err = %v, want context.Canceled", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("poll loop did not stop on cancellation")
	}
	if n := len(srv.requests()); n != 2 {
		t.Fatalf("requests after cancel = %d, want 2 (start + one poll)", n)
	}
	// The tool face turns cancellation into an error result, never a Go error.
	tr, err := m.Tools()[0].Execute(map[string]any{"prompt": "x"}, &ToolContext{Ctx: ctx})
	if err != nil || !tr.IsError || !strings.Contains(tr.Output, context.Canceled.Error()) {
		t.Fatalf("tool = %+v, %v", tr, err)
	}
}

func TestMediaFirstStyleWinsAndProviderOptions(t *testing.T) {
	srv := newFakeMediaServer([]mediaScriptEntry{{Status: 200, Body: json.RawMessage(`{"u":"https://x/a.gif"}`)}})
	defer srv.Close()
	first := MediaStyle{BaseURL: srv.URL + "/one", Verbs: map[string]MediaVerb{"image": {
		Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Defaults: map[string]any{"q": "low", "keep": 1},
		Args: map[string]string{"prompt": "p", "seed": "opts.seed"}, ResultPath: "u"}}}
	second := MediaStyle{BaseURL: srv.URL + "/two", Verbs: map[string]MediaVerb{
		"image":  {Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Args: map[string]string{"prompt": "p"}, ResultPath: "u"},
		"speech": {Mode: "sync", Kind: "audio", Start: MediaStart{Path: "/s"}, Args: map[string]string{"text": "t"}, ResultPath: "u"},
	}}
	m := CreateMedia([]MediaStyle{first, second}, MediaOptions{HTTPClient: srv.Client()})
	r, err := m.Image(context.Background(), ImageInput{Prompt: "cat", Seed: 7, ProviderOptions: map[string]any{"q": "hd"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Files) != 1 || r.Files[0].MimeType != "image/gif" {
		t.Fatalf("result = %+v", r)
	}
	req := srv.requests()[0]
	if req.Path != "/one/i" {
		t.Fatalf("path = %s, want first style", req.Path)
	}
	want := map[string]any{"q": "hd", "keep": float64(1), "p": "cat", "opts": map[string]any{"seed": float64(7)}}
	if !reflect.DeepEqual(req.Body, want) {
		t.Fatalf("body = %s", mediaJSON(req.Body))
	}
	if first.Verbs["image"].Defaults["q"] != "low" {
		t.Fatal("defaults were mutated")
	}
	names := []string{}
	for _, tl := range m.Tools() {
		names = append(names, tl.Name)
	}
	if !reflect.DeepEqual(names, []string{"generate_image", "generate_speech"}) {
		t.Fatalf("tools = %v", names)
	}
}

func TestMediaDotSetArrays(t *testing.T) {
	body := map[string]any{}
	dotSet(body, "a.2.b", "x")
	dotSet(body, "a.0", 1)
	if got := mediaJSON(body); got != `{"a":[1,null,{"b":"x"}]}` {
		t.Fatalf("body = %s", got)
	}
}

func TestLoadMediaStyleExample(t *testing.T) {
	s, err := LoadMediaStyle("../examples/media-styles/a2e.json")
	if err != nil {
		t.Fatal(err)
	}
	if len(s.Verbs) == 0 || s.BaseURL == "" {
		t.Fatalf("style = %+v", s)
	}
}

// RunResult.Files / toolCalls[].Parts (§8): every non-text part a run's tools
// produced, in call order, with From = tool name and CallID = the model's id.
func TestRunResultFilesFromMediaTools(t *testing.T) {
	srv := newFakeMediaServer([]mediaScriptEntry{{Status: 200, Body: json.RawMessage(`{"u":["https://x/a.png","https://x/b.webp"]}`)}})
	defer srv.Close()
	media := CreateMedia([]MediaStyle{{BaseURL: srv.URL, Verbs: map[string]MediaVerb{"image": {
		Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Args: map[string]string{"prompt": "p"}, ResultPath: "u"}}}},
		MediaOptions{HTTPClient: srv.Client()})
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, Media: media})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	tk.Register(addTool(t))
	if _, ok := tk.Get("generate_image"); !ok {
		t.Fatal("Options.Media did not register generate_image")
	}

	newClient := func() *Client {
		n := 0
		return CreateInProcessClient(InProcessOptions{Model: "m",
			Generate: func(req InProcessRequest) (InProcessResponse, error) {
				n++
				if n == 1 {
					return InProcessResponse{ToolCalls: []InProcessToolCall{
						{ID: "call_add", Name: "add", Arguments: map[string]any{"a": 1, "b": 2}},
						{ID: "call_img", Name: "generate_image", Arguments: map[string]any{"prompt": "cat"}}}}, nil
				}
				return InProcessResponse{Content: "done"}, nil
			}})
	}
	wantFiles := []RunFile{
		{Type: "image", MimeType: "image/png", URL: "https://x/a.png", From: "generate_image", CallID: "call_img"},
		{Type: "image", MimeType: "image/webp", URL: "https://x/b.webp", From: "generate_image", CallID: "call_img"},
	}
	check := func(t *testing.T, r RunResult) {
		t.Helper()
		if !reflect.DeepEqual(r.Files, wantFiles) {
			t.Fatalf("files = %+v", r.Files)
		}
		if r.ToolCalls[0].Parts != nil || len(r.ToolCalls[1].Parts) != 2 {
			t.Fatalf("toolCalls parts = %+v", r.ToolCalls)
		}
		if r.Text != "done" {
			t.Fatalf("text = %q", r.Text)
		}
		j := jsonNorm(t, r).(map[string]any)
		calls := j["toolCalls"].([]any)
		if _, has := calls[0].(map[string]any)["parts"]; has {
			t.Fatal("parts key present on a call without parts")
		}
	}
	r, err := newClient().Run(context.Background(), "draw", tk)
	if err != nil {
		t.Fatal(err)
	}
	check(t, r)

	turn := 0
	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		turn++
		w.Header().Set("content-type", "text/event-stream")
		if turn == 1 {
			_, _ = io.WriteString(w, "data: "+mediaJSON(map[string]any{"choices": []any{map[string]any{
				"delta": map[string]any{"tool_calls": []any{
					map[string]any{"index": 0, "id": "call_add", "function": map[string]any{"name": "add", "arguments": `{"a":1,"b":2}`}},
					map[string]any{"index": 1, "id": "call_img", "function": map[string]any{"name": "generate_image", "arguments": `{"prompt":"cat"}`}},
				}}}}})+"\n\n")
		} else {
			_, _ = io.WriteString(w, "data: "+mediaJSON(map[string]any{"choices": []any{map[string]any{
				"delta": map[string]any{"content": "done"}}}})+"\n\n")
		}
		_, _ = io.WriteString(w, "data: [DONE]\n\n")
	}))
	defer llm.Close()
	sc := CreateClient(ClientOptions{BaseURL: llm.URL, Style: StyleOpenAI, Model: "m", APIKey: "k"})
	ch, err := sc.Stream(context.Background(), "draw", tk)
	if err != nil {
		t.Fatal(err)
	}
	var final *RunResult
	for ev := range ch {
		if ev.Type == "error" {
			t.Fatal(ev.Err)
		}
		if ev.Type == "done" {
			final = ev.Result
		}
	}
	if final == nil {
		t.Fatal("no done event")
	}
	check(t, *final)
}

func TestRunResultFilesEmptyWithoutMedia(t *testing.T) {
	tk := bareToolkit(t)
	defer tk.Close()
	c := CreateInProcessClient(InProcessOptions{Model: "m",
		Generate: func(InProcessRequest) (InProcessResponse, error) { return InProcessResponse{Content: "hi"}, nil }})
	r, err := c.Run(context.Background(), "hi", tk)
	if err != nil {
		t.Fatal(err)
	}
	if r.Files == nil || len(r.Files) != 0 {
		t.Fatalf("files = %#v, want non-nil empty", r.Files)
	}
	if !strings.Contains(mediaJSON(r), `"files":[]`) {
		t.Fatal("files not serialised as []")
	}
}

// Media tools are concurrency-safe (SPEC §7G, §8 Tool scheduling): each call is an independent remote job.
func TestMediaToolsAreConcurrencySafe(t *testing.T) {
	var style MediaStyle
	if err := json.Unmarshal([]byte("{\"baseUrl\":\"http://unused\",\"verbs\":{\"image\":{\"mode\":\"sync\",\"kind\":\"image\",\"start\":{\"path\":\"/i\"},\"args\":{\"prompt\":\"p\"},\"resultPath\":\"u\"},\"video\":{\"mode\":\"sync\",\"kind\":\"video\",\"start\":{\"path\":\"/v\"},\"args\":{\"prompt\":\"p\"},\"resultPath\":\"u\"},\"speech\":{\"mode\":\"sync\",\"kind\":\"audio\",\"start\":{\"path\":\"/s\"},\"args\":{\"text\":\"t\"},\"resultPath\":\"u\"},\"transcribe\":{\"mode\":\"sync\",\"kind\":\"text\",\"start\":{\"path\":\"/r\"},\"args\":{\"audio\":\"a\"},\"resultPath\":\"u\"},\"cloneVoice\":{\"mode\":\"sync\",\"kind\":\"text\",\"start\":{\"path\":\"/c\"},\"args\":{\"name\":\"n\"},\"resultPath\":\"id\"},\"talking\":{\"mode\":\"sync\",\"kind\":\"video\",\"start\":{\"path\":\"/t\"},\"args\":{\"image\":\"i\"},\"resultPath\":\"u\"}}}"), &style); err != nil {
		t.Fatal(err)
	}
	tools := CreateMedia([]MediaStyle{style}, MediaOptions{}).Tools()
	if len(tools) != 6 {
		t.Fatalf("want all six media tools, got %d", len(tools))
	}
	for _, tl := range tools {
		if !IsConcurrencySafe(tl, map[string]any{}) {
			t.Fatalf("%s is not concurrency-safe", tl.Name)
		}
	}
}

// §7G Redirects: a cross-origin redirect never carries the style headers.
func TestMediaCrossOriginRedirectDropsStyleHeaders(t *testing.T) {
	var gotAuth, gotCustom = "unset", "unset"
	b := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotCustom = r.Header.Get("Authorization"), r.Header.Get("X-Style")
		_, _ = io.WriteString(w, `{"u":"https://x/r.png"}`)
	}))
	defer b.Close()
	a := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, b.URL+"/landed", http.StatusTemporaryRedirect)
	}))
	defer a.Close()
	m := CreateMedia([]MediaStyle{{BaseURL: a.URL, Headers: map[string]string{"Authorization": "Bearer FAKE", "X-Style": "s"},
		Verbs: map[string]MediaVerb{"image": {Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"},
			Args: map[string]string{"prompt": "p"}, ResultPath: "u"}}}}, MediaOptions{})
	if _, err := m.Image(context.Background(), ImageInput{Prompt: "x"}); err != nil {
		t.Fatal(err)
	}
	if gotAuth != "" || gotCustom != "" {
		t.Fatalf("server B saw Authorization=%q X-Style=%q", gotAuth, gotCustom)
	}
}

// §7G Per-request timeout: a server that never answers is bounded by the deadline.
func TestMediaHungServerBoundedByDeadline(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer srv.Close()
	defer close(release)
	m := CreateMedia([]MediaStyle{{BaseURL: srv.URL, Verbs: map[string]MediaVerb{"image": {
		Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Args: map[string]string{"prompt": "p"},
		Poll: &MediaPoll{TimeoutMs: 200}, ResultPath: "u"}}}}, MediaOptions{})
	began := time.Now()
	_, err := m.Image(context.Background(), ImageInput{Prompt: "x"})
	if err == nil || err.Error() != "image: timed out after 200ms" {
		t.Fatalf("err = %v", err)
	}
	if d := time.Since(began); d > 3*time.Second {
		t.Fatalf("took %v", d)
	}
	// A hung poll is bounded too.
	srv2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			_, _ = io.WriteString(w, `{"id":"h1"}`)
			return
		}
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer srv2.Close()
	st := asyncTestStyle(srv2.URL, 1)
	v := st.Verbs["image"]
	v.Poll.TimeoutMs = 200
	m2 := CreateMedia([]MediaStyle{st}, MediaOptions{})
	if _, err := m2.Image(context.Background(), ImageInput{Prompt: "x"}); err == nil || err.Error() != "job h1 timed out after 200ms" {
		t.Fatalf("poll err = %v", err)
	}
}

func TestMediaDotSetThroughArrayWithWord(t *testing.T) {
	body := map[string]any{"a": []any{}}
	dotSet(body, "a.x.y", 1)
	if got := mediaJSON(body); got != `{"a":{"x":{"y":1}}}` {
		t.Fatalf("body = %s", got)
	}
}
