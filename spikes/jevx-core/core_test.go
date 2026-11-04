package jevxcore

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// fakeSystemOne answers every question with the canned answer for its type and
// records the last request so tests can check what went over the wire.
type fakeSystemOne struct {
	noul, choice, score string // answer JSON per type
	status              []int  // statuses to return first (e.g. 429)
	calls               atomic.Int32
	lastBody, lastAuth  string
	lastPath            string
}

func (f *fakeSystemOne) handler(w http.ResponseWriter, r *http.Request) {
	n := int(f.calls.Add(1)) - 1
	b, _ := io.ReadAll(r.Body)
	f.lastBody, f.lastAuth, f.lastPath = string(b), r.Header.Get("Authorization"), r.URL.Path
	if n < len(f.status) {
		w.Header().Set("Retry-After", "0")
		w.WriteHeader(f.status[n])
		return
	}
	var req struct {
		Questions map[string]struct{ Type string } `json:"questions"`
	}
	_ = json.Unmarshal(b, &req)
	ans := []string{}
	for k, q := range req.Questions {
		a := map[string]string{"noul": f.noul, "choice": f.choice, "score": f.score}[q.Type]
		ans = append(ans, `"`+k+`":`+a)
	}
	_, _ = io.WriteString(w, `{"model":"jev-fake-1","answers":{`+strings.Join(ans, ",")+`},"usage":{"input_tokens":3,"output_tokens":1}}`)
}

func setup(t *testing.T, f *fakeSystemOne) Core {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(f.handler))
	t.Cleanup(srv.Close)
	t.Setenv("JEVX_SPIKE_FAKE_KEY", "YOUR_KEY_HERE")
	return Core{Reg: &Registry{Default: "local", Profiles: map[string]Profile{
		"local":  {URL: srv.URL + "/v1/systemone", Model: "openjevx", APIKeyEnv: "JEVX_SPIKE_FAKE_KEY", Retries: 3},
		"strict": {URL: srv.URL + "/v1/systemone", Model: "openjevx", APIKeyEnv: "JEVX_SPIKE_FAKE_KEY", Thresh: &Thresholds{Yes: 0.99, No: 0.01, MinConfidence: 0.95}},
	}}}
}

func TestIsThresholdsInclusiveLikeJevx(t *testing.T) {
	for _, tc := range []struct {
		p    string
		want string
		code int
	}{{"0.8", "yes", 0}, {"0.97", "yes", 0}, {"0.2", "no", 1}, {"0.5", "unsure", 3}} {
		f := &fakeSystemOne{noul: `{"type":"noul","noul":` + tc.p + `}`}
		c := setup(t, f)
		v, err := c.Is(context.Background(), "", Item{Text: "Checkout is down"}, "Is this urgent?")
		if err != nil {
			t.Fatal(err)
		}
		if v.Answer != tc.want || v.ExitCode() != tc.code || v.Model != "jev-fake-1" || v.Profile != "local" {
			t.Fatalf("p=%s: got %+v exit %d", tc.p, v, v.ExitCode())
		}
		if f.lastPath != "/v1/systemone" || f.lastAuth != "Bearer YOUR_KEY_HERE" {
			t.Fatalf("wire: path %q auth set=%v", f.lastPath, f.lastAuth != "")
		}
		if !strings.Contains(f.lastBody, `"model":"openjevx"`) || !strings.Contains(f.lastBody, "Checkout is down") {
			t.Fatalf("body %s", f.lastBody)
		}
	}
}

// The boundary case that makes T1 a real gap: toolnexus DefaultBands would
// call 0.70 "uncertain" (exclusive) — jevx with yes=0.7 says yes.
func TestBoundaryDiffersFromToolnexusBands(t *testing.T) {
	if b := tn.DefaultBands.Band(tn.NoulAnswer{Noul: 0.70}); b != tn.BandUncertain {
		t.Fatalf("toolnexus band at 0.70 = %s", b)
	}
	f := &fakeSystemOne{noul: `{"type":"noul","noul":0.7}`}
	c := setup(t, f)
	p := c.Reg.Profiles["local"]
	p.Thresh = &Thresholds{Yes: 0.7, No: 0.3, MinConfidence: 0.6}
	c.Reg.Profiles["local"] = p
	v, _ := c.Is(context.Background(), "", Item{Text: "x"}, "q?")
	if v.Answer != "yes" {
		t.Fatalf("got %s", v.Answer)
	}
}

