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
	"sync"
	"time"
	"unicode/utf8"
)

// BuiltinsConfig is the object form of the single global builtin toggle. It
// mirrors MCP's enabled/disabled precedence, plus an optional per-tool map that
// drops individual builtins (all-on baseline; a name mapped to false is
// dropped; true/absent stays on; unknown names are ignored).
type BuiltinsConfig struct {
	Enabled  *bool           `json:"enabled"`
	Disabled *bool           `json:"disabled"`
	Tools    map[string]bool `json:"tools"`

	// Shell is the argv prefix `bash` runs a command with — {"sh","-c"},
	// {"cmd","/d","/s","/c"}, {"powershell","-NoProfile","-Command"},
	// {"bash","-lc"}. Set, it is used verbatim. Absent, an interpreter is
	// DETECTED at toolkit construction: `sh -c` on POSIX; on Windows %COMSPEC%
	// first, then pwsh, powershell, and a POSIX bash if one resolves. SPEC §4A,
	// ADR 0034 D1.
	Shell []string `json:"shell"`

	// BaseDir is the directory RELATIVE paths resolve against, for every builtin
	// that touches the filesystem — including the paths inside apply_patch's
	// patch text, and as bash's default workdir. Empty ⇒ the host process
	// working directory, which is the behaviour before ADR 0034, so a host that
	// sets nothing sees no change.
	BaseDir string `json:"baseDir"`

	// ConfineToBaseDir refuses any path whose CANONICAL form lies outside
	// BaseDir — through a symlink or a Windows junction too — and refuses
	// Windows reserved device names. Default off. It is a guarantee about path
	// resolution in the file builtins, NOT a sandbox: a command run by `bash`
	// still reaches the whole filesystem (ADR 0034 D3).
	ConfineToBaseDir bool `json:"confineToBaseDir"`
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
	tools, _ := SelectBuiltinsChecked(cfg)
	return tools
}

