// Built-in API media clients (SPEC §7G.9): ElevenLabs, OpenAI, OpenRouter and
// fal, each code on the shared API base (media_engine.go). A2E is a style
// client (media_style.go). Request shapes marked unverified in the SPEC are
// implemented from the provider's docs.

package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
)

// apiMediaClient is the shared API base: name, key, base URL, verbs and args.
type apiMediaClient struct {
	UnsupportedMedia
	name       string
	keyEnv     string
	keyHeader  string
	keyPrefix  string
	defaultURL string
	interval   int
	cfg        MediaConfig
	verbs      map[string][]MediaArg
}

// Name is the client's name ("openai", "fal", ...).
func (a *apiMediaClient) Name() string { return a.name }

// Supports reports whether the client serves verb.
func (a *apiMediaClient) Supports(verb string) bool { _, ok := a.verbs[verb]; return ok }

// Args is the client's accepted args for verb (§7G.9 table).
func (a *apiMediaClient) Args(verb string) []MediaArg { return a.verbs[verb] }

// begin checks the key (missing ⇒ unavailable, no request) and starts a job.
func (a *apiMediaClient) begin(ctx context.Context, verb string, call MediaCall) (*mediaJob, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	key := a.cfg.Key
	if key == "" {
		key = os.Getenv(a.keyEnv)
	}
	if key == "" {
		return nil, mediaErr("unavailable", fmt.Sprintf("%s: no API key (set %s)", a.name, a.keyEnv))
	}
	base := a.defaultURL
	if a.cfg.URL != "" {
		base = a.cfg.URL
	}
	return newMediaJob(ctx, a.name, verb, a.cfg, call, base,
		map[string]string{a.keyHeader: a.keyPrefix + key}, a.cfg.timeoutMs(), "download"), nil
}

func withProviderOptions(body map[string]any, opts map[string]any) map[string]any {
	for k, v := range opts {
		body[k] = v
	}
	return body
}

func done(files []MediaFile, jobID string) MediaResult {
	if files == nil {
		files = []MediaFile{}
	}
	return MediaResult{Files: files, JobID: jobID, Status: "completed"}
}

func textResult(text, jobID string) MediaResult {
	return MediaResult{Files: []MediaFile{}, Text: text, JobID: jobID, Status: "completed", hasText: true}
}

func sampleList(v any) []string {
	switch x := v.(type) {
	case []string:
		return x
	case []any:
		out := make([]string, 0, len(x))
		for _, e := range x {
			out = append(out, argText(e))
		}
		return out
	case string:
		return []string{x}
	}
	return nil
}

// ---- ElevenLabs ----

// ElevenLabsMediaClient: speech (incl. cloned voices by name), music,
// transcribe, cloneVoice. Key ELEVENLABS_API_KEY, header xi-api-key.
type ElevenLabsMediaClient struct{ *apiMediaClient }

// NewElevenLabsMediaClient builds the ElevenLabs client.
func NewElevenLabsMediaClient(cfg MediaConfig) *ElevenLabsMediaClient {
	return &ElevenLabsMediaClient{&apiMediaClient{name: "elevenlabs", keyEnv: "ELEVENLABS_API_KEY",
		keyHeader: "xi-api-key", defaultURL: "https://api.elevenlabs.io", cfg: cfg,
		verbs: map[string][]MediaArg{
			"speech":     mediaArgsOf([]string{"text"}, "voice"),
			"music":      mediaArgsOf([]string{"prompt"}, "seconds", "instrumental"),
			"transcribe": mediaArgsOf([]string{"audio"}, "language"),
			"cloneVoice": mediaArgsOf([]string{"name", "samples"}),
		}}}
}

const elevenDefaultVoice = "JBFqnCBsd6RMkjVDRZzb"

