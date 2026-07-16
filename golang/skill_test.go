// Frontmatter-parser tests: the header is parsed with a real YAML parser
// (gopkg.in/yaml.v3), so folded (`>`)/literal (`|`) block scalars resolve and
// scalar values are trimmed — keeping the ports byte-identical. Mirrors the
// consensus matrix in js/test. See SPEC.md §3.

package toolnexus

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// writeSkill writes a SKILL.md under a fresh temp skills root and returns the
// root dir so it can be handed to LoadSkills.
func writeSkill(t *testing.T, body string) string {
	t.Helper()
	root := t.TempDir()
	dir := filepath.Join(root, "s")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(body), 0o644); err != nil {
		t.Fatalf("write SKILL.md: %v", err)
	}
	return root
}

func TestFrontmatterSingleLine(t *testing.T) {
	root := writeSkill(t, "---\nname: single\ndescription: A one-liner.\n---\nBody.\n")
	src := LoadSkills(root)
	info, ok := src.Skills["single"]
	if !ok {
		t.Fatalf("single not discovered; skills: %v", src.Skills)
	}
	if info.Description != "A one-liner." {
		t.Fatalf("description = %q, want %q", info.Description, "A one-liner.")
	}
}

func TestFrontmatterFolded(t *testing.T) {
	// Folded `>`: newlines collapse to spaces. Must NOT capture just ">" and
	// must NOT keep the literal line breaks.
	body := "---\nname: folded\ndescription: >\n  first line\n  second line\n---\nBody.\n"
	src := LoadSkills(writeSkill(t, body))
	info, ok := src.Skills["folded"]
	if !ok {
		t.Fatalf("folded not discovered; skills: %v", src.Skills)
	}
	if info.Description == ">" {
		t.Fatalf("description captured the block indicator %q instead of the folded text", ">")
	}
	if info.Description != "first line second line" {
		t.Fatalf("folded description = %q, want %q", info.Description, "first line second line")
	}
}

func TestFrontmatterLiteral(t *testing.T) {
	// Literal `|`: newlines preserved (trailing chomp trimmed).
	body := "---\nname: literal\ndescription: |\n  line one\n  line two\n---\nBody.\n"
	src := LoadSkills(writeSkill(t, body))
	info, ok := src.Skills["literal"]
	if !ok {
		t.Fatalf("literal not discovered; skills: %v", src.Skills)
	}
	if info.Description != "line one\nline two" {
		t.Fatalf("literal description = %q, want %q", info.Description, "line one\nline two")
	}
}

func TestFrontmatterEmptyDescription(t *testing.T) {
	// Empty description must not crash discovery; skill still discovered by name.
	root := writeSkill(t, "---\nname: emptydesc\ndescription:\n---\nBody.\n")
	src := LoadSkills(root)
	info, ok := src.Skills["emptydesc"]
	if !ok {
		t.Fatalf("emptydesc not discovered; skills: %v", src.Skills)
	}
	if info.Description != "" {
		t.Fatalf("description = %q, want empty", info.Description)
	}
}

func TestFrontmatterMalformedYAML(t *testing.T) {
	// Malformed YAML must fail gracefully (empty frontmatter) — no panic, no
	// name, skill simply skipped — never crashing discovery.
	root := writeSkill(t, "---\nname: [unterminated\ndescription: broken: : :\n---\nBody.\n")
	src := LoadSkills(root) // must not panic
	if len(src.Skills) != 0 {
		t.Fatalf("expected malformed skill to be skipped, got: %v", src.Skills)
	}
}

