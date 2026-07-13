// Dynamic agent-skill source. Mirrors opencode's skill/index.ts + tool/skill.ts:
// discover **/SKILL.md, parse YAML frontmatter, and expose ONE `skill` tool that
// loads a skill's instructions + sampled resources on demand (progressive
// disclosure).
//
// Beyond on-disk discovery the source also accepts skills supplied as data
// (SkillDef) — SPEC.md §3. Directory-sourced skills keep the exact file:// base
// + on-disk sibling sampling (byte-identical); data-sourced skills use a logical
// skill://name/ base + a supplied resource list and never touch disk.
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
	Location    string // absolute path to SKILL.md (fs) or logical base (data)
	Content     string // body after frontmatter
	Origin      string // "fs" (default) or "logical" — internal discriminator
	Resources   []string
	Base        string
}

// SkillDef supplies one skill directly as data, bypassing the filesystem
// (SPEC.md §3, S1). Resources is an optional logical resource list; nil ⇒
// instruction-only. Base is an optional logical base (default skill://<name>/).
type SkillDef struct {
	Name        string
	Description string
	Content     string
	Resources   []string
	Base        string
}

// SkillSkipReason is why a candidate SKILL.md did not become a skill (S3).
type SkillSkipReason string

const (
	SkipMissingName   SkillSkipReason = "missing-name"
	SkipMalformed     SkillSkipReason = "malformed-frontmatter"
	SkipDuplicateName SkillSkipReason = "duplicate-name"
	SkipUnreadable    SkillSkipReason = "unreadable"
)

// SkillSkip records a skipped candidate and the reason.
type SkillSkip struct {
	Location string
	Reason   SkillSkipReason
}

// SkillInventory is the result of a list-only validate pass (S3).
type SkillInventory struct {
	Skills  []SkillInfo
	Skipped []SkillSkip
}

// LoadSkillsOptions configures LoadSkillsWith / ListSkills (SPEC.md §3, S1/S2/S5).
type LoadSkillsOptions struct {
	Dirs        []string
	Skills      []SkillDef
	Filter      map[string]bool // per-agent allowlist; same semantics as the MCP tools filter
	SampleLimit int             // 0 ⇒ default 10, n>0 ⇒ cap, -1 ⇒ omit <skill_files>
}

var frontmatterRe = regexp.MustCompile(`(?s)^---\r?\n(.*?)\r?\n---\r?\n?(.*)$`)

type frontmatter struct {
	Name        string
	Description string
}

// parseFrontmatter parses the `---`-fenced YAML header with a real YAML parser.
// `malformed` is true only when fences are present but the YAML fails to parse —
// distinguishing a malformed header from a body with no frontmatter, so the
// inventory (S3) reports the right skip reason. LoadSkills' behavior is
// unchanged. See SPEC.md §3.
func parseFrontmatter(text string) (data frontmatter, content string, malformed bool) {
	m := frontmatterRe.FindStringSubmatch(text)
	if m == nil {
		return frontmatter{}, text, false
	}
	var raw map[string]any
	if err := yaml.Unmarshal([]byte(m[1]), &raw); err != nil {
		raw = nil
		malformed = true
	}
	fm := frontmatter{}
	if s, ok := scalarString(raw["name"]); ok {
		fm.Name = s
	}
	if s, ok := scalarString(raw["description"]); ok {
		fm.Description = s
	}
	return fm, m[2], malformed
}

// scalarString returns a trimmed string for scalar YAML values (string, number,
// bool) and ok=false for anything else (maps, sequences, null).
func scalarString(v any) (string, bool) {
	switch v.(type) {
	case string, int, int64, uint64, float64, float32, bool:
		return strings.TrimSpace(fmt.Sprintf("%v", v)), true
	default:
		return "", false
	}
}

