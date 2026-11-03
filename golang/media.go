// Media generation (SPEC §7G) — image, video, speech, transcription, voice
// cloning and talking-head generation behind one engine with two faces: typed
// verbs a host calls (Media.Image, Media.Video, ...) and tools an LLM calls
// (Media.Tools). Providers are styles — JSON data, never code; the library
// names no vendor. ADR 0036; conformance fixtures in conformance/media/.

package toolnexus

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// SourceMedia marks a tool produced by Media.Tools (§7G).
const SourceMedia ToolSource = "media"

// MediaStyle describes one provider as data (§7G Style).
type MediaStyle struct {
	// BaseURL is joined with each request path; a trailing "/" is trimmed.
	BaseURL string `json:"baseUrl"`
	// Headers are sent on every request. "${VAR}" expands from the process
	// environment at call time (unset ⇒ ""); values are never logged.
	Headers map[string]string `json:"headers,omitempty"`
	// ErrorEnvelope detects errors reported inside a 2xx JSON body.
	ErrorEnvelope *MediaErrorEnvelope `json:"errorEnvelope,omitempty"`
	// Verbs maps a verb (image|video|speech|transcribe|cloneVoice|talking) to its wiring.
	Verbs map[string]MediaVerb `json:"verbs"`
}

// MediaErrorEnvelope: a present value at CodePath that is not JSON-equal to one
// of OkValues is an error `API error <code>: <message at MessagePath>`.
type MediaErrorEnvelope struct {
	CodePath    string `json:"codePath"`
	OkValues    []any  `json:"okValues"`
	MessagePath string `json:"messagePath,omitempty"`
}

// MediaVerb wires one verb to a provider endpoint.
type MediaVerb struct {
	Mode       string            `json:"mode"` // "sync" | "async"
	Kind       string            `json:"kind"` // "image" | "video" | "audio" | "text"
	Start      MediaStart        `json:"start"`
	Defaults   map[string]any    `json:"defaults,omitempty"`
	Args       map[string]string `json:"args"` // normalised arg → provider dotPath
	IDPath     string            `json:"idPath,omitempty"`
	Poll       *MediaPoll        `json:"poll,omitempty"`
	ResultPath string            `json:"resultPath"`
}

// MediaStart is the start request (Method defaults to POST).
type MediaStart struct {
	Method string `json:"method,omitempty"`
	Path   string `json:"path"`
}

// MediaPoll is the async status loop. Path contains "{id}".
type MediaPoll struct {
	Method     string   `json:"method,omitempty"` // default GET
	Path       string   `json:"path"`
	StatusPath string   `json:"statusPath"`
	Done       []string `json:"done"`
	Failed     []string `json:"failed"`
	ErrorPath  string   `json:"errorPath,omitempty"`
	IntervalMs int      `json:"intervalMs,omitempty"` // default 5000
	TimeoutMs  int      `json:"timeoutMs,omitempty"`  // default 600000
}

// MediaFile is one produced file. Type is "image", "audio" or "file" (video).
type MediaFile struct {
	Type     string `json:"type"`
	MimeType string `json:"mimeType"`
	URL      string `json:"url"`
}

// MediaResult is what every verb returns.
type MediaResult struct {
	Files  []MediaFile `json:"files"`
	Text   string      `json:"text,omitempty"`
	JobID  string      `json:"jobId,omitempty"`
	Status string      `json:"status"`
	// hasText marks a text-kind result, so an empty Text ("" — e.g. silence)
	// still serialises as "text":"".
	hasText bool
}

// MarshalJSON emits "text" for every text-kind result, even when it is "".
func (r MediaResult) MarshalJSON() ([]byte, error) {
	type plain MediaResult
	if !r.hasText || r.Text != "" {
		return json.Marshal(plain(r))
	}
	return json.Marshal(struct {
		plain
		Text string `json:"text"`
	}{plain(r), r.Text})
}

// MediaProgress is reported after every poll of an async job.
type MediaProgress struct {
	Verb   string
	JobID  string
	Status string
}

// MediaOptions configures CreateMedia.
type MediaOptions struct {
	// OnProgress is called after each poll of an async job.
	OnProgress func(MediaProgress)
	// HTTPClient overrides the HTTP client (the same seam as ClientOptions.HTTPClient).
	// Nil ⇒ http.DefaultClient.
	HTTPClient *http.Client
}

