package toolnexus

// Conformance tests for SPEC.md §8B, run against the SHARED fixtures in
// examples/judge/. The fixtures are the contract; nothing here re-derives what
// correct is believed to look like.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// ---------------------------------------------------------------- fixtures

type judgeFixture struct {
	Name            string          `json:"name"`
	Request         judgeRequest    `json:"request"`
	Canonical       string          `json:"canonical"`
	CanonicalSha256 string          `json:"canonicalSha256"`
	CanonicalBytes  int             `json:"canonicalBytes"`
	Response        json.RawMessage `json:"response"`
	Expect          json.RawMessage `json:"expect"`
	Entries         []judgeFixture  `json:"entries"`
}

type judgeRequest struct {
	Model     string                     `json:"model"`
	State     any                        `json:"state"`
	Questions map[string]json.RawMessage `json:"questions"`
}

func loadJudgeFixture(t *testing.T, name string) judgeFixture {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "examples", "judge", name+".json"))
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	var f judgeFixture
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatalf("parse fixture %s: %v", name, err)
	}
	return f
}

// questions rebuilds the typed questions from a fixture's raw JSON. The
// absent-vs-empty distinction is preserved: a noul with no `criteria` key gets a
// nil pointer, one with `{"true":"","false":""}` gets a non-nil pointer to empty
// strings, and the two produce different bytes.
func (r judgeRequest) questions(t *testing.T) map[string]Question {
	t.Helper()
	out := make(map[string]Question, len(r.Questions))
	for key, raw := range r.Questions {
		var head struct {
			Type         string          `json:"type"`
			Instructions string          `json:"instructions"`
			Criteria     json.RawMessage `json:"criteria"`
		}
		if err := json.Unmarshal(raw, &head); err != nil {
			t.Fatalf("question %q: %v", key, err)
		}
		switch head.Type {
		case "noul":
			q := NoulQuestion{Instructions: head.Instructions}
			if len(head.Criteria) > 0 {
				var c struct{ True, False string }
				if err := json.Unmarshal(head.Criteria, &c); err != nil {
					t.Fatalf("question %q criteria: %v", key, err)
				}
				q.Criteria = &NoulCriteria{True: c.True, False: c.False}
			}
			out[key] = q
		case "choice":
			var c map[string]string
			if err := json.Unmarshal(head.Criteria, &c); err != nil {
				t.Fatalf("question %q criteria: %v", key, err)
			}
			out[key] = ChoiceQuestion{Instructions: head.Instructions, Criteria: c}
		case "score":
			var c []string
			if err := json.Unmarshal(head.Criteria, &c); err != nil {
				t.Fatalf("question %q criteria: %v", key, err)
			}
			out[key] = ScoreQuestion{Instructions: head.Instructions, Criteria: c}
		default:
			t.Fatalf("question %q: unknown type %q", key, head.Type)
		}
	}
	return out
}

// staticFrom builds a `static` classifier answering this fixture's own request.
func staticFrom(t *testing.T, f judgeFixture, opts ClassifierOptions) (*Classifier, map[string]Question) {
	t.Helper()
	qs := f.Request.questions(t)
	opts.Style = StyleStatic
	opts.Model = f.Request.Model
	opts.Decisions = []RecordedDecision{{State: f.Request.State, Questions: qs, Response: f.Response}}
	c, err := CreateClassifier(opts)
	if err != nil {
		t.Fatalf("CreateClassifier: %v", err)
	}
	return c, qs
}

// ---------------------------------------------------------------- the bytes

// The byte-identity claim: every canonical fixture, bytes AND sha256. Go escapes
// <, > and & by default, so this is the test SetEscapeHTML(false) exists for.
func TestClassifierCanonicalBytes(t *testing.T) {
	for _, name := range []string{"base", "hardened", "numbers", "wide", "degenerate", "near-uniform"} {
		t.Run(name, func(t *testing.T) {
			f := loadJudgeFixture(t, name)
			got, err := CanonicalRequest(f.Request.Model, f.Request.questions(t))
			if err != nil {
				t.Fatalf("CanonicalRequest: %v", err)
			}
			if string(got) != f.Canonical {
				t.Fatalf("canonical bytes differ\n got: %s\nwant: %s", got, f.Canonical)
			}
			if len(got) != f.CanonicalBytes {
				t.Fatalf("canonicalBytes = %d, want %d", len(got), f.CanonicalBytes)
			}
			sum := sha256.Sum256(got)
			if h := hex.EncodeToString(sum[:]); h != f.CanonicalSha256 {
				t.Fatalf("sha256 = %s, want %s", h, f.CanonicalSha256)
			}
		})
	}
}

