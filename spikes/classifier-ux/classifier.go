// Package classifierux is a spike of the classifier UX proposed in ADR 0038,
// built over the toolnexus golang module without changing it:
//
//	c, _ := classifierux.New(classifierux.Options{Backend: classifierux.Cloudflare})
//	d, err := c.State(v).Evaluate(ctx, tn.Noul(...), tn.Choice(...))
//	ds, err := c.States(a, b, c).Evaluate(ctx, qs...)
//
// One constructor and one backend enum. Each backend says what it supports
// (Supports); a feature it lacks is ErrUnsupported BEFORE any network call. Batch
// is never an error: no backend has a public native batch, so it fans out.
// A client-level Cache answers repeated (state, question) pairs per question.
package classifierux

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Backend is the enum. Names match the existing tn.ClassifierBackend values.
type Backend string

const (
	TypeSafe   Backend = "typesafe"
	OpenRouter Backend = "openrouter"
	Cloudflare Backend = "cloudflare"
	Vercel     Backend = "vercel"
	Custom     Backend = "custom" // self-hosted / anything else; the host declares Supports
)

// Supports is what a backend+model can take. Batch is not here: it always
// works (native when a backend has it, fan-out otherwise).
type Supports struct {
	Images bool // image items in the state
	Box    bool // box questions (Jev 1.14); not expressible yet: tn.Question is closed
}

type preset struct {
	base, model, keyEnv string
	cf                  bool // Cloudflare /ai/run envelope
	supports            func(model string) Supports
}

// presets: researched 2026-11-04 (spikes/classifier-backends/STYLES.md).
// Vercel and Cloudflare-Jev images stay false until a live probe proves them; that is deliberate: an unproven image
// path must fail loudly, not send bytes a gateway may silently drop.
var presets = map[Backend]preset{
	TypeSafe: {"https://api.typesafe.ai/v1", "jev-latest", "TYPESAFE_API_KEY", false,
		func(m string) Supports { return Supports{Images: true, Box: strings.HasPrefix(m, "jev-1.14")} }},
	// OpenRouter forwards assets + the images header: proven live 2026-11-04 (README).
	OpenRouter: {"https://openrouter.ai/api/v1", "typesafe/jev-1.14", "OPENROUTER_API_KEY", false,
		func(m string) Supports { return Supports{Images: true, Box: strings.Contains(m, "jev-1.14")} }},
	Cloudflare: {"https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run", "typesafe/jev", "CLOUDFLARE_JEV_API_KEY", true,
		func(m string) Supports { return Supports{Images: strings.Contains(m, "clef")} }},
	Vercel: {"https://ai-gateway.vercel.sh/typesafe/v1", "typesafe-ai/jev", "AI_GATEWAY_API_KEY", false,
		func(string) Supports { return Supports{} }},
}

// Options: Backend picks the preset; every other field overrides it.
type Options struct {
	Backend   Backend
	BaseURL   string
	Model     string
	APIKeyEnv string
	Supports  *Supports // Custom: required; others: override the preset
	Cache     Cache     // nil = no cache
	Timeout   time.Duration
	Retries   int
	// Concurrency bounds a fan-out batch. 0 = 16.
	Concurrency int
	HTTPClient  *http.Client
}

// ErrUnsupported names exactly what was refused. Nothing was sent.
type ErrUnsupported struct{ Backend, Model, Feature string }

func (e *ErrUnsupported) Error() string {
	return fmt.Sprintf("classifier: %s (%s) does not support %s", e.Backend, e.Model, e.Feature)
}

// Client is stateless apart from the optional Cache the host handed it.
type Client struct {
	opts     Options
	supports Supports
	plain    *tn.Classifier // no image header
	images   *tn.Classifier // with TypeSafe-Danger-Zone-Images: true
}

