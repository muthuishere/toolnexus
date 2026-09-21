package toolnexus

// Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as Tool is the contract
// for an ACTION. A System One model takes a state plus pre-declared, typed
// questions and returns calibrated answers with no free text. It has no messages,
// no tool calling and no streaming, so it never enters the client loop.
//
// A classifier INTERPRETS; it never AUTHORISES. Schema validity is not
// correctness: a decision can be confidently wrong, and "cannot hallucinate"
// means only that the returned value is in the declared schema. Numeric limits,
// permission checks and allowlists stay in code. Nothing here is a security
// control.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// ---------------------------------------------------------------- constants

const (
	// DefaultClassifierBaseURL is the System One endpoint base.
	DefaultClassifierBaseURL = "https://api.typesafe.ai/v1"
	// DefaultClassifierModel is the floating alias. Pin it once thresholds are tuned.
	DefaultClassifierModel = "jev-latest"
	// DefaultClassifierAPIKeyEnv is the NAME of the env var holding the credential.
	DefaultClassifierAPIKeyEnv = "TYPESAFE_API_KEY"
	// DefaultClassifierTimeout bounds one request (a classifier has no loop to bound).
	DefaultClassifierTimeout = 10 * time.Second

	// MaxChoiceOptions is the client-side cap on a choice's named options (§8B).
	MaxChoiceOptions = 255
	// MinScoreLevels / MaxScoreLevels bound a score rubric (§8B).
	MinScoreLevels = 2
	MaxScoreLevels = 10

	// NearUniformTolerance is the ABSOLUTE tolerance on max|p - 1/n|, compared
	// INCLUSIVELY (§8B). Pinned across every port; fixtures pin both sides.
	NearUniformTolerance = 0.05

	// MetricClassifierEvaluate / MetricClassifierWarning are the two MetricEvent
	// Event values this file emits into the §8 OnMetric sink. Neither is folded
	// into the Prometheus registry, so Client.Metrics() text is unchanged.
	MetricClassifierEvaluate = "classifier.evaluate"
	MetricClassifierWarning  = "classifier.warning"
)

// ClassifierStyle selects the backend (§8B Backends).
type ClassifierStyle string

const (
	// StyleSystemOne posts the canonical body to {baseURL}/systemone.
	StyleSystemOne ClassifierStyle = "systemone"
	// StyleLLM renders the questions as one structured-output call on a §8 Client.
	// It reports Calibrated false unless the backend read token probabilities.
	StyleLLM ClassifierStyle = "llm"
	// StyleCustom delegates to the host's own Evaluate.
	StyleCustom ClassifierStyle = "custom"
	// StyleStatic answers from a recorded corpus. This is what CI runs: no
	// network, no credential.
	StyleStatic ClassifierStyle = "static"
)

// ---------------------------------------------------------------- questions

// Question is one pre-declared question. The set is CLOSED: the only
// implementations are NoulQuestion, ChoiceQuestion and ScoreQuestion, enforced by
// the unexported marker method. The three shapes differ only in what `criteria`
// is on the wire — absent, an object, or an ordered array — so the interface is
// "give me your wire form", not a field set.
//
// The projection goes through map[string]any rather than struct tags because
// struct tags make field order a comment convention, and `omitempty` cannot tell
// an ABSENT field from an EMPTY one — a distinction the wire carries (§8B).
type Question interface {
	isQuestion()
	wire() map[string]any
	// validate enforces the client-side limits, naming the offending key.
	validate(key string) error
}

// NoulCriteria labels the true and false cases of a noul question. Absent and
// empty are different values and both are preserved on the wire, which is why
// NoulQuestion.Criteria is a pointer: nil omits `criteria` entirely, while a
// non-nil pointer to a zero value emits {"false":"","true":""}.
type NoulCriteria struct {
	True  string
	False string
}

// NoulQuestion asks for the probability that a statement holds, one number in
// 0..1. It reports NO confidence: the number is the answer.
type NoulQuestion struct {
	Instructions string
	// Criteria is optional. nil ⇒ the field is absent from the request.
	Criteria *NoulCriteria
}

func (NoulQuestion) isQuestion() {}

func (q NoulQuestion) wire() map[string]any {
	m := map[string]any{"type": "noul", "instructions": q.Instructions}
	if q.Criteria != nil {
		m["criteria"] = map[string]any{"true": q.Criteria.True, "false": q.Criteria.False}
	}
	return m
}

func (q NoulQuestion) validate(string) error { return nil }

