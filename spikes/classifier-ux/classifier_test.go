package classifierux

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"image"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// fake answers System One (and, with cf, the Cloudflare envelope) and records
// every request it saw.
type fake struct {
	cf   bool
	mu   sync.Mutex
	reqs []seen
	fail string // a state value that gets a 400
}

type seen struct {
	path, imgHeader string
	body            map[string]any
}

func (f *fake) handler(w http.ResponseWriter, r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	var body map[string]any
	_ = json.Unmarshal(b, &body)
	f.mu.Lock()
	f.reqs = append(f.reqs, seen{r.URL.Path, r.Header.Get("TypeSafe-Danger-Zone-Images"), body})
	f.mu.Unlock()
	core := body
	if f.cf {
		core = body["input"].(map[string]any)
	}
	if f.fail != "" && core["state"] == f.fail {
		w.WriteHeader(400)
		return
	}
	ans := []string{}
	for k, q := range core["questions"].(map[string]any) {
		switch q.(map[string]any)["type"] {
		case "noul":
			ans = append(ans, `"`+k+`":{"type":"noul","noul":0.9}`)
		case "choice":
			ans = append(ans, `"`+k+`":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"tech":0.1},"confidence":0.9}`)
		}
	}
	out := `{"model":"jev-fake","answers":{` + strings.Join(ans, ",") + `},"usage":{"input_tokens":5,"output_tokens":0}}`
	if f.cf {
		out = `{"success":true,"result":{"state":"Completed","result":` + out + `}}`
	}
	_, _ = io.WriteString(w, out)
}

func (f *fake) n() int { f.mu.Lock(); defer f.mu.Unlock(); return len(f.reqs) }
func (f *fake) last() seen {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.reqs[len(f.reqs)-1]
}

func client(t *testing.T, o Options, cf bool) (*Client, *fake) {
	t.Helper()
	f := &fake{cf: cf}
	srv := httptest.NewServer(http.HandlerFunc(f.handler))
	t.Cleanup(srv.Close)
	if cf {
		o.BaseURL = srv.URL + "/ai/run"
	} else if o.BaseURL == "" {
		o.BaseURL = srv.URL + "/v1"
	}
	t.Setenv("FAKE_KEY", "YOUR_KEY_HERE")
	o.APIKeyEnv = "FAKE_KEY"
	o.Retries = -1
	c, err := New(o)
	if err != nil {
		t.Fatal(err)
	}
	return c, f
}

func pngPart(t *testing.T, w, h int) tn.ContentPart {
	var b bytes.Buffer
	_ = png.Encode(&b, image.NewRGBA(image.Rect(0, 0, w, h)))
	return tn.Bytes(b.Bytes(), "image/png")
}

var (
	ctx    = context.Background()
	refund = tn.Noul("refund", "Is the customer asking for money back?")
	team   = tn.Choice("team", "Which team?", map[string]string{"billing": "Charges", "tech": "Bugs"})
)

func TestTextStateManyQuestionsOneRequest(t *testing.T) {
	c, f := client(t, Options{Backend: TypeSafe}, false)
	r, err := c.State("I was charged twice").Evaluate(ctx, refund, team)
	if err != nil {
		t.Fatal(err)
	}
	if f.n() != 1 || f.last().path != "/v1/systemone" || f.last().imgHeader != "" || f.last().body["assets"] != nil {
		t.Fatalf("wire %+v", f.last())
	}
	if a, _ := r.Noul("refund"); a.Noul != 0.9 {
		t.Fatal(r)
	}
	if a, _ := r.Choice("team"); a.Choice != "billing" {
		t.Fatal(r)
	}
}

func TestImageLiftedIntoAssetsWithHeader(t *testing.T) {
	c, f := client(t, Options{Backend: TypeSafe}, false)
	_, err := c.State(map[string]any{"note": "listing photo", "photo": pngPart(t, 320, 320)}).
		Evaluate(ctx, tn.Noul("broken", "Is the mug in `photo` broken?"))
	if err != nil {
		t.Fatal(err)
	}
	s := f.last()
	st := s.body["state"].(map[string]any)
	as := s.body["assets"].(map[string]any)["photo"].(map[string]any)
	if s.imgHeader != "true" || st["photo"] != "photo" || st["note"] != "listing photo" ||
		as["type"] != "image" || as["format"] != "png" || as["data_base64"] == "" {
		t.Fatalf("wire %+v", s)
	}
}

func TestUnsupportedImageFailsBeforeTheWire(t *testing.T) {
	for _, o := range []Options{{Backend: Vercel}, {Backend: Cloudflare}} {
		c, f := client(t, o, o.Backend == Cloudflare)
		_, err := c.State([]tn.ContentPart{tn.Text("look"), pngPart(t, 320, 320)}).Evaluate(ctx, refund)
		var u *ErrUnsupported
		if !errors.As(err, &u) || u.Feature != "images" || f.n() != 0 {
			t.Fatalf("%s: err=%v calls=%d", o.Backend, err, f.n())
		}
	}
}

func TestCloudflareEnvelopeAndClefImages(t *testing.T) {
	c, f := client(t, Options{Backend: Cloudflare}, true)
	if _, err := c.State("hello").Evaluate(ctx, refund); err != nil {
		t.Fatal(err)
	}
	if f.last().body["model"] != "typesafe/jev" || f.last().body["input"] == nil {
		t.Fatalf("%+v", f.last().body)
	}
	c, f = client(t, Options{Backend: Cloudflare, Model: "@cf/cloudflare/clef"}, true)
	if !c.Supports().Images {
		t.Fatal("clef should support images")
	}
	if _, err := c.State(map[string]any{"photo": pngPart(t, 300, 300)}).Evaluate(ctx, refund); err != nil {
		t.Fatal(err)
	}
	imgs := f.last().body["input"].(map[string]any)["images"].([]any)
	if len(imgs) != 1 || !strings.HasPrefix(imgs[0].(string), "data:image/png;base64,") {
		t.Fatalf("%v", imgs)
	}
}

