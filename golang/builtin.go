// Built-in tool source (source: "builtin"). The default toolset toolnexus ships
// so an agent can act with zero custom wiring — opencode's built-ins, ported
// with identical tool names + input schemas. See ../SPEC.md §4A.
//
// Every tool obeys the uniform Tool/ToolResult contract: a failure is a
// ToolResult{IsError:true}, never a panic across the boundary. Paths resolve
// relative to the process working directory unless absolute.
package toolnexus

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"regexp"
	"sort"
	"strings"
	"time"
)

// BuiltinsConfig is the object form of the single global builtin toggle. It
// mirrors MCP's enabled/disabled precedence, plus an optional per-tool map that
// drops individual builtins (all-on baseline; a name mapped to false is
// dropped; true/absent stays on; unknown names are ignored).
type BuiltinsConfig struct {
	Enabled  *bool           `json:"enabled"`
	Disabled *bool           `json:"disabled"`
	Tools    map[string]bool `json:"tools"`
}

// BuiltinsEnabled reports whether the builtin source is on. Default ON. Same
// precedence as MCP: nil ⇒ on; a bool is taken as-is; otherwise `disabled:true`
// wins, else `enabled:false` disables, otherwise on. Accepts nil, bool,
// BuiltinsConfig, *BuiltinsConfig, or a map[string]any (a parsed config object's
// top-level `builtins` key).
func BuiltinsEnabled(cfg any) bool {
	switch v := cfg.(type) {
	case nil:
		return true
	case bool:
		return v
	case BuiltinsConfig:
		return builtinsObjEnabled(v.Enabled, v.Disabled)
	case *BuiltinsConfig:
		if v == nil {
			return true
		}
		return builtinsObjEnabled(v.Enabled, v.Disabled)
	case map[string]any:
		var enabled, disabled *bool
		if d, ok := v["disabled"].(bool); ok {
			disabled = &d
		}
		if e, ok := v["enabled"].(bool); ok {
			enabled = &e
		}
		return builtinsObjEnabled(enabled, disabled)
	default:
		return true
	}
}

func builtinsObjEnabled(enabled, disabled *bool) bool {
	if disabled != nil && *disabled {
		return false
	}
	if enabled != nil && !*enabled {
		return false
	}
	return true
}

// builtinsToolMap extracts the per-tool enable/disable map from a config, or nil
// when the config carries none. Accepts BuiltinsConfig, *BuiltinsConfig, or a
// map[string]any whose `tools` value is a nested object of bools.
func builtinsToolMap(cfg any) map[string]bool {
	switch v := cfg.(type) {
	case BuiltinsConfig:
		return v.Tools
	case *BuiltinsConfig:
		if v == nil {
			return nil
		}
		return v.Tools
	case map[string]any:
		nested, ok := v["tools"].(map[string]any)
		if !ok {
			return nil
		}
		out := make(map[string]bool, len(nested))
		for name, raw := range nested {
			if b, ok := raw.(bool); ok {
				out[name] = b
			}
		}
		return out
	default:
		return nil
	}
}

// SelectBuiltins resolves the active builtin tools for a config. Whole-source-off
// wins and returns nil. Otherwise all ten are on; a per-tool map drops any
// tool mapped to false (all-on baseline; true/absent stay on; unknown names are
// ignored). Mirrors the JS selectBuiltins. SPEC §4A.
func SelectBuiltins(cfg any) []Tool {
	if !BuiltinsEnabled(cfg) {
		return nil
	}
	toolMap := builtinsToolMap(cfg)
	all := CreateBuiltinTools()
	if toolMap == nil {
		return all
	}
	out := all[:0]
	for _, t := range all {
		if enabled, ok := toolMap[t.Name]; ok && !enabled {
			continue
		}
		out = append(out, t)
	}
	return out
}

// ---------------------------------------------------------------------------
// result + arg helpers
// ---------------------------------------------------------------------------

func bErr(output string, meta map[string]any) (ToolResult, error) {
	return ToolResult{Output: output, IsError: true, Metadata: meta}, nil
}

