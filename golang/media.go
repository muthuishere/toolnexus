// Media generation (SPEC §7G) — image, video, speech, music, transcription,
// voice cloning and talking-head video behind one interface with two faces:
// typed verbs a host calls (Media.Image, Media.Video, ...) and tools an LLM
// calls (Media.Tools). Providers are media clients: built-in API clients
// (A2E, ElevenLabs, OpenAI, OpenRouter, fal), CLI clients (Grok, Codex), style
// clients over the JSON style engine, or any type implementing MediaClient.
// ADRs 0036 + 0037; conformance fixtures in conformance/media/.

package toolnexus

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
)

// SourceMedia marks a tool produced by Media.Tools (§7G).
const SourceMedia ToolSource = "media"

// ---- the interface (§7G.2) ----

// MediaClient is one media provider. Every verb is optional: Supports is the
// truth, and a verb the client does not support is never called. Embed
// UnsupportedMedia to implement only the verbs a client serves.
//
// args holds canonical arg names (§7G.1) with nil values already removed.
// A failed verb returns a *MediaError (or any error, treated as failed);
// return a *MediaError with Status "unavailable" (see MediaUnavailable) to let
// CreateMedia fall through to the next client.
type MediaClient interface {
	Name() string
	Supports(verb string) bool
	Image(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error)
	Video(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error)
	Speech(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error)
	Music(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error)
	Transcribe(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error)
	CloneVoice(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error)
	Talk(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error)
}

// MediaArgsProvider is the optional args(verb) of §7G.2: the canonical args a
// client accepts for a verb. A client without it — or returning nil — accepts
// every canonical arg of the verb, with the canonical required flags.
type MediaArgsProvider interface {
	Args(verb string) []MediaArg
}

// MediaArg is one accepted canonical arg.
type MediaArg struct {
	Name     string
	Required bool
}

// UnsupportedMedia is the embeddable base for a custom MediaClient: every verb
// fails. Override the verbs the client serves and implement Name and Supports.
type UnsupportedMedia struct{}

func unsupportedVerb(verb string) (MediaResult, error) {
	return MediaResult{}, mediaErr("failed", fmt.Sprintf("media: no client supports %q", verb))
}

func (UnsupportedMedia) Image(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return unsupportedVerb("image")
}
func (UnsupportedMedia) Video(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return unsupportedVerb("video")
}
func (UnsupportedMedia) Speech(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return unsupportedVerb("speech")
}
func (UnsupportedMedia) Music(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return unsupportedVerb("music")
}
func (UnsupportedMedia) Transcribe(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return unsupportedVerb("transcribe")
}
func (UnsupportedMedia) CloneVoice(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return unsupportedVerb("cloneVoice")
}
func (UnsupportedMedia) Talk(context.Context, map[string]any, MediaCall) (MediaResult, error) {
	return unsupportedVerb("talk")
}

// MediaCall is the per-call context of §7G.2 beside the context.Context (which
// is the cancellation signal). The zero value is "nothing set".
type MediaCall struct {
	// Deadline is absolute; the job deadline is the earlier of it and
	// loop.timeoutMs from the start. Zero ⇒ none.
	Deadline time.Time
	// Progress is called for every progress status; when nil the client's
	// Loop.OnProgress is called instead (never both).
	Progress func(MediaProgress)
	// ProviderOptions' top-level keys overwrite the request body last (function
	// face only — never set from the tool face).
	ProviderOptions map[string]any
}

// MediaProgress is one progress report.
type MediaProgress struct {
	Verb      string
	Client    string
	JobID     string
	Status    string
	ElapsedMs int64
}

// MediaFile is one produced file. Type is "image", "audio" or "file" (video).
// Which of URL / Path / Data is set is decided by gather (§7G.5): Data is
// standard padded base64.
type MediaFile struct {
	Type     string `json:"type"`
	MimeType string `json:"mimeType"`
	URL      string `json:"url,omitempty"`
	Path     string `json:"path,omitempty"`
	Data     string `json:"data,omitempty"`
}