func TestImageLimitsCheckedClientSide(t *testing.T) {
	c, f := client(t, Options{Backend: TypeSafe}, false)
	_, err := c.State(pngPart(t, 10, 10)).Evaluate(ctx, refund)
	if err == nil || !strings.Contains(err.Error(), "280..1568") || f.n() != 0 {
		t.Fatalf("%v calls=%d", err, f.n())
	}
	_, err = c.State(tn.Bytes([]byte("GIF89a"), "image/gif")).Evaluate(ctx, refund)
	var u *ErrUnsupported
	if !errors.As(err, &u) || f.n() != 0 {
		t.Fatalf("%v", err)
	}
}

func TestCacheIsPerQuestion(t *testing.T) {
	cache := NewMemoryCache(time.Hour, 100)
	c, f := client(t, Options{Backend: TypeSafe, Cache: cache}, false)
	st := c.State("I was charged twice")
	if _, err := st.Evaluate(ctx, refund); err != nil {
		t.Fatal(err)
	}
	r, err := st.Evaluate(ctx, refund)
	if err != nil || f.n() != 1 || len(r.Cached) != 1 {
		t.Fatalf("repeat: calls=%d cached=%v %v", f.n(), r.Cached, err)
	}
	r, _ = st.Evaluate(ctx, refund, team) // only the new question goes out
	sent := f.last().body["questions"].(map[string]any)
	if f.n() != 2 || len(sent) != 1 || sent["team"] == nil || len(r.Answers) != 2 {
		t.Fatalf("calls=%d sent=%v answers=%d", f.n(), sent, len(r.Answers))
	}
	// renaming a question is the same question: the name is never sent
	r, _ = st.Evaluate(ctx, tn.Noul("wants_money", "Is the customer asking for money back?"))
	if f.n() != 2 || len(r.Cached) != 1 {
		t.Fatalf("renamed: calls=%d", f.n())
	}
	// a different state is a miss
	_, _ = c.State("other").Evaluate(ctx, refund)
	if f.n() != 3 {
		t.Fatalf("calls=%d", f.n())
	}
}

func TestMemoryCacheTTLAndCap(t *testing.T) {
	m := NewMemoryCache(time.Minute, 2)
	now := time.Unix(0, 0)
	m.now = func() time.Time { return now }
	m.Set("a", tn.NoulAnswer{Noul: 1})
	now = now.Add(time.Second)
	m.Set("b", tn.NoulAnswer{})
	now = now.Add(time.Second)
	m.Set("c", tn.NoulAnswer{})
	if _, ok := m.Get("a"); ok {
		t.Fatal("oldest should be evicted")
	}
	now = now.Add(2 * time.Minute)
	if _, ok := m.Get("c"); ok {
		t.Fatal("should expire")
	}
}

func TestBatchFansOutInOrderAndNamesLowestFailure(t *testing.T) {
	c, f := client(t, Options{Backend: OpenRouter, Concurrency: 2}, false)
	rs, err := c.States("a", "b", "c", "d").Evaluate(ctx, refund)
	if err != nil || len(rs) != 4 || f.n() != 4 {
		t.Fatalf("%v %d", err, f.n())
	}
	f.fail = "c"
	_, err = c.States("a", "b", "c", "d").Evaluate(ctx, refund)
	if err == nil || !strings.Contains(err.Error(), "state 2") {
		t.Fatal(err)
	}
}

func TestVercelPathAndCustomNeedsSupports(t *testing.T) {
	c, f := client(t, Options{Backend: Vercel}, false)
	if _, err := c.State("x").Evaluate(ctx, refund); err != nil || f.last().path != "/v1/systemone" {
		t.Fatalf("%v %+v", err, f.last())
	}
	if _, err := New(Options{Backend: Custom, BaseURL: "http://127.0.0.1:21160/v1"}); err == nil {
		t.Fatal("custom without Supports must fail")
	}
	c, _ = client(t, Options{Backend: Custom, BaseURL: "", Supports: &Supports{Images: true}}, false)
	if !c.Supports().Images {
		t.Fatal("declared supports")
	}
}

func TestInvalidReplyFailsClosed(t *testing.T) {
	q := map[string]tn.Question{"team": tn.ChoiceQuestion{Instructions: "?", Criteria: map[string]string{"a": "A"}}}
	d := tn.Decision{Answers: map[string]tn.DecisionAnswer{"team": tn.ChoiceAnswer{Choice: "zzz", Probabilities: map[string]float64{"a": 1}}}}
	if err := Validate(q, d); err == nil || !strings.Contains(err.Error(), "not offered") {
		t.Fatal(err)
	}
}

func TestKeyNeverInError(t *testing.T) {
	f := &fake{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(401) }))
	defer srv.Close()
	_ = f
	t.Setenv("FAKE_KEY", "YOUR_KEY_HERE")
	c, _ := New(Options{Backend: TypeSafe, BaseURL: srv.URL + "/v1", APIKeyEnv: "FAKE_KEY", Retries: -1})
	_, err := c.State("x").Evaluate(ctx, refund)
	if err == nil || strings.Contains(err.Error(), "YOUR_KEY_HERE") {
		t.Fatal(err)
	}
}