func (c *ElevenLabsMediaClient) Speech(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "speech", call)
	if err != nil {
		return MediaResult{}, err
	}
	id := elevenDefaultVoice
	if v, ok := args["voice"]; ok {
		voice := argText(v)
		out, _, err := j.jsonCall(http.MethodGet, "/v2/voices?page_size=100&search="+percentEncode(voice), nil)
		if err != nil {
			return MediaResult{}, err
		}
		id = ""
		if list, ok := dotGet(out, "voices").([]any); ok {
			for _, e := range list {
				vid, _ := dotGet(e, "voice_id").(string)
				name, _ := dotGet(e, "name").(string)
				if vid != "" && (strings.EqualFold(name, voice) || vid == voice) {
					id = vid
					break
				}
			}
		}
		if id == "" {
			if len([]rune(voice)) == 20 && !strings.Contains(voice, " ") {
				id = voice
			} else {
				return MediaResult{}, j.fail(fmt.Sprintf("speech: voice %q not found", voice))
			}
		}
	}
	body := withProviderOptions(map[string]any{"text": argText(args["text"]),
		"model_id": c.cfg.modelFor("speech", "speech", "eleven_flash_v2_5")}, call.ProviderOptions)
	b, _ := json.Marshal(body)
	r, err := j.call(http.MethodPost, "/v1/text-to-speech/"+percentEncode(id)+"?output_format=mp3_22050_32", b, "application/json")
	if err != nil {
		return MediaResult{}, err
	}
	j.jobID = r.header.Get("request-id")
	f, err := j.fromBytes("audio", r.raw, "audio/mpeg")
	if err != nil {
		return MediaResult{}, err
	}
	return done([]MediaFile{f}, j.jobID), nil
}

func (c *ElevenLabsMediaClient) Music(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "music", call)
	if err != nil {
		return MediaResult{}, err
	}
	ms := int64(10000)
	if n, ok := argInt(args["seconds"]); ok {
		ms = n * 1000
	}
	body := map[string]any{"prompt": argText(args["prompt"]), "music_length_ms": ms}
	if b, ok := args["instrumental"].(bool); ok && b {
		body["force_instrumental"] = true
	}
	b, _ := json.Marshal(withProviderOptions(body, call.ProviderOptions))
	r, err := j.call(http.MethodPost, "/v1/music?output_format=mp3_44100_128", b, "application/json")
	if err != nil {
		return MediaResult{}, err
	}
	f, err := j.fromBytes("audio", r.raw, "audio/mpeg")
	if err != nil {
		return MediaResult{}, err
	}
	return done([]MediaFile{f}, ""), nil
}

func (c *ElevenLabsMediaClient) Transcribe(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "transcribe", call)
	if err != nil {
		return MediaResult{}, err
	}
	file, err := j.readInput(argText(args["audio"]))
	if err != nil {
		return MediaResult{}, err
	}
	file.name = "file"
	parts := []mediaPart{{name: "model_id", value: c.cfg.modelFor("transcribe", "", "scribe_v1")}}
	if l, ok := args["language"]; ok {
		parts = append(parts, mediaPart{name: "language_code", value: argText(l)})
	}
	out, err := j.multipartCall("/v1/speech-to-text", append(parts, file), call.ProviderOptions)
	if err != nil {
		return MediaResult{}, err
	}
	text, ok := dotGet(out, "text").(string)
	if !ok {
		return MediaResult{}, j.fail("transcribe: no result at text")
	}
	return textResult(text, ""), nil
}

func (c *ElevenLabsMediaClient) CloneVoice(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "cloneVoice", call)
	if err != nil {
		return MediaResult{}, err
	}
	parts := []mediaPart{{name: "name", value: argText(args["name"])}}
	for _, s := range sampleList(args["samples"]) {
		f, err := j.readInput(s)
		if err != nil {
			return MediaResult{}, err
		}
		f.name = "files"
		parts = append(parts, f)
	}
	out, err := j.multipartCall("/v1/voices/add", parts, call.ProviderOptions)
	if err != nil {
		return MediaResult{}, err
	}
	id, ok := scalarText(dotGet(out, "voice_id"))
	if !ok {
		return MediaResult{}, j.fail("cloneVoice: no result at voice_id")
	}
	return textResult(id, ""), nil
}

// ---- OpenAI ----

// OpenAIMediaClient: image, video, speech, transcribe. Key OPENAI_API_KEY.
// URL points it at any OpenAI-compatible server.
type OpenAIMediaClient struct{ *apiMediaClient }

// NewOpenAIMediaClient builds the OpenAI client.
func NewOpenAIMediaClient(cfg MediaConfig) *OpenAIMediaClient {
	return &OpenAIMediaClient{&apiMediaClient{name: "openai", keyEnv: "OPENAI_API_KEY",
		keyHeader: "Authorization", keyPrefix: "Bearer ", defaultURL: "https://api.openai.com/v1",
		interval: 5000, cfg: cfg,
		verbs: map[string][]MediaArg{
			"image":      mediaArgsOf([]string{"prompt"}, "width", "height"),
			"video":      mediaArgsOf([]string{"prompt"}, "seconds"),
			"speech":     mediaArgsOf([]string{"text"}, "voice"),
			"transcribe": mediaArgsOf([]string{"audio"}, "language"),
		}}}
}

