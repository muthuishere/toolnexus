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

// SkillInfo describes one discovered skill.
type SkillInfo struct {
	Name        string
	Description string
	Location    string // absolute path to SKILL.md
	Content     string // body after frontmatter
}

var frontmatterRe = regexp.MustCompile(`(?s)^---\r?\n(.*?)\r?\n---\r?\n?(.*)$`)

type frontmatter struct {
	Name        string `yaml:"name"`
	Description string `yaml:"description"`
}

func parseFrontmatter(text string) (data frontmatter, content string) {
	m := frontmatterRe.FindStringSubmatch(text)
	if m == nil {
		return frontmatter{}, text
	}
	var fm frontmatter
	if err := yaml.Unmarshal([]byte(m[1]), &fm); err != nil {
		return frontmatter{}, text
	}
	return fm, m[2]
}

func walkSkillFiles(root string) []string {
	var out []string
	_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if d.Name() == "node_modules" || d.Name() == ".git" {
				return fs.SkipDir
			}
			return nil
		}
		if d.Name() == "SKILL.md" {
			out = append(out, path)
		}
		return nil
	})
	return out
}

func sampleSiblingFiles(dir string, limit int) []string {
	var out []string
	_ = filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if len(out) >= limit {
			return fs.SkipAll
		}
		if d.IsDir() {
			if path != dir && (d.Name() == "node_modules" || d.Name() == ".git") {
				return fs.SkipDir
			}
			return nil
		}
		if d.Name() != "SKILL.md" {
			out = append(out, path)
		}
		return nil
	})
	if len(out) > limit {
		out = out[:limit]
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
	lines := []string{"## Available Skills"}
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
