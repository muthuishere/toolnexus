// Dynamic agent-skill source. Mirrors opencode's skill/index.ts + tool/skill.ts:
// discover **/SKILL.md, parse YAML frontmatter, and expose ONE `skill` tool that
// loads a skill's instructions + sampled resources on demand (progressive
// disclosure).
package toolnexus

import (
	"fmt"
	"io/fs"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"
)

// SkillToolDescription is the loader description, verbatim from opencode.
const SkillToolDescription = `Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.

The skill name must match one of the skills listed in your system prompt.`

// SkillsPromptPreamble is prepended to Prompt() when ≥1 described skill exists.
// Byte-identical across all four ports — do not reword. See SPEC.md §3.
const SkillsPromptPreamble = "Skills provide specialized instructions and workflows for specific tasks.\n" +
	"Use the skill tool to load a skill when a task matches its description."

// SkillInfo describes one discovered skill.
type SkillInfo struct {
	Name        string
	Description string
	Location    string // absolute path to SKILL.md
	Content     string // body after frontmatter
}

var frontmatterRe = regexp.MustCompile(`(?s)^---\r?\n(.*?)\r?\n---\r?\n?(.*)$`)

type frontmatter struct {
	Name        string
	Description string
}

// parseFrontmatter parses the `---`-fenced YAML header with a real YAML parser
// (gopkg.in/yaml.v3) so folded (`>`)/literal (`|`) block scalars, chomping,
// quoting, and multi-line values all resolve — NOT a hand-rolled key:value
// split. Scalar values are coerced to string and trimmed, so block-scalar
// trailing newlines (which chomp slightly differently per lib) don't leak and
// the ports stay byte-identical. Malformed YAML fails gracefully to an empty
// map, never crashing discovery. Mirrors js/src/skill.ts parseFrontmatter.
// See SPEC.md §3.
func parseFrontmatter(text string) (data frontmatter, content string) {
	m := frontmatterRe.FindStringSubmatch(text)
	if m == nil {
		return frontmatter{}, text
	}
	var raw map[string]any
	if err := yaml.Unmarshal([]byte(m[1]), &raw); err != nil {
		raw = nil // malformed YAML → empty frontmatter, skill skipped for missing name
	}
	fm := frontmatter{}
	if s, ok := scalarString(raw["name"]); ok {
		fm.Name = s
	}
	if s, ok := scalarString(raw["description"]); ok {
		fm.Description = s
	}
	return fm, m[2]
}

// scalarString returns a trimmed string for scalar YAML values (string, number,
// bool) and ok=false for anything else (maps, sequences, null), mirroring the
// JS port's `typeof value === "string" | "number" | "boolean"` guard.
func scalarString(v any) (string, bool) {
	switch v.(type) {
	case string, int, int64, uint64, float64, float32, bool:
		return strings.TrimSpace(fmt.Sprintf("%v", v)), true
	default:
		return "", false
	}
}

// resolveEntry classifies a directory entry, following symlinks to their target
// (like opencode's `symlink: true` glob). A symlink is stat'd so isDir/isFile
// reflect the target; a broken symlink returns ok=false so the caller skips it.
func resolveEntry(full string, entry fs.DirEntry) (isDir, isFile, ok bool) {
	if entry.Type()&fs.ModeSymlink != 0 {
		info, err := os.Stat(full) // follows the link
		if err != nil {
			return false, false, false
		}
		return info.IsDir(), info.Mode().IsRegular(), true
	}
	return entry.IsDir(), entry.Type().IsRegular(), true
}

func walkSkillFiles(root string) []string {
	var out []string
	stack := []string{root}
	// Follow symlinked directories; guard against symlink cycles by tracking
	// resolved real paths already visited.
	seen := map[string]bool{}
	for len(stack) > 0 {
		dir := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			full := filepath.Join(dir, entry.Name())
			isDir, isFile, ok := resolveEntry(full, entry)
			if !ok {
				continue
			}
			if isDir {
				if entry.Name() == "node_modules" || entry.Name() == ".git" {
					continue
				}
				real, rErr := filepath.EvalSymlinks(full)
				if rErr != nil {
					continue
				}
				if seen[real] {
					continue
				}
				seen[real] = true
				stack = append(stack, full)
			} else if isFile && entry.Name() == "SKILL.md" {
				out = append(out, full)
			}
		}
	}
	return out
}