func bOk(output string, meta map[string]any) (ToolResult, error) {
	return ToolResult{Output: output, IsError: false, Metadata: meta}, nil
}

func argString(args map[string]any, key string) (string, bool) {
	s, ok := args[key].(string)
	return s, ok
}

func argNumber(args map[string]any, key string) (float64, bool) {
	switch v := args[key].(type) {
	case float64:
		return v, true
	case float32:
		return float64(v), true
	case int:
		return float64(v), true
	case int64:
		return float64(v), true
	case json.Number:
		f, err := v.Float64()
		return f, err == nil
	}
	return 0, false
}

func argBool(args map[string]any, key string) bool {
	b, _ := args[key].(bool)
	return b
}

// asArray returns v when it is a slice/array, else an empty []any (mirrors the
// JS `Array.isArray(x) ? x : []`).
func asArray(v any) any {
	if v == nil {
		return []any{}
	}
	rv := reflect.ValueOf(v)
	if rv.Kind() == reflect.Slice || rv.Kind() == reflect.Array {
		return v
	}
	return []any{}
}

func dirArg(args map[string]any) string {
	if p, ok := argString(args, "path"); ok && p != "" {
		return p
	}
	if cwd, err := os.Getwd(); err == nil {
		return cwd
	}
	return "."
}

// builtin wraps a run function into a uniform Tool, recovering from any panic so
// a builtin never crashes the caller across the boundary.
func builtin(
	name, description string,
	inputSchema JSONSchema,
	run func(args map[string]any, ctx *ToolContext) (ToolResult, error),
) Tool {
	return Tool{
		Name:        name,
		Description: description,
		InputSchema: inputSchema,
		Source:      SourceBuiltin,
		Execute: func(args map[string]any, ctx *ToolContext) (res ToolResult, err error) {
			defer func() {
				if r := recover(); r != nil {
					res = ToolResult{Output: fmt.Sprintf("%s: %v", name, r), IsError: true}
					err = nil
				}
			}()
			if args == nil {
				args = map[string]any{}
			}
			return run(args, ctx)
		},
	}
}

// ---------------------------------------------------------------------------
// glob helpers (shared by grep + glob)
// ---------------------------------------------------------------------------

var globEscape = "\\^$.|+()[]{}"

// globToRegExp converts a glob (`*`, `**`, `?`) to an anchored RegExp, matching
// the JS reference byte-for-byte.
func globToRegExp(glob string) *regexp.Regexp {
	var b strings.Builder
	b.WriteString("^")
	for i := 0; i < len(glob); i++ {
		c := glob[i]
		switch {
		case c == '*':
			if i+1 < len(glob) && glob[i+1] == '*' {
				b.WriteString(".*")
				i++
				if i+1 < len(glob) && glob[i+1] == '/' {
					i++
				}
			} else {
				b.WriteString("[^/]*")
			}
		case c == '?':
			b.WriteString("[^/]")
		case strings.IndexByte(globEscape, c) >= 0:
			b.WriteByte('\\')
			b.WriteByte(c)
		default:
			b.WriteByte(c)
		}
	}
	b.WriteString("$")
	return regexp.MustCompile(b.String())
}

// matchGlob matches a relative path against a glob; slash-less globs test the
// basename.
func matchGlob(rel, glob string) bool {
	re := globToRegExp(glob)
	if !strings.Contains(glob, "/") {
		return re.MatchString(filepath.Base(rel))
	}
	return re.MatchString(filepath.ToSlash(rel))
}

// walkBuiltinFiles recursively lists files under root (skips node_modules/.git).
func walkBuiltinFiles(root string) []string {
	var out []string
	_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if path != root && (d.Name() == "node_modules" || d.Name() == ".git") {
				return fs.SkipDir
			}
			return nil
		}
		if d.Type().IsRegular() {
			out = append(out, path)
		}
		return nil
	})
	return out
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// ---------------------------------------------------------------------------
// individual tools
// ---------------------------------------------------------------------------

