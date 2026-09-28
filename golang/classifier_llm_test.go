package toolnexus

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
)

// Recorded Scaleway replies (testdata/llmreplies, captured 2026-09-28 with the
// key and response id stripped) replayed through a real §8 Client.

var nliQs = map[string]Question{
	"supported": NoulQuestion{Instructions: "Does the premise support the hypothesis?"},
}

func replay(t *testing.T, bodies ...[]byte) (*httptest.Server, *[]map[string]any) {
	t.Helper()
	var n int32
	var seen []map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		seen = append(seen, m)
		i := int(atomic.AddInt32(&n, 1)) - 1
		if i >= len(bodies) {
			i = len(bodies) - 1
		}
		if bodies[i] == nil {
			w.WriteHeader(503)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(bodies[i])
	}))
	t.Cleanup(srv.Close)
	return srv, &seen
}

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("testdata/llmreplies/" + name + ".json")
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// reply wraps a content string as a minimal chat completion.
func reply(content string) []byte {
	b, _ := json.Marshal(map[string]any{"model": "m", "choices": []any{map[string]any{
		"message": map[string]any{"role": "assistant", "content": content}, "finish_reason": "stop"}}})
	return b
}

func llmClassifier(t *testing.T, url string, prof *LLMProfile) *Classifier {
	t.Helper()
	cl := CreateClient(ClientOptions{BaseURL: url, Style: StyleOpenAI, Model: "m", APIKey: "x", RetryBaseMs: 1})
	c, err := CreateClassifier(ClassifierOptions{Style: StyleLLM, Model: "m", Client: cl, LLMProfile: prof})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func noul(t *testing.T, d Decision, key string) float64 {
	t.Helper()
	a, err := d.Noul(key)
	if err != nil {
		t.Fatal(err)
	}
	return a.Noul
}

func TestLLMFencedReplyParses(t *testing.T) { // mistral-small, recorded
	srv, _ := replay(t, reply("```json\n{\"answers\":{\"supported\":{\"type\":\"noul\",\"noul\":0.9}}}\n```"))
	d, err := llmClassifier(t, srv.URL, nil).Evaluate(context.Background(), "s", nliQs)
	if err != nil || noul(t, d, "supported") != 0.9 {
		t.Fatalf("got %v %v", d, err)
	}
}

// pixtral-12b: the old LastIndex('}') extraction spanned a fence + second
// object → "invalid character '`' after top-level value". The first complete
// object wins now.
func TestLLMTrailingFenceAndSecondObject(t *testing.T) {
	txt := "```json\n{\"answers\":{\"supported\":{\"type\":\"noul\",\"noul\":0.2}}}\n```\nNote: {\"x\":1}"
	srv, _ := replay(t, reply(txt))
	d, err := llmClassifier(t, srv.URL, nil).Evaluate(context.Background(), "s", nliQs)
	if err != nil || noul(t, d, "supported") != 0.2 {
		t.Fatalf("got %v %v", d, err)
	}
}

// qwen3.6: a choice-shaped answer to a noul question maps when it is yes/no.
func TestLLMChoiceShapedNoulNormalised(t *testing.T) {
	cases := map[string]float64{
		`{"answers":{"supported":{"type":"choice","choice":"yes","probabilities":{"yes":0.8,"no":0.2},"confidence":0.8}}}`: 0.8,
		`{"answers":{"supported":{"type":"choice","choice":"supported"}}}`:                                                  1,
		`{"answers":{"supported":true}}`:                                                                                   1,
		`{"answers":{"supported":0.3}}`:                                                                                    0.3,
		`{"supported":{"type":"noul","noul":0.4}}`:                                                                         0.4,
	}
	for txt, want := range cases {
		srv, _ := replay(t, reply(txt))
		d, err := llmClassifier(t, srv.URL, nil).Evaluate(context.Background(), "s", nliQs)
		if err != nil || noul(t, d, "supported") != want {
			t.Fatalf("%s: got %v %v", txt, d, err)
		}
	}
}

func TestLLMAmbiguousOrMissingFailsClosed(t *testing.T) {
	for _, txt := range []string{
		`{"answers":{"supported":{"type":"choice","choice":"maybe","probabilities":{"maybe":0.5,"yes":0.5}}}}`,
		`{"answers":{"supported":{"type":"noul","noul":1.7}}}`,
		`{"answers":{}}`,
		`I think it is supported.`,
		``,
	} {
		srv, _ := replay(t, reply(txt))
		if _, err := llmClassifier(t, srv.URL, nil).Evaluate(context.Background(), "s", nliQs); err == nil {
			t.Fatalf("%q: want a fail-closed error", txt)
		}
	}
}

// qwen3.6 with thinking on and a small budget: content empty, reasoning full,
// finish_reason=length. The reasoning text is never parsed; the error says why.
func TestLLMReasoningTruncatedNeverReadsReasoning(t *testing.T) {
	raw := fixture(t, "qwen36_thinking_truncated")
	var m map[string]any
	_ = json.Unmarshal(raw, &m)
	msg := m["choices"].([]any)[0].(map[string]any)["message"].(map[string]any)
	msg["content"] = ""
	m["choices"].([]any)[0].(map[string]any)["finish_reason"] = "length"
	msg["reasoning_content"] = `{"answers":{"supported":{"type":"noul","noul":1}}}`
	b, _ := json.Marshal(m)
	srv, _ := replay(t, b)
	_, err := llmClassifier(t, srv.URL, &LLMProfile{}).Evaluate(context.Background(), "s", nliQs)
	if err == nil || !strings.Contains(err.Error(), "finish_reason=length") {
		t.Fatalf("want a finish_reason=length failure, got %v", err)
	}
}

func TestLLMProfileRequestShape(t *testing.T) {
	srv, seen := replay(t, fixture(t, "qwen38_verdict_logprobs_nothink"))
	prof := &LLMProfile{Probability: LLMProbabilityLogprobs, MaxTokens: 256,
		RequestParams: map[string]any{"reasoning_effort": "none"}}
	if _, err := llmClassifier(t, srv.URL, prof).Evaluate(context.Background(), "s", nliQs); err != nil {
		t.Fatal(err)
	}
	b := (*seen)[0]
	rf, _ := b["response_format"].(map[string]any)
	if rf["type"] != "json_schema" || b["reasoning_effort"] != "none" || b["logprobs"] != true ||
		b["max_tokens"] != float64(256) || b["temperature"] != float64(0) {
		t.Fatalf("body: %v", b)
	}
	sch, _ := json.Marshal(rf)
	if !strings.Contains(string(sch), `"verdict":{"type":"boolean"}`) {
		t.Fatalf("schema lacks verdict: %s", sch)
	}
}

func TestLLMLegacyBodyUnchanged(t *testing.T) {
	srv, seen := replay(t, reply(`{"answers":{"supported":{"type":"noul","noul":1}}}`))
	if _, err := llmClassifier(t, srv.URL, nil).Evaluate(context.Background(), "s", nliQs); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"response_format", "temperature", "logprobs"} {
		if _, ok := (*seen)[0][k]; ok {
			t.Fatalf("legacy body gained %q", k)
		}
	}
}