// The three guard entries share one questions payload and one hash, differing
// only in state — which is exactly why the static corpus must key on state too.
func TestClassifierDecisionsFixtureBytes(t *testing.T) {
	f := loadJudgeFixture(t, "decisions")
	if len(f.Entries) != 3 {
		t.Fatalf("expected 3 recorded entries, got %d", len(f.Entries))
	}
	for i, e := range f.Entries {
		got, err := CanonicalRequest(e.Request.Model, e.Request.questions(t))
		if err != nil {
			t.Fatalf("entry %d: %v", i, err)
		}
		if string(got) != e.Canonical {
			t.Fatalf("entry %d canonical bytes differ\n got: %s\nwant: %s", i, got, e.Canonical)
		}
		sum := sha256.Sum256(got)
		if h := hex.EncodeToString(sum[:]); h != e.CanonicalSha256 {
			t.Fatalf("entry %d sha256 = %s, want %s", i, h, e.CanonicalSha256)
		}
	}
}

// An absent criteria and an empty one are DIFFERENT values, and both survive.
func TestClassifierAbsentIsNotEmpty(t *testing.T) {
	absent, err := CanonicalRequest("m", map[string]Question{"q": NoulQuestion{Instructions: "i"}})
	if err != nil {
		t.Fatal(err)
	}
	empty, err := CanonicalRequest("m", map[string]Question{"q": NoulQuestion{Instructions: "i", Criteria: &NoulCriteria{}}})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(absent), "criteria") {
		t.Fatalf("absent criteria leaked into the wire: %s", absent)
	}
	if !strings.Contains(string(empty), `"criteria":{"false":"","true":""}`) {
		t.Fatalf("empty criteria did not survive: %s", empty)
	}
}

// ---------------------------------------------------------------- the parse

func TestClassifierBaseParse(t *testing.T) {
	f := loadJudgeFixture(t, "base")
	c, qs := staticFrom(t, f, ClassifierOptions{})
	d, err := c.Evaluate(context.Background(), f.Request.State, qs)
	if err != nil {
		t.Fatalf("Evaluate: %v", err)
	}
	if !d.Calibrated {
		t.Fatal("systemone-recorded decision should be calibrated")
	}
	if d.Model != "typesafe/jev-1.13-20260917" {
		t.Fatalf("Model = %q", d.Model)
	}
	ch, err := d.Choice("department")
	if err != nil {
		t.Fatal(err)
	}
	if ch.Choice != "shipping" || ch.Confidence != 0.41 || ch.NearUniform {
		t.Fatalf("choice = %+v", ch)
	}
	if p, ok := ch.Probabilities["technical"]; !ok || p != 0 {
		t.Fatalf("a zero probability must stay an entry, got %v ok=%v", p, ok)
	}
	n, err := d.Noul("is_refund_request")
	if err != nil || n.Noul != 0.98 {
		t.Fatalf("noul = %+v, %v", n, err)
	}
	s, err := d.Score("urgency")
	if err != nil || s.Score != 1.21 || s.Confidence != 0.57 {
		t.Fatalf("score = %+v, %v", s, err)
	}
	if got := s.Levels(); len(got) != 3 || got[0] != "routine" || got[2] != "urgent" {
		t.Fatalf("Levels() = %v", got)
	}
	// A wrong-type read is an error, never a panic.
	if _, err := d.Noul("department"); err == nil {
		t.Fatal("reading a choice as a noul should error")
	}
	if _, err := d.Choice("nope"); err == nil {
		t.Fatal("reading an absent key should error")
	}
}

