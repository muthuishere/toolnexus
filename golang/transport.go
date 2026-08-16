package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
)

// ---- ADR 0019 REVISED spike: one Transport seam, PARSED body ----
//
// Transport := (ctx, TransportRequest) -> TransportResponse
//   TransportRequest  = {url, method, headers, body: <parsed request>, stream: bool}
//   TransportResponse = {status, headers, body: <parsed response> | <byte stream>}
//
// "Parsed" in Go means `any`: the body the adapters already build
// (map[string]any) travels un-marshalled. A streaming response returns an
// io.ReadCloser in the same field — the union the ADR specifies.

// SerOps counts JSON serialization operations on the LLM path, so a spike can
// prove (or disprove) "zero serialization for the in-process case".
var SerOps struct {
	Marshal   int64 // library-side json.Marshal on the LLM path
	Unmarshal int64 // library-side json.Unmarshal on the LLM path
}

func ResetSerOps() { atomic.StoreInt64(&SerOps.Marshal, 0); atomic.StoreInt64(&SerOps.Unmarshal, 0) }

func countMarshal(v any) ([]byte, error) {
	atomic.AddInt64(&SerOps.Marshal, 1)
	return json.Marshal(v)
}

func countUnmarshal(b []byte, v any) error {
	atomic.AddInt64(&SerOps.Unmarshal, 1)
	return json.Unmarshal(b, v)
}

type TransportRequest struct {
	URL     string
	Method  string
	Headers map[string]string
	// Body is the PARSED provider body (map[string]any as the adapters built it).
	// Nothing marshalled it; a transport that needs bytes marshals it itself.
	Body any
	// Stream says which response shape the client expects.
	Stream bool
}

type TransportResponse struct {
	Status  int
	Headers map[string]string
	raw     []byte         // cached bytes, once a stream body has been read
	pmap    map[string]any // cached parse, so hooks don't re-pay
	// Body is either a parsed value (map[string]any) or an io.ReadCloser byte
	// stream (streaming responses, and any transport that has real bytes).
	Body any
}

type Transport interface {
	RoundTrip(ctx context.Context, req TransportRequest) (*TransportResponse, error)
}

type TransportFunc func(ctx context.Context, req TransportRequest) (*TransportResponse, error)

func (f TransportFunc) RoundTrip(ctx context.Context, req TransportRequest) (*TransportResponse, error) {
	return f(ctx, req)
}

// httpTransport is the port-native DEFAULT implementation: today's *http.Client.
// It is the case that pays for the parsed shape — it must marshal the request
// itself, and (non-streaming) parse the response before handing it back.
type httpTransport struct{ client *http.Client }

func (t httpTransport) RoundTrip(ctx context.Context, tr TransportRequest) (*TransportResponse, error) {
	raw, err := countMarshal(tr.Body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, tr.Method, tr.URL, strings.NewReader(string(raw)))
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
	// Streaming (or a non-2xx, whose body may not be JSON at all) stays bytes.
	if tr.Stream || resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &TransportResponse{Status: resp.StatusCode, Headers: h, Body: resp.Body}, nil
	}
	return &TransportResponse{Status: resp.StatusCode, Headers: h, Body: resp.Body}, nil
}

// header does a case-insensitive lookup, normatively (ADR 0019 rule 4).
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

// closeBody closes a byte-stream body; a parsed body has nothing to close.
func (r *TransportResponse) closeBody() {
	if rc, ok := r.Body.(io.ReadCloser); ok {
		rc.Close()
	}
}

// stream returns the body as a byte stream. A parsed body has to be marshalled
// back — the price of the union.
func (r *TransportResponse) stream() (io.ReadCloser, error) {
	switch b := r.Body.(type) {
	case io.ReadCloser:
		return b, nil
	case nil:
		return NewBodyReader(nil), nil
	default:
		raw, err := countMarshal(b)
		if err != nil {
			return nil, err
		}
		return NewBodyReader(raw), nil
	}
}

// text renders the body as text for an error message. THIS IS THE LOSSY ONE:
// a parsed body cannot represent an HTML/plaintext error page, so a transport
// reporting an error either returns bytes or the exact upstream text is gone.
func (r *TransportResponse) text() string {
	switch b := r.Body.(type) {
	case io.ReadCloser:
		out, _ := io.ReadAll(b)
		b.Close()
		return string(out)
	case nil:
		return ""
	case string:
		return b
	default:
		raw, _ := countMarshal(b)
		return string(raw)
	}
}

// decodeInto is the GENERIC path a statically-typed port must take: the client
// wants a typed struct, the transport handed it a map, so it costs a marshal +
// an unmarshal. On the bytes path it costs exactly one unmarshal, same as today.
func (r *TransportResponse) decodeInto(v any) error {
	if r.raw == nil {
		switch b := r.Body.(type) {
		case io.ReadCloser:
			out, err := io.ReadAll(b)
			b.Close()
			if err != nil {
				return err
			}
			r.raw = out
		case nil:
			return fmt.Errorf("empty body")
		default:
			raw, err := countMarshal(b) // <-- the tax parsed-body adds in Go
			if err != nil {
				return err
			}
			r.raw = raw
		}
	}
	return countUnmarshal(r.raw, v)
}

// decodeResponseMap is what the AfterLLM hook / TranslateResult.Raw need: the
// response as map[string]any. FREE when the transport handed one over.
func (r *TransportResponse) decodeResponseMap() map[string]any {
	if m, ok := r.Body.(map[string]any); ok {
		return m
	}
	if r.pmap != nil {
		return r.pmap
	}
	var m map[string]any
	if r.raw != nil {
		_ = countUnmarshal(r.raw, &m)
	}
	r.pmap = m
	return m
}

// ParsedMap returns the parsed body if the transport supplied one (no work), or
// parses the byte stream once.
func (r *TransportResponse) ParsedMap() (map[string]any, error) {
	switch b := r.Body.(type) {
	case map[string]any:
		return b, nil
	case io.ReadCloser:
		out, err := io.ReadAll(b)
		b.Close()
		if err != nil {
			return nil, err
		}
		var m map[string]any
		if err := countUnmarshal(out, &m); err != nil {
			return nil, err
		}
		return m, nil
	default:
		return nil, fmt.Errorf("unsupported body %T", r.Body)
	}
}

func NewBodyReader(b []byte) io.ReadCloser { return io.NopCloser(strings.NewReader(string(b))) }