func New(o Options) (*Client, error) {
	p, ok := presets[o.Backend]
	switch {
	case o.Backend == Custom:
		if o.BaseURL == "" || o.Supports == nil {
			return nil, errors.New("classifier: custom backend needs BaseURL and Supports")
		}
	case !ok:
		return nil, fmt.Errorf("classifier: unknown backend %q", o.Backend)
	}
	if o.BaseURL == "" {
		o.BaseURL = p.base
	}
	if o.Model == "" {
		o.Model = p.model
	}
	if o.APIKeyEnv == "" {
		o.APIKeyEnv = p.keyEnv
	}
	c := &Client{opts: o}
	if o.Supports != nil {
		c.supports = *o.Supports
	} else {
		c.supports = p.supports(o.Model)
	}
	mk := func(img bool) (*tn.Classifier, error) {
		to := tn.ClassifierOptions{BaseURL: os.ExpandEnv(o.BaseURL), Model: o.Model, APIKeyEnv: o.APIKeyEnv,
			Timeout: o.Timeout, Retries: o.Retries, HTTPClient: o.HTTPClient}
		if img {
			to.Headers = map[string]string{"TypeSafe-Danger-Zone-Images": "true"}
		}
		// assets ride as a sibling of state: BodyTransform sees the per-call
		// state value before marshalling and lifts them out. GAP A1: tn should
		// own this (an assets field on the body) instead of a transform.
		to.BodyTransform = func(b map[string]any) map[string]any {
			if w, ok := b["state"].(withAssets); ok {
				b["state"], b["assets"] = w.state, w.assets
			}
			return b
		}
		if p.cf { // the envelope cannot be expressed on the systemone path: GAP U1
			to.Style, to.Evaluate = tn.StyleCustom, c.cloudflare(img)
		}
		return tn.CreateClassifier(to)
	}
	var err error
	if c.plain, err = mk(false); err != nil {
		return nil, err
	}
	if c.images, err = mk(true); err != nil {
		return nil, err
	}
	return c, nil
}

func (c *Client) Supports() Supports { return c.supports }

// ---------------------------------------------------------------- state

// State binds a value; nothing is sent or stored until Evaluate.
type State struct {
	c *Client
	v any
}

func (c *Client) State(v any) State { return State{c, v} }

// Result is the Decision plus which question names came from the cache.
type Result struct {
	tn.Decision
	Cached []string
}

// Evaluate asks qs about the state: one request for every question the cache
// did not answer, none if it answered all.
func (s State) Evaluate(ctx context.Context, qs ...tn.JudgeQuestion) (Result, error) {
	c := s.c
	questions, err := tn.Questions(qs...)
	if err != nil {
		return Result{}, err
	}
	wire, assets, err := encodeState(s.v)
	if err != nil {
		return Result{}, err
	}
	if len(assets) > 0 && !c.supports.Images {
		return Result{}, &ErrUnsupported{string(c.opts.Backend), c.opts.Model, "images"}
	}
	res := Result{Decision: tn.Decision{Model: c.opts.Model, Answers: map[string]tn.DecisionAnswer{}, Calibrated: true}}
	keys := map[string]string{}
	miss := map[string]tn.Question{}
	for name, q := range questions {
		if c.opts.Cache != nil {
			keys[name] = cacheKey(c.opts.BaseURL, c.opts.Model, wire, assets, q)
			if a, ok := c.opts.Cache.Get(keys[name]); ok {
				res.Answers[name] = a
				res.Cached = append(res.Cached, name)
				continue
			}
		}
		miss[name] = q
	}
	if len(miss) == 0 {
		return res, nil
	}
	cl := c.plain
	var state any = wire
	if len(assets) > 0 {
		cl = c.images
		state = withAssets{wire, assets}
	}
	d, err := cl.Evaluate(ctx, state, miss)
	if err != nil {
		return Result{}, err
	}
	if err := Validate(miss, d); err != nil {
		return Result{}, err
	}
	res.Model, res.Usage, res.Calibrated = d.Model, d.Usage, d.Calibrated
	for name, a := range d.Answers {
		res.Answers[name] = a
		if c.opts.Cache != nil {
			c.opts.Cache.Set(keys[name], a)
		}
	}
	return res, nil
}