func bashTool() Tool {
	return builtin(
		"bash",
		"Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"command":     map[string]any{"type": "string", "description": "The shell command to run"},
				"workdir":     map[string]any{"type": "string", "description": "Working directory (default: process cwd)"},
				"timeout":     map[string]any{"type": "number", "description": "Timeout in milliseconds (default 60000)"},
				"description": map[string]any{"type": "string", "description": "Human-readable description of the command"},
			},
			"required":             []string{"command"},
			"additionalProperties": false,
		},
		func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			command, _ := argString(args, "command")
			if command == "" {
				return bErr("bash: command is required", nil)
			}
			workdir, _ := argString(args, "workdir")
			timeoutMs := 60000.0
			if t, ok := argNumber(args, "timeout"); ok {
				timeoutMs = t
			}
			parent := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				parent = tctx.Ctx
			}
			ctx, cancel := context.WithTimeout(parent, time.Duration(timeoutMs)*time.Millisecond)
			defer cancel()

			cmd := exec.CommandContext(ctx, "sh", "-c", command)
			if workdir != "" {
				cmd.Dir = workdir
			}
			out, runErr := cmd.CombinedOutput()
			output := string(out)
			if ctx.Err() == context.DeadlineExceeded {
				return bErr(fmt.Sprintf("bash: command timed out after %dms\n%s", int(timeoutMs), output), nil)
			}
			if runErr != nil {
				if exitErr, ok := runErr.(*exec.ExitError); ok {
					code := exitErr.ExitCode()
					return bErr(fmt.Sprintf("%s\nbash: command exited with code %d", output, code), map[string]any{"exitCode": code})
				}
				return bErr(fmt.Sprintf("bash: %v", runErr), nil)
			}
			return bOk(output, map[string]any{"exitCode": 0})
		},
	)
}

func readTool() Tool {
	return builtin(
		"read",
		"Read a UTF-8 text file. With offset/limit, return only that line window.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"path":   map[string]any{"type": "string", "description": "Path to the file to read"},
				"offset": map[string]any{"type": "number", "description": "1-based line to start from"},
				"limit":  map[string]any{"type": "number", "description": "Maximum number of lines to read"},
			},
			"required":             []string{"path"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			p, _ := argString(args, "path")
			if p == "" {
				return bErr("read: path is required", nil)
			}
			raw, err := os.ReadFile(p)
			if err != nil {
				return bErr(fmt.Sprintf("read: %v", err), nil)
			}
			content := string(raw)
			_, hasOffset := args["offset"]
			_, hasLimit := args["limit"]
			if !hasOffset && !hasLimit {
				return bOk(content, nil)
			}
			lines := strings.Split(content, "\n")
			offset := 1
			if o, ok := argNumber(args, "offset"); ok {
				offset = max(1, int(o))
			}
			start := offset - 1
			limit := len(lines) - start
			if l, ok := argNumber(args, "limit"); ok {
				limit = max(0, int(l))
			}
			if start < 0 {
				start = 0
			}
			if start > len(lines) {
				start = len(lines)
			}
			end := start + limit
			if end < start {
				end = start
			}
			if end > len(lines) {
				end = len(lines)
			}
			return bOk(strings.Join(lines[start:end], "\n"), nil)
		},
	)
}

func writeTool() Tool {
	return builtin(
		"write",
		"Write content to a file (create/overwrite), creating parent directories.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"path":    map[string]any{"type": "string", "description": "Path to write to"},
				"content": map[string]any{"type": "string", "description": "Content to write"},
			},
			"required":             []string{"path", "content"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			p, _ := argString(args, "path")
			if p == "" {
				return bErr("write: path is required", nil)
			}
			content, _ := args["content"].(string)
			if abs, err := filepath.Abs(p); err == nil {
				_ = os.MkdirAll(filepath.Dir(abs), 0o755)
			}
			if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
				return bErr(fmt.Sprintf("write: %v", err), nil)
			}
			bytes := len(content)
			return bOk(fmt.Sprintf("Wrote %d bytes to %s", bytes, p), map[string]any{"bytes": bytes})
		},
	)
}

