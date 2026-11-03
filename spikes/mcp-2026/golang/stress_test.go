package mcp2026

import (
	"bytes"
	"context"
	"fmt"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Stress rows print as "STRESS|..." so `./run.sh stress` can render a table.
func stressRow(cells ...any) {
	parts := make([]string, len(cells))
	for i, c := range cells {
		parts[i] = fmt.Sprint(c)
	}
	fmt.Println("STRESS|" + strings.Join(parts, "|"))
}

func settle() (goroutines int, heap uint64) {
	sharedHTTP.CloseIdleConnections()
	runtime.GC()
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return runtime.NumGoroutine(), m.HeapAlloc
}

// waitGoroutines polls until the count drops to <= limit (server conn goroutines exit asynchronously).
func waitGoroutines(limit int) int {
	n := 0
	for i := 0; i < 100; i++ {
		if n, _ = settle(); n <= limit {
			return n
		}
		time.Sleep(20 * time.Millisecond)
	}
	return n
}

func load(t *testing.T, name string, mk func() *Client, clients, calls int) {
	t.Helper()
	g0, h0 := settle()
	lat := make([]time.Duration, 0, clients*calls)
	var mu sync.Mutex
	var errs atomic.Int64
	var wg sync.WaitGroup
	start := time.Now()
	for i := 0; i < clients; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			c := mk()
			local := make([]time.Duration, 0, calls)
			for j := 0; j < calls; j++ {
				want := fmt.Sprintf("c%d-%d", i, j)
				t0 := time.Now()
				r, err := c.CallTool("echo", obj{"text": want}, nil)
				local = append(local, time.Since(t0))
				if err != nil || Text(r) != want {
					if errs.Add(1) == 1 {
						t.Errorf("%s: %v %v", name, err, r)
					}
				}
			}
			mu.Lock()
			lat = append(lat, local...)
			mu.Unlock()
		}(i)
	}
	wg.Wait()
	el := time.Since(start)
	sort.Slice(lat, func(i, j int) bool { return lat[i] < lat[j] })
	g1 := waitGoroutines(g0 + 5)
	_, h1 := settle()
	total := clients * calls
	stressRow(name, total, fmt.Sprintf("%.0f/s", float64(total)/el.Seconds()), lat[len(lat)/2].Round(time.Microsecond),
		lat[len(lat)*99/100].Round(time.Microsecond), errs.Load(), fmt.Sprintf("%d->%d", g0, g1),
		fmt.Sprintf("%+.1fMB", (float64(h1)-float64(h0))/(1<<20)))
	if errs.Load() != 0 {
		t.Fatalf("%s: %d errors", name, errs.Load())
	}
	if g1 > g0+5 {
		t.Fatalf("%s: goroutine leak %d -> %d", name, g0, g1)
	}
}

func TestStressLoadPlain(t *testing.T) {
	if testing.Short() {
		t.Skip("heavy")
	}
	ts := httptest.NewServer(mustServer())
	defer ts.Close()
	load(t, "200x50 echo, no auth", func() *Client { return NewClient(ts.URL + "/mcp") }, 200, 50)
}

func TestStressLoadOAuth(t *testing.T) {
	if testing.Short() {
		t.Skip("heavy")
	}
	e := newEnv(t)
	shared := &OAuth{ClientMetadataURL: e.cimdURL, Authorizer: LoopbackAuthorizer{Open: e.as.Approve}}
	mk := func() *Client { c := NewClient(e.url); c.Auth = shared; return c }
	if _, err := mk().CallTool("echo", obj{"text": "warm"}, nil); err != nil { // one login, then 200 clients share it
		t.Fatal(err)
	}
	load(t, "200x50 echo, OAuth (JWT verify)", mk, 200, 50)
	if a, _ := e.as.Stats(); a != 1 {
		t.Fatalf("authorizations=%d, want 1", a)
	}
}

// A cold herd: 100 concurrent first calls on one OAuth must cause ONE login, not 100.
func TestStressColdLoginHerd(t *testing.T) {
	e := newEnv(t)
	c := NewClient(e.url)
	c.Auth = &OAuth{ClientMetadataURL: e.cimdURL, Authorizer: LoopbackAuthorizer{Open: e.as.Approve}}
	var wg sync.WaitGroup
	var errs atomic.Int64
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if r, err := c.CallTool("echo", obj{"text": fmt.Sprint(i)}, nil); err != nil || Text(r) != fmt.Sprint(i) {
				errs.Add(1)
			}
		}(i)
	}
	wg.Wait()
	a, _ := e.as.Stats()
	stressRow("100 cold concurrent logins", 100, "-", "-", "-", errs.Load(), "-", fmt.Sprintf("authorizations=%d", a))
	if errs.Load() != 0 || a != 1 {
		t.Fatalf("errors=%d authorizations=%d", errs.Load(), a)
	}
}

