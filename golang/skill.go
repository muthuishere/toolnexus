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
	// Detail carries the NATIVE parser error behind the skip, when there is one
	// (malformed-frontmatter). It is diagnostic only: the message is whatever the
	// port's YAML library said and is deliberately NOT compared across ports —
	// `Reason` stays byte-identical, `Detail` stays native (SPEC.md §3, ADR 0028).
	Detail string
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

// lenientKeyRe matches ONE top-level `key: rest-of-line` at COLUMN 0. It is the
// fallback rescue read (ADR 0028) and never runs before the YAML parser.
var lenientKeyRe = regexp.MustCompile(`^([A-Za-z0-9_][A-Za-z0-9_.-]*):[ \t]*(.*)$`)

// lenientOpeners are the YAML constructs the rescue read does NOT implement:
// block scalars, anchors, aliases, flow collections and tags. A value opening
// with one of these is taken as NOTHING rather than as garbage — a half-broken
// block scalar degrades to "no description", never to an invented one.
const lenientOpeners = "|>&*[{!"

// lenientKeys are the only two keys the rescue read will take. Everything else
// in a frontmatter YAML has already refused is left alone.
var lenientKeys = map[string]bool{"name": true, "description": true}

type frontmatter struct {
	Name        string
	Description string
}

// parseFrontmatter parses the `---`-fenced YAML header with a REAL YAML parser,
// and only when that parser refuses falls back to a line-wise `key: rest-of-line`
// rescue for `name`/`description` (ADR 0028).
//
// The order is the whole point. A line-wise read that ran FIRST would misparse
// legitimate YAML the six other ports handle today — `description: |`,
// `description: >`, a plain scalar continued on the next line, `name: !!str x` —
// because rest-of-line is empty or a block marker there. Running it only over a
// block a real parser has ALREADY refused means it can never see a file whose
// YAML semantics matter: by construction such a file has none to preserve.
//
// `malformed` is true only when fences are present, the YAML failed to parse AND
// the rescue read could not recover a name — so a genuinely malformed file is
// still refused, and the inventory (S3) reports the right skip reason.
// `detail` carries the native parser error for a skip record; it is empty
// whenever the frontmatter parsed.
func parseFrontmatter(text string) (data frontmatter, content string, malformed bool, detail string) {
	m := frontmatterRe.FindStringSubmatch(text)
	if m == nil {
		return frontmatter{}, text, false, ""
	}
	var raw map[string]any
	err := yaml.Unmarshal([]byte(m[1]), &raw)
	if err == nil && raw != nil {
		fm := frontmatter{}
		wrong := ""
		for _, key := range []string{"name", "description"} {
			v, present := raw[key]
			if !present {
				continue
			}
			s, ok := scalarString(v)
			if !ok {
				// Present but STRUCTURALLY WRONG (a sequence, a mapping, null).
				// YAML libraries disagree about inputs like `description:
				// [unterminated` — some throw, some error-recover it into a
				// sequence — so the rescue must trigger on the recovered shape
				// too, or the seven ports' skip tables diverge on the same file
				// (DECISIONS A10).
				wrong = key
				break
			}
			if key == "name" {
				fm.Name = s
			} else {
				fm.Description = s
			}
		}
		if wrong == "" {
			return fm, m[2], false, ""
		}
		detail = fmt.Sprintf("frontmatter key %q is %T, want a scalar", wrong, raw[wrong])
	} else if err != nil {
		detail = err.Error()
	} else {
		detail = "frontmatter is not a YAML mapping"
	}
	fm := lenientFrontmatter(m[1])
	if fm.Name == "" {
		return fm, m[2], true, detail
	}
	return fm, m[2], false, ""
}

