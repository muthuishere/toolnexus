// Usage: go run . <cmd>   (keys via: sec run ELEVENLABS_API_KEY,... -- go run . <cmd>)
// cmds: elevenlabs-speech [voice] | elevenlabs-voices | openai-image | openrouter-image | fal-image |
//
//	grok-video | grok-image | codex-image | route
package main

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"
)

var (
	_ MediaClient = (*A2EMediaClient)(nil)
	_ MediaClient = (*ElevenLabsMediaClient)(nil)
	_ MediaClient = (*OpenAIMediaClient)(nil)
	_ MediaClient = (*OpenRouterMediaClient)(nil)
	_ MediaClient = (*FalMediaClient)(nil)
	_ MediaClient = (*GrokMediaClient)(nil)
	_ MediaClient = (*CodexMediaClient)(nil)
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: go run . <cmd>")
		os.Exit(2)
	}
	cmd := os.Args[1]
	arg := ""
	if len(os.Args) > 2 {
		arg = strings.Join(os.Args[2:], " ")
	}
	prog := func(s string, d time.Duration) { fmt.Printf("  [%6.1fs] %s\n", d.Seconds(), s) }
	cfg := Config{Loop: Loop{OnProgress: prog}}
	ctx := context.Background()
	t0 := time.Now()
	var res MediaResult
	var err error
	switch cmd {
	case "elevenlabs-voices":
		e := NewElevenLabs(cfg)
		id, err := e.resolveVoice(ctx, arg)
		fmt.Println("voice", arg, "->", id, err)
		return
	case "elevenlabs-speech":
		res, err = NewElevenLabs(cfg).Speech(ctx, Args{Text: "Hello from toolnexus media clients.", Voice: arg})
	case "openai-image":
		res, err = NewOpenAI(cfg).Image(ctx, Args{Prompt: "a small red apple on a white table"})
	case "openrouter-image":
		if arg != "" {
			cfg.Model = arg
		}
		res, err = NewOpenRouter(cfg).Image(ctx, Args{Prompt: "a small red apple on a white table"})
	case "fal-image":
		res, err = NewFal(cfg).Image(ctx, Args{Prompt: "a small red apple on a white table"})
	case "grok-image", "grok-video":
		g := NewGrok(cfg)
		g.Capture = "out/captures/" + cmd + ".jsonl"
		if cmd == "grok-image" {
			res, err = g.Image(ctx, Args{Prompt: "a small red apple on a white table"})
		} else {
			res, err = g.Video(ctx, Args{Prompt: "a red apple slowly rotating on a white table"})
		}
	case "codex-image":
		c := NewCodex(cfg)
		c.Capture = "out/captures/codex-image.jsonl"
		res, err = c.Image(ctx, Args{Prompt: "a small red apple on a white table"})
	case "route": // routing only, no network: which client gets each verb
		m := CreateMedia(NewGrok(cfg), NewCodex(cfg), NewElevenLabs(cfg), NewOpenAI(cfg), NewFal(cfg), NewA2E(cfg))
		for _, v := range AllVerbs {
			c, e := m.pick(v)
			if e != nil {
				fmt.Printf("%-11s -> (none)\n", v)
			} else {
				fmt.Printf("%-11s -> %s\n", v, c.Name())
			}
		}
		return
	default:
		fmt.Println("unknown cmd", cmd)
		os.Exit(2)
	}
	fmt.Printf("%s: %s in %s status=%s job=%s\n", cmd, map[bool]string{true: "OK", false: "FAIL"}[err == nil],
		time.Since(t0).Round(time.Millisecond), res.Status, res.JobID)
	if err != nil {
		fmt.Println("error:", err)
	}
	for _, f := range res.Files {
		fmt.Printf("  file %s %s %s\n", f.MimeType, f.Path, trunc(f.URL, 120))
	}
	if res.Text != "" {
		fmt.Println("  text:", trunc(res.Text, 300))
	}
	if err != nil {
		os.Exit(1)
	}
}
