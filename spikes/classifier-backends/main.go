// classifier-backends: a live probe of every System One route ADR 0038 names,
// with a text case and an image case on each. It answers the ADR's open
// questions with evidence instead of docs:
//
//   - Does each backend serve the SAME System One body and reply?
//   - Does each one accept the TypeSafe image wire (assets + danger-zone header)?
//   - Is OpenRouter's alpha Decisions API byte-compatible with System One?
//
// Raw net/http on purpose: toolnexus cannot send images yet, and the point is
// to see the wire. Keys are read from env at call time and never printed; an
// auth failure prints only its status, never the body.
//
//	sec run TYPESAFE_API_KEY OPENROUTER_API_KEY CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_JEV_API_KEY -- go run .
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// redPNG is a 320x320 solid red PNG: inside TypeSafe's 280..1568 px limits,
// and a question with an unambiguous answer ("is it mostly red?" -> yes).
func redPNG() string {
	img := image.NewRGBA(image.Rect(0, 0, 320, 320))
	for y := 0; y < 320; y++ {
		for x := 0; x < 320; x++ {
			img.Set(x, y, color.RGBA{220, 20, 20, 255})
		}
	}
	var b bytes.Buffer
	_ = png.Encode(&b, img)
	return base64.StdEncoding.EncodeToString(b.Bytes())
}

type route struct {
	name, url, model, keyEnv string
	wrap                     string // "" = System One body; "cf" = Cloudflare {model,input}
	images                   string // "assets" (TypeSafe wire) | "cf-images" (Clef images field)
}

func routes() []route {
	acct := os.Getenv("CLOUDFLARE_ACCOUNT_ID")
	cf := "https://api.cloudflare.com/client/v4/accounts/" + acct + "/ai/run"
	return []route{
		{"typesafe", "https://api.typesafe.ai/v1/systemone", "jev-1.14.0", "TYPESAFE_API_KEY", "", "assets"},
		{"typesafe-latest", "https://api.typesafe.ai/v1/systemone", "jev-latest", "TYPESAFE_API_KEY", "", "assets"},
		{"openrouter", "https://openrouter.ai/api/v1/systemone", "typesafe/jev-1.14", "OPENROUTER_API_KEY", "", "assets"},
		{"openrouter-decisions", "https://openrouter.ai/api/alpha/decisions", "typesafe/jev-1.14", "OPENROUTER_API_KEY", "", "assets"},
		{"openrouter-decisions-openai", "https://openrouter.ai/api/alpha/decisions", "openai/gpt-6-luna-decisions", "OPENROUTER_API_KEY", "", "assets"},
		{"openrouter-systemone-openai", "https://openrouter.ai/api/v1/systemone", "openai/gpt-6-luna-decisions", "OPENROUTER_API_KEY", "", "assets"},
		{"cloudflare-jev", cf, "typesafe/jev", "CLOUDFLARE_JEV_API_KEY", "cf", "assets"},
		{"cloudflare-clef", cf, "@cf/cloudflare/clef", "CLOUDFLARE_JEV_API_KEY", "cf", "cf-images"},
		{"cloudflare-clef-assets", cf, "@cf/cloudflare/clef", "CLOUDFLARE_JEV_API_KEY", "cf", "assets"},
	}
}

var textQ = map[string]any{"refund": map[string]any{"type": "noul", "instructions": "Is the customer asking for money back?"}}

// body builds the request. The image case uses the reference "photo": per
// docs.typesafe.ai/images a state string that is exactly a reference becomes
// that image.
func body(r route, withImage bool, b64 string) (map[string]any, map[string]string) {
	hdr := map[string]string{}
	core := map[string]any{"state": "I was charged twice for my subscription.", "questions": textQ}
	if withImage {
		core["questions"] = map[string]any{"red": map[string]any{"type": "noul", "instructions": "Is this image mostly red?"}}
		switch r.images {
		case "assets":
			core["state"] = "photo"
			core["assets"] = map[string]any{"photo": map[string]any{"type": "image", "format": "png", "data_base64": b64}}
			hdr["TypeSafe-Danger-Zone-Images"] = "true"
		case "cf-images":
			core["state"] = "Look at the attached image."
			core["images"] = []string{"data:image/png;base64," + b64}
		}
	}
	if r.wrap == "cf" {
		return map[string]any{"model": r.model, "input": core}, hdr
	}
	core["model"] = r.model
	return core, hdr
}

