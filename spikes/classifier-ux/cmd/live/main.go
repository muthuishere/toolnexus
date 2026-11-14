// live: one pass of the classifier UX against real backends. Keys come from
// env (run under `sec run`), are never printed; errors carry no body for auth.
//
//	sec run TYPESAFE_API_KEY OPENROUTER_API_KEY CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_JEV_API_KEY -- go run ./cmd/live
package main

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
	cx "toolnexus.spike/classifierux"
)

func redPNG() tn.ContentPart {
	img := image.NewRGBA(image.Rect(0, 0, 320, 320))
	for y := 0; y < 320; y++ {
		for x := 0; x < 320; x++ {
			img.Set(x, y, color.RGBA{220, 20, 20, 255})
		}
	}
	var b bytes.Buffer
	_ = png.Encode(&b, img)
	return tn.Bytes(b.Bytes(), "image/png")
}

func safe(err error) string {
	s := err.Error()
	if a := os.Getenv("CLOUDFLARE_ACCOUNT_ID"); a != "" {
		s = strings.ReplaceAll(s, a, "<account>")
	}
	if len(s) > 200 {
		s = s[:200] + "…"
	}
	return s
}

func main() {
	ctx := context.Background()
	refund := tn.Noul("refund", "Is the customer asking for money back?")
	team := tn.Choice("team", "Which team should handle it?", map[string]string{"billing": "Charges and refunds", "tech": "Bugs and outages"})
	red := tn.Noul("red", "Is the image in `photo` mostly red?")
	for _, o := range []cx.Options{
		{Backend: cx.TypeSafe, Model: "jev-1.14.0"},
		{Backend: cx.OpenRouter},
		{Backend: cx.Cloudflare},
		{Backend: cx.Cloudflare, Model: "@cf/cloudflare/clef"},
		// probe: declare images on OpenRouter's System One path to see whether the
		// gateway forwards assets + the danger-zone header (sets its Supports entry).
		{Backend: cx.Custom, BaseURL: "https://openrouter.ai/api/v1", Model: "typesafe/jev-1.14",
			APIKeyEnv: "OPENROUTER_API_KEY", Supports: &cx.Supports{Images: true}},
	} {
		o.Cache = cx.NewMemoryCache(time.Hour, 100)
		label := string(o.Backend) + "/" + o.Model
		c, err := cx.New(o)
		if err != nil {
			fmt.Printf("%-34s new: %s\n", label, safe(err))
			continue
		}
		st := c.State("I was charged twice for my subscription.")
		t0 := time.Now()
		r, err := st.Evaluate(ctx, refund, team)
		if err != nil {
			fmt.Printf("%-34s text : ERR %s\n", label, safe(err))
		} else {
			a, _ := r.Noul("refund")
			ch, _ := r.Choice("team")
			fmt.Printf("%-34s text : model=%s refund=%.2f team=%s (%.2f) %dms\n", label, r.Model, a.Noul, ch.Choice, ch.Confidence, time.Since(t0).Milliseconds())
			t1 := time.Now()
			r2, _ := st.Evaluate(ctx, refund, team)
			fmt.Printf("%-34s cache: cached=%v %dms\n", label, r2.Cached, time.Since(t1).Milliseconds())
		}
		r, err = c.State(map[string]any{"photo": redPNG()}).Evaluate(ctx, red)
		if err != nil {
			fmt.Printf("%-34s image: %s\n", label, safe(err))
		} else {
			a, _ := r.Noul("red")
			fmt.Printf("%-34s image: model=%s red=%.2f\n", label, r.Model, a.Noul)
		}
	}
}
