package toolnexus

// The `llm` classifier backend as a jev client (§8B): any OpenAI-compatible
// chat model answers the SAME state + typed questions contract, reliably.
//
// What makes an ordinary chat model a jev client, per model, lives in an
// LLMProfile (data, not code — a host keeps one per model in config):
//
//   - output constraint: response_format json_schema built from the questions
//     (guided decoding), json_object, or none;
//   - thinking: RequestParams such as {"reasoning_effort":"none"} — the answer
//     is ALWAYS read from message.content, never from a reasoning field;
//   - probability source: the model's stated number ("self") or the token
//     probability of a boolean verdict ("logprobs", calibrated where found).
//
// Whatever the profile, the reply is parsed tolerantly (the first complete
// JSON object; code fences and trailing prose ignored) and each answer is
// normalised to the asked type when that is unambiguous — a yes/no-shaped
// choice, a bare number or a boolean answering a noul question. Anything else
// fails closed with an error naming the key: an unparseable answer is no
// answer, never a guessed one.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

// LLM profile values.
const (
	LLMFormatJSONSchema = "json_schema"
	LLMFormatJSONObject = "json_object"
	LLMFormatNone       = "none"

	LLMProbabilitySelf     = "self"
	LLMProbabilityLogprobs = "logprobs"
)

// LLMProfile tunes the `llm` backend for one chat model. The zero value is the
// recommended default: json_schema output, self-reported numbers, temperature 0.
type LLMProfile struct {
	// ResponseFormat: "json_schema" (default) | "json_object" | "none".
	ResponseFormat string `json:"response_format,omitempty"`
	// Probability: "self" (default) | "logprobs". logprobs asks noul questions
	// for a boolean verdict and derives P(true) from the verdict token's
	// top_logprobs; a noul whose token is not found falls back to the verdict
	// as 0/1 and the Decision is then NOT calibrated.
	Probability string `json:"probability,omitempty"`
	// MaxTokens caps the completion. 0 ⇒ not sent (provider default).
	MaxTokens int `json:"max_tokens,omitempty"`
	// Temperature. nil ⇒ 0.
	Temperature *float64 `json:"temperature,omitempty"`
	// TopLogprobs for Probability=logprobs. 0 ⇒ 5.
	TopLogprobs int `json:"top_logprobs,omitempty"`
	// RequestParams are extra top-level body keys for this model, e.g.
	// {"reasoning_effort":"none"} to switch a reasoning model's thinking off.
	RequestParams map[string]any `json:"request_params,omitempty"`
}

func (p LLMProfile) format() string {
	if p.ResponseFormat == "" {
		return LLMFormatJSONSchema
	}
	return p.ResponseFormat
}

func (p LLMProfile) logprobs() bool { return p.Probability == LLMProbabilityLogprobs }

// Validate rejects unknown profile values before any call is made.
func (p LLMProfile) Validate() error {
	switch p.format() {
	case LLMFormatJSONSchema, LLMFormatJSONObject, LLMFormatNone:
	default:
		return fmt.Errorf("classifier: llm profile: response_format %q unknown", p.ResponseFormat)
	}
	switch p.Probability {
	case "", LLMProbabilitySelf, LLMProbabilityLogprobs:
	default:
		return fmt.Errorf("classifier: llm profile: probability %q unknown", p.Probability)
	}
	return nil
}

// requestParams is the per-call body additions for this profile + questions.
func (p LLMProfile) requestParams(keys []string, questions map[string]Question) map[string]any {
	out := map[string]any{}
	t := 0.0
	if p.Temperature != nil {
		t = *p.Temperature
	}
	out["temperature"] = t
	if p.MaxTokens > 0 {
		out["max_tokens"] = p.MaxTokens
	}
	switch p.format() {
	case LLMFormatJSONSchema:
		out["response_format"] = map[string]any{
			"type": "json_schema",
			"json_schema": map[string]any{
				"name":   "decision",
				"strict": true,
				"schema": LLMDecisionSchema(keys, questions, p.logprobs()),
			},
		}
	case LLMFormatJSONObject:
		out["response_format"] = map[string]any{"type": "json_object"}
	}
	if p.logprobs() {
		n := p.TopLogprobs
		if n <= 0 {
			n = 5
		}
		out["logprobs"] = true
		out["top_logprobs"] = n
	}
	for k, v := range p.RequestParams {
		out[k] = v
	}
	return out
}

