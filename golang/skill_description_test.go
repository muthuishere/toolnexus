package toolnexus

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

// SPEC.md §3, issue #84: oversized descriptions are warned, never modified.
// Shared fixture: conformance/skill_description/.
func TestSkillDescriptionLengthWarning(t *testing.T) {
	raw, err := os.ReadFile("../conformance/skill_description/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases struct {
		ExpectWarnings []struct {
			LocationSuffix string `json:"location_suffix"`
			Reason         string `json:"reason"`
			Chars          int    `json:"chars"`
		} `json:"expect_warnings"`
		FullDescriptions map[string]string `json:"full_descriptions"`
	}
	if err := json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	opts := LoadSkillsOptions{Dirs: []string{"../conformance/skill_description/skills"}}

	check := func(label string, got []SkillWarning) {
		t.Helper()
		if len(got) != len(cases.ExpectWarnings) {
			t.Fatalf("%s: warnings = %+v, want %d", label, got, len(cases.ExpectWarnings))
		}
		for i, w := range cases.ExpectWarnings {
			g := got[i]
			if !strings.HasSuffix(strings.ReplaceAll(g.Location, "\\", "/"), w.LocationSuffix) ||
				string(g.Reason) != w.Reason || g.Chars != w.Chars {
				t.Errorf("%s[%d] = %+v, want %+v", label, i, g, w)
			}
		}
	}

	src := LoadSkillsWith(opts)
	check("loader", src.Warnings)
	check("inventory", ListSkills(opts).Warnings)

	prompt := src.Prompt()
	for name, desc := range cases.FullDescriptions {
		if src.Skills[name].Description != desc {
			t.Errorf("%s: description modified", name)
		}
		if !strings.Contains(prompt, "- **"+name+"**: "+desc) {
			t.Errorf("%s: prompt does not carry the full description", name)
		}
	}

	// Loader warnings are over EXPOSED skills: filtering long-desc out drops its
	// warning, while the unfiltered inventory keeps it.
	filtered := LoadSkillsWith(LoadSkillsOptions{Dirs: opts.Dirs, Filter: map[string]bool{"long-desc": false}})
	if len(filtered.Warnings) != 0 {
		t.Errorf("filtered loader warnings = %+v, want none", filtered.Warnings)
	}
}