func sampleSiblingFiles(dir string, limit int) []string {
	var out []string
	stack := []string{dir}
	seen := map[string]bool{}
	for len(stack) > 0 && len(out) < limit {
		cur := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		entries, err := os.ReadDir(cur)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if len(out) >= limit {
				break
			}
			full := filepath.Join(cur, entry.Name())
			isDir, isFile, ok := resolveEntry(full, entry)
			if !ok {
				continue
			}
			if isDir {
				if entry.Name() == "node_modules" || entry.Name() == ".git" {
					continue
				}
				real, rErr := filepath.EvalSymlinks(full)
				if rErr != nil {
					continue
				}
				if seen[real] {
					continue
				}
				seen[real] = true
				stack = append(stack, full)
			} else if isFile && entry.Name() != "SKILL.md" {
				out = append(out, full)
			}
		}
	}
	return out
}

// SkillSource is the result of discovering skills.
type SkillSource struct {
	Skills map[string]SkillInfo
	Tool   Tool
}

// Prompt returns the markdown catalog for the system prompt (mirrors opencode
// Skill.fmt).
func (s *SkillSource) Prompt() string {
	described := make([]SkillInfo, 0, len(s.Skills))
	for _, info := range s.Skills {
		if info.Description != "" {
			described = append(described, info)
		}
	}
	if len(described) == 0 {
		return "No skills are currently available."
	}
	sort.Slice(described, func(i, j int) bool { return described[i].Name < described[j].Name })
	lines := []string{SkillsPromptPreamble, "", "## Available Skills"}
	for _, s := range described {
		lines = append(lines, fmt.Sprintf("- **%s**: %s", s.Name, s.Description))
	}
	return strings.Join(lines, "\n")
}

// LoadSkills discovers skills under one or more roots and builds the `skill`
// loader tool.
func LoadSkills(dirs ...string) *SkillSource {
	skills := map[string]SkillInfo{}

	for _, root := range dirs {
		fi, err := os.Stat(root)
		if err != nil || !fi.IsDir() {
			log.Printf("[toolnexus] skills dir not found: %s", root)
			continue
		}
		for _, file := range walkSkillFiles(root) {
			raw, rErr := os.ReadFile(file)
			if rErr != nil {
				continue
			}
			data, content := parseFrontmatter(string(raw))
			if data.Name == "" {
				continue
			}
			if _, exists := skills[data.Name]; exists {
				log.Printf("[toolnexus] duplicate skill name %q (%s) — keeping first", data.Name, file)
				continue
			}
			abs, aErr := filepath.Abs(file)
			if aErr != nil {
				abs = file
			}
			skills[data.Name] = SkillInfo{
				Name:        data.Name,
				Description: data.Description,
				Location:    abs,
				Content:     content,
			}
		}
	}

	tool := Tool{
		Name:        "skill",
		Description: SkillToolDescription,
		InputSchema: JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"name": map[string]any{
					"type":        "string",
					"description": "The name of the skill to load",
				},
			},
			"required":             []string{"name"},
			"additionalProperties": false,
		},
		Source: SourceSkill,
		Execute: func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			name, _ := args["name"].(string)
			info, ok := skills[name]
			if !ok {
				names := make([]string, 0, len(skills))
				for n := range skills {
					names = append(names, n)
				}
				sort.Strings(names)
				avail := "none"
				if len(names) > 0 {
					avail = strings.Join(names, ", ")
				}
				return ToolResult{
					Output:  fmt.Sprintf("Skill %q not found. Available skills: %s", name, avail),
					IsError: true,
				}, nil
			}
			dir := filepath.Dir(info.Location)
			base := fileURL(dir)
			files := sampleSiblingFiles(dir, 10)
			fileLines := make([]string, 0, len(files))
			for _, f := range files {
				fileLines = append(fileLines, fmt.Sprintf("<file>%s</file>", f))
			}
			output := strings.Join([]string{
				fmt.Sprintf("<skill_content name=%q>", info.Name),
				fmt.Sprintf("# Skill: %s", info.Name),
				"",
				strings.TrimSpace(info.Content),
				"",
				fmt.Sprintf("Base directory for this skill: %s", base),
				"Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
				"Note: file list is sampled.",
				"",
				"<skill_files>",
				strings.Join(fileLines, "\n"),
				"</skill_files>",
				"</skill_content>",
			}, "\n")
			return ToolResult{
				Output:   output,
				IsError:  false,
				Metadata: map[string]any{"name": info.Name, "dir": dir},
			}, nil
		},
	}

	return &SkillSource{Skills: skills, Tool: tool}
}

// fileURL renders an absolute directory path as a file:// URL (mirrors Node's
// pathToFileURL).
func fileURL(dir string) string {
	u := &url.URL{Scheme: "file", Path: filepath.ToSlash(dir)}
	return u.String()
}
