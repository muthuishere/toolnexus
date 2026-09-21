// Package classifier is a THROWAWAY spike for ADR 0020 (§8B `Classifier`).
// Nothing here is imported by the real port; it exists to cost the three-shaped
// `criteria` union and the type-discriminated `answers` map in Go.
package classifier

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"time"
)

// ---------------------------------------------------------------- questions

// Question is one pre-declared question. The three shapes differ ONLY in what
// `criteria` is on the wire — absent, an object, or an ordered array — so the
// interface is "give me your wire form", not a field set.
type Question interface {
	wire() map[string]any
}

// Noul is a 0..1 truth question. criteria is OPTIONAL: {true,false} labels.
type Noul struct {
	Instructions string
	True, False  string // both empty ⇒ criteria omitted entirely
}

func (q Noul) wire() map[string]any {
	m := map[string]any{"type": "noul", "instructions": q.Instructions}
	if q.True != "" || q.False != "" {
		m["criteria"] = map[string]any{"true": q.True, "false": q.False}
	}
	return m
}

// Choice is a named-option question. criteria is an OBJECT {name: description};
// a nil description is allowed by the wire, so the value type is any.
type Choice struct {
	Instructions string
	Criteria     map[string]any // name -> description|nil
}

func (q Choice) wire() map[string]any {
	c := map[string]any{}
	for k, v := range q.Criteria {
		c[k] = v
	}
	return map[string]any{"type": "choice", "instructions": q.Instructions, "criteria": c}
}

// Score is an ordered-levels question. criteria is an ARRAY and its ORDER IS
// THE MEANING (index = level), so it must never be sorted.
type Score struct {
	Instructions string
	Criteria     []string
}

func (q Score) wire() map[string]any {
	c := make([]any, len(q.Criteria))
	for i, s := range q.Criteria {
		c[i] = s
	}
	return map[string]any{"type": "score", "instructions": q.Instructions, "criteria": c}
}

// ChoiceOver builds a Choice from any (name, description) pairs — Tool,
// skill, agent Def, A2A card skill. Spiked only for shape.
func ChoiceOver(instructions string, items map[string]string) Choice {
	c := map[string]any{}
	for k, v := range items {
		c[k] = v
	}
	return Choice{Instructions: instructions, Criteria: c}
}

// ---------------------------------------------------------------- the wire

// CanonicalRequest is the §D2 conformance body: recursively key-sorted, compact,
// arrays untouched, no trailing newline.
func CanonicalRequest(model string, state any, questions map[string]Question) ([]byte, error) {
	qs := map[string]any{}
	for k, q := range questions {
		qs[k] = q.wire()
	}
	return canonical(map[string]any{"model": model, "state": state, "questions": qs})
}

// canonical marshals through map[string]any ONLY. encoding/json sorts map keys
// (ASCII) recursively for free and leaves slice order alone — which is exactly
// the canonical form the spec asks for. HTML escaping is disabled so `<`/`>`/`&`
// in an instruction survive as themselves.
func canonical(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil // Encode always appends one
}

// ---------------------------------------------------------------- answers

// Answer is one typed answer. The map is heterogeneous and discriminated by the
// `type` field, so decoding is a two-pass: RawMessage, peek `type`, re-decode.
type Answer interface{ AnswerType() string }

type NoulAnswer struct {
	Noul float64 `json:"noul"`
}

func (NoulAnswer) AnswerType() string { return "noul" }

type ChoiceAnswer struct {
	Choice        string             `json:"choice"`
	Probabilities map[string]float64 `json:"probabilities"`
	Confidence    float64            `json:"confidence"`
}

func (ChoiceAnswer) AnswerType() string { return "choice" }

type ScoreAnswer struct {
	Score         float64            `json:"score"`
	Legend        map[string]string  `json:"legend"`
	Probabilities map[string]float64 `json:"probabilities"`
	Confidence    float64            `json:"confidence"`
}

func (ScoreAnswer) AnswerType() string { return "score" }

// Usage mirrors the wire; Cost is absent on some backends.
type Usage struct {
	InputTokens  int     `json:"input_tokens"`
	OutputTokens int     `json:"output_tokens"`
	Cost         float64 `json:"cost,omitempty"`
}

// Decision is what evaluate returns.
type Decision struct {
	Model      string
	Answers    map[string]Answer
	Usage      Usage
	Calibrated bool
}

