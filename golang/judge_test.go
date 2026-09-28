package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

func judgeAdapterFixture(t *testing.T, name string, v any) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "examples", "judge", "adapters", name))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, v); err != nil {
		t.Fatal(err)
	}
}

// fixtureQuestion converts one {kind,name,...} list entry into a builder call.
func fixtureQuestion(t *testing.T, m map[string]any) JudgeQuestion {
	name, instr := m["name"].(string), m["instructions"].(string)
	switch m["kind"] {
	case "noul":
		return Noul(name, instr)
	case "choice":
		opts := map[string]string{}
		for k, v := range m["options"].(map[string]any) {
			opts[k] = v.(string)
		}
		return Choice(name, instr, opts)
	case "score":
		var lv []string
		for _, v := range m["levels"].([]any) {
			lv = append(lv, v.(string))
		}
		return Score(name, instr, lv...)
	}
	t.Fatalf("unknown kind %v", m["kind"])
	return JudgeQuestion{}
}

// wireQuestion converts a §8B wire question back into a Question (test only).
func wireQuestion(t *testing.T, m map[string]any) Question {
	instr, _ := m["instructions"].(string)
	switch m["type"] {
	case "noul":
		q := NoulQuestion{Instructions: instr}
		if c, ok := m["criteria"].(map[string]any); ok {
			q.Criteria = &NoulCriteria{True: c["true"].(string), False: c["false"].(string)}
		}
		return q
	case "choice":
		c := map[string]string{}
		for k, v := range m["criteria"].(map[string]any) {
			c[k] = v.(string)
		}
		return ChoiceQuestion{Instructions: instr, Criteria: c}
	case "score":
		var lv []string
		for _, v := range m["criteria"].([]any) {
			lv = append(lv, v.(string))
		}
		return ScoreQuestion{Instructions: instr, Criteria: lv}
	}
	t.Fatalf("unknown type %v", m["type"])
	return nil
}

func wireMap(qs map[string]Question) map[string]any {
	out := map[string]any{}
	for k, q := range qs {
		b, _ := json.Marshal(QuestionWire(q))
		var v any
		_ = json.Unmarshal(b, &v)
		out[k] = v
	}
	return out
}

