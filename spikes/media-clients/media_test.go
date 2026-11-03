package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

type fake struct {
	Unsupported
	name string
	err  error
}

func (f fake) Name() string { return f.name }
func (f fake) Image(context.Context, Args) (MediaResult, error) {
	if f.err != nil {
		return MediaResult{Status: "failed"}, f.err
	}
	return MediaResult{Status: "completed", Text: f.name}, nil
}

func TestFallthroughOnUnavailableOnly(t *testing.T) {
	a := fake{supportsOf(VImage), "a", unavailable("no credits")}
	b := fake{supportsOf(VImage), "b", nil}
	r, err := CreateMedia(a, b).Run(context.Background(), VImage, Args{})
	if err != nil || r.Text != "b" {
		t.Fatalf("want b, got %+v %v", r, err)
	}
	c := fake{supportsOf(VImage), "c", errors.New("bad prompt")}
	if _, err := CreateMedia(c, b).Run(context.Background(), VImage, Args{}); err == nil {
		t.Fatal("a plain failure must not fall through")
	}
	r, err = CreateMedia(a).Run(context.Background(), VImage, Args{})
	if !errors.Is(err, ErrUnavailable) || r.Status != "unavailable" {
		t.Fatalf("all unavailable: %+v %v", r, err)
	}
}

func TestHTTP402IsUnavailable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(402)
		w.Write([]byte(`{"error":{"message":"requires at least $1.00"}}`))
	}))
	defer srv.Close()
	_, err := NewOpenRouter(Config{Key: "YOUR_KEY_HERE", URL: srv.URL}).Image(context.Background(), Args{Prompt: "x"})
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("want unavailable, got %v", err)
	}
}

// Replays the live headless capture (out/, gitignored) through the Grok parser: no new call.
func TestGrokParserOnCapture(t *testing.T) {
	data, err := os.ReadFile("out/captures/grok-headless-video.jsonl")
	if err != nil {
		t.Skip("no capture")
	}
	g := NewGrok(Config{})
	var paths []string
	var lastErr, session string
	for _, ln := range strings.Split(string(data), "\n") {
		var l map[string]any
		if json.Unmarshal([]byte(ln), &l) != nil {
			continue
		}
		e := g.Parse(l)
		paths = append(paths, e.Paths...)
		if e.Err != "" {
			lastErr = e.Err
		}
		if e.Session != "" {
			session = e.Session
		}
	}
	if len(paths) != 1 || !strings.HasSuffix(paths[0], "/images/1.jpg") || session == "" {
		t.Fatalf("paths=%v session=%q", paths, session)
	}
	if !g.Unavailable(lastErr) {
		t.Fatalf("ZDR error not classified unavailable: %q", lastErr)
	}
}
