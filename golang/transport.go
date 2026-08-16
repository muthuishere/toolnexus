package toolnexus

import (
	"context"
	"io"
	"net/http"
	"strings"
)

// ---- ADR 0019 spike: the one Transport seam ----

// TransportRequest is what toolnexus hands a Transport. Body is always bytes —
// the provider body is marshalled OUTSIDE the seam in every port.
type TransportRequest struct {
	URL     string
	Method  string
	Headers map[string]string
	Body    []byte
}

// TransportResponse is what a Transport hands back. Body is a STREAM, never a
// byte slice — the streaming paths read it incrementally (scanSSE).
type TransportResponse struct {
	Status  int
	Headers map[string]string
	Body    io.ReadCloser
}

// Transport (§8 Gap 2, ADR 0019) is the single injectable seam for the LLM path.
// Anything that can answer "take this request, give me back that response" is a
// valid transport — an HTTP client, a proxy, a record/replay fixture, a pure
// in-memory fake, or an in-process model with no socket anywhere.
type Transport interface {
	RoundTrip(ctx context.Context, req TransportRequest) (*TransportResponse, error)
}

// TransportFunc adapts a plain function to Transport.
type TransportFunc func(ctx context.Context, req TransportRequest) (*TransportResponse, error)

func (f TransportFunc) RoundTrip(ctx context.Context, req TransportRequest) (*TransportResponse, error) {
	return f(ctx, req)
}

// httpTransport is the port-native default: the shipped *http.Client path,
// expressed as a Transport. Absent any option this wraps http.DefaultClient, so
// behaviour is byte-identical to before ADR 0019.
type httpTransport struct{ client *http.Client }

func (t httpTransport) RoundTrip(ctx context.Context, tr TransportRequest) (*TransportResponse, error) {
	req, err := http.NewRequestWithContext(ctx, tr.Method, tr.URL, strings.NewReader(string(tr.Body)))
	if err != nil {
		return nil, err
	}
	for k, v := range tr.Headers {
		req.Header.Set(k, v)
	}
	resp, err := t.client.Do(req)
	if err != nil {
		return nil, err
	}
	h := make(map[string]string, len(resp.Header))
	for k := range resp.Header {
		h[k] = resp.Header.Get(k)
	}
	return &TransportResponse{Status: resp.StatusCode, Headers: h, Body: resp.Body}, nil
}

// header does a case-insensitive lookup, because a map[string]string loses the
// canonicalisation http.Header gives for free. (ADR 0019 finding.)
func (r *TransportResponse) header(name string) string {
	if v, ok := r.Headers[name]; ok {
		return v
	}
	for k, v := range r.Headers {
		if strings.EqualFold(k, name) {
			return v
		}
	}
	return ""
}

// NewBodyReader wraps bytes as a one-shot stream body, for non-streaming
// transports.
func NewBodyReader(b []byte) io.ReadCloser { return io.NopCloser(strings.NewReader(string(b))) }
