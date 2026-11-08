package toolnexus

// Classifier backends, images, cache and validation (SPEC.md §8B "Backends,
// images, cache and validation", OpenSpec change add-classifier-backends,
// ADR 0038). Every default here is a PROPOSED default, owner to confirm
// (openspec/changes/add-classifier-backends/design.md PD-1..PD-15).

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// ---------------------------------------------------------------- backends

const (
	// BackendCloudflare is Cloudflare Workers AI's /ai/run envelope
	// ({model, input:{state,questions}} -> {success, result}).
	BackendCloudflare ClassifierBackend = "cloudflare"
	// BackendVercel is Vercel AI Gateway's TypeSafe-compatible System One route.
	BackendVercel ClassifierBackend = "vercel"
	// BackendCustom is a self-hosted System One endpoint. It requires BaseURL and
	// Supports: the library cannot know what an unknown server takes.
	BackendCustom ClassifierBackend = "custom"

	// ClassifierWireSystemOne / ClassifierWireCloudflare name the two request
	// shapes a backend row can use.
	ClassifierWireSystemOne  = "systemone"
	ClassifierWireCloudflare = "cloudflare"

	// ImagesHeader is TypeSafe's required opt-in for image ("danger zone") input.
	ImagesHeader = "TypeSafe-Danger-Zone-Images"

	// Image limits (docs.typesafe.ai/images). Outside them the API rejects, never
	// resizes, so they are checked client-side before any request.
	ClassifierImageMinSide      = 280
	ClassifierImageMaxSide      = 1568
	ClassifierImageMaxBytes     = 16 << 20
	ClassifierRequestImageBytes = 32 << 20
)

// ClassifierSupports is what a backend+model can take. It is DATA: a fail-fast
// for what the table says, never a claim about the vendor. Override it per
// classifier with ClassifierOptions.Supports.
type ClassifierSupports struct {
	Images bool `json:"images"`
}

// ClassifierBackendPreset is one row of the backend table.
type ClassifierBackendPreset struct {
	BaseURL   string
	Model     string
	APIKeyEnv string
	// Wire is ClassifierWireSystemOne, ClassifierWireCloudflare or
	// ClassifierWireDecisions.
	Wire string
	// ImagesHeader: send ImagesHeader: true when a request carries images.
	ImagesHeader bool
	// Supports computes the default support for a model on this backend. Nil on
	// BackendCustom, which requires ClassifierOptions.Supports.
	Supports func(model string) ClassifierSupports
}

// ClassifierBackends is the backend table (researched and live-probed
// 2026-11-04, ADR 0038). Vercel and Cloudflare-Jev images stay false until a
// live probe proves them: an unproven image path fails loudly rather than send
// bytes a gateway may silently drop.
var ClassifierBackends = map[ClassifierBackend]ClassifierBackendPreset{
	BackendTypeSafe: {DefaultClassifierBaseURL, DefaultClassifierModel, DefaultClassifierAPIKeyEnv,
		ClassifierWireSystemOne, true, func(string) ClassifierSupports { return ClassifierSupports{Images: true} }},
	BackendOpenRouter: {OpenRouterClassifierBaseURL, OpenRouterClassifierModel, OpenRouterClassifierAPIKeyEnv,
		ClassifierWireSystemOne, true, func(string) ClassifierSupports { return ClassifierSupports{Images: true} }},
	BackendCloudflare: {"https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run", "typesafe/jev",
		"CLOUDFLARE_JEV_API_KEY", ClassifierWireCloudflare, false,
		func(m string) ClassifierSupports { return ClassifierSupports{Images: strings.Contains(m, "clef")} }},
	BackendVercel: {"https://ai-gateway.vercel.sh/typesafe/v1", "typesafe-ai/jev", "AI_GATEWAY_API_KEY",
		ClassifierWireSystemOne, true, func(string) ClassifierSupports { return ClassifierSupports{} }},
	BackendOpenAI: {"https://api.openai.com/v1", "gpt-6-luna", "OPENAI_API_KEY",
		ClassifierWireDecisions, false, func(string) ClassifierSupports { return ClassifierSupports{Images: true} }},
	BackendCustom: {"", DefaultClassifierModel, "", ClassifierWireSystemOne, true, nil},
}