func (c *OpenAIMediaClient) Image(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "image", call)
	if err != nil {
		return MediaResult{}, err
	}
	model := c.cfg.modelFor("image", "image", "gpt-image-1-mini")
	w, hasW := argInt(args["width"])
	h, hasH := argInt(args["height"])
	switch {
	case hasW && !hasH:
		h = w
	case hasH && !hasW:
		w = h
	case !hasW && !hasH:
		w, h = 1024, 1024
	}
	body := map[string]any{"model": model, "prompt": argText(args["prompt"]), "n": 1, "size": fmt.Sprintf("%dx%d", w, h)}
	if strings.HasPrefix(model, "gpt-image") {
		body["quality"] = "low"
	}
	out, _, err := j.jsonCall(http.MethodPost, "/images/generations", withProviderOptions(body, call.ProviderOptions))
	if err != nil {
		return MediaResult{}, err
	}
	mime := "image/png"
	switch dotGet(out, "output_format") {
	case "jpeg":
		mime = "image/jpeg"
	case "webp":
		mime = "image/webp"
	}
	data, _ := dotGet(out, "data").([]any)
	var files []MediaFile
	for _, e := range data {
		var f MediaFile
		if b64, ok := dotGet(e, "b64_json").(string); ok && b64 != "" {
			f, err = j.fromBase64("image", b64, mime)
		} else if u, ok := dotGet(e, "url").(string); ok && u != "" {
			f, err = j.fromURL("image", u)
		} else {
			continue
		}
		if err != nil {
			return MediaResult{}, err
		}
		files = append(files, f)
	}
	if len(files) == 0 {
		return MediaResult{}, j.fail("image: no result at data")
	}
	res := done(files, "")
	res.Usage = mediaUsageOf(MediaUsage{InputTokens: usageInt(dotGet(out, "usage.input_tokens")),
		OutputTokens: usageInt(dotGet(out, "usage.output_tokens"))})
	return res, nil
}

func (c *OpenAIMediaClient) Speech(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "speech", call)
	if err != nil {
		return MediaResult{}, err
	}
	voice := "alloy"
	if v, ok := args["voice"]; ok {
		voice = argText(v)
	}
	body := map[string]any{"model": c.cfg.modelFor("speech", "", "gpt-4o-mini-tts"), "input": argText(args["text"]), "voice": voice}
	b, _ := json.Marshal(withProviderOptions(body, call.ProviderOptions))
	r, err := j.call(http.MethodPost, "/audio/speech", b, "application/json")
	if err != nil {
		return MediaResult{}, err
	}
	f, err := j.fromBytes("audio", r.raw, "audio/mpeg")
	if err != nil {
		return MediaResult{}, err
	}
	return done([]MediaFile{f}, ""), nil
}

func (c *OpenAIMediaClient) Transcribe(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "transcribe", call)
	if err != nil {
		return MediaResult{}, err
	}
	file, err := j.readInput(argText(args["audio"]))
	if err != nil {
		return MediaResult{}, err
	}
	file.name = "file"
	parts := []mediaPart{{name: "model", value: c.cfg.modelFor("transcribe", "", "gpt-4o-mini-transcribe")}}
	if l, ok := args["language"]; ok {
		parts = append(parts, mediaPart{name: "language", value: argText(l)})
	}
	out, err := j.multipartCall("/audio/transcriptions", append(parts, file), call.ProviderOptions)
	if err != nil {
		return MediaResult{}, err
	}
	text, ok := dotGet(out, "text").(string)
	if !ok {
		return MediaResult{}, j.fail("transcribe: no result at text")
	}
	return textResult(text, ""), nil
}