func TestStressMRTRNoCrossTalk(t *testing.T) {
	ts := httptest.NewServer(mustServer())
	defer ts.Close()
	c := NewClient(ts.URL + "/mcp") // one shared client on purpose
	var wg sync.WaitGroup
	var crossed atomic.Int64
	start := time.Now()
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			me := fmt.Sprintf("caller-%d", i)
			r, err := c.CallTool("greet", obj{}, func(string, obj) (obj, error) {
				time.Sleep(time.Duration(rand.Intn(5)) * time.Millisecond) // shuffle completion order
				return obj{"action": "accept", "content": obj{"name": me}}, nil
			})
			if err != nil || Text(r) != "Hello, "+me+"!" {
				crossed.Add(1)
			}
		}(i)
	}
	wg.Wait()
	stressRow("100 parallel MRTR greet", 100, fmt.Sprintf("%.0f/s", 100/time.Since(start).Seconds()), "-", "-", crossed.Load(), "-", "-")
	if crossed.Load() != 0 {
		t.Fatalf("%d crossed/failed", crossed.Load())
	}
}

func TestStressRefreshRace(t *testing.T) {
	e := newEnv(t)
	c := e.autoClient()
	if _, err := c.CallTool("echo", obj{"text": "1"}, nil); err != nil {
		t.Fatal(err)
	}
	tok, _ := c.Auth.store().Load(e.url)
	exp := *tok
	exp.Expiry = time.Now().Add(-time.Minute)
	c.Auth.store().Save(e.url, &exp)
	var wg sync.WaitGroup
	var errs atomic.Int64
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if r, err := c.CallTool("echo", obj{"text": fmt.Sprint(i)}, nil); err != nil || Text(r) != fmt.Sprint(i) {
				errs.Add(1)
			}
		}(i)
	}
	wg.Wait()
	a, rf := e.as.Stats()
	stressRow("100 goroutines on expired token", 100, "-", "-", "-", errs.Load(), "-", fmt.Sprintf("refreshes=%d authz=%d", rf, a))
	if errs.Load() != 0 || rf != 1 || a != 1 {
		t.Fatalf("errors=%d refreshes=%d authorizations=%d (want 0/1/1)", errs.Load(), rf, a)
	}
}

func TestStressPauseResumeOutOfOrder(t *testing.T) {
	e := newEnv(t)
	c := e.pauseClient()
	const n = 50
	pend := make([]Pending, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, err := c.CallTool("echo", obj{"text": fmt.Sprintf("call-%d", i)}, nil)
			if p := PendingOf(err); p != nil {
				pend[i] = *p
			} else {
				t.Errorf("call %d: want pending, got %v", i, err)
			}
		}(i)
	}
	wg.Wait()
	cbs := make([]string, n)
	for i, p := range pend {
		cbs[i] = e.visit(t, p.URL)
	}
	order := rand.Perm(n)
	var bad atomic.Int64
	for _, i := range order {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if r, err := c.Auth.Resume(context.Background(), pend[i].ID, cbs[i]); err != nil || Text(r) != fmt.Sprintf("call-%d", i) {
				bad.Add(1)
				t.Errorf("resume %d: %v %v", i, err, r)
			}
		}(i)
	}
	wg.Wait()
	if _, err := c.Auth.Resume(context.Background(), pend[0].ID, cbs[0]); err == nil || !strings.Contains(err.Error(), "unknown pending") {
		t.Fatalf("double resume: %v", err)
	}
	if _, err := c.Auth.Resume(context.Background(), "auth-nope", cbs[0]); err == nil {
		t.Fatal("unknown id must fail")
	}
	if _, err := c.Auth.ResumeCallback(context.Background(), "http://127.0.0.1:9/callback?state=forged&code=x"); err == nil {
		t.Fatal("unknown state must fail")
	}
	stressRow("50 pendings resumed shuffled", n, "-", "-", "-", bad.Load(), "-", "double/unknown resume rejected")

	// Expiry: an expired pending is refused, and is purged when the next pending is created.
	c2 := e.pauseClient()
	_, err := c2.CallTool("echo", obj{"text": "late"}, nil)
	p := PendingOf(err)
	if p == nil {
		t.Fatalf("want pending, got %v", err)
	}
	cb := e.visit(t, p.URL)
	c2.Auth.mu.Lock()
	c2.Auth.flows[p.ID].expires = time.Now().Add(-time.Second)
	c2.Auth.mu.Unlock()
	if _, err := c2.Auth.Resume(context.Background(), p.ID, cb); err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("expired: %v", err)
	}
	_, err = c2.CallTool("echo", obj{"text": "a"}, nil)
	p1 := PendingOf(err)
	c2.Auth.mu.Lock()
	c2.Auth.flows[p1.ID].expires = time.Now().Add(-time.Second)
	c2.Auth.mu.Unlock()
	c2.CallTool("echo", obj{"text": "b"}, nil)
	c2.Auth.mu.Lock()
	_, still := c2.Auth.flows[p1.ID]
	left := len(c2.Auth.flows)
	c2.Auth.mu.Unlock()
	if still || left != 1 {
		t.Fatalf("expired pending not purged: still=%v left=%d", still, left)
	}
}

