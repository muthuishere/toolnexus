package main

// Minimal Classifier (ADR 0020 D2/D3 shape) — one POST, no SDK.
import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"sync/atomic"
	"time"
)

type Classifier struct {
	BaseURL, Model, APIKeyEnv string
	HTTP                      *http.Client
	Calls                     int64
	Nanos                     int64
	CostMicros                int64
}

type Decision struct {
	Model   string                     `json:"model"`
	Answers map[string]json.RawMessage `json:"answers"`
	Usage   struct {
		InputTokens int     `json:"input_tokens"`
		Cost        float64 `json:"cost"`
	} `json:"usage"`
}

type ChoiceAnswer struct {
	Choice        string             `json:"choice"`
	Confidence    float64            `json:"confidence"`
	Probabilities map[string]float64 `json:"probabilities"`
}

// canonical: sort objects recursively, NEVER reorder arrays (verdict finding 4).
func canonical(v any) ([]byte, error) {
	var b bytes.Buffer
	e := json.NewEncoder(&b)
	e.SetEscapeHTML(false) // Go escapes <>& by default — the trap the fixture missed
	if err := e.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(b.Bytes(), "\n"), nil
}

func (c *Classifier) Evaluate(state any, questions map[string]any) (*Decision, error) {
	body, err := canonical(map[string]any{"model": c.Model, "state": state, "questions": questions})
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequest("POST", c.BaseURL+"/systemone", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+os.Getenv(c.APIKeyEnv)) // value never logged
	start := time.Now()
	res, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	atomic.AddInt64(&c.Calls, 1)
	atomic.AddInt64(&c.Nanos, int64(time.Since(start)))
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("classifier HTTP %d: %.200s", res.StatusCode, raw)
	}
	var d Decision
	if err := json.Unmarshal(raw, &d); err != nil {
		return nil, err
	}
	atomic.AddInt64(&c.CostMicros, int64(d.Usage.Cost*1e6))
	return &d, nil
}

func (d *Decision) Choice(key string) (ChoiceAnswer, error) {
	var a ChoiceAnswer
	raw, ok := d.Answers[key]
	if !ok {
		return a, fmt.Errorf("no answer %q", key)
	}
	return a, json.Unmarshal(raw, &a)
}

// choiceOver builds a choice question from {name: description} — the same
// helper that works on Tools, skills, agents and A2A cards.
func choiceOver(instructions string, criteria map[string]string) map[string]any {
	keys := make([]string, 0, len(criteria))
	for k := range criteria {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	c := map[string]any{}
	for _, k := range keys {
		c[k] = criteria[k]
	}
	return map[string]any{"type": "choice", "instructions": instructions, "criteria": c}
}
