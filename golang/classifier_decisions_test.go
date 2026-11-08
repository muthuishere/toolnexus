package toolnexus

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
)

// Fixture: examples/judge/backends/openai-decisions.json (SPEC §8B Decisions wire, #157).
type decisionsFixture struct {
	Questions    map[string]map[string]any `json:"questions"`
	RequestCases []struct {
		Name   string `json:"name"`
		Model  string `json:"model"`
		State  any    `json:"state"`
		Expect struct {
			Body string `json:"body"`
		} `json:"expect"`
	} `json:"requestCases"`
	ReplyCases []struct {
		Name   string          `json:"name"`
		Reply  json.RawMessage `json:"reply"`
		Expect struct {
			Normalized string             `json:"normalized"`
			Calibrated bool               `json:"calibrated"`
			Model      string             `json:"model"`
			Noul       map[string]float64 `json:"noul"`
			Choice     map[string]string  `json:"choice"`
			Score      map[string]float64 `json:"score"`
			Error      string             `json:"error"`
		} `json:"expect"`
	} `json:"replyCases"`
	ErrorCases []struct {
		Name      string                    `json:"name"`
		Questions map[string]map[string]any `json:"questions"`
		Expect    struct {
			Error       string `json:"error"`
			Unsupported bool   `json:"unsupported"`
		} `json:"expect"`
	} `json:"errorCases"`
	CaptureCases []struct {
		Name                string            `json:"name"`
		APIKeyEnv           string            `json:"apiKeyEnv"`
		Env                 map[string]string `json:"env"`
		ExpectAuthorization *string           `json:"expectAuthorization"`
	} `json:"captureCases"`
}

func TestOpenAIDecisionsFixture(t *testing.T) {
	var fx decisionsFixture
	loadBackendFixture(t, "openai-decisions.json", &fx)
	qs := fixtureQuestions(t, fx.Questions)
	okReply := string(fx.ReplyCases[0].Reply)

	for _, c := range fx.RequestCases {
		t.Run("request/"+c.Name, func(t *testing.T) {
			f := newFakeSystemOne(t, func(int, string) (int, string) { return 200, okReply })
			cl, err := CreateClassifier(ClassifierOptions{Backend: BackendOpenAI, BaseURL: f.srv.URL + "/v1", Model: c.Model, Retries: -1})
			if err != nil {
				t.Fatal(err)
			}
			if _, err := cl.Evaluate(context.Background(), fixtureState(c.State), qs); err != nil {
				t.Fatal(err)
			}
			if f.bodies[0] != c.Expect.Body {
				t.Fatalf("body\n got %s\nwant %s", f.bodies[0], c.Expect.Body)
			}
			if f.paths[0] != "/v1/decisions" {
				t.Fatalf("path %s", f.paths[0])
			}
			if f.hdrs[0].Get(ImagesHeader) != "" {
				t.Fatal("danger-zone header sent on the decisions wire")
			}
		})
	}
	for _, c := range fx.ReplyCases {
		t.Run("reply/"+c.Name, func(t *testing.T) {
			if c.Expect.Normalized != "" {
				got, err := decisionsReply(c.Reply, qs)
				if err != nil || string(got) != c.Expect.Normalized {
					t.Fatalf("normalized\n got %s (%v)\nwant %s", got, err, c.Expect.Normalized)
				}
			}
			f := newFakeSystemOne(t, func(int, string) (int, string) { return 200, string(c.Reply) })
			cl, _ := CreateClassifier(ClassifierOptions{Backend: BackendOpenAI, BaseURL: f.srv.URL, Retries: -1})
			d, err := cl.State("x").Evaluate(context.Background(), judgeQuestionsFrom(qs)...)
			if c.Expect.Error != "" {
				if err == nil || err.Error() != c.Expect.Error {
					t.Fatalf("want %q got %v", c.Expect.Error, err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if d.Model != c.Expect.Model || d.Calibrated != c.Expect.Calibrated {
				t.Fatalf("model %q calibrated %v", d.Model, d.Calibrated)
			}
			for k, v := range c.Expect.Noul {
				if a, _ := d.Noul(k); a.Noul != v {
					t.Fatalf("%s: %v", k, a)
				}
			}
			for k, v := range c.Expect.Choice {
				if a, _ := d.Choice(k); a.Choice != v {
					t.Fatalf("%s: %v", k, a)
				}
			}
			for k, v := range c.Expect.Score {
				if a, _ := d.Score(k); a.Score != v {
					t.Fatalf("%s: %v", k, a)
				}
			}
		})
	}
	for _, c := range fx.ErrorCases {
		t.Run("error/"+c.Name, func(t *testing.T) {
			f := newFakeSystemOne(t, func(int, string) (int, string) { return 200, okReply })
			cl, _ := CreateClassifier(ClassifierOptions{Backend: BackendOpenAI, BaseURL: f.srv.URL, Retries: -1})
			_, err := cl.Evaluate(context.Background(), "x", fixtureQuestions(t, c.Questions))
			if err == nil || err.Error() != c.Expect.Error {
				t.Fatalf("want %q got %v", c.Expect.Error, err)
			}
			var u *ErrClassifierUnsupported
			if errors.As(err, &u) != c.Expect.Unsupported {
				t.Fatalf("unsupported = %v", !c.Expect.Unsupported)
			}
			if len(f.bodies) != 0 {
				t.Fatal("a request was sent")
			}
		})
	}
	for _, c := range fx.CaptureCases {
		t.Run("capture/"+c.Name, func(t *testing.T) {
			for k, v := range c.Env {
				t.Setenv(k, v)
			}
			f := newFakeSystemOne(t, func(int, string) (int, string) { return 200, okReply })
			cl, _ := CreateClassifier(ClassifierOptions{Backend: BackendOpenAI, BaseURL: f.srv.URL + "/v1", APIKeyEnv: c.APIKeyEnv, Retries: -1})
			if _, err := cl.Evaluate(context.Background(), "x", qs); err != nil {
				t.Fatal(err)
			}
			got, has := f.hdrs[0]["Authorization"]
			switch {
			case c.ExpectAuthorization == nil && has:
				t.Fatal("a key was sent to a host that is not its own")
			case c.ExpectAuthorization != nil && (!has || got[0] != *c.ExpectAuthorization):
				t.Fatalf("Authorization = %v", got)
			}
		})
	}
}

// judgeQuestionsFrom turns a question map into named JudgeQuestions (test helper).
func judgeQuestionsFrom(qs map[string]Question) []JudgeQuestion {
	out := make([]JudgeQuestion, 0, len(qs))
	for _, k := range sortedQuestionKeys(qs) {
		out = append(out, JudgeQuestion{k, qs[k]})
	}
	return out
}