func editTool() Tool {
	return builtin(
		"edit",
		"Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"path":       map[string]any{"type": "string", "description": "Path to the file to edit"},
				"oldString":  map[string]any{"type": "string", "description": "Exact string to replace"},
				"newString":  map[string]any{"type": "string", "description": "Replacement string"},
				"replaceAll": map[string]any{"type": "boolean", "description": "Replace all occurrences"},
			},
			"required":             []string{"path", "oldString", "newString"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			p, _ := argString(args, "path")
			if p == "" {
				return bErr("edit: path is required", nil)
			}
			oldString, ok := argString(args, "oldString")
			if !ok || oldString == "" {
				return bErr("edit: oldString is required", nil)
			}
			newString, _ := args["newString"].(string)
			raw, err := os.ReadFile(p)
			if err != nil {
				return bErr(fmt.Sprintf("edit: %v", err), nil)
			}
			content := string(raw)
			count := strings.Count(content, oldString)
			if count == 0 {
				return bErr(fmt.Sprintf("edit: oldString not found in %s", p), nil)
			}
			replaceAll := argBool(args, "replaceAll")
			var next string
			replacements := 1
			if replaceAll {
				next = strings.ReplaceAll(content, oldString, newString)
				replacements = count
			} else {
				if count > 1 {
					return bErr(fmt.Sprintf("edit: oldString is not unique in %s (%d occurrences); use replaceAll", p, count), nil)
				}
				next = strings.Replace(content, oldString, newString, 1)
			}
			if err := os.WriteFile(p, []byte(next), 0o644); err != nil {
				return bErr(fmt.Sprintf("edit: %v", err), nil)
			}
			plural := "s"
			if replacements == 1 {
				plural = ""
			}
			return bOk(fmt.Sprintf("Edited %s (%d replacement%s)", p, replacements, plural), map[string]any{"replacements": replacements})
		},
	)
}

func grepTool() Tool {
	return builtin(
		"grep",
		"Search file contents by regex under a directory. Output is file:line:text matches.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"pattern": map[string]any{"type": "string", "description": "Regular expression to search for"},
				"path":    map[string]any{"type": "string", "description": "Directory to search (default: process cwd)"},
				"include": map[string]any{"type": "string", "description": "Glob filter for file names"},
				"limit":   map[string]any{"type": "number", "description": "Maximum number of matches (default 100)"},
			},
			"required":             []string{"pattern"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			pattern, _ := argString(args, "pattern")
			if pattern == "" {
				return bErr("grep: pattern is required", nil)
			}
			re, err := regexp.Compile(pattern)
			if err != nil {
				return bErr(fmt.Sprintf("grep: invalid regex: %v", err), nil)
			}
			root := dirArg(args)
			include, _ := argString(args, "include")
			limit := 100
			if l, ok := argNumber(args, "limit"); ok {
				limit = int(l)
			}
			var matches []string
			for _, file := range walkBuiltinFiles(root) {
				if len(matches) >= limit {
					break
				}
				rel, relErr := filepath.Rel(root, file)
				if relErr != nil {
					rel = file
				}
				if include != "" && !matchGlob(rel, include) {
					continue
				}
				raw, rErr := os.ReadFile(file)
				if rErr != nil {
					continue
				}
				lines := strings.Split(string(raw), "\n")
				for i, line := range lines {
					if len(matches) >= limit {
						break
					}
					if re.MatchString(line) {
						matches = append(matches, fmt.Sprintf("%s:%d:%s", file, i+1, line))
					}
				}
			}
			return bOk(strings.Join(matches, "\n"), map[string]any{"count": len(matches)})
		},
	)
}

