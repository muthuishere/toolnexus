package classifierux

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"sort"
	"sync"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// asset is one TypeSafe image asset (docs.typesafe.ai/images).
type asset struct {
	Type   string `json:"type"`
	Format string `json:"format"`
	Data   string `json:"data_base64"`
}

// TypeSafe's image limits; outside them the API rejects, never resizes.
const (
	minSide, maxSide = 280, 1568
	maxImageBytes    = 16 << 20
)

// encodeState walks the state. Every image ContentPart is lifted into assets
// under a reference: its map key, else "image1", "image2"... and the reference
// string takes its place, because "a string that is exactly a reference becomes
// that image". A text part becomes its text. Everything else is untouched.
func encodeState(v any) (any, map[string]asset, error) {
	assets := map[string]asset{}
	n := 0
	var walk func(v any, name string) (any, error)
	walk = func(v any, name string) (any, error) {
		switch x := v.(type) {
		case tn.ContentPart:
			if err := x.Err(); err != nil {
				return nil, err
			}
			if x.Type == tn.PartText {
				return x.Text, nil
			}
			if x.Type != tn.PartImage {
				return nil, fmt.Errorf("classifier: %s parts are not supported in a state", x.Type)
			}
			if name == "" || assets[name] != (asset{}) {
				n++
				name = fmt.Sprintf("image%d", n)
			}
			a, err := toAsset(x)
			if err != nil {
				return nil, err
			}
			assets[name] = a
			return name, nil
		case *tn.ContentPart:
			return walk(*x, name)
		case []tn.ContentPart:
			out := make([]any, len(x))
			for i, p := range x {
				var err error
				if out[i], err = walk(p, ""); err != nil {
					return nil, err
				}
			}
			return out, nil
		case map[string]any:
			out := make(map[string]any, len(x))
			for k, e := range x {
				var err error
				if out[k], err = walk(e, k); err != nil {
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
	out, err := walk(v, "")
	if err != nil {
		return nil, nil, err
	}
	if len(assets) == 0 {
		assets = nil
	}
	return out, assets, nil
}

func toAsset(p tn.ContentPart) (asset, error) {
	format := map[string]string{"image/png": "png", "image/jpeg": "jpeg", "image/jpg": "jpeg"}[p.MimeType]
	if format == "" {
		return asset{}, &ErrUnsupported{"any", "", "image type " + p.MimeType + " (PNG or JPEG only)"}
	}
	raw, err := base64.StdEncoding.DecodeString(p.Data)
	if err != nil {
		return asset{}, fmt.Errorf("classifier: image data: %w", err)
	}
	if len(raw) > maxImageBytes {
		return asset{}, fmt.Errorf("classifier: image is %d bytes, over 16 MiB", len(raw))
	}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil {
		return asset{}, fmt.Errorf("classifier: image: %w", err)
	}
	if cfg.Width < minSide || cfg.Width > maxSide || cfg.Height < minSide || cfg.Height > maxSide {
		return asset{}, fmt.Errorf("classifier: image is %dx%d; each side must be %d..%d px", cfg.Width, cfg.Height, minSide, maxSide)
	}
	return asset{Type: "image", Format: format, Data: p.Data}, nil
}

// ---------------------------------------------------------------- cache

// Cache is client-level and per QUESTION: asking the same state with one more
// question sends only the new one. Implementations must be safe for
// concurrent use. The library ships MemoryCache; a host persists by
// implementing these two methods (jevx's file cache would).
type Cache interface {
	Get(key string) (tn.DecisionAnswer, bool)
	Set(key string, a tn.DecisionAnswer)
}

// cacheKey hashes everything that decides an answer: endpoint, model, the
// encoded state, the image bytes and the question's wire form. The question's
// NAME is left out (it is never transmitted), and so are headers: rotating a
// key must not empty the cache. The key is a hash; content is never stored in it.
func cacheKey(base, model string, state any, assets map[string]asset, q tn.Question) string {
	b, _ := json.Marshal([]any{base, model, state, assets, tn.QuestionWire(q)}) // map keys marshal sorted
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// MemoryCache is an in-process TTL cache with a size cap (oldest out first).
type MemoryCache struct {
	TTL time.Duration
	Max int
	mu  sync.Mutex
	m   map[string]entry
	now func() time.Time
}

type entry struct {
	a  tn.DecisionAnswer
	at time.Time
}

func NewMemoryCache(ttl time.Duration, max int) *MemoryCache {
	return &MemoryCache{TTL: ttl, Max: max, m: map[string]entry{}, now: time.Now}
}

func (c *MemoryCache) Get(k string) (tn.DecisionAnswer, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[k]
	if !ok || (c.TTL > 0 && c.now().Sub(e.at) > c.TTL) {
		delete(c.m, k)
		return nil, false
	}
	return e.a, true
}

func (c *MemoryCache) Set(k string, a tn.DecisionAnswer) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.m[k] = entry{a, c.now()}
	if c.Max > 0 && len(c.m) > c.Max {
		ks := make([]string, 0, len(c.m))
		for k := range c.m {
			ks = append(ks, k)
		}
		sort.Slice(ks, func(i, j int) bool { return c.m[ks[i]].at.Before(c.m[ks[j]].at) })
		for _, k := range ks[:len(c.m)-c.Max] {
			delete(c.m, k)
		}
	}
}

// ---------------------------------------------------------------- validation

// Validate fails closed (jevx core.go:865, ADR 0038 V1): exactly the questions
// asked, probabilities in [0,1] summing to 1±0.02, a choice that was offered.
func Validate(qs map[string]tn.Question, d tn.Decision) error {
	if len(d.Answers) != len(qs) {
		return fmt.Errorf("classifier: invalid answer: %d answers for %d questions", len(d.Answers), len(qs))
	}
	dist := func(k string, m map[string]float64) error {
		if len(m) == 0 {
			return nil
		}
		s := 0.0
		for _, v := range m {
			if v < 0 || v > 1 {
				return fmt.Errorf("classifier: invalid answer %q: probability %v", k, v)
			}
			s += v
		}
		if s < 0.98 || s > 1.02 {
			return fmt.Errorf("classifier: invalid answer %q: probabilities sum to %.3f", k, s)
		}
		return nil
	}
	for k, q := range qs {
		a, ok := d.Answers[k]
		if !ok {
			return fmt.Errorf("classifier: invalid answer: none for %q", k)
		}
		switch x := a.(type) {
		case tn.NoulAnswer:
			if x.Noul < 0 || x.Noul > 1 {
				return fmt.Errorf("classifier: invalid answer %q: probability %v", k, x.Noul)
			}
		case tn.ChoiceAnswer:
			if cq, ok := q.(tn.ChoiceQuestion); ok {
				if _, offered := cq.Criteria[x.Choice]; !offered {
					return fmt.Errorf("classifier: invalid answer %q: %q was not offered", k, x.Choice)
				}
			}
			if err := dist(k, x.Probabilities); err != nil {
				return err
			}
		case tn.ScoreAnswer:
			if err := dist(k, x.Probabilities); err != nil {
				return err
			}
		}
	}
	return nil
}
