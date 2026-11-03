// Spike: media generation as one engine with two faces (typed verbs + tools).
// Styles are data (styles/*.json); the engine knows no vendor.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"regexp"
	"strconv"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

type Req struct {
	Method string `json:"method"`
	Path   string `json:"path"`
}
type Poll struct {
	Method     string   `json:"method"`
	Path       string   `json:"path"`
	StatusPath string   `json:"statusPath"`
	Done       []string `json:"done"`
	Failed     []string `json:"failed"`
	ErrorPath  string   `json:"errorPath"`
	IntervalMs int      `json:"intervalMs"`
	TimeoutMs  int      `json:"timeoutMs"`
}
type Verb struct {
	Mode       string            `json:"mode"` // sync | async
	Kind       string            `json:"kind"` // image | video | audio
	Start      Req               `json:"start"`
	Defaults   map[string]any    `json:"defaults"`
	Args       map[string]string `json:"args"` // normalised arg -> provider dot-path
	IDPath     string            `json:"idPath"`
	Poll       *Poll             `json:"poll"`
	ResultPath string            `json:"resultPath"`
}
type Style struct {
	BaseURL string            `json:"baseUrl"`
	Headers map[string]string `json:"headers"`
	Verbs   map[string]Verb   `json:"verbs"`
}

// File is one produced media file. From/CallID let a host place it.
type File struct {
	Type     string `json:"type"`
	MimeType string `json:"mimeType"`
	URL      string `json:"url,omitempty"`
	From     string `json:"from,omitempty"`
}
type Result struct {
	Files     []File `json:"files"`
	JobID     string `json:"jobId,omitempty"`
	Status    string `json:"status"`
	ElapsedMs int64  `json:"elapsedMs"`
}
type Progress struct{ Verb, JobID, Status string }

type Options struct {
	OnProgress func(Progress)
	HTTP       *http.Client
}

type Media struct {
	style Style
	opts  Options
}

func LoadStyle(file string) (Style, error) {
	var s Style
	b, err := os.ReadFile(file)
	if err != nil {
		return s, err
	}
	return s, json.Unmarshal(b, &s)
}

func New(s Style, o Options) *Media {
	if o.HTTP == nil {
		o.HTTP = &http.Client{Timeout: 60 * time.Second}
	}
	return &Media{style: s, opts: o}
}

// ---- typed verbs (the function face) ----

type ImageInput struct {
	Prompt        string
	Width, Height int
	Seed          int
}
type VideoInput struct {
	Image   string // URL of a previous File, or any URL
	Prompt  string
	Seconds int
}
type SpeechInput struct{ Text, Voice string }

func (m *Media) Image(ctx context.Context, in ImageInput) (Result, error) {
	return m.Generate(ctx, "image", compact(map[string]any{"prompt": in.Prompt, "width": in.Width, "height": in.Height, "seed": in.Seed}))
}
func (m *Media) Video(ctx context.Context, in VideoInput) (Result, error) {
	return m.Generate(ctx, "video", compact(map[string]any{"image": in.Image, "prompt": in.Prompt, "seconds": in.Seconds}))
}
func (m *Media) Speech(ctx context.Context, in SpeechInput) (Result, error) {
	return m.Generate(ctx, "speech", compact(map[string]any{"text": in.Text, "voice": in.Voice}))
}

// ---- engine ----

func (m *Media) Generate(ctx context.Context, verb string, args map[string]any) (Result, error) {
	v, ok := m.style.Verbs[verb]
	if !ok {
		return Result{}, fmt.Errorf("style does not support %q", verb)
	}
	t0 := time.Now()
	body := map[string]any{}
	for k, val := range v.Defaults {
		body[k] = val
	}
	for k, val := range args {
		p, ok := v.Args[k]
		if !ok {
			return Result{}, fmt.Errorf("%s: unknown argument %q", verb, k)
		}
		setPath(body, p, val)
	}
	resp, err := m.do(ctx, v.Start.Method, v.Start.Path, body)
	if err != nil {
		return Result{}, err
	}
	res := Result{Status: "completed"}
	if v.Mode == "async" {
		res.JobID = fmt.Sprint(getPath(resp, v.IDPath))
		if res.JobID == "" || res.JobID == "<nil>" {
			return Result{}, fmt.Errorf("%s: no job id at %s", verb, v.IDPath)
		}
		resp, res.Status, err = m.poll(ctx, verb, v, res.JobID)
		if err != nil {
			return res, err
		}
	}
	for _, u := range urls(getPath(resp, v.ResultPath)) {
		res.Files = append(res.Files, File{Type: partType(v.Kind), MimeType: mimeOf(v.Kind, u), URL: u, From: verb})
	}
	if len(res.Files) == 0 {
		return res, fmt.Errorf("%s: no result at %s", verb, v.ResultPath)
	}
	res.ElapsedMs = time.Since(t0).Milliseconds()
	return res, nil
}