// Numbers are compared NUMERICALLY, never as strings: 0 vs 0.0 and 1.6716e-5 vs
// 0.000016716 are the same value and different bytes.
func TestClassifierNumbersParse(t *testing.T) {
	f := loadJudgeFixture(t, "numbers")
	c, qs := staticFrom(t, f, ClassifierOptions{})
	d, err := c.Evaluate(context.Background(), f.Request.State, qs)
	if err != nil {
		t.Fatalf("Evaluate: %v", err)
	}
	s, err := d.Score("urgency")
	if err != nil {
		t.Fatal(err)
	}
	if s.Score != 1.21 {
		t.Fatalf("score = %v", s.Score)
	}
	if s.Probabilities["0"] != 0.04 {
		t.Fatalf("probabilities[0] = %v", s.Probabilities["0"])
	}
	n, err := d.Noul("is_expensive")
	if err != nil || n.Noul != 0 {
		t.Fatalf("noul = %+v, %v", n, err)
	}
	if d.Usage.Cost == nil || *d.Usage.Cost != 1.6716e-05 {
		t.Fatalf("usage.cost = %v", d.Usage.Cost)
	}
}

// 40 keys: above the width at which some runtimes stop iterating small maps in
// term order. The request bytes are covered by TestClassifierCanonicalBytes; the
// response side is checked here.
func TestClassifierWide(t *testing.T) {
	f := loadJudgeFixture(t, "wide")
	c, qs := staticFrom(t, f, ClassifierOptions{})
	d, err := c.Evaluate(context.Background(), f.Request.State, qs)
	if err != nil {
		t.Fatalf("Evaluate: %v", err)
	}
	ch, err := d.Choice("skill")
	if err != nil {
		t.Fatal(err)
	}
	if ch.Choice != "skill_07" {
		t.Fatalf("choice = %q", ch.Choice)
	}
	if len(ch.Probabilities) != 40 {
		t.Fatalf("probabilities = %d keys, want 40", len(ch.Probabilities))
	}
	if ch.NearUniform {
		t.Fatal("wide answer is peaked, not near-uniform")
	}
}

// ---------------------------------------------------------------- nearUniform

func TestClassifierNearUniformFixture(t *testing.T) {
	f := loadJudgeFixture(t, "near-uniform")
	var expect struct {
		Tolerance float64 `json:"tolerance"`
		Answers   map[string]struct {
			MaxDeviation float64 `json:"maxDeviation"`
			NearUniform  bool    `json:"nearUniform"`
		} `json:"answers"`
	}
	if err := json.Unmarshal(f.Expect, &expect); err != nil {
		t.Fatal(err)
	}
	if expect.Tolerance != NearUniformTolerance {
		t.Fatalf("fixture tolerance %v != NearUniformTolerance %v", expect.Tolerance, NearUniformTolerance)
	}
	c, qs := staticFrom(t, f, ClassifierOptions{})
	d, err := c.Evaluate(context.Background(), f.Request.State, qs)
	if err != nil {
		t.Fatalf("Evaluate: %v", err)
	}
	for key, want := range expect.Answers {
		ch, err := d.Choice(key)
		if err != nil {
			t.Fatalf("%s: %v", key, err)
		}
		if ch.NearUniform != want.NearUniform {
			t.Fatalf("%s: nearUniform = %v, want %v", key, ch.NearUniform, want.NearUniform)
		}
		// The deviation the fixture records is the one the rule is decided on.
		target := 1.0 / float64(len(ch.Probabilities))
		max := 0.0
		for _, p := range ch.Probabilities {
			if dv := math.Abs(p - target); dv > max {
				max = dv
			}
		}
		if math.Abs(max-want.MaxDeviation) > 1e-9 {
			t.Fatalf("%s: maxDeviation = %v, want %v", key, max, want.MaxDeviation)
		}
	}
}

// n == 1 is trivially uniform; an empty map has no distribution at all. The
// tolerance boundary itself is pinned by near-uniform.json at 0.0499 / 0.0501,
// 1e-4 either side — a deviation of EXACTLY 0.05 is not representable in binary
// floating point (0.55 - 0.5 is 0.050000000000000044), which is why the fixture
// stays clear of the boundary and no port needs an epsilon.
func TestNearUniformBoundary(t *testing.T) {
	cases := []struct {
		name string
		p    map[string]float64
		want bool
	}{
		{"just inside", map[string]float64{"a": 0.5499, "b": 0.4501}, true},
		{"just outside", map[string]float64{"a": 0.5501, "b": 0.4499}, false},
		{"single option", map[string]float64{"only": 1}, true},
		{"empty", map[string]float64{}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := NearUniform(tc.p); got != tc.want {
				t.Fatalf("NearUniform(%v) = %v, want %v", tc.p, got, tc.want)
			}
		})
	}
}