func TestFrontmatterRealHuddleFolded(t *testing.T) {
	// A realistic skill (name: huddle + folded description) as shipped by real
	// skills — the folded text must come through, not the ">" indicator.
	body := "---\n" +
		"name: huddle\n" +
		"description: >\n" +
		"  Runs a repo-aware expert huddle for engineering decisions,\n" +
		"  planning, research, verification, and spec capture.\n" +
		"---\n" +
		"# Huddle\n\nBody.\n"
	src := LoadSkills(writeSkill(t, body))
	info, ok := src.Skills["huddle"]
	if !ok {
		t.Fatalf("huddle not discovered; skills: %v", src.Skills)
	}
	want := "Runs a repo-aware expert huddle for engineering decisions, planning, research, verification, and spec capture."
	if info.Description != want {
		t.Fatalf("huddle description = %q, want %q", info.Description, want)
	}
}

// --- extend-skill-source: S1–S5 (SPEC.md §3) ---

// TestS4OnDiskBlockByteIdentical pins the exact bytes the fs path must never move.
func TestS4OnDiskBlockByteIdentical(t *testing.T) {
	src := LoadSkills("../examples/skills")
	res, err := src.Tool.Execute(map[string]any{"name": "hello-world"}, nil)
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	dir, _ := filepath.Abs("../examples/skills/hello-world")
	content := strings.TrimSpace(src.Skills["hello-world"].Content)
	want := strings.Join([]string{
		`<skill_content name="hello-world">`,
		"# Skill: hello-world",
		"",
		content,
		"",
		"Base directory for this skill: " + fileURL(dir),
		"Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
		"Note: file list is sampled.",
		"",
		"<skill_files>",
		"<file>" + filepath.Join(dir, "scripts", "greet.sh") + "</file>",
		"</skill_files>",
		"</skill_content>",
	}, "\n")
	if res.Output != want {
		t.Fatalf("on-disk output moved.\n got: %q\nwant: %q", res.Output, want)
	}
}

func TestS1DataSkillLogicalBase(t *testing.T) {
	src := LoadSkillsWith(LoadSkillsOptions{
		Skills: []SkillDef{{Name: "x", Description: "d", Content: "body", Resources: []string{"scripts/foo.sh"}}},
	})
	if _, ok := src.Skills["x"]; !ok {
		t.Fatalf("data skill x not discovered")
	}
	res, _ := src.Tool.Execute(map[string]any{"name": "x"}, nil)
	if !strings.Contains(res.Output, "Base directory for this skill: skill://x/") {
		t.Fatalf("missing logical base; got:\n%s", res.Output)
	}
	if !strings.Contains(res.Output, "<file>scripts/foo.sh</file>") {
		t.Fatalf("missing logical resource; got:\n%s", res.Output)
	}
	if strings.Contains(res.Output, "file://") {
		t.Fatalf("absolute host path leaked; got:\n%s", res.Output)
	}
}

func TestS1InstructionOnlyNoFilesBlock(t *testing.T) {
	src := LoadSkillsWith(LoadSkillsOptions{Skills: []SkillDef{{Name: "y", Description: "d", Content: "just text"}}})
	res, _ := src.Tool.Execute(map[string]any{"name": "y"}, nil)
	if strings.Contains(res.Output, "<skill_files>") {
		t.Fatalf("instruction-only skill emitted <skill_files>; got:\n%s", res.Output)
	}
}

func TestS1ProviderFailureIsolated(t *testing.T) {
	tk, err := CreateToolkit(context.Background(), Options{
		SkillsDir:     []string{"../examples/skills"},
		SkillProvider: func(context.Context) ([]SkillDef, error) { return nil, errors.New("boom") },
		Builtins:      false,
	})
	if err != nil {
		t.Fatalf("create toolkit: %v", err)
	}
	if !strings.Contains(tk.SkillsPrompt(), "hello-world") {
		t.Fatalf("provider failure lost the dir skill; prompt:\n%s", tk.SkillsPrompt())
	}
}