// unwrapCF mirrors jevx core/core.go:770-815: {success,result} and the nested
// {state:"Completed",result}.
func unwrapCF(raw []byte) ([]byte, error) {
	var env struct {
		Result  json.RawMessage `json:"result"`
		Success bool            `json:"success"`
		Errors  []struct {
			Message string `json:"message"`
		} `json:"errors"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, err
	}
	if !env.Success {
		if len(env.Errors) > 0 {
			return nil, fmt.Errorf("cloudflare: %s", env.Errors[0].Message)
		}
		return nil, fmt.Errorf("cloudflare: success=false")
	}
	var inner struct {
		State  string          `json:"state"`
		Result json.RawMessage `json:"result"`
	}
	if json.Unmarshal(env.Result, &inner) == nil && inner.State != "" {
		if inner.State != "Completed" {
			return nil, fmt.Errorf("cloudflare: state %q", inner.State)
		}
		return inner.Result, nil
	}
	return env.Result, nil
}

type outcome struct {
	Route, Case, Status, Model, Answers, TopKeys, Note string
}

func call(r route, withImage bool, b64 string) outcome {
	o := outcome{Route: r.name, Case: map[bool]string{false: "text", true: "image"}[withImage]}
	key := os.Getenv(r.keyEnv)
	if key == "" || (r.wrap == "cf" && os.Getenv("CLOUDFLARE_ACCOUNT_ID") == "") {
		o.Status, o.Note = "SKIP", "no "+r.keyEnv
		return o
	}
	b, hdr := body(r, withImage, b64)
	raw, _ := json.Marshal(b)
	req, _ := http.NewRequest("POST", r.url, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+key)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	res, err := (&http.Client{Timeout: 60 * time.Second}).Do(req)
	if err != nil {
		o.Status, o.Note = "ERR", "transport error"
		return o
	}
	defer res.Body.Close()
	rb, _ := io.ReadAll(res.Body)
	o.Status = fmt.Sprint(res.StatusCode)
	if res.StatusCode == 401 || res.StatusCode == 403 {
		o.Note = "auth failure (body withheld)"
		return o
	}
	if res.StatusCode/100 != 2 {
		o.Note = trim(string(rb))
		return o
	}
	if r.wrap == "cf" {
		if rb, err = unwrapCF(rb); err != nil {
			o.Note = trim(err.Error())
			return o
		}
	}
	var top map[string]json.RawMessage
	_ = json.Unmarshal(rb, &top)
	var ks []string
	for k := range top {
		ks = append(ks, k)
	}
	o.TopKeys = strings.Join(sorted(ks), ",")
	var m struct {
		Model   string                     `json:"model"`
		Answers map[string]json.RawMessage `json:"answers"`
	}
	_ = json.Unmarshal(rb, &m)
	o.Model = m.Model
	var as []string
	for k, v := range m.Answers {
		as = append(as, k+"="+string(v))
	}
	o.Answers = strings.Join(as, " ")
	return o
}

func sorted(s []string) []string {
	for i := range s {
		for j := i + 1; j < len(s); j++ {
			if s[j] < s[i] {
				s[i], s[j] = s[j], s[i]
			}
		}
	}
	return s
}

// trim caps an error body and strips anything that looks like an account id
// in a URL, so the report is safe to commit.
func trim(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	if acct := os.Getenv("CLOUDFLARE_ACCOUNT_ID"); acct != "" {
		s = strings.ReplaceAll(s, acct, "<account>")
	}
	if len(s) > 220 {
		s = s[:220] + "…"
	}
	return s
}

func main() {
	b64 := redPNG()
	only := os.Getenv("ONLY")
	var all []outcome
	for _, r := range routes() {
		if only != "" && !strings.Contains(r.name, only) {
			continue
		}
		for _, img := range []bool{false, true} {
			o := call(r, img, b64)
			all = append(all, o)
			fmt.Printf("%-28s %-5s %-4s model=%-28s keys=[%s] %s %s\n", o.Route, o.Case, o.Status, o.Model, o.TopKeys, o.Answers, o.Note)
		}
	}
	if p := os.Getenv("OUT"); p != "" {
		b, _ := json.MarshalIndent(all, "", " ")
		_ = os.WriteFile(p, b, 0o644)
	}
}
