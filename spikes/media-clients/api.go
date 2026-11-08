package main

// ApiMediaClient base: HTTP + JSON + poll loop. The named clients below are each a few
// request builders on top of it.

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

type ApiBase struct {
	Cfg    Config
	header func(*http.Request)
}

func (b ApiBase) do(ctx context.Context, method, url string, body any, out any) ([]byte, http.Header, error) {
	var rd io.Reader
	ct := ""
	switch v := body.(type) {
	case nil:
	case *multipartBody:
		rd, ct = &v.buf, v.ct
	default:
		j, _ := json.Marshal(v)
		rd, ct = bytes.NewReader(j), "application/json"
	}
	req, err := http.NewRequestWithContext(ctx, method, url, rd)
	if err != nil {
		return nil, nil, err
	}
	if ct != "" {
		req.Header.Set("Content-Type", ct)
	}
	b.header(req)
	resp, err := b.Cfg.HTTP.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, nil, err
	}
	if resp.StatusCode/100 != 2 {
		msg := fmt.Sprintf("HTTP %d: %s", resp.StatusCode, trunc(string(data), 400))
		low := strings.ToLower(string(data))
		if resp.StatusCode == 402 || (resp.StatusCode == 429 && (strings.Contains(low, "quota") || strings.Contains(low, "credit"))) {
			return nil, nil, unavailable(msg)
		}
		return nil, nil, fmt.Errorf("%s", msg)
	}
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			return data, resp.Header, fmt.Errorf("decode: %w: %s", err, trunc(string(data), 200))
		}
	}
	return data, resp.Header, nil
}

// poll calls check until it says done; honours ctx deadline/cancel and reports progress.
func (b ApiBase) poll(ctx context.Context, check func() (status string, done bool, err error)) (string, error) {
	start := time.Now()
	for {
		st, done, err := check()
		report(ctx, st, start)
		if err != nil || done {
			return st, err
		}
		select {
		case <-ctx.Done():
			if ctx.Err() == context.DeadlineExceeded {
				return "timeout", ctx.Err()
			}
			return "cancelled", ctx.Err()
		case <-time.After(time.Duration(b.Cfg.Loop.IntervalMs) * time.Millisecond):
		}
	}
}

type multipartBody struct {
	buf bytes.Buffer
	ct  string
}

func newMultipart(fields map[string]string, files map[string]string) (*multipartBody, error) {
	m := &multipartBody{}
	w := multipart.NewWriter(&m.buf)
	for k, v := range fields {
		w.WriteField(k, v)
	}
	for k, p := range files {
		data, err := os.ReadFile(p)
		if err != nil {
			return nil, err
		}
		fw, _ := w.CreateFormFile(k, p)
		fw.Write(data)
	}
	w.Close()
	m.ct = w.FormDataContentType()
	return m, nil
}