// ---------------------------------------------------------------- degenerate

// The WARNING is the assertion — and the bytes are unchanged by it.
func TestClassifierDegenerateCriteria(t *testing.T) {
	f := loadJudgeFixture(t, "degenerate")
	var expect struct {
		Warnings  []string `json:"warnings"`
		NoWarning []string `json:"noWarning"`
	}
	if err := json.Unmarshal(f.Expect, &expect); err != nil {
		t.Fatal(err)
	}
	qs := f.Request.questions(t)
	var warned []string
	c, err := CreateClassifier(ClassifierOptions{
		Style: StyleCustom,
		Model: f.Request.Model,
		Evaluate: func(context.Context, any, map[string]Question) (Decision, error) {
			return Decision{Model: f.Request.Model}, nil
		},
		OnMetric: func(ev MetricEvent) {
			if ev.Event == MetricClassifierWarning {
				warned = append(warned, ev.Question)
				if ev.Error != "" {
					t.Errorf("a warning is not a failure; Error must stay empty, got %q", ev.Error)
				}
				if !strings.Contains(ev.Warning, ev.Question) {
					t.Errorf("warning text does not name the question key: %q", ev.Warning)
				}
			}
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	// Twice: detection is ONCE PER QUESTION KEY, so a per-turn judge does not
	// flood the sink.
	for i := 0; i < 2; i++ {
		if _, err := c.Evaluate(context.Background(), f.Request.State, qs); err != nil {
			t.Fatalf("Evaluate: %v", err)
		}
	}
	sort.Strings(warned)
	if fmt.Sprint(warned) != fmt.Sprint(expect.Warnings) {
		t.Fatalf("warnings = %v, want %v", warned, expect.Warnings)
	}
	for _, key := range expect.NoWarning {
		for _, w := range warned {
			if w == key {
				t.Fatalf("%q is described and must not warn", key)
			}
		}
	}
	// Detection, never repair: the bytes are the bytes the same questions produce
	// with detection switched off.
	got, err := CanonicalRequest(f.Request.Model, qs)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(got)
	if h := hex.EncodeToString(sum[:]); h != f.CanonicalSha256 {
		t.Fatalf("detection changed the request: sha256 %s != %s", h, f.CanonicalSha256)
	}
}

func TestDegenerateCriteriaPredicate(t *testing.T) {
	cases := []struct {
		name string
		in   map[string]string
		want bool
	}{
		{"all empty", map[string]string{"a": "", "b": ""}, true},
		{"all equal their key", map[string]string{"a": "a", "b": "b"}, true},
		{"all identical", map[string]string{"a": "same", "b": "same"}, true},
		{"described", map[string]string{"a": "one thing", "b": "another"}, false},
		{"one empty is not all empty", map[string]string{"a": "", "b": "described"}, false},
		{"single option is never reported", map[string]string{"only": "only"}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, got := degenerateCriteria(tc.in); got != tc.want {
				t.Fatalf("degenerateCriteria(%v) = %v, want %v", tc.in, got, tc.want)
			}
		})
	}
}

// ---------------------------------------------------------------- the static backend

// The three guard bands: one questions payload, one hash, three states. A corpus
// keyed on the canonical request alone would return the same band three times.
func TestClassifierStaticBands(t *testing.T) {
	f := loadJudgeFixture(t, "decisions")
	var recorded []RecordedDecision
	for _, e := range f.Entries {
		recorded = append(recorded, RecordedDecision{
			State: e.Request.State, Questions: e.Request.questions(t), Response: e.Response,
		})
	}
	c, err := CreateClassifier(ClassifierOptions{
		Style: StyleStatic, Model: f.Entries[0].Request.Model, Decisions: recorded,
	})
	if err != nil {
		t.Fatal(err)
	}
	wantScores := []float64{0.02, 2.25, 2.97}
	for i, e := range f.Entries {
		d, err := c.Evaluate(context.Background(), e.Request.State, e.Request.questions(t))
		if err != nil {
			t.Fatalf("band %d: %v", i, err)
		}
		s, err := d.Score("risk")
		if err != nil {
			t.Fatalf("band %d: %v", i, err)
		}
		if s.Score != wantScores[i] {
			t.Fatalf("band %d: risk = %v, want %v", i, s.Score, wantScores[i])
		}
	}
	// An unrecorded state is an error, never a guess.
	if _, err := c.Evaluate(context.Background(), map[string]any{"command": "unseen"}, f.Entries[0].Request.questions(t)); err == nil {
		t.Fatal("an unrecorded state must error, not fall back to a neighbouring band")
	}
}

// ---------------------------------------------------------------- limits

// Limits are enforced CLIENT-SIDE, before the request: no HTTP call is made, and
// the error names the offending question key and the limit.
func TestClassifierLimitsRejectedPreflight(t *testing.T) {
	calls := 0
	rt := roundTripFunc(func(*http.Request) (*http.Response, error) {
		calls++
		return nil, fmt.Errorf("no request should have been sent")
	})
	tooMany := map[string]string{}
	for i := 0; i < MaxChoiceOptions+1; i++ {
		tooMany[fmt.Sprintf("opt_%03d", i)] = fmt.Sprintf("description %d", i)
	}
	cases := []struct {
		name string
		q    Question
		want string
	}{
		{"too many options", ChoiceQuestion{Instructions: "?", Criteria: tooMany}, "1..255 options"},
		{"no options", ChoiceQuestion{Instructions: "?"}, "1..255 options"},
		{"one rubric level", ScoreQuestion{Instructions: "?", Criteria: []string{"only"}}, "2..10 ordered levels"},
		{"eleven rubric levels", ScoreQuestion{Instructions: "?", Criteria: make([]string, 11)}, "2..10 ordered levels"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c, err := CreateClassifier(ClassifierOptions{HTTPClient: &http.Client{Transport: rt}})
			if err != nil {
				t.Fatal(err)
			}
			_, err = c.Evaluate(context.Background(), "s", map[string]Question{"the_key": tc.q})
			if err == nil {
				t.Fatal("expected a pre-flight error")
			}
			if !strings.Contains(err.Error(), "the_key") || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error must name the key and the limit, got: %v", err)
			}
		})
	}
	if calls != 0 {
		t.Fatalf("%d request(s) were sent despite a pre-flight failure", calls)
	}
}