func (m *Media) poll(ctx context.Context, verb string, v Verb, id string) (any, string, error) {
	p := v.Poll
	iv, to := orInt(p.IntervalMs, 5000), orInt(p.TimeoutMs, 600000)
	deadline := time.Now().Add(time.Duration(to) * time.Millisecond)
	for {
		select {
		case <-ctx.Done():
			return nil, "canceled", ctx.Err()
		case <-time.After(time.Duration(iv) * time.Millisecond):
		}
		resp, err := m.do(ctx, orStr(p.Method, "GET"), strings.ReplaceAll(p.Path, "{id}", id), nil)
		if err != nil {
			return nil, "", err
		}
		st := fmt.Sprint(getPath(resp, p.StatusPath))
		if m.opts.OnProgress != nil {
			m.opts.OnProgress(Progress{verb, id, st})
		}
		if contains(p.Done, st) {
			return resp, st, nil
		}
		if contains(p.Failed, st) {
			msg := ""
			if p.ErrorPath != "" {
				if e := getPath(resp, p.ErrorPath); e != nil {
					msg = fmt.Sprint(e)
				}
			}
			return nil, st, fmt.Errorf("job %s %s: %s", id, st, msg)
		}
		if time.Now().After(deadline) {
			return nil, st, fmt.Errorf("job %s timed out after %dms", id, to)
		}
	}
}

var envRe = regexp.MustCompile(`\$\{([A-Z0-9_]+)\}`)

func (m *Media) do(ctx context.Context, method, p string, body any) (any, error) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(m.style.BaseURL, "/")+p, rd)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range m.style.Headers { // ${ENV} expanded at call time, never logged
		req.Header.Set(k, envRe.ReplaceAllStringFunc(v, func(s string) string { return os.Getenv(envRe.FindStringSubmatch(s)[1]) }))
	}
	r, err := m.opts.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer r.Body.Close()
	b, _ := io.ReadAll(r.Body)
	if r.StatusCode/100 != 2 {
		return nil, fmt.Errorf("HTTP %d: %s", r.StatusCode, b)
	}
	var out any
	if err := json.Unmarshal(b, &out); err != nil {
		return nil, fmt.Errorf("non-JSON response: %.200s", b)
	}
	if mm, ok := out.(map[string]any); ok { // a2e-style envelope errors arrive as 200 + code!=0
		if c, ok := mm["code"].(float64); ok && c != 0 {
			return nil, fmt.Errorf("API code %v: %v", c, mm["message"])
		}
	}
	return out, nil
}

// ---- tool face: each verb a Tool with a provider-independent name/schema ----

var verbTools = map[string]struct {
	name, desc string
	schema     tn.JSONSchema
}{
	"image":  {"generate_image", "Generate an image from a text prompt. Returns the image.", obj([]string{"prompt"}, map[string]any{"prompt": str(), "width": num(), "height": num(), "seed": num()})},
	"video":  {"generate_video", "Animate an image (URL) into a short video. Takes minutes.", obj([]string{"image", "prompt"}, map[string]any{"image": str(), "prompt": str(), "seconds": num()})},
	"speech": {"generate_speech", "Speak text aloud. Returns audio.", obj([]string{"text"}, map[string]any{"text": str(), "voice": str()})},
}

