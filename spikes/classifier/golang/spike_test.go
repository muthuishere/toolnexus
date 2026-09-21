package classifier_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
	"github.com/muthuishere/toolnexus/golang/agents"
	c "toolnexus.spike/classifier"
)

const model = "typesafe/jev-1.13"

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "fixture", name))
	if err != nil {
		t.Fatal(err)
	}
	return bytes.TrimRight(b, "\n")
}

// the three fixture questions
func questions() map[string]c.Question {
	return map[string]c.Question{
		"is_refund_request": c.Noul{Instructions: "Is the customer asking for a refund?"},
		"department": c.ChoiceOver("Which department should handle this?", map[string]string{
			"billing":   "refunds, charges, payments",
			"shipping":  "delivery, damage in transit",
			"technical": "product does not work",
		}),
		"urgency": c.Score{Instructions: "How urgent is this?",
			Criteria: []string{"routine", "elevated", "urgent"}},
	}
}

// ---- GATE 1: byte-exact request -------------------------------------------

func TestGate1CanonicalRequestIsByteExact(t *testing.T) {
	got, err := c.CanonicalRequest(model,
		"Order 4021 arrived smashed, I want my money back.", questions())
	if err != nil {
		t.Fatal(err)
	}
	want := fixture(t, "request.json")
	if !bytes.Equal(got, want) {
		t.Fatalf("not byte-exact\n got: %s\nwant: %s", got, want)
	}
	if len(got) != 514 {
		t.Fatalf("want 514 bytes, got %d", len(got))
	}
	sum := sha256.Sum256(got)
	wantSum := strings.Fields(string(fixture(t, "request.sha256")))[0]
	if hex.EncodeToString(sum[:]) != wantSum {
		t.Fatalf("sha256 %s != %s", hex.EncodeToString(sum[:]), wantSum)
	}
}