// Every recorded logprob reply yields a calibrated P(true) from the verdict
// token — including gemma's trailing <turn|> token and qwen3.6's spaced JSON.
func TestLLMVerdictLogprobsRecorded(t *testing.T) {
	for _, f := range []string{"mistral_verdict_logprobs", "pixtral_verdict_logprobs", "qwen38_verdict_logprobs_nothink",
		"gemma_verdict_logprobs_nothink", "qwen36_verdict_logprobs_nothink"} {
		srv, _ := replay(t, fixture(t, f))
		d, err := llmClassifier(t, srv.URL, &LLMProfile{Probability: LLMProbabilityLogprobs}).Evaluate(context.Background(), "s", nliQs)
		if err != nil {
			t.Fatalf("%s: %v", f, err)
		}
		p := noul(t, d, "supported")
		if !d.Calibrated || p < 0.5 || p > 1 {
			t.Fatalf("%s: calibrated=%v p=%v", f, d.Calibrated, p)
		}
	}
}

func TestLLMVerdictLogprobsSplitMass(t *testing.T) {
	content := `{"answers":{"supported":{"type":"noul","verdict":false}}}`
	toks := []any{}
	pre := content[:strings.Index(content, "false")]
	toks = append(toks, map[string]any{"token": pre, "logprob": 0, "top_logprobs": []any{}})
	toks = append(toks, map[string]any{"token": "false", "logprob": -0.357, "top_logprobs": []any{
		map[string]any{"token": "false", "logprob": -0.357}, map[string]any{"token": "true", "logprob": -1.204}}})
	toks = append(toks, map[string]any{"token": "}}}", "logprob": 0, "top_logprobs": []any{}})
	b, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": content},
		"finish_reason": "stop", "logprobs": map[string]any{"content": toks}}}})
	srv, _ := replay(t, b)
	d, err := llmClassifier(t, srv.URL, &LLMProfile{Probability: LLMProbabilityLogprobs}).Evaluate(context.Background(), "s", nliQs)
	if err != nil {
		t.Fatal(err)
	}
	if p := noul(t, d, "supported"); p < 0.29 || p > 0.31 || !d.Calibrated {
		t.Fatalf("p=%v calibrated=%v", p, d.Calibrated)
	}
}