func (m *Media) Tools() []tn.Tool {
	var out []tn.Tool
	for verb := range m.style.Verbs {
		vt, ok := verbTools[verb]
		if !ok {
			continue
		}
		verb := verb
		out = append(out, tn.Tool{
			Name: vt.name, Description: vt.desc, InputSchema: vt.schema, Source: "media",
			Execute: func(args map[string]any, tc *tn.ToolContext) (tn.ToolResult, error) {
				ctx := context.Background()
				if tc != nil && tc.Ctx != nil {
					ctx = tc.Ctx
				}
				r, err := m.Generate(ctx, verb, args)
				if err != nil {
					return tn.ToolResult{Output: err.Error(), IsError: true}, nil
				}
				var us []string
				var parts []tn.ContentPart
				for _, f := range r.Files {
					us = append(us, f.URL)
					parts = append(parts, tn.ContentPart{Type: f.Type, MimeType: f.MimeType, URL: f.URL})
				}
				return tn.ToolResult{
					Output:   fmt.Sprintf("%s ready: %s", verbKind(verb), strings.Join(us, "\n")),
					Parts:    parts,
					Metadata: map[string]any{"source": "media", "jobId": r.JobID, "status": r.Status, "elapsedMs": r.ElapsedMs},
				}, nil
			},
		})
	}
	return out
}

// ---- helpers ----

func verbKind(v string) string { return map[string]string{"image": "image", "video": "video", "speech": "audio"}[v] }
func partType(kind string) string {
	if kind == "video" {
		return "file"
	}
	return kind
}
func mimeOf(kind, u string) string {
	ext := strings.ToLower(strings.TrimPrefix(path.Ext(strings.SplitN(u, "?", 2)[0]), "."))
	if m, ok := map[string]string{"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp", "gif": "image/gif",
		"mp4": "video/mp4", "webm": "video/webm", "mov": "video/quicktime", "mp3": "audio/mpeg", "wav": "audio/wav", "ogg": "audio/ogg"}[ext]; ok {
		return m
	}
	return map[string]string{"image": "image/png", "video": "video/mp4", "audio": "audio/mpeg"}[kind]
}
func getPath(v any, p string) any {
	if p == "" {
		return v
	}
	for _, k := range strings.Split(p, ".") {
		switch x := v.(type) {
		case map[string]any:
			v = x[k]
		case []any:
			i, err := strconv.Atoi(k)
			if err != nil || i >= len(x) {
				return nil
			}
			v = x[i]
		default:
			return nil
		}
	}
	return v
}

// setPath writes val at a dot-path, creating maps and (for numeric keys) arrays.
func setPath(root map[string]any, p string, val any) {
	keys := strings.Split(p, ".")
	var cur any = root
	for i, k := range keys {
		last := i == len(keys)-1
		nextIsIdx := !last && isIdx(keys[i+1])
		mk := func() any {
			if nextIsIdx {
				return []any{}
			}
			return map[string]any{}
		}
		switch c := cur.(type) {
		case map[string]any:
			if last {
				c[k] = val
				return
			}
			if c[k] == nil {
				c[k] = mk()
			}
			if arr, ok := c[k].([]any); ok && nextIsIdx { // grow then re-store
				j, _ := strconv.Atoi(keys[i+1])
				for len(arr) <= j {
					arr = append(arr, nil)
				}
				c[k] = arr
			}
			cur = c[k]
		case []any:
			j, _ := strconv.Atoi(k)
			if last {
				c[j] = val
				return
			}
			if c[j] == nil {
				c[j] = mk()
			}
			cur = c[j]
		}
	}
}
func isIdx(s string) bool { _, err := strconv.Atoi(s); return err == nil }
func urls(v any) []string {
	switch x := v.(type) {
	case string:
		if x != "" {
			return []string{x}
		}
	case []any:
		var out []string
		for _, e := range x {
			out = append(out, urls(e)...)
		}
		return out
	}
	return nil
}
func compact(m map[string]any) map[string]any {
	for k, v := range m {
		if v == "" || v == 0 {
			delete(m, k)
		}
	}
	return m
}
func contains(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}
func orInt(a, b int) int {
	if a > 0 {
		return a
	}
	return b
}
func orStr(a, b string) string {
	if a != "" {
		return a
	}
	return b
}
func str() map[string]any { return map[string]any{"type": "string"} }
func num() map[string]any { return map[string]any{"type": "integer"} }
func obj(req []string, props map[string]any) tn.JSONSchema {
	return tn.JSONSchema{"type": "object", "required": req, "properties": props}
}