// Media is the engine. Build it with CreateMedia.
type Media struct {
	styles []MediaStyle
	opts   MediaOptions
	http   *http.Client
}

// LoadMediaStyle reads one style JSON file.
func LoadMediaStyle(path string) (MediaStyle, error) {
	var s MediaStyle
	b, err := os.ReadFile(path)
	if err != nil {
		return s, err
	}
	if err := json.Unmarshal(b, &s); err != nil {
		return s, fmt.Errorf("media style %s: %w", path, err)
	}
	if err := validateMediaStyle(s); err != nil {
		return s, err
	}
	return s, nil
}

// validateMediaStyle rejects an args entry mapped to "" (§7G Empty dotPath).
// Verbs and keys are checked in sorted order so the error is deterministic.
func validateMediaStyle(s MediaStyle) error {
	verbs := make([]string, 0, len(s.Verbs))
	for v := range s.Verbs {
		verbs = append(verbs, v)
	}
	sort.Strings(verbs)
	for _, v := range verbs {
		keys := make([]string, 0, len(s.Verbs[v].Args))
		for k := range s.Verbs[v].Args {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			if s.Verbs[v].Args[k] == "" {
				return fmt.Errorf("style: empty path for argument %q", k)
			}
		}
	}
	return nil
}

// CreateMedia builds a media engine over one or more styles. A verb resolves to
// the FIRST style in the list that declares it.
func CreateMedia(styles []MediaStyle, opts MediaOptions) *Media {
	h := opts.HTTPClient
	if h == nil {
		h = http.DefaultClient
	}
	return &Media{styles: append([]MediaStyle(nil), styles...), opts: opts, http: h}
}

// ---- function face: typed verbs ----
//
// Zero-valued fields are treated as absent (not sent). To send a literal zero,
// use Generate with an explicit args map. ProviderOptions' top-level keys
// overwrite the request body last.

// ImageInput is the input of Media.Image.
type ImageInput struct {
	Prompt          string
	Width           int
	Height          int
	AspectRatio     string
	Seed            int
	ProviderOptions map[string]any
}

// VideoInput is the input of Media.Video.
type VideoInput struct {
	Prompt          string
	Image           string // URL of the starting image
	Seconds         int
	AspectRatio     string
	ProviderOptions map[string]any
}

// SpeechInput is the input of Media.Speech.
type SpeechInput struct {
	Text            string
	Voice           string
	ProviderOptions map[string]any
}

// TranscribeInput is the input of Media.Transcribe.
type TranscribeInput struct {
	Audio           string // URL of the audio
	ProviderOptions map[string]any
}

// CloneVoiceInput is the input of Media.CloneVoice.
type CloneVoiceInput struct {
	Name            string
	Samples         []string // URLs of audio samples
	ProviderOptions map[string]any
}

// TalkingInput is the input of Media.Talking.
type TalkingInput struct {
	Image           string // URL of the face image
	Audio           string // URL of the speech audio
	ProviderOptions map[string]any
}

// Image generates an image from a text prompt.
func (m *Media) Image(ctx context.Context, in ImageInput) (MediaResult, error) {
	return m.Generate(ctx, "image", present(
		"prompt", in.Prompt, "width", in.Width, "height", in.Height,
		"aspectRatio", in.AspectRatio, "seed", in.Seed), in.ProviderOptions)
}

// Video generates a short video, optionally from a starting image URL.
func (m *Media) Video(ctx context.Context, in VideoInput) (MediaResult, error) {
	return m.Generate(ctx, "video", present(
		"prompt", in.Prompt, "image", in.Image, "seconds", in.Seconds,
		"aspectRatio", in.AspectRatio), in.ProviderOptions)
}

// Speech speaks text aloud.
func (m *Media) Speech(ctx context.Context, in SpeechInput) (MediaResult, error) {
	return m.Generate(ctx, "speech", present("text", in.Text, "voice", in.Voice), in.ProviderOptions)
}

// Transcribe turns speech in an audio URL into MediaResult.Text.
func (m *Media) Transcribe(ctx context.Context, in TranscribeInput) (MediaResult, error) {
	return m.Generate(ctx, "transcribe", present("audio", in.Audio), in.ProviderOptions)
}

