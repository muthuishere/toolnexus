package toolnexus

// What the library hands back when it FAILS is part of the contract (ADR 0027).
//
// A failure is a return value. It gets the same treatment the success path gets:
// a closed vocabulary, a stated absence, and no credential-adjacent data. Three
// rules, applied in this order — structure first, redact second, cap third:
//
//  1. the provider failure travels as a VALUE (ProviderError) with Status, Body
//     and RetryAfter as fields, so a host deciding what to log never has to parse
//     err.Error();
//  2. known ACCOUNT IDENTIFIERS are replaced with «redacted» before the body is
//     interpolated anywhere — a length cap is not redaction (the body that
//     started this was 96 bytes, well under any cap);
//  3. an auth body (401/403) is dropped entirely, because it routinely reflects
//     the credential or the header that was sent, and what is left is capped at
//     200 characters IN THE MESSAGE. The typed Body field carries the whole
//     redacted body — a host that reached for the type asked for all of it.
//
// Rules 2 and 3 are the policy the classifier path has enforced since it shipped
// (classifier.go `cause`); this lifts it to the §8 client path rather than
// writing it an eighth time, differently.

import (
	"context"
	"fmt"
	"regexp"
	"strings"
)

// providerBodyCap is the §8B cap, reused verbatim: a body longer than this is
// truncated with an ellipsis.
const providerBodyCap = 200

// RedactedMarker replaces a redacted value. The SHAPE survives — a host reading
// the body can still see that the field was there — while the value does not.
const RedactedMarker = "«redacted»"

// redactedKeys are the account identifiers scrubbed out of any provider body
// before it reaches a message, a log or a metric. Pinned across all seven ports
// (SPEC.md §8 credentials guarantee).
var redactedKeys = []string{"user_id", "account_id", "org_id", "organization"}

// redactRe matches `"<key>": <json-value>` for each redacted key, in the JSON
// bodies providers actually return. Deliberately textual: the body may not be
// valid JSON at all (an HTML error page, a truncated stream), and a redaction
// that only works on parseable input is not a redaction.
var redactRe = regexp.MustCompile(`("(?:` + strings.Join(redactedKeys, "|") + `)"\s*:\s*)("(?:[^"\\]|\\.)*"|[^,}\s]+)`)

// RedactProviderBody applies rules 2 and 3 to a raw provider body: an auth body
// is dropped in full and account identifiers are replaced. It does NOT cap — the
// cap belongs to the message (see ProviderError.Error). Exported because a host
// that logs its own provider calls should apply the same policy rather than
// re-derive it.
func RedactProviderBody(status int, body string) string {
	if status == 401 || status == 403 {
		return ""
	}
	s := strings.TrimSpace(body)
	if s == "" {
		return ""
	}
	return redactRe.ReplaceAllString(s, `${1}"`+RedactedMarker+`"`)
}

// capBody truncates for the MESSAGE only.
func capBody(s string) string {
	if len(s) > providerBodyCap {
		return s[:providerBodyCap] + "…"
	}
	return s
}

// ProviderError is a non-2xx response from the LLM provider, carried as a value
// rather than a sentence. Match it with errors.As:
//
//	var pe *tn.ProviderError
//	if errors.As(err, &pe) && pe.Status == 402 { … }
//
// Body is ALREADY redacted — there is no raw form on this type, by design: the
// value a host wants is reachable from the field, and the value a host must
// never log cannot be reached at all. It is NOT capped; Error() caps.
type ProviderError struct {
	// Status is the HTTP status of the failed response.
	Status int
	// Body is the whole response body, redacted (possibly empty — an auth body is
	// dropped in full, and an empty body stays empty). Uncapped: the 200-char cap
	// is a property of the rendered message, not of the value.
	Body string
	// RetryAfter is the raw Retry-After header, when the provider sent one.
	// Empty otherwise; absence is expressible (ADR 0022's principle).
	RetryAfter string
}

// Error renders `LLM <status>: <redacted body>` — byte-identical to the string
// this type replaced whenever the body carried nothing that had to be scrubbed,
// which is what keeps a host that still matches on text working.
func (e *ProviderError) Error() string {
	if e.Body == "" {
		return fmt.Sprintf("LLM %d", e.Status)
	}
	return fmt.Sprintf("LLM %d: %s", e.Status, capBody(e.Body))
}

// newProviderError builds the typed error from a raw response.
func newProviderError(status int, body []byte, retryAfter string) *ProviderError {
	return &ProviderError{
		Status:     status,
		Body:       RedactProviderBody(status, string(body)),
		RetryAfter: retryAfter,
	}
}

// RunTimeoutError is the whole-run deadline (ClientOptions.TimeoutMs) expiring.
// It names the budget, so a deadline is never mistaken for caller cancellation —
// which is exactly what the bare `context deadline exceeded` it replaced was.
//
// Go is the only port that also hands back a RunResult here; that result is
// Status "incomplete" with Limit "timeout" and the Turns/Usage accumulated
// before the deadline, never the zero value (ADR 0027 D2, DECISIONS D5).
type RunTimeoutError struct {
	// TimeoutMs is the configured whole-run deadline, in milliseconds.
	TimeoutMs int
}

func (e *RunTimeoutError) Error() string {
	return fmt.Sprintf("run timeout after %dms", e.TimeoutMs)
}

// Unwrap keeps errors.Is(err, context.DeadlineExceeded) working for hosts that
// already branch on it.
func (e *RunTimeoutError) Unwrap() error { return context.DeadlineExceeded }