// classifierKeyEnv names the env var whose key may be sent to baseURL (already
// ${NAME}-expanded): the backend's own variable, and only when baseURL is that
// backend's own host over https (OpenRouter also its subdomains, as in the §8
// client). "" when none may — custom has no preset key at all. A key is never
// sent to a host the caller did not name (#155, SPEC §8B).
func classifierKeyEnv(backend ClassifierBackend, baseURL string) string {
	p, ok := ClassifierBackends[backend]
	if !ok || p.APIKeyEnv == "" || p.BaseURL == "" {
		return ""
	}
	h, own := hostOf(baseURL), hostOf(p.BaseURL)
	if h == "" || own == "" {
		return ""
	}
	if h == own || (backend == BackendOpenRouter && strings.HasSuffix(h, ".openrouter.ai")) {
		return p.APIKeyEnv
	}
	return ""
}

// ErrClassifierUnsupported is returned BEFORE any network call when the
// backend's support table says it cannot take what the request carries. Named
// apart from errors.ErrUnsupported on purpose.
type ErrClassifierUnsupported struct {
	Backend, Model, Feature string
}

func (e *ErrClassifierUnsupported) Error() string {
	return fmt.Sprintf("classifier: %s (%s) does not support %s", e.Backend, e.Model, e.Feature)
}

