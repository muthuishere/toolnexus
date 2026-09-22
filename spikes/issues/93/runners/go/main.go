// Runner: print the Go port's skill inventory as JSON for the issue-93 harness.
package main

import (
	"encoding/json"
	"os"

	tn "github.com/muthuishere/toolnexus/golang"
)

type row struct {
	Location string `json:"location"`
	Reason   string `json:"reason"`
}
type out struct {
	Skills  []row `json:"skills"`
	Skipped []row `json:"skipped"`
}

func main() {
	inv := tn.ListSkills(tn.LoadSkillsOptions{Dirs: os.Args[1:]})
	o := out{Skills: []row{}, Skipped: []row{}}
	for _, s := range inv.Skills {
		o.Skills = append(o.Skills, row{Location: s.Location})
	}
	for _, s := range inv.Skipped {
		o.Skipped = append(o.Skipped, row{Location: s.Location, Reason: string(s.Reason)})
	}
	json.NewEncoder(os.Stdout).Encode(o)
}
