package main

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"

	tn "github.com/muthuishere/toolnexus/golang"
)

func main() {
	src := tn.LoadSkills(os.Args[1])
	names := []string{"nope"}
	for n := range src.Skills {
		names = append(names, n)
	}
	sort.Strings(names)
	out := map[string]any{"prompt": src.Prompt()}
	tools := map[string]any{}
	for _, n := range names {
		r, _ := src.Tool.Execute(map[string]any{"name": n}, nil)
		tools[n] = map[string]any{"output": r.Output, "isError": r.IsError, "metadata": r.Metadata}
	}
	out["tools"] = tools
	var skips []string
	for _, s := range src.Skipped {
		skips = append(skips, s.Location+" "+string(s.Reason))
	}
	sort.Strings(skips)
	out["skipped"] = skips
	b, _ := json.MarshalIndent(out, "", " ")
	fmt.Println(string(b))
}