func (c *OpenAIMediaClient) Video(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "video", call)
	if err != nil {
		return MediaResult{}, err
	}
	seconds := "4"
	if v, ok := args["seconds"]; ok {
		seconds = argText(v)
	}
	body := map[string]any{"model": c.cfg.modelFor("video", "", "sora-2"), "prompt": argText(args["prompt"]), "seconds": seconds}
	out, _, err := j.jsonCall(http.MethodPost, "/videos", withProviderOptions(body, call.ProviderOptions))
	if err != nil {
		return MediaResult{}, err
	}
	id, ok := jobIDText(dotGet(out, "id"))
	if !ok {
		return MediaResult{}, j.fail("video: no job id at id")
	}
	j.jobID = id
	interval := c.cfg.intervalMs(c.interval)
	for {
		if err := j.wait(interval); err != nil {
			return MediaResult{}, err
		}
		st, _, err := j.jsonCall(http.MethodGet, "/videos/"+percentEncode(id), nil)
		if err != nil {
			return MediaResult{}, err
		}
		status, _ := scalarText(dotGet(st, "status"))
		j.report(status)
		if status == "completed" {
			break
		}
		if status == "failed" {
			if msg, ok := scalarText(dotGet(st, "error.message")); ok {
				return MediaResult{}, j.fail(fmt.Sprintf("job %s failed: %s", id, msg))
			}
			return MediaResult{}, j.fail(fmt.Sprintf("job %s failed", id))
		}
	}
	r, err := j.call(http.MethodGet, "/videos/"+percentEncode(id)+"/content", nil, "")
	if err != nil {
		return MediaResult{}, err
	}
	f, err := j.fromBytes("video", r.raw, "video/mp4")
	if err != nil {
		return MediaResult{}, err
	}
	return done([]MediaFile{f}, id), nil
}

// ---- OpenRouter ----

// OpenRouterMediaClient: image through chat completions with image output.
// Key OPENROUTER_API_KEY.
type OpenRouterMediaClient struct{ *apiMediaClient }

// NewOpenRouterMediaClient builds the OpenRouter client.
func NewOpenRouterMediaClient(cfg MediaConfig) *OpenRouterMediaClient {
	return &OpenRouterMediaClient{&apiMediaClient{name: "openrouter", keyEnv: "OPENROUTER_API_KEY",
		keyHeader: "Authorization", keyPrefix: "Bearer ", defaultURL: "https://openrouter.ai/api/v1", cfg: cfg,
		verbs: map[string][]MediaArg{"image": mediaArgsOf([]string{"prompt"})}}}
}

func (c *OpenRouterMediaClient) Image(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, "image", call)
	if err != nil {
		return MediaResult{}, err
	}
	body := map[string]any{"model": c.cfg.modelFor("image", "image", "google/gemini-2.5-flash-image"),
		"modalities": []string{"image", "text"},
		"messages":   []any{map[string]any{"role": "user", "content": argText(args["prompt"])}}}
	out, _, err := j.jsonCall(http.MethodPost, "/chat/completions", withProviderOptions(body, call.ProviderOptions))
	if err != nil {
		return MediaResult{}, err
	}
	jobID, _ := jobIDText(dotGet(out, "id"))
	j.jobID = jobID
	var text strings.Builder
	var files []MediaFile
	choices, _ := dotGet(out, "choices").([]any)
	for _, ch := range choices {
		if s, ok := dotGet(ch, "message.content").(string); ok {
			text.WriteString(s)
		}
		imgs, _ := dotGet(ch, "message.images").([]any)
		for _, im := range imgs {
			u, _ := dotGet(im, "image_url.url").(string)
			if u == "" {
				continue
			}
			var f MediaFile
			if strings.HasPrefix(u, "data:") {
				f, err = j.fromDataURL("image", u)
			} else {
				f, err = j.fromURL("image", u)
			}
			if err != nil {
				return MediaResult{}, err
			}
			files = append(files, f)
		}
	}
	if len(files) == 0 {
		msg := "image: no image in response"
		if text.Len() > 0 {
			msg += ": " + capRunes(text.String(), 200)
		}
		return MediaResult{}, j.fail(msg)
	}
	res := done(files, jobID)
	res.Text = text.String()
	res.Usage = mediaUsageOf(MediaUsage{InputTokens: usageInt(dotGet(out, "usage.prompt_tokens")),
		OutputTokens: usageInt(dotGet(out, "usage.completion_tokens")), CostUsd: usageFloat(dotGet(out, "usage.cost"))})
	return res, nil
}

// ---- fal ----

// FalMediaClient: the fal queue API for any hosted model id — image and video.
// Key FAL_API_KEY, header "Authorization: Key <key>".
type FalMediaClient struct{ *apiMediaClient }

// NewFalMediaClient builds the fal client.
func NewFalMediaClient(cfg MediaConfig) *FalMediaClient {
	return &FalMediaClient{&apiMediaClient{name: "fal", keyEnv: "FAL_API_KEY",
		keyHeader: "Authorization", keyPrefix: "Key ", defaultURL: "https://queue.fal.run",
		interval: 500, cfg: cfg,
		verbs: map[string][]MediaArg{
			"image": mediaArgsOf([]string{"prompt"}, "width", "height", "seed"),
			"video": mediaArgsOf([]string{"prompt"}, "image", "seconds", "aspectRatio"),
		}}}
}

