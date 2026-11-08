package toolnexus

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"
)

// SPEC §7G conformance: replays every file of ../conformance/media/ (the shared
// fixtures, never copies) — cases.json (style engine), clients.json (built-in
// API clients), cli.json (CLI clients over fake-cli.sh), routing.json
// (createMedia routing + tools), schemas.json and verbs.json — hermetically.

// ---- fake HTTP server ----

type mediaRecorded struct {
	Method    string
	Path      string
	Body      any
	Multipart []map[string]any
	Headers   http.Header
}

type mediaScriptEntry struct {
	Status     int               `json:"status"`
	Body       json.RawMessage   `json:"body"`
	RawBody    *string           `json:"rawBody"`
	BodyBase64 *string           `json:"bodyBase64"`
	Headers    map[string]string `json:"headers"`
}

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
		rec := mediaRecorded{Method: r.Method, Path: r.RequestURI, Headers: r.Header.Clone()}
		if mt, params, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err == nil && mt == "multipart/form-data" {
			mr := multipart.NewReader(bytes.NewReader(raw), params["boundary"])
			rec.Multipart = []map[string]any{}
			for {
				p, err := mr.NextPart()
				if err != nil {
					break
				}
				b, _ := io.ReadAll(p)
				if p.FileName() == "" {
					rec.Multipart = append(rec.Multipart, map[string]any{"name": p.FormName(), "value": string(b)})
				} else {
					rec.Multipart = append(rec.Multipart, map[string]any{"name": p.FormName(), "filename": p.FileName(),
						"contentType": p.Header.Get("Content-Type"), "bytesBase64": base64.StdEncoding.EncodeToString(b)})
				}
			}
		} else if len(raw) > 0 {
			_ = json.Unmarshal(raw, &rec.Body)
		}
		f.mu.Lock()
		f.reqs = append(f.reqs, rec)
		e := f.script[min(f.n, len(f.script)-1)]
		f.n++
		f.mu.Unlock()
		for k, v := range e.Headers {
			w.Header().Set(k, v)
		}
		w.WriteHeader(e.Status)
		switch {
		case e.RawBody != nil:
			_, _ = io.WriteString(w, *e.RawBody)
		case e.BodyBase64 != nil:
			b, _ := base64.StdEncoding.DecodeString(*e.BodyBase64)
			_, _ = w.Write(b)
		default:
			_, _ = w.Write(e.Body)
		}
	}))
	return f
}

func (f *fakeMediaServer) requests() []mediaRecorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]mediaRecorded(nil), f.reqs...)
}

// ---- shared helpers ----

