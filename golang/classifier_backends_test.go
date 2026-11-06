package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func loadBackendFixture(t *testing.T, name string, v any) {
	t.Helper()
	b, err := os.ReadFile("../examples/judge/backends/" + name)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, v); err != nil {
		t.Fatal(err)
	}
}

// fixtureQuestions turns a wire question map into Questions (raw for unknown types).
func fixtureQuestions(t *testing.T, m map[string]map[string]any) map[string]Question {
	t.Helper()
	out := map[string]Question{}
	for k, w := range m {
		ins, _ := w["instructions"].(string)
		switch w["type"] {
		case "noul":
			q := NoulQuestion{Instructions: ins}
			if c, ok := w["criteria"].(map[string]any); ok {
				q.Criteria = &NoulCriteria{True: c["true"].(string), False: c["false"].(string)}
			}
			out[k] = q
		case "choice":
			c := map[string]string{}
			for o, d := range w["criteria"].(map[string]any) {
				c[o] = d.(string)
			}
			out[k] = ChoiceQuestion{Instructions: ins, Criteria: c}
		case "score":
			var lv []string
			for _, l := range w["criteria"].([]any) {
				lv = append(lv, l.(string))
			}
			out[k] = ScoreQuestion{Instructions: ins, Criteria: lv}
		default:
			out[k] = RawQuestion{Type: w["type"].(string), Instructions: ins, Criteria: w["criteria"]}
		}
	}
	return out
}

// fixtureState converts {"$image":…}/{"$text":…} markers into ContentParts.
func fixtureState(v any) any {
	switch x := v.(type) {
	case map[string]any:
		if im, ok := x["$image"].(map[string]any); ok {
			return ContentPart{Type: PartImage, MimeType: im["mimeType"].(string), Data: im["data"].(string)}
		}
		if s, ok := x["$text"].(string); ok {
			return Text(s)
		}
		out := map[string]any{}
		for k, e := range x {
			out[k] = fixtureState(e)
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = fixtureState(e)
		}
		return out
	}
	return v
}

type fakeSystemOne struct {
	srv    *httptest.Server
	mu     sync.Mutex
	paths  []string
	bodies []string
	hdrs   []http.Header
	reply  func(n int, body string) (int, string)
	n      atomic.Int32
}

func newFakeSystemOne(t *testing.T, reply func(n int, body string) (int, string)) *fakeSystemOne {
	f := &fakeSystemOne{reply: reply}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		f.paths = append(f.paths, r.URL.Path)
		f.bodies = append(f.bodies, string(b))
		f.hdrs = append(f.hdrs, r.Header.Clone())
		f.mu.Unlock()
		st, out := f.reply(int(f.n.Add(1)), string(b))
		w.WriteHeader(st)
		_, _ = io.WriteString(w, out)
	}))
	t.Cleanup(f.srv.Close)
	return f
}

const noulReply = `{"model":"jev-1.14.0","answers":{"q":{"type":"noul","noul":0.91}},"usage":{"input_tokens":3,"output_tokens":1}}`

func TestBackendPresetsFixture(t *testing.T) {
	var fx struct {
		Backends map[string]struct {
			BaseURL        *string         `json:"baseUrl"`
			Model          string          `json:"model"`
			APIKeyEnv      string          `json:"apiKeyEnv"`
			Wire           string          `json:"wire"`
			ImagesHeader   bool            `json:"imagesHeader"`
			SupportsImages map[string]bool `json:"supportsImages"`
		}
		UnknownBackendError string `json:"unknownBackendError"`
		CustomError         string `json:"customError"`
	}
	loadBackendFixture(t, "presets.json", &fx)
	for name, want := range fx.Backends {
		p, ok := ClassifierBackends[ClassifierBackend(name)]
		if !ok {
			t.Fatalf("missing backend %s", name)
		}
		base := ""
		if want.BaseURL != nil {
			base = *want.BaseURL
		}
		if p.BaseURL != base || p.Model != want.Model || p.APIKeyEnv != want.APIKeyEnv || p.Wire != want.Wire || p.ImagesHeader != want.ImagesHeader {
			t.Errorf("%s: preset %+v != fixture", name, p)
		}
		for m, img := range want.SupportsImages {
			if p.Supports(m).Images != img {
				t.Errorf("%s %s: images %v", name, m, !img)
			}
		}
	}
	if len(ClassifierBackends) != len(fx.Backends) {
		t.Errorf("table has %d rows, fixture %d", len(ClassifierBackends), len(fx.Backends))
	}
	if _, err := CreateClassifier(ClassifierOptions{Backend: "nope"}); err == nil || err.Error() != fx.UnknownBackendError {
		t.Errorf("unknown backend: %v", err)
	}
	if _, err := CreateClassifier(ClassifierOptions{Backend: BackendCustom, BaseURL: "http://x"}); err == nil || err.Error() != fx.CustomError {
		t.Errorf("custom: %v", err)
	}
	if _, err := CreateClassifier(ClassifierOptions{Backend: BackendCustom, BaseURL: "http://x", Supports: &ClassifierSupports{}}); err != nil {
		t.Errorf("custom with declarations: %v", err)
	}
}