func globTool() Tool {
	return builtin(
		"glob",
		"List files matching a glob under a directory. Output is newline-joined relative paths.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"pattern": map[string]any{"type": "string", "description": "Glob pattern to match"},
				"path":    map[string]any{"type": "string", "description": "Directory to search (default: process cwd)"},
				"limit":   map[string]any{"type": "number", "description": "Maximum number of results (default 100)"},
			},
			"required":             []string{"pattern"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			pattern, _ := argString(args, "pattern")
			if pattern == "" {
				return bErr("glob: pattern is required", nil)
			}
			root := dirArg(args)
			limit := 100
			if l, ok := argNumber(args, "limit"); ok {
				limit = int(l)
			}
			var found []string
			for _, file := range walkBuiltinFiles(root) {
				if len(found) >= limit {
					break
				}
				rel, relErr := filepath.Rel(root, file)
				if relErr != nil {
					rel = file
				}
				if matchGlob(rel, pattern) {
					found = append(found, rel)
				}
			}
			sort.Strings(found)
			if len(found) > limit {
				found = found[:limit]
			}
			return bOk(strings.Join(found, "\n"), map[string]any{"count": min(len(found), limit)})
		},
	)
}

// stripHTML is a light HTML → text: drop scripts/styles + tags, collapse
// whitespace. Mirrors the JS reference.
var (
	scriptRe     = regexp.MustCompile(`(?is)<script.*?</script>`)
	styleRe      = regexp.MustCompile(`(?is)<style.*?</style>`)
	tagRe        = regexp.MustCompile(`<[^>]+>`)
	trailingWsRe = regexp.MustCompile(`[ \t]+\n`)
	multiNlRe    = regexp.MustCompile(`\n{3,}`)
)

func stripHTML(html string) string {
	html = scriptRe.ReplaceAllString(html, "")
	html = styleRe.ReplaceAllString(html, "")
	html = tagRe.ReplaceAllString(html, "")
	html = trailingWsRe.ReplaceAllString(html, "\n")
	html = multiNlRe.ReplaceAllString(html, "\n\n")
	return strings.TrimSpace(html)
}

func webfetchTool() Tool {
	return builtin(
		"webfetch",
		"HTTP GET a URL and return its body as text, markdown, or html.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"url":     map[string]any{"type": "string", "description": "URL to fetch"},
				"format":  map[string]any{"type": "string", "enum": []string{"text", "markdown", "html"}, "description": "Response format (default markdown)"},
				"timeout": map[string]any{"type": "number", "description": "Timeout in seconds (default 30)"},
			},
			"required":             []string{"url"},
			"additionalProperties": false,
		},
		func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			url, _ := argString(args, "url")
			if url == "" {
				return bErr("webfetch: url is required", nil)
			}
			format := "markdown"
			if f, _ := argString(args, "format"); f == "text" || f == "html" {
				format = f
			}
			timeoutS := 30.0
			if t, ok := argNumber(args, "timeout"); ok {
				timeoutS = t
			}
			parent := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				parent = tctx.Ctx
			}
			ctx, cancel := context.WithTimeout(parent, time.Duration(timeoutS*1000)*time.Millisecond)
			defer cancel()

			req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
			if err != nil {
				return bErr(fmt.Sprintf("webfetch: %v", err), nil)
			}
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				return bErr(fmt.Sprintf("webfetch: %v", err), nil)
			}
			defer resp.Body.Close()
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				return bErr(fmt.Sprintf("webfetch: %v", err), nil)
			}
			if resp.StatusCode < 200 || resp.StatusCode >= 300 {
				return bErr(fmt.Sprintf("HTTP %d", resp.StatusCode), map[string]any{"status": resp.StatusCode})
			}
			output := string(body)
			if format != "html" {
				output = stripHTML(output)
			}
			return bOk(output, map[string]any{"status": resp.StatusCode, "format": format})
		},
	)
}

func questionTool() Tool {
	return builtin(
		"question",
		"Ask the host one or more questions. Returns the questions as structured output for the host to answer.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"questions": map[string]any{
					"type":        "array",
					"description": "Questions to ask",
					"items": map[string]any{
						"type": "object",
						"properties": map[string]any{
							"question": map[string]any{"type": "string"},
							"header":   map[string]any{"type": "string"},
							"options":  map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
							"multiple": map[string]any{"type": "boolean"},
						},
						"required": []string{"question"},
					},
				},
			},
			"required":             []string{"questions"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			questions := asArray(args["questions"])
			b, err := json.Marshal(questions)
			if err != nil {
				return bErr(fmt.Sprintf("question: %v", err), nil)
			}
			return bOk(string(b), map[string]any{"questions": questions})
		},
	)
}