// lenientFrontmatter rescues `name`/`description` from a frontmatter block YAML
// has already refused (ADR 0028). Guards, all three load-bearing:
//
//   - COLUMN 0 only — an indented line is a continuation or a nested mapping,
//     never a top-level key;
//   - FIRST WINS — a repeated key keeps the first value, matching YAML's own
//     document order for the keys that survive;
//   - REFUSE an opener — a value starting `| > & * [ { !` (or empty) is a
//     construct this read does not implement, so it takes nothing.
func lenientFrontmatter(block string) frontmatter {
	var fm frontmatter
	seen := map[string]bool{}
	for _, raw := range strings.Split(block, "\n") {
		raw = strings.TrimSuffix(raw, "\r")
		if raw == "" {
			continue
		}
		if c := raw[0]; c == ' ' || c == '\t' || c == '#' {
			continue // indented continuation, or a comment — not a top-level key
		}
		m := lenientKeyRe.FindStringSubmatch(raw)
		if m == nil {
			continue
		}
		key, value := m[1], strings.TrimSpace(m[2])
		if !lenientKeys[key] || seen[key] {
			continue
		}
		if value == "" || strings.ContainsRune(lenientOpeners, rune(value[0])) {
			continue
		}
		seen[key] = true
		value = unquoteScalar(value)
		switch key {
		case "name":
			fm.Name = value
		case "description":
			fm.Description = value
		}
	}
	return fm
}

// unquoteScalar strips one matching pair of surrounding quotes. It is NOT a YAML
// unescape: the rescue read never claims to implement YAML, only to hand back the
// obvious literal.
func unquoteScalar(v string) string {
	if len(v) > 1 && v[0] == v[len(v)-1] && (v[0] == '"' || v[0] == '\'') {
		return v[1 : len(v)-1]
	}
	return v
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

// walkSkillFiles returns every SKILL.md under root in a DETERMINISTIC order:
// by DEPTH ascending (how many path segments the file sits under), then by
// Unicode CODE POINT within a depth. Discovery order is part of the contract,
// not a property of the filesystem — first-wins dedupe is meaningless if
// "first" depends on the order a directory happened to be read in.
//
// Depth comes FIRST, and that is the correction that matters (DECISIONS A15).
// A pure code-point sort does not implement "a top-level copy beats a nested
// one": it makes the winner depend on the skill's first letter relative to a
// sibling DIRECTORY's name. On the real corpus `docx/SKILL.md` beats
// `synced/<uuid>/docx/SKILL.md` because `d` < `s` — but `xlsx/SKILL.md` LOSES to
// `synced/<uuid>/xlsx/SKILL.md` because `x` > `s`. Same shape, opposite winner,
// decided by an unrelated directory's name. Ordering by depth first makes the
// shallower copy win every time, and leaves code point as the tie-break WITHIN
// a depth, which is what it was always for.
//
// NOTE for anyone tempted to "improve" the tie-break: Go's `<` on strings is a
// BYTE-WISE comparison of the UTF-8 bytes, which orders identically to Unicode
// CODE POINTS. That is the normative order (DECISIONS A1c) — do not replace it
// with a locale collator, a case-folding compare, or a segment-aware one.
// (UTF-16 hosts need an explicit code-point comparer here because code-UNIT
// order disagrees above U+FFFF; Go does not.) A symlink sorts at its DISCOVERED
// path, never its target.
func walkSkillFiles(root string) []string {
	out := walkSkillFilesUnordered(root)
	rel := make(map[string]string, len(out))
	depth := make(map[string]int, len(out))
	for _, p := range out {
		r, err := filepath.Rel(root, p)
		if err != nil {
			r = p
		}
		r = filepath.ToSlash(r)
		rel[p] = r
		depth[p] = strings.Count(r, "/")
	}
	sort.Slice(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if depth[a] != depth[b] {
			return depth[a] < depth[b]
		}
		if rel[a] != rel[b] {
			return rel[a] < rel[b]
		}
		return a < b
	})
	return out
}