func (c *FalMediaClient) Image(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	model := c.cfg.modelFor("image", "image", "fal-ai/flux/schnell")
	input := map[string]any{"prompt": args["prompt"], "num_images": 1}
	if strings.Contains(model, "schnell") {
		input["image_size"] = "square"
		input["num_inference_steps"] = 4
	}
	w, hasW := args["width"]
	h, hasH := args["height"]
	if hasW || hasH {
		if !hasW {
			w = h
		}
		if !hasH {
			h = w
		}
		input["image_size"] = map[string]any{"width": w, "height": h}
	}
	if s, ok := args["seed"]; ok {
		input["seed"] = s
	}
	return c.queue(ctx, "image", model, input, call)
}

func (c *FalMediaClient) Video(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	img, hasImg := args["image"]
	def := "fal-ai/kling-video/v2.1/standard/text-to-video"
	if hasImg {
		def = "fal-ai/kling-video/v2.1/standard/image-to-video"
	}
	model := c.cfg.modelFor("video", "", def)
	seconds := "5"
	if v, ok := args["seconds"]; ok {
		seconds = argText(v)
	}
	input := map[string]any{"prompt": args["prompt"], "duration": seconds}
	if hasImg {
		input["image_url"] = img
	}
	if ar, ok := args["aspectRatio"]; ok {
		input["aspect_ratio"] = ar
	}
	return c.queue(ctx, "video", model, input, call)
}

// queue: submit, poll status_url, fetch response_url, gather the result URLs.
func (c *FalMediaClient) queue(ctx context.Context, verb, model string, input map[string]any, call MediaCall) (MediaResult, error) {
	j, err := c.begin(ctx, verb, call)
	if err != nil {
		return MediaResult{}, err
	}
	out, _, err := j.jsonCall(http.MethodPost, "/"+strings.TrimLeft(model, "/"), withProviderOptions(input, call.ProviderOptions))
	if err != nil {
		return MediaResult{}, err
	}
	id, ok := jobIDText(dotGet(out, "request_id"))
	if !ok {
		return MediaResult{}, j.fail(verb + ": no job id at request_id")
	}
	j.jobID = id
	statusURL, _ := dotGet(out, "status_url").(string)
	if statusURL == "" {
		statusURL = "/" + model + "/requests/" + percentEncode(id) + "/status"
	}
	responseURL, _ := dotGet(out, "response_url").(string)
	if responseURL == "" {
		responseURL = "/" + model + "/requests/" + percentEncode(id)
	}
	interval := c.cfg.intervalMs(c.interval)
	for {
		if err := j.wait(interval); err != nil {
			return MediaResult{}, err
		}
		st, _, err := j.jsonCall(http.MethodGet, statusURL, nil)
		if err != nil {
			return MediaResult{}, err
		}
		status, _ := scalarText(dotGet(st, "status"))
		if status == "COMPLETED" { // D1: done, an error field is ignored
			j.report(status)
			break
		}
		if e := dotGet(st, "error"); e != nil {
			msg, isStr := e.(string)
			if !isStr {
				b, _ := json.Marshal(e)
				msg = string(b)
			}
			if msg != "" {
				return MediaResult{}, j.fail(fmt.Sprintf("job %s failed: %s", id, msg))
			}
		}
		j.report(status)
	}
	res, _, err := j.jsonCall(http.MethodGet, responseURL, nil)
	if err != nil {
		return MediaResult{}, err
	}
	var urls []string
	if imgs, ok := dotGet(res, "images").([]any); ok {
		for _, im := range imgs {
			if u, ok := dotGet(im, "url").(string); ok && u != "" {
				urls = append(urls, u)
			}
		}
	}
	for _, p := range []string{"image.url", "video.url", "audio.url", "audio_file.url"} {
		if u, ok := dotGet(res, p).(string); ok && u != "" {
			urls = append(urls, u)
		}
	}
	if len(urls) == 0 {
		return MediaResult{}, j.fail(verb + ": no result in response")
	}
	kind := map[string]string{"image": "image", "video": "video"}[verb]
	var files []MediaFile
	for _, u := range urls {
		f, err := j.fromURL(kind, u)
		if err != nil {
			return MediaResult{}, err
		}
		files = append(files, f)
	}
	return done(files, id), nil
}