func TestJudge_StateCases(t *testing.T) {
	var fx struct {
		Cases []struct {
			Name    string
			State   map[string]any
			Context *struct {
				Context, Message string
				Extra            map[string]any
			}
			RoleState *struct {
				Role string
				Data any
			}
			Questions     []map[string]any
			WantState     map[string]any
			WantQuestions map[string]any
			WantError     string
		}
	}
	judgeAdapterFixture(t, "state-cases.json", &fx)
	if len(fx.Cases) == 0 {
		t.Fatal("no state cases")
	}
	for _, c := range fx.Cases {
		t.Run(c.Name, func(t *testing.T) {
			var qs []JudgeQuestion
			for _, q := range c.Questions {
				qs = append(qs, fixtureQuestion(t, q))
			}
			got, err := Questions(qs...)
			if c.WantError != "" {
				if err == nil || !strings.Contains(err.Error(), c.WantError) {
					t.Fatalf("err = %v, want %q", err, c.WantError)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			state := c.State
			if c.Context != nil {
				state = MessageState(c.Context.Context, c.Context.Message, c.Context.Extra)
			}
			if c.RoleState != nil {
				state = State(c.RoleState.Role, c.RoleState.Data)
			}
			if !reflect.DeepEqual(state, c.WantState) {
				t.Fatalf("state = %v, want %v", state, c.WantState)
			}
			if w := wireMap(got); !reflect.DeepEqual(w, c.WantQuestions) {
				t.Fatalf("questions = %v, want %v", w, c.WantQuestions)
			}
		})
	}
}

func TestJudge_StateRole(t *testing.T) {
	s := State("You are Donkey Kong, you want to win.", map[string]any{"message_received": "hi"})
	if !reflect.DeepEqual(s, map[string]any{"role": "You are Donkey Kong, you want to win.", "message_received": "hi"}) {
		t.Fatal(s)
	}
	if s := State("r", "plain text"); !reflect.DeepEqual(s, map[string]any{"role": "r", "data": "plain text"}) {
		t.Fatal(s)
	}
	type bug struct {
		Title string `json:"title"`
	}
	if s := State("r", bug{"x"}); !reflect.DeepEqual(s, map[string]any{"role": "r", "title": "x"}) {
		t.Fatal(s)
	}
}

type gateFixture struct {
	DefaultBands Bands
	Questions    map[string]map[string]any
	Rules        []Rule
	Cases        []struct {
		Name    string
		Answers map[string]any
		Bands   *Bands
		Rules   []Rule
		Policy  *struct {
			Default       string
			SkipUncertain bool
		}
		WantAnswers map[string]struct {
			Value  float64
			Band   *string
			Sure   *bool
			Choice *string
		}
		Want struct {
			Action, Target              string
			Escalated                   bool
			Question, Reason, RequestID string
		}
	}
}

func TestJudge_GateCases(t *testing.T) {
	var fx gateFixture
	judgeAdapterFixture(t, "gate-cases.json", &fx)
	if fx.DefaultBands != DefaultBands {
		t.Fatalf("default bands %v != fixture %v", DefaultBands, fx.DefaultBands)
	}
	names := make([]string, 0, len(fx.Questions))
	for k := range fx.Questions {
		names = append(names, k)
	}
	sort.Strings(names)
	var qs []JudgeQuestion
	for _, k := range names {
		qs = append(qs, JudgeQuestion{k, wireQuestion(t, fx.Questions[k])})
	}
	qm, err := Questions(qs...)
	if err != nil {
		t.Fatal(err)
	}
	var recs []RecordedDecision
	for _, c := range fx.Cases {
		recs = append(recs, Recorded(map[string]any{"case": c.Name}, qm, map[string]any{"model": "m", "answers": c.Answers}))
	}
	cl, err := StaticClassifier(recs...)
	if err != nil {
		t.Fatal(err)
	}
	if len(fx.Cases) == 0 {
		t.Fatal("no gate cases")
	}
	for _, c := range fx.Cases {
		t.Run(c.Name, func(t *testing.T) {
			var b []Bands
			if c.Bands != nil {
				b = append(b, *c.Bands)
			}
			rules := fx.Rules
			if c.Rules != nil {
				rules = c.Rules
			}
			var o Outcome
			var err error
			if c.Policy != nil {
				p := Policy{Rules: rules, Default: c.Policy.Default, SkipUncertain: c.Policy.SkipUncertain}
				if c.Bands != nil {
					p.Bands = *c.Bands
				}
				o, err = p.Gate(context.Background(), cl, map[string]any{"case": c.Name}, qs)
			} else {
				o, err = Gate(context.Background(), cl, map[string]any{"case": c.Name}, qs, rules, b...)
			}
			if err != nil {
				t.Fatal(err)
			}
			if o.Action != c.Want.Action || o.Target != c.Want.Target || o.Escalated != c.Want.Escalated {
				t.Fatalf("got {%q %q %v}, want %+v", o.Action, o.Target, o.Escalated, c.Want)
			}
			if o.Escalated {
				r := o.Request
				if r == nil || r.Kind != "input" || r.Data["question"] == nil || r.Data["reason"] == nil || r.Data["answers"] == nil {
					t.Fatalf("escalation request not §10 input shaped: %+v", r)
				}
				if r.Data["question"] != c.Want.Question || (c.Want.Reason != "" && r.Data["reason"] != c.Want.Reason) || r.ID != c.Want.RequestID {
					t.Fatalf("escalation {%q %q %q}, want {%q %q %q}", r.Data["question"], r.Data["reason"], r.ID,
						c.Want.Question, c.Want.Reason, c.Want.RequestID)
				}
			}
			a, err := Ask(context.Background(), cl, map[string]any{"case": c.Name}, qs, b...)
			if err != nil {
				t.Fatal(err)
			}
			if len(a) != len(c.WantAnswers) {
				t.Fatalf("answers %v, want %v", a, c.WantAnswers)
			}
			for name, wa := range c.WantAnswers {
				got, ok := a[name]
				if !ok || got.Value() != wa.Value ||
					(wa.Band != nil && got.Band != *wa.Band) ||
					(wa.Sure != nil && got.Sure != *wa.Sure) ||
					(wa.Choice != nil && got.Choice() != *wa.Choice) {
					t.Fatalf("answer %s = %+v, want %+v", name, got, wa)
				}
			}
			if c.Name == "missing-component" && !strings.Contains(o.Request.Data["reason"].(string), `"component"`) {
				t.Fatalf("missing reason should name the answer: %v", o.Request.Data["reason"])
			}
		})
	}
}

func noulAns(p float64) JudgeAnswer {
	a := NoulAnswer{Noul: p}
	b := DefaultBands.Band(a)
	return JudgeAnswer{a, b, b == BandYes}
}

func TestJudge_BandsAndValue(t *testing.T) {
	for _, p := range []float64{0.30, 0.70} {
		if b := DefaultBands.Band(NoulAnswer{Noul: p}); b != BandUncertain {
			t.Fatalf("%v -> %s", p, b)
		}
	}
	if b := (Bands{0.2, 0.5}).Band(NoulAnswer{Noul: 0.55}); b != BandYes {
		t.Fatal(b)
	}
	if b := DefaultBands.Band(ChoiceAnswer{Choice: "a", Confidence: 0.8, NearUniform: true}); b != BandUncertain {
		t.Fatal(b)
	}
	if v := noulAns(0.96).Value(); v != 0.96 {
		t.Fatal(v)
	}
	ch := JudgeAnswer{DecisionAnswer: ChoiceAnswer{Choice: "pricing", Confidence: 0.9}}
	if ch.Choice() != "pricing" || ch.Value() != 0.9 {
		t.Fatal(ch)
	}
}

// Task 2.5: an answer marshals flat — no DecisionAnswer nesting.
func TestJudge_AnswerMarshalsFlat(t *testing.T) {
	b, err := json.Marshal(noulAns(0.96))
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != `{"band":"yes","noul":0.96,"type":"noul"}` {
		t.Fatal(string(b))
	}
	b, _ = json.Marshal(JudgeAnswer{DecisionAnswer: ChoiceAnswer{Choice: "a", Confidence: 0.9, Probabilities: map[string]float64{"a": 0.9}}, Band: BandYes, Sure: true})
	if strings.Contains(string(b), "DecisionAnswer") || !strings.Contains(string(b), `"sure":true`) {
		t.Fatal(string(b))
	}
}

func TestJudge_Policy(t *testing.T) {
	lo := 0.3
	rules := []Rule{{Question: "a", Below: &lo, Action: "fail"}, {Question: "b", Below: &lo, Action: "stop"}}
	conf := Answers{"a": noulAns(0.9), "b": noulAns(0.9)}
	o := Policy{Rules: rules}.Decide(conf)
	if !o.Escalated || o.Request.Data["reason"] != "no rule fired" || o.Request.Kind != "input" {
		t.Fatalf("%+v", o)
	}
	if o := (Policy{Rules: rules, Default: "go"}).Decide(conf); o.Action != "go" || o.Escalated {
		t.Fatalf("%+v", o)
	}
	mixed := Answers{"a": noulAns(0.5), "b": noulAns(0.1)}
	if o := (Policy{Rules: rules}).Decide(mixed); !o.Escalated {
		t.Fatalf("without skip: %+v", o)
	}
	if o := (Policy{Rules: rules, SkipUncertain: true}).Decide(mixed); o.Action != "stop" || o.Escalated {
		t.Fatalf("with skip: %+v", o)
	}
}

func TestJudge_Tape(t *testing.T) {
	qs := []JudgeQuestion{Noul("ok", "Is `message` fine?")}
	qm, _ := Questions(qs...)
	state := map[string]any{"message": "hi"}
	live, err := StaticClassifier(Recorded(state, qm, map[string]any{"model": "m", "answers": map[string]any{"ok": map[string]any{"type": "noul", "noul": 0.9}}}))
	if err != nil {
		t.Fatal(err)
	}
	tape := &Tape{}
	rec, _ := tape.Recording(live)
	if _, err := Ask(WithCallName(context.Background(), "triage"), rec, state, qs); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "tape.json")
	if err := tape.Save(path); err != nil {
		t.Fatal(err)
	}
	loaded, err := LoadTape(path)
	if err != nil {
		t.Fatal(err)
	}
	rp, err := loaded.Replayer()
	if err != nil {
		t.Fatalf("obtaining a replayer never fails: %v", err)
	}
	a, err := Ask(WithCallName(context.Background(), "triage"), rp, map[string]any{"message": "changed"}, qs)
	if err != nil || a["ok"].Value() != 0.9 || a["ok"].Band != BandYes {
		t.Fatalf("%v %v", a, err)
	}
	_, err = Ask(WithCallName(context.Background(), "plan"), rp, state, qs)
	if err == nil || err.Error() != `tape: no recorded decision for call "plan"` {
		t.Fatalf("miss should be the shared text naming plan: %v", err)
	}
	// The name is quoted verbatim, never Go-escaped (%q would turn " into \").
	_, err = Ask(WithCallName(context.Background(), `a"b\c`), rp, state, qs)
	if err == nil || err.Error() != `tape: no recorded decision for call "a"b\c"` {
		t.Fatalf("miss should quote the name verbatim: %v", err)
	}
}

// Builders vs hand-written maps: the transmitted request bodies are byte-identical.
func TestJudge_ByteIdentity(t *testing.T) {
	var bodies []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		bodies = append(bodies, string(b))
		fmt.Fprint(w, `{"model":"m","answers":{"is_appropriate":{"type":"noul","noul":0.1},"does_this_help":{"type":"noul","noul":0.9}}}`)
	}))
	defer srv.Close()
	c, err := CreateClassifier(ClassifierOptions{BaseURL: srv.URL, APIKeyEnv: "TEST_JUDGE_UNSET", HTTPClient: srv.Client()})
	if err != nil {
		t.Fatal(err)
	}
	role := "You are Donkey Kong, you want to win."
	ctx := context.Background()
	_, err = Ask(ctx, c, State(role, map[string]any{"message_received": "jump"}), []JudgeQuestion{
		Noul("is_appropriate", "Does the message contain inappropriate language or topics that are considered harmful."),
		Noul("does_this_help", "Does this help donkey kong win?"),
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = c.Evaluate(ctx, map[string]any{"role": role, "message_received": "jump"}, map[string]Question{
		"is_appropriate": NoulQuestion{Instructions: "Does the message contain inappropriate language or topics that are considered harmful."},
		"does_this_help": NoulQuestion{Instructions: "Does this help donkey kong win?"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(bodies) != 2 || bodies[0] != bodies[1] {
		t.Fatalf("bodies differ:\n%v", bodies)
	}
}
