// Media generation (SPEC §7G) — the E1 example contract, identical in every port.
//
//	go run ./examples/media
//
// Clients, in this order, each only when present: Grok CLI, Codex CLI (on PATH),
// ElevenLabs (ELEVENLABS_API_KEY), then the paid fallbacks OpenAI (OPENAI_API_KEY),
// fal (FAL_API_KEY), A2E (A2E_API_KEY). Steps:
//
//  1. image "a red apple on a white table" (first client that can serve it)
//  2. speech through ElevenLabs — voice from MEDIA_VOICE, else the default
//  3. tool face: list media.Tools() and call generate_image, as an LLM would
//  4. video through Grok, only with MEDIA_VIDEO=1
//  5. paid video (OpenAI / fal) and talk (A2E), only with MEDIA_PAID=1
//
// Paid image clients keep their smallest / lowest-quality defaults. Each result
// prints client, verb, path or URL, mime type, size and provider-reported usage.
// Exit 1 if any attempted step errors; exit 0 with a hint when no client is
// available. Keys are read from the environment at call time and never printed.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strings"

	"github.com/muthuishere/toolnexus/golang"
)

const prompt = "a red apple on a white table"

func main() {
	gather := toolnexus.MediaGather{Mode: "download"} // saved under <os temp>/toolnexus-media
	cfg := toolnexus.MediaConfig{Gather: gather}
	has := func(env string) bool { return os.Getenv(env) != "" }
	onPath := func(cli string) bool { _, err := exec.LookPath(cli); return err == nil }

	var clients, paid []toolnexus.MediaClient
	var grok toolnexus.MediaClient
	if onPath("grok") {
		grok = toolnexus.NewGrokMediaClient(cfg)
		clients = append(clients, grok)
	}
	if onPath("codex") {
		clients = append(clients, toolnexus.NewCodexMediaClient(cfg))
	}
	if has("ELEVENLABS_API_KEY") {
		clients = append(clients, toolnexus.NewElevenLabsMediaClient(cfg))
	}
	if has("OPENAI_API_KEY") {
		c := toolnexus.NewOpenAIMediaClient(cfg) // gpt-image-1-mini, 1024x1024, quality low
		clients, paid = append(clients, c), append(paid, c)
	}
	if has("FAL_API_KEY") {
		c := toolnexus.NewFalMediaClient(cfg)
		clients, paid = append(clients, c), append(paid, c)
	}
	var a2e toolnexus.MediaClient
	if has("A2E_API_KEY") {
		a2e = toolnexus.NewA2EMediaClient(cfg)
		clients, paid = append(clients, a2e), append(paid, a2e)
	}
	if len(clients) == 0 {
		fmt.Println("no media client available — install/log in the grok or codex CLI, or set " +
			"ELEVENLABS_API_KEY, OPENAI_API_KEY, FAL_API_KEY or A2E_API_KEY (no network call made)")
		return
	}
	names := make([]string, len(clients))
	for i, c := range clients {
		names[i] = c.Name()
	}
	fmt.Println("clients:", strings.Join(names, ", "))

	media := toolnexus.MustCreateMedia(clients...)
	ctx := context.Background()
	failed := false
	report := func(step string, res toolnexus.MediaResult, client string, err error) {
		if err != nil {
			failed = true
			fmt.Printf("%s: ERROR %v\n", step, err)
			return
		}
		for _, f := range res.Files {
			loc := f.Path
			if loc == "" {
				loc = f.URL
			}
			fmt.Printf("%s: client=%s verb=%s file=%s mime=%s size=%d%s\n",
				step, client, step, loc, f.MimeType, fileSize(f), usageText(res.Usage))
		}
	}
	// 1. image
	var image, speech toolnexus.MediaResult
	if media.Supports("image") {
		var err error
		image, err = media.Image(ctx, toolnexus.ImageInput{Prompt: prompt})
		report("image", image, servedBy(clients, image, "image"), err)
	}

	// 2. speech (ElevenLabs)
	if has("ELEVENLABS_API_KEY") {
		var err error
		speech, err = media.Speech(ctx, toolnexus.SpeechInput{Text: "Hello from toolnexus.", Voice: os.Getenv("MEDIA_VOICE")})
		report("speech", speech, "elevenlabs", err)
	}

	// 3. tool face
	if media.Supports("image") {
		var tn []string
		for _, t := range media.Tools() {
			tn = append(tn, t.Name)
		}
		fmt.Println("tools:", strings.Join(tn, ", "))
		for _, t := range media.Tools() {
			if t.Name != "generate_image" {
				continue
			}
			tr, err := t.Execute(map[string]any{"prompt": prompt}, nil)
			if err == nil && tr.IsError {
				err = fmt.Errorf("%s", tr.Output)
			}
			if err != nil {
				failed = true
				fmt.Println("generate_image: ERROR", err)
				continue
			}
			size := 0
			for _, p := range tr.Parts {
				if b, e := base64.StdEncoding.DecodeString(p.Data); e == nil {
					size += len(b)
				}
			}
			fmt.Printf("generate_image: client=%v verb=image output=%q parts=%d size=%d%s\n",
				tr.Metadata["client"], tr.Output, len(tr.Parts), size, usageAny(tr.Metadata["usage"]))
		}
	}

	// 4. Grok video (opt-in)
	if os.Getenv("MEDIA_VIDEO") == "1" && grok != nil {
		res, err := toolnexus.MustCreateMedia(grok).Video(ctx, toolnexus.VideoInput{Prompt: prompt})
		report("video", res, "grok", err)
	}

	// 5. paid video / talk (opt-in)
	if os.Getenv("MEDIA_PAID") == "1" && len(paid) > 0 {
		pm := toolnexus.MustCreateMedia(paid...)
		if pm.Supports("video") {
			res, err := pm.Video(ctx, toolnexus.VideoInput{Prompt: prompt, Seconds: 4})
			report("video", res, servedBy(paid, res, "video"), err)
		}
		if a2e != nil && len(image.Files) > 0 && len(speech.Files) > 0 {
			res, err := toolnexus.MustCreateMedia(a2e).Talk(ctx, toolnexus.TalkInput{Image: image.Files[0].Path, Audio: speech.Files[0].Path})
			report("talk", res, "a2e", err)
		}
	}

	if failed {
		os.Exit(1)
	}
}

// servedBy names the client whose saved-file name prefix matches (download mode
// names files <client>-<verb>-…); CLI url results fall back to the first supporting client.
func servedBy(clients []toolnexus.MediaClient, res toolnexus.MediaResult, verb string) string {
	for _, f := range res.Files {
		base := f.Path[strings.LastIndex(f.Path, "/")+1:]
		for _, c := range clients {
			if strings.HasPrefix(base, c.Name()+"-"+verb+"-") {
				return c.Name()
			}
		}
	}
	for _, c := range clients {
		if c.Supports(verb) {
			return c.Name()
		}
	}
	return "?"
}

func fileSize(f toolnexus.MediaFile) int64 {
	if f.Path != "" {
		if fi, err := os.Stat(f.Path); err == nil {
			return fi.Size()
		}
	}
	if b, err := base64.StdEncoding.DecodeString(f.Data); err == nil {
		return int64(len(b))
	}
	return 0
}

func usageText(u *toolnexus.MediaUsage) string {
	if u == nil {
		return ""
	}
	return usageAny(u)
}

func usageAny(u any) string {
	if u == nil {
		return ""
	}
	b, _ := json.Marshal(u)
	return " usage=" + string(b)
}