// SelectBuiltinsChecked is SelectBuiltins plus the one construction-time
// failure the host boundary introduces: when `bash` survives the toggles and no
// interpreter resolves, that is a configuration fact and it is reported HERE,
// not on turn fourteen of a paid run (ADR 0034 D1).
func SelectBuiltinsChecked(cfg any) ([]Tool, error) {
	if !BuiltinsEnabled(cfg) {
		return nil, nil
	}
	toolMap := builtinsToolMap(cfg)
	all := CreateBuiltinToolsWith(cfg)
	out := all[:0]
	for _, t := range all {
		if enabled, ok := toolMap[t.Name]; ok && !enabled {
			continue
		}
		out = append(out, t)
	}
	for _, t := range out {
		if t.Name == "bash" {
			if _, err := BuiltinShell(cfg); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

// ---------------------------------------------------------------------------
// the host boundary: interpreter, base directory, confinement (ADR 0034)
// ---------------------------------------------------------------------------

// builtinKillGraceMs is how long a job gets between "please stop" and "stop".
// Fixed, and identical in every port, so a timeout means the same thing
// everywhere: a test runner that gets SIGTERM removes its temp directories, one
// that gets SIGKILL does not.
const builtinKillGraceMs = 2000

// builtinEnv is what the builtins may know about the host: which interpreter to
// run, which directory relative paths mean, and whether to refuse the ones that
// leave it. A nil *builtinEnv behaves exactly as the pre-0.20 builtins did.
type builtinEnv struct {
	shell    []string
	shellErr error
	baseDir  string
	confine  bool
}

// detectShell returns the first candidate interpreter that resolves on PATH.
// The error names every candidate tried, because "sh: not found" on turn 14 of
// a paid run is the expensive place to learn that a box has no shell.
func detectShell() ([]string, error) {
	cands := shellCandidates()
	tried := make([]string, 0, len(cands))
	for _, argv := range cands {
		tried = append(tried, argv[0])
		if _, err := exec.LookPath(argv[0]); err == nil {
			return argv, nil
		}
	}
	return nil, fmt.Errorf("no shell interpreter found (tried: %s); set Builtins.Shell, or disable the bash builtin with Builtins.Tools{\"bash\": false}",
		strings.Join(tried, ", "))
}

// newBuiltinEnv resolves the boundary once, at toolkit construction. A shell
// that cannot be found is reported as an error only when `bash` is actually
// enabled — a host that disabled it should run fine on a box with no shell.
func newBuiltinEnv(cfg any) *builtinEnv {
	env := &builtinEnv{}
	switch v := cfg.(type) {
	case BuiltinsConfig:
		env.shell, env.baseDir, env.confine = v.Shell, v.BaseDir, v.ConfineToBaseDir
	case *BuiltinsConfig:
		if v != nil {
			env.shell, env.baseDir, env.confine = v.Shell, v.BaseDir, v.ConfineToBaseDir
		}
	case map[string]any:
		if raw, ok := v["shell"].([]any); ok {
			for _, part := range raw {
				if s, ok := part.(string); ok {
					env.shell = append(env.shell, s)
				}
			}
		}
		if raw, ok := v["shell"].([]string); ok {
			env.shell = append(env.shell, raw...)
		}
		if b, ok := v["baseDir"].(string); ok {
			env.baseDir = b
		}
		if b, ok := v["confineToBaseDir"].(bool); ok {
			env.confine = b
		}
	}
	if len(env.shell) == 0 {
		env.shell, env.shellErr = detectShell()
	}
	return env
}

// shellArgv is the interpreter for a bash call: the resolved argv, or the
// historical `sh -c` when there is no env at all.
func (e *builtinEnv) shellArgv() ([]string, error) {
	if e == nil || len(e.shell) == 0 {
		if e != nil && e.shellErr != nil {
			return nil, e.shellErr
		}
		return []string{"sh", "-c"}, nil
	}
	return e.shell, nil
}

// shellLabel is the human-readable interpreter, for metadata.shell.
func (e *builtinEnv) shellLabel() string {
	argv, err := e.shellArgv()
	if err != nil {
		return ""
	}
	return strings.Join(argv, " ")
}

// dir is the working directory a command or a walk starts from.
func (e *builtinEnv) dir() string {
	if e != nil && e.baseDir != "" {
		return e.baseDir
	}
	if cwd, err := os.Getwd(); err == nil {
		return cwd
	}
	return "."
}

// resolvePath maps a tool-supplied path onto the filesystem: relative to
// BaseDir (or, with none, to the process cwd exactly as before), and refused
// when confinement is on and the canonical target is outside the base.
func (e *builtinEnv) resolvePath(p string) (string, error) {
	if e == nil {
		return p, nil
	}
	if e.confine && e.baseDir == "" {
		return "", fmt.Errorf("confineToBaseDir is set but baseDir is empty")
	}
	full := p
	if e.baseDir != "" && !filepath.IsAbs(p) {
		full = filepath.Join(e.baseDir, p)
	}
	if !e.confine {
		return full, nil
	}
	if reservedDeviceName(full) {
		return "", fmt.Errorf("%s names a reserved device, which is not a file inside %s", p, e.baseDir)
	}
	base, err := canonicalPath(e.baseDir)
	if err != nil {
		return "", fmt.Errorf("cannot canonicalise baseDir %s: %v", e.baseDir, err)
	}
	target, err := canonicalPath(full)
	if err != nil {
		return "", fmt.Errorf("cannot canonicalise %s: %v", p, err)
	}
	rel, err := filepath.Rel(base, target)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("%s resolves outside baseDir %s", p, e.baseDir)
	}
	return full, nil
}

// canonicalPath resolves a path to compare it with another. Symlinks — and, on
// Windows, directory junctions and 8.3 short names — are resolved on the
// DEEPEST EXISTING ancestor and the remaining segments re-attached, because a
// file `write` is about to create has no real path, and a check that only works
// on existing files is not a check for `write`.
func canonicalPath(p string) (string, error) {
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", err
	}
	tail := ""
	cur := abs
	for {
		if resolved, err := realPath(cur); err == nil {
			if tail == "" {
				return resolved, nil
			}
			return filepath.Join(resolved, tail), nil
		}
		parent := filepath.Dir(cur)
		if parent == cur {
			return abs, nil
		}
		tail = filepath.Join(filepath.Base(cur), tail)
		cur = parent
	}
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

// dirArg is the walk root for grep/glob: the `path` argument resolved through
// the host boundary, or the boundary's own directory when none is given.
func dirArg(args map[string]any, env *builtinEnv) (string, error) {
	if p, ok := argString(args, "path"); ok && p != "" {
		return env.resolvePath(p)
	}
	return env.dir(), nil
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
//
// Ordering is CODE POINT, by construction and documented as such: filepath.
// WalkDir walks in LEXICAL order (it sorts each directory's entries by name),
// and Go compares strings byte-wise over UTF-8, which is code-point order. The
// glob builtin's results are user- and model-visible, so do not replace this
// with an unsorted read — os.File.Readdirnames in particular is NOT sorted
// (DECISIONS A24).
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

func bashTool(env *builtinEnv) Tool {
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
			argv, shellErr := env.shellArgv()
			if shellErr != nil {
				return bErr(fmt.Sprintf("bash: %v", shellErr), nil)
			}
			workdir, _ := argString(args, "workdir")
			if workdir == "" {
				workdir = env.dir()
			} else if resolved, err := env.resolvePath(workdir); err != nil {
				return bErr(fmt.Sprintf("bash: %v", err), nil)
			} else {
				workdir = resolved
			}
			timeoutMs := 60000.0
			if t, ok := argNumber(args, "timeout"); ok {
				timeoutMs = t
			}
			parent := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				parent = tctx.Ctx
			}

			// NOT exec.CommandContext: its kill reaches the direct child only —
			// the interpreter — and leaves the real command running, reparented.
			// startJob puts the command in its own process group (POSIX) or Job
			// Object (Windows) so the whole job can be stopped. SPEC §4A, ADR 0034 D4.
			cmd := exec.Command(argv[0], append(argv[1:len(argv):len(argv)], command)...)
			cmd.Dir = workdir
			var buf lockedBuffer
			cmd.Stdout = &buf
			cmd.Stderr = &buf
			meta := map[string]any{"shell": env.shellLabel()}
			if err := startJob(cmd); err != nil {
				return bErr(fmt.Sprintf("bash: %v", err), meta)
			}

			done := make(chan error, 1)
			go func() { done <- cmd.Wait() }()

			timer := time.NewTimer(time.Duration(timeoutMs) * time.Millisecond)
			defer timer.Stop()

			var runErr error
			stopped := "" // "timeout" | "cancelled"
			select {
			case runErr = <-done:
			case <-timer.C:
				stopped = "timeout"
			case <-parent.Done():
				stopped = "cancelled"
			}

			killedTree := false
			if stopped != "" {
				// Ask, wait out the grace window, then insist. A runner that gets
				// SIGTERM cleans up its temp directories; one that gets SIGKILL
				// does not.
				killedTree = signalJob(cmd, true) == nil
				grace := time.NewTimer(builtinKillGraceMs * time.Millisecond)
				select {
				case runErr = <-done:
				case <-grace.C:
					if err := signalJob(cmd, false); err == nil {
						killedTree = true
					}
					runErr = <-done
				}
				grace.Stop()
				meta["timedOut"] = stopped == "timeout"
				meta["killedTree"] = killedTree
				output := buf.String()
				if stopped == "cancelled" {
					return bErr(fmt.Sprintf("bash: command cancelled\n%s", output), meta)
				}
				return bErr(fmt.Sprintf("bash: command timed out after %dms\n%s", int(timeoutMs), output), meta)
			}

			output := buf.String()
			if runErr != nil {
				if exitErr, ok := runErr.(*exec.ExitError); ok {
					code := exitErr.ExitCode()
					meta["exitCode"] = code
					return bErr(fmt.Sprintf("%s\nbash: command exited with code %d", output, code), meta)
				}
				return bErr(fmt.Sprintf("bash: %v", runErr), meta)
			}
			meta["exitCode"] = 0
			return bOk(output, meta)
		},
	)
}