// CloneVoice creates a cloned voice; its id is returned as MediaResult.Text
// (for a text-kind style).
func (m *Media) CloneVoice(ctx context.Context, in CloneVoiceInput) (MediaResult, error) {
	args := present("name", in.Name)
	if in.Samples != nil {
		s := make([]any, len(in.Samples))
		for i, v := range in.Samples {
			s[i] = v
		}
		args["samples"] = s
	}
	return m.Generate(ctx, "cloneVoice", args, in.ProviderOptions)
}

// Talking makes the person in an image speak the given audio.
func (m *Media) Talking(ctx context.Context, in TalkingInput) (MediaResult, error) {
	return m.Generate(ctx, "talking", present("image", in.Image, "audio", in.Audio), in.ProviderOptions)
}

// present builds an args map from key/value pairs, dropping zero values.
func present(kv ...any) map[string]any {
	out := map[string]any{}
	for i := 0; i+1 < len(kv); i += 2 {
		switch v := kv[i+1].(type) {
		case string:
			if v != "" {
				out[kv[i].(string)] = v
			}
		case int:
			if v != 0 {
				out[kv[i].(string)] = v
			}
		}
	}
	return out
}

func (m *Media) resolve(verb string) (MediaStyle, MediaVerb, bool) {
	for _, s := range m.styles {
		if v, ok := s.Verbs[verb]; ok {
			return s, v, true
		}
	}
	return MediaStyle{}, MediaVerb{}, false
}

// ---- engine (§7G normative order) ----

// Generate runs verb with normalised args (the generic function face).
// providerOptions may be nil; its top-level keys overwrite the body last.
func (m *Media) Generate(ctx context.Context, verb string, args map[string]any, providerOptions map[string]any) (MediaResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	style, v, ok := m.resolve(verb)
	if !ok {
		return MediaResult{}, fmt.Errorf("media: no style supports %q", verb)
	}
	if err := validateMediaStyle(style); err != nil {
		return MediaResult{}, err
	}
	keys := make([]string, 0, len(args))
	for k := range args {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if args[k] == nil {
			continue // a null arg is absent
		}
		if _, ok := v.Args[k]; !ok {
			return MediaResult{}, fmt.Errorf("%s: argument %q not supported by this style", verb, k)
		}
	}

	body := map[string]any{}
	if v.Defaults != nil {
		body = deepCopyJSON(v.Defaults).(map[string]any)
	}
	for _, k := range keys {
		if args[k] == nil {
			continue
		}
		dotSet(body, v.Args[k], args[k])
	}
	for k, val := range providerOptions {
		body[k] = val
	}

	method := v.Start.Method
	if method == "" {
		method = http.MethodPost
	}
	// Every request is bounded by the time left until the job deadline (§7G
	// Per-request timeout); the deadline counts from the start request.
	timeout := 600000
	if v.Poll != nil && v.Poll.TimeoutMs > 0 {
		timeout = v.Poll.TimeoutMs
	}
	deadline := time.Now().Add(time.Duration(timeout) * time.Millisecond)
	resp, err := m.do(ctx, deadline, style, verb, method, v.Start.Path, body)
	if err == errMediaDeadline {
		return MediaResult{}, fmt.Errorf("%s: timed out after %dms", verb, timeout)
	}
	if err != nil {
		return MediaResult{}, err
	}
	res := MediaResult{Files: []MediaFile{}, Status: "completed"}
	if v.Mode == "async" {
		id, ok := jobIDText(dotGet(resp, v.IDPath))
		if !ok {
			return MediaResult{}, fmt.Errorf("%s: no job id at %s", verb, v.IDPath)
		}
		res.JobID = id
		resp, res.Status, err = m.poll(ctx, deadline, style, verb, v, id)
		if err != nil {
			return MediaResult{}, err
		}
	}

	found := dotGet(resp, v.ResultPath)
	if v.Kind == "text" {
		t, ok := scalarText(found)
		if s, isStr := found.(string); isStr {
			t, ok = s, true
		}
		if !ok {
			return MediaResult{}, fmt.Errorf("%s: no result at %s", verb, v.ResultPath)
		}
		res.Text, res.hasText = t, true
		return res, nil
	}
	for _, u := range flattenURLs(found) {
		res.Files = append(res.Files, MediaFile{Type: mediaPartType(v.Kind), MimeType: mediaMime(v.Kind, u), URL: u})
	}
	if len(res.Files) == 0 {
		return MediaResult{}, fmt.Errorf("%s: no result at %s", verb, v.ResultPath)
	}
	return res, nil
}

