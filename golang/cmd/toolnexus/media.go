package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"strings"

	"github.com/muthuishere/toolnexus/golang"
)

const mediaUsage = `toolnexus media <verb> --style <style> --prompt "..." [flags]

One generation, then exit: prints the path (or URL) of each produced file, one
per line. The twin of GenerateMedia(ctx, style, verb, args).

  <verb>                image | video (what the style declares)
  --style string        grok-cli | codex-cli | google-agy-cli (or a -mock twin),
                        or the path of an HTTP style JSON file
  --prompt string       What to make (required)
  --aspect-ratio string e.g. 16:9
  --width int           Width in pixels
  --height int          Height in pixels
  --seconds int         Video length in seconds
  --model string        Model passed to the CLI
  --out string          Folder the files are saved in (default <tmp>/toolnexus-media)
  --timeout-ms int      Job deadline in ms (default 600000)

CLI styles fold width/height/seconds/aspect ratio into the prompt text.
`

// mediaCmd runs `toolnexus media` and returns the exit code.
func mediaCmd(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		fmt.Fprint(stderr, mediaUsage)
		return 2
	}
	verb := args[0]
	fs := flag.NewFlagSet("media", flag.ContinueOnError)
	fs.SetOutput(stderr)
	fs.Usage = func() { fmt.Fprint(stderr, mediaUsage) }
	style := fs.String("style", "", "")
	prompt := fs.String("prompt", "", "")
	aspect := fs.String("aspect-ratio", "", "")
	width := fs.Int("width", 0, "")
	height := fs.Int("height", 0, "")
	seconds := fs.Int("seconds", 0, "")
	model := fs.String("model", "", "")
	out := fs.String("out", "", "")
	timeout := fs.Int("timeout-ms", 0, "")
	if err := fs.Parse(args[1:]); err != nil {
		return 2
	}
	if *style == "" || *prompt == "" {
		fmt.Fprintln(stderr, "error: --style and --prompt are required")
		return 2
	}
	in := map[string]any{"prompt": *prompt}
	if *aspect != "" {
		in["aspectRatio"] = *aspect
	}
	for k, v := range map[string]int{"width": *width, "height": *height, "seconds": *seconds} {
		if v > 0 {
			in[k] = v
		}
	}
	cfg := toolnexus.MediaConfig{Model: *model}
	cfg.Gather.Dir = *out
	cfg.Loop.TimeoutMs = *timeout
	res, err := toolnexus.GenerateMedia(context.Background(), *style, verb, in, cfg)
	if err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	for _, f := range res.Files {
		if f.Path != "" {
			fmt.Fprintln(stdout, f.Path)
		} else {
			fmt.Fprintln(stdout, f.URL)
		}
	}
	return 0
}