func TestLLMVerdictWithoutLogprobsNotCalibrated(t *testing.T) {
	srv, _ := replay(t, reply(`{"answers":{"supported":{"type":"noul","verdict":true}}}`))
	d, err := llmClassifier(t, srv.URL, &LLMProfile{Probability: LLMProbabilityLogprobs}).Evaluate(context.Background(), "s", nliQs)
	if err != nil || d.Calibrated || noul(t, d, "supported") != 1 {
		t.Fatalf("got %v %v", d, err)
	}
}

func TestLLMRetriesTransient(t *testing.T) {
	srv, seen := replay(t, nil, reply(`{"answers":{"supported":{"type":"noul","noul":1}}}`))
	if _, err := llmClassifier(t, srv.URL, &LLMProfile{}).Evaluate(context.Background(), "s", nliQs); err != nil {
		t.Fatal(err)
	}
	if len(*seen) != 2 {
		t.Fatalf("want one retry, saw %d calls", len(*seen))
	}
}

func TestLLMProfileValidate(t *testing.T) {
	cl := CreateClient(ClientOptions{BaseURL: "http://x", Style: StyleOpenAI, Model: "m"})
	if _, err := CreateClassifier(ClassifierOptions{Style: StyleLLM, Client: cl, Model: "m",
		LLMProfile: &LLMProfile{Probability: "vibes"}}); err == nil {
		t.Fatal("want a validation error")
	}
}

func TestLLMSchemaChoiceScore(t *testing.T) {
	qs := map[string]Question{
		"c": ChoiceQuestion{Instructions: "pick", Criteria: map[string]string{"b": "B", "a": "A"}},
		"s": ScoreQuestion{Instructions: "rate", Criteria: []string{"low", "high"}},
	}
	srv, _ := replay(t, reply(`{"answers":{"c":{"type":"choice","choice":"a","probabilities":{"a":0.7,"b":0.3},"confidence":0.7},`+
		`"s":{"type":"score","score":1,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.1,"1":0.9},"confidence":0.9}}}`))
	d, err := llmClassifier(t, srv.URL, &LLMProfile{}).Evaluate(context.Background(), "s", qs)
	if err != nil {
		t.Fatal(err)
	}
	if c, _ := d.Choice("c"); c.Choice != "a" {
		t.Fatalf("choice %v", c)
	}
	if s, _ := d.Score("s"); s.Score != 1 {
		t.Fatalf("score %v", s)
	}
}