// ---------------------------------------------------------------- secrets

// No credential value and no expanded header value reaches any log, metric,
// error message or returned value — including when the backend reflects them
// back in its own error body.
func TestClassifierNeverLeaksCredentials(t *testing.T) {
	const key = "sk-live-NEVER-IN-AN-ERROR"
	const hdr = "tenant-NEVER-IN-AN-ERROR"
	t.Setenv("TEST_JUDGE_KEY", key)
	t.Setenv("TEST_JUDGE_TENANT", hdr)

	var sawAuth, sawTenant string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawAuth = r.Header.Get("Authorization")
		sawTenant = r.Header.Get("X-Tenant")
		w.WriteHeader(http.StatusUnauthorized)
		// A real gateway happily reflects what it was sent.
		fmt.Fprintf(w, `{"error":"bad credential %s for %s"}`, sawAuth, sawTenant)
	}))
	defer srv.Close()

	var events []MetricEvent
	c, err := CreateClassifier(ClassifierOptions{
		BaseURL:   srv.URL,
		APIKeyEnv: "TEST_JUDGE_KEY",
		Headers:   map[string]string{"X-Tenant": "${TEST_JUDGE_TENANT}"},
		Retries:   1,
		OnMetric:  func(ev MetricEvent) { events = append(events, ev) },
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = c.Evaluate(context.Background(), "s", map[string]Question{"q": NoulQuestion{Instructions: "?"}})
	if err == nil {
		t.Fatal("expected a 401 failure")
	}
	// The credential and the header DID reach the wire (they are use-only, not
	// unused) …
	if sawAuth != "Bearer "+key {
		t.Fatalf("Authorization header = %q", sawAuth)
	}
	if sawTenant != hdr {
		t.Fatalf("${ENV} header did not expand at call time: %q", sawTenant)
	}
	// … and nowhere else.
	haystacks := map[string]string{"error": err.Error()}
	for i, ev := range events {
		haystacks[fmt.Sprintf("metric[%d]", i)] = ev.Error + ev.Model + ev.Question + ev.Status
	}
	for where, s := range haystacks {
		for _, secret := range []string{key, hdr, "NEVER-IN-AN-ERROR"} {
			if strings.Contains(s, secret) {
				t.Fatalf("%s leaked a secret: %q", where, s)
			}
		}
	}
	if !strings.Contains(err.Error(), "401") || !strings.Contains(err.Error(), srv.URL) {
		t.Fatalf("an auth failure names the status and the endpoint: %v", err)
	}
}

// The default retryable set is an ENUMERATION (429/500/502/503/504/529, plus the
// classifier's 408), not "429 plus any 5xx": a permanently-broken 501 must not
// cost a host Retries backed-off attempts. A backend with its own transient
// status opts in through RetryableStatuses, which ADDS to the set and cannot
// remove from it.
func TestClassifierRetryableStatuses(t *testing.T) {
	cases := []struct {
		name   string
		extra  []int
		status int
		want   int // expected attempts
	}{
		{"529 retries by default", nil, 529, 2},
		{"an unlisted 5xx is terminal by default", nil, 520, 1},
		{"a permanent 5xx is terminal by default", nil, 501, 1},
		{"a listed 520 retries", []int{520, 521, 522, 523, 524, 525, 526, 527}, 520, 2},
		{"429 still retries alongside a list (additive)", []int{520, 521, 522, 523, 524, 525, 526, 527}, 429, 2},
		{"501 stays terminal alongside a list", []int{520, 521, 522, 523, 524, 525, 526, 527}, 501, 1},
		{"a non-429 4xx stays terminal", nil, 422, 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var attempts int
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				attempts++
				if attempts == 1 {
					w.WriteHeader(tc.status)
					fmt.Fprint(w, "nope")
					return
				}
				w.Header().Set("Content-Type", "application/json")
				fmt.Fprint(w, `{"model":"m","answers":{"q":{"type":"noul","noul":0.5}},"usage":{"input_tokens":1,"output_tokens":1}}`)
			}))
			defer srv.Close()
			c, err := CreateClassifier(ClassifierOptions{
				BaseURL:           srv.URL,
				APIKeyEnv:         "TEST_JUDGE_UNSET",
				Retries:           2,
				RetryableStatuses: tc.extra,
				HTTPClient:        srv.Client(),
			})
			if err != nil {
				t.Fatal(err)
			}
			_, err = c.Evaluate(context.Background(), "s", map[string]Question{"q": NoulQuestion{Instructions: "?"}})
			if tc.want == 1 && err == nil {
				t.Fatalf("status %d must fail terminally", tc.status)
			}
			if tc.want > 1 && err != nil {
				t.Fatalf("status %d should have been retried: %v", tc.status, err)
			}
			if attempts != tc.want {
				t.Fatalf("status %d: attempts = %d, want %d", tc.status, attempts, tc.want)
			}
		})
	}
}

