package main

// Side effects run exactly once across auth pauses, refresh-after-401 and step-up; a lost or failed
// response is never retried (spec issue #3394).

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// counted registers "tick" (base scope) and "charge" (needs mcp:charge), each counting executions.
func counted(tick, charge *atomic.Int64) func(*Server) {
	return func(s *Server) {
		s.Tool("tick", "counts", func(context.Context, obj) (any, error) { return "tick", inc(tick) })
		s.Tool("charge", "counts, extra scope", func(context.Context, obj) (any, error) { return "charged", inc(charge) })
		if s.Auth != nil {
			s.Auth.Scopes["charge"] = []string{"mcp:charge"}
		}
	}
}

func inc(n *atomic.Int64) error { n.Add(1); return nil }

func TestPauseResumeExecutesOnce(t *testing.T) {
	var tick, charge atomic.Int64
	e := newEnv(t, counted(&tick, &charge))
	c := e.pauseClient()
	_, err := c.CallTool("tick", obj{}, nil)
	p := pending(t, err)
	if tick.Load() != 0 {
		t.Fatalf("paused call must not have executed: %d", tick.Load())
	}
	cb := e.visit(t, p.URL)
	if r, err := c.Auth.Resume(context.Background(), p.ID, cb); err != nil || Text(r) != "tick" {
		t.Fatalf("resume: %v %v", r, err)
	}
	if _, err := c.Auth.Resume(context.Background(), p.ID, cb); err == nil {
		t.Fatal("second resume must fail")
	}
	if tick.Load() != 1 {
		t.Fatalf("executions=%d, want exactly 1", tick.Load())
	}
}

func TestRefreshAfter401ExecutesOnce(t *testing.T) {
	var tick, charge atomic.Int64
	e := newEnv(t, counted(&tick, &charge))
	c := e.autoClient()
	if _, err := c.CallTool("tick", obj{}, nil); err != nil {
		t.Fatal(err)
	}
	tok, _ := c.Auth.store().Load(e.url)
	bad := *tok
	bad.AccessToken = "revoked-by-server" // not expired client-side: the server's 401 invalid_token drives the refresh
	c.Auth.store().Save(e.url, &bad)
	if r, err := c.CallTool("tick", obj{}, nil); err != nil || Text(r) != "tick" {
		t.Fatalf("%v %v", r, err)
	}
	a, rf := e.as.Stats()
	if tick.Load() != 2 || rf != 1 || a != 1 {
		t.Fatalf("executions=%d refreshes=%d authorizations=%d, want 2/1/1", tick.Load(), rf, a)
	}
}

func TestStepUpExecutesOnce(t *testing.T) {
	var tick, charge atomic.Int64
	e := newEnv(t, counted(&tick, &charge))
	c := e.autoClient()
	// 401 (base scope) -> login -> 403 insufficient_scope -> step-up login -> run.
	if r, err := c.CallTool("charge", obj{}, nil); err != nil || Text(r) != "charged" {
		t.Fatalf("%v %v", r, err)
	}
	if a, _ := e.as.Stats(); charge.Load() != 1 || a != 2 {
		t.Fatalf("executions=%d authorizations=%d, want 1/2", charge.Load(), a)
	}
}

// A pause in a later MRTR round resumes THAT round: the user is not re-asked, round 0 is not replayed.
func TestPauseMidMRTRResumesSameRound(t *testing.T) {
	e := newEnv(t, func(s *Server) { delete(s.Auth.Scopes, "greet") })
	c := e.pauseClient()
	p := pending(t, func() error { _, err := c.CallTool("echo", obj{"text": "login"}, nil); return err }())
	if _, err := c.Auth.Resume(context.Background(), p.ID, e.visit(t, p.URL)); err != nil {
		t.Fatal(err)
	}
	asked := 0
	_, err := c.CallTool("greet", obj{}, func(string, obj) (obj, error) {
		asked++
		c.Auth.store().Delete(e.url) // token lost between rounds -> round 1 gets 401
		return obj{"action": "accept", "content": obj{"name": "once"}}, nil
	})
	p = pending(t, err)
	r, err := c.Auth.Resume(context.Background(), p.ID, e.visit(t, p.URL))
	if err != nil || Text(r) != "Hello, once!" || asked != 1 {
		t.Fatalf("result=%v err=%v asked=%d (want the paused round resent with its inputResponses)", r, err, asked)
	}
}

func TestLostOrFailedResponseNotRetried(t *testing.T) {
	for _, mode := range []string{"connection dropped", "502 after execution"} {
		t.Run(mode, func(t *testing.T) {
			var tick, charge atomic.Int64
			s := mustServer()
			counted(&tick, &charge)(s)
			ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				s.ServeHTTP(httptest.NewRecorder(), r) // the tool runs; its response is lost
				if mode == "502 after execution" {
					http.Error(w, "bad gateway", http.StatusBadGateway)
					return
				}
				conn, _, _ := w.(http.Hijacker).Hijack()
				conn.Close()
			}))
			defer ts.Close()
			c := NewClient(ts.URL + "/mcp")
			if _, err := c.CallTool("tick", obj{}, nil); err == nil {
				t.Fatal("want an error")
			}
			time.Sleep(50 * time.Millisecond) // a hidden retry would land here
			if tick.Load() != 1 {
				t.Fatalf("executions=%d, want exactly 1 (no auto-retry of tools/call)", tick.Load())
			}
		})
	}
}