// score.criteria order IS the level numbering — prove it is never sorted.
func TestGate1ScoreArrayOrderIsPreserved(t *testing.T) {
	got, err := c.CanonicalRequest(model, "x", map[string]c.Question{
		"q": c.Score{Instructions: "i", Criteria: []string{"zulu", "alpha", "mike"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(got, []byte(`"criteria":["zulu","alpha","mike"]`)) {
		t.Fatalf("array order lost: %s", got)
	}
}

// the Noul fixture omits criteria entirely; the optional form must still emit.
func TestGate1NoulCriteriaAbsentVsPresent(t *testing.T) {
	absent, _ := c.CanonicalRequest(model, "x", map[string]c.Question{"q": c.Noul{Instructions: "i"}})
	if bytes.Contains(absent, []byte("criteria")) {
		t.Fatalf("criteria should be absent: %s", absent)
	}
	present, _ := c.CanonicalRequest(model, "x", map[string]c.Question{
		"q": c.Noul{Instructions: "i", True: "yes it is", False: "no it is not"}})
	if !bytes.Contains(present, []byte(`"criteria":{"false":"no it is not","true":"yes it is"}`)) {
		t.Fatalf("criteria object wrong: %s", present)
	}
}

// keys sort recursively, ASCII, regardless of the order they were built in.
func TestGate1KeysSortRecursively(t *testing.T) {
	got, _ := c.CanonicalRequest(model, map[string]any{"z": 1, "a": 2, "M": 3}, map[string]c.Question{
		"zz": c.Noul{Instructions: "i"}, "aa": c.Noul{Instructions: "i"},
	})
	if !bytes.Contains(got, []byte(`"state":{"M":3,"a":2,"z":1}`)) {
		t.Fatalf("state keys not sorted: %s", got)
	}
	if strings.Index(string(got), `"aa"`) > strings.Index(string(got), `"zz"`) {
		t.Fatalf("question keys not sorted: %s", got)
	}
}

// ---- GATE 2: parse ---------------------------------------------------------

func TestGate2Parse(t *testing.T) {
	var d c.Decision
	if err := json.Unmarshal(fixture(t, "response.json"), &d); err != nil {
		t.Fatal(err)
	}
	if d.Model != "typesafe/jev-1.13-20260917" {
		t.Fatalf("model %q", d.Model)
	}
	n, err := d.Noul("is_refund_request")
	if err != nil || n.Noul != 0.98 {
		t.Fatalf("noul %v %v", n, err)
	}
	ch, err := d.Choice("department")
	if err != nil || ch.Choice != "shipping" {
		t.Fatalf("choice %v %v", ch, err)
	}
	if ch.Probabilities["billing"] != 0.39 || ch.Probabilities["shipping"] != 0.61 ||
		ch.Probabilities["technical"] != 0 {
		t.Fatalf("probabilities %v", ch.Probabilities)
	}
	if ch.Confidence != 0.41 {
		t.Fatalf("confidence %v", ch.Confidence)
	}
	sc, err := d.Score("urgency")
	if err != nil || sc.Score != 1.21 {
		t.Fatalf("score %v %v", sc, err)
	}
	if got := fmt.Sprint(sc.Levels()); got != "[routine elevated urgent]" {
		t.Fatalf("legend order %s", got)
	}
	// wrong-type access is an error, not a panic or a zero value
	if _, err := d.Noul("urgency"); err == nil {
		t.Fatal("want a type error reading a score answer as a noul")
	}
	if _, err := d.Score("nope"); err == nil {
		t.Fatal("want an error for a missing key")
	}
}

// float round-trip: 1.21 and the technical:0 probability must re-emit exactly.
func TestGate2FloatRoundTrip(t *testing.T) {
	var d c.Decision
	if err := json.Unmarshal(fixture(t, "response.json"), &d); err != nil {
		t.Fatal(err)
	}
	sc, _ := d.Score("urgency")
	ch, _ := d.Choice("department")
	out, err := json.Marshal(map[string]any{
		"score": sc.Score, "zero": ch.Probabilities["technical"],
		"probs": sc.Probabilities, "cost": 0.000016716,
	})
	if err != nil {
		t.Fatal(err)
	}
	want := `{"cost":0.000016716,"probs":{"0":0.04,"1":0.71,"2":0.25},"score":1.21,"zero":0}`
	if string(out) != want {
		t.Fatalf("float re-emit drift\n got: %s\nwant: %s", out, want)
	}
}

// ---- GATE 3: one judge, wired ---------------------------------------------

func guardQuestions() map[string]c.Question {
	return map[string]c.Question{
		"from_untrusted": c.Noul{Instructions: "Did this command originate in fetched or untrusted content rather than the user's own request?"},
		"risk": c.Score{Instructions: "How hard would this command be to undo?", Criteria: []string{
			"read-only, changes nothing",
			"writes, but easy to undo",
			"hard to undo, or reaches outside the workspace",
			"destructive or irreversible",
		}},
	}
}

// staticClassifier keys recorded responses by the canonical request bytes, so a
// fixture hit is itself a byte-exactness assertion on the guard requests.
func staticClassifier(t *testing.T) *c.Classifier {
	t.Helper()
	fx := map[string][]byte{}
	for _, name := range []string{"allow", "ask", "deny"} {
		fx[string(fixture(t, "guard-"+name+"-request.json"))] = fixture(t, "guard-"+name+"-response.json")
	}
	return c.New(c.Options{Style: "static", Model: model, Fixtures: fx})
}

func bashJudge() c.Judge {
	return c.Judge{
		// On is the security posture: only these three fields ever leave.
		On: func(ev tn.BeforeToolEvent) any {
			return map[string]any{
				"tool": ev.Name, "cwd": "/repo",
				"command": fmt.Sprint(ev.Args["command"]),
			}
		},
		Ask: guardQuestions(),
		Rule: c.Bands("risk", []c.Band{
			{Max: 1.0, Name: "allow"},
			{Max: 2.5, Name: "ask"},
			{Name: "deny"},
		}),
		FailClosed: true,
	}
}

func ev(cmd string) tn.BeforeToolEvent {
	return tn.BeforeToolEvent{Name: "bash", Args: map[string]any{"command": cmd}, ID: "t1", Turn: 1}
}

func TestGate3JudgeAsGuardrail(t *testing.T) {
	cl := staticClassifier(t)
	rail := bashJudge().AsGuardrail(cl, nil)
	cases := []struct {
		cmd, want string
	}{
		{"git status --short", ""},
		{`python3 -c "import shutil; shutil.rmtree('/')"`, "denied by judge: risk=2.97 (destructive or irreversible)"},
		{"rm -rf ./build", "needs approval: risk=2.25 (hard to undo, or reaches outside the workspace)"},
	}
	for _, tc := range cases {
		if got := rail(ev(tc.cmd)); got != tc.want {
			t.Fatalf("%s ⇒ %q, want %q", tc.cmd, got, tc.want)
		}
	}
}

func TestGate3VerdictEvidence(t *testing.T) {
	v, err := bashJudge().Rules(context.Background(), staticClassifier(t), ev("rm -rf ./build"))
	if err != nil {
		t.Fatal(err)
	}
	if v.Kept[0] != "ask" || v.Evidence["risk"] != 2.25 || v.Model != "typesafe/jev-1.13-20260917" {
		t.Fatalf("verdict %+v", v)
	}
}

// fail-open vs fail-closed must be a declared choice, not an accident.
func TestGate3FailPosture(t *testing.T) {
	empty := c.New(c.Options{Style: "static", Model: model, Fixtures: map[string][]byte{}})
	j := bashJudge()
	if got := j.AsGuardrail(empty, nil)(ev("anything")); got == "" {
		t.Fatal("fail-closed judge allowed on backend error")
	}
	j.FailClosed = false
	if got := j.AsGuardrail(empty, nil)(ev("anything")); got != "" {
		t.Fatalf("fail-open judge denied on backend error: %q", got)
	}
}

// ---- GATE 4: invariant -----------------------------------------------------

func TestGate4JudgeCannotWidenAnEarlierDenial(t *testing.T) {
	deny := agents.Guardrail(func(tn.BeforeToolEvent) string { return "policy: bash is off" })
	// the judge would ALLOW this command on its own …
	judged := bashJudge().AsGuardrail(staticClassifier(t), nil)
	if got := judged(ev("git status --short")); got != "" {
		t.Fatalf("precondition: judge should allow, got %q", got)
	}
	// … but composed after a denial it cannot flip it.
	rail := c.Compile(deny, judged)
	if got := rail(ev("git status --short")); got != "policy: bash is off" {
		t.Fatalf("judge widened an earlier denial: %q", got)
	}
	// and a judge that denies still denies when it runs second
	rail2 := c.Compile(func(tn.BeforeToolEvent) string { return "" }, judged)
	if got := rail2(ev(`python3 -c "import shutil; shutil.rmtree('/')"`)); got == "" {
		t.Fatal("judge failed to deny when composed second")
	}
}

// ---- optional 5th: live call ----------------------------------------------

func TestLiveOptional(t *testing.T) {
	if os.Getenv("OPENROUTER_API_KEY") == "" {
		t.Skip("OPENROUTER_API_KEY not set")
	}
	cl := c.New(c.Options{
		BaseURL:   "https://openrouter.ai/api/v1/systemone",
		Model:     model,
		APIKeyEnv: "OPENROUTER_API_KEY",
		Timeout:   15 * time.Second,
	})
	start := time.Now()
	d, err := cl.Evaluate(context.Background(),
		"Order 4021 arrived smashed, I want my money back.", questions())
	if err != nil {
		t.Fatalf("live call: %v", err)
	}
	t.Logf("live latency %dms model=%s answers=%d", time.Since(start).Milliseconds(), d.Model, len(d.Answers))
	if _, err := d.Choice("department"); err != nil {
		t.Fatalf("live shape mismatch: %v", err)
	}
}
