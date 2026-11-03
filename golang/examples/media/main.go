// Media generation (SPEC §7G) against the shared a2e.ai preset — both faces:
// the typed verb (Media.Image) and the tool face (Media.Tools, invoked directly).
//
//	A2E_API_KEY=... go run ./examples/media
//
// Without A2E_API_KEY it prints a hint and exits 0, so hermetic runs never touch
// the network. The key is read by the preset's ${A2E_API_KEY} header and is never
// printed or logged.
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"

	"github.com/muthuishere/toolnexus/golang"
)

func main() {
	if os.Getenv("A2E_API_KEY") == "" {
		fmt.Println("set A2E_API_KEY to run the media example (skipping: no network call made)")
		return
	}

	_, thisFile, _, _ := runtime.Caller(0)
	preset := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "examples", "media-styles", "a2e.json")
	style, err := toolnexus.LoadMediaStyle(preset)
	if err != nil {
		log.Fatal(err)
	}
	media := toolnexus.CreateMedia([]toolnexus.MediaStyle{style}, toolnexus.MediaOptions{
		OnProgress: func(p toolnexus.MediaProgress) { fmt.Printf("  ..%s %s\n", p.Verb, p.Status) },
	})
	ctx := context.Background()

	// 1. Typed verb.
	img, err := media.Image(ctx, toolnexus.ImageInput{Prompt: "a red fox in fresh snow, watercolor", Width: 768, Height: 768})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("image status:", img.Status)
	for _, f := range img.Files {
		fmt.Println("  file:", f.Type, f.MimeType, f.URL)
	}

	// 2. Tool face — the same engine as plain Tools an LLM would call.
	tools := media.Tools()
	for _, t := range tools {
		fmt.Println("tool:", t.Name)
	}
	for _, t := range tools {
		if t.Name != "generate_speech" {
			continue
		}
		res, err := t.Execute(map[string]any{"text": "Hello from toolnexus."}, nil)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println("speech output:", res.Output, "isError:", res.IsError)
		for _, p := range res.Parts {
			fmt.Println("  part:", p.Type, p.MimeType, p.URL)
		}
	}
}