// LLMDecisionSchema is the JSON schema of the reply the `llm` backend asks for.
// Keys are listed in the given order (guided decoders emit properties in schema
// order, which the logprob reader relies on only for locating, never for
// meaning). verdict=true shapes noul answers as a boolean verdict.
func LLMDecisionSchema(keys []string, questions map[string]Question, verdict bool) map[string]any {
	props := map[string]any{}
	for _, k := range keys {
		props[k] = answerSchema(questions[k], verdict)
	}
	return map[string]any{
		"type":                 "object",
		"additionalProperties": false,
		"required":             []any{"answers"},
		"properties": map[string]any{
			"answers": map[string]any{
				"type":                 "object",
				"additionalProperties": false,
				"required":             toAny(keys),
				"properties":           props,
			},
		},
	}
}

func toAny(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}

func obj(required []string, props map[string]any) map[string]any {
	return map[string]any{"type": "object", "additionalProperties": false, "required": toAny(required), "properties": props}
}

func probs(ids []string) map[string]any {
	p := map[string]any{}
	for _, id := range ids {
		p[id] = map[string]any{"type": "number", "minimum": 0, "maximum": 1}
	}
	return obj(ids, p)
}

func answerSchema(q Question, verdict bool) map[string]any {
	unit := map[string]any{"type": "number", "minimum": 0, "maximum": 1}
	switch q := q.(type) {
	case NoulQuestion:
		if verdict {
			return obj([]string{"type", "verdict"}, map[string]any{
				"type":    map[string]any{"type": "string", "enum": []any{"noul"}},
				"verdict": map[string]any{"type": "boolean"},
			})
		}
		return obj([]string{"type", "noul"}, map[string]any{
			"type": map[string]any{"type": "string", "enum": []any{"noul"}},
			"noul": unit,
		})
	case ChoiceQuestion:
		ids := make([]string, 0, len(q.Criteria))
		for id := range q.Criteria {
			ids = append(ids, id)
		}
		sort.Strings(ids)
		return obj([]string{"type", "choice", "probabilities", "confidence"}, map[string]any{
			"type":          map[string]any{"type": "string", "enum": []any{"choice"}},
			"choice":        map[string]any{"type": "string", "enum": toAny(ids)},
			"probabilities": probs(ids),
			"confidence":    unit,
		})
	case ScoreQuestion:
		lv := make([]string, len(q.Criteria))
		legend := map[string]any{}
		for i, s := range q.Criteria {
			lv[i] = strconv.Itoa(i)
			legend[lv[i]] = map[string]any{"type": "string", "enum": []any{s}}
		}
		return obj([]string{"type", "score", "legend", "probabilities", "confidence"}, map[string]any{
			"type":          map[string]any{"type": "string", "enum": []any{"score"}},
			"score":         map[string]any{"type": "number", "minimum": 0, "maximum": len(q.Criteria) - 1},
			"legend":        obj(lv, legend),
			"probabilities": probs(lv),
			"confidence":    unit,
		})
	}
	return map[string]any{"type": "object"}
}

// withCall derives a one-call view of c: extra request params merged over the
// client's own, and onResponse observing the raw provider response (chained
// before any host AfterLLM hook). The derived client shares transport, store
// and metrics with c; c itself is never mutated.
func (c *Client) withCall(extra map[string]any, onResponse func(map[string]any)) *Client {
	o := c.opts
	rp := make(map[string]any, len(o.RequestParams)+len(extra))
	for k, v := range o.RequestParams {
		rp[k] = v
	}
	for k, v := range extra {
		rp[k] = v
	}
	o.RequestParams = rp
	var h Hooks
	if o.Hooks != nil {
		h = *o.Hooks
	}
	prev := h.AfterLLM
	h.AfterLLM = func(ctx context.Context, ev AfterLLMEvent) error {
		if onResponse != nil {
			onResponse(ev.Response)
		}
		if prev != nil {
			return prev(ctx, ev)
		}
		return nil
	}
	o.Hooks = &h
	return &Client{opts: o, http: c.http, store: c.store, registry: c.registry}
}