// --- hostile input ---

func rawPost(h http.Handler, body []byte, hdr map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest("POST", "/mcp", bytes.NewReader(body))
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func goodHeaders(method, name string) map[string]string {
	h := map[string]string{"Content-Type": "application/json", "MCP-Protocol-Version": ProtocolVersion, "Mcp-Method": method}
	if name != "" {
		h["Mcp-Name"] = name
	}
	return h
}

func hostileServer() *Server {
	s := mustServer()
	s.Tool("boom", "returns unmarshalable", func(context.Context, obj) (any, error) { return make(chan int), nil })
	return s
}

func TestHostileInputs(t *testing.T) {
	s := hostileServer()
	meta := fmt.Sprintf(`"_meta":{%q:%q}`, MetaVersion, ProtocolVersion)
	cases := []struct {
		name   string
		body   string
		hdr    map[string]string
		status int
	}{
		{"malformed json", `{"jsonrpc":`, goodHeaders("tools/list", ""), 400},
		{"params not object", `{"jsonrpc":"2.0","id":1,"method":"tools/list","params":[1,2]}`, goodHeaders("tools/list", ""), 400},
		{"missing headers", `{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{` + meta + `}}`, nil, 400},
		{"wrong content-type (accepted, body is JSON)", `{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{` + meta + `}}`,
			map[string]string{"Content-Type": "text/plain", "MCP-Protocol-Version": ProtocolVersion, "Mcp-Method": "tools/list"}, 200},
		{"name not a string", `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":7,` + meta + `}}`, goodHeaders("tools/call", "7"), 400},
		{"arguments not object", `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":"x",` + meta + `}}`, goodHeaders("tools/call", "echo"), 200},
		{"bad base64 Mcp-Name", `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo",` + meta + `}}`, goodHeaders("tools/call", "=?base64?!!!?="), 400},
		{"unmarshalable tool result", `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boom",` + meta + `}}`, goodHeaders("tools/call", "boom"), 200},
		{"_meta not object", `{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":5}}`, goodHeaders("tools/list", ""), 400},
		{"huge body", `{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"pad":"` + strings.Repeat("A", MaxBodyBytes) + `"}}`, goodHeaders("tools/list", ""), 413},
	}
	for _, tc := range cases {
		w := rawPost(s, []byte(tc.body), tc.hdr)
		if w.Code != tc.status {
			t.Errorf("%s: status %d want %d: %.200s", tc.name, w.Code, tc.status, w.Body.String())
		}
	}
}

// Garbage bearers on a protected server: always 401, never a panic, and unknown kids do not
// turn every request into a JWKS fetch at the AS.
func TestHostileBearerTokens(t *testing.T) {
	e := newEnv(t)
	var jwksFetches atomic.Int64
	base := e.hc.Transport
	e.server.Auth.HTTP = &http.Client{Transport: rtFunc(func(r *http.Request) (*http.Response, error) {
		if strings.HasSuffix(r.URL.Path, "/jwks") {
			jwksFetches.Add(1)
		}
		return base.RoundTrip(r)
	})}
	b64 := func(s string) string { return strings.TrimRight(encodeB64URL(s), "=") }
	garbage := []string{"x", "a.b.c", "....", strings.Repeat("A", 8000), "Bearer",
		b64(`{"alg":"none"}`) + "." + b64(`{}`) + ".",
		b64(`{"alg":"RS256","kid":"dev"}`) + "." + b64(`{"iss":1}`) + ".AAAA"}
	for i := 0; i < 50; i++ {
		garbage = append(garbage, b64(fmt.Sprintf(`{"alg":"RS256","kid":"k%d"}`, i))+"."+b64(`{}`)+".AAAA")
	}
	for _, g := range garbage {
		if st, _, _ := rawCall(t, e, g, "echo"); st != 401 {
			t.Errorf("token %.20q: status %d", g, st)
		}
	}
	if n := jwksFetches.Load(); n > 1 {
		t.Fatalf("JWKS fetched %d times for garbage kids (want <=1)", n)
	}
}

type rtFunc func(*http.Request) (*http.Response, error)

func (f rtFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func encodeB64URL(s string) string {
	const a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	var out []byte
	b := []byte(s)
	for i := 0; i < len(b); i += 3 {
		var n uint32
		k := min(3, len(b)-i)
		for j := 0; j < 3; j++ {
			n <<= 8
			if j < k {
				n |= uint32(b[i+j])
			}
		}
		for j := 0; j < k+1; j++ {
			out = append(out, a[(n>>(18-6*j))&63])
		}
	}
	return string(out)
}

// The client must not hang on a server that accepts and never answers, nor panic on garbage replies.
func TestClientHungAndHostileServer(t *testing.T) {
	if defaultHTTP().Timeout <= 0 {
		t.Fatal("default client has no timeout")
	}
	release := make(chan struct{})
	hung := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-release }))
	defer hung.Close()
	defer close(release)
	c := NewClient(hung.URL + "/mcp")
	c.HTTP = &http.Client{Timeout: 300 * time.Millisecond}
	t0 := time.Now()
	if _, err := c.CallTool("echo", obj{"text": "x"}, nil); err == nil || time.Since(t0) > 3*time.Second {
		t.Fatalf("hung server: err=%v after %v", err, time.Since(t0))
	}
	replies := []string{
		`{"jsonrpc":"2.0","id":1}`,
		`{"jsonrpc":"2.0","id":1,"result":null}`,
		`{"jsonrpc":"2.0","id":1,"result":{"resultType":"input_required","inputRequests":{"k":5}}}`,
		`{"jsonrpc":"2.0","id":1,"result":{"resultType":"input_required","inputRequests":{"k":{}}}}`,
		`not json`, `[]`, `{"result":[1]}`,
	}
	for _, rep := range replies {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(rep))
		}))
		func() {
			defer func() {
				if p := recover(); p != nil {
					t.Errorf("client panicked on %s: %v", rep, p)
				}
			}()
			NewClient(srv.URL+"/mcp").CallTool("echo", obj{"text": "x"}, nil) // nil Responder on purpose
		}()
		srv.Close()
	}
}

func FuzzServerHandler(f *testing.F) {
	meta := fmt.Sprintf(`"_meta":{%q:%q}`, MetaVersion, ProtocolVersion)
	for _, s := range []string{
		`{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{` + meta + `}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"greet","inputResponses":{"name":{"action":"accept","content":{"name":"x"}}},` + meta + `}}`,
		`{"jsonrpc":"2.0","id":"a","method":"skills/get","params":{"uri":"skill://hello-world/SKILL.md",` + meta + `}}`,
		`{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"skill://hello-world/SKILL.md",` + meta + `}}`,
		`{"id":null}`, `[]`, `{"jsonrpc":"2.0","id":{},"method":"server/discover","params":{"_meta":[]}}`,
	} {
		f.Add([]byte(s), "tools/list", "echo")
	}
	s := hostileServer()
	f.Fuzz(func(t *testing.T, body []byte, method, name string) {
		h := map[string]string{"Content-Type": "application/json", "MCP-Protocol-Version": ProtocolVersion, "Mcp-Method": method, "Mcp-Name": name}
		w := rawPost(s, body, h)
		if w.Code < 200 || w.Code > 599 {
			t.Fatalf("status %d", w.Code)
		}
	})
}
