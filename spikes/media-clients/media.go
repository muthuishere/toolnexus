// Spike for ADR 0037: one MediaClient interface, named API and CLI clients, seven verbs.
package main

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
)

// ---- verbs, args, results --------------------------------------------------

type Verb string

const (
	VImage      Verb = "image"
	VVideo      Verb = "video"
	VSpeech     Verb = "speech"
	VMusic      Verb = "music"
	VTranscribe Verb = "transcribe"
	VCloneVoice Verb = "cloneVoice"
	VTalk       Verb = "talk"
)

var AllVerbs = []Verb{VImage, VVideo, VSpeech, VMusic, VTranscribe, VCloneVoice, VTalk}

// Args is the normalised argument bag shared by every verb (the tool schema).
type Args struct {
	Prompt   string // image/video/music prompt
	Text     string // speech / talk text
	Voice    string // name or id
	Image    string // reference image URL/path (video i2v, talk face)
	Audio    string // audio URL/path (transcribe, cloneVoice, talk)
	Name     string // cloneVoice name
	Seconds  int
	Size     string
	Language string
}

type File struct {
	Type     string `json:"type"` // image|video|audio
	MimeType string `json:"mimeType"`
	URL      string `json:"url,omitempty"`  // remote URL or file:// path
	Data     []byte `json:"-"`              // inline bytes (gather=inline)
	Path     string `json:"path,omitempty"` // local path after download
}

type MediaResult struct {
	Files  []File
	Text   string // transcript / voice id
	JobID  string
	Status string // completed|failed|timeout|cancelled
}

// Progress is what ctx carries alongside deadline/cancel (ADR 0037 §2).
type Progress func(status string, elapsed time.Duration)

type progressKey struct{}

func WithProgress(ctx context.Context, p Progress) context.Context {
	return context.WithValue(ctx, progressKey{}, p)
}
func report(ctx context.Context, status string, start time.Time) {
	if p, _ := ctx.Value(progressKey{}).(Progress); p != nil {
		p(status, time.Since(start))
	}
}

var ErrUnsupported = errors.New("verb not supported by this client")

// ErrUnavailable marks account preconditions (no credits, quota, privacy/ZDR gate):
// the call was valid but this client cannot serve it now. createMedia falls through on it.
var ErrUnavailable = errors.New("unavailable")

func unavailable(msg string) error { return fmt.Errorf("%w: %s", ErrUnavailable, msg) }

// ---- the interface -----------------------------------------------------------

type MediaClient interface {
	Name() string
	Supports(Verb) bool
	Image(ctx context.Context, a Args) (MediaResult, error)
	Video(ctx context.Context, a Args) (MediaResult, error)
	Speech(ctx context.Context, a Args) (MediaResult, error)
	Music(ctx context.Context, a Args) (MediaResult, error)
	Transcribe(ctx context.Context, a Args) (MediaResult, error)
	CloneVoice(ctx context.Context, a Args) (MediaResult, error)
	Talk(ctx context.Context, a Args) (MediaResult, error)
}

// Unsupported is the embeddable base: every verb errors, Supports is driven by a set.
type Unsupported struct{ verbs map[Verb]bool }

func supportsOf(v ...Verb) Unsupported {
	m := map[Verb]bool{}
	for _, x := range v {
		m[x] = true
	}
	return Unsupported{m}
}
func (u Unsupported) Supports(v Verb) bool { return u.verbs[v] }
func (Unsupported) Image(context.Context, Args) (MediaResult, error) {
	return MediaResult{}, ErrUnsupported
}
func (Unsupported) Video(context.Context, Args) (MediaResult, error) {
	return MediaResult{}, ErrUnsupported
}
func (Unsupported) Speech(context.Context, Args) (MediaResult, error) {
	return MediaResult{}, ErrUnsupported
}
func (Unsupported) Music(context.Context, Args) (MediaResult, error) {
	return MediaResult{}, ErrUnsupported
}
func (Unsupported) Transcribe(context.Context, Args) (MediaResult, error) {
	return MediaResult{}, ErrUnsupported
}
func (Unsupported) CloneVoice(context.Context, Args) (MediaResult, error) {
	return MediaResult{}, ErrUnsupported
}
func (Unsupported) Talk(context.Context, Args) (MediaResult, error) {
	return MediaResult{}, ErrUnsupported
}