// States is a batch. Each state goes through State.Evaluate (so the cache and
// image rules apply per state); results come back in order; the lowest failing
// index is reported and no results are returned (toolnexus EvaluateBatch rule).
type States struct {
	c  *Client
	vs []any
}

func (c *Client) States(vs ...any) States { return States{c, vs} }

func (b States) Evaluate(ctx context.Context, qs ...tn.JudgeQuestion) ([]Result, error) {
	n := b.c.opts.Concurrency
	if n <= 0 {
		n = 16
	}
	out, errs := make([]Result, len(b.vs)), make([]error, len(b.vs))
	sem, wg := make(chan struct{}, n), sync.WaitGroup{}
	for i, v := range b.vs {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, v any) {
			defer func() { <-sem; wg.Done() }()
			out[i], errs[i] = b.c.State(v).Evaluate(ctx, qs...)
		}(i, v)
	}
	wg.Wait()
	for i, e := range errs {
		if e != nil {
			return nil, fmt.Errorf("classifier: state %d: %w", i, e)
		}
	}
	return out, nil
}

// ---------------------------------------------------------------- wire

// withAssets carries a state's lifted images to the wire; see BodyTransform in
// New. It marshals as the bare state so a path that ignores assets never leaks
// them into the state.
type withAssets struct {
	state  any
	assets map[string]asset
}

func (w withAssets) MarshalJSON() ([]byte, error) { return json.Marshal(w.state) }

// cloudflare is the /ai/run envelope as a StyleCustom evaluator: {model, input}
// -> {success, result} with the nested Completed unwrap (jevx core.go:770-815).
// Clef takes images in input.images (base64 data URLs).
func (c *Client) cloudflare(img bool) func(ctx context.Context, state any, qs map[string]tn.Question) (tn.Decision, error) {
	return func(ctx context.Context, state any, qs map[string]tn.Question) (tn.Decision, error) {
		wq := map[string]any{}
		for k, q := range qs {
			wq[k] = tn.QuestionWire(q)
		}
		input := map[string]any{"state": state, "questions": wq}
		if w, ok := state.(withAssets); ok {
			input["state"] = w.state
			var urls []string
			for _, a := range w.assets {
				urls = append(urls, "data:image/"+a.Format+";base64,"+a.Data)
			}
			input["images"] = urls
		}
		raw, _ := json.Marshal(map[string]any{"model": c.opts.Model, "input": input})
		req, _ := http.NewRequestWithContext(ctx, http.MethodPost, os.ExpandEnv(c.opts.BaseURL), bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		if k := os.Getenv(c.opts.APIKeyEnv); k != "" {
			req.Header.Set("Authorization", "Bearer "+k)
		}
		hc := c.opts.HTTPClient
		if hc == nil {
			hc = http.DefaultClient
		}
		res, err := hc.Do(req)
		if err != nil {
			return tn.Decision{}, err
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		if res.StatusCode/100 != 2 {
			return tn.Decision{}, fmt.Errorf("classifier: cloudflare: HTTP %d", res.StatusCode) // body withheld
		}
		var env struct {
			Success bool            `json:"success"`
			Result  json.RawMessage `json:"result"`
		}
		if json.Unmarshal(b, &env) != nil || !env.Success {
			return tn.Decision{}, errors.New("classifier: cloudflare: envelope not successful")
		}
		body := env.Result
		var inner struct {
			State  string          `json:"state"`
			Result json.RawMessage `json:"result"`
		}
		if json.Unmarshal(body, &inner) == nil && inner.State != "" {
			if inner.State != "Completed" {
				return tn.Decision{}, fmt.Errorf("classifier: cloudflare: run state %q", inner.State)
			}
			body = inner.Result
		}
		var d tn.Decision
		return d, json.Unmarshal(body, &d)
	}
}
