package toolnexus

// Reactive compaction on context overflow (SPEC.md §7F.1, change
// add-reactive-compaction, issue #194). A proactive compactor works from an
// estimate; the provider is the only authority on whether a prompt fits. When
// it rejects a request as too long and ClientOptions.CompactOnOverflow is set,
// the loop compacts the working transcript and re-sends the same turn, guarded
// by a "rapid refill" breaker. Unset ⇒ byte-identical: the *ProviderError
// surfaces exactly as before.

import (
	"context"
	"errors"
	"fmt"
	"strings"
)

// overflowMarkers are the pinned body markers (matched lowercased).
var overflowMarkers = []string{
	"context_length_exceeded",
	"maximum context length",
	"prompt is too long",
	"prompt_too_long",
	"request_too_large",
	"context window",
}

// IsContextOverflow reports whether a provider failure means "the prompt does
// not fit": status 400 or 413 AND the (redacted) body, lowercased, contains one
// of the pinned markers. Nothing else counts (SPEC §7F.1).
func IsContextOverflow(status int, body string) bool {
	if status != 400 && status != 413 {
		return false
	}
	b := strings.ToLower(body)
	for _, m := range overflowMarkers {
		if strings.Contains(b, m) {
			return true
		}
	}
	return false
}

// ContextOverflowError is the run failing because reactive compaction could not
// make the prompt fit: the breaker tripped (Compactions == 3) or the hook made
// no progress. Status and Body come from the last provider error (Body already
// redacted, as on ProviderError). Match it with errors.As. It unwraps to that
// last *ProviderError.
type ContextOverflowError struct {
	// Compactions is the streak at failure: 3 from the breaker, else the number
	// of compactions in the current rapid chain.
	Compactions int
	// Status is the HTTP status of the last overflow response.
	Status int
	// Body is the redacted body of the last overflow response.
	Body string

	last *ProviderError
}

// Error returns the pinned, byte-identical message.
func (e *ContextOverflowError) Error() string {
	return fmt.Sprintf("context overflow: %d reactive compaction(s) did not make the prompt fit", e.Compactions)
}

// Unwrap returns the last provider error, so errors.As(err, **ProviderError)
// still finds the status/body that triggered the failure.
func (e *ContextOverflowError) Unwrap() error {
	if e.last == nil {
		return nil
	}
	return e.last
}

// overflowBreaker holds the per-run counters of §7F.1: okSince (successful LLM
// calls since the last reactive compaction) and streak (compactions in the
// current rapid chain).
type overflowBreaker struct {
	okSince int
	streak  int
}

// ok records one successful LLM call.
func (b *overflowBreaker) ok() { b.okSince++ }

// react decides what a failed LLM call becomes. It returns (messages, true, nil)
// when the turn must be re-sent with the compacted transcript; otherwise
// (nil, false, err) where err is the error the run fails with — the original
// one untouched when the option is unset or the failure is not an overflow.
// BeforeLLM is NOT re-run and no turn is consumed by the caller on retry.
func (c *Client) reactOverflow(ctx context.Context, b *overflowBreaker, callErr error, messages, tools []any, model string, turn int) ([]any, bool, error) {
	if c.opts.CompactOnOverflow == nil {
		return nil, false, callErr
	}
	var pe *ProviderError
	if !errors.As(callErr, &pe) || !IsContextOverflow(pe.Status, pe.Body) {
		return nil, false, callErr
	}
	fail := func() error {
		return &ContextOverflowError{Compactions: b.streak, Status: pe.Status, Body: pe.Body, last: pe}
	}
	if !(b.streak > 0 && b.okSince < 2) { // not a rapid refill
		b.streak = 0
	}
	if b.streak == 3 {
		return nil, false, fail()
	}
	ov, err := c.opts.CompactOnOverflow(ctx, BeforeLLMEvent{Messages: messages, Tools: tools, Model: model, Turn: turn})
	if err != nil {
		return nil, false, err
	}
	if ov == nil || ov.Messages == nil || len(ov.Messages) >= len(messages) {
		return nil, false, fail()
	}
	b.streak++
	b.okSince = 0
	return ov.Messages, true, nil
}