// --- RFC 8707 resource on refresh (python-sdk #2578) ---

func expireToken(t *testing.T, c *Client, url string) {
	tok, _ := c.Auth.store().Load(url)
	x := *tok
	x.Expiry = time.Now().Add(-time.Minute)
	c.Auth.store().Save(url, &x)
}

func TestRefreshResourceConfigurable(t *testing.T) {
	for _, omit := range []bool{false, true} {
		e := newEnv(t)
		e.as.RejectRefreshResource = true // Entra-like
		c := e.autoClient()
		c.Auth.OmitRefreshResource = omit
		if _, err := c.CallTool("echo", obj{"text": "1"}, nil); err != nil {
			t.Fatal(err)
		}
		if got := e.as.LastAuthorizeQuery.Get("resource"); got != e.url {
			t.Fatalf("omit=%v: authorize resource=%q", omit, got)
		}
		if got := e.as.LastTokenForm.Get("resource"); got != e.url {
			t.Fatalf("omit=%v: code exchange resource=%q (must always be sent)", omit, got)
		}
		expireToken(t, c, e.url)
		if r, err := c.CallTool("echo", obj{"text": "2"}, nil); err != nil || Text(r) != "2" {
			t.Fatalf("omit=%v: %v %v", omit, r, err)
		}
		a, rf := e.as.Stats()
		if omit && (rf != 1 || a != 1 || e.as.LastTokenForm.Has("resource")) {
			t.Fatalf("omit: refreshes=%d authorizations=%d form=%v", rf, a, e.as.LastTokenForm)
		}
		if !omit && (rf != 0 || a != 2) { // default sends resource; Entra rejects -> falls back to a new login
			t.Fatalf("default: refreshes=%d authorizations=%d", rf, a)
		}
	}
	e := newEnv(t) // default AS accepts resource on refresh and it is sent
	c := e.autoClient()
	c.CallTool("echo", obj{"text": "1"}, nil)
	expireToken(t, c, e.url)
	c.CallTool("echo", obj{"text": "2"}, nil)
	if f := e.as.LastTokenForm; f.Get("grant_type") != "refresh_token" || f.Get("resource") != e.url {
		t.Fatalf("default refresh form: %v", f)
	}
}

// --- pre-registered client id (+ secret) as the alternative to CIMD; no DCR ---

func TestPreRegisteredConfidentialClient(t *testing.T) {
	for _, method := range []string{"", "client_secret_basic", "client_secret_post"} {
		e := newEnv(t)
		e.as.NoCIMD = true
		e.as.Clients["conf"] = []string{"http://127.0.0.1/callback"}
		e.as.Secrets = map[string]string{"conf": "test-only-secret"}
		c := NewClient(e.url)
		c.HTTP = e.hc
		c.Auth = &OAuth{HTTP: e.hc, ClientID: "conf", ClientSecret: "test-only-secret", TokenAuthMethod: method,
			Authorizer: LoopbackAuthorizer{Open: e.follow}}
		if r, err := c.CallTool("echo", obj{"text": "ok"}, nil); err != nil || Text(r) != "ok" {
			t.Fatalf("%q: %v %v", method, r, err)
		}
		f := e.as.LastTokenForm
		if method == "client_secret_post" && f.Get("client_secret") == "" || method != "client_secret_post" && f.Has("client_secret") {
			t.Fatalf("%q: secret placement wrong", method)
		}
		c.Auth.ClientSecret = "wrong"
		expireToken(t, c, e.url)
		c.Auth.Authorizer = PauseAuthorizer{Redirect: "http://127.0.0.1:9/callback"}
		if _, err := c.CallTool("echo", obj{"text": "x"}, nil); PendingOf(err) == nil {
			t.Fatalf("%q: wrong secret must fail the refresh and fall back to a new authorization, got %v", method, err)
		}
	}
}

func TestNoCIMDAndNoClientIDIsAClearError(t *testing.T) {
	e := newEnv(t)
	e.as.NoCIMD = true
	_, err := e.autoClient().CallTool("echo", obj{"text": "x"}, nil) // has a CIMD URL, AS can't use it
	if err == nil || !strings.Contains(err.Error(), "does not support Client ID Metadata Documents") ||
		!strings.Contains(err.Error(), "register an app with "+e.as.Issuer+" and set OAuth.ClientID") {
		t.Fatalf("got %v", err)
	}
	if a, _ := e.as.Stats(); a != 0 {
		t.Fatal("must not start an authorization")
	}
}