func llmPrompt(stateJSON, qJSON []byte, verdict bool) string {
	shape := `{"answers":{"<key>":{"type":"noul","noul":0.0}}}`
	noul := `A "noul" answer is {"type":"noul","noul":<0..1>}. `
	if verdict {
		shape = `{"answers":{"<key>":{"type":"noul","verdict":true}}}`
		noul = `A "noul" answer is {"type":"noul","verdict":<true|false>}. `
	}
	return "Answer every question about the state below. Questions are INDEPENDENT: " +
		"one answer is never context for another.\n\n" +
		"STATE:\n" + string(stateJSON) + "\n\nQUESTIONS:\n" + string(qJSON) + "\n\n" +
		"Reply with JSON only, no prose and no code fence, shaped exactly:\n" +
		shape + "\n" + noul +
		`A "choice" answer is ` +
		`{"type":"choice","choice":"<one offered option id>","probabilities":{"<every offered option id>":<0..1>},"confidence":<0..1>}. ` +
		`A "score" answer is {"type":"score","score":<a number within the rubric bounds, fractional allowed>,` +
		`"legend":{"0":"<level 0>",…},"probabilities":{"0":<0..1>,…},"confidence":<0..1>}.`
}

// parseLLMDecision turns the model's reply text into a Decision for exactly the
// asked keys, normalising unambiguous shape slips and failing closed otherwise.
func parseLLMDecision(text string, questions map[string]Question) (Decision, error) {
	payload, err := firstJSONObject(text)
	if err != nil {
		return Decision{}, err
	}
	var top map[string]json.RawMessage
	if err := json.Unmarshal(payload, &top); err != nil {
		return Decision{}, err
	}
	answers := map[string]json.RawMessage{}
	if a, ok := top["answers"]; ok {
		if err := json.Unmarshal(a, &answers); err != nil {
			return Decision{}, fmt.Errorf("answers: %w", err)
		}
	} else {
		answers = top // a model that dropped the envelope but kept the keys
	}
	d := Decision{Answers: map[string]DecisionAnswer{}}
	for _, key := range sortedQuestionKeys(questions) {
		raw, ok := answers[key]
		if !ok {
			return Decision{}, fmt.Errorf("no answer %q in the reply", key)
		}
		a, err := normalizeAnswer(questions[key], raw)
		if err != nil {
			return Decision{}, fmt.Errorf("answer %q: %w", key, err)
		}
		d.Answers[key] = a
	}
	return d, nil
}

func unit(v float64) bool { return v >= 0 && v <= 1 && !math.IsNaN(v) }

// yes/no option ids a choice-shaped reply to a noul question may carry.
var (
	yesWords = map[string]bool{"true": true, "yes": true, "1": true, "supported": true, "entailed": true, "entailment": true, "holds": true}
	noWords  = map[string]bool{"false": true, "no": true, "0": true, "unsupported": true, "not_supported": true, "contradicted": true, "contradiction": true, "neutral": true}
)

func normalizeAnswer(q Question, raw json.RawMessage) (DecisionAnswer, error) {
	var any_ any
	if err := json.Unmarshal(raw, &any_); err != nil {
		return nil, err
	}
	switch q.(type) {
	case NoulQuestion:
		return normalizeNoul(any_)
	case ChoiceQuestion:
		var a ChoiceAnswer
		if err := json.Unmarshal(raw, &a); err != nil || a.Choice == "" {
			return nil, fmt.Errorf("not a choice answer")
		}
		if _, ok := q.(ChoiceQuestion).Criteria[a.Choice]; !ok {
			return nil, fmt.Errorf("choice %q is not an offered option", a.Choice)
		}
		a.NearUniform = NearUniform(a.Probabilities)
		return a, nil
	case ScoreQuestion:
		var a ScoreAnswer
		if err := json.Unmarshal(raw, &a); err != nil {
			return nil, fmt.Errorf("not a score answer")
		}
		if n := float64(len(q.(ScoreQuestion).Criteria) - 1); a.Score < 0 || a.Score > n {
			return nil, fmt.Errorf("score %v outside 0..%v", a.Score, n)
		}
		return a, nil
	}
	return nil, fmt.Errorf("unknown question type")
}

// normalizeNoul maps the shapes models actually return for a noul question.
func normalizeNoul(v any) (DecisionAnswer, error) {
	switch x := v.(type) {
	case float64:
		if unit(x) {
			return NoulAnswer{Noul: x}, nil
		}
	case bool:
		return NoulAnswer{Noul: b01(x)}, nil
	case map[string]any:
		if n, ok := x["noul"].(float64); ok {
			if unit(n) {
				return NoulAnswer{Noul: n}, nil
			}
			return nil, fmt.Errorf("noul %v outside 0..1", n)
		}
		if b, ok := x["verdict"].(bool); ok {
			return NoulAnswer{Noul: b01(b)}, nil
		}
		// choice-shaped: a yes/no distribution or a yes/no pick.
		if p, ok := x["probabilities"].(map[string]any); ok {
			if y, ok := yesProbability(p); ok {
				return NoulAnswer{Noul: y}, nil
			}
		}
		if c, ok := x["choice"].(string); ok {
			k := strings.ToLower(strings.TrimSpace(c))
			if yesWords[k] {
				return NoulAnswer{Noul: 1}, nil
			}
			if noWords[k] {
				return NoulAnswer{Noul: 0}, nil
			}
		}
		return nil, fmt.Errorf("a %v-shaped reply is not a noul answer", x["type"])
	}
	return nil, fmt.Errorf("not a noul answer")
}