func TestImagesFixture(t *testing.T) {
	var fx struct {
		Questions map[string]map[string]any `json:"questions"`
		Cases     []struct {
			Name     string              `json:"name"`
			Backend  string              `json:"backend"`
			Model    string              `json:"model"`
			Supports *ClassifierSupports `json:"supports"`
			State    any                 `json:"state"`
			Expect   struct {
				URL          string `json:"url"`
				Body         string `json:"body"`
				ImagesHeader bool   `json:"imagesHeader"`
				Unsupported  bool   `json:"unsupported"`
				Error        string `json:"error"`
				ErrorPrefix  string `json:"errorPrefix"`
			} `json:"expect"`
		} `json:"cases"`
	}
	loadBackendFixture(t, "images.json", &fx)
	qs := fixtureQuestions(t, fx.Questions)
	for _, c := range fx.Cases {
		t.Run(c.Name, func(t *testing.T) {
			f := newFakeSystemOne(t, func(int, string) (int, string) {
				if c.Backend == "cloudflare" {
					return 200, `{"success":true,"result":` + noulReply + `}`
				}
				return 200, noulReply
			})
			preset := ClassifierBackends[ClassifierBackend(c.Backend)]
			// Point the preset's host at the fake, keeping its path and template.
			base := f.srv.URL + "/v1"
			if c.Backend == "cloudflare" {
				base = f.srv.URL + "/accounts/${TN_TEST_ACCOUNT}/ai/run"
				t.Setenv("TN_TEST_ACCOUNT", "acct123")
				if c.Expect.URL != "" && c.Expect.URL != preset.BaseURL {
					t.Fatalf("url %s != preset %s", c.Expect.URL, preset.BaseURL)
				}
			}
			cl, err := CreateClassifier(ClassifierOptions{Backend: ClassifierBackend(c.Backend), BaseURL: base,
				Model: c.Model, Supports: c.Supports, Retries: -1})
			if err != nil {
				t.Fatal(err)
			}
			_, err = cl.Evaluate(context.Background(), fixtureState(c.State), qs)
			switch {
			case c.Expect.Unsupported:
				var u *ErrClassifierUnsupported
				if !errors.As(err, &u) || err.Error() != c.Expect.Error {
					t.Fatalf("want unsupported %q, got %v", c.Expect.Error, err)
				}
			case c.Expect.ErrorPrefix != "":
				if err == nil || !strings.HasPrefix(err.Error(), c.Expect.ErrorPrefix) {
					t.Fatalf("want prefix %q, got %v", c.Expect.ErrorPrefix, err)
				}
			default:
				if err != nil {
					t.Fatal(err)
				}
				if f.bodies[0] != c.Expect.Body {
					t.Fatalf("body\n got %s\nwant %s", f.bodies[0], c.Expect.Body)
				}
				if got := f.hdrs[0].Get(ImagesHeader) == "true"; got != c.Expect.ImagesHeader {
					t.Fatalf("images header %v", got)
				}
				if c.Backend == "cloudflare" && f.paths[0] != "/accounts/acct123/ai/run" {
					t.Fatalf("path %s", f.paths[0])
				}
				return
			}
			if len(f.bodies) != 0 {
				t.Fatal("a request was sent")
			}
		})
	}
}