func TestS1DirDataFirstWins(t *testing.T) {
	src := LoadSkillsWith(LoadSkillsOptions{
		Dirs:   []string{"../examples/skills"},
		Skills: []SkillDef{{Name: "hello-world", Description: "shadow", Content: "shadow body"}},
	})
	res, _ := src.Tool.Execute(map[string]any{"name": "hello-world"}, nil)
	if !strings.Contains(res.Output, "file://") || strings.Contains(res.Output, "shadow body") {
		t.Fatalf("dir did not win the dedupe; got:\n%s", res.Output)
	}
}

func TestS2FilterSemantics(t *testing.T) {
	defs := []SkillDef{{Name: "a", Description: "A", Content: "a"}, {Name: "b", Description: "B", Content: "b"}, {Name: "c", Description: "C", Content: "c"}}
	keys := func(s *SkillSource) []string {
		out := make([]string, 0, len(s.Skills))
		for k := range s.Skills {
			out = append(out, k)
		}
		sort.Strings(out)
		return out
	}
	if got := keys(LoadSkillsWith(LoadSkillsOptions{Skills: defs, Filter: map[string]bool{"a": true, "b": true}})); strings.Join(got, ",") != "a,b" {
		t.Fatalf("allowlist = %v", got)
	}
	if got := keys(LoadSkillsWith(LoadSkillsOptions{Skills: defs, Filter: map[string]bool{"c": false}})); strings.Join(got, ",") != "a,b" {
		t.Fatalf("droplist = %v", got)
	}
	if got := keys(LoadSkillsWith(LoadSkillsOptions{Skills: defs, Filter: map[string]bool{"a": true, "nope": true}})); strings.Join(got, ",") != "a" {
		t.Fatalf("unknown-name = %v", got)
	}
	if got := keys(LoadSkillsWith(LoadSkillsOptions{Skills: defs, Filter: map[string]bool{}})); strings.Join(got, ",") != "a,b,c" {
		t.Fatalf("empty filter = %v", got)
	}
}

func TestS3ListSkillsTypedSkips(t *testing.T) {
	root := t.TempDir()
	mk := func(name, body string) {
		d := filepath.Join(root, name)
		_ = os.MkdirAll(d, 0o755)
		_ = os.WriteFile(filepath.Join(d, "SKILL.md"), []byte(body), 0o644)
	}
	mk("good", "---\nname: good\ndescription: ok\n---\nbody")
	mk("noname", "---\ndescription: no name\n---\nbody")
	mk("bad", "---\nname: [unclosed\n---\nbody")
	inv := ListSkills(LoadSkillsOptions{
		Dirs:   []string{root},
		Skills: []SkillDef{{Name: "good", Description: "dup", Content: "dup"}},
	})
	if len(inv.Skills) != 1 || inv.Skills[0].Name != "good" {
		t.Fatalf("skills = %v", inv.Skills)
	}
	reasons := make([]string, 0, len(inv.Skipped))
	for _, s := range inv.Skipped {
		reasons = append(reasons, string(s.Reason))
	}
	sort.Strings(reasons)
	if strings.Join(reasons, ",") != "duplicate-name,malformed-frontmatter,missing-name" {
		t.Fatalf("skip reasons = %v", reasons)
	}
}

func TestS5SampleCapAndDisable(t *testing.T) {
	defs := []SkillDef{{Name: "z", Content: "z", Resources: []string{"one", "two", "three"}}}
	capped, _ := LoadSkillsWith(LoadSkillsOptions{Skills: defs, SampleLimit: 2}).Tool.Execute(map[string]any{"name": "z"}, nil)
	if !strings.Contains(capped.Output, "<file>one</file>\n<file>two</file>") || strings.Contains(capped.Output, "three") {
		t.Fatalf("cap failed; got:\n%s", capped.Output)
	}
	off, _ := LoadSkillsWith(LoadSkillsOptions{Skills: defs, SampleLimit: -1}).Tool.Execute(map[string]any{"name": "z"}, nil)
	if strings.Contains(off.Output, "<skill_files>") || strings.Contains(off.Output, "Note: file list is sampled") {
		t.Fatalf("disable failed; got:\n%s", off.Output)
	}
}
