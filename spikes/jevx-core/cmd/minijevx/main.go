// minijevx: a mini jevx over the spike core (toolnexus underneath).
//
//	minijevx [--profile P] [--url U --model M --key-env NAME] is   "QUESTION"           --in TEXT
//	minijevx [...]                                       pick "QUESTION" k=desc ...  --in TEXT
//	minijevx [...]                                       rate "QUESTION" low mid high --in TEXT
//
// Prints "ANSWER VALUE"; exit 0 yes/answered, 1 no, 3 unsure, 4 error (jevx's contract).
// --url/--model/--key-env register an ad-hoc profile "cli" (how jevx's `profile add` maps on).
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"strings"

	jc "toolnexus.spike/jevxcore"
)

func die(f string, a ...any) { fmt.Fprintf(os.Stderr, "minijevx: "+f+"\n", a...); os.Exit(4) }

func main() {
	fs := flag.NewFlagSet("minijevx", flag.ExitOnError)
	prof := fs.String("profile", "", "profile name (default: jev)")
	url := fs.String("url", "", "ad-hoc System One endpoint (full URL)")
	model := fs.String("model", "", "ad-hoc model")
	keyEnv := fs.String("key-env", "", "NAME of the env var holding the key")
	in := fs.String("in", "", "the item text")
	var pos []string
	args := os.Args[1:]
	for len(args) > 0 {
		_ = fs.Parse(args)
		args = fs.Args()
		if len(args) > 0 {
			pos, args = append(pos, args[0]), args[1:]
		}
	}
	if len(pos) < 2 || *in == "" {
		die("usage: minijevx [--profile P] is|pick|rate \"QUESTION\" [options|levels] --in TEXT")
	}
	reg := jc.DefaultRegistry(nil)
	if *url != "" {
		reg.Profiles["cli"] = jc.Profile{URL: *url, Model: *model, APIKeyEnv: *keyEnv}
		if *prof == "" {
			*prof = "cli"
		}
	}
	c, ctx, it := jc.Core{Reg: reg}, context.Background(), jc.Item{Text: *in}
	var v jc.Verdict
	var err error
	switch pos[0] {
	case "is":
		v, err = c.Is(ctx, *prof, it, pos[1])
	case "pick":
		opts := map[string]string{}
		for _, o := range pos[2:] {
			k, d, _ := strings.Cut(o, "=")
			if d == "" {
				d = k
			}
			opts[k] = d
		}
		v, err = c.Pick(ctx, *prof, it, pos[1], opts)
	case "rate":
		v, err = c.Rate(ctx, *prof, it, pos[1], pos[2:])
	default:
		die("unknown verb %q", pos[0])
	}
	if err != nil {
		die("%v", err)
	}
	fmt.Printf("%s %.2f\n", v.Answer, v.Value)
	os.Exit(v.ExitCode())
}