var envRefRe = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}`)

// expandEnvRefs expands ${NAME} only (not $NAME), at call time.
func expandEnvRefs(s string) string {
	if !strings.Contains(s, "${") {
		return s
	}
	return envRefRe.ReplaceAllStringFunc(s, func(m string) string {
		return os.Getenv(m[2 : len(m)-1])
	})
}

// ---------------------------------------------------------------- open union

// RawQuestion is the open end of the Question union: a System One question
// type the library does not model (e.g. Jev 1.14 "box"), sent as written. It
// gets no client-side limits.
type RawQuestion struct {
	Type         string
	Instructions string
	// Criteria is sent verbatim when non-nil, omitted when nil.
	Criteria any
}

func (RawQuestion) isQuestion() {}

func (q RawQuestion) wire() map[string]any {
	m := map[string]any{"type": q.Type, "instructions": q.Instructions}
	if q.Criteria != nil {
		m["criteria"] = q.Criteria
	}
	return m
}

func (q RawQuestion) validate(key string) error {
	if q.Type == "" {
		return fmt.Errorf("classifier: question %q: a raw question needs a type", key)
	}
	return nil
}

// Raw builds a named RawQuestion for State(...).Evaluate / Ask.
func Raw(name, typ, instructions string, criteria any) JudgeQuestion {
	return JudgeQuestion{name, RawQuestion{Type: typ, Instructions: instructions, Criteria: criteria}}
}

// RawAnswer is an answer of a type the library does not model. Value is the
// whole answer object as decoded (including "type").
type RawAnswer struct {
	Type  string
	Value map[string]any
}

func (RawAnswer) isDecisionAnswer()    {}
func (a RawAnswer) AnswerType() string { return a.Type }

// MarshalJSON writes the answer object back as received.
func (a RawAnswer) MarshalJSON() ([]byte, error) { return json.Marshal(a.Value) }

// ---------------------------------------------------------------- images

type classifierAsset struct {
	Type   string `json:"type"`
	Format string `json:"format"`
	Data   string `json:"data_base64"`
}

type liftedImage struct {
	ref   string
	asset classifierAsset
}

// liftImages implements the §8B lifting walk. It returns the state to send and
// the images in collection order. A state with no ContentPart is returned
// VERBATIM (the same value), so a text-only state is byte-identical to before.
func liftImages(state any) (any, []liftedImage, error) {
	if !containsPart(state) {
		return state, nil, nil
	}
	used := map[string]bool{}
	var imgs []liftedImage
	next := func() string {
		for n := 1; ; n++ {
			if name := fmt.Sprintf("image%d", n); !used[name] {
				return name
			}
		}
	}
	var walk func(v any, key string) (any, error)
	walk = func(v any, key string) (any, error) {
		switch x := v.(type) {
		case *ContentPart:
			if x == nil {
				return nil, nil
			}
			return walk(*x, key)
		case ContentPart:
			if err := x.Err(); err != nil {
				return nil, err
			}
			switch x.Type {
			case PartText:
				return x.Text, nil
			case PartImage:
				if x.Data == "" {
					return nil, fmt.Errorf("classifier: state: an image part needs inline data (a URL image cannot be sent)")
				}
				ref := key
				if ref == "" || used[ref] {
					ref = next()
				}
				used[ref] = true
				imgs = append(imgs, liftedImage{ref: ref, asset: classifierAsset{Type: "image", Format: x.MimeType, Data: x.Data}})
				return ref, nil
			default:
				return nil, fmt.Errorf("classifier: state: %s parts are not supported", x.Type)
			}
		case []ContentPart:
			out := make([]any, len(x))
			for i, p := range x {
				var err error
				if out[i], err = walk(p, ""); err != nil {
					return nil, err
				}
			}
			return out, nil
		case map[string]any:
			keys := make([]string, 0, len(x))
			for k := range x {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			out := make(map[string]any, len(x))
			for _, k := range keys {
				var err error
				if out[k], err = walk(x[k], k); err != nil {
					return nil, err
				}
			}
			return out, nil
		case []any:
			out := make([]any, len(x))
			for i, e := range x {
				var err error
				if out[i], err = walk(e, ""); err != nil {
					return nil, err
				}
			}
			return out, nil
		}
		return v, nil
	}
	out, err := walk(state, "")
	if err != nil {
		return nil, nil, err
	}
	return out, imgs, nil
}

func containsPart(v any) bool {
	switch x := v.(type) {
	case ContentPart, *ContentPart, []ContentPart:
		return true
	case map[string]any:
		for _, e := range x {
			if containsPart(e) {
				return true
			}
		}
	case []any:
		for _, e := range x {
			if containsPart(e) {
				return true
			}
		}
	}
	return false
}

// checkImages applies the format, per-image and per-request limits, in
// collection order, and normalises the format. Errors name the reference.
func checkImages(imgs []liftedImage) error {
	total := 0
	for i := range imgs {
		im := &imgs[i]
		format := map[string]string{"image/png": "png", "image/jpeg": "jpeg", "image/jpg": "jpeg"}[im.asset.Format]
		if format == "" {
			return fmt.Errorf("classifier: image %q: type %q is not supported (PNG or JPEG only)", im.ref, im.asset.Format)
		}
		im.asset.Format = format
		raw, err := base64.StdEncoding.DecodeString(im.asset.Data)
		if err != nil {
			return fmt.Errorf("classifier: image %q: data is not base64", im.ref)
		}
		if len(raw) > ClassifierImageMaxBytes {
			return fmt.Errorf("classifier: image %q: %d bytes, over 16 MiB", im.ref, len(raw))
		}
		w, h, ok := imageSize(format, raw)
		if !ok {
			return fmt.Errorf("classifier: image %q: not a readable %s", im.ref, strings.ToUpper(format))
		}
		if w < ClassifierImageMinSide || w > ClassifierImageMaxSide || h < ClassifierImageMinSide || h > ClassifierImageMaxSide {
			return fmt.Errorf("classifier: image %q: %dx%d; each side must be %d..%d px", im.ref, w, h,
				ClassifierImageMinSide, ClassifierImageMaxSide)
		}
		total += len(raw)
	}
	if total > ClassifierRequestImageBytes {
		return fmt.Errorf("classifier: image %q: request images total %d bytes, over 32 MiB", imgs[len(imgs)-1].ref, total)
	}
	return nil
}

// imageSize reads width/height from a PNG IHDR or the first JPEG SOF marker.
func imageSize(format string, b []byte) (int, int, bool) {
	be16 := func(i int) int { return int(b[i])<<8 | int(b[i+1]) }
	if format == "png" {
		if len(b) < 24 || string(b[:8]) != "\x89PNG\r\n\x1a\n" || string(b[12:16]) != "IHDR" {
			return 0, 0, false
		}
		w := int(b[16])<<24 | int(b[17])<<16 | int(b[18])<<8 | int(b[19])
		h := int(b[20])<<24 | int(b[21])<<16 | int(b[22])<<8 | int(b[23])
		return w, h, true
	}
	if len(b) < 4 || b[0] != 0xFF || b[1] != 0xD8 {
		return 0, 0, false
	}
	i := 2
	for i+3 < len(b) {
		if b[i] != 0xFF {
			return 0, 0, false
		}
		m := b[i+1]
		if m == 0xFF { // fill byte
			i++
			continue
		}
		if m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC {
			if i+8 >= len(b) {
				return 0, 0, false
			}
			return be16(i + 7), be16(i + 5), true
		}
		i += 2 + be16(i+2)
	}
	return 0, 0, false
}

// ---------------------------------------------------------------- cache

// ClassifierCache is an injected answer cache. Values are opaque strings: the
// served model under a model key, the System One reply body under a decision
// key. Implementations must be safe for concurrent use. Nil = no cache.
type ClassifierCache interface {
	Get(key string) (string, bool)
	Set(key, value string)
}

// MemoryCache is an in-process TTL cache with a size cap (oldest-inserted out
// first). TTL 0 = no expiry; Max 0 = unbounded.
type MemoryCache struct {
	TTL time.Duration
	Max int
	mu  sync.Mutex
	m   map[string]memEntry
	seq uint64
	now func() time.Time
}

type memEntry struct {
	v   string
	at  time.Time
	seq uint64
}

// NewMemoryCache builds a MemoryCache.
func NewMemoryCache(ttl time.Duration, max int) *MemoryCache {
	return &MemoryCache{TTL: ttl, Max: max, m: map[string]memEntry{}, now: time.Now}
}

func (c *MemoryCache) Get(k string) (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[k]
	if !ok {
		return "", false
	}
	if c.TTL > 0 && c.now().Sub(e.at) > c.TTL {
		delete(c.m, k)
		return "", false
	}
	return e.v, true
}

func (c *MemoryCache) Set(k, v string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil {
		c.m = map[string]memEntry{}
	}
	if c.now == nil {
		c.now = time.Now
	}
	c.seq++
	c.m[k] = memEntry{v, c.now(), c.seq}
	if c.Max > 0 && len(c.m) > c.Max {
		ks := make([]string, 0, len(c.m))
		for k := range c.m {
			ks = append(ks, k)
		}
		sort.Slice(ks, func(i, j int) bool { return c.m[ks[i]].seq < c.m[ks[j]].seq })
		for _, k := range ks[:len(c.m)-c.Max] {
			delete(c.m, k)
		}
	}
}

func sha256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// ClassifierModelCacheKey is §8B's model key: which model an endpoint+alias
// served last.
func ClassifierModelCacheKey(endpoint, model string) (string, error) {
	b, err := canonicalJSON(map[string]any{"endpoint": endpoint, "kind": "model", "model": model})
	if err != nil {
		return "", err
	}
	return sha256Hex(b), nil
}

// ClassifierDecisionCacheKey is §8B's decision key, on the SERVED model.
// assets nil/empty ⇒ the "assets" member is omitted.
func ClassifierDecisionCacheKey(endpoint, servedModel string, state any, assets map[string]any, questions map[string]Question) (string, error) {
	qs := make(map[string]any, len(questions))
	for k, q := range questions {
		qs[k] = q.wire()
	}
	m := map[string]any{"endpoint": endpoint, "kind": "decision", "model": servedModel, "questions": qs, "state": state}
	if len(assets) > 0 {
		m["assets"] = assets
	}
	b, err := canonicalJSON(m)
	if err != nil {
		return "", err
	}
	return sha256Hex(b), nil
}

// ---------------------------------------------------------------- V1

// ValidateDecision fails closed on a reply that does not answer exactly the
// questions asked within their schema (ADR 0038 V1). First failure wins, in
// the §8B order. Nil = valid.
func ValidateDecision(questions map[string]Question, d Decision) error {
	bad := func(format string, a ...any) error {
		return fmt.Errorf("classifier: invalid decision: "+format, a...)
	}
	for _, k := range sortedQuestionKeys(questions) {
		if _, ok := d.Answers[k]; !ok {
			return bad("no answer %q", k)
		}
	}
	extra := make([]string, 0)
	for k := range d.Answers {
		if _, ok := questions[k]; !ok {
			extra = append(extra, k)
		}
	}
	sort.Strings(extra)
	if len(extra) > 0 {
		return bad("unexpected answer %q", extra[0])
	}
	in01 := func(p float64) bool { return !math.IsNaN(p) && p >= 0 && p <= 1 }
	dist := func(k string, probs map[string]float64, offered func(string) bool) error {
		pk := make([]string, 0, len(probs))
		for o := range probs {
			pk = append(pk, o)
		}
		sort.Strings(pk)
		sum := 0.0
		for _, o := range pk {
			if !offered(o) {
				return bad("answer %q: probability for %q, which was not offered", k, o)
			}
			if !in01(probs[o]) {
				return bad("answer %q: probability %v outside [0,1]", k, probs[o])
			}
			sum += probs[o]
		}
		if len(probs) > 0 && math.Abs(sum-1) > 0.02 {
			return bad("answer %q: probabilities sum to %.4f", k, sum)
		}
		return nil
	}
	for _, k := range sortedQuestionKeys(questions) {
		q, a := questions[k], d.Answers[k]
		qType := questionType(q)
		if a.AnswerType() != qType {
			return bad("answer %q is a %s answer, not %s", k, a.AnswerType(), qType)
		}
		switch x := a.(type) {
		case NoulAnswer:
			if !in01(x.Noul) {
				return bad("answer %q: noul %v outside [0,1]", k, x.Noul)
			}
		case ChoiceAnswer:
			cq, _ := q.(ChoiceQuestion)
			offered := func(o string) bool { _, ok := cq.Criteria[o]; return ok }
			if !offered(x.Choice) {
				return bad("answer %q: choice %q was not offered", k, x.Choice)
			}
			if err := dist(k, x.Probabilities, offered); err != nil {
				return err
			}
			if !in01(x.Confidence) {
				return bad("answer %q: confidence %v outside [0,1]", k, x.Confidence)
			}
		case ScoreAnswer:
			sq, _ := q.(ScoreQuestion)
			n := len(sq.Criteria)
			if math.IsNaN(x.Score) || x.Score < 0 || x.Score > float64(n-1) {
				return bad("answer %q: score %v outside [0,%d]", k, x.Score, n-1)
			}
			offered := func(o string) bool {
				for i := 0; i < n; i++ {
					if o == fmt.Sprint(i) {
						return true
					}
				}
				return false
			}
			if err := dist(k, x.Probabilities, offered); err != nil {
				return err
			}
			if !in01(x.Confidence) {
				return bad("answer %q: confidence %v outside [0,1]", k, x.Confidence)
			}
		}
	}
	return nil
}

func questionType(q Question) string {
	switch x := q.(type) {
	case NoulQuestion:
		return "noul"
	case ChoiceQuestion:
		return "choice"
	case ScoreQuestion:
		return "score"
	case RawQuestion:
		return x.Type
	}
	return ""
}

// ---------------------------------------------------------------- T1 / C1

// Thresholds are jevx's INCLUSIVE cut-points (ADR 0038 T1). They sit alongside
// Bands (exclusive), which stays the default of Ask/Gate.
type Thresholds struct {
	Yes           float64 `json:"yes"`
	No            float64 `json:"no"`
	MinConfidence float64 `json:"minConfidence"`
}

// DefaultThresholds is 0.8 / 0.2 / 0.6 (jevx's builtin profile).
var DefaultThresholds = Thresholds{Yes: 0.8, No: 0.2, MinConfidence: 0.6}

// Band of one answer: noul p >= Yes ⇒ yes, p <= No ⇒ no, else uncertain;
// choice/score confidence >= MinConfidence ⇒ yes, else uncertain. No
// near-uniform demotion (that is Bands' rule). A raw answer is uncertain.
func (t Thresholds) Band(a DecisionAnswer) string {
	switch x := a.(type) {
	case NoulAnswer:
		if x.Noul >= t.Yes {
			return BandYes
		}
		if x.Noul <= t.No {
			return BandNo
		}
	case ChoiceAnswer:
		if x.Confidence >= t.MinConfidence {
			return BandYes
		}
	case ScoreAnswer:
		if x.Confidence >= t.MinConfidence {
			return BandYes
		}
	}
	return BandUncertain
}

// ChunkQuestions splits a question set into chunks of size, keys in ASCII
// order. A helper, not automatic: joint calibration across a split is unproven.
func ChunkQuestions(questions map[string]Question, size int) ([]map[string]Question, error) {
	if size < 1 {
		return nil, fmt.Errorf("classifier: chunk size must be at least 1, got %d", size)
	}
	keys := sortedQuestionKeys(questions)
	var out []map[string]Question
	for i := 0; i < len(keys); i += size {
		end := min(i+size, len(keys))
		m := make(map[string]Question, end-i)
		for _, k := range keys[i:end] {
			m[k] = questions[k]
		}
		out = append(out, m)
	}
	return out, nil
}

// ---------------------------------------------------------------- builders

// ClassifierState binds a state; nothing is sent until Evaluate. A value, not
// a session.
type ClassifierState struct {
	c *Classifier
	v any
}

// State binds v. See ClassifierState.
func (c *Classifier) State(v any) ClassifierState { return ClassifierState{c, v} }

// Evaluate asks qs about the state in one request, and validates the reply
// fail-closed (ValidateDecision).
func (s ClassifierState) Evaluate(ctx context.Context, qs ...JudgeQuestion) (Decision, error) {
	questions, err := Questions(qs...)
	if err != nil {
		return Decision{}, err
	}
	return s.c.evaluate(ctx, s.v, questions, true)
}

// ClassifierStates is a batch of states.
type ClassifierStates struct {
	c  *Classifier
	vs []any
}

// States binds a batch. See ClassifierStates.Evaluate.
func (c *Classifier) States(vs ...any) ClassifierStates { return ClassifierStates{c, vs} }

// BatchResult is one state's outcome: exactly one of Decision / Err is set.
type BatchResult struct {
	Decision Decision
	Err      error
}

// Evaluate fans out State(v).Evaluate per state (at most 16 in flight) and
// returns one result per state, in order. One state's failure never fails the
// batch; the returned error is only for the question set (duplicate name).
func (b ClassifierStates) Evaluate(ctx context.Context, qs ...JudgeQuestion) ([]BatchResult, error) {
	questions, err := Questions(qs...)
	if err != nil {
		return nil, err
	}
	out := make([]BatchResult, len(b.vs))
	if len(b.vs) == 0 {
		return out, nil
	}
	sem := make(chan struct{}, min(DefaultClassifierBatchConcurrency, len(b.vs)))
	var wg sync.WaitGroup
	for i, v := range b.vs {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, v any) {
			defer wg.Done()
			defer func() { <-sem }()
			d, err := b.c.evaluate(ctx, v, questions, true)
			out[i] = BatchResult{Decision: d, Err: err}
		}(i, v)
	}
	wg.Wait()
	return out, nil
}

// ---------------------------------------------------------------- wire path

// preparedRequest is one systemone-style request after lifting and checks.
type preparedRequest struct {
	state  any
	imgs   []liftedImage
	assets map[string]any
}

func (c *Classifier) prepare(state any) (preparedRequest, error) {
	sent, imgs, err := liftImages(state)
	if err != nil {
		return preparedRequest{}, err
	}
	if len(imgs) > 0 && !c.supports.Images {
		return preparedRequest{}, &ErrClassifierUnsupported{Backend: string(c.backend), Model: c.opts.Model, Feature: "images"}
	}
	if err := checkImages(imgs); err != nil {
		return preparedRequest{}, err
	}
	p := preparedRequest{state: sent, imgs: imgs}
	if len(imgs) > 0 {
		p.assets = make(map[string]any, len(imgs))
		for _, im := range imgs {
			p.assets[im.ref] = map[string]any{"type": im.asset.Type, "format": im.asset.Format, "data_base64": im.asset.Data}
		}
	}
	return p, nil
}

// wireBody assembles the request body for the backend's wire, then the §8
// Gap 1 pipeline (RequestParams merge → BodyTransform → marshal).
func (c *Classifier) wireBody(p preparedRequest, questions map[string]Question) ([]byte, error) {
	qs := make(map[string]any, len(questions))
	for k, q := range questions {
		qs[k] = q.wire()
	}
	var b map[string]any
	if c.preset.Wire == ClassifierWireDecisions {
		_, list, err := decisionsQuestions(c.backend, c.opts.Model, questions)
		if err != nil {
			return nil, err
		}
		input, err := decisionsInput(p)
		if err != nil {
			return nil, err
		}
		b = map[string]any{"model": c.opts.Model, "input": input, "questions": list}
	} else if c.preset.Wire == ClassifierWireCloudflare {
		input := map[string]any{"questions": qs, "state": p.state}
		if len(p.imgs) > 0 {
			urls := make([]any, len(p.imgs))
			for i, im := range p.imgs {
				urls[i] = "data:image/" + im.asset.Format + ";base64," + im.asset.Data
			}
			input["images"] = urls
		}
		b = map[string]any{"model": c.opts.Model, "input": input}
	} else {
		b = map[string]any{"model": c.opts.Model, "questions": qs, "state": p.state}
		if len(p.assets) > 0 {
			b["assets"] = p.assets
		}
	}
	for k, v := range c.opts.RequestParams {
		b[k] = v
	}
	if c.opts.BodyTransform != nil {
		if out := c.opts.BodyTransform(b); out != nil {
			b = out
		}
	}
	return canonicalJSON(b)
}

// cloudflareCause renders errors[0] of a failed envelope as ": <code> <message>"
// (SPEC §8B), through the same redaction and cap as cause(). Cloudflare reports
// a missing balance, a bad model id and a malformed request alike as HTTP 200
// with success:false, so this is the only thing that tells them apart. "" when
// there is nothing to show.
func cloudflareCause(raw json.RawMessage) string {
	var errs []struct {
		Code    json.RawMessage `json:"code"`
		Message string          `json:"message"`
	}
	if json.Unmarshal(raw, &errs) != nil || len(errs) == 0 {
		return ""
	}
	var parts []string
	if code := strings.Trim(string(errs[0].Code), `" `); code != "" && code != "null" {
		parts = append(parts, code)
	}
	if errs[0].Message != "" {
		parts = append(parts, errs[0].Message)
	}
	s := capBody(RedactProviderBody(200, strings.Join(parts, " ")))
	if s == "" {
		return ""
	}
	return ": " + s
}