// MediaUsage is provider-reported usage (§7G.12 D21). Absent fields were not reported.
// Raw carries values whose unit is undocumented (Grok costUsdTicks), unconverted.
type MediaUsage struct {
	InputTokens  *int           `json:"inputTokens,omitempty"`
	OutputTokens *int           `json:"outputTokens,omitempty"`
	CostUsd      *float64       `json:"costUsd,omitempty"`
	Raw          map[string]any `json:"raw,omitempty"`
}

// mediaUsageOf returns nil when u has no fields.
func mediaUsageOf(u MediaUsage) *MediaUsage {
	if u.InputTokens == nil && u.OutputTokens == nil && u.CostUsd == nil && len(u.Raw) == 0 {
		return nil
	}
	return &u
}

func usageInt(v any) *int {
	if f := usageFloat(v); f != nil {
		n := int(*f)
		return &n
	}
	return nil
}

func usageFloat(v any) *float64 {
	switch x := v.(type) {
	case float64:
		return &x
	case json.Number:
		if f, err := x.Float64(); err == nil {
			return &f
		}
	}
	return nil
}

// MediaResult is what every verb returns.
type MediaResult struct {
	Files  []MediaFile `json:"files"`
	Text   string      `json:"text,omitempty"`
	JobID  string      `json:"jobId,omitempty"`
	Status string      `json:"status"`
	// Usage is what the provider reported (§7G.12 D21), never estimated; nil when none.
	Usage *MediaUsage `json:"usage,omitempty"`
	// Style is who served the call: the preset id for a CLI preset, the style's
	// name for an HTTP style, else the client's name (§7G.10b).
	Style string `json:"style,omitempty"`
	// Model is the configured model for the verb, when one was set; never guessed.
	Model string `json:"model,omitempty"`
	// hasText marks a text-kind result, so an empty Text ("" — e.g. silence)
	// still serialises as "text":"".
	hasText bool
}

// MarshalJSON emits "text" for every text-kind result, even when it is "".
func (r MediaResult) MarshalJSON() ([]byte, error) {
	type plain MediaResult
	if r.Files == nil {
		r.Files = []MediaFile{}
	}
	if !r.hasText || r.Text != "" {
		return json.Marshal(plain(r))
	}
	return json.Marshal(struct {
		plain
		Text string `json:"text"`
	}{plain(r), r.Text})
}

// ErrMediaUnavailable is matched by errors.Is for every media error whose
// status is "unavailable" (§7G.2): the call was valid but this client cannot
// serve it now (no credits, quota, no key, CLI missing, ZDR).
var ErrMediaUnavailable = errors.New("media: unavailable")

// MediaError is the typed media error. Status is failed | timeout | cancelled |
// unavailable. A cancelled error unwraps to the context's error.
type MediaError struct {
	Message string
	Status  string
	JobID   string
	Err     error
}

func (e *MediaError) Error() string { return e.Message }
func (e *MediaError) Unwrap() error { return e.Err }

// Is makes errors.Is(err, ErrMediaUnavailable) true for status "unavailable".
func (e *MediaError) Is(target error) bool {
	return target == ErrMediaUnavailable && e.Status == "unavailable"
}

// MediaUnavailable builds an "unavailable" media error, for custom clients.
func MediaUnavailable(message string) error { return mediaErr("unavailable", message) }

func mediaErr(status, msg string) *MediaError { return &MediaError{Message: msg, Status: status} }

func mediaFailed(format string, a ...any) *MediaError {
	return mediaErr("failed", fmt.Sprintf(format, a...))
}

// mediaCancelled keeps the native cancellation error reachable (§7G.8).
func mediaCancelled(err error) *MediaError {
	return &MediaError{Message: err.Error(), Status: "cancelled", Err: err}
}

// ---- shared config (§7G.4) ----

