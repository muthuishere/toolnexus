// Style clients (SPEC §7G.7): the JSON style engine of ADR 0036, now one kind of
// MediaClient. LoadMediaStyle reads a style file; NewStyleMediaClient makes a
// client from it. The built-in A2EMediaClient is a style client over the
// embedded a2e preset.

package toolnexus

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// MediaStyle describes one provider as data (§7G.7 Style).
type MediaStyle struct {
	// Label is the client name in messages (JSON "name"); default "style".
	Label string `json:"name,omitempty"`
	// CLI is set only on a built-in CLI preset (MediaStyleXaiGrokCli,
	// MediaStyleOpenAICodexCli): the CLI client that serves the style.
	CLI string `json:"-"`
	cfg MediaConfig
	// BaseURL is joined with each request path; a trailing "/" is trimmed.
	BaseURL string `json:"baseUrl"`
	// Headers are sent on every request. "${VAR}" expands from the process
	// environment at call time (unset ⇒ ""), or to the config Key when set.
	// Values are never logged.
	Headers map[string]string `json:"headers,omitempty"`
	// ErrorEnvelope detects errors reported inside a 2xx JSON body.
	ErrorEnvelope *MediaErrorEnvelope `json:"errorEnvelope,omitempty"`
	// Verbs maps a verb (one of the seven of §7G.1) to its wiring.
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
	Args       map[string]string `json:"args"`               // canonical arg → provider dotPath
	Required   []string          `json:"required,omitempty"` // extra required args
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

// Built-in CLI presets for LoadMediaStyle (ADR 0042): media through a locally
// installed CLI: the vendor's official CLI in its documented headless mode, signed in by the user; the provider's terms apply.
const (
	// MediaStyleXaiGrokCli drives the `grok` CLI: image and video.
	MediaStyleXaiGrokCli = "xai-grok-cli"
	// MediaStyleOpenAICodexCli drives the `codex` CLI: image only.
	MediaStyleOpenAICodexCli = "openai-codex-cli"
	// MediaStyleGoogleAgyCli drives Google's Antigravity CLI `agy`: image only.
	MediaStyleGoogleAgyCli = "google-agy-cli"

	// The mock twins: same name, verbs, args, prompt folding and errors as the
	// preset, but no process is started and a fixed file is returned (mock kit).
	MediaStyleXaiGrokCliMock     = "xai-grok-cli-mock"
	MediaStyleOpenAICodexCliMock = "openai-codex-cli-mock"
	MediaStyleGoogleAgyCliMock   = "google-agy-cli-mock"
)

// cliPresets maps a built-in preset to its client label.
var cliPresets = map[string]string{
	MediaStyleXaiGrokCli: "grok", MediaStyleOpenAICodexCli: "codex", MediaStyleGoogleAgyCli: "agy",
	MediaStyleXaiGrokCliMock: "grok", MediaStyleOpenAICodexCliMock: "codex", MediaStyleGoogleAgyCliMock: "agy",
}

// LoadMediaStyle reads and validates one style JSON file, or returns a built-in
// CLI preset when path is MediaStyleXaiGrokCli or MediaStyleOpenAICodexCli. cfg (at most
// one) configures the client behind the style (model, command, loop, gather).
func LoadMediaStyle(path string, cfg ...MediaConfig) (MediaStyle, error) {
	var c MediaConfig
	if len(cfg) > 0 {
		c = cfg[0]
	}
	if label, ok := cliPresets[path]; ok {
		return MediaStyle{Label: label, CLI: path, cfg: c}, nil
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return MediaStyle{}, err
	}
	s, err := parseMediaStyle(b, path)
	s.cfg = c
	return s, err
}

// client is the MediaClient behind a loaded style.
func (s MediaStyle) client() MediaClient {
	if s.CLI != "" {
		var c *CliMediaClient
		switch cliPresets[s.CLI] {
		case "grok":
			c = NewGrokMediaClient(s.cfg).CliMediaClient
		case "codex":
			c = NewCodexMediaClient(s.cfg).CliMediaClient
		default:
			c = newAgyMediaClient(s.cfg)
		}
		c.Mock = strings.HasSuffix(s.CLI, "-mock")
		return c
	}
	c, err := NewStyleMediaClient(s, s.cfg)
	if err != nil {
		return brokenStyle{s.Label, err}
	}
	return c
}

// A loaded style is itself a MediaClient, so CreateMedia(style) works.

func (s MediaStyle) Name() string { return s.client().Name() }

// Supports reports whether the style declares verb (the one verb-support
// mechanism for every style, HTTP or CLI).
func (s MediaStyle) Supports(verb string) bool { return s.client().Supports(verb) }

// Args is the canonical args the style accepts for verb.
func (s MediaStyle) Args(verb string) []MediaArg { return clientArgs(s.client(), verb) }

func (s MediaStyle) Image(ctx context.Context, a map[string]any, c MediaCall) (MediaResult, error) {
	return s.client().Image(ctx, a, c)
}
func (s MediaStyle) Video(ctx context.Context, a map[string]any, c MediaCall) (MediaResult, error) {
	return s.client().Video(ctx, a, c)
}
func (s MediaStyle) Speech(ctx context.Context, a map[string]any, c MediaCall) (MediaResult, error) {
	return s.client().Speech(ctx, a, c)
}
func (s MediaStyle) Music(ctx context.Context, a map[string]any, c MediaCall) (MediaResult, error) {
	return s.client().Music(ctx, a, c)
}
func (s MediaStyle) Transcribe(ctx context.Context, a map[string]any, c MediaCall) (MediaResult, error) {
	return s.client().Transcribe(ctx, a, c)
}
func (s MediaStyle) CloneVoice(ctx context.Context, a map[string]any, c MediaCall) (MediaResult, error) {
	return s.client().CloneVoice(ctx, a, c)
}
func (s MediaStyle) Talk(ctx context.Context, a map[string]any, c MediaCall) (MediaResult, error) {
	return s.client().Talk(ctx, a, c)
}

type brokenStyle struct {
	name string
	err  error
}

func (b brokenStyle) Name() string       { return b.name }
func (brokenStyle) Supports(string) bool { return false }
func (b brokenStyle) Image(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return MediaResult{}, b.err
}
func (b brokenStyle) Video(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return MediaResult{}, b.err
}
func (b brokenStyle) Speech(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return MediaResult{}, b.err
}
func (b brokenStyle) Music(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return MediaResult{}, b.err
}
func (b brokenStyle) Transcribe(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return MediaResult{}, b.err
}
func (b brokenStyle) CloneVoice(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return MediaResult{}, b.err
}
func (b brokenStyle) Talk(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return MediaResult{}, b.err
}

func loadMediaStyleFile(path string) (MediaStyle, error) {
	var s MediaStyle
	b, err := os.ReadFile(path)
	if err != nil {
		return s, err
	}
	return parseMediaStyle(b, path)
}

func parseMediaStyle(b []byte, path string) (MediaStyle, error) {
	var s MediaStyle
	if err := json.Unmarshal(b, &s); err != nil {
		return s, fmt.Errorf("media style %s: %w", path, err)
	}
	if err := validateMediaStyle(s); err != nil {
		return s, err
	}
	return s, nil
}

// validateMediaStyle: an unknown verb, or an args entry mapped to "", is a
// style error. Checked in sorted order so the error is deterministic.
func validateMediaStyle(s MediaStyle) error {
	verbs := make([]string, 0, len(s.Verbs))
	for v := range s.Verbs {
		verbs = append(verbs, v)
	}
	sort.Strings(verbs)
	for _, v := range verbs {
		if _, ok := mediaVerbSpecOf(v); !ok {
			return fmt.Errorf("style: unknown verb %q", v)
		}
	}
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

// StyleMediaClient is a MediaClient over a JSON style (§7G.7). It uses the
// config's URL, Loop, Gather (default mode url) and HTTPClient; Key, when set,
// is what every ${…} in the style's headers expands to.
type StyleMediaClient struct {
	UnsupportedMedia
	style     MediaStyle
	cfg       MediaConfig
	keyEnv    string // built-in: a key is required (config Key or this env var)
	modelPath string // built-in: where the image model goes
	gatherDef string
}

// NewStyleMediaClient makes a client named style.Name (default "style").
func NewStyleMediaClient(style MediaStyle, cfg MediaConfig) (*StyleMediaClient, error) {
	if err := validateMediaStyle(style); err != nil {
		return nil, err
	}
	return &StyleMediaClient{style: style, cfg: cfg, gatherDef: "url"}, nil
}

// Name is the style's name, default "style".
func (s *StyleMediaClient) Name() string {
	if s.style.Label != "" {
		return s.style.Label
	}
	return "style"
}

// Supports reports whether the style declares verb.
func (s *StyleMediaClient) Supports(verb string) bool { _, ok := s.style.Verbs[verb]; return ok }

// Args is the mapped canonical args; required = (canonical required ∩ mapped) ∪ Verb.Required.
func (s *StyleMediaClient) Args(verb string) []MediaArg {
	v, ok := s.style.Verbs[verb]
	spec, known := mediaVerbSpecOf(verb)
	if !ok || !known {
		return nil
	}
	out := []MediaArg{}
	for _, a := range spec.args {
		if _, mapped := v.Args[a.name]; !mapped {
			continue
		}
		req := a.required
		for _, r := range v.Required {
			if r == a.name {
				req = true
			}
		}
		out = append(out, MediaArg{Name: a.name, Required: req})
	}
	return out
}

func (s *StyleMediaClient) Image(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return s.run(ctx, "image", args, call)
}
func (s *StyleMediaClient) Video(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return s.run(ctx, "video", args, call)
}
func (s *StyleMediaClient) Speech(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return s.run(ctx, "speech", args, call)
}
func (s *StyleMediaClient) Music(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return s.run(ctx, "music", args, call)
}
func (s *StyleMediaClient) Transcribe(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return s.run(ctx, "transcribe", args, call)
}
func (s *StyleMediaClient) CloneVoice(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return s.run(ctx, "cloneVoice", args, call)
}
func (s *StyleMediaClient) Talk(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return s.run(ctx, "talk", args, call)
}

var mediaEnvRe = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}`)

// run is the engine (§7G.7 normative order; arg checks were done by the router).
func (s *StyleMediaClient) run(ctx context.Context, verb string, args map[string]any, call MediaCall) (MediaResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	v, ok := s.style.Verbs[verb]
	if !ok {
		return unsupportedVerb(verb)
	}
	key := s.cfg.Key
	if key == "" && s.keyEnv != "" {
		if key = os.Getenv(s.keyEnv); key == "" {
			return MediaResult{}, mediaErr("unavailable", fmt.Sprintf("%s: no API key (set %s)", s.Name(), s.keyEnv))
		}
	}
	headers := map[string]string{}
	for k, val := range s.style.Headers {
		headers[k] = mediaEnvRe.ReplaceAllStringFunc(val, func(m string) string {
			if key != "" {
				return key
			}
			return os.Getenv(mediaEnvRe.FindStringSubmatch(m)[1])
		})
	}
	base := s.style.BaseURL
	if s.cfg.URL != "" {
		base = s.cfg.URL
	}
	timeout := mediaDefaultTimeoutMs
	interval := 5000
	if v.Poll != nil && v.Poll.TimeoutMs > 0 {
		timeout = v.Poll.TimeoutMs
	}
	if v.Poll != nil && v.Poll.IntervalMs > 0 {
		interval = v.Poll.IntervalMs
	}
	if s.cfg.Loop.TimeoutMs > 0 {
		timeout = s.cfg.Loop.TimeoutMs
	}
	if s.cfg.Loop.IntervalMs > 0 {
		interval = s.cfg.Loop.IntervalMs
	}
	j := newMediaJob(ctx, s.Name(), verb, s.cfg, call, base, headers, timeout, s.gatherDef)

	body := map[string]any{}
	if v.Defaults != nil {
		body = deepCopyJSON(v.Defaults).(map[string]any)
	}
	if s.modelPath != "" && verb == "image" {
		if m := s.cfg.modelFor("image", "image", ""); m != "" {
			dotSet(body, s.modelPath, m)
		}
	}
	for _, k := range mediaSortedKeys(args) {
		if p, ok := v.Args[k]; ok && args[k] != nil {
			dotSet(body, p, args[k])
		}
	}
	for k, val := range call.ProviderOptions {
		body[k] = val
	}
	method := v.Start.Method
	if method == "" {
		method = http.MethodPost
	}
	resp, err := s.do(j, method, v.Start.Path, body)
	if err != nil {
		return MediaResult{}, err
	}
	res := MediaResult{Files: []MediaFile{}, Status: "completed"}
	if v.Mode == "async" {
		id, ok := jobIDText(dotGet(resp, v.IDPath))
		if !ok {
			return MediaResult{}, j.fail(fmt.Sprintf("%s: no job id at %s", verb, v.IDPath))
		}
		j.jobID, res.JobID = id, id
		resp, res.Status, err = s.poll(j, v, interval, timeout)
		if err != nil {
			return MediaResult{}, err
		}
	}
	found := dotGet(resp, v.ResultPath)
	if v.Kind == "text" {
		t, ok := scalarText(found)
		if str, isStr := found.(string); isStr {
			t, ok = str, true
		}
		if !ok {
			return MediaResult{}, j.fail(fmt.Sprintf("%s: no result at %s", verb, v.ResultPath))
		}
		res.Text, res.hasText = t, true
		return res, nil
	}
	for _, u := range flattenURLs(found) {
		f, err := j.fromURL(v.Kind, u)
		if err != nil {
			return MediaResult{}, err
		}
		res.Files = append(res.Files, f)
	}
	if len(res.Files) == 0 {
		return MediaResult{}, j.fail(fmt.Sprintf("%s: no result at %s", verb, v.ResultPath))
	}
	return res, nil
}

func (s *StyleMediaClient) poll(j *mediaJob, v MediaVerb, interval, timeout int) (any, string, error) {
	p := v.Poll
	if p == nil {
		return nil, "", j.fail(j.verb + ": async verb has no poll")
	}
	method := p.Method
	if method == "" {
		method = http.MethodGet
	}
	path := strings.ReplaceAll(p.Path, "{id}", percentEncode(j.jobID))
	firstWait := time.Now()
	for {
		if err := j.wait(interval); err != nil {
			return nil, "", err
		}
		resp, err := s.do(j, method, path, nil)
		if err != nil {
			return nil, "", err
		}
		status, _ := scalarText(dotGet(resp, p.StatusPath))
		j.report(status)
		if mediaHas(p.Done, status) {
			return resp, status, nil
		}
		if mediaHas(p.Failed, status) {
			msg := ""
			if p.ErrorPath != "" {
				msg, _ = scalarText(dotGet(resp, p.ErrorPath))
			}
			if msg == "" {
				return nil, status, j.fail(fmt.Sprintf("job %s %s", j.jobID, status))
			}
			return nil, status, j.fail(fmt.Sprintf("job %s %s: %s", j.jobID, status, msg))
		}
		if time.Since(firstWait) >= time.Duration(timeout)*time.Millisecond || !time.Now().Before(j.deadline) {
			return nil, status, j.timedOut()
		}
	}
}

// do sends one style request and applies the §7G.7 step-4 response checks.
func (s *StyleMediaClient) do(j *mediaJob, method, path string, body map[string]any) (any, error) {
	var b any
	if body != nil {
		b = body
	}
	out, _, err := j.jsonCall(method, path, b)
	if err != nil {
		return nil, err
	}
	if env := s.style.ErrorEnvelope; env != nil {
		code := dotGet(out, env.CodePath)
		if code != nil && !jsonEqualAny(code, env.OkValues) {
			msg := ""
			if env.MessagePath != "" {
				if str, ok := dotGet(out, env.MessagePath).(string); ok {
					msg = str
				}
			}
			ct, ok := scalarText(code)
			if str, isStr := code.(string); isStr {
				ct, ok = str, true
			}
			if !ok {
				enc, _ := json.Marshal(code)
				ct = string(enc)
			}
			return nil, j.fail(fmt.Sprintf("API error %s: %s", ct, msg))
		}
	}
	return out, nil
}

// ---- A2E (§7G.9): a style client over the embedded preset ----

//go:embed media-styles/a2e.json
var a2eStyleJSON []byte

// A2EMediaClient is the built-in a2e.ai client (image, video, speech, talk):
// a style client over examples/media-styles/a2e.json, embedded verbatim.
// Key defaults to A2E_API_KEY at call time; gather defaults to download;
// Model, when set, is the image body's model_type.
type A2EMediaClient struct{ *StyleMediaClient }

// NewA2EMediaClient builds the a2e client.
func NewA2EMediaClient(cfg MediaConfig) *A2EMediaClient {
	style, err := parseMediaStyle(a2eStyleJSON, "a2e.json")
	if err != nil {
		panic(err) // the embedded preset is asserted valid by a test
	}
	return &A2EMediaClient{&StyleMediaClient{style: style, cfg: cfg, keyEnv: "A2E_API_KEY",
		modelPath: "model_type", gatherDef: "download"}}
}

// ---- style helpers ----

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
// segment, an array only a numeric one (else it is replaced, §7G.8).
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

// mediaSourceID names who served a call (MediaResult.Style).
func mediaSourceID(c MediaClient) string {
	if s, ok := c.(MediaStyle); ok {
		if s.CLI != "" {
			return s.CLI
		}
		if s.Label != "" {
			return s.Label
		}
		return "style"
	}
	return c.Name()
}

// mediaSourceModel is the configured model for verb, or "" (never guessed).
func mediaSourceModel(c MediaClient, verb string) string {
	s, ok := c.(MediaStyle)
	if !ok {
		return ""
	}
	if m := s.cfg.Models[verb]; m != "" {
		return m
	}
	return s.cfg.Model
}

// MediaAvailability is the cheap pre-flight answer of MediaStyle.Available.
type MediaAvailability struct {
	Installed bool   `json:"installed"`
	LoggedIn  bool   `json:"loggedIn"`
	Reason    string `json:"reason"`
}

// MediaMockAvailableEnv set to "0" makes every mock preset report unavailable.
const MediaMockAvailableEnv = "TOOLNEXUS_MEDIA_MOCK_AVAILABLE"

// Available reports, in well under a second and without generating anything,
// whether the style can run: for a CLI preset, the binary is found and the CLI's
// own login file exists (its contents are never read); for an HTTP style, every
// ${VAR} its headers use is set (or the config Key is). §7G.10b.
func (s MediaStyle) Available(ctx context.Context) MediaAvailability {
	if s.CLI == "" {
		if s.cfg.Key != "" {
			return MediaAvailability{true, true, s.styleID() + ": key from config"}
		}
		for _, name := range styleEnvVars(s) {
			if os.Getenv(name) == "" {
				return MediaAvailability{true, false, s.styleID() + ": " + name + " is not set"}
			}
		}
		return MediaAvailability{true, true, s.styleID() + ": env present"}
	}
	label := cliPresets[s.CLI]
	if strings.HasSuffix(s.CLI, "-mock") {
		if os.Getenv(MediaMockAvailableEnv) == "0" {
			return MediaAvailability{false, false, label + ": mock set unavailable by " + MediaMockAvailableEnv + "=0"}
		}
		return MediaAvailability{true, true, label + ": mock"}
	}
	cmd := s.cfg.Command
	bin := label
	if len(cmd) > 0 {
		bin = cmd[0]
	}
	if _, err := exec.LookPath(bin); err != nil {
		return MediaAvailability{false, false, fmt.Sprintf("%s: %q not found on PATH", label, bin)}
	}
	auth := cliAuthFile(label)
	if auth == "" {
		return MediaAvailability{true, false, label + ": installed; login state unknown"}
	}
	if fi, err := os.Stat(auth); err != nil || fi.IsDir() {
		return MediaAvailability{true, false, fmt.Sprintf("%s: installed, not logged in (%s missing)", label, auth)}
	}
	return MediaAvailability{true, true, fmt.Sprintf("%s: installed, logged in (%s present)", label, auth)}
}

func (s MediaStyle) styleID() string {
	if s.Label != "" {
		return s.Label
	}
	return "style"
}

// cliAuthFile is the file each CLI writes when logged in (checked for existence only).
func cliAuthFile(label string) string {
	home, _ := os.UserHomeDir()
	switch label {
	case "grok":
		return filepath.Join(home, ".grok", "auth.json")
	case "codex":
		if h := os.Getenv("CODEX_HOME"); h != "" {
			return filepath.Join(h, "auth.json")
		}
		return filepath.Join(home, ".codex", "auth.json")
	case "agy":
		return filepath.Join(home, ".gemini", "antigravity-cli", "antigravity-oauth-token")
	}
	return ""
}

var styleEnvRe = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}`)

// styleEnvVars lists the ${VAR}s the style's headers use, sorted, unique.
func styleEnvVars(s MediaStyle) []string {
	seen := map[string]bool{}
	var out []string
	for _, v := range s.Headers {
		for _, m := range styleEnvRe.FindAllStringSubmatch(v, -1) {
			if !seen[m[1]] {
				seen[m[1]] = true
				out = append(out, m[1])
			}
		}
	}
	sort.Strings(out)
	return out
}
