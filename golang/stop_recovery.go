package toolnexus

import (
	"encoding/json"
	"strings"
)

// The three pinned user messages of SPEC §8 "Stop-reason recovery" — byte-exact in every port.
const (
	StopRecoveryNudge      = "Your previous response contained no answer. Continue and give your answer."
	StopRecoveryContinue   = "Your previous response was cut off at the output token limit. Continue exactly where it stopped, without repeating anything."
	StopRecoveryCorrective = "Your previous tool call could not be used: its arguments were not a valid JSON object. Call the tool again with arguments that are a single valid JSON object matching its input schema."
)

// stopRecovery holds the per-run budgets (corrective 1, continue 3, nudge 1) and the carried text.
type stopRecovery struct {
	on                    bool
	correct, cont, nudges int
	carry                 string
}

// decide classifies one response (SPEC §8 order). It returns the pinned text to append ("" ⇒ no
// recovery) and whether the assistant message must be dropped from the transcript.
func (s *stopRecovery) decide(lastTurn, hasCalls, malformed, truncated bool, text string) (string, bool) {
	if !s.on || lastTurn {
		return "", false
	}
	switch {
	case hasCalls && malformed && s.correct < 1:
		s.correct++
		return StopRecoveryCorrective, true
	case !hasCalls && truncated && s.cont < 3:
		s.cont++
		s.carry += text
		return StopRecoveryContinue, false
	case !hasCalls && !truncated && strings.TrimSpace(text) == "" && s.nudges < 1:
		s.nudges++
		return StopRecoveryNudge, true
	}
	return "", false
}

// final is the run's text: carried continuations + the final response's text.
func (s *stopRecovery) final(text string) string { return s.carry + text }

// malformedArgs reports whether a raw tool-arguments string is not a JSON object
// (empty/whitespace is `{}` and is not malformed).
func malformedArgs(raw string) bool {
	t := strings.TrimSpace(raw)
	if t == "" {
		return false
	}
	var out map[string]any
	return json.Unmarshal([]byte(t), &out) != nil || out == nil
}

// malformedInput reports whether an Anthropic tool_use input is present and not an object.
func malformedInput(in any) bool {
	if in == nil {
		return false
	}
	_, ok := in.(map[string]any)
	return !ok
}