// ChoiceQuestion asks for one option from a named set, 1..255 options.
//
// THE ENCODING OBLIGATION IS THE CALLER'S (§8B, docs/adr/0021 D1): Criteria[id]
// is the only thing that differentiates one option from another to the model.
// Passing the id itself, an empty string, or one value repeated is schema-valid,
// returns HTTP 200 and a well-formed distribution — and ranks at chance
// (measured: 17 apples described by consequence, 0/1/0 described by id).
type ChoiceQuestion struct {
	Instructions string
	Criteria     map[string]string // option id -> what picking it would MEAN
}

func (ChoiceQuestion) isQuestion() {}

func (q ChoiceQuestion) wire() map[string]any {
	c := make(map[string]any, len(q.Criteria))
	for k, v := range q.Criteria {
		c[k] = v
	}
	return map[string]any{"type": "choice", "instructions": q.Instructions, "criteria": c}
}

func (q ChoiceQuestion) validate(key string) error {
	if n := len(q.Criteria); n < 1 || n > MaxChoiceOptions {
		return fmt.Errorf("classifier: question %q: a choice needs 1..%d options, got %d",
			key, MaxChoiceOptions, n)
	}
	return nil
}

// ScoreQuestion asks for a rating against an ORDERED rubric of 2..10 levels. The
// slice order IS the level numbering, so it is never sorted — a "sort
// everything" canonicaliser silently renumbers the rubric.
type ScoreQuestion struct {
	Instructions string
	Criteria     []string
}

func (ScoreQuestion) isQuestion() {}

func (q ScoreQuestion) wire() map[string]any {
	c := make([]any, len(q.Criteria))
	for i, s := range q.Criteria {
		c[i] = s
	}
	return map[string]any{"type": "score", "instructions": q.Instructions, "criteria": c}
}

func (q ScoreQuestion) validate(key string) error {
	if n := len(q.Criteria); n < MinScoreLevels || n > MaxScoreLevels {
		return fmt.Errorf("classifier: question %q: a score needs %d..%d ordered levels, got %d",
			key, MinScoreLevels, MaxScoreLevels, n)
	}
	return nil
}

// ChoiceOver builds a ChoiceQuestion from any (name, description) pairs — a
// Tool, a skill, an agent, an A2A card skill. The description must say what
// picking that option would MEAN; see the encoding obligation above.
func ChoiceOver(instructions string, items map[string]string) ChoiceQuestion {
	c := make(map[string]string, len(items))
	for k, v := range items {
		c[k] = v
	}
	return ChoiceQuestion{Instructions: instructions, Criteria: c}
}

// ---------------------------------------------------------------- the wire

// CanonicalRequest returns the bytes the byte-identity claim covers: `model` +
// `questions`, keys sorted recursively in ASCII order, arrays never reordered,
// compact separators, and <>&'" plus non-ASCII transmitted raw.
//
// `state` is deliberately NOT here. It is transmitted verbatim as the host
// supplied it and is outside the claim, because numbers do not canonicalise
// across languages (-0.0 renders four ways across our own seven runtimes). Do
// not re-widen this: a caller who needs their state pinned canonicalises it
// themselves before handing it over.
func CanonicalRequest(model string, questions map[string]Question) ([]byte, error) {
	qs := make(map[string]any, len(questions))
	for k, q := range questions {
		if q == nil {
			return nil, fmt.Errorf("classifier: question %q is nil", k)
		}
		qs[k] = q.wire()
	}
	return canonicalJSON(map[string]any{"model": model, "questions": qs})
}

// canonicalJSON marshals through map[string]any / []any only. encoding/json
// sorts map keys in ASCII order recursively for free and leaves slice order
// alone — exactly the canonical form §8B asks for. SetEscapeHTML(false) is
// MANDATORY: Go escapes <, > and & into </>/& by default, and the
// base fixture would not catch it.
func canonicalJSON(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil // Encode always appends one
}

// ---------------------------------------------------------------- answers

// DecisionAnswer is one typed answer. The answers map is heterogeneous and
// discriminated by the wire's `type` field, so decoding is a two-pass: keep the
// raw bytes, peek `type`, re-decode into the concrete shape. The set is closed.
//
// It is DecisionAnswer rather than Answer because §10 already owns Answer (the
// suspension resolution). Same idea, different seam.
type DecisionAnswer interface {
	isDecisionAnswer()
	// AnswerType is the wire discriminator: "noul" | "choice" | "score".
	AnswerType() string
}