// MediaConfig is the one config shape of every built-in client. Fields a client
// does not use are ignored.
type MediaConfig struct {
	// Model is the model of the client's primary verb (CLI: passed as -m).
	Model string
	// Models overrides the model per verb; wins over Model and the default.
	Models map[string]string
	// Key is the API key; empty ⇒ the client's env var, read at call time.
	// Never logged, never in an error message.
	Key string
	// URL overrides the base URL (trailing "/" trimmed) — any compatible server.
	URL string
	// Command is the CLI argv prefix; empty ⇒ the CLI's name.
	Command []string
	// Loop bounds jobs and reports progress.
	Loop MediaLoop
	// Gather decides how results become files (§7G.5).
	Gather MediaGather
	// HTTPClient is the same injection seam as ClientOptions.HTTPClient.
	// Nil ⇒ http.DefaultClient.
	HTTPClient *http.Client
}

// MediaLoop: values <= 0 mean the default.
type MediaLoop struct {
	// IntervalMs is the poll interval (default per client).
	IntervalMs int
	// TimeoutMs is the job deadline from the start (default 600000).
	TimeoutMs int
	// OnProgress is called when MediaCall.Progress is nil.
	OnProgress func(MediaProgress)
}

// MediaGather: Mode url | download | inline (default download; style clients
// url). Dir default <os temp>/toolnexus-media; MaxBytes default 100 MiB;
// Keep (CLI only) default true — false deletes the CLI's original after a copy.
type MediaGather struct {
	// Name, when set, names saved files "<name><ext>", then "<name>-2<ext>", …
	// (first free name). Sanitised: anything outside [A-Za-z0-9._-] becomes "-",
	// leading "." and "-" are dropped; empty ⇒ the default naming (§7G.10b).
	Name     string
	Mode     string
	Dir      string
	MaxBytes int64
	Keep     *bool
}

const mediaDefaultTimeoutMs = 600000

func (c MediaConfig) timeoutMs() int {
	if c.Loop.TimeoutMs > 0 {
		return c.Loop.TimeoutMs
	}
	return mediaDefaultTimeoutMs
}

func (c MediaConfig) intervalMs(def int) int {
	if c.Loop.IntervalMs > 0 {
		return c.Loop.IntervalMs
	}
	return def
}

// modelFor: models[verb], else Model when verb is the primary verb, else def.
func (c MediaConfig) modelFor(verb, primary, def string) string {
	if m := c.Models[verb]; m != "" {
		return m
	}
	if verb == primary && c.Model != "" {
		return c.Model
	}
	return def
}

func (c MediaConfig) httpClient() *http.Client {
	if c.HTTPClient != nil {
		return c.HTTPClient
	}
	return http.DefaultClient
}

// ---- verbs table (§7G.1) ----

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
	{"video", "generate_video", "Generate a short video from a prompt, optionally starting from an image. Can take minutes.", []mediaArgSpec{
		{"prompt", "string", "", "What should happen in the video.", true},
		{"image", "string", "", "URL or local path of the starting image.", false},
		{"seconds", "integer", "", "Video length in seconds.", false},
		{"aspectRatio", "string", "", "Aspect ratio, e.g. 9:16.", false},
	}},
	{"speech", "generate_speech", "Speak text aloud and return the audio.", []mediaArgSpec{
		{"text", "string", "", "The text to speak.", true},
		{"voice", "string", "", "Voice name or id, including a cloned voice.", false},
	}},
	{"music", "generate_music", "Generate music from a text prompt and return the audio.", []mediaArgSpec{
		{"prompt", "string", "", "Style, mood and instruments of the music.", true},
		{"seconds", "integer", "", "Length in seconds.", false},
		{"instrumental", "boolean", "", "True for no vocals.", false},
		{"lyrics", "string", "", "Lyrics to sing.", false},
	}},
	{"transcribe", "transcribe_audio", "Transcribe speech in an audio file to text.", []mediaArgSpec{
		{"audio", "string", "", "URL or local path of the audio.", true},
		{"language", "string", "", "Language of the speech as an ISO 639-1 code, e.g. en.", false},
	}},
	{"cloneVoice", "clone_voice", "Create a cloned voice from audio samples and return its voice id.", []mediaArgSpec{
		{"name", "string", "", "Name for the new voice.", true},
		{"samples", "array", "string", "URLs or local paths of audio samples of the voice.", true},
	}},
	{"talk", "talking_video", "Make the person in an image speak, from speech audio or from text (lip-synced video).", []mediaArgSpec{
		{"image", "string", "", "URL or local path of the face image.", true},
		{"audio", "string", "", "URL or local path of the speech audio.", false},
		{"text", "string", "", "Text to speak when no audio is given.", false},
		{"voice", "string", "", "Voice for the text.", false},
	}},
}