// unwrapCloudflare returns the System One reply inside a Cloudflare envelope.
func unwrapCloudflare(body []byte) ([]byte, error) {
	var env map[string]json.RawMessage
	if err := json.Unmarshal(body, &env); err != nil {
		return nil, errors.New("classifier: cloudflare: envelope not successful")
	}
	var ok bool
	if json.Unmarshal(env["success"], &ok) != nil || !ok {
		return nil, errors.New("classifier: cloudflare: envelope not successful" + cloudflareCause(env["errors"]))
	}
	res := env["result"]
	var inner map[string]json.RawMessage
	if json.Unmarshal(res, &inner) == nil {
		var st string
		if raw, has := inner["state"]; has && json.Unmarshal(raw, &st) == nil {
			if st != "Completed" {
				return nil, fmt.Errorf("classifier: cloudflare: run state %q", st)
			}
			res = inner["result"]
		}
	}
	var v any
	if err := json.Unmarshal(res, &v); err != nil {
		return nil, fmt.Errorf("classifier: cloudflare: %w", err)
	}
	return canonicalJSON(v)
}

// evaluateWire is the systemone style: lift, check, cache, post, decode.
func (c *Classifier) evaluateWire(ctx context.Context, state any, questions map[string]Question, validate bool) (Decision, error) {
	p, err := c.prepare(state)
	if err != nil {
		return Decision{}, err
	}
	var modelKey string
	if c.opts.Cache != nil {
		if modelKey, err = ClassifierModelCacheKey(c.opts.BaseURL, c.opts.Model); err != nil {
			return Decision{}, err
		}
		if served, ok := c.opts.Cache.Get(modelKey); ok {
			if dk, err := ClassifierDecisionCacheKey(c.opts.BaseURL, served, p.state, p.assets, questions); err == nil {
				if raw, ok := c.opts.Cache.Get(dk); ok {
					var d Decision
					if json.Unmarshal([]byte(raw), &d) == nil && ValidateDecision(questions, d) == nil {
						d.Cached = true
						return d, nil
					}
				}
			}
		}
	}
	raw, err := c.wireBody(p, questions)
	if err != nil {
		return Decision{}, err
	}
	var extra map[string]string
	if len(p.imgs) > 0 && c.preset.ImagesHeader {
		extra = map[string]string{ImagesHeader: "true"}
	}
	body, err := c.post(ctx, raw, extra)
	if err != nil {
		return Decision{}, err
	}
	switch c.preset.Wire {
	case ClassifierWireCloudflare:
		if body, err = unwrapCloudflare(body); err != nil {
			return Decision{}, err
		}
	case ClassifierWireDecisions:
		if body, err = decisionsReply(body, questions); err != nil {
			return Decision{}, err
		}
	}
	var d Decision
	if err := json.Unmarshal(body, &d); err != nil {
		return Decision{}, err
	}
	verr := ValidateDecision(questions, d)
	if validate && verr != nil {
		return Decision{}, verr
	}
	if c.opts.Cache != nil && verr == nil {
		served := d.Model
		if served == "" {
			served = c.opts.Model
		}
		if dk, err := ClassifierDecisionCacheKey(c.opts.BaseURL, served, p.state, p.assets, questions); err == nil {
			c.opts.Cache.Set(modelKey, served)
			c.opts.Cache.Set(dk, string(body))
		}
	}
	return d, nil
}