// ---- shared config -------------------------------------------------------------

type Loop struct {
	IntervalMs int
	TimeoutMs  int
	OnProgress Progress
}

type Gather struct {
	Mode string // url|download|inline
	Dir  string
}

type Config struct {
	Model   string
	Key     string
	URL     string
	Command []string
	Loop    Loop
	Gather  Gather
	HTTP    *http.Client
}

func (c Config) withDefaults(envKey, baseURL, model string, gatherMode string) Config {
	if c.Key == "" && envKey != "" {
		c.Key = os.Getenv(envKey)
	}
	if c.URL == "" {
		c.URL = baseURL
	}
	if c.Model == "" {
		c.Model = model
	}
	if c.Loop.IntervalMs == 0 {
		c.Loop.IntervalMs = 2000
	}
	if c.Loop.TimeoutMs == 0 {
		c.Loop.TimeoutMs = 10 * 60 * 1000
	}
	if c.Gather.Mode == "" {
		c.Gather.Mode = gatherMode
	}
	if c.Gather.Dir == "" {
		c.Gather.Dir = "out"
	}
	if c.HTTP == nil {
		c.HTTP = &http.Client{Timeout: 5 * time.Minute}
	}
	return c
}

// withLoop applies loop.timeout as a deadline and loop.onProgress unless ctx already has them.
func (c Config) withLoop(ctx context.Context) (context.Context, context.CancelFunc) {
	if c.Loop.OnProgress != nil {
		if _, ok := ctx.Value(progressKey{}).(Progress); !ok {
			ctx = WithProgress(ctx, c.Loop.OnProgress)
		}
	}
	if _, ok := ctx.Deadline(); !ok && c.Loop.TimeoutMs > 0 {
		return context.WithTimeout(ctx, time.Duration(c.Loop.TimeoutMs)*time.Millisecond)
	}
	return context.WithCancel(ctx)
}

// ---- gather: turn whatever the provider gave back into File per gather.mode -----

var seq atomic.Int64

func extFor(mt string) string {
	if exts, _ := mime.ExtensionsByType(mt); len(exts) > 0 {
		for _, e := range exts { // prefer the common ones
			if e == ".jpg" || e == ".png" || e == ".mp3" || e == ".mp4" || e == ".webp" || e == ".wav" {
				return e
			}
		}
		return exts[0]
	}
	return ".bin"
}

func mimeFor(name, fallback string) string {
	if mt := mime.TypeByExtension(strings.ToLower(path.Ext(strings.SplitN(name, "?", 2)[0]))); mt != "" {
		return strings.SplitN(mt, ";", 2)[0]
	}
	return fallback
}

func kindOf(mt string) string { return strings.SplitN(mt, "/", 2)[0] }

// fromBytes: provider returned bytes (ElevenLabs body, OpenAI b64, OpenRouter data URL).
func (c Config) fromBytes(prefix string, data []byte, mt string) (File, error) {
	f := File{Type: kindOf(mt), MimeType: mt}
	if c.Gather.Mode == "inline" {
		f.Data = data
		return f, nil
	}
	// url mode has no URL to give for bytes: fall back to download (finding).
	if err := os.MkdirAll(c.Gather.Dir, 0o755); err != nil {
		return f, err
	}
	p := filepath.Join(c.Gather.Dir, fmt.Sprintf("%s-%d-%d%s", prefix, time.Now().Unix(), seq.Add(1), extFor(mt)))
	if err := os.WriteFile(p, data, 0o644); err != nil {
		return f, err
	}
	abs, _ := filepath.Abs(p)
	f.Path, f.URL = abs, "file://"+abs
	return f, nil
}