func (m *Media) poll(ctx context.Context, deadline time.Time, style MediaStyle, verb string, v MediaVerb, id string) (any, string, error) {
	p := v.Poll
	if p == nil {
		return nil, "", fmt.Errorf("%s: async verb has no poll", verb)
	}
	interval, timeout := p.IntervalMs, p.TimeoutMs
	if interval <= 0 {
		interval = 5000
	}
	if timeout <= 0 {
		timeout = 600000
	}
	method := p.Method
	if method == "" {
		method = http.MethodGet
	}
	path := strings.ReplaceAll(p.Path, "{id}", percentEncode(id))
	timedOut := fmt.Errorf("job %s timed out after %dms", id, timeout)
	timer := time.NewTimer(time.Duration(interval) * time.Millisecond)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil, "", ctx.Err()
		case <-timer.C:
		}
		resp, err := m.do(ctx, deadline, style, verb, method, path, nil)
		if err == errMediaDeadline {
			return nil, "", timedOut
		}
		if err != nil {
			return nil, "", err
		}
		status, _ := scalarText(dotGet(resp, p.StatusPath))
		if m.opts.OnProgress != nil {
			m.opts.OnProgress(MediaProgress{Verb: verb, JobID: id, Status: status})
		}
		if mediaHas(p.Done, status) {
			return resp, status, nil
		}
		if mediaHas(p.Failed, status) {
			msg := ""
			if p.ErrorPath != "" {
				msg, _ = scalarText(dotGet(resp, p.ErrorPath))
			}
			if msg == "" {
				return nil, status, fmt.Errorf("job %s %s", id, status)
			}
			return nil, status, fmt.Errorf("job %s %s: %s", id, status, msg)
		}
		if !time.Now().Before(deadline) {
			return nil, status, timedOut
		}
		timer.Reset(time.Duration(interval) * time.Millisecond)
	}
}

// errMediaDeadline marks a request cut short by the job deadline; callers turn
// it into the §7G timed-out message.
var errMediaDeadline = fmt.Errorf("media deadline")