func walkSkillFilesUnordered(root string) []string {
	var out []string
	stack := []string{root}
	seen := map[string]bool{}
	for len(stack) > 0 {
		dir := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		// os.ReadDir IS sorted by filename (code point, since Go compares
		// strings byte-wise over UTF-8). That does NOT make this walk ordered —
		// the stack is LIFO — which is why walkSkillFiles re-sorts its whole
		// result explicitly above. Recorded so the guarantee and its limits are
		// written down rather than rediscovered (DECISIONS A24).
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

// sampleSiblingFiles returns the skill's sibling resources for the
// `<skill_files>` sample list, in CODE-POINT order of each file's path relative
// to the skill directory, SORTED BEFORE the cap is applied.
//
// The ordering is load-bearing TWICE, which is why it is computed rather than
// inherited from the walk (DECISIONS A22/A24/A25):
//
//   - it is the order of SHIPPED, MODEL-VISIBLE TEXT — `<skill_files>` goes
//     straight into the tool result, so two ports that disagree here emit
//     different bytes for the same skill;
//   - and because the list is CAPPED, the order also decides WHICH files are
//     sampled at all. Sorting must therefore happen BEFORE the cap. This is a
//     CONTENT bug, not a cosmetic one (ADR-0004 K1).
//
// The rule is a GLOBAL sort over relative paths, with plain code point — not
// depth-then-code-point, and not a per-directory sort during traversal (A25):
//
//   - A15's depth rule exists to resolve duplicate NAMES in discovery, i.e. to
//     decide WHICH file a name resolves to. It has no business ordering a flat
//     file listing, where it would interleave `a-root.txt, z-root.txt,
//     alpha/f.txt` instead of `a-root.txt, alpha/f.txt, z-root.txt`.
//   - A global sort is a function of the FILE SET and the cap alone. A
//     per-directory sort during traversal is a function of the TRAVERSAL, and
//     would require every port to reproduce the same stack discipline to emit
//     the same bytes.
//
// os.ReadDir IS sorted by filename, so this looked correct by construction —
// the same shape as the §0.10 prompt sort. It was not: the walk pushed
// subdirectories onto a LIFO stack and emitted them in REVERSE code-point order
// (alpha/ beta/ zeta/ came back zeta, beta, alpha) while files within a single
// directory were sorted. Correct in the small, wrong in the whole.
func sampleSiblingFiles(dir string, limit int) []string {
	var all []string
	stack := []string{dir}
	seen := map[string]bool{}
	for len(stack) > 0 {
		cur := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		entries, err := os.ReadDir(cur)
		if err != nil {
			continue
		}
		for _, entry := range entries {
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
				all = append(all, full)
			}
		}
	}
	// Code point by construction: Go's `<` on strings is byte-wise over UTF-8,
	// which orders identically to Unicode code points. Do NOT replace this with a
	// locale collator or a UTF-16 code-unit compare (see Prompt()). The walk
	// above is deliberately NOT relied on for order — it is a LIFO stack.
	rel := make(map[string]string, len(all))
	for _, p := range all {
		r, err := filepath.Rel(dir, p)
		if err != nil {
			r = p
		}
		rel[p] = filepath.ToSlash(r)
	}
	sort.Slice(all, func(i, j int) bool {
		if rel[all[i]] != rel[all[j]] {
			return rel[all[i]] < rel[all[j]]
		}
		return all[i] < all[j]
	})
	// Cap AFTER the sort: the two together decide the CONTENT of the sample.
	if limit >= 0 && len(all) > limit {
		all = all[:limit]
	}
	return all
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
		data, content, malformed, detail := parseFrontmatter(string(raw))
		if malformed {
			out = append(out, rawCandidate{skip: &SkillSkip{Location: file, Reason: SkipMalformed, Detail: detail}})
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
	// Skipped is every candidate that did NOT become a skill, with its typed
	// reason (and the native parser error in Detail). A host must not need a
	// second ListSkills pass to learn that 6 of 87 SKILL.md files vanished —
	// the loading path reports its own losses (ADR 0028). Unfiltered, like
	// ListSkills' inventory: the S2 allowlist removes skills, not candidates.
	Skipped []SkillSkip
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
	// SPEC §0.10 pins this prompt BYTE-IDENTICAL across ports, so its order is
	// part of the contract, not a presentation detail. Go's `<` on strings is a
	// BYTE-WISE comparison of the UTF-8 bytes, which orders identically to
	// Unicode CODE POINTS — the normative rule (DECISIONS A22, same note as the
	// discovery sort). Do NOT "improve" this into a locale collator
	// (locale-dependent, so not even stable across machines) or a UTF-16
	// code-unit compare (disagrees above U+FFFF).
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
	merged, skipped := mergeCandidates(collectCandidates(opts))
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
				// Code point again, by construction: sort.Strings compares with
				// `<`, i.e. byte-wise over UTF-8. This list is user-visible (it
				// goes to the model in an error result), so it follows the same
				// rule as the §0.10 prompt.
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

	return &SkillSource{Skills: skills, Tool: tool, Skipped: skipped}
}

// fileURL renders an absolute directory path as a file:// URL (mirrors Node's
// pathToFileURL).
func fileURL(dir string) string {
	u := &url.URL{Scheme: "file", Path: filepath.ToSlash(dir)}
	return u.String()
}