// RetryableStatuses sets the DEFAULT classification only; OnError still runs per
// attempt and has the final say, so TierFail overrides a status the host listed.
func TestClassifierOnErrorOverridesRetryableStatuses(t *testing.T) {
	var attempts int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		w.WriteHeader(520)
		fmt.Fprint(w, "origin error")
	}))
	defer srv.Close()
	c, err := CreateClassifier(ClassifierOptions{
		BaseURL:           srv.URL,
		APIKeyEnv:         "TEST_JUDGE_UNSET",
		Retries:           3,
		RetryableStatuses: []int{520},
		OnError:           func(ErrorInfo) Tier { return TierFail },
		HTTPClient:        srv.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Evaluate(context.Background(), "s", map[string]Question{"q": NoulQuestion{Instructions: "?"}}); err == nil {
		t.Fatal("OnError returning TierFail must fail the call")
	}
	if attempts != 1 {
		t.Fatalf("OnError TierFail must stop after one attempt, attempts = %d", attempts)
	}
}

// TypeSafe's own API returns model/answers/usage and no `cost` key at all.
// Reporting 0 there would read as "this call was free" when the truth is "this
// backend does not say".
func TestClassifierAbsentCostIsNotZero(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.98}},"usage":{"input_tokens":331,"output_tokens":48}}`)
	}))
	defer srv.Close()
	c, err := CreateClassifier(ClassifierOptions{
		BaseURL:    srv.URL,
		Model:      "jev-latest",
		APIKeyEnv:  "TEST_JUDGE_UNSET",
		HTTPClient: srv.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	d, err := c.Evaluate(context.Background(), "s", map[string]Question{"q": NoulQuestion{Instructions: "?"}})
	if err != nil {
		t.Fatal(err)
	}
	if d.Usage.InputTokens != 331 {
		t.Fatalf("usage.input_tokens = %d", d.Usage.InputTokens)
	}
	if d.Usage.Cost != nil {
		t.Fatalf("an absent cost must stay absent, got %v", *d.Usage.Cost)
	}
}

// A backend's own limit error is surfaced with its reported cause intact, so a
// caller can tell a limit from a transport fault.
func TestClassifierSurfacesBackendCause(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprint(w, `{"error":"Too many choices. Must have at most 255 choices."}`)
	}))
	defer srv.Close()
	c, err := CreateClassifier(ClassifierOptions{BaseURL: srv.URL, APIKeyEnv: "TEST_JUDGE_UNSET"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = c.Evaluate(context.Background(), "s", map[string]Question{"q": NoulQuestion{Instructions: "?"}})
	if err == nil || !strings.Contains(err.Error(), "Too many choices") {
		t.Fatalf("backend cause must survive intact, got: %v", err)
	}
}

// ---------------------------------------------------------------- non-breaking

// Proves the non-breaking claim rather than asserting it: the request body the
// client loop sends is byte-identical whether or not a Classifier exists in the
// same process (SPEC §8B, tasks 6.2).
func TestClassifierAbsenceIsByteIdentical(t *testing.T) {
	capture := func() []byte {
		var body []byte
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			b := make([]byte, r.ContentLength)
			_, _ = r.Body.Read(b)
			body = b
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"choices":[{"message":{"content":"done"}}],"usage":{}}`)
		}))
		defer srv.Close()
		cl := CreateClient(ClientOptions{
			BaseURL: srv.URL, Style: StyleOpenAI, Model: "m", APIKey: "k", HTTPClient: srv.Client(),
		})
		if _, err := cl.Run(context.Background(), "hello", nil); err != nil {
			t.Fatalf("Run: %v", err)
		}
		return body
	}
	without := capture()
	// Construct one, and exercise it, before capturing again.
	c, err := CreateClassifier(ClassifierOptions{
		Style: StyleCustom,
		Evaluate: func(context.Context, any, map[string]Question) (Decision, error) {
			return Decision{Model: "m"}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Evaluate(context.Background(), "s", map[string]Question{"q": NoulQuestion{Instructions: "?"}}); err != nil {
		t.Fatal(err)
	}
	with := capture()
	if string(without) != string(with) {
		t.Fatalf("constructing a Classifier changed a client request:\n%s\n%s", without, with)
	}
}

// ---------------------------------------------------------------- construction

func TestCreateClassifierRejectsIncompleteStyles(t *testing.T) {
	if _, err := CreateClassifier(ClassifierOptions{Style: StyleLLM}); err == nil {
		t.Fatal("llm without a Client must fail at construction")
	}
	if _, err := CreateClassifier(ClassifierOptions{Style: StyleCustom}); err == nil {
		t.Fatal("custom without Evaluate must fail at construction")
	}
	if _, err := CreateClassifier(ClassifierOptions{Style: "nonsense"}); err == nil {
		t.Fatal("an unknown style must fail at construction")
	}
	c, err := CreateClassifier(ClassifierOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if c.opts.Style != StyleSystemOne || c.opts.Model != DefaultClassifierModel ||
		c.opts.BaseURL != DefaultClassifierBaseURL || c.opts.APIKeyEnv != DefaultClassifierAPIKeyEnv ||
		c.opts.Timeout != DefaultClassifierTimeout {
		t.Fatalf("defaults not applied: %+v", c.opts)
	}
}
