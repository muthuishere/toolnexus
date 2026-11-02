package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// go run . [image|speech|video|all]   (needs A2E_API_KEY; spends credits)
func main() {
	which := "all"
	if len(os.Args) > 1 {
		which = os.Args[1]
	}
	st, err := LoadStyle("styles/a2e.json")
	must(err)
	m := New(st, Options{OnProgress: func(p Progress) {
		fmt.Printf("  … %s %s %s\n", p.Verb, p.JobID, p.Status)
	}})
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
	defer cancel()

	var files []File // what RunResult.files would hold

	// Face 1: typed verb
	var imgURL string
	if which == "image" || which == "all" || which == "video" {
		fmt.Println("== media.Image (typed verb)")
		r, err := m.Image(ctx, ImageInput{Prompt: "a cat astronaut floating in space, cinematic studio light", Width: 768, Height: 1024})
		must(err)
		dump(r)
		files = append(files, r.Files...)
		imgURL = r.Files[0].URL
	}

	// Face 2: the same engine as a Tool inside a real toolnexus Toolkit
	if which == "video" || which == "all" {
		fmt.Println("== toolkit.Execute(generate_video) (tool face)")
		tk, err := tn.CreateToolkit(ctx, tn.Options{})
		must(err)
		tk.Register(m.Tools()...)
		fmt.Println("  OpenAI schema:", js(tk.ToOpenAI()))
		tr, err := tk.Execute(ctx, "generate_video", map[string]any{"image": imgURL, "prompt": "the cat gently floats and waves", "seconds": 5})
		must(err)
		dump(tr)
		for _, p := range tr.Parts {
			files = append(files, File{Type: p.Type, MimeType: p.MimeType, URL: p.URL, From: "generate_video"})
		}
	}

	if which == "speech" || which == "all" {
		fmt.Println("== media.Speech (typed verb)")
		r, err := m.Speech(ctx, SpeechInput{Text: "One small paw for a cat, one giant leap for catkind."})
		must(err)
		dump(r)
		files = append(files, r.Files...)
	}

	fmt.Println("== RunResult.files (what a host would receive)")
	dump(files)
}

func js(v any) string { b, _ := json.Marshal(v); return string(b) }
func dump(v any)      { b, _ := json.MarshalIndent(v, "  ", "  "); fmt.Println("  " + string(b)) }
func must(err error) {
	if err != nil {
		fmt.Println("ERROR:", err)
		os.Exit(1)
	}
}