func TestCloudflareRepliesFixture(t *testing.T) {
	var fx struct {
		Questions map[string]map[string]any `json:"questions"`
		Cases     []struct {
			Name   string          `json:"name"`
			Reply  json.RawMessage `json:"reply"`
			Expect struct {
				Model string  `json:"model"`
				Noul  float64 `json:"noul"`
				Error string  `json:"error"`
			} `json:"expect"`
		} `json:"cases"`
	}
	loadBackendFixture(t, "cloudflare-replies.json", &fx)
	qs := fixtureQuestions(t, fx.Questions)
	for _, c := range fx.Cases {
		t.Run(c.Name, func(t *testing.T) {
			f := newFakeSystemOne(t, func(int, string) (int, string) { return 200, string(c.Reply) })
			cl, _ := CreateClassifier(ClassifierOptions{Backend: BackendCloudflare, BaseURL: f.srv.URL})
			d, err := cl.Evaluate(context.Background(), "x", qs)
			if c.Expect.Error != "" {
				if err == nil || err.Error() != c.Expect.Error {
					t.Fatalf("want %q got %v", c.Expect.Error, err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			n, _ := d.Noul("q")
			if d.Model != c.Expect.Model || n.Noul != c.Expect.Noul {
				t.Fatalf("got %+v", d)
			}
		})
	}
}

func TestCacheKeysFixture(t *testing.T) {
	var fx struct {
		Cases []struct {
			Name        string                    `json:"name"`
			Endpoint    string                    `json:"endpoint"`
			Model       string                    `json:"model"`
			Served      string                    `json:"served"`
			State       any                       `json:"state"`
			Questions   map[string]map[string]any `json:"questions"`
			Assets      map[string]any            `json:"assets"`
			ModelKey    string                    `json:"modelKey"`
			DecisionKey string                    `json:"decisionKey"`
		} `json:"cases"`
	}
	loadBackendFixture(t, "cache-keys.json", &fx)
	for _, c := range fx.Cases {
		mk, _ := ClassifierModelCacheKey(c.Endpoint, c.Model)
		dk, err := ClassifierDecisionCacheKey(c.Endpoint, c.Served, c.State, c.Assets, fixtureQuestions(t, c.Questions))
		if err != nil || mk != c.ModelKey || dk != c.DecisionKey {
			t.Errorf("%s: %s %s %v", c.Name, mk, dk, err)
		}
	}
}

func TestValidateCasesFixture(t *testing.T) {
	var fx struct {
		Cases []struct {
			Name      string                    `json:"name"`
			Questions map[string]map[string]any `json:"questions"`
			Response  json.RawMessage           `json:"response"`
			Valid     bool                      `json:"valid"`
			Key       string                    `json:"key"`
		} `json:"cases"`
	}
	loadBackendFixture(t, "validate-cases.json", &fx)
	for _, c := range fx.Cases {
		var d Decision
		if err := json.Unmarshal(c.Response, &d); err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		err := ValidateDecision(fixtureQuestions(t, c.Questions), d)
		if (err == nil) != c.Valid {
			t.Errorf("%s: valid=%v err=%v", c.Name, c.Valid, err)
			continue
		}
		if err != nil && (!strings.HasPrefix(err.Error(), "classifier: invalid decision: ") || !strings.Contains(err.Error(), `"`+c.Key+`"`)) {
			t.Errorf("%s: %v does not name %q", c.Name, err, c.Key)
		}
	}
}

func TestThresholdsFixture(t *testing.T) {
	var fx struct {
		Default Thresholds `json:"default"`
		Cases   []struct {
			Name       string          `json:"name"`
			Answer     json.RawMessage `json:"answer"`
			Band       string          `json:"band"`
			BandsBand  string          `json:"bandsBand"`
			Thresholds *Thresholds     `json:"thresholds"`
		} `json:"cases"`
	}
	loadBackendFixture(t, "thresholds-cases.json", &fx)
	if fx.Default != DefaultThresholds {
		t.Fatalf("default %+v", DefaultThresholds)
	}
	for _, c := range fx.Cases {
		var d Decision
		if err := json.Unmarshal([]byte(`{"answers":{"a":`+string(c.Answer)+`}}`), &d); err != nil {
			t.Fatal(err)
		}
		th := DefaultThresholds
		if c.Thresholds != nil {
			th = *c.Thresholds
		}
		if got := th.Band(d.Answers["a"]); got != c.Band {
			t.Errorf("%s: thresholds %s want %s", c.Name, got, c.Band)
		}
		if got := DefaultBands.Band(d.Answers["a"]); got != c.BandsBand {
			t.Errorf("%s: bands %s want %s", c.Name, got, c.BandsBand)
		}
	}
}

func TestStateBuilderAndCache(t *testing.T) {
	f := newFakeSystemOne(t, func(int, string) (int, string) { return 200, noulReply })
	var events []MetricEvent
	var mu sync.Mutex
	cl, _ := CreateClassifier(ClassifierOptions{BaseURL: f.srv.URL, Cache: NewMemoryCache(time.Hour, 100),
		OnMetric: func(e MetricEvent) { mu.Lock(); events = append(events, e); mu.Unlock() }})
	ctx := context.Background()
	q := Noul("q", "Is the object red?")
	d1, err := cl.State("a red mug").Evaluate(ctx, q)
	if err != nil || d1.Cached {
		t.Fatal(err, d1.Cached)
	}
	d2, err := cl.State("a red mug").Evaluate(ctx, q)
	if err != nil || !d2.Cached || len(f.bodies) != 1 {
		t.Fatalf("second call: cached=%v requests=%d err=%v", d2.Cached, len(f.bodies), err)
	}
	if !events[1].Cached || events[1].Questions != 1 || events[1].QuestionsHash == "" {
		t.Fatalf("event %+v", events[1])
	}
	// Different state -> miss.
	if _, err := cl.State("a blue mug").Evaluate(ctx, q); err != nil || len(f.bodies) != 2 {
		t.Fatal("different state must miss")
	}
	if _, err := cl.State("x").Evaluate(ctx, q, q); err == nil {
		t.Fatal("duplicate name must fail")
	}
}

func TestInvalidRepliesAreNotCached(t *testing.T) {
	bad := `{"model":"m","answers":{"q":{"type":"noul","noul":1.5}}}`
	f := newFakeSystemOne(t, func(int, string) (int, string) { return 200, bad })
	cl, _ := CreateClassifier(ClassifierOptions{BaseURL: f.srv.URL, Cache: NewMemoryCache(0, 0)})
	qs := map[string]Question{"q": NoulQuestion{Instructions: "x"}}
	for i := 0; i < 2; i++ { // plain Evaluate: returned as before, never cached
		if _, err := cl.Evaluate(context.Background(), "s", qs); err != nil {
			t.Fatal(err)
		}
	}
	if len(f.bodies) != 2 {
		t.Fatalf("invalid reply was cached: %d requests", len(f.bodies))
	}
	_, err := cl.State("s").Evaluate(context.Background(), Noul("q", "x"))
	if err == nil || !strings.HasPrefix(err.Error(), `classifier: invalid decision: answer "q"`) {
		t.Fatalf("builder must fail closed: %v", err)
	}
}

func TestCacheFollowsServedModel(t *testing.T) {
	served := "jev-1.13.0"
	f := newFakeSystemOne(t, func(int, string) (int, string) {
		return 200, `{"model":"` + served + `","answers":{"q":{"type":"noul","noul":0.5}}}`
	})
	cache := NewMemoryCache(0, 0)
	cl, _ := CreateClassifier(ClassifierOptions{BaseURL: f.srv.URL, Cache: cache})
	ctx := context.Background()
	_, _ = cl.State("s").Evaluate(ctx, Noul("q", "x"))
	mk, _ := ClassifierModelCacheKey(f.srv.URL, DefaultClassifierModel)
	if v, _ := cache.Get(mk); v != "jev-1.13.0" {
		t.Fatalf("model key holds %q", v)
	}
	// The alias moves: a live call on another state re-points the model key, and
	// the old state's answer (keyed on 1.13) is no longer found.
	served = "jev-1.14.0"
	_, _ = cl.State("other").Evaluate(ctx, Noul("q", "x"))
	d, _ := cl.State("s").Evaluate(ctx, Noul("q", "x"))
	if d.Cached || len(f.bodies) != 3 || d.Model != "jev-1.14.0" {
		t.Fatalf("stale alias answer served: cached=%v model=%s", d.Cached, d.Model)
	}
}

func TestStatesBatchPerItem(t *testing.T) {
	f := newFakeSystemOne(t, func(_ int, body string) (int, string) {
		if strings.Contains(body, `"state":"bad"`) {
			return 400, `{"error":"nope"}`
		}
		return 200, noulReply
	})
	cl, _ := CreateClassifier(ClassifierOptions{BaseURL: f.srv.URL})
	rs, err := cl.States("a", "bad", "c").Evaluate(context.Background(), Noul("q", "Is the object red?"))
	if err != nil || len(rs) != 3 {
		t.Fatal(err)
	}
	if rs[0].Err != nil || rs[2].Err != nil || rs[1].Err == nil || rs[0].Decision.Model != "jev-1.14.0" {
		t.Fatalf("%+v", rs)
	}
	empty, err := cl.States().Evaluate(context.Background(), Noul("q", "x"))
	if err != nil || len(empty) != 0 {
		t.Fatal("empty batch")
	}
}

func TestRawQuestionAndAnswer(t *testing.T) {
	f := newFakeSystemOne(t, func(int, string) (int, string) {
		return 200, `{"model":"jev-1.14.0","answers":{"spot":{"type":"box","box":[1,2,3,4]}}}`
	})
	cl, _ := CreateClassifier(ClassifierOptions{BaseURL: f.srv.URL})
	d, err := cl.State("s").Evaluate(context.Background(), Raw("spot", "box", "Where is the mug?", nil))
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"model":"jev-latest","questions":{"spot":{"instructions":"Where is the mug?","type":"box"}},"state":"s"}`; f.bodies[0] != want {
		t.Fatalf("body %s", f.bodies[0])
	}
	ra, ok := d.Answers["spot"].(RawAnswer)
	if !ok || ra.Type != "box" || ra.Value["box"] == nil {
		t.Fatalf("%#v", d.Answers["spot"])
	}
	if _, err := Questions(Raw("x", "", "y", nil)); err != nil {
		t.Fatal(err)
	}
	if _, err := cl.Evaluate(context.Background(), "s", map[string]Question{"x": RawQuestion{}}); err == nil {
		t.Fatal("empty raw type must fail")
	}
}

func TestChunkQuestions(t *testing.T) {
	qs := map[string]Question{"c": NoulQuestion{}, "a": NoulQuestion{}, "b": NoulQuestion{}}
	ch, err := ChunkQuestions(qs, 2)
	if err != nil || len(ch) != 2 || ch[0]["a"] == nil || ch[0]["b"] == nil || ch[1]["c"] == nil {
		t.Fatalf("%v %v", ch, err)
	}
	if _, err := ChunkQuestions(qs, 0); err == nil {
		t.Fatal("size 0")
	}
}

func TestMetricHashMatchesFixture(t *testing.T) {
	var fx struct {
		Request struct {
			Model     string                    `json:"model"`
			Questions map[string]map[string]any `json:"questions"`
			State     any                       `json:"state"`
		} `json:"request"`
		Sha string `json:"canonicalSha256"`
	}
	b, _ := os.ReadFile("../examples/judge/base.json")
	_ = json.Unmarshal(b, &fx)
	var ev MetricEvent
	cl, _ := CreateClassifier(ClassifierOptions{Style: StyleCustom, Model: fx.Request.Model,
		Evaluate: func(context.Context, any, map[string]Question) (Decision, error) { return Decision{Model: "m"}, nil },
		OnMetric: func(e MetricEvent) { ev = e }})
	if _, err := cl.Evaluate(context.Background(), fx.Request.State, fixtureQuestions(t, fx.Request.Questions)); err != nil {
		t.Fatal(err)
	}
	if ev.QuestionsHash != fx.Sha || ev.Questions != 3 || ev.Cached {
		t.Fatalf("%+v want %s", ev, fx.Sha)
	}
}

func TestCloudflareErrorKeepsAccountTemplate(t *testing.T) {
	f := newFakeSystemOne(t, func(int, string) (int, string) { return 500, "boom" })
	t.Setenv("TN_TEST_ACCOUNT", "secretacct")
	cl, _ := CreateClassifier(ClassifierOptions{Backend: BackendCloudflare, BaseURL: f.srv.URL + "/accounts/${TN_TEST_ACCOUNT}/ai/run", Retries: -1})
	_, err := cl.Evaluate(context.Background(), "x", map[string]Question{"q": NoulQuestion{Instructions: "y"}})
	if err == nil || strings.Contains(err.Error(), "secretacct") || !strings.Contains(err.Error(), "${TN_TEST_ACCOUNT}") {
		t.Fatalf("%v", err)
	}
}