func readConformance(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("../conformance/media/" + name)
	if err != nil {
		t.Fatalf("read conformance fixture: %v", err)
	}
	return b
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

// loadMediaFile reads a conformance file's raw cases and checks the exact count.
func loadMediaFile(t *testing.T, name string, want int) []json.RawMessage {
	t.Helper()
	var doc struct {
		Cases []json.RawMessage `json:"cases"`
	}
	if err := json.Unmarshal(readConformance(t, name), &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Cases) != want {
		t.Fatalf("%s: %d cases, want exactly %d", name, len(doc.Cases), want)
	}
	return doc.Cases
}

// substitute replaces placeholders throughout a raw case; {dir} inside an
// outputPattern is regex-escaped first.
func substitute(t *testing.T, raw json.RawMessage, subs map[string]string) map[string]any {
	t.Helper()
	var c map[string]any
	if err := json.Unmarshal(raw, &c); err != nil {
		t.Fatal(err)
	}
	if tool, ok := c["tool"].(map[string]any); ok {
		if p, ok := tool["outputPattern"].(string); ok {
			for k, v := range subs {
				p = strings.ReplaceAll(p, k, regexp.QuoteMeta(v))
			}
			tool["outputPattern"] = p
		}
	}
	b, _ := json.Marshal(c)
	s := string(b)
	for k, v := range subs {
		enc, _ := json.Marshal(v)
		s = strings.ReplaceAll(s, k, string(enc[1:len(enc)-1]))
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(s), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func mediaMap(v any) map[string]any { m, _ := v.(map[string]any); return m }

func applyEnv(t *testing.T, c map[string]any) {
	t.Helper()
	for k, v := range mediaMap(c["env"]) {
		t.Setenv(k, v.(string))
	}
	if us, ok := c["unsetEnv"].([]any); ok {
		for _, k := range us {
			t.Setenv(k.(string), "")
			_ = os.Unsetenv(k.(string))
		}
	}
}

func scriptOf(t *testing.T, c map[string]any) []mediaScriptEntry {
	t.Helper()
	var s []mediaScriptEntry
	if err := json.Unmarshal([]byte(mediaJSON(c["script"])), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

// configOf decodes a case config into MediaConfig.
func configOf(t *testing.T, raw any) MediaConfig {
	t.Helper()
	var c struct {
		Model  string            `json:"model"`
		Models map[string]string `json:"models"`
		Key    string            `json:"key"`
		URL    string            `json:"url"`
		Loop   struct {
			IntervalMs int `json:"intervalMs"`
			TimeoutMs  int `json:"timeoutMs"`
		} `json:"loop"`
		Gather struct {
			Mode     string `json:"mode"`
			Dir      string `json:"dir"`
			MaxBytes int64  `json:"maxBytes"`
			Keep     *bool  `json:"keep"`
		} `json:"gather"`
	}
	if raw != nil {
		if err := json.Unmarshal([]byte(mediaJSON(raw)), &c); err != nil {
			t.Fatal(err)
		}
	}
	return MediaConfig{Model: c.Model, Models: c.Models, Key: c.Key, URL: c.URL,
		Loop:   MediaLoop{IntervalMs: c.Loop.IntervalMs, TimeoutMs: c.Loop.TimeoutMs},
		Gather: MediaGather{Mode: c.Gather.Mode, Dir: c.Gather.Dir, MaxBytes: c.Gather.MaxBytes, Keep: c.Gather.Keep}}
}

func assertRequests(t *testing.T, face string, c map[string]any, got []mediaRecorded) {
	t.Helper()
	if want, has := c["expectRequests"].([]any); has {
		if len(got) != len(want) {
			t.Fatalf("%s: %d requests, want %d: %+v", face, len(got), len(want), got)
		}
		for i, wv := range want {
			w := mediaMap(wv)
			g := got[i]
			if g.Method != w["method"] || g.Path != w["path"] {
				t.Errorf("%s: request %d = %s %s, want %v %v", face, i, g.Method, g.Path, w["method"], w["path"])
			}
			if wb, ok := w["body"]; ok {
				if !reflect.DeepEqual(g.Body, wb) {
					t.Errorf("%s: request %d body = %s, want %s", face, i, mediaJSON(g.Body), mediaJSON(wb))
				}
				if ct := g.Headers.Get("Content-Type"); ct != "application/json" {
					t.Errorf("%s: request %d Content-Type = %q", face, i, ct)
				}
			} else if _, mp := w["multipart"]; !mp && g.Body != nil && g.Method == http.MethodGet { // an absent body is asserted only on a GET

				t.Errorf("%s: request %d has unexpected body %s", face, i, mediaJSON(g.Body))
			}
			if wm, ok := w["multipart"]; ok {
				if gm := jsonNorm(t, g.Multipart); !reflect.DeepEqual(gm, wm) {
					t.Errorf("%s: request %d multipart = %s, want %s", face, i, mediaJSON(gm), mediaJSON(wm))
				}
			}
			for k, v := range mediaMap(w["headers"]) {
				if gv := g.Headers.Get(k); gv != v {
					t.Errorf("%s: request %d header %s = %q, want %q", face, i, k, gv, v)
				}
			}
			if ab, ok := w["absentHeaders"].([]any); ok {
				for _, k := range ab {
					if _, present := g.Headers[http.CanonicalHeaderKey(k.(string))]; present {
						t.Errorf("%s: request %d sent header %s", face, i, k)
					}
				}
			}
		}
	}
	for name, pat := range mediaMap(c["expectHeaderPatterns"]) { // D23
		re := regexp.MustCompile(pat.(string))
		for i, g := range got {
			if v := g.Headers.Get(name); !re.MatchString(v) {
				t.Fatalf("%s request %d header %s = %q, want pattern %q", face, i, name, v, pat)
			}
		}
	}
	for name, want := range mediaMap(c["expectHeaders"]) {
		for i, g := range got {
			if v := g.Headers.Get(name); v != want {
				t.Errorf("%s: request %d header %s = %q, want %q", face, i, name, v, want)
			}
		}
	}
}

// assertResult compares a function-face outcome with result / error / errorPrefix / status.
func assertResult(t *testing.T, c map[string]any, client, dir string, res MediaResult, err error) {
	t.Helper()
	wantErr, hasErr := c["error"].(string)
	prefix, hasPrefix := c["errorPrefix"].(string)
	if hasErr || hasPrefix {
		if err == nil {
			t.Fatalf("no error, want %q%q (result %s)", wantErr, prefix, mediaJSON(res))
		}
		if hasErr && err.Error() != wantErr {
			t.Fatalf("error = %q\nwant    %q", err.Error(), wantErr)
		}
		if hasPrefix && !strings.HasPrefix(err.Error(), prefix) {
			t.Fatalf("error = %q, want prefix %q", err.Error(), prefix)
		}
		if st, ok := c["status"].(string); ok {
			var me *MediaError
			if !errors.As(err, &me) || me.Status != st {
				t.Fatalf("error status = %+v, want %s", me, st)
			}
			if (st == "unavailable") != errors.Is(err, ErrMediaUnavailable) {
				t.Fatalf("errors.Is(ErrMediaUnavailable) wrong for status %s", st)
			}
		}
		return
	}
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if c["noUsage"] == true && res.Usage != nil {
		t.Fatalf("result usage = %+v, want none (D21)", res.Usage)
	}
	want, ok := c["result"]
	if !ok {
		return
	}
	got := jsonNorm(t, res).(map[string]any)
	wf, _ := mediaMap(want)["files"].([]any)
	gf, _ := got["files"].([]any)
	if len(wf) == len(gf) {
		for i := range wf {
			saved, ok := mediaMap(wf[i])["saved"]
			if !ok {
				continue
			}
			g := mediaMap(gf[i])
			assertSaved(t, client, c["verb"].(string), dir, g, mediaMap(saved))
			gf[i] = wf[i]
		}
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("result = %s\nwant     %s", mediaJSON(got), mediaJSON(want))
	}
}

func assertSaved(t *testing.T, client, verb, dir string, g, saved map[string]any) {
	t.Helper()
	p, _ := g["path"].(string)
	if !filepath.IsAbs(p) || filepath.Dir(p) != dir {
		t.Fatalf("saved path %q not directly in %q", p, dir)
	}
	re := regexp.MustCompile("^" + regexp.QuoteMeta(client+"-"+verb+"-") + "[0-9]+-[0-9]+" + regexp.QuoteMeta(saved["ext"].(string)) + "$")
	if !re.MatchString(filepath.Base(p)) {
		t.Fatalf("saved name %q", filepath.Base(p))
	}
	if g["url"] != "file://"+p {
		t.Fatalf("saved url %v, path %q", g["url"], p)
	}
	b, err := os.ReadFile(p)
	if err != nil || base64.StdEncoding.EncodeToString(b) != saved["bytesBase64"] {
		t.Fatalf("saved bytes %q (%v), want %v", base64.StdEncoding.EncodeToString(b), err, saved["bytesBase64"])
	}
}

func toolNameFor(verb string) string {
	s, _ := mediaVerbSpecOf(verb)
	return s.tool
}

// runToolFace executes the verb's tool and asserts the tool expectation.
func runToolFace(t *testing.T, m *Media, c map[string]any) {
	t.Helper()
	want := mediaMap(c["tool"])
	name := toolNameFor(c["verb"].(string))
	var tool *Tool
	for _, tl := range m.Tools() {
		if tl.Name == name {
			tool = &tl
		}
	}
	if tool == nil {
		t.Fatalf("no tool %s", name)
	}
	if n, ok := want["name"]; ok && n != tool.Name {
		t.Fatalf("tool name %s, want %v", tool.Name, n)
	}
	if tool.Source != SourceMedia {
		t.Errorf("source = %q", tool.Source)
	}
	args, _ := c["args"].(map[string]any)
	tr, terr := tool.Execute(args, &ToolContext{Ctx: context.Background()})
	if terr != nil {
		t.Fatalf("tool Execute returned error: %v", terr)
	}
	if c["noUsage"] == true && tr.Metadata != nil {
		if _, ok := tr.Metadata["usage"]; ok {
			t.Fatalf("tool metadata has usage, want none (D21)")
		}
	}
	assertRunFilePath(t, name, tr, c)
	if tr.IsError != want["isError"] {
		t.Fatalf("tool isError = %v (%q), want %v", tr.IsError, tr.Output, want["isError"])
	}
	if o, ok := want["output"].(string); ok && tr.Output != o {
		t.Fatalf("tool output = %q\nwant          %q", tr.Output, o)
	}
	if p, ok := want["outputPattern"].(string); ok && !regexp.MustCompile(p).MatchString(tr.Output) {
		t.Fatalf("tool output = %q, want pattern %q", tr.Output, p)
	}
	if p, ok := want["outputPrefix"].(string); ok && !strings.HasPrefix(tr.Output, p) {
		t.Fatalf("tool output = %q, want prefix %q", tr.Output, p)
	}
	if wp, ok := want["parts"]; ok {
		if gp := jsonNorm(t, tr.Parts); !reflect.DeepEqual(gp, wp) {
			t.Fatalf("tool parts = %s\nwant         %s", mediaJSON(gp), mediaJSON(wp))
		}
	}
	if want["noParts"] == true && tr.Parts != nil {
		t.Fatalf("tool parts = %+v, want none", tr.Parts)
	}
	if wm, ok := want["metadata"]; ok {
		if gm := jsonNorm(t, tr.Metadata); !reflect.DeepEqual(gm, wm) {
			t.Fatalf("tool metadata = %s\nwant            %s", mediaJSON(gm), mediaJSON(wm))
		}
	}
	if wmp, ok := want["modelParts"]; ok {
		// §7G.6 model visibility, via the same function message assembly uses (§8A).
		entry := withToolParts(map[string]any{"role": "tool", "content": tr.Output}, tool.Name, toolPartsForModel(tr))
		gp := []ContentPart{}
		if ps, ok := entry["parts"].([]ContentPart); ok {
			gp = ps
		}
		if g := jsonNorm(t, gp); !reflect.DeepEqual(g, wmp) {
			t.Fatalf("modelParts = %s\nwant         %s", mediaJSON(g), mediaJSON(wmp))
		}
	}
}

func progressRecorder(t *testing.T, verb, client string) (*[]string, func(MediaProgress)) {
	var mu sync.Mutex
	got := []string{}
	return &got, func(p MediaProgress) {
		mu.Lock()
		defer mu.Unlock()
		if p.Verb != verb || p.Client != client {
			t.Errorf("progress verb/client = %s/%s, want %s/%s", p.Verb, p.Client, verb, client)
		}
		got = append(got, p.Status)
	}
}

func assertProgress(t *testing.T, c map[string]any, got []string) {
	t.Helper()
	if want, ok := c["expectProgress"].([]any); ok {
		if g := jsonNorm(t, got); !reflect.DeepEqual(g, jsonNorm(t, want)) {
			t.Errorf("progress = %v, want %v", got, want)
		}
	}
}

// ---- cases.json (style engine) ----

func TestMediaConformanceCases(t *testing.T) {
	for _, raw := range loadMediaFile(t, "cases.json", 49) {
		var head struct{ Name string }
		_ = json.Unmarshal(raw, &head)
		t.Run(head.Name, func(t *testing.T) {
			for _, face := range []string{"function", "tool"} {
				srv := newFakeMediaServer(nil)
				dir := t.TempDir()
				c := substitute(t, raw, map[string]string{"{server}": srv.URL, "{dir}": dir})
				srv.script = scriptOf(t, c)
				applyEnv(t, c)
				var style MediaStyle
				if err := json.Unmarshal([]byte(mediaJSON(c["style"])), &style); err != nil {
					t.Fatal(err)
				}
				cfg := configOf(t, c["config"])
				cfg.HTTPClient = srv.Client()
				name := "style"
				if style.Label != "" {
					name = style.Label
				}
				progress, onProgress := progressRecorder(t, c["verb"].(string), name)
				cfg.Loop.OnProgress = onProgress
				client, err := NewStyleMediaClient(style, cfg)
				if c["styleError"] == true {
					if err == nil || err.Error() != c["error"] {
						t.Fatalf("style error = %v, want %v", err, c["error"])
					}
					if len(srv.requests()) != 0 {
						t.Fatal("request made for an invalid style")
					}
					srv.Close()
					return
				}
				if err != nil {
					t.Fatal(err)
				}
				m := MustCreateMedia(client)
				if face == "function" {
					args, _ := c["args"].(map[string]any)
					res, err := m.Generate(context.Background(), c["verb"].(string), args, MediaCall{})
					assertResult(t, c, name, dir, res, err)
					assertProgress(t, c, *progress)
				} else {
					if _, ok := c["tool"]; !ok {
						srv.Close()
						continue
					}
					runToolFace(t, m, c)
				}
				assertRequests(t, face, c, srv.requests())
				srv.Close()
			}
		})
	}
}

// ---- clients.json (built-in API clients) ----

func newBuiltinClient(t *testing.T, name string, cfg MediaConfig) MediaClient {
	t.Helper()
	switch name {
	case "a2e":
		return NewA2EMediaClient(cfg)
	case "elevenlabs":
		return NewElevenLabsMediaClient(cfg)
	case "openai":
		return NewOpenAIMediaClient(cfg)
	case "openrouter":
		return NewOpenRouterMediaClient(cfg)
	case "fal":
		return NewFalMediaClient(cfg)
	}
	t.Fatalf("unknown client %s", name)
	return nil
}

func TestMediaConformanceClients(t *testing.T) {
	for _, raw := range loadMediaFile(t, "clients.json", 76) {
		var head struct{ Name string }
		_ = json.Unmarshal(raw, &head)
		t.Run(head.Name, func(t *testing.T) {
			for _, face := range []string{"function", "tool"} {
				srv := newFakeMediaServer(nil)
				dir, inputs := t.TempDir(), t.TempDir()
				c := substitute(t, raw, map[string]string{"{server}": srv.URL, "{dir}": dir, "{inputs}": inputs})
				_, hasTool := c["tool"]
				_, hasRFP := c["runFilePath"]
				if face == "tool" {
					if !hasTool && !hasRFP {
						srv.Close()
						continue
					}
				}
				srv.script = scriptOf(t, c)
				applyEnv(t, c)
				for n, b64 := range mediaMap(c["inputFiles"]) {
					b, _ := base64.StdEncoding.DecodeString(b64.(string))
					if err := os.WriteFile(filepath.Join(inputs, n), b, 0o644); err != nil {
						t.Fatal(err)
					}
				}
				name := c["client"].(string)
				cfg := configOf(t, c["config"])
				cfg.HTTPClient = srv.Client()
				progress, onProgress := progressRecorder(t, c["verb"].(string), name)
				cfg.Loop.OnProgress = onProgress
				m := MustCreateMedia(newBuiltinClient(t, name, cfg))
				if face == "tool" && hasCtxTimeout(c) {
					srv.Close()
					continue
				}
				began := time.Now()
				if face == "function" {
					args, _ := c["args"].(map[string]any)
					ctx, cancel := caseCtx(c)
					res, err := m.Generate(ctx, c["verb"].(string), args, MediaCall{})
					cancel()
					assertResult(t, c, name, dir, res, err)
					assertProgress(t, c, *progress)
				} else if hasTool {
					runToolFace(t, m, c)
				} else {
					runFilePathOnly(t, m, c)
				}
				assertElapsed(t, c, face, began)
				assertRequests(t, face, c, srv.requests())
				srv.Close()
			}
		})
	}
}

func hasCtxTimeout(c map[string]any) bool {
	_, ok := c["ctxTimeoutMs"].(float64)
	return ok
}

// caseCtx gives the function face ctx.deadline = now + ctxTimeoutMs (D12), else Background.
func caseCtx(c map[string]any) (context.Context, context.CancelFunc) {
	if ms, ok := c["ctxTimeoutMs"].(float64); ok {
		return context.WithTimeout(context.Background(), time.Duration(ms)*time.Millisecond)
	}
	return context.WithCancel(context.Background())
}

func assertElapsed(t *testing.T, c map[string]any, face string, began time.Time) {
	t.Helper()
	if ms, ok := c["maxElapsedMs"].(float64); ok && time.Since(began) > time.Duration(ms)*time.Millisecond {
		t.Fatalf("%s face took %v, max %vms", face, time.Since(began), ms)
	}
}

// ---- cli.json (CLI clients over the fake CLI) ----

func TestMediaConformanceCLI(t *testing.T) {
	fake, err := filepath.Abs("../conformance/media/fake-cli/fake-cli.sh")
	if err != nil {
		t.Fatal(err)
	}
	for _, raw := range loadMediaFile(t, "cli.json", 33) {
		var head struct{ Name string }
		_ = json.Unmarshal(raw, &head)
		t.Run(head.Name, func(t *testing.T) {
			for _, face := range []string{"function", "tool"} {
				out, dir := t.TempDir(), t.TempDir()
				c := substitute(t, raw, map[string]string{"{out}": out, "{dir}": dir})
				if face == "tool" {
					if _, ok := c["tool"]; !ok {
						continue
					}
				}
				applyEnv(t, c)
				fixture := filepath.Join(t.TempDir(), "fixture.jsonl")
				var lines []string
				for _, l := range c["fixture"].([]any) {
					lines = append(lines, l.(string))
				}
				if err := os.WriteFile(fixture, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
					t.Fatal(err)
				}
				style, _ := c["style"].(string)
				if style == "" {
					style = c["client"].(string) + "-cli"
				}
				name := cliPresets[style]
				cfg := configOf(t, c["config"])
				cfg.Command = []string{"/bin/sh", fake, fixture, out}
				if cmd, ok := c["command"].([]any); ok {
					cfg.Command = nil
					for _, a := range cmd {
						cfg.Command = append(cfg.Command, a.(string))
					}
				}
				progress, onProgress := progressRecorder(t, c["verb"].(string), name)
				cfg.Loop.OnProgress = onProgress
				client, err := LoadMediaStyle(style, cfg)
				if err != nil {
					t.Fatal(err)
				}
				m := MustCreateMedia(client)
				if face == "tool" && hasCtxTimeout(c) {
					continue
				}
				began := time.Now()
				if face == "function" {
					args, _ := c["args"].(map[string]any)
					ctx, cancel := caseCtx(c)
					res, err := m.Generate(ctx, c["verb"].(string), args, MediaCall{})
					cancel()
					assertResult(t, c, name, dir, res, err)
					assertProgress(t, c, *progress)
					if c["oneShot"] == true {
						res, err := GenerateMedia(context.Background(), style, c["verb"].(string), args, cfg)
						assertResult(t, c, name, dir, res, err)
					}
					if c["oneShot"] == true {
						res, err := GenerateMedia(context.Background(), style, c["verb"].(string), args, cfg)
						assertResult(t, c, name, dir, res, err)
					}
				} else {
					runToolFace(t, m, c)
				}
				assertElapsed(t, c, face, began)
				assertCLIOut(t, c, out)
			}
		})
	}
}

func assertCLIOut(t *testing.T, c map[string]any, out string) {
	t.Helper()
	argvFile := filepath.Join(out, "argv.txt")
	if c["expectNoSpawn"] == true {
		if _, err := os.Stat(argvFile); err == nil {
			t.Fatal("the CLI was spawned")
		}
	}
	if want, ok := c["expectArgv"].([]any); ok {
		b, err := os.ReadFile(argvFile)
		if err != nil {
			t.Fatal(err)
		}
		got := strings.Split(strings.TrimSuffix(string(b), "\n"), "\n")
		cwdB, _ := os.ReadFile(filepath.Join(out, "cwd.txt"))
		cwd := strings.TrimSpace(string(cwdB))
		if len(got) != len(want) {
			t.Fatalf("argv = %q, want %v", got, want)
		}
		for i, w := range want {
			if w == "{cwd}" { // D17: byte for byte with the fake's `pwd -P`
				if got[i] != cwd {
					t.Fatalf("argv cwd %q, want %q", got[i], cwd)
				}
				continue
			}
			if got[i] != w {
				t.Fatalf("argv[%d] = %q, want %q", i, got[i], w)
			}
		}
	}
	if env, ok := mediaMap(c["expectEnv"])["GROK_CONFIG"].(string); ok {
		b, _ := os.ReadFile(filepath.Join(out, "env-GROK_CONFIG.txt"))
		if string(b) != env {
			t.Fatalf("GROK_CONFIG = %q, want %q", b, env)
		}
	}
	if ps, ok := c["expectExists"].([]any); ok {
		for _, p := range ps {
			if _, err := os.Stat(p.(string)); err != nil {
				t.Fatalf("missing %s", p)
			}
		}
	}
	if ps, ok := c["expectMissing"].([]any); ok {
		for _, p := range ps {
			if _, err := os.Stat(p.(string)); err == nil {
				t.Fatalf("%s still exists", p)
			}
		}
	}
}

// ---- routing.json ----

type routeCall struct {
	Client string         `json:"client"`
	Verb   string         `json:"verb"`
	Args   map[string]any `json:"args"`
}

type routeClient struct {
	UnsupportedMedia
	name  string
	verbs map[string]map[string]any
	calls *[]routeCall
}

func (r *routeClient) Name() string { return r.name }
func (r *routeClient) Supports(v string) bool {
	_, ok := r.verbs[v]
	return ok
}
func (r *routeClient) Args(v string) []MediaArg {
	list, ok := r.verbs[v]["args"].([]any)
	if !ok {
		return nil
	}
	out := []MediaArg{}
	for _, a := range list {
		out = append(out, MediaArg{Name: mediaMap(a)["name"].(string), Required: mediaMap(a)["required"] == true})
	}
	return out
}
func (r *routeClient) do(verb string, args map[string]any) (MediaResult, error) {
	*r.calls = append(*r.calls, routeCall{r.name, verb, args})
	o := mediaMap(r.verbs[verb]["outcome"])
	if e, ok := o["error"].(string); ok {
		return MediaResult{}, &MediaError{Message: e, Status: o["status"].(string)}
	}
	var res MediaResult
	_ = json.Unmarshal([]byte(mediaJSON(o["result"])), &res)
	return res, nil
}
func (r *routeClient) Image(_ context.Context, a map[string]any, _ MediaCall) (MediaResult, error) {
	return r.do("image", a)
}
func (r *routeClient) Video(_ context.Context, a map[string]any, _ MediaCall) (MediaResult, error) {
	return r.do("video", a)
}
func (r *routeClient) Speech(_ context.Context, a map[string]any, _ MediaCall) (MediaResult, error) {
	return r.do("speech", a)
}
func (r *routeClient) Music(_ context.Context, a map[string]any, _ MediaCall) (MediaResult, error) {
	return r.do("music", a)
}
func (r *routeClient) Transcribe(_ context.Context, a map[string]any, _ MediaCall) (MediaResult, error) {
	return r.do("transcribe", a)
}
func (r *routeClient) CloneVoice(_ context.Context, a map[string]any, _ MediaCall) (MediaResult, error) {
	return r.do("cloneVoice", a)
}
func (r *routeClient) Talk(_ context.Context, a map[string]any, _ MediaCall) (MediaResult, error) {
	return r.do("talk", a)
}

func routeClients(c map[string]any, calls *[]routeCall) []MediaClient {
	var out []MediaClient
	for _, cl := range c["clients"].([]any) {
		m := mediaMap(cl)
		verbs := map[string]map[string]any{}
		for v, spec := range mediaMap(m["verbs"]) {
			verbs[v] = mediaMap(spec)
		}
		out = append(out, &routeClient{name: m["name"].(string), verbs: verbs, calls: calls})
	}
	return out
}

func TestMediaConformanceRouting(t *testing.T) {
	raw := readConformance(t, "routing.json")
	var doc struct {
		Cases        []map[string]any `json:"cases"`
		SchemaCases  []map[string]any `json:"schemaCases"`
		EmptyClients struct {
			Error string `json:"error"`
		} `json:"emptyClients"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Cases) != 19 || len(doc.SchemaCases) != 1 {
		t.Fatalf("routing.json: %d cases + %d schema cases, want exactly 19 + 1", len(doc.Cases), len(doc.SchemaCases))
	}
	for _, c := range doc.Cases {
		t.Run(c["name"].(string), func(t *testing.T) {
			calls := []routeCall{}
			m := MustCreateMedia(routeClients(c, &calls)...)
			args, _ := c["args"].(map[string]any)
			res, err := m.Generate(context.Background(), c["verb"].(string), args, MediaCall{})
			if want, ok := c["expectCalls"]; ok {
				if g := jsonNorm(t, calls); !reflect.DeepEqual(g, want) {
					t.Fatalf("calls = %s\nwant    %s", mediaJSON(g), mediaJSON(want))
				}
			}
			assertResult(t, c, "", "", res, err)
			if _, ok := c["tool"]; ok {
				calls = []routeCall{}
				runToolFace(t, MustCreateMedia(routeClients(c, &calls)...), c)
			}
		})
	}
	for _, sc := range doc.SchemaCases {
		t.Run(sc["name"].(string), func(t *testing.T) {
			calls := []routeCall{}
			tools := MustCreateMedia(routeClients(sc, &calls)...).Tools()
			got := []any{}
			for _, tl := range tools {
				got = append(got, map[string]any{"name": tl.Name, "description": tl.Description,
					"source": string(tl.Source), "inputSchema": map[string]any(tl.InputSchema)})
			}
			if g, w := jsonNorm(t, got), jsonNorm(t, sc["tools"]); !reflect.DeepEqual(g, w) {
				t.Fatalf("tools = %s\nwant    %s", mediaJSON(g), mediaJSON(w))
			}
		})
	}
	if _, err := CreateMedia(); err == nil || err.Error() != doc.EmptyClients.Error {
		t.Fatalf("CreateMedia() = %v, want %q", err, doc.EmptyClients.Error)
	}
}

// ---- schemas.json, verbs.json, the embedded a2e preset ----

func TestMediaConformanceSchemas(t *testing.T) {
	var doc struct {
		Style MediaStyle       `json:"style"`
		Tools []map[string]any `json:"tools"`
	}
	if err := json.Unmarshal(readConformance(t, "schemas.json"), &doc); err != nil {
		t.Fatal(err)
	}
	client, err := NewStyleMediaClient(doc.Style, MediaConfig{})
	if err != nil {
		t.Fatal(err)
	}
	got := jsonNorm(t, MustCreateMedia(client).Tools())
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

func TestMediaA2EPresetEmbeddedVerbatim(t *testing.T) {
	want, err := os.ReadFile("../examples/media-styles/a2e.json")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(a2eStyleJSON, want) {
		t.Fatal("golang/media-styles/a2e.json differs from examples/media-styles/a2e.json — copy it verbatim")
	}
	if _, err := LoadMediaStyle("../examples/media-styles/a2e.json"); err != nil {
		t.Fatal(err)
	}
	c := NewA2EMediaClient(MediaConfig{})
	if c.Name() != "a2e" || !c.Supports("talk") || c.Supports("music") {
		t.Fatalf("a2e client = %s", c.Name())
	}
}

// ---- engine behaviour beyond the fixtures ----

func asyncTestStyle(base string, intervalMs int) MediaStyle {
	return MediaStyle{BaseURL: base, Verbs: map[string]MediaVerb{"image": {
		Mode: "async", Kind: "image", Start: MediaStart{Path: "/s"},
		Args: map[string]string{"prompt": "prompt"}, IDPath: "id",
		Poll: &MediaPoll{Path: "/p/{id}", StatusPath: "st", Done: []string{"completed"}, Failed: []string{"failed"},
			IntervalMs: intervalMs, TimeoutMs: 600000},
		ResultPath: "u"}}}
}

func styleMedia(t *testing.T, s MediaStyle, cfg MediaConfig) *Media {
	t.Helper()
	c, err := NewStyleMediaClient(s, cfg)
	if err != nil {
		t.Fatal(err)
	}
	return MustCreateMedia(c)
}

func TestMediaCancellationStopsPolling(t *testing.T) {
	srv := newFakeMediaServer([]mediaScriptEntry{
		{Status: 200, Body: json.RawMessage(`{"id":"j9"}`)},
		{Status: 200, Body: json.RawMessage(`{"st":"processing"}`)},
	})
	defer srv.Close()
	ctx, cancel := context.WithCancel(context.Background())
	m := styleMedia(t, asyncTestStyle(srv.URL, 5), MediaConfig{HTTPClient: srv.Client()})
	done := make(chan error, 1)
	go func() {
		_, err := m.Image(ctx, ImageInput{Prompt: "x", MediaCall: MediaCall{Progress: func(MediaProgress) { cancel() }}})
		done <- err
	}()
	select {
	case err := <-done:
		var me *MediaError
		if !errors.Is(err, context.Canceled) || !errors.As(err, &me) || me.Status != "cancelled" {
			t.Fatalf("err = %v, want context.Canceled with status cancelled", err)
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

func TestMediaCLICancellationKillsProcess(t *testing.T) {
	fake, _ := filepath.Abs("../conformance/media/fake-cli/fake-cli.sh")
	fixture := filepath.Join(t.TempDir(), "f.jsonl")
	_ = os.WriteFile(fixture, []byte("#hang\n"), 0o644)
	m := MustCreateMedia(NewGrokMediaClient(MediaConfig{Command: []string{"/bin/sh", fake, fixture, t.TempDir()}}))
	ctx, cancel := context.WithCancel(context.Background()) // an explicit cancel (D12: a deadline is timeout)
	defer cancel()
	time.AfterFunc(200*time.Millisecond, cancel)
	began := time.Now()
	_, err := m.Image(ctx, ImageInput{Prompt: "x"})
	var me *MediaError
	if !errors.Is(err, context.Canceled) || !errors.As(err, &me) || me.Status != "cancelled" {
		t.Fatalf("err = %v", err)
	}
	if time.Since(began) > 5*time.Second {
		t.Fatalf("took %v", time.Since(began))
	}
}

func TestMediaFirstClientWinsAndProviderOptions(t *testing.T) {
	srv := newFakeMediaServer([]mediaScriptEntry{{Status: 200, Body: json.RawMessage(`{"u":"https://x/a.gif"}`)}})
	defer srv.Close()
	first := MediaStyle{BaseURL: srv.URL + "/one", Verbs: map[string]MediaVerb{"image": {
		Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Defaults: map[string]any{"q": "low", "keep": 1},
		Args: map[string]string{"prompt": "p", "seed": "opts.seed"}, ResultPath: "u"}}}
	second := MediaStyle{BaseURL: srv.URL + "/two", Verbs: map[string]MediaVerb{
		"image":  {Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Args: map[string]string{"prompt": "p"}, ResultPath: "u"},
		"speech": {Mode: "sync", Kind: "audio", Start: MediaStart{Path: "/s"}, Args: map[string]string{"text": "t"}, ResultPath: "u"},
	}}
	a, _ := NewStyleMediaClient(first, MediaConfig{HTTPClient: srv.Client()})
	b, _ := NewStyleMediaClient(second, MediaConfig{HTTPClient: srv.Client()})
	m := MustCreateMedia(a, b)
	r, err := m.Image(context.Background(), ImageInput{Prompt: "cat", Seed: 7, MediaCall: MediaCall{ProviderOptions: map[string]any{"q": "hd"}}})
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Files) != 1 || r.Files[0].MimeType != "image/gif" {
		t.Fatalf("result = %+v", r)
	}
	req := srv.requests()[0]
	if req.Path != "/one/i" {
		t.Fatalf("path = %s, want first client", req.Path)
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
	if !m.Supports("speech") || m.Supports("music") {
		t.Fatal("Supports wrong")
	}
}

func TestMediaUnavailableIsTyped(t *testing.T) {
	err := MediaUnavailable("x: HTTP 402: {}")
	if !errors.Is(err, ErrMediaUnavailable) {
		t.Fatal("MediaUnavailable is not ErrMediaUnavailable")
	}
	if errors.Is(mediaFailed("boom"), ErrMediaUnavailable) {
		t.Fatal("failed matched unavailable")
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

// RunResult.Files / toolCalls[].Parts (§8): every non-text part a run's tools
// produced, in call order, with From = tool name and CallID = the model's id.
// Only image parts reach the model (§7G.6); the video stays out of its context.
func TestRunResultFilesFromMediaTools(t *testing.T) {
	calls := []routeCall{}
	media := MustCreateMedia(&routeClient{name: "f", calls: &calls, verbs: map[string]map[string]any{
		"image": {"outcome": map[string]any{"result": map[string]any{"status": "completed", "files": []any{
			map[string]any{"type": "image", "mimeType": "image/png", "url": "https://x/a.png"},
			map[string]any{"type": "file", "mimeType": "video/mp4", "url": "https://x/b.mp4"}}}}}}})
	tk, err := CreateToolkit(context.Background(), Options{Builtins: false, Media: media})
	if err != nil {
		t.Fatal(err)
	}
	defer tk.Close()
	tk.Register(addTool(t))
	if _, ok := tk.Get("generate_image"); !ok {
		t.Fatal("Options.Media did not register generate_image")
	}
	var second string
	n := 0
	c := CreateInProcessClient(InProcessOptions{Model: "m",
		Generate: func(req InProcessRequest) (InProcessResponse, error) {
			n++
			if n == 1 {
				return InProcessResponse{ToolCalls: []InProcessToolCall{
					{ID: "call_add", Name: "add", Arguments: map[string]any{"a": 1, "b": 2}},
					{ID: "call_img", Name: "generate_image", Arguments: map[string]any{"prompt": "cat"}}}}, nil
			}
			second = mediaJSON(req.Messages)
			return InProcessResponse{Content: "done"}, nil
		}})
	r, err := c.Run(context.Background(), "draw", tk)
	if err != nil {
		t.Fatal(err)
	}
	wantFiles := []RunFile{
		{Type: "image", MimeType: "image/png", URL: "https://x/a.png", From: "generate_image", CallID: "call_img"},
		{Type: "file", MimeType: "video/mp4", URL: "https://x/b.mp4", From: "generate_image", CallID: "call_img"},
	}
	if !reflect.DeepEqual(r.Files, wantFiles) {
		t.Fatalf("files = %+v", r.Files)
	}
	if r.ToolCalls[0].Parts != nil || len(r.ToolCalls[1].Parts) != 2 {
		t.Fatalf("toolCalls parts = %+v", r.ToolCalls)
	}
	if !strings.Contains(second, `"image_url":{"url":"https://x/a.png"}`) {
		t.Fatalf("image part did not reach the model: %s", second)
	}
	if strings.Count(second, "b.mp4") != 1 { // only in the tool's output line
		t.Fatalf("video part reached the model: %s", second)
	}
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

// Media tools are concurrency-safe (SPEC §7G.6): each call is an independent job.
func TestMediaToolsAreConcurrencySafe(t *testing.T) {
	calls := []routeCall{}
	verbs := map[string]map[string]any{}
	for _, v := range mediaVerbs {
		verbs[v.verb] = map[string]any{}
	}
	tools := MustCreateMedia(&routeClient{name: "all", verbs: verbs, calls: &calls}).Tools()
	if len(tools) != 7 {
		t.Fatalf("want all seven media tools, got %d", len(tools))
	}
	for _, tl := range tools {
		if !IsConcurrencySafe(tl, map[string]any{}) {
			t.Fatalf("%s is not concurrency-safe", tl.Name)
		}
	}
}

// §7G.8 Redirects: a cross-origin redirect never carries the client headers.
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
	m := styleMedia(t, MediaStyle{BaseURL: a.URL, Headers: map[string]string{"Authorization": "Bearer FAKE", "X-Style": "s"},
		Verbs: map[string]MediaVerb{"image": {Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"},
			Args: map[string]string{"prompt": "p"}, ResultPath: "u"}}}, MediaConfig{})
	if _, err := m.Image(context.Background(), ImageInput{Prompt: "x"}); err != nil {
		t.Fatal(err)
	}
	if gotAuth != "" || gotCustom != "" {
		t.Fatalf("server B saw Authorization=%q X-Style=%q", gotAuth, gotCustom)
	}
}

// §7G.8 Per-request timeout: a server that never answers is bounded by the deadline.
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
	m := styleMedia(t, MediaStyle{BaseURL: srv.URL, Verbs: map[string]MediaVerb{"image": {
		Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Args: map[string]string{"prompt": "p"},
		Poll: &MediaPoll{TimeoutMs: 1000}, ResultPath: "u"}}}, MediaConfig{})
	began := time.Now()
	_, err := m.Image(context.Background(), ImageInput{Prompt: "x"})
	var me *MediaError
	if err == nil || err.Error() != "image: timed out after 1000ms" || !errors.As(err, &me) || me.Status != "timeout" {
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
	st.Verbs["image"].Poll.TimeoutMs = 1000
	m2 := styleMedia(t, st, MediaConfig{})
	if _, err := m2.Image(context.Background(), ImageInput{Prompt: "x"}); err == nil || err.Error() != "job h1 timed out after 1000ms" {
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

func TestMediaJobIDTextRejectsFractionalFloat(t *testing.T) {
	if _, ok := jobIDText(1.5); ok {
		t.Fatal("1.5 accepted as job id")
	}
	if id, ok := jobIDText(float64(42)); !ok || id != "42" {
		t.Fatalf("42 => %q %v", id, ok)
	}
	if _, ok := jobIDText(json.Number("2.5")); ok {
		t.Fatal("json 2.5 accepted")
	}
}

func TestMediaHostCheckRedirectStillRuns(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/i" {
			w.Header().Set("Location", "/r")
			w.WriteHeader(302)
			_, _ = io.WriteString(w, `{"u":"https://x/a.png"}`)
			return
		}
		t.Errorf("redirect followed despite host policy")
	}))
	defer srv.Close()
	hc := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	m := styleMedia(t, MediaStyle{BaseURL: srv.URL, Verbs: map[string]MediaVerb{"image": {
		Mode: "sync", Kind: "image", Start: MediaStart{Path: "/i"}, Args: map[string]string{"prompt": "p"}, ResultPath: "u"}}},
		MediaConfig{HTTPClient: hc})
	if _, err := m.Image(context.Background(), ImageInput{Prompt: "x"}); err == nil || err.Error() != "HTTP 302: {\"u\":\"https://x/a.png\"}" {
		t.Fatalf("err = %v", err)
	}
}

// assertRunFilePath: D20 — RunResult.files built from a toolCalls record of
// this tool result (runFiles) carry each MediaFile's absolute path.
func assertRunFilePath(t *testing.T, name string, tr ToolResult, c map[string]any) {
	t.Helper()
	rfp, ok := c["runFilePath"].(bool)
	if !ok {
		return
	}
	// D20: RunResult.files from a toolCalls record of this result (runFiles).
	files := runFiles([]ToolCall{{Name: name, Parts: tr.Parts}})
	lines := strings.Split(strings.SplitN(tr.Output, ": ", 2)[len(strings.SplitN(tr.Output, ": ", 2))-1], "\n")
	if len(files) == 0 {
		t.Fatalf("runFilePath: no run files")
	}
	for i, f := range files {
		if !rfp {
			if f.Path != "" {
				t.Fatalf("run file %d has path %q, want none", i, f.Path)
			}
			continue
		}
		if !filepath.IsAbs(f.Path) || i >= len(lines) || f.Path != lines[i] {
			t.Fatalf("run file %d path = %q, want the MediaFile path %q", i, f.Path, lines)
		}
	}
	if b, _ := json.Marshal(tr.Parts); strings.Contains(string(b), `"path"`) {
		t.Fatalf("parts hold a path: %s", b)
	}
}

// runFilePathOnly runs the tool face of a case that has no tool block only for D20.
func runFilePathOnly(t *testing.T, m *Media, c map[string]any) {
	t.Helper()
	name := toolNameFor(c["verb"].(string))
	for _, tl := range m.Tools() {
		if tl.Name == name {
			args, _ := c["args"].(map[string]any)
			tr, err := tl.Execute(args, &ToolContext{Ctx: context.Background()})
			if err != nil || tr.IsError {
				t.Fatalf("tool face: %v %q", err, tr.Output)
			}
			assertRunFilePath(t, name, tr, c)
			return
		}
	}
	t.Fatalf("no tool %s", name)
}

func TestMediaConformanceCLIVerbs(t *testing.T) {
	b, err := os.ReadFile("../conformance/media/cli.json")
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		VerbCases []struct {
			Name        string
			Styles      []json.RawMessage
			ExpectVerbs []string
			ExpectTools []string
			Unsupported string
			Error       string
		}
	}
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.VerbCases) != 8 {
		t.Fatalf("verbCases = %d, want 8", len(f.VerbCases))
	}
	for _, vc := range f.VerbCases {
		t.Run(vc.Name, func(t *testing.T) {
			var srcs []MediaClient
			for _, raw := range vc.Styles {
				var name string
				if json.Unmarshal(raw, &name) == nil {
					s, err := LoadMediaStyle(name, MediaConfig{Command: []string{"/nonexistent/toolnexus-must-not-run"}})
					if err != nil {
						t.Fatal(err)
					}
					srcs = append(srcs, s)
					continue
				}
				s, err := parseMediaStyle(raw, "inline")
				if err != nil {
					t.Fatal(err)
				}
				srcs = append(srcs, s)
			}
			m := MustCreateMedia(srcs...)
			if got := m.Verbs(); strings.Join(got, ",") != strings.Join(vc.ExpectVerbs, ",") {
				t.Errorf("verbs = %v, want %v", got, vc.ExpectVerbs)
			}
			var names []string
			for _, tl := range m.Tools() {
				names = append(names, tl.Name)
			}
			if strings.Join(names, ",") != strings.Join(vc.ExpectTools, ",") {
				t.Errorf("tools = %v, want %v", names, vc.ExpectTools)
			}
			_, err := m.Generate(context.Background(), vc.Unsupported, map[string]any{}, MediaCall{})
			if err == nil || err.Error() != vc.Error {
				t.Errorf("error = %v, want %q", err, vc.Error)
			}
		})
	}
}