// resolveEntry classifies a directory entry, following symlinks to their target.
func resolveEntry(full string, entry fs.DirEntry) (isDir, isFile, ok bool) {
	if entry.Type()&fs.ModeSymlink != 0 {
		info, err := os.Stat(full)
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

// rawCandidate is one candidate before cross-source dedupe.
type rawCandidate struct {
	info *SkillInfo
	skip *SkillSkip
}

func candidatesFromDir(root string) []rawCandidate {
	var out []rawCandidate
	fi, err := os.Stat(root)
	if err != nil || !fi.IsDir() {
		log.Printf("[toolnexus] skills dir not found: %s", root)
		return out
	}
	for _, file := range walkSkillFiles(root) {
		raw, rErr := os.ReadFile(file)
		if rErr != nil {
			out = append(out, rawCandidate{skip: &SkillSkip{Location: file, Reason: SkipUnreadable}})
			continue
		}
		data, content, malformed := parseFrontmatter(string(raw))
		if malformed {
			out = append(out, rawCandidate{skip: &SkillSkip{Location: file, Reason: SkipMalformed}})
			continue
		}
		if data.Name == "" {
			out = append(out, rawCandidate{skip: &SkillSkip{Location: file, Reason: SkipMissingName}})
			continue
		}
		abs, aErr := filepath.Abs(file)
		if aErr != nil {
			abs = file
		}
		out = append(out, rawCandidate{info: &SkillInfo{
			Name:        data.Name,
			Description: data.Description,
			Location:    abs,
			Content:     content,
			Origin:      "fs",
		}})
	}
	return out
}

func candidatesFromDefs(defs []SkillDef) []rawCandidate {
	out := make([]rawCandidate, 0, len(defs))
	for _, d := range defs {
		if d.Name == "" {
			loc := d.Base
			if loc == "" {
				loc = "skill://"
			}
			out = append(out, rawCandidate{skip: &SkillSkip{Location: loc, Reason: SkipMissingName}})
			continue
		}
		base := d.Base
		if base == "" {
			base = fmt.Sprintf("skill://%s/", d.Name)
		}
		out = append(out, rawCandidate{info: &SkillInfo{
			Name:        d.Name,
			Description: d.Description,
			Location:    base,
			Content:     d.Content,
			Origin:      "logical",
			Resources:   append([]string{}, d.Resources...),
			Base:        base,
		}})
	}
	return out
}

func collectCandidates(opts LoadSkillsOptions) []rawCandidate {
	var cands []rawCandidate
	for _, root := range opts.Dirs {
		cands = append(cands, candidatesFromDir(root)...)
	}
	if len(opts.Skills) > 0 {
		cands = append(cands, candidatesFromDefs(opts.Skills)...)
	}
	return cands
}

func mergeCandidates(cands []rawCandidate) (map[string]SkillInfo, []SkillSkip) {
	skills := map[string]SkillInfo{}
	var skipped []SkillSkip
	for _, c := range cands {
		if c.skip != nil {
			skipped = append(skipped, *c.skip)
			continue
		}
		info := c.info
		if _, exists := skills[info.Name]; exists {
			log.Printf("[toolnexus] duplicate skill name %q (%s) — keeping first", info.Name, info.Location)
			skipped = append(skipped, SkillSkip{Location: info.Location, Reason: SkipDuplicateName})
			continue
		}
		skills[info.Name] = *info
	}
	return skills, skipped
}

// applySkillsFilter applies the per-agent allowlist (S2): nil/empty ⇒ all; ≥1
// true ⇒ allowlist; only-false ⇒ drop-list over all-on; unknown ⇒ ignore+warn.
func applySkillsFilter(skills map[string]SkillInfo, filter map[string]bool) map[string]SkillInfo {
	if len(filter) == 0 {
		return skills
	}
	hasTrue := false
	for _, v := range filter {
		if v {
			hasTrue = true
			break
		}
	}
	for k := range filter {
		if _, ok := skills[k]; !ok {
			log.Printf("[toolnexus] skill filter name %q matched no skill", k)
		}
	}
	out := map[string]SkillInfo{}
	for name, info := range skills {
		v, present := filter[name]
		keep := false
		if hasTrue {
			keep = present && v
		} else {
			keep = !(present && !v)
		}
		if keep {
			out[name] = info
		}
	}
	return out
}

// SkillSource is the result of discovering skills.
type SkillSource struct {
	Skills map[string]SkillInfo
	Tool   Tool
}

// Prompt returns the markdown catalog for the system prompt.
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

// ListSkills discovers + validates skills from the same sources LoadSkills
// accepts, returning parsed skills plus typed skip reasons — no toolkit wired
// (SPEC.md §3, S3). The inventory is UNFILTERED (it authors the S2 allowlist).
func ListSkills(opts LoadSkillsOptions) *SkillInventory {
	merged, skipped := mergeCandidates(collectCandidates(opts))
	skills := make([]SkillInfo, 0, len(merged))
	for _, info := range merged {
		skills = append(skills, info)
	}
	return &SkillInventory{Skills: skills, Skipped: skipped}
}

// LoadSkills discovers skills under one or more roots and builds the `skill`
// loader tool. Back-compatible variadic entry; use LoadSkillsWith for data
// sources, filters, or a sample cap.
func LoadSkills(dirs ...string) *SkillSource {
	return LoadSkillsWith(LoadSkillsOptions{Dirs: dirs})
}

// LoadSkillsWith discovers skills (dirs and/or data) and builds the `skill` tool.
func LoadSkillsWith(opts LoadSkillsOptions) *SkillSource {
	merged, _ := mergeCandidates(collectCandidates(opts))
	skills := applySkillsFilter(merged, opts.Filter)
	sampleLimit := opts.SampleLimit

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
			// effLimit: 0 ⇒ default 10 (byte-identical), n>0 ⇒ cap, -1 ⇒ omit.
			effLimit := sampleLimit
			if effLimit == 0 {
				effLimit = 10
			}
			emitFiles := effLimit != -1
			var base, metaDir string
			var files []string
			if info.Origin == "logical" {
				base = info.Base
				if base == "" {
					base = fmt.Sprintf("skill://%s/", info.Name)
				}
				res := info.Resources
				if len(res) == 0 {
					emitFiles = false
				}
				if effLimit > 0 && len(res) > effLimit {
					res = res[:effLimit]
				}
				files = res
				metaDir = base
			} else {
				dir := filepath.Dir(info.Location)
				base = fileURL(dir)
				if effLimit != -1 {
					files = sampleSiblingFiles(dir, effLimit)
				}
				metaDir = dir
			}
			fileLines := make([]string, 0, len(files))
			for _, f := range files {
				fileLines = append(fileLines, fmt.Sprintf("<file>%s</file>", f))
			}
			lines := []string{
				fmt.Sprintf("<skill_content name=%q>", info.Name),
				fmt.Sprintf("# Skill: %s", info.Name),
				"",
				strings.TrimSpace(info.Content),
				"",
				fmt.Sprintf("Base directory for this skill: %s", base),
				"Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
			}
			if emitFiles {
				lines = append(lines,
					"Note: file list is sampled.",
					"",
					"<skill_files>",
					strings.Join(fileLines, "\n"),
					"</skill_files>",
				)
			}
			lines = append(lines, "</skill_content>")
			return ToolResult{
				Output:   strings.Join(lines, "\n"),
				IsError:  false,
				Metadata: map[string]any{"name": info.Name, "dir": metaDir},
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