// NoulAnswer is the probability that a statement holds. It carries NO
// confidence: the number is the answer.
type NoulAnswer struct {
	Noul float64 `json:"noul"`
}

func (NoulAnswer) isDecisionAnswer()  {}
func (NoulAnswer) AnswerType() string { return "noul" }

// ChoiceAnswer is one option from the offered set, with a probability for every
// offered option.
type ChoiceAnswer struct {
	Choice        string             `json:"choice"`
	Probabilities map[string]float64 `json:"probabilities"`
	Confidence    float64            `json:"confidence"`
	// NearUniform is DERIVED from Probabilities on decode and never read from the
	// wire (§8B): max|p - 1/n| <= 0.05, inclusive, n = len(Probabilities).
	//
	// ADVISORY, NOT a correctness signal. It detects an encoding that gave the
	// model nothing to rank on — it cannot distinguish a good encoding from a
	// subtly wrong one. Calibrated carries the same caveat.
	NearUniform bool `json:"-"`
}

func (ChoiceAnswer) isDecisionAnswer()  {}
func (ChoiceAnswer) AnswerType() string { return "choice" }

// ScoreAnswer is a rating against the ordered rubric. Score MAY fall between
// levels (1.21 is a real answer) and is always within the rubric's bounds.
type ScoreAnswer struct {
	Score         float64            `json:"score"`
	Legend        map[string]string  `json:"legend"`
	Probabilities map[string]float64 `json:"probabilities"`
	Confidence    float64            `json:"confidence"`
}

func (ScoreAnswer) isDecisionAnswer()  {}
func (ScoreAnswer) AnswerType() string { return "score" }

// Levels returns the legend in level order, which the map itself loses.
func (a ScoreAnswer) Levels() []string {
	keys := make([]string, 0, len(a.Legend))
	for k := range a.Legend {
		keys = append(keys, k)
	}
	// Numeric-by-length-then-lexicographic: "2" < "10" as levels, unlike as strings.
	sort.Slice(keys, func(i, j int) bool {
		if len(keys[i]) != len(keys[j]) {
			return len(keys[i]) < len(keys[j])
		}
		return keys[i] < keys[j]
	})
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		out = append(out, a.Legend[k])
	}
	return out
}

// ClassifierUsage mirrors the wire's usage block. Cost is absent on some
// backends. Named apart from the §8 Usage, which counts a client run.
type ClassifierUsage struct {
	InputTokens  int `json:"input_tokens"`
	OutputTokens int `json:"output_tokens"`
	// Cost is nil when the backend did not report one — TypeSafe's own API never
	// does. Absent is NOT zero: a nil here means "this backend does not say",
	// while a real 0 means the call was free, and printing $0.00 for the first
	// would be a lie about money.
	Cost *float64 `json:"cost,omitempty"`
}

// Decision carries one answer per question, keyed by the CALLER's keys. The keys
// are addressing, not content: they are never transmitted, so a key may be a
// tool, skill or agent name verbatim.
type Decision struct {
	// Model echoes what actually answered, which may be more specific than the
	// model that was asked for.
	Model   string
	Answers map[string]DecisionAnswer
	Usage   ClassifierUsage
	// Calibrated reports whether the probabilities are calibrated. systemone
	// reports true; llm reports false unless it derived them from provider token
	// probabilities. A THRESHOLD TUNED AGAINST ONE BACKEND DOES NOT TRANSFER TO
	// ANOTHER.
	Calibrated bool
}