// fromURL: provider returned a URL (fal, A2E). download/inline fetch it.
func (c Config) fromURL(ctx context.Context, prefix, u, fallbackMime string) (File, error) {
	mt := mimeFor(u, fallbackMime)
	if c.Gather.Mode == "url" {
		return File{Type: kindOf(mt), MimeType: mt, URL: u}, nil
	}
	req, _ := http.NewRequestWithContext(ctx, "GET", u, nil)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return File{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return File{}, fmt.Errorf("download %s: HTTP %d", prefix, resp.StatusCode)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return File{}, err
	}
	if ct := resp.Header.Get("Content-Type"); ct != "" && !strings.HasPrefix(ct, "application/octet") {
		mt = strings.SplitN(ct, ";", 2)[0]
	}
	f, err := c.fromBytes(prefix, data, mt)
	if err == nil && f.URL == "" {
		f.URL = u
	}
	return f, err
}

// fromLocal: a CLI agent left a file in its own storage. url = file:// to it; download = copy out.
func (c Config) fromLocal(prefix, p string) (File, error) {
	mt := mimeFor(p, "application/octet-stream")
	if c.Gather.Mode == "url" {
		return File{Type: kindOf(mt), MimeType: mt, URL: "file://" + p, Path: p}, nil
	}
	data, err := os.ReadFile(p)
	if err != nil {
		return File{}, err
	}
	return c.fromBytes(prefix, data, mt)
}

func decodeDataURL(s string) ([]byte, string, error) {
	// data:image/png;base64,....
	if !strings.HasPrefix(s, "data:") {
		return nil, "", fmt.Errorf("not a data URL")
	}
	head, body, ok := strings.Cut(s[5:], ",")
	if !ok {
		return nil, "", fmt.Errorf("bad data URL")
	}
	mt := strings.SplitN(head, ";", 2)[0]
	b, err := base64.StdEncoding.DecodeString(body)
	return b, mt, err
}

// ---- createMedia: route each verb to the first supporting client ------------------

type Media struct{ clients []MediaClient }

func CreateMedia(clients ...MediaClient) *Media { return &Media{clients} }

func (m *Media) pick(v Verb) (MediaClient, error) {
	for _, c := range m.clients {
		if c.Supports(v) {
			return c, nil
		}
	}
	return nil, fmt.Errorf("no client supports %s", v)
}

func (m *Media) Supports(v Verb) bool { _, err := m.pick(v); return err == nil }

// Run tries each supporting client in order; it moves on only when a client is unavailable.
func (m *Media) Run(ctx context.Context, v Verb, a Args) (MediaResult, error) {
	var last error = fmt.Errorf("no client supports %s", v)
	var lastRes MediaResult
	for _, c := range m.clients {
		if !c.Supports(v) {
			continue
		}
		res, err := call(ctx, c, v, a)
		if err == nil || !errors.Is(err, ErrUnavailable) {
			return res, err
		}
		lastRes, last = res, fmt.Errorf("%s: %w", c.Name(), err)
	}
	lastRes.Status = "unavailable"
	return lastRes, last
}

func call(ctx context.Context, c MediaClient, v Verb, a Args) (MediaResult, error) {
	switch v {
	case VImage:
		return c.Image(ctx, a)
	case VVideo:
		return c.Video(ctx, a)
	case VSpeech:
		return c.Speech(ctx, a)
	case VMusic:
		return c.Music(ctx, a)
	case VTranscribe:
		return c.Transcribe(ctx, a)
	case VCloneVoice:
		return c.CloneVoice(ctx, a)
	case VTalk:
		return c.Talk(ctx, a)
	}
	return MediaResult{}, ErrUnsupported
}
