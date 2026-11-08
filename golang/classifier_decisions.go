package toolnexus

// The Decisions API wire (SPEC.md §8B "Decisions wire", OpenSpec change
// add-classifier-openai-decisions, issue #157). OpenAI's POST /v1/decisions
// takes the same three question kinds as System One under other names
// (predicate / choice / score) as an ORDERED LIST, and answers in that order.
// This file maps a §8B question set onto that list and reads the answers back
// into the one Decision type, so a caller never sees which wire answered.

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
)

const (
	// BackendOpenAI is OpenAI's Decisions API: gpt-6-luna, OPENAI_API_KEY.
	BackendOpenAI ClassifierBackend = "openai"
	// ClassifierWireDecisions names the Decisions API request shape.
	ClassifierWireDecisions = "decisions"
	// MaxDecisionsQuestions is the Decisions API's per-request question cap.
	MaxDecisionsQuestions = 400
)

// decisionsQuestions maps the question set to the Decisions API list, in ASCII
// key order. Caller keys are NOT sent (no "name"): §8B keys are addressing, not
// content, and the answers come back in request order. Every check here fires
// before any request.
func decisionsQuestions(backend ClassifierBackend, model string, questions map[string]Question) ([]string, []any, error) {
	if len(questions) > MaxDecisionsQuestions {
		return nil, nil, fmt.Errorf("classifier: the decisions wire takes at most %d questions, got %d",
			MaxDecisionsQuestions, len(questions))
	}
	keys := sortedQuestionKeys(questions)
	out := make([]any, 0, len(keys))
	for _, k := range keys {
		switch q := questions[k].(type) {
		case NoulQuestion:
			instr := q.Instructions
			if q.Criteria != nil {
				instr += "\n\nTrue means: " + q.Criteria.True + "\nFalse means: " + q.Criteria.False
			}
			out = append(out, map[string]any{"type": "predicate", "instructions": instr})
		case ChoiceQuestion:
			if len(q.Criteria) < 2 {
				return nil, nil, fmt.Errorf("classifier: question %q: the decisions wire needs 2..%d choice options, got %d",
					k, MaxChoiceOptions, len(q.Criteria))
			}
			ids := make([]string, 0, len(q.Criteria))
			for id := range q.Criteria {
				ids = append(ids, id)
			}
			sort.Strings(ids)
			choices := make([]any, len(ids))
			for i, id := range ids {
				c := map[string]any{"value": id}
				if d := q.Criteria[id]; d != "" {
					c["description"] = d
				}
				choices[i] = c
			}
			out = append(out, map[string]any{"type": "choice", "instructions": q.Instructions, "choices": choices})
		case ScoreQuestion:
			levels := make([]any, len(q.Criteria))
			for i, l := range q.Criteria {
				levels[i] = map[string]any{"label": l}
			}
			out = append(out, map[string]any{"type": "score", "instructions": q.Instructions, "levels": levels})
		case RawQuestion:
			return nil, nil, &ErrClassifierUnsupported{Backend: string(backend), Model: model,
				Feature: fmt.Sprintf("raw question type %q", q.Type)}
		default:
			return nil, nil, fmt.Errorf("classifier: question %q: unsupported question", k)
		}
	}
	return keys, out, nil
}

// decisionsInput is the state as sent: a string state verbatim, any other state
// as its canonical JSON text; with images, one user message holding that text
// then the images as data URLs, in collection order.
func decisionsInput(p preparedRequest) (any, error) {
	text, ok := p.state.(string)
	if !ok {
		b, err := canonicalJSON(p.state)
		if err != nil {
			return nil, err
		}
		text = string(b)
	}
	if len(p.imgs) == 0 {
		return text, nil
	}
	content := []any{map[string]any{"type": "input_text", "text": text}}
	for _, im := range p.imgs {
		content = append(content, map[string]any{"type": "input_image",
			"image_url": "data:image/" + im.asset.Format + ";base64," + im.asset.Data})
	}
	return []any{map[string]any{"role": "user", "content": content}}, nil
}

// decisionsReply reads a Decisions API reply back into a System One-shaped body
// (canonical JSON), so decode, validation and the cache are the one path.
// calibrated is false: a Decisions model is not a System One model.
func decisionsReply(body []byte, questions map[string]Question) ([]byte, error) {
	malformed := errors.New("classifier: decisions: malformed reply")
	var r struct {
		Model   string            `json:"model"`
		Answers []json.RawMessage `json:"answers"`
		Usage   struct {
			InputTokens  int `json:"input_tokens"`
			OutputTokens int `json:"output_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(body, &r); err != nil || r.Answers == nil {
		return nil, malformed
	}
	keys := sortedQuestionKeys(questions)
	if len(r.Answers) != len(keys) {
		return nil, fmt.Errorf("classifier: decisions: %d answers for %d questions", len(r.Answers), len(keys))
	}
	answers := make(map[string]any, len(keys))
	for i, k := range keys {
		var a struct {
			Type          string  `json:"type"`
			Probability   float64 `json:"probability"`
			Choice        any     `json:"choice"`
			Score         float64 `json:"score"`
			Confidence    float64 `json:"confidence"`
			Probabilities []struct {
				Value       any     `json:"value"`
				Probability float64 `json:"probability"`
			} `json:"probabilities"`
		}
		if err := json.Unmarshal(r.Answers[i], &a); err != nil {
			return nil, malformed
		}
		if a.Type == "refusal" {
			return nil, fmt.Errorf("classifier: decisions: question %q was refused", k)
		}
		want := map[string]string{"noul": "predicate", "choice": "choice", "score": "score"}[questionType(questions[k])]
		if a.Type != want {
			return nil, fmt.Errorf("classifier: decisions: answer %q is a %s answer, not %s", k, a.Type, want)
		}
		probs := make(map[string]any, len(a.Probabilities))
		for _, p := range a.Probabilities {
			probs[decisionsValue(p.Value)] = p.Probability
		}
		switch q := questions[k].(type) {
		case NoulQuestion:
			answers[k] = map[string]any{"type": "noul", "noul": a.Probability}
		case ChoiceQuestion:
			answers[k] = map[string]any{"type": "choice", "choice": decisionsValue(a.Choice),
				"probabilities": probs, "confidence": a.Confidence}
		case ScoreQuestion:
			legend := make(map[string]any, len(q.Criteria))
			for j, l := range q.Criteria {
				legend[strconv.Itoa(j)] = l
			}
			answers[k] = map[string]any{"type": "score", "score": a.Score, "legend": legend,
				"probabilities": probs, "confidence": a.Confidence}
		}
	}
	return canonicalJSON(map[string]any{
		"answers":    answers,
		"calibrated": false,
		"model":      r.Model,
		"usage":      map[string]any{"input_tokens": r.Usage.InputTokens, "output_tokens": r.Usage.OutputTokens},
	})
}

// decisionsValue renders a choice value or a level index as the §8B key: a
// string as written, a boolean as "true"/"false", a number in its shortest form.
func decisionsValue(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case bool:
		return strconv.FormatBool(x)
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	}
	return fmt.Sprint(v)
}