var mediaEnvRe = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}`)

// do sends one request and applies the §7G step-5 response checks.
func (m *Media) do(parent context.Context, deadline time.Time, style MediaStyle, verb, method, path string, body map[string]any) (any, error) {
	ctx, cancel := context.WithDeadline(parent, deadline)
	defer cancel()
	failed := func(err error) error {
		if parent.Err() != nil {
			return parent.Err() // cancellation stays the native error
		}
		if ctx.Err() != nil {
			return errMediaDeadline
		}
		return fmt.Errorf("%s: %w", verb, err)
	}
	var payload []byte
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", verb, err)
		}
		payload = b
	}
	r, err := m.send(ctx, style, verb, method, strings.TrimRight(style.BaseURL, "/")+path, payload)
	if err != nil {
		if errors.Is(err, errTooManyRedirects) {
			return nil, fmt.Errorf("%s: too many redirects", verb)
		}
		return nil, failed(err)
	}
	defer r.Body.Close()
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, failed(err)
	}
	if r.StatusCode < 200 || r.StatusCode > 299 {
		return nil, fmt.Errorf("HTTP %d: %s", r.StatusCode, capMediaBody(raw))
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var out any
	if err := dec.Decode(&out); err != nil || dec.More() {
		return nil, fmt.Errorf("%s: response is not JSON", verb)
	}
	if env := style.ErrorEnvelope; env != nil {
		code := dotGet(out, env.CodePath)
		if code != nil && !jsonEqualAny(code, env.OkValues) {
			msg := ""
			if env.MessagePath != "" {
				if s, ok := dotGet(out, env.MessagePath).(string); ok {
					msg = s
				}
			}
			ct, ok := scalarText(code)
			if str, isStr := code.(string); isStr {
				ct, ok = str, true
			}
			if !ok {
				b, _ := json.Marshal(code)
				ct = string(b)
			}
			return nil, fmt.Errorf("API error %s: %s", ct, msg)
		}
	}
	return out, nil
}

var errTooManyRedirects = errors.New("too many redirects")

// send issues the request and follows redirects itself with the §7G Redirects
// rule set: 301/302/303/307/308 with a Location are followed (at most 10);
// 301/302/303 after a non-GET/HEAD become a bodiless GET; style headers are
// only sent to the origin of the baseUrl request. A host-supplied
// CheckRedirect still runs before each hop.
func (m *Media) send(ctx context.Context, style MediaStyle, verb, method, target string, payload []byte) (*http.Response, error) {
	c := *m.http
	inner := m.http.CheckRedirect
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	var first *url.URL
	var via []*http.Request
	var prev *http.Response // the redirect response being followed
	for {
		var rd io.Reader
		if payload != nil {
			rd = bytes.NewReader(payload)
		}
		req, err := http.NewRequestWithContext(ctx, method, target, rd)
		if err != nil {
			return nil, err
		}
		if first == nil {
			first = req.URL
		}
		if payload != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if origin(req.URL) == origin(first) {
			for k, val := range style.Headers {
				req.Header.Set(k, mediaEnvRe.ReplaceAllStringFunc(val, func(s string) string {
					return os.Getenv(mediaEnvRe.FindStringSubmatch(s)[1])
				}))
			}
		}
		if len(via) > 0 && inner != nil {
			if err := inner(req, via); err != nil {
				if err == http.ErrUseLastResponse {
					return prev, nil
				}
				prev.Body.Close()
				return nil, err
			}
		}
		if prev != nil {
			io.Copy(io.Discard, io.LimitReader(prev.Body, 1<<16))
			prev.Body.Close()
		}
		r, err := c.Do(req)
		if err != nil {
			return nil, err
		}
		loc := r.Header.Get("Location")
		switch r.StatusCode {
		case 301, 302, 303, 307, 308:
		default:
			return r, nil
		}
		if loc == "" {
			return r, nil
		}
		if len(via) >= 10 {
			r.Body.Close()
			return nil, errTooManyRedirects
		}
		next, err := req.URL.Parse(loc)
		if err != nil {
			r.Body.Close()
			return nil, err
		}
		if r.StatusCode <= 303 && method != http.MethodGet && method != http.MethodHead {
			method, payload = http.MethodGet, nil
		}
		prev = r
		via = append(via, req)
		target = next.String()
	}
}

func origin(u *url.URL) string {
	port := u.Port()
	if port == "" {
		switch strings.ToLower(u.Scheme) {
		case "https":
			port = "443"
		case "http":
			port = "80"
		}
	}
	return strings.ToLower(u.Scheme) + "://" + strings.ToLower(u.Hostname()) + ":" + port
}

// capMediaBody keeps the first 1000 code points of an echoed error body, appending
// "…" when it truncates (§7G Echoed error bodies).
func capMediaBody(raw []byte) string {
	s := string(raw)
	if utf8.RuneCountInString(s) <= 1000 {
		return s
	}
	n := 0
	for i := range s {
		if n == 1000 {
			return s[:i] + "…"
		}
		n++
	}
	return s
}

// percentEncode encodes everything but RFC 3986 unreserved characters as %XX
// (uppercase) of its UTF-8 bytes (§7G Job id in the poll path).
func percentEncode(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~' {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

// ---- tool face ----

type mediaArgSpec struct {
	name, typ, items, desc string
	required               bool
}

type mediaVerbSpec struct {
	verb, tool, desc string
	args             []mediaArgSpec
}

// mediaVerbs is conformance/media/verbs.json, verbatim (asserted by a test).
var mediaVerbs = []mediaVerbSpec{
	{"image", "generate_image", "Generate an image from a text prompt.", []mediaArgSpec{
		{"prompt", "string", "", "What to draw.", true},
		{"width", "integer", "", "Width in pixels.", false},
		{"height", "integer", "", "Height in pixels.", false},
		{"aspectRatio", "string", "", "Aspect ratio, e.g. 16:9.", false},
		{"seed", "integer", "", "Seed for reproducible output.", false},
	}},
	{"video", "generate_video", "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes.", []mediaArgSpec{
		{"prompt", "string", "", "What should happen in the video.", true},
		{"image", "string", "", "URL of the starting image.", false},
		{"seconds", "integer", "", "Video length in seconds.", false},
		{"aspectRatio", "string", "", "Aspect ratio, e.g. 9:16.", false},
	}},
	{"speech", "generate_speech", "Speak text aloud and return the audio.", []mediaArgSpec{
		{"text", "string", "", "The text to speak.", true},
		{"voice", "string", "", "Voice or speaker id.", false},
	}},
	{"transcribe", "transcribe_audio", "Transcribe speech in an audio URL to text.", []mediaArgSpec{
		{"audio", "string", "", "URL of the audio.", true},
	}},
	{"cloneVoice", "clone_voice", "Create a cloned voice from audio sample URLs and return its voice id.", []mediaArgSpec{
		{"name", "string", "", "Name for the new voice.", true},
		{"samples", "array", "string", "URLs of audio samples of the voice.", true},
	}},
	{"talking", "talking_video", "Make the person in an image speak the given audio (lip-synced video).", []mediaArgSpec{
		{"image", "string", "", "URL of the face image.", true},
		{"audio", "string", "", "URL of the speech audio.", true},
	}},
}

// Tools returns one Tool (source "media") per supported verb, in canonical
// verb order. Each schema lists only the args the resolving style maps.
// Execute never returns an error: an engine failure is ToolResult{IsError:true}.
func (m *Media) Tools() []Tool {
	out := []Tool{}
	for _, spec := range mediaVerbs {
		_, v, ok := m.resolve(spec.verb)
		if !ok {
			continue
		}
		props := map[string]any{}
		required := []string{}
		for _, a := range spec.args {
			if _, mapped := v.Args[a.name]; !mapped {
				continue
			}
			p := map[string]any{"type": a.typ, "description": a.desc}
			if a.typ == "array" {
				p["items"] = map[string]any{"type": a.items}
			}
			props[a.name] = p
			if a.required {
				required = append(required, a.name)
			}
		}
		verb := spec.verb
		kind := v.Kind
		out = append(out, Tool{
			Name:        spec.tool,
			Description: spec.desc,
			InputSchema: JSONSchema{"type": "object", "properties": props, "required": required},
			Source:      SourceMedia,
			// Each call is an independent remote job: always concurrency-safe (§7G, §8 Tool scheduling).
			ConcurrencySafe: func(map[string]any) bool { return true },
			Execute: func(args map[string]any, tc *ToolContext) (ToolResult, error) {
				ctx := context.Background()
				if tc != nil && tc.Ctx != nil {
					ctx = tc.Ctx
				}
				return m.toolResult(ctx, verb, kind, args), nil
			},
		})
	}
	return out
}

func (m *Media) toolResult(ctx context.Context, verb, kind string, args map[string]any) ToolResult {
	r, err := m.Generate(ctx, verb, args, nil)
	if err != nil {
		return ToolResult{IsError: true, Output: err.Error()}
	}
	meta := map[string]any{"source": "media", "verb": verb, "status": r.Status}
	if r.JobID != "" {
		meta["jobId"] = r.JobID
	}
	if kind == "text" {
		return ToolResult{Output: r.Text, Metadata: meta}
	}
	urls := make([]string, len(r.Files))
	parts := make([]ContentPart, len(r.Files))
	for i, f := range r.Files {
		urls[i] = f.URL
		parts[i] = ContentPart{Type: f.Type, MimeType: f.MimeType, URL: f.URL}
	}
	word := kind
	if kind != "image" && kind != "video" {
		word = "audio"
	}
	return ToolResult{Output: word + " ready: " + strings.Join(urls, "\n"), Parts: parts, Metadata: meta}
}

// ---- helpers ----

func mediaPartType(kind string) string {
	switch kind {
	case "image", "audio":
		return kind
	}
	return "file"
}

var mediaMimes = map[string]string{
	"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp", "gif": "image/gif",
	"mp4": "video/mp4", "webm": "video/webm", "mov": "video/quicktime",
	"mp3": "audio/mpeg", "wav": "audio/wav", "ogg": "audio/ogg", "m4a": "audio/mp4", "flac": "audio/flac",
}

func mediaMime(kind, u string) string {
	if i := strings.IndexAny(u, "?#"); i >= 0 {
		u = u[:i]
	}
	if i := strings.Index(u, "://"); i >= 0 { // only the path's extension counts
		rest := u[i+3:]
		if j := strings.Index(rest, "/"); j >= 0 {
			u = rest[j:]
		} else {
			u = ""
		}
	}
	last := u[strings.LastIndex(u, "/")+1:]
	if dot := strings.LastIndex(last, "."); dot >= 0 {
		if mt, ok := mediaMimes[strings.ToLower(last[dot+1:])]; ok {
			return mt
		}
	}
	switch kind {
	case "image":
		return "image/png"
	case "audio":
		return "audio/mpeg"
	}
	return "video/mp4"
}

// scalarText renders a string as-is and a number (integral ⇒ integer text).
// Anything else (absent, null, objects, bools) ⇒ ok=false. Empty string ⇒ ok=false.
func scalarText(v any) (string, bool) {
	switch x := v.(type) {
	case string:
		return x, x != ""
	case json.Number:
		return numberText(string(x)), true
	case float64:
		return numberText(strconv.FormatFloat(x, 'f', -1, 64)), true
	case int:
		return strconv.Itoa(x), true
	case int64:
		return strconv.FormatInt(x, 10), true
	}
	return "", false
}

// jobIDText: only a non-empty string or an integral number is a job id.
func jobIDText(v any) (string, bool) {
	switch n := v.(type) {
	case json.Number:
		f, err := strconv.ParseFloat(string(n), 64)
		if err != nil || f != math.Trunc(f) {
			return "", false
		}
	case float64:
		if n != math.Trunc(n) {
			return "", false
		}
	}
	return scalarText(v)
}

func numberText(s string) string {
	if _, err := strconv.ParseInt(s, 10, 64); err == nil {
		return s
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return s
	}
	if f == math.Trunc(f) && math.Abs(f) < 1e18 {
		return strconv.FormatInt(int64(f), 10)
	}
	return strconv.FormatFloat(f, 'g', -1, 64)
}

func flattenURLs(v any) []string {
	switch x := v.(type) {
	case string:
		if x != "" {
			return []string{x}
		}
	case []any:
		var out []string
		for _, e := range x {
			out = append(out, flattenURLs(e)...)
		}
		return out
	}
	return nil
}

// dotGet reads a dotPath; a missing segment ⇒ nil. An empty path is the root.
func dotGet(v any, path string) any {
	if path == "" {
		return v
	}
	for _, k := range strings.Split(path, ".") {
		switch x := v.(type) {
		case map[string]any:
			v = x[k]
		case []any:
			if !isDigits(k) {
				return nil
			}
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

// dotSet writes val at a dotPath, creating objects — or arrays when the next
// segment is all digits (padding with null).
func dotSet(root map[string]any, path string, val any) {
	keys := strings.Split(path, ".")
	var cur any = root
	for i, k := range keys {
		last := i == len(keys)-1
		var next func() any
		if !last && isDigits(keys[i+1]) {
			next = func() any { return []any{} }
		} else {
			next = func() any { return map[string]any{} }
		}
		switch c := cur.(type) {
		case map[string]any:
			if last {
				c[k] = val
				return
			}
			child := c[k]
			if !fitsNext(child, keys[i+1]) {
				child = next()
			}
			child = growFor(child, keys[i+1])
			c[k] = child
			cur = child
		case []any:
			j, _ := strconv.Atoi(k)
			if last {
				c[j] = val
				return
			}
			child := c[j]
			if !fitsNext(child, keys[i+1]) {
				child = next()
			}
			child = growFor(child, keys[i+1])
			c[j] = child
			cur = child
		default:
			return
		}
	}
}

// growFor pads a slice so index seg exists; maps pass through.
func growFor(child any, seg string) any {
	arr, ok := child.([]any)
	if !ok || !isDigits(seg) {
		return child
	}
	j, _ := strconv.Atoi(seg)
	for len(arr) <= j {
		arr = append(arr, nil)
	}
	return arr
}

// fitsNext reports whether child can take segment seg: an object takes any
// segment, an array only a numeric one (else it is replaced, §7G).
func fitsNext(child any, seg string) bool {
	switch child.(type) {
	case map[string]any:
		return true
	case []any:
		return isDigits(seg)
	}
	return false
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func deepCopyJSON(v any) any {
	switch x := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(x))
		for k, e := range x {
			out[k] = deepCopyJSON(e)
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = deepCopyJSON(e)
		}
		return out
	}
	return v
}

// jsonEqualAny reports whether v is JSON-equal to any of vals (numbers compare
// by value, so 0 == 0.0).
func jsonEqualAny(v any, vals []any) bool {
	nv := normJSON(v)
	for _, o := range vals {
		if reflect.DeepEqual(nv, normJSON(o)) {
			return true
		}
	}
	return false
}

func normJSON(v any) any {
	b, err := json.Marshal(v)
	if err != nil {
		return v
	}
	var out any
	_ = json.Unmarshal(b, &out)
	return out
}

func mediaHas(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}