// yesProbability reads P(yes) from a two-sided yes/no distribution; anything
// with an option that is neither yes nor no is ambiguous.
func yesProbability(p map[string]any) (float64, bool) {
	var y, n float64
	var sawY, sawN bool
	for k, v := range p {
		f, ok := v.(float64)
		if !ok || !unit(f) {
			return 0, false
		}
		k = strings.ToLower(strings.TrimSpace(k))
		switch {
		case yesWords[k]:
			y += f
			sawY = true
		case noWords[k]:
			n += f
			sawN = true
		default:
			return 0, false
		}
	}
	if !sawY || y+n == 0 {
		return 0, false
	}
	if !sawN {
		return y, unit(y)
	}
	return y / (y + n), true
}

func b01(b bool) float64 {
	if b {
		return 1
	}
	return 0
}

// ---------------------------------------------------------------- logprobs

type lpToken struct {
	Token   string  `json:"token"`
	Logprob float64 `json:"logprob"`
	Top     []struct {
		Token   string  `json:"token"`
		Logprob float64 `json:"logprob"`
	} `json:"top_logprobs"`
}

// responseLogprobs pulls choices[0].logprobs.content from a raw response.
func responseLogprobs(resp map[string]any) []lpToken {
	ch, _ := resp["choices"].([]any)
	if len(ch) == 0 {
		return nil
	}
	c0, _ := ch[0].(map[string]any)
	lp, _ := c0["logprobs"].(map[string]any)
	if lp == nil {
		return nil
	}
	b, err := json.Marshal(lp["content"])
	if err != nil {
		return nil
	}
	var toks []lpToken
	_ = json.Unmarshal(b, &toks)
	return toks
}

// verdictOffsets finds, for each noul key, the byte offset in content where the
// value of answers.<key>.verdict starts.
func verdictOffsets(content string) map[string]int {
	start := strings.IndexByte(content, '{')
	if start < 0 {
		return nil
	}
	dec := json.NewDecoder(strings.NewReader(content[start:]))
	out := map[string]int{}
	var path []string // object keys along the current path; "" for arrays
	var expectKey []bool
	var pending string
	for {
		before := dec.InputOffset()
		tok, err := dec.Token()
		if err != nil {
			return out
		}
		switch t := tok.(type) {
		case json.Delim:
			switch t {
			case '{', '[':
				path = append(path, pending)
				expectKey = append(expectKey, t == '{')
				pending = ""
			case '}', ']':
				path = path[:len(path)-1]
				expectKey = expectKey[:len(expectKey)-1]
				if n := len(expectKey); n > 0 {
					expectKey[n-1] = true
				}
			}
			if len(path) == 0 {
				return out
			}
			continue
		}
		top := len(expectKey) - 1
		if top >= 0 && expectKey[top] {
			if s, ok := tok.(string); ok {
				pending = s
				expectKey[top] = false
				continue
			}
		}
		// a value
		if b, ok := tok.(bool); ok && pending == "verdict" && len(path) == 3 && path[1] == "answers" {
			lit := "false"
			if b {
				lit = "true"
			}
			end := int(dec.InputOffset())
			off := strings.LastIndex(content[start+int(before):start+end], lit)
			if off >= 0 {
				out[path[2]] = start + int(before) + off
			}
		}
		if top >= 0 {
			expectKey[top] = true
		}
		pending = ""
	}
}

// verdictProbabilities maps each located verdict to P(true) from the token
// covering it. Keys whose token cannot be located or scored are absent.
func verdictProbabilities(content string, toks []lpToken) map[string]float64 {
	if len(toks) == 0 {
		return nil
	}
	var all strings.Builder
	for _, t := range toks {
		all.WriteString(t.Token)
	}
	full := all.String()
	base := strings.LastIndex(full, content)
	if base < 0 {
		return nil
	}
	out := map[string]float64{}
	for key, off := range verdictOffsets(content) {
		pos := base + off
		at := 0
		for _, t := range toks {
			if pos >= at && pos < at+len(t.Token) {
				if p, ok := trueProbability(t); ok {
					out[key] = p
				}
				break
			}
			at += len(t.Token)
		}
	}
	return out
}