func todowriteTool() Tool {
	return builtin(
		"todowrite",
		"Replace the session todo list. Returns the rendered list.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"todos": map[string]any{
					"type":        "array",
					"description": "The full todo list to store",
					"items": map[string]any{
						"type": "object",
						"properties": map[string]any{
							"id":        map[string]any{"type": "string"},
							"text":      map[string]any{"type": "string"},
							"completed": map[string]any{"type": "boolean"},
						},
						"required": []string{"id", "text", "completed"},
					},
				},
			},
			"required":             []string{"todos"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			todos := asArray(args["todos"])
			rv := reflect.ValueOf(todos)
			var lines []string
			for i := 0; i < rv.Len(); i++ {
				item, _ := rv.Index(i).Interface().(map[string]any)
				text, _ := item["text"].(string)
				box := " "
				if c, _ := item["completed"].(bool); c {
					box = "x"
				}
				lines = append(lines, fmt.Sprintf("[%s] %s", box, text))
			}
			rendered := strings.Join(lines, "\n")
			if rendered == "" {
				rendered = "(no todos)"
			}
			return bOk(rendered, map[string]any{"todos": todos})
		},
	)
}

// ---------------------------------------------------------------------------
// apply_patch (opencode Begin/End Patch grammar)
// ---------------------------------------------------------------------------

type patchOp struct {
	kind    string // "add" | "update" | "delete"
	path    string
	content string   // add
	body    []string // update
}

var fileMarkerRe = regexp.MustCompile(`^\*\*\* (Add|Update|Delete) File: (.+)$`)

func parsePatch(patchText string) ([]patchOp, error) {
	lines := strings.Split(patchText, "\n")
	i := 0
	for i < len(lines) && strings.TrimSpace(lines[i]) == "" {
		i++
	}
	if i >= len(lines) || strings.TrimSpace(lines[i]) != "*** Begin Patch" {
		return nil, fmt.Errorf("missing '*** Begin Patch'")
	}
	i++
	var ops []patchOp
	for i < len(lines) {
		line := lines[i]
		if strings.TrimSpace(line) == "*** End Patch" {
			return ops, nil
		}
		if strings.TrimSpace(line) == "" {
			i++
			continue
		}
		m := fileMarkerRe.FindStringSubmatch(line)
		if m == nil {
			return nil, fmt.Errorf("unexpected line: %s", line)
		}
		kind := m[1]
		p := strings.TrimSpace(m[2])
		i++
		var body []string
		for i < len(lines) && strings.TrimSpace(lines[i]) != "*** End Patch" && !fileMarkerRe.MatchString(lines[i]) {
			body = append(body, lines[i])
			i++
		}
		switch kind {
		case "Add":
			parts := make([]string, len(body))
			for j, l := range body {
				if strings.HasPrefix(l, "+") {
					parts[j] = l[1:]
				} else {
					parts[j] = l
				}
			}
			ops = append(ops, patchOp{kind: "add", path: p, content: strings.Join(parts, "\n")})
		case "Delete":
			ops = append(ops, patchOp{kind: "delete", path: p})
		default:
			ops = append(ops, patchOp{kind: "update", path: p, body: body})
		}
	}
	return nil, fmt.Errorf("missing '*** End Patch'")
}