func dig(v any, path string) any {
	for _, k := range strings.Split(path, ".") {
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

func trunc(s string, n int) string {
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}

func failStatus(err error) string {
	if errors.Is(err, ErrUnavailable) {
		return "unavailable"
	}
	return "failed"
}

func done(files []File, job string) MediaResult {
	return MediaResult{Files: files, JobID: job, Status: "completed"}
}

// ============================ ElevenLabs ============================
// speech, music, transcribe, cloneVoice. Bytes come back in the body (speech/music).

type ElevenLabsMediaClient struct {
	Unsupported
	ApiBase
}

func NewElevenLabs(c Config) *ElevenLabsMediaClient {
	c = c.withDefaults("ELEVENLABS_API_KEY", "https://api.elevenlabs.io", "eleven_flash_v2_5", "download")
	k := c.Key
	return &ElevenLabsMediaClient{supportsOf(VSpeech, VMusic, VTranscribe, VCloneVoice),
		ApiBase{c, func(r *http.Request) { r.Header.Set("xi-api-key", k) }}}
}
func (*ElevenLabsMediaClient) Name() string { return "elevenlabs" }

// resolveVoice: an id passes through; a name is looked up via GET /v2/voices?search= (free).
func (e *ElevenLabsMediaClient) resolveVoice(ctx context.Context, v string) (string, error) {
	if v == "" {
		return "JBFqnCBsd6RMkjVDRZzb", nil // "George", the docs' default example voice
	}
	var out struct {
		Voices []struct {
			VoiceID string `json:"voice_id"`
			Name    string `json:"name"`
		} `json:"voices"`
	}
	if _, _, err := e.do(ctx, "GET", e.Cfg.URL+"/v2/voices?page_size=100&search="+urlq(v), nil, &out); err != nil {
		return "", err
	}
	for _, x := range out.Voices {
		if strings.EqualFold(x.Name, v) || x.VoiceID == v {
			return x.VoiceID, nil
		}
	}
	if len(v) == 20 && !strings.Contains(v, " ") {
		return v, nil // looks like an id
	}
	return "", fmt.Errorf("voice %q not found", v)
}

func (e *ElevenLabsMediaClient) Speech(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := e.Cfg.withLoop(ctx)
	defer cancel()
	vid, err := e.resolveVoice(ctx, a.Voice)
	if err != nil {
		return MediaResult{}, err
	}
	data, h, err := e.do(ctx, "POST", e.Cfg.URL+"/v1/text-to-speech/"+vid+"?output_format=mp3_22050_32",
		map[string]any{"text": a.Text, "model_id": e.Cfg.Model}, nil)
	if err != nil {
		return MediaResult{}, err
	}
	f, err := e.Cfg.fromBytes("elevenlabs-speech", data, "audio/mpeg")
	return done([]File{f}, h.Get("request-id")), err
}

func (e *ElevenLabsMediaClient) Music(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := e.Cfg.withLoop(ctx)
	defer cancel()
	ms := a.Seconds * 1000
	if ms == 0 {
		ms = 10000
	}
	data, _, err := e.do(ctx, "POST", e.Cfg.URL+"/v1/music?output_format=mp3_44100_128",
		map[string]any{"prompt": a.Prompt, "music_length_ms": ms}, nil)
	if err != nil {
		return MediaResult{}, err
	}
	f, err := e.Cfg.fromBytes("elevenlabs-music", data, "audio/mpeg")
	return done([]File{f}, ""), err
}

func (e *ElevenLabsMediaClient) Transcribe(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := e.Cfg.withLoop(ctx)
	defer cancel()
	mp, err := newMultipart(map[string]string{"model_id": "scribe_v1"}, map[string]string{"file": a.Audio})
	if err != nil {
		return MediaResult{}, err
	}
	var out struct{ Text string }
	if _, _, err := e.do(ctx, "POST", e.Cfg.URL+"/v1/speech-to-text", mp, &out); err != nil {
		return MediaResult{}, err
	}
	return MediaResult{Text: out.Text, Status: "completed"}, nil
}

func (e *ElevenLabsMediaClient) CloneVoice(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := e.Cfg.withLoop(ctx)
	defer cancel()
	mp, err := newMultipart(map[string]string{"name": a.Name}, map[string]string{"files": a.Audio})
	if err != nil {
		return MediaResult{}, err
	}
	var out struct {
		VoiceID string `json:"voice_id"`
	}
	if _, _, err := e.do(ctx, "POST", e.Cfg.URL+"/v1/voices/add", mp, &out); err != nil {
		return MediaResult{}, err
	}
	return MediaResult{Text: out.VoiceID, Status: "completed"}, nil
}

// ============================ OpenAI ============================
// image (b64), speech (bytes), transcribe (json), video (Sora: async job + content download).
// URL override → any OpenAI-compatible server.

type OpenAIMediaClient struct {
	Unsupported
	ApiBase
}

func NewOpenAI(c Config) *OpenAIMediaClient {
	c = c.withDefaults("OPENAI_API_KEY", "https://api.openai.com/v1", "gpt-image-1-mini", "download")
	k := c.Key
	return &OpenAIMediaClient{supportsOf(VImage, VVideo, VSpeech, VTranscribe),
		ApiBase{c, func(r *http.Request) { r.Header.Set("Authorization", "Bearer "+k) }}}
}
func (*OpenAIMediaClient) Name() string { return "openai" }

func (o *OpenAIMediaClient) Image(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := o.Cfg.withLoop(ctx)
	defer cancel()
	size := a.Size
	if size == "" {
		size = "1024x1024"
	}
	var out struct {
		Data []struct {
			B64 string `json:"b64_json"`
			URL string `json:"url"`
		} `json:"data"`
		OutputFormat string `json:"output_format"`
	}
	body := map[string]any{"model": o.Cfg.Model, "prompt": a.Prompt, "n": 1, "size": size}
	if strings.HasPrefix(o.Cfg.Model, "gpt-image") {
		body["quality"] = "low"
	}
	if _, _, err := o.do(ctx, "POST", o.Cfg.URL+"/images/generations", body, &out); err != nil {
		return MediaResult{}, err
	}
	var files []File
	for _, d := range out.Data {
		var f File
		var err error
		if d.B64 != "" {
			raw, _ := base64.StdEncoding.DecodeString(d.B64)
			mt := "image/png"
			if out.OutputFormat != "" {
				mt = "image/" + out.OutputFormat
			}
			f, err = o.Cfg.fromBytes("openai-image", raw, mt)
		} else {
			f, err = o.Cfg.fromURL(ctx, "openai-image", d.URL, "image/png")
		}
		if err != nil {
			return MediaResult{}, err
		}
		files = append(files, f)
	}
	return done(files, ""), nil
}

func (o *OpenAIMediaClient) Speech(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := o.Cfg.withLoop(ctx)
	defer cancel()
	voice := a.Voice
	if voice == "" {
		voice = "alloy"
	}
	data, _, err := o.do(ctx, "POST", o.Cfg.URL+"/audio/speech",
		map[string]any{"model": "gpt-4o-mini-tts", "input": a.Text, "voice": voice}, nil)
	if err != nil {
		return MediaResult{}, err
	}
	f, err := o.Cfg.fromBytes("openai-speech", data, "audio/mpeg")
	return done([]File{f}, ""), err
}

func (o *OpenAIMediaClient) Transcribe(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := o.Cfg.withLoop(ctx)
	defer cancel()
	mp, err := newMultipart(map[string]string{"model": "gpt-4o-mini-transcribe"}, map[string]string{"file": a.Audio})
	if err != nil {
		return MediaResult{}, err
	}
	var out struct{ Text string }
	if _, _, err := o.do(ctx, "POST", o.Cfg.URL+"/audio/transcriptions", mp, &out); err != nil {
		return MediaResult{}, err
	}
	return MediaResult{Text: out.Text, Status: "completed"}, nil
}

// Video (Sora): POST /videos → poll GET /videos/{id} → GET /videos/{id}/content. Compile-only.
func (o *OpenAIMediaClient) Video(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := o.Cfg.withLoop(ctx)
	defer cancel()
	var job struct{ ID, Status string }
	if _, _, err := o.do(ctx, "POST", o.Cfg.URL+"/videos", map[string]any{"model": "sora-2", "prompt": a.Prompt, "seconds": "4"}, &job); err != nil {
		return MediaResult{}, err
	}
	st, err := o.poll(ctx, func() (string, bool, error) {
		var s struct {
			Status string
			Error  struct{ Message string }
		}
		if _, _, err := o.do(ctx, "GET", o.Cfg.URL+"/videos/"+job.ID, nil, &s); err != nil {
			return "", false, err
		}
		if s.Status == "failed" {
			return s.Status, true, fmt.Errorf("video failed: %s", s.Error.Message)
		}
		return s.Status, s.Status == "completed", nil
	})
	if err != nil {
		return MediaResult{JobID: job.ID, Status: st}, err
	}
	data, _, err := o.do(ctx, "GET", o.Cfg.URL+"/videos/"+job.ID+"/content", nil, nil)
	if err != nil {
		return MediaResult{}, err
	}
	f, err := o.Cfg.fromBytes("openai-video", data, "video/mp4")
	return done([]File{f}, job.ID), err
}

// ============================ OpenRouter ============================
// image only, via chat completions with modalities [image,text]; image arrives as a data URL.

type OpenRouterMediaClient struct {
	Unsupported
	ApiBase
}

func NewOpenRouter(c Config) *OpenRouterMediaClient {
	c = c.withDefaults("OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", "google/gemini-2.5-flash-image", "download")
	k := c.Key
	return &OpenRouterMediaClient{supportsOf(VImage),
		ApiBase{c, func(r *http.Request) { r.Header.Set("Authorization", "Bearer "+k) }}}
}
func (*OpenRouterMediaClient) Name() string { return "openrouter" }

func (o *OpenRouterMediaClient) Image(ctx context.Context, a Args) (MediaResult, error) {
	ctx, cancel := o.Cfg.withLoop(ctx)
	defer cancel()
	var out struct {
		ID      string `json:"id"`
		Choices []struct {
			Message struct {
				Content string `json:"content"`
				Images  []struct {
					ImageURL struct {
						URL string `json:"url"`
					} `json:"image_url"`
				} `json:"images"`
			} `json:"message"`
		} `json:"choices"`
	}
	body := map[string]any{"model": o.Cfg.Model, "modalities": []string{"image", "text"},
		"messages": []any{map[string]any{"role": "user", "content": a.Prompt}}}
	if _, _, err := o.do(ctx, "POST", o.Cfg.URL+"/chat/completions", body, &out); err != nil {
		return MediaResult{}, err
	}
	var files []File
	text := ""
	for _, ch := range out.Choices {
		text += ch.Message.Content
		for _, im := range ch.Message.Images {
			raw, mt, err := decodeDataURL(im.ImageURL.URL)
			var f File
			if err != nil { // a real URL
				f, err = o.Cfg.fromURL(ctx, "openrouter-image", im.ImageURL.URL, "image/png")
			} else {
				f, err = o.Cfg.fromBytes("openrouter-image", raw, mt)
			}
			if err != nil {
				return MediaResult{}, err
			}
			files = append(files, f)
		}
	}
	if len(files) == 0 {
		return MediaResult{Status: "failed", Text: text}, fmt.Errorf("no image in response: %s", trunc(text, 200))
	}
	r := done(files, out.ID)
	r.Text = text
	return r, nil
}

// ============================ fal ============================
// Any hosted model by id via the queue API: submit → status_url poll → response_url.

type FalMediaClient struct {
	Unsupported
	ApiBase
	VideoModel string
}

func NewFal(c Config) *FalMediaClient {
	c = c.withDefaults("FAL_API_KEY", "https://queue.fal.run", "fal-ai/flux/schnell", "download")
	if c.Loop.IntervalMs == 2000 {
		c.Loop.IntervalMs = 500
	}
	k := c.Key
	return &FalMediaClient{supportsOf(VImage, VVideo),
		ApiBase{c, func(r *http.Request) { r.Header.Set("Authorization", "Key "+k) }},
		"fal-ai/kling-video/v2.1/standard/text-to-video"}
}
func (*FalMediaClient) Name() string { return "fal" }

func (f *FalMediaClient) run(ctx context.Context, model string, input map[string]any, prefix, fallback string) (MediaResult, error) {
	ctx, cancel := f.Cfg.withLoop(ctx)
	defer cancel()
	var sub struct {
		RequestID   string `json:"request_id"`
		StatusURL   string `json:"status_url"`
		ResponseURL string `json:"response_url"`
	}
	if _, _, err := f.do(ctx, "POST", f.Cfg.URL+"/"+model, input, &sub); err != nil {
		return MediaResult{}, err
	}
	st, err := f.poll(ctx, func() (string, bool, error) {
		var s struct {
			Status string `json:"status"`
			Error  string `json:"error"`
		}
		if _, _, err := f.do(ctx, "GET", sub.StatusURL, nil, &s); err != nil {
			return "", false, err
		}
		if s.Error != "" {
			return "failed", true, fmt.Errorf("fal: %s", s.Error)
		}
		return s.Status, s.Status == "COMPLETED", nil
	})
	if err != nil {
		return MediaResult{JobID: sub.RequestID, Status: st}, err
	}
	var out map[string]any
	if _, _, err := f.do(ctx, "GET", sub.ResponseURL, nil, &out); err != nil {
		return MediaResult{}, err
	}
	// Output shape varies per model: images[].url, image.url, video.url, audio.url.
	var urls []string
	if imgs, ok := out["images"].([]any); ok {
		for _, im := range imgs {
			if u, _ := dig(im, "url").(string); u != "" {
				urls = append(urls, u)
			}
		}
	}
	for _, k := range []string{"image.url", "video.url", "audio.url", "audio_file.url"} {
		if u, _ := dig(out, k).(string); u != "" {
			urls = append(urls, u)
		}
	}
	var files []File
	for _, u := range urls {
		fl, err := f.Cfg.fromURL(ctx, prefix, u, fallback)
		if err != nil {
			return MediaResult{}, err
		}
		files = append(files, fl)
	}
	return done(files, sub.RequestID), nil
}

func (f *FalMediaClient) Image(ctx context.Context, a Args) (MediaResult, error) {
	in := map[string]any{"prompt": a.Prompt, "num_images": 1}
	if strings.Contains(f.Cfg.Model, "schnell") {
		in["image_size"] = "square"
		in["num_inference_steps"] = 4
	}
	return f.run(ctx, f.Cfg.Model, in, "fal-image", "image/jpeg")
}

func (f *FalMediaClient) Video(ctx context.Context, a Args) (MediaResult, error) { // compile-only
	return f.run(ctx, f.VideoModel, map[string]any{"prompt": a.Prompt, "duration": "5"}, "fal-video", "video/mp4")
}

// ============================ A2E ============================
// image, video, speech, talk. Compile-only here (proven live in spikes/media-a2e).

type A2EMediaClient struct {
	Unsupported
	ApiBase
}

func NewA2E(c Config) *A2EMediaClient {
	c = c.withDefaults("A2E_API_KEY", "https://api.a2e.ai/api", "a2e", "download")
	if c.Loop.IntervalMs == 2000 {
		c.Loop.IntervalMs = 3000
	}
	k := c.Key
	return &A2EMediaClient{supportsOf(VImage, VVideo, VSpeech, VTalk),
		ApiBase{c, func(r *http.Request) { r.Header.Set("Authorization", "Bearer "+k) }}}
}
func (*A2EMediaClient) Name() string { return "a2e" }

func (x *A2EMediaClient) job(ctx context.Context, startPath, pollPath, idPath, resultPath string, body map[string]any, prefix, fb string) (MediaResult, error) {
	ctx, cancel := x.Cfg.withLoop(ctx)
	defer cancel()
	var s map[string]any
	if _, _, err := x.do(ctx, "POST", x.Cfg.URL+startPath, body, &s); err != nil {
		return MediaResult{}, err
	}
	if c, _ := s["code"].(float64); c != 0 { // HTTP 200 + code != 0 = error envelope
		return MediaResult{Status: "failed"}, fmt.Errorf("a2e: %v", s["msg"])
	}
	id, _ := dig(s, idPath).(string)
	if id == "" {
		return MediaResult{Status: "failed"}, fmt.Errorf("a2e: empty job id")
	}
	var last map[string]any
	st, err := x.poll(ctx, func() (string, bool, error) {
		last = nil
		if _, _, err := x.do(ctx, "GET", x.Cfg.URL+strings.Replace(pollPath, "{id}", id, 1), nil, &last); err != nil {
			return "", false, err
		}
		st, _ := dig(last, "data.current_status").(string)
		switch st {
		case "completed":
			return st, true, nil
		case "failed", "blocked", "canceled":
			return st, true, fmt.Errorf("a2e: %v", dig(last, "data.failed_message"))
		}
		return st, false, nil
	})
	if err != nil {
		return MediaResult{JobID: id, Status: st}, err
	}
	var urls []string
	switch v := dig(last, resultPath).(type) {
	case string:
		urls = []string{v}
	case []any:
		for _, u := range v {
			if s, ok := u.(string); ok {
				urls = append(urls, s)
			}
		}
	}
	var files []File
	for _, u := range urls {
		f, err := x.Cfg.fromURL(ctx, prefix, u, fb)
		if err != nil {
			return MediaResult{}, err
		}
		files = append(files, f)
	}
	return done(files, id), nil
}

func (x *A2EMediaClient) Image(ctx context.Context, a Args) (MediaResult, error) {
	return x.job(ctx, "/v1/userText2Image/start", "/v1/userText2Image/{id}", "data.0._id", "data.image_urls",
		map[string]any{"name": "toolnexus", "model_type": "a2e", "prompt": a.Prompt}, "a2e-image", "image/jpeg")
}
func (x *A2EMediaClient) Video(ctx context.Context, a Args) (MediaResult, error) {
	return x.job(ctx, "/v1/userImage2Video/start", "/v1/userImage2Video/{id}", "data._id", "data.result_url",
		map[string]any{"name": "toolnexus", "generation_mode": "image-to-video", "negative_prompt": "blurry, distorted, low quality",
			"image_url": a.Image, "prompt": a.Prompt, "video_time": a.Seconds}, "a2e-video", "video/mp4")
}
func (x *A2EMediaClient) Speech(ctx context.Context, a Args) (MediaResult, error) {
	return x.job(ctx, "/v1/seedAudio/start", "/v1/seedAudio/{id}", "data._id", "data.result_url",
		map[string]any{"text_prompt": a.Text, "references": []any{map[string]any{"speaker": a.Voice}}}, "a2e-speech", "audio/wav")
}
func (x *A2EMediaClient) Talk(ctx context.Context, a Args) (MediaResult, error) {
	return x.job(ctx, "/v1/talkingPhoto/start", "/v1/talkingPhoto/{id}", "data._id", "data.result_url",
		map[string]any{"name": "toolnexus", "image_url": a.Image, "audio_url": a.Audio}, "a2e-talk", "video/mp4")
}