func trueProbability(t lpToken) (float64, bool) {
	var pt, pf float64
	cands := t.Top
	if len(cands) == 0 {
		cands = append(cands, struct {
			Token   string  `json:"token"`
			Logprob float64 `json:"logprob"`
		}{t.Token, t.Logprob})
	}
	for _, c := range cands {
		s := strings.ToLower(strings.TrimLeft(c.Token, " \t\n:\""))
		if s == "" {
			continue
		}
		p := math.Exp(c.Logprob)
		switch {
		case strings.HasPrefix("true", s) || strings.HasPrefix(s, "true"):
			pt += p
		case strings.HasPrefix("false", s) || strings.HasPrefix(s, "false"):
			pf += p
		}
	}
	if pt+pf == 0 {
		return 0, false
	}
	return pt / (pt + pf), true
}

// ---------------------------------------------------------------- evaluate

func (c *Classifier) evaluateLLM(ctx context.Context, state any, questions map[string]Question) (Decision, error) {
	stateJSON, err := canonicalJSON(state)
	if err != nil {
		return Decision{}, err
	}
	qs := make(map[string]any, len(questions))
	for k, q := range questions {
		qs[k] = q.wire()
	}
	qJSON, err := canonicalJSON(qs)
	if err != nil {
		return Decision{}, err
	}
	var prof LLMProfile
	legacy := c.opts.LLMProfile == nil
	if !legacy {
		prof = *c.opts.LLMProfile
	}
	verdict := !legacy && prof.logprobs()
	prompt := llmPrompt(stateJSON, qJSON, verdict)

	cl := c.opts.Client
	var resp map[string]any
	if !legacy {
		keys := sortedQuestionKeys(questions)
		params := map[string]any{}
		if cl.opts.Style == "" || cl.opts.Style == StyleOpenAI {
			params = prof.requestParams(keys, questions)
		} else if prof.MaxTokens > 0 {
			params["max_tokens"] = prof.MaxTokens
		}
		cl = cl.withCall(params, func(r map[string]any) { resp = r })
	}
	run, err := cl.Run(ctx, prompt, nil)
	if err != nil {
		return Decision{}, err
	}
	d, err := parseLLMDecision(run.Text, questions)
	if err != nil {
		if reason := finishReason(resp); reason != "" && reason != "stop" {
			err = fmt.Errorf("%w (finish_reason=%s)", err, reason)
		}
		return Decision{}, fmt.Errorf("classifier: llm: %w", err)
	}
	// Self-reported numbers are never calibrated (ADR 0020). logprobs mode is
	// calibrated only when EVERY noul answer came from a verdict token.
	d.Calibrated = false
	if verdict {
		ps := verdictProbabilities(run.Text, responseLogprobs(resp))
		all, any_ := true, false
		for k, q := range questions {
			if _, ok := q.(NoulQuestion); !ok {
				continue
			}
			any_ = true
			if p, ok := ps[k]; ok {
				d.Answers[k] = NoulAnswer{Noul: p}
			} else {
				all = false
			}
		}
		d.Calibrated = any_ && all
	}
	d.Model = c.opts.Model
	if run.Model != "" {
		d.Model = run.Model
	}
	d.Usage = ClassifierUsage{
		InputTokens:  run.Usage.PromptTokens,
		OutputTokens: run.Usage.CompletionTokens,
	}
	return d, nil
}

func finishReason(resp map[string]any) string {
	ch, _ := resp["choices"].([]any)
	if len(ch) == 0 {
		return ""
	}
	c0, _ := ch[0].(map[string]any)
	s, _ := c0["finish_reason"].(string)
	return s
}

// firstJSONObject returns the FIRST complete JSON object in a model reply —
// fences, leading prose and anything after it (a closing fence, a second
// object, a note) are ignored. It does NOT repair malformed JSON — an
// unparseable answer is no answer (ADR 0020).
func firstJSONObject(s string) ([]byte, error) {
	for i := 0; i < len(s); i++ {
		if s[i] != '{' {
			continue
		}
		dec := json.NewDecoder(strings.NewReader(s[i:]))
		var raw json.RawMessage
		if err := dec.Decode(&raw); err == nil && len(bytes.TrimSpace(raw)) > 0 && raw[0] == '{' {
			return raw, nil
		}
	}
	return nil, fmt.Errorf("no JSON object in the reply")
}