// UnmarshalJSON performs the discriminated decode. One custom unmarshaller for
// the whole Decision is cheaper than one per answer type, and it is the single
// place NearUniform is derived.
func (d *Decision) UnmarshalJSON(b []byte) error {
	var raw struct {
		Model      string                     `json:"model"`
		Answers    map[string]json.RawMessage `json:"answers"`
		Usage      ClassifierUsage            `json:"usage"`
		Calibrated *bool                      `json:"calibrated"`
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	d.Model, d.Usage = raw.Model, raw.Usage
	// Absent ⇒ true: the systemone wire reports calibration by being itself. A
	// backend that is not calibrated says so explicitly.
	d.Calibrated = raw.Calibrated == nil || *raw.Calibrated
	d.Answers = make(map[string]DecisionAnswer, len(raw.Answers))
	for k, r := range raw.Answers {
		var disc struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(r, &disc); err != nil {
			return fmt.Errorf("classifier: answer %q: %w", k, err)
		}
		var a DecisionAnswer
		switch disc.Type {
		case "noul":
			var v NoulAnswer
			if err := json.Unmarshal(r, &v); err != nil {
				return fmt.Errorf("classifier: answer %q: %w", k, err)
			}
			a = v
		case "choice":
			var v ChoiceAnswer
			if err := json.Unmarshal(r, &v); err != nil {
				return fmt.Errorf("classifier: answer %q: %w", k, err)
			}
			v.NearUniform = NearUniform(v.Probabilities)
			a = v
		case "score":
			var v ScoreAnswer
			if err := json.Unmarshal(r, &v); err != nil {
				return fmt.Errorf("classifier: answer %q: %w", k, err)
			}
			a = v
		default:
			return fmt.Errorf("classifier: answer %q: unknown type %q", k, disc.Type)
		}
		d.Answers[k] = a
	}
	return nil
}

// NearUniform reports whether a choice answer's probability map is
// indistinguishable from flat (§8B):
//
//	nearUniform  ⇔  max over i of |p_i - 1/n|  <=  0.05
//
// n is the number of ENTRIES IN THE MAP and the values are taken AS RETURNED —
// not renormalised, not sorted, not rounded; an offered option absent from the
// map counts as 0 by not being an entry. The tolerance is ABSOLUTE (a relative
// band collapses below the wire's two-decimal rounding on a 255-option roster)
// and the comparison is INCLUSIVE. n == 1 is trivially uniform. An empty map has
// no distribution at all and is reported false.
func NearUniform(probabilities map[string]float64) bool {
	n := len(probabilities)
	if n == 0 {
		return false
	}
	if n == 1 {
		return true
	}
	target := 1.0 / float64(n)
	for _, p := range probabilities {
		if math.Abs(p-target) > NearUniformTolerance {
			return false
		}
	}
	return true
}

// Noul, Choice and Score are typed accessors, so a wrong-type read is an error
// rather than a panic and a caller never type-asserts by hand.
func (d Decision) Noul(key string) (NoulAnswer, error) {
	v, ok := d.Answers[key].(NoulAnswer)
	return v, answerTypeErr(d, key, ok, "noul")
}

func (d Decision) Choice(key string) (ChoiceAnswer, error) {
	v, ok := d.Answers[key].(ChoiceAnswer)
	return v, answerTypeErr(d, key, ok, "choice")
}

func (d Decision) Score(key string) (ScoreAnswer, error) {
	v, ok := d.Answers[key].(ScoreAnswer)
	return v, answerTypeErr(d, key, ok, "score")
}

func answerTypeErr(d Decision, key string, ok bool, want string) error {
	if ok {
		return nil
	}
	if a, present := d.Answers[key]; present {
		return fmt.Errorf("classifier: answer %q is a %s answer, not %s", key, a.AnswerType(), want)
	}
	return fmt.Errorf("classifier: no answer %q in this decision", key)
}

// ---------------------------------------------------------------- options

// RecordedDecision is one entry of the `static` corpus. It is keyed on the
// canonical request AND the state: several recorded entries legitimately share
// one questions payload and differ only in state (the three guard bands do
// exactly that), so a corpus keyed on the canonical request alone cannot tell
// them apart.
type RecordedDecision struct {
	State     any
	Questions map[string]Question
	// Response is the recorded backend response body, verbatim.
	Response []byte
}

// ClassifierOptions mirrors §8 ClientOptions field-for-field wherever a field
// makes sense, so a host that has configured one has configured the other.
type ClassifierOptions struct {
	// Style selects the backend. "" ⇒ StyleSystemOne.
	Style ClassifierStyle
	// BaseURL is the API base. "" ⇒ DefaultClassifierBaseURL. OpenRouter
	// (https://openrouter.ai/api/v1) serves this wire today.
	BaseURL string
	// Model. "" ⇒ DefaultClassifierModel. Pin it once thresholds are tuned.
	Model string
	// APIKeyEnv is the NAME of the env var holding the credential, never the
	// value — read at call time and never logged. "" ⇒ DefaultClassifierAPIKeyEnv.
	// §8's APIKey takes a value; this option deliberately does not.
	APIKeyEnv string
	// Headers are extra request headers. Values expand ${ENV_VAR} from the
	// environment AT CALL TIME and are NEVER logged, identically to remote-MCP
	// headers (§2).
	Headers map[string]string
	// Timeout bounds ONE request (a classifier has no loop to bound).
	// 0 ⇒ DefaultClassifierTimeout.
	Timeout time.Duration
	// HTTPClient (§8 Gap 2) overrides the transport. Scope is the classifier path
	// only. Nil ⇒ http.DefaultClient.
	HTTPClient *http.Client
	// Retries on transient errors (408/429/500/502/503/504/529 + network). 0 ⇒ 2.
	// Widen the status set with RetryableStatuses.
	Retries int
	// RetryBaseMs is the base of the retry backoff in ms (base * 2^attempt, no
	// jitter — a Retry-After header still wins). 0 ⇒ 500, the §8
	// ClientOptions.RetryBaseMs default this mirrors.
	RetryBaseMs int
	// RetryableStatuses are extra HTTP statuses to treat as retryable, ADDED to
	// the default set (429/500/502/503/504/529, plus 408 here). It can only
	// widen: a host cannot remove 429 and lose Retry-After handling with it. This
	// sets the DEFAULT classification; OnError still runs per attempt and has the
	// final say, so OnError returning TierFail overrides a status listed here.
	// Example: a Cloudflare-fronted origin that answers 520–527.
	RetryableStatuses []int
	// OnError (§8 Resilience) classifies a failed attempt into TierRetry or
	// TierFail. Nil ⇒ the default classifier. REUSES the client's ErrorInfo/Tier
	// and the Retry-After delay-seconds rule verbatim — there is no second retry
	// policy, and no "suspend" tier here either.
	OnError func(ErrorInfo) Tier
	// RequestParams (§8 Gap 1) are extra top-level keys shallow-merged into the
	// request body after the classifier builds its own; a RequestParams key WINS
	// on collision. Nil ⇒ body byte-identical.
	RequestParams map[string]any
	// BodyTransform (§8 Gap 1) receives the assembled body after the
	// RequestParams merge and returns the body to send. Returning nil ⇒
	// unchanged. Order is: base body → RequestParams merge → BodyTransform →
	// marshal, exactly as §8.
	BodyTransform func(body map[string]any) map[string]any
	// OnMetric emits MetricClassifierEvaluate events into the SAME §8 sink, and
	// carries the degenerate-criteria warning as MetricClassifierWarning.
	OnMetric func(MetricEvent)
	// Client is the §8 Client to emulate over. StyleLLM only.
	Client *Client
	// Evaluate is the host's own function. StyleCustom only; every wire option is
	// ignored.
	Evaluate func(ctx context.Context, state any, questions map[string]Question) (Decision, error)
	// Decisions is the recorded corpus. StyleStatic only.
	Decisions []RecordedDecision
}

// ---------------------------------------------------------------- classifier

// Classifier is the whole seam: one verb.
type Classifier struct {
	opts   ClassifierOptions
	static map[string][]byte
	// warned is the once-per-question-key set for the degenerate-criteria
	// warning, so a per-turn judge does not flood the sink.
	mu     sync.Mutex
	warned map[string]bool
}

// CreateClassifier builds a Classifier, applying the §8B defaults and rejecting
// a style whose required option is missing before any call is made.
func CreateClassifier(opts ClassifierOptions) (*Classifier, error) {
	if opts.Style == "" {
		opts.Style = StyleSystemOne
	}
	if opts.BaseURL == "" {
		opts.BaseURL = DefaultClassifierBaseURL
	}
	if opts.Model == "" {
		opts.Model = DefaultClassifierModel
	}
	if opts.APIKeyEnv == "" {
		opts.APIKeyEnv = DefaultClassifierAPIKeyEnv
	}
	if opts.Timeout == 0 {
		opts.Timeout = DefaultClassifierTimeout
	}
	c := &Classifier{opts: opts, warned: map[string]bool{}}
	switch opts.Style {
	case StyleSystemOne:
	case StyleLLM:
		if opts.Client == nil {
			return nil, fmt.Errorf("classifier: style %q requires Client", opts.Style)
		}
	case StyleCustom:
		if opts.Evaluate == nil {
			return nil, fmt.Errorf("classifier: style %q requires Evaluate", opts.Style)
		}
	case StyleStatic:
		c.static = make(map[string][]byte, len(opts.Decisions))
		for i, rec := range opts.Decisions {
			k, err := staticKey(opts.Model, rec.State, rec.Questions)
			if err != nil {
				return nil, fmt.Errorf("classifier: recorded decision %d: %w", i, err)
			}
			c.static[k] = rec.Response
		}
	default:
		return nil, fmt.Errorf("classifier: unknown style %q", opts.Style)
	}
	return c, nil
}

// Evaluate is the whole contract: a state plus typed questions in, a Decision
// out. Questions are INDEPENDENT — one answer is never context for another.
func (c *Classifier) Evaluate(ctx context.Context, state any, questions map[string]Question) (Decision, error) {
	start := time.Now()
	if len(questions) == 0 {
		return Decision{}, fmt.Errorf("classifier: no questions to evaluate")
	}
	// Limits are enforced CLIENT-SIDE, before the request: the caller finds out
	// faster and more legibly than from the backend's own 400. Keys are walked in
	// sorted order so the same malformed set always names the same key first.
	for _, key := range sortedQuestionKeys(questions) {
		q := questions[key]
		if q == nil {
			return Decision{}, fmt.Errorf("classifier: question %q is nil", key)
		}
		if err := q.validate(key); err != nil {
			c.emitError(start, err)
			return Decision{}, err
		}
	}
	// Detection, never repair (ADR 0020/0021): the request goes out BYTE-UNCHANGED
	// and the warning is the entire observable effect.
	c.reportDegenerate(questions)

	var (
		d   Decision
		err error
	)
	switch c.opts.Style {
	case StyleCustom:
		d, err = c.opts.Evaluate(ctx, state, questions)
	case StyleStatic:
		d, err = c.evaluateStatic(state, questions)
	case StyleLLM:
		d, err = c.evaluateLLM(ctx, state, questions)
	default:
		d, err = c.evaluateSystemOne(ctx, state, questions)
	}
	if err != nil {
		c.emitError(start, err)
		return Decision{}, err
	}
	c.emit(MetricEvent{
		Event:            MetricClassifierEvaluate,
		Model:            d.Model,
		Status:           "ok",
		Ms:               time.Since(start).Milliseconds(),
		PromptTokens:     d.Usage.InputTokens,
		CompletionTokens: d.Usage.OutputTokens,
	})
	return d, nil
}

func sortedQuestionKeys(questions map[string]Question) []string {
	keys := make([]string, 0, len(questions))
	for k := range questions {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// ---------------------------------------------------------------- degenerate

// reportDegenerate emits ONE warning per degenerate question key per classifier,
// naming the key, and changes nothing about the request. Repairing would invent
// option descriptions the caller did not write, and the library has no way to
// know what the options mean.
func (c *Classifier) reportDegenerate(questions map[string]Question) {
	for _, key := range sortedQuestionKeys(questions) {
		q, ok := questions[key].(ChoiceQuestion)
		if !ok {
			continue
		}
		reason, degenerate := degenerateCriteria(q.Criteria)
		if !degenerate {
			continue
		}
		c.mu.Lock()
		first := !c.warned[key]
		c.warned[key] = true
		c.mu.Unlock()
		if !first {
			continue
		}
		c.emit(MetricEvent{
			Event:    MetricClassifierWarning,
			Question: key,
			Warning: fmt.Sprintf(
				"classifier: question %q has degenerate criteria (%s) — every option reads "+
					"the same to the model and the answer ranks at chance; describe what "+
					"picking each option would MEAN (SPEC.md §8B)", key, reason),
		})
	}
}

// degenerateCriteria implements the §8B predicate. Degenerate ⇔ ANY of:
//  1. every value is empty (empty string or absent), or
//  2. every value equals its own key, or
//  3. every value is identical to every other value (n >= 2).
//
// A single-option choice (n == 1) is NEVER reported: there is nothing to
// differentiate. Note the ordering — with n == 1 rules 1 and 2 can still hold,
// and rule 3 is vacuous, so the n == 1 gate comes first for all three.
func degenerateCriteria(criteria map[string]string) (reason string, degenerate bool) {
	if len(criteria) < 2 {
		return "", false
	}
	allEmpty, allEqualKey, allIdentical := true, true, true
	var first string
	seen := false
	for k, v := range criteria {
		if v != "" {
			allEmpty = false
		}
		if v != k {
			allEqualKey = false
		}
		if !seen {
			first, seen = v, true
		} else if v != first {
			allIdentical = false
		}
	}
	switch {
	case allEmpty:
		return "every description is empty", true
	case allEqualKey:
		return "every description is just its own option id", true
	case allIdentical:
		return "every description is identical", true
	}
	return "", false
}

// ---------------------------------------------------------------- backends

// body assembles the request: the canonical model + questions, plus state
// VERBATIM as the host supplied it, then the §8 Gap 1 pipeline in §8 order
// (base → RequestParams merge → BodyTransform → marshal).
func (c *Classifier) body(state any, questions map[string]Question) ([]byte, error) {
	qs := make(map[string]any, len(questions))
	for k, q := range questions {
		qs[k] = q.wire()
	}
	b := map[string]any{"model": c.opts.Model, "questions": qs, "state": state}
	for k, v := range c.opts.RequestParams { // a RequestParams key WINS
		b[k] = v
	}
	if c.opts.BodyTransform != nil {
		if out := c.opts.BodyTransform(b); out != nil {
			b = out
		}
	}
	return canonicalJSON(b)
}

// staticKey identifies a recorded decision by the canonical request AND the
// state. See RecordedDecision for why the state is load-bearing here.
func staticKey(model string, state any, questions map[string]Question) (string, error) {
	req, err := CanonicalRequest(model, questions)
	if err != nil {
		return "", err
	}
	st, err := canonicalJSON(state)
	if err != nil {
		return "", err
	}
	return string(req) + "\x00" + string(st), nil
}

func (c *Classifier) evaluateStatic(state any, questions map[string]Question) (Decision, error) {
	k, err := staticKey(c.opts.Model, state, questions)
	if err != nil {
		return Decision{}, err
	}
	raw, ok := c.static[k]
	if !ok {
		return Decision{}, fmt.Errorf("classifier: static: no recorded decision for this request+state")
	}
	var d Decision
	if err := json.Unmarshal(raw, &d); err != nil {
		return Decision{}, err
	}
	return d, nil
}

func (c *Classifier) evaluateSystemOne(ctx context.Context, state any, questions map[string]Question) (Decision, error) {
	raw, err := c.body(state, questions)
	if err != nil {
		return Decision{}, err
	}
	res, err := c.post(ctx, raw)
	if err != nil {
		return Decision{}, err
	}
	var d Decision
	if err := json.Unmarshal(res, &d); err != nil {
		return Decision{}, err
	}
	return d, nil
}

// post issues the one POST with the §8 retry budget, reusing the client's
// ErrorInfo/Tier classifier and the Retry-After delay-seconds rule verbatim.
//
// NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE APPEARS ON ANY PATH OUT OF
// HERE: an authentication failure names the status and the endpoint, nothing
// else, and the response body is never echoed back (a gateway happily reflects
// a bad Authorization header into its own 401 text).
func (c *Classifier) post(ctx context.Context, raw []byte) ([]byte, error) {
	endpoint := strings.TrimRight(c.opts.BaseURL, "/") + "/systemone"
	retries := c.opts.Retries
	if retries <= 0 {
		retries = 2
	}
	baseMs := c.opts.RetryBaseMs
	if baseMs <= 0 {
		baseMs = 500
	}
	classify := c.opts.OnError
	if classify == nil {
		classify = func(info ErrorInfo) Tier {
			if info.Retryable {
				return TierRetry
			}
			return TierFail
		}
	}
	hc := c.opts.HTTPClient
	if hc == nil {
		hc = http.DefaultClient
	}
	var lastErr error
	for attempt := 0; ; attempt++ {
		reqCtx, cancel := context.WithTimeout(ctx, c.opts.Timeout)
		body, status, retryAfter, err := c.attempt(reqCtx, hc, endpoint, raw)
		cancel()
		if err == nil && status < 300 {
			return body, nil
		}
		retryable := err != nil || status == 408 || isRetryableStatus(status, c.opts.RetryableStatuses)
		if ctx.Err() != nil { // caller cancellation is never retried
			return nil, ctx.Err()
		}
		if err != nil {
			lastErr = fmt.Errorf("classifier: POST %s: %w", endpoint, err)
		} else {
			// The backend's own cause is surfaced INTACT so a caller can tell a
			// limit error from a transport fault — but only for a non-auth status.
			lastErr = fmt.Errorf("classifier: POST %s: HTTP %d%s", endpoint, status, cause(status, body))
		}
		if attempt >= retries {
			return nil, lastErr
		}
		if classify(ErrorInfo{Err: err, Status: status, Attempt: attempt, Retryable: retryable}) != TierRetry {
			return nil, lastErr
		}
		delay, ok := retryAfterSeconds(retryAfter)
		if !ok {
			delay = time.Duration(baseMs*(1<<attempt)) * time.Millisecond
		}
		t := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			t.Stop()
			return nil, ctx.Err()
		case <-t.C:
		}
	}
}

// cause surfaces a backend's reported cause intact, EXCEPT on an authentication
// status: a 401/403 body routinely reflects the credential or the header that
// was sent, so it never reaches a log, a metric, an error or a return value.
func cause(status int, body []byte) string {
	if status == 401 || status == 403 {
		return ""
	}
	s := strings.TrimSpace(string(body))
	if s == "" {
		return ""
	}
	if len(s) > 200 {
		s = s[:200] + "…"
	}
	return ": " + s
}

func (c *Classifier) attempt(ctx context.Context, hc *http.Client, endpoint string, raw []byte) (body []byte, status int, retryAfter string, err error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(raw))
	if err != nil {
		return nil, 0, "", err
	}
	req.Header.Set("Content-Type", "application/json")
	// Read at call time. Never logged, never returned, never in an error.
	if key := os.Getenv(c.opts.APIKeyEnv); key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	for k, v := range c.opts.Headers {
		req.Header.Set(k, os.ExpandEnv(v)) // ${ENV_VAR} expands at call time
	}
	res, err := hc.Do(req)
	if err != nil {
		return nil, 0, "", err
	}
	defer res.Body.Close()
	b, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, res.StatusCode, res.Header.Get("Retry-After"), err
	}
	return b, res.StatusCode, res.Header.Get("Retry-After"), nil
}