func TestPickAndRateWithProfileSelection(t *testing.T) {
	f := &fakeSystemOne{
		choice: `{"type":"choice","choice":"billing","probabilities":{"web":0.02,"api":0.07,"billing":0.91},"confidence":0.91}`,
		score:  `{"type":"score","score":1.8,"legend":{"0":"low","1":"medium","2":"high"},"probabilities":{"0":0.05,"1":0.15,"2":0.8},"confidence":0.9}`,
	}
	c := setup(t, f)
	opts := map[string]string{"web": "frontend", "api": "backend", "billing": "money"}
	v, err := c.Pick(context.Background(), "", Item{Text: "I was charged twice"}, "Which team?", opts)
	if err != nil || v.Answer != "billing" {
		t.Fatalf("pick %+v %v", v, err)
	}
	v, err = c.Pick(context.Background(), "strict", Item{Text: "I was charged twice"}, "Which team?", opts)
	if err != nil || v.Answer != "unsure" || v.Profile != "strict" {
		t.Fatalf("strict pick %+v %v", v, err)
	}
	v, err = c.Rate(context.Background(), "", Item{Text: "db down"}, "How severe?", []string{"low", "medium", "high"})
	if err != nil || v.Answer != "high" {
		t.Fatalf("rate %+v %v", v, err)
	}
}

func TestRetriesOn429(t *testing.T) {
	f := &fakeSystemOne{noul: `{"type":"noul","noul":0.9}`, status: []int{429, 503}}
	c := setup(t, f)
	v, err := c.Is(context.Background(), "", Item{Text: "x"}, "q?")
	if err != nil || v.Answer != "yes" || f.calls.Load() != 3 {
		t.Fatalf("%+v %v calls=%d", v, err, f.calls.Load())
	}
}

func TestFailClosedOnBadReply(t *testing.T) {
	for name, f := range map[string]*fakeSystemOne{
		"unoffered choice": {choice: `{"type":"choice","choice":"sales","probabilities":{"web":0.5,"api":0.5},"confidence":0.9}`},
		"bad sum":          {choice: `{"type":"choice","choice":"web","probabilities":{"web":0.9,"api":0.9},"confidence":0.9}`},
		"noul out of range": {noul: `{"type":"noul","noul":1.4}`},
	} {
		c := setup(t, f)
		var err error
		if f.noul != "" {
			_, err = c.Is(context.Background(), "", Item{Text: "x"}, "q?")
		} else {
			_, err = c.Pick(context.Background(), "", Item{Text: "x"}, "q?", map[string]string{"web": "w", "api": "a"})
		}
		if err == nil || !strings.Contains(err.Error(), "invalid answer") {
			t.Fatalf("%s: want invalid answer, got %v", name, err)
		}
	}
}

func TestErrorNeverCarriesKey(t *testing.T) {
	f := &fakeSystemOne{status: []int{401, 401, 401, 401}}
	c := setup(t, f)
	_, err := c.Is(context.Background(), "", Item{Text: "x"}, "q?")
	if err == nil || strings.Contains(err.Error(), "YOUR_KEY_HERE") {
		t.Fatalf("err %v", err)
	}
}

func TestDefaultJevFallsBackToLLMWithoutKey(t *testing.T) {
	t.Setenv("TYPESAFE_API_KEY", "")
	r := DefaultRegistry(nil)
	if _, _, err := r.Resolve(""); !errors.Is(err, ErrNeedKey) {
		t.Fatalf("no llm client: want ErrNeedKey, got %v", err)
	}
	r = DefaultRegistry(tn.CreateClient(tn.ClientOptions{BaseURL: "http://127.0.0.1:1", Model: "m", APIKey: "YOUR_KEY_HERE"}))
	name, p, err := r.Resolve("")
	if err != nil || name != "llm" || p.Style != tn.StyleLLM {
		t.Fatalf("got %s %+v %v", name, p, err)
	}
	t.Setenv("TYPESAFE_API_KEY", "YOUR_KEY_HERE")
	if name, _, _ := r.Resolve(""); name != "jev" {
		t.Fatalf("with key: %s", name)
	}
}

func TestImageItemFailsLoudly(t *testing.T) {
	c := setup(t, &fakeSystemOne{})
	img := tn.Bytes([]byte{0x89, 'P', 'N', 'G'}, "image/png")
	if _, err := c.Is(context.Background(), "", Item{Image: &img}, "Is this a cat?"); !errors.Is(err, ErrImageUnsupported) {
		t.Fatalf("got %v", err)
	}
}