// lockedBuffer collects combined stdout+stderr. CombinedOutput cannot be used
// any more: it waits for the pipes to close, and an orphaned grandchild holds
// them open long past the kill — so the call that is supposed to time out at
// 30s would return when the thing it killed finally lets go.
type lockedBuffer struct {
	mu  sync.Mutex
	buf strings.Builder
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func readTool(env *builtinEnv) Tool {
	return builtin(
		"read",
		"Read a file. A recognised media file (png/jpg/jpeg/gif/webp/pdf/mp3/wav) comes back as a content part; anything else is read as UTF-8 text, and with offset/limit only that line window.",
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
			full, rErr := env.resolvePath(p)
			if rErr != nil {
				return bErr(fmt.Sprintf("read: %v", rErr), nil)
			}
			raw, err := os.ReadFile(full)
			if err != nil {
				return bErr(fmt.Sprintf("read: %v", err), nil)
			}
			// §6 media table: a recognised media extension comes back as a §1B part,
			// with output describing it. Fixed table — never sniffed, never resolved
			// through a platform mime database (which varies per machine).
			if e, ok := lookupMedia(full); ok {
				part := encodeBytes(raw, e.mime, e.partType)
				if part.Err() != nil {
					return bErr(fmt.Sprintf("read: %v", part.Err()), nil)
				}
				if part.Type == PartFile {
					part.Name = filepath.Base(p)
				}
				res, _ := bOk(fmt.Sprintf("%s (%s, %d bytes)", p, e.mime, len(raw)), nil)
				res.Parts = []ContentPart{part}
				return res, nil
			}
			if !utf8.Valid(raw) {
				return bErr(fmt.Sprintf("read: %s is not valid UTF-8 text and its extension is not a recognised media type", p), nil)
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

func writeTool(env *builtinEnv) Tool {
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
			full, rErr := env.resolvePath(p)
			if rErr != nil {
				return bErr(fmt.Sprintf("write: %v", rErr), nil)
			}
			if abs, err := filepath.Abs(full); err == nil {
				_ = os.MkdirAll(filepath.Dir(abs), 0o755)
			}
			if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
				return bErr(fmt.Sprintf("write: %v", err), nil)
			}
			bytes := len(content)
			return bOk(fmt.Sprintf("Wrote %d bytes to %s", bytes, p), map[string]any{"bytes": bytes})
		},
	)
}

func editTool(env *builtinEnv) Tool {
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
			full, rErr := env.resolvePath(p)
			if rErr != nil {
				return bErr(fmt.Sprintf("edit: %v", rErr), nil)
			}
			raw, err := os.ReadFile(full)
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
			if err := os.WriteFile(full, []byte(next), 0o644); err != nil {
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

func grepTool(env *builtinEnv) Tool {
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
			root, rootErr := dirArg(args, env)
			if rootErr != nil {
				return bErr(fmt.Sprintf("grep: %v", rootErr), nil)
			}
			include, _ := argString(args, "include")
			limit := 100
			if l, ok := argNumber(args, "limit"); ok {
				limit = int(l)
			}
			// COLLECT → SORT → TRUNCATE (DECISIONS A26). This had the worse half
			// of the defect: the cap broke the walk mid-traversal AND there was
			// NO sort at all, so both the content and the order of what the
			// model saw were whatever filepath.WalkDir happened to reach first.
			// An ABSENT sort over shipped output is invisible to a grep for
			// comparators, which is why this survived three audits.
			type grepHit struct {
				rel  string
				line int
				text string
			}
			var hits []grepHit
			for _, file := range walkBuiltinFiles(root) {
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
				for i, line := range strings.Split(string(raw), "\n") {
					if re.MatchString(line) {
						// The emitted path is the SORT KEY — `/`-separated and
						// relative to the walk root (SPEC §4A: "SORT ON THE SAME
						// STRING you emit"). This used to print `file`, the joined
						// path: absolute on POSIX, backslashed on Windows, and
						// ordered by a string it did not show.
						slash := filepath.ToSlash(rel)
						hits = append(hits, grepHit{rel: slash, line: i + 1,
							text: fmt.Sprintf("%s:%d:%s", slash, i+1, line)})
					}
				}
			}
			// By relative path in code point, then by line number ascending — a
			// pure function of the matches, with no traversal order left in it.
			sort.Slice(hits, func(i, j int) bool {
				if hits[i].rel != hits[j].rel {
					return hits[i].rel < hits[j].rel
				}
				return hits[i].line < hits[j].line
			})
			if len(hits) > limit {
				hits = hits[:limit]
			}
			matches := make([]string, 0, len(hits))
			for _, h := range hits {
				matches = append(matches, h.text)
			}
			return bOk(strings.Join(matches, "\n"), map[string]any{"count": len(matches)})
		},
	)
}

func globTool(env *builtinEnv) Tool {
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
			root, rootErr := dirArg(args, env)
			if rootErr != nil {
				return bErr(fmt.Sprintf("glob: %v", rootErr), nil)
			}
			limit := 100
			if l, ok := argNumber(args, "limit"); ok {
				limit = int(l)
			}
			// COLLECT → SORT → TRUNCATE, in that order (DECISIONS A26). The cap
			// used to break the walk BEFORE the sort, so the filesystem walk
			// chose WHICH files the model saw and sort.Strings only ordered the
			// survivors. filepath.WalkDir's lexical order did not save this: its
			// order is per-directory-level, while the sort is over full relative
			// paths, so selection and presentation used two DIFFERENT rules —
			// they disagree wherever a directory `alpha/` sits beside a file
			// `alpha-b.txt`. A capped listing's order decides its CONTENT.
			var found []string
			for _, file := range walkBuiltinFiles(root) {
				rel, relErr := filepath.Rel(root, file)
				if relErr != nil {
					rel = file
				}
				if matchGlob(rel, pattern) {
					found = append(found, filepath.ToSlash(rel))
				}
			}
			// Code point by construction: Go compares strings byte-wise over
			// UTF-8. Never a locale collator or a UTF-16 code-unit compare.
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

// renderQuestionPrompt renders the questions into a human-readable Request.prompt
// (§10). Byte-identical across ports: each question's text in order, " (options:
// a, b, c)" appended when it has non-empty options, joined by "\n" (no trailing
// newline). header is NOT rendered — it survives in data.questions. Mirrors the
// JS renderQuestionPrompt.
func renderQuestionPrompt(questions []any) string {
	lines := make([]string, 0, len(questions))
	for _, q := range questions {
		item, _ := q.(map[string]any)
		line := ""
		if s, ok := item["question"].(string); ok {
			line = s
		}
		opts := questionOptions(item["options"])
		if len(opts) > 0 {
			line += " (options: " + strings.Join(opts, ", ") + ")"
		}
		lines = append(lines, line)
	}
	return strings.Join(lines, "\n")
}

// questionOptions coerces the schema-string[] options into a []string, matching
// the JS `options.join(", ")`. Non-slice / empty ⇒ nil.
func questionOptions(v any) []string {
	arr, ok := v.([]any)
	if !ok || len(arr) == 0 {
		return nil
	}
	out := make([]string, 0, len(arr))
	for _, e := range arr {
		if s, ok := e.(string); ok {
			out = append(out, s)
		} else {
			out = append(out, fmt.Sprintf("%v", e))
		}
	}
	return out
}

func questionTool() Tool {
	return builtin(
		"question",
		"Ask the host one or more questions. Suspends via a kind:\"question\" Request (§10); the host's waitFor resolves it and the answer is returned to the model.",
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
		func(args map[string]any, ctx *ToolContext) (ToolResult, error) {
			questions := asArray(args["questions"])
			// Re-executed after the host's waitFor resolved (§10 loop rule): the
			// resolution IS the answer, as with kind:"input" — forward it verbatim
			// to the model.
			if ctx != nil && ctx.Answer != nil {
				data := ctx.Answer.Data
				if data == nil {
					data = map[string]any{}
				}
				b, err := json.Marshal(data)
				if err != nil {
					return bErr(fmt.Sprintf("question: %v", err), nil)
				}
				return bOk(string(b), nil)
			}
			// First call: suspend. A question is just a §10 Request with kind:"question".
			qs, _ := questions.([]any)
			return Pending(Request{
				Kind:   "question",
				Prompt: renderQuestionPrompt(qs),
				Data:   map[string]any{"questions": questions},
			}), nil
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

func applyPatchTool(env *builtinEnv) Tool {
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
			// The paths live INSIDE the patch text, not in the arguments, so a
			// host cannot rewrite them from a hook — this is the one case that
			// genuinely needs the base directory to be library-side (ADR 0034 D2).
			for i := range ops {
				full, rErr := env.resolvePath(ops[i].path)
				if rErr != nil {
					return bErr(fmt.Sprintf("apply_patch: %v", rErr), nil)
				}
				ops[i].path = full
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
	return CreateBuiltinToolsWith(nil)
}

// CreateBuiltinToolsWith builds the ten builtins against a host boundary — the
// interpreter, base directory and confinement of a BuiltinsConfig (ADR 0034).
// A nil config is the historical behaviour: `sh -c` (or the platform's detected
// interpreter), paths relative to the process cwd, no confinement.
func CreateBuiltinToolsWith(cfg any) []Tool {
	env := newBuiltinEnv(cfg)
	return []Tool{
		bashTool(env),
		readTool(env),
		writeTool(env),
		editTool(env),
		grepTool(env),
		globTool(env),
		webfetchTool(),
		questionTool(),
		applyPatchTool(env),
		todowriteTool(),
	}
}

// BuiltinShell reports the interpreter the builtins resolved for `bash` — what
// a host prints, and what `metadata.shell` carries on every bash result. The
// error is the one construction fails on when no interpreter resolves.
func BuiltinShell(cfg any) ([]string, error) {
	env := newBuiltinEnv(cfg)
	return env.shellArgv()
}