func mediaVerbSpecOf(verb string) (mediaVerbSpec, bool) {
	for _, s := range mediaVerbs {
		if s.verb == verb {
			return s, true
		}
	}
	return mediaVerbSpec{}, false
}

// mediaArgsOf builds a MediaArg list for a built-in client: names in canonical
// order, required ones marked.
func mediaArgsOf(required []string, optional ...string) []MediaArg {
	out := []MediaArg{}
	for _, n := range required {
		out = append(out, MediaArg{Name: n, Required: true})
	}
	for _, n := range optional {
		out = append(out, MediaArg{Name: n})
	}
	return out
}

// clientArgs is a client's accepted args for verb (§7G.2 args, absent ⇒ canonical).
func clientArgs(c MediaClient, verb string) []MediaArg {
	if p, ok := c.(MediaArgsProvider); ok {
		if a := p.Args(verb); a != nil {
			return a
		}
	}
	spec, _ := mediaVerbSpecOf(verb)
	out := make([]MediaArg, len(spec.args))
	for i, a := range spec.args {
		out[i] = MediaArg{Name: a.name, Required: a.required}
	}
	return out
}

// ---- createMedia (§7G.3) ----

// Media routes each verb to its clients. Build it with CreateMedia.
type Media struct {
	clients []MediaClient
}

// CreateMedia builds a router over one or more clients, in priority order.
// Each call goes to the first client that supports the verb, and falls through
// to the next only when a client is unavailable.
func CreateMedia(clients ...MediaClient) (*Media, error) {
	if len(clients) == 0 {
		return nil, errors.New("media: no clients")
	}
	return &Media{clients: append([]MediaClient(nil), clients...)}, nil
}

// MustCreateMedia is CreateMedia that panics on an empty client list.
func MustCreateMedia(clients ...MediaClient) *Media {
	m, err := CreateMedia(clients...)
	if err != nil {
		panic(err)
	}
	return m
}

func (m *Media) candidates(verb string) []MediaClient {
	var out []MediaClient
	for _, c := range m.clients {
		if c.Supports(verb) {
			out = append(out, c)
		}
	}
	return out
}

// noun is "style" when every source is a loaded style, else "client".
func (m *Media) noun() string {
	for _, c := range m.clients {
		if _, ok := c.(MediaStyle); !ok {
			return "client"
		}
	}
	return "style"
}

// Verbs is every verb some source supports, in canonical verb order.
func (m *Media) Verbs() []string {
	out := []string{}
	for _, spec := range mediaVerbs {
		if m.Supports(spec.verb) {
			out = append(out, spec.verb)
		}
	}
	return out
}

// GenerateMedia is one-shot sugar for
// CreateMedia(LoadMediaStyle(style)).Generate(ctx, verb, args, MediaCall{}).
func GenerateMedia(ctx context.Context, style string, verb string, args map[string]any, cfg ...MediaConfig) (MediaResult, error) {
	s, err := LoadMediaStyle(style, cfg...)
	if err != nil {
		return MediaResult{}, err
	}
	m, err := CreateMedia(s)
	if err != nil {
		return MediaResult{}, err
	}
	return m.Generate(ctx, verb, args, MediaCall{})
}

// Supports reports whether any client supports verb.
func (m *Media) Supports(verb string) bool { return len(m.candidates(verb)) > 0 }

// Generate runs verb with canonical args through the routing of §7G.3 (the
// generic function face).
func (m *Media) Generate(ctx context.Context, verb string, args map[string]any, call MediaCall) (MediaResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	res, _, err := m.route(ctx, verb, args, call)
	return res, err
}

