// Package jevxcore is a spike: the decision core jevx needs, built ONLY on the
// toolnexus golang module. It answers "how small can jevx's core get if
// toolnexus owns the classifier?" Everything marked GAP is something this
// spike had to write itself and that ADR 0038 proposes moving into toolnexus.
package jevxcore

import (
	"context"
	"errors"
	"fmt"
	"math"
	"os"
	"sort"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Thresholds are jevx's verdict cut-points, INCLUSIVE on the confident side
// (jevcli cmd/jevx/ask.go:109 verdict): P >= Yes is yes, P <= No is no, a
// choice/score with confidence < MinConfidence is unsure.
//
// GAP T1: toolnexus Bands (judge.go:89) are EXCLUSIVE (> High, < Low), share one
// High for choice confidence, and also demote near-uniform choices. Same
// numbers, different verdicts at the boundary — so the spike keeps jevx's rule.
type Thresholds struct {
	Yes, No, MinConfidence float64
}

// JevxDefaults are jevx's Builtin settings (jevcli core/core.go:109).
var JevxDefaults = Thresholds{Yes: 0.8, No: 0.2, MinConfidence: 0.6}

// Profile is one named classifier. It maps onto tn.ClassifierOptions; the only
// jevx-specific fields are URL (jevx stores the FULL endpoint) and Thresholds.
type Profile struct {
	Style     tn.ClassifierStyle // systemone (default) | llm | custom | static
	URL       string             // full endpoint, e.g. https://api.typesafe.ai/v1/systemone
	Model     string
	APIKeyEnv string            // NAME of the env var; the value is never read here
	Headers   map[string]string // ${ENV} expanded by toolnexus at call time
	Timeout   time.Duration
	Retries   int
	Thresh    *Thresholds // nil => JevxDefaults
	Fallback  string      // profile to use when APIKeyEnv is unset (e.g. "llm")
	// Client is the §8 client for Style llm; Evaluate for Style custom.
	Client   *tn.Client
	Evaluate func(ctx context.Context, state any, qs map[string]tn.Question) (tn.Decision, error)
}

// Registry holds several named classifiers. GAP R1: toolnexus has no registry
// or profile concept; every host (jevx, wfnexus) rebuilds this map itself.
type Registry struct {
	Default  string
	Profiles map[string]Profile
	built    map[string]*tn.Classifier
}

// DefaultRegistry is the owner-approved default: Jev on TypeSafe (systemone),
// falling back to an llm profile when TYPESAFE_API_KEY is absent — only if the
// host supplies a chat client for it. Keys come from env only.
func DefaultRegistry(llm *tn.Client) *Registry {
	r := &Registry{Default: "jev", Profiles: map[string]Profile{
		"jev": {URL: tn.DefaultClassifierBaseURL + "/systemone", Model: tn.DefaultClassifierModel,
			APIKeyEnv: tn.DefaultClassifierAPIKeyEnv},
	}}
	if llm != nil {
		p := r.Profiles["jev"]
		p.Fallback = "llm"
		r.Profiles["jev"] = p
		r.Profiles["llm"] = Profile{Style: tn.StyleLLM, Client: llm}
	}
	return r
}

// ErrNeedKey mirrors jevx's ErrNeedKey: no credential and no fallback.
var ErrNeedKey = errors.New("need key")

// Resolve picks the profile (""=default) and follows Fallback when the key is unset.
func (r *Registry) Resolve(name string) (string, Profile, error) {
	if name == "" {
		name = r.Default
	}
	for hops := 0; hops < 4; hops++ {
		p, ok := r.Profiles[name]
		if !ok {
			return "", Profile{}, fmt.Errorf("unknown profile %q (have %s)", name, strings.Join(r.names(), ", "))
		}
		style := p.Style
		if style == "" || style == tn.StyleSystemOne {
			if p.APIKeyEnv != "" && os.Getenv(p.APIKeyEnv) == "" {
				if p.Fallback != "" {
					name = p.Fallback
					continue
				}
				return name, p, fmt.Errorf("%w: set %s", ErrNeedKey, p.APIKeyEnv)
			}
		}
		return name, p, nil
	}
	return "", Profile{}, errors.New("profile fallback loop")
}

func (r *Registry) names() []string {
	out := make([]string, 0, len(r.Profiles))
	for k := range r.Profiles {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// Classifier builds (once) the toolnexus classifier for a profile.
func (r *Registry) Classifier(name string) (string, *tn.Classifier, Profile, error) {
	name, p, err := r.Resolve(name)
	if err != nil {
		return name, nil, p, err
	}
	if c := r.built[name]; c != nil {
		return name, c, p, nil
	}
	// GAP U1: toolnexus always POSTs {BaseURL}/systemone; jevx stores the full
	// URL. Stripping the suffix works for every System One URL jevx ships, but a
	// Cloudflare /ai/run envelope (jevcli core/core.go:778) cannot be expressed.
	base := strings.TrimSuffix(strings.TrimRight(p.URL, "/"), "/systemone")
	c, err := tn.CreateClassifier(tn.ClassifierOptions{Style: p.Style, BaseURL: base, Model: p.Model,
		APIKeyEnv: p.APIKeyEnv, Headers: p.Headers, Timeout: p.Timeout, Retries: p.Retries,
		Client: p.Client, Evaluate: p.Evaluate})
	if err != nil {
		return name, nil, p, err
	}
	if r.built == nil {
		r.built = map[string]*tn.Classifier{}
	}
	r.built[name] = c
	return name, c, p, nil
}

// Item is what is judged: text, or an image (GAP I1).
type Item struct {
	Text  string
	Image *tn.ContentPart
}

// ErrImageUnsupported: GAP I1 — §8B state is JSON; neither the systemone wire
// nor evaluateLLM (which calls Client.Run with TEXT) can carry an image today,
// although Client.RunParts can. Fails loudly rather than describing the image.
var ErrImageUnsupported = errors.New("image items are not supported by this profile's classifier (toolnexus §8B gap I1)")

// Verdict is one answer in jevx's vocabulary.
type Verdict struct {
	Answer  string  // yes|no|unsure, an option id, a level name, or unsure
	Value   float64 // P(yes) for noul, confidence otherwise
	Profile string
	Model   string
}

// ExitCode is jevx's contract: 0 yes, 1 no, 3 unsure (4 = error, set by the CLI).
func (v Verdict) ExitCode() int {
	switch v.Answer {
	case "no":
		return 1
	case "unsure":
		return 3
	}
	return 0
}

type Core struct{ Reg *Registry }

func (c Core) one(ctx context.Context, profile string, it Item, q tn.Question) (Verdict, tn.DecisionAnswer, Thresholds, error) {
	name, cl, p, err := c.Reg.Classifier(profile)
	if err != nil {
		return Verdict{}, nil, Thresholds{}, err
	}
	if it.Image != nil {
		return Verdict{}, nil, Thresholds{}, ErrImageUnsupported // every style today, llm included
	}
	d, err := cl.Evaluate(ctx, it.Text, map[string]tn.Question{"q": q})
	if err != nil {
		return Verdict{}, nil, Thresholds{}, err
	}
	if err := validate(q, d); err != nil {
		return Verdict{}, nil, Thresholds{}, err
	}
	t := JevxDefaults
	if p.Thresh != nil {
		t = *p.Thresh
	}
	return Verdict{Profile: name, Model: d.Model}, d.Answers["q"], t, nil
}

// validate is jevx's fail-closed check (jevcli core/core.go:865). GAP V1:
// toolnexus decodes answers but does not reject a missing/extra answer, a
// probability outside [0,1], probabilities not summing to ~1, or a choice that
// is not an offered key.
func validate(q tn.Question, d tn.Decision) error {
	if len(d.Answers) != 1 {
		return fmt.Errorf("invalid answer: %d answers for 1 question", len(d.Answers))
	}
	a, ok := d.Answers["q"]
	if !ok {
		return errors.New("invalid answer: no answer for \"q\"")
	}
	sum := func(m map[string]float64) error {
		if len(m) == 0 {
			return nil
		}
		s := 0.0
		for _, v := range m {
			if v < 0 || v > 1 {
				return fmt.Errorf("invalid answer: probability %v", v)
			}
			s += v
		}
		if s < 0.98 || s > 1.02 {
			return fmt.Errorf("invalid answer: probabilities sum to %.3f", s)
		}
		return nil
	}
	switch x := a.(type) {
	case tn.NoulAnswer:
		if x.Noul < 0 || x.Noul > 1 {
			return fmt.Errorf("invalid answer: probability %v", x.Noul)
		}
	case tn.ChoiceAnswer:
		if cq, ok := q.(tn.ChoiceQuestion); ok {
			if _, offered := cq.Criteria[x.Choice]; !offered {
				return fmt.Errorf("invalid answer: chose %q, not an offered key", x.Choice)
			}
		}
		return sum(x.Probabilities)
	case tn.ScoreAnswer:
		return sum(x.Probabilities)
	}
	return nil
}

// Is answers a yes/no question (jevx is / ask --noul).
func (c Core) Is(ctx context.Context, profile string, it Item, question string) (Verdict, error) {
	v, a, t, err := c.one(ctx, profile, it, tn.NoulQuestion{Instructions: question})
	if err != nil {
		return v, err
	}
	p := a.(tn.NoulAnswer).Noul
	v.Value = p
	switch {
	case p >= t.Yes:
		v.Answer = "yes"
	case p <= t.No:
		v.Answer = "no"
	default:
		v.Answer = "unsure"
	}
	return v, nil
}

// Pick chooses one option id (jevx pick / ask --choice).
func (c Core) Pick(ctx context.Context, profile string, it Item, question string, options map[string]string) (Verdict, error) {
	v, a, t, err := c.one(ctx, profile, it, tn.ChoiceQuestion{Instructions: question, Criteria: options})
	if err != nil {
		return v, err
	}
	ch := a.(tn.ChoiceAnswer)
	v.Value, v.Answer = ch.Confidence, ch.Choice
	if ch.Confidence < t.MinConfidence {
		v.Answer = "unsure"
	}
	return v, nil
}

// Rate scores on ordered levels, lowest first (jevx ask --score).
func (c Core) Rate(ctx context.Context, profile string, it Item, question string, levels []string) (Verdict, error) {
	v, a, t, err := c.one(ctx, profile, it, tn.ScoreQuestion{Instructions: question, Criteria: levels})
	if err != nil {
		return v, err
	}
	s := a.(tn.ScoreAnswer)
	v.Value = s.Confidence
	if s.Confidence < t.MinConfidence {
		v.Answer = "unsure"
		return v, nil
	}
	if i := int(math.Round(s.Score)); i >= 0 && i < len(levels) {
		v.Answer = levels[i]
	} else {
		v.Answer = fmt.Sprintf("%.2f", s.Score)
	}
	return v, nil
}