// evaluateLLM renders the three question types as ONE structured-output call on
// any §8 Client — the vendor-neutral fallback, so a host with no System One
// credential runs the same questions on a cheap chat model. Calibrated is FALSE:
// the numbers are the model's self-report, not token probabilities.
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
	prompt := "Answer every question about the state below. Questions are INDEPENDENT: " +
		"one answer is never context for another.\n\n" +
		"STATE:\n" + string(stateJSON) + "\n\nQUESTIONS:\n" + string(qJSON) + "\n\n" +
		"Reply with JSON only, no prose and no code fence, shaped exactly:\n" +
		`{"answers":{"<key>":{"type":"noul","noul":0.0}}}` + "\n" +
		`A "noul" answer is {"type":"noul","noul":<0..1>}. A "choice" answer is ` +
		`{"type":"choice","choice":"<one offered option id>","probabilities":{"<every offered option id>":<0..1>},"confidence":<0..1>}. ` +
		`A "score" answer is {"type":"score","score":<a number within the rubric bounds, fractional allowed>,` +
		`"legend":{"0":"<level 0>",…},"probabilities":{"0":<0..1>,…},"confidence":<0..1>}.`
	run, err := c.opts.Client.Run(ctx, prompt, nil)
	if err != nil {
		return Decision{}, err
	}
	payload, err := firstJSONObject(run.Text)
	if err != nil {
		return Decision{}, fmt.Errorf("classifier: llm: %w", err)
	}
	var d Decision
	if err := json.Unmarshal(payload, &d); err != nil {
		return Decision{}, fmt.Errorf("classifier: llm: %w", err)
	}
	// The model reports no calibration and none is derived here. Never repaired,
	// never asserted as calibrated (ADR 0020).
	d.Calibrated = false
	if d.Model == "" {
		d.Model = c.opts.Model
	}
	d.Usage = ClassifierUsage{
		InputTokens:  run.Usage.PromptTokens,
		OutputTokens: run.Usage.CompletionTokens,
	}
	return d, nil
}

// firstJSONObject extracts the outermost JSON object from a model reply, which
// may arrive wrapped in a code fence or prose. It does NOT repair malformed JSON
// — an unparseable answer is no answer (ADR 0020).
func firstJSONObject(s string) ([]byte, error) {
	start := strings.IndexByte(s, '{')
	end := strings.LastIndexByte(s, '}')
	if start < 0 || end <= start {
		return nil, fmt.Errorf("no JSON object in the reply")
	}
	return []byte(s[start : end+1]), nil
}

// ---------------------------------------------------------------- metrics

func (c *Classifier) emit(ev MetricEvent) {
	if c.opts.OnMetric != nil {
		c.opts.OnMetric(ev)
	}
}

func (c *Classifier) emitError(start time.Time, err error) {
	c.emit(MetricEvent{
		Event:  MetricClassifierEvaluate,
		Model:  c.opts.Model,
		Status: "error",
		Ms:     time.Since(start).Milliseconds(),
		Error:  err.Error(),
	})
}