// UnmarshalJSON does the discriminated decode. One custom unmarshaller for the
// whole Decision is cheaper than one per answer type.
func (d *Decision) UnmarshalJSON(b []byte) error {
	var raw struct {
		Model   string                     `json:"model"`
		Answers map[string]json.RawMessage `json:"answers"`
		Usage   Usage                      `json:"usage"`
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	d.Model, d.Usage, d.Calibrated = raw.Model, raw.Usage, true
	d.Answers = make(map[string]Answer, len(raw.Answers))
	for k, r := range raw.Answers {
		var disc struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(r, &disc); err != nil {
			return fmt.Errorf("answer %q: %w", k, err)
		}
		var a Answer
		switch disc.Type {
		case "noul":
			var v NoulAnswer
			if err := json.Unmarshal(r, &v); err != nil {
				return fmt.Errorf("answer %q: %w", k, err)
			}
			a = v
		case "choice":
			var v ChoiceAnswer
			if err := json.Unmarshal(r, &v); err != nil {
				return fmt.Errorf("answer %q: %w", k, err)
			}
			a = v
		case "score":
			var v ScoreAnswer
			if err := json.Unmarshal(r, &v); err != nil {
				return fmt.Errorf("answer %q: %w", k, err)
			}
			a = v
		default:
			return fmt.Errorf("answer %q: unknown type %q", k, disc.Type)
		}
		d.Answers[k] = a
	}
	return nil
}

// Noul/Choice/Score are typed accessors so a caller never type-asserts by hand.
func (d Decision) Noul(key string) (NoulAnswer, error) {
	v, ok := d.Answers[key].(NoulAnswer)
	return v, answerErr(key, ok, "noul")
}
func (d Decision) Choice(key string) (ChoiceAnswer, error) {
	v, ok := d.Answers[key].(ChoiceAnswer)
	return v, answerErr(key, ok, "choice")
}
func (d Decision) Score(key string) (ScoreAnswer, error) {
	v, ok := d.Answers[key].(ScoreAnswer)
	return v, answerErr(key, ok, "score")
}
func answerErr(key string, ok bool, want string) error {
	if ok {
		return nil
	}
	return fmt.Errorf("answer %q is not a %s answer", key, want)
}

// Levels returns a ScoreAnswer's legend in level order (the map loses it).
func (a ScoreAnswer) Levels() []string {
	keys := make([]string, 0, len(a.Legend))
	for k := range a.Legend {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		return len(keys[i]) < len(keys[j]) || (len(keys[i]) == len(keys[j]) && keys[i] < keys[j])
	})
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		out = append(out, a.Legend[k])
	}
	return out
}

// ---------------------------------------------------------------- classifier

// Options mirrors tn.ClientOptions field-for-field where it makes sense (D3).
type Options struct {
	Style     string // "systemone" | "static"
	BaseURL   string
	Model     string
	APIKeyEnv string // the NAME of the env var; read at call time, never logged
	Headers   map[string]string
	Timeout   time.Duration
	// Fixtures is the `static` backend: canonical request bytes -> response body.
	Fixtures map[string][]byte
	// HTTPClient overrides the client for the systemone path (ADR 0019 seam).
	HTTPClient *http.Client
}

// Classifier is the core contract: one verb.
type Classifier struct{ opts Options }

func New(o Options) *Classifier {
	if o.Style == "" {
		o.Style = "systemone"
	}
	if o.Model == "" {
		o.Model = "typesafe/jev-1.13"
	}
	if o.BaseURL == "" {
		o.BaseURL = "https://api.typesafe.ai/v1"
	}
	if o.APIKeyEnv == "" {
		o.APIKeyEnv = "TYPESAFE_API_KEY"
	}
	if o.Timeout == 0 {
		o.Timeout = 10 * time.Second
	}
	return &Classifier{opts: o}
}

// Evaluate is the whole contract.
func (c *Classifier) Evaluate(ctx context.Context, state any, questions map[string]Question) (Decision, error) {
	body, err := CanonicalRequest(c.opts.Model, state, questions)
	if err != nil {
		return Decision{}, err
	}
	var raw []byte
	switch c.opts.Style {
	case "static":
		r, ok := c.opts.Fixtures[string(body)]
		if !ok {
			return Decision{}, fmt.Errorf("static: no fixture for this request (%d bytes)", len(body))
		}
		raw = r
	case "systemone":
		raw, err = c.post(ctx, body)
		if err != nil {
			return Decision{}, err
		}
	default:
		return Decision{}, fmt.Errorf("unknown style %q", c.opts.Style)
	}
	var d Decision
	if err := json.Unmarshal(raw, &d); err != nil {
		return Decision{}, err
	}
	return d, nil
}

// post is raw net/http — no SDK, per the ADR's D5.
func (c *Classifier) post(ctx context.Context, body []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, c.opts.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.opts.BaseURL, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if key := os.Getenv(c.opts.APIKeyEnv); key != "" { // read at call time, never logged
		req.Header.Set("Authorization", "Bearer "+key)
	}
	for k, v := range c.opts.Headers {
		req.Header.Set(k, os.ExpandEnv(v))
	}
	hc := c.opts.HTTPClient
	if hc == nil {
		hc = http.DefaultClient
	}
	res, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	b, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, err
	}
	if res.StatusCode >= 300 {
		return nil, fmt.Errorf("classifier: HTTP %d: %s", res.StatusCode, truncate(b))
	}
	return b, nil
}

func truncate(b []byte) string {
	if len(b) > 200 {
		return string(b[:200]) + "…"
	}
	return string(b)
}