// applyUpdate applies an Update hunk-body to file content, returning the new
// content or erroring on a non-match.
func applyUpdate(content string, body []string) (string, error) {
	var hunks [][]string
	var cur []string
	for _, l := range body {
		if strings.HasPrefix(l, "@@") {
			if len(cur) > 0 {
				hunks = append(hunks, cur)
			}
			cur = nil
		} else {
			cur = append(cur, l)
		}
	}
	if len(cur) > 0 {
		hunks = append(hunks, cur)
	}

	result := content
	for _, hunk := range hunks {
		var oldLines, newLines []string
		for _, l := range hunk {
			switch {
			case strings.HasPrefix(l, "-"):
				oldLines = append(oldLines, l[1:])
			case strings.HasPrefix(l, "+"):
				newLines = append(newLines, l[1:])
			case strings.HasPrefix(l, " "):
				oldLines = append(oldLines, l[1:])
				newLines = append(newLines, l[1:])
			default:
				oldLines = append(oldLines, l)
				newLines = append(newLines, l)
			}
		}
		oldBlock := strings.Join(oldLines, "\n")
		newBlock := strings.Join(newLines, "\n")
		if len(oldBlock) > 0 {
			if !strings.Contains(result, oldBlock) {
				return "", fmt.Errorf("hunk does not match file contents")
			}
			result = strings.Replace(result, oldBlock, newBlock, 1)
		} else {
			suffix := ""
			if result != "" && !strings.HasSuffix(result, "\n") {
				suffix = "\n"
			}
			result = result + suffix + newBlock
		}
	}
	return result, nil
}

func applyPatchTool() Tool {
	return builtin(
		"apply_patch",
		"Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"patchText": map[string]any{"type": "string", "description": "The patch text in Begin/End Patch format"},
			},
			"required":             []string{"patchText"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			patchText, _ := argString(args, "patchText")
			if patchText == "" {
				return bErr("apply_patch: patchText is required", nil)
			}
			ops, err := parsePatch(patchText)
			if err != nil {
				return bErr(fmt.Sprintf("apply_patch: %v", err), nil)
			}
			// Stage every write/delete first; only touch the filesystem once all
			// hunks apply.
			type pendingWrite struct {
				path    string
				content string
			}
			var writes []pendingWrite
			var deletes []string
			for _, op := range ops {
				switch op.kind {
				case "add":
					if fileExists(op.path) {
						return bErr(fmt.Sprintf("apply_patch: file already exists: %s", op.path), nil)
					}
					writes = append(writes, pendingWrite{op.path, op.content})
				case "delete":
					if !fileExists(op.path) {
						return bErr(fmt.Sprintf("apply_patch: file not found: %s", op.path), nil)
					}
					deletes = append(deletes, op.path)
				default:
					raw, rErr := os.ReadFile(op.path)
					if rErr != nil {
						return bErr(fmt.Sprintf("apply_patch: %v", rErr), nil)
					}
					next, uErr := applyUpdate(string(raw), op.body)
					if uErr != nil {
						return bErr(fmt.Sprintf("apply_patch: %v", uErr), nil)
					}
					writes = append(writes, pendingWrite{op.path, next})
				}
			}
			for _, w := range writes {
				if abs, aErr := filepath.Abs(w.path); aErr == nil {
					_ = os.MkdirAll(filepath.Dir(abs), 0o755)
				}
				if err := os.WriteFile(w.path, []byte(w.content), 0o644); err != nil {
					return bErr(fmt.Sprintf("apply_patch: %v", err), nil)
				}
			}
			for _, d := range deletes {
				_ = os.Remove(d)
			}
			var added, updated, deleted int
			for _, op := range ops {
				switch op.kind {
				case "add":
					added++
				case "update":
					updated++
				case "delete":
					deleted++
				}
			}
			plural := "s"
			if len(ops) == 1 {
				plural = ""
			}
			return bOk(
				fmt.Sprintf("Applied patch: %d file operation%s", len(ops), plural),
				map[string]any{"added": added, "updated": updated, "deleted": deleted},
			)
		},
	)
}

// CreateBuiltinTools builds the ten built-in tools (each source:"builtin").
// The order is fixed for parity: bash, read, write, edit, grep, glob, webfetch,
// question, apply_patch, todowrite.
func CreateBuiltinTools() []Tool {
	return []Tool{
		bashTool(),
		readTool(),
		writeTool(),
		editTool(),
		grepTool(),
		globTool(),
		webfetchTool(),
		questionTool(),
		applyPatchTool(),
		todowriteTool(),
	}
}