// route returns the result and the name of the client that served it.
func (m *Media) route(ctx context.Context, verb string, args map[string]any, call MediaCall) (MediaResult, string, error) {
	cands := m.candidates(verb)
	if len(cands) == 0 {
		return MediaResult{}, "", mediaFailed("media: no %s supports %q", m.noun(), verb)
	}
	clean := map[string]any{}
	for k, v := range args {
		if v != nil {
			clean[k] = v
		}
	}
	keys := make([]string, 0, len(clean))
	for k := range clean {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	// D7/D19: a key that is not canonical for the verb fails at once.
	spec, _ := mediaVerbSpecOf(verb)
	for _, k := range keys {
		canonical := false
		for _, a := range spec.args {
			if a.name == k {
				canonical = true
				break
			}
		}
		if !canonical {
			return MediaResult{}, "", mediaFailed("%s: argument %q not supported", verb, k)
		}
	}
	var msgs []string
	for _, c := range cands {
		// D19: a candidate that cannot take the args is skipped like unavailable.
		acc := clientArgs(c, verb)
		if bad := firstUnaccepted(acc, keys); bad != "" {
			msgs = append(msgs, fmt.Sprintf("%s: argument %q not supported", c.Name(), bad))
			continue
		}
		if miss := missingRequired(acc, verb, clean); miss != "" {
			msgs = append(msgs, fmt.Sprintf("%s: missing required argument %q", c.Name(), miss))
			continue
		}
		a := clean
		res, err := callMediaVerb(ctx, c, verb, a, call)
		if err == nil {
			if res.Style == "" {
				res.Style = mediaSourceID(c)
			}
			if res.Model == "" {
				res.Model = mediaSourceModel(c, verb)
			}
			return res, c.Name(), nil
		}
		if !errors.Is(err, ErrMediaUnavailable) {
			return MediaResult{}, c.Name(), err
		}
		msgs = append(msgs, err.Error())
	}
	return MediaResult{}, "", mediaErr("unavailable",
		fmt.Sprintf("media: no client available for %q: %s", verb, strings.Join(msgs, "; ")))
}

// missingRequired returns the first required arg of acc (in canonical order,
// then declaration order) absent from args, or "".
func missingRequired(acc []MediaArg, verb string, args map[string]any) string {
	req := map[string]bool{}
	for _, a := range acc {
		if a.Required {
			if _, ok := args[a.Name]; !ok {
				req[a.Name] = true
			}
		}
	}
	if len(req) == 0 {
		return ""
	}
	spec, _ := mediaVerbSpecOf(verb)
	for _, a := range spec.args {
		if req[a.name] {
			return a.name
		}
	}
	for _, a := range acc {
		if req[a.Name] {
			return a.Name
		}
	}
	return ""
}

func firstUnaccepted(acc []MediaArg, keys []string) string {
	for _, k := range keys {
		if !mediaArgIn(acc, k) {
			return k
		}
	}
	return ""
}

func mediaArgIn(args []MediaArg, name string) bool {
	for _, a := range args {
		if a.Name == name {
			return true
		}
	}
	return false
}

func callMediaVerb(ctx context.Context, c MediaClient, verb string, args map[string]any, call MediaCall) (MediaResult, error) {
	switch verb {
	case "image":
		return c.Image(ctx, args, call)
	case "video":
		return c.Video(ctx, args, call)
	case "speech":
		return c.Speech(ctx, args, call)
	case "music":
		return c.Music(ctx, args, call)
	case "transcribe":
		return c.Transcribe(ctx, args, call)
	case "cloneVoice":
		return c.CloneVoice(ctx, args, call)
	case "talk":
		return c.Talk(ctx, args, call)
	}
	return unsupportedVerb(verb)
}

// ---- function face: typed verbs ----
//
// Zero-valued fields are absent (not sent). To send a literal zero, use
// Generate with an explicit args map. The embedded MediaCall carries the
// deadline, progress callback and ProviderOptions.

// ImageInput is the input of Media.Image.
type ImageInput struct {
	Prompt      string
	Width       int
	Height      int
	AspectRatio string
	Seed        int
	MediaCall
}

// VideoInput is the input of Media.Video.
type VideoInput struct {
	Prompt      string
	Image       string // URL or local path of the starting image
	Seconds     int
	AspectRatio string
	MediaCall
}

// SpeechInput is the input of Media.Speech.
type SpeechInput struct {
	Text  string
	Voice string // name or id, including a cloned voice
	MediaCall
}

// MusicInput is the input of Media.Music.
type MusicInput struct {
	Prompt       string
	Seconds      int
	Instrumental *bool // nil ⇒ absent
	Lyrics       string
	MediaCall
}

// TranscribeInput is the input of Media.Transcribe.
type TranscribeInput struct {
	Audio    string // URL or local path of the audio
	Language string
	MediaCall
}

// CloneVoiceInput is the input of Media.CloneVoice.
type CloneVoiceInput struct {
	Name    string
	Samples []string // URLs or local paths of audio samples
	MediaCall
}

// TalkInput is the input of Media.Talk.
type TalkInput struct {
	Image string // URL or local path of the face image
	Audio string // URL or local path of the speech audio
	Text  string // text to speak when no audio is given
	Voice string
	MediaCall
}

// Image generates an image from a text prompt.
func (m *Media) Image(ctx context.Context, in ImageInput) (MediaResult, error) {
	return m.Generate(ctx, "image", present(
		"prompt", in.Prompt, "width", in.Width, "height", in.Height,
		"aspectRatio", in.AspectRatio, "seed", in.Seed), in.MediaCall)
}

// Video generates a short video, optionally from a starting image.
func (m *Media) Video(ctx context.Context, in VideoInput) (MediaResult, error) {
	return m.Generate(ctx, "video", present(
		"prompt", in.Prompt, "image", in.Image, "seconds", in.Seconds,
		"aspectRatio", in.AspectRatio), in.MediaCall)
}

// Speech speaks text aloud.
func (m *Media) Speech(ctx context.Context, in SpeechInput) (MediaResult, error) {
	return m.Generate(ctx, "speech", present("text", in.Text, "voice", in.Voice), in.MediaCall)
}

// Music generates music from a prompt.
func (m *Media) Music(ctx context.Context, in MusicInput) (MediaResult, error) {
	args := present("prompt", in.Prompt, "seconds", in.Seconds, "lyrics", in.Lyrics)
	if in.Instrumental != nil {
		args["instrumental"] = *in.Instrumental
	}
	return m.Generate(ctx, "music", args, in.MediaCall)
}

// Transcribe turns speech into MediaResult.Text.
func (m *Media) Transcribe(ctx context.Context, in TranscribeInput) (MediaResult, error) {
	return m.Generate(ctx, "transcribe", present("audio", in.Audio, "language", in.Language), in.MediaCall)
}

// CloneVoice creates a cloned voice; its id is MediaResult.Text.
func (m *Media) CloneVoice(ctx context.Context, in CloneVoiceInput) (MediaResult, error) {
	args := present("name", in.Name)
	if in.Samples != nil {
		s := make([]any, len(in.Samples))
		for i, v := range in.Samples {
			s[i] = v
		}
		args["samples"] = s
	}
	return m.Generate(ctx, "cloneVoice", args, in.MediaCall)
}

// Talk makes the person in an image speak (lip-synced video).
func (m *Media) Talk(ctx context.Context, in TalkInput) (MediaResult, error) {
	return m.Generate(ctx, "talk", present("image", in.Image, "audio", in.Audio,
		"text", in.Text, "voice", in.Voice), in.MediaCall)
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

// ---- tool face (§7G.6) ----

// Tools returns one Tool (source "media") per verb any client supports, in
// canonical verb order. A tool's schema is the first supporting client's args.
// Execute never returns an error: a failure is ToolResult{IsError:true}.
func (m *Media) Tools() []Tool {
	out := []Tool{}
	for _, spec := range mediaVerbs {
		cands := m.candidates(spec.verb)
		if len(cands) == 0 {
			continue
		}
		accepted := clientArgs(cands[0], spec.verb)
		props := map[string]any{}
		required := []string{}
		for _, a := range spec.args {
			var acc *MediaArg
			for i := range accepted {
				if accepted[i].Name == a.name {
					acc = &accepted[i]
				}
			}
			if acc == nil {
				continue
			}
			p := map[string]any{"type": a.typ, "description": a.desc}
			if a.typ == "array" {
				p["items"] = map[string]any{"type": a.items}
			}
			props[a.name] = p
			if acc.Required {
				required = append(required, a.name)
			}
		}
		verb := spec.verb
		out = append(out, Tool{
			Name:        spec.tool,
			Description: spec.desc,
			InputSchema: JSONSchema{"type": "object", "properties": props, "required": required},
			Source:      SourceMedia,
			// Each call is an independent job: always concurrency-safe (§7G.6).
			ConcurrencySafe: func(map[string]any) bool { return true },
			Execute: func(args map[string]any, tc *ToolContext) (ToolResult, error) {
				ctx := context.Background()
				if tc != nil && tc.Ctx != nil {
					ctx = tc.Ctx
				}
				return m.toolResult(ctx, verb, args), nil
			},
		})
	}
	return out
}

func (m *Media) toolResult(ctx context.Context, verb string, args map[string]any) ToolResult {
	r, client, err := m.route(ctx, verb, args, MediaCall{})
	if err != nil {
		return ToolResult{IsError: true, Output: err.Error()}
	}
	meta := map[string]any{"source": "media", "verb": verb, "client": client, "status": r.Status}
	if r.JobID != "" {
		meta["jobId"] = r.JobID
	}
	if r.Usage != nil {
		meta["usage"] = r.Usage
	}
	if verb == "transcribe" || verb == "cloneVoice" || (len(r.Files) == 0 && r.hasText) {
		return ToolResult{Output: r.Text, Metadata: meta}
	}
	lines := make([]string, len(r.Files))
	parts := make([]ContentPart, len(r.Files))
	for i, f := range r.Files {
		switch {
		case f.Path != "":
			lines[i] = f.Path
		case f.URL != "":
			lines[i] = f.URL
		default:
			n := 0
			if b, err := base64.StdEncoding.DecodeString(f.Data); err == nil {
				n = len(b)
			}
			lines[i] = fmt.Sprintf("inline %s %d bytes", f.MimeType, n)
		}
		p := ContentPart{Type: f.Type, MimeType: f.MimeType, path: f.Path}
		switch {
		case f.Data != "":
			p.Data = f.Data
		case strings.HasPrefix(f.URL, "http://") || strings.HasPrefix(f.URL, "https://"):
			p.URL = f.URL
		default: // a local file: its bytes, read now (a part never holds a path, §1B)
			path := f.Path
			if path == "" {
				path = strings.TrimPrefix(f.URL, "file://")
			}
			b, err := os.ReadFile(path)
			if err != nil {
				return ToolResult{IsError: true, Output: fmt.Sprintf("%s: cannot read %q: %v", verb, path, err)}
			}
			p.Data = base64.StdEncoding.EncodeToString(b)
		}
		parts[i] = p
	}
	word := "audio"
	kind := ""
	if len(r.Files) > 0 {
		kind = r.Files[0].Type
	}
	switch kind {
	case "image":
		word = "image"
	case "file":
		word = "video"
	}
	return ToolResult{Output: word + " ready: " + strings.Join(lines, "\n"), Parts: parts, Metadata: meta}
}

// toolPartsForModel is the §7G.6 model-visibility rule applied at message
// assembly (§8A): of a media tool result's parts, only image parts reach the
// model; video and audio stay in toolCalls[].parts and RunResult.files only.
// Every other tool's parts pass through unchanged.
func toolPartsForModel(r ToolResult) []ContentPart {
	if r.Metadata == nil || r.Metadata["source"] != "media" {
		return r.Parts
	}
	var out []ContentPart
	for _, p := range r.Parts {
		if p.Type == "image" {
			out = append(out, p)
		}
	}
	return out
}
