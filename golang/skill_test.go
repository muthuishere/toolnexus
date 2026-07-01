// Frontmatter-parser tests: the header is parsed with a real YAML parser
// (gopkg.in/yaml.v3), so folded (`>`)/literal (`|`) block scalars resolve and
// scalar values are trimmed — keeping the ports byte-identical. Mirrors the
// consensus matrix in js/test. See SPEC.md §3.
package toolnexus

import (
	"os"
	"path/filepath"
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
