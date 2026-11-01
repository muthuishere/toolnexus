package toolnexus

// Opt-in builtins for coding-agent hosts (change add-go-host-asks, ask #7):
// background shells (bash run_in_background + bash_output + kill_shell),
// websearch over a host-supplied provider, and notebook_edit. None of them is
// on by default, so a host that sets nothing sees the same ten builtins.
// golang only for now; other ports pending.

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---------------------------------------------------------------------------
// background shells
// ---------------------------------------------------------------------------

// BackgroundShells owns the commands `bash` started with run_in_background.
// Create one with NewBackgroundShells, pass it as BuiltinsConfig.Background,
// and call CloseAll when the host shuts down — a background command is NOT
// tied to the turn that started it, so nothing else stops it.
type BackgroundShells struct {
	mu   sync.Mutex
	next int
	jobs map[string]*bgJob
}

type bgJob struct {
	id      string
	command string
	started time.Time
	cmd     *exec.Cmd
	buf     lockedBuffer
	grace   time.Duration

	mu       sync.Mutex
	read     int // bytes of buf already returned by bash_output
	done     chan struct{}
	exitCode int
	killed   bool
}

// BackgroundShellInfo is one background command, for a host UI.
type BackgroundShellInfo struct {
	ID       string
	Command  string
	Started  time.Time
	Running  bool
	ExitCode int // valid when !Running; -1 if killed by a signal
	Killed   bool
}

// NewBackgroundShells returns an empty background-shell registry.
func NewBackgroundShells() *BackgroundShells {
	return &BackgroundShells{jobs: map[string]*bgJob{}}
}

func (b *BackgroundShells) start(argv []string, command, workdir string, grace time.Duration) (*bgJob, error) {
	cmd := exec.Command(argv[0], append(argv[1:len(argv):len(argv)], command)...)
	cmd.Dir = workdir
	j := &bgJob{command: command, started: time.Now(), cmd: cmd, grace: grace, done: make(chan struct{})}
	cmd.Stdout = &j.buf
	cmd.Stderr = &j.buf
	if err := startJob(cmd); err != nil {
		return nil, err
	}
	b.mu.Lock()
	b.next++
	j.id = "bg" + strconv.Itoa(b.next)
	b.jobs[j.id] = j
	b.mu.Unlock()
	go func() {
		err := cmd.Wait()
		j.mu.Lock()
		j.exitCode = 0
		if err != nil {
			j.exitCode = -1
			if ee, ok := err.(*exec.ExitError); ok {
				j.exitCode = ee.ExitCode()
			}
		}
		j.mu.Unlock()
		close(j.done)
	}()
	return j, nil
}

func (b *BackgroundShells) get(id string) (*bgJob, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	j, ok := b.jobs[id]
	return j, ok
}

func (j *bgJob) running() bool {
	select {
	case <-j.done:
		return false
	default:
		return true
	}
}

func (j *bgJob) info() BackgroundShellInfo {
	j.mu.Lock()
	defer j.mu.Unlock()
	return BackgroundShellInfo{ID: j.id, Command: j.command, Started: j.started, Running: j.running(), ExitCode: j.exitCode, Killed: j.killed}
}

// kill stops the job's whole process group: SIGTERM, the grace window, SIGKILL.
// Returns whether it was still running.
func (j *bgJob) kill() bool {
	if !j.running() {
		return false
	}
	j.mu.Lock()
	j.killed = true
	j.mu.Unlock()
	_ = signalJob(j.cmd, true)
	select {
	case <-j.done:
	case <-time.After(j.grace):
		_ = signalJob(j.cmd, false)
		<-j.done
	}
	return true
}

// List returns every background command, oldest first.
func (b *BackgroundShells) List() []BackgroundShellInfo {
	b.mu.Lock()
	jobs := make([]*bgJob, 0, len(b.jobs))
	for _, j := range b.jobs {
		jobs = append(jobs, j)
	}
	b.mu.Unlock()
	sort.Slice(jobs, func(a, c int) bool { return jobs[a].started.Before(jobs[c].started) })
	out := make([]BackgroundShellInfo, 0, len(jobs))
	for _, j := range jobs {
		out = append(out, j.info())
	}
	return out
}

// Kill stops one background command's process group. Unknown id ⇒ error.
func (b *BackgroundShells) Kill(id string) error {
	j, ok := b.get(id)
	if !ok {
		return fmt.Errorf("no background shell %q", id)
	}
	j.kill()
	return nil
}

// CloseAll stops every background command still running, concurrently.
func (b *BackgroundShells) CloseAll() {
	b.mu.Lock()
	jobs := make([]*bgJob, 0, len(b.jobs))
	for _, j := range b.jobs {
		jobs = append(jobs, j)
	}
	b.mu.Unlock()
	var wg sync.WaitGroup
	for _, j := range jobs {
		wg.Add(1)
		go func(j *bgJob) { defer wg.Done(); j.kill() }(j)
	}
	wg.Wait()
}

func bgStatus(i BackgroundShellInfo) string {
	switch {
	case i.Running:
		return "running"
	case i.Killed:
		return "killed"
	default:
		return fmt.Sprintf("exited with code %d", i.ExitCode)
	}
}

func bashOutputTool(env *builtinEnv) Tool {
	return builtin(
		"bash_output",
		"Read the output a background bash command (started with run_in_background) produced since the last read, and whether it is still running.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"id": map[string]any{"type": "string", "description": "The background shell id bash returned"},
			},
			"required":             []string{"id"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			id, _ := argString(args, "id")
			j, ok := env.bg.get(id)
			if !ok {
				return bErr(fmt.Sprintf("bash_output: no background shell %q", id), nil)
			}
			all := j.buf.String()
			j.mu.Lock()
			fresh := all[j.read:]
			j.read = len(all)
			j.mu.Unlock()
			info := j.info()
			status := bgStatus(info)
			meta := map[string]any{"id": id, "status": strings.SplitN(status, " ", 2)[0]}
			if !info.Running && !info.Killed {
				meta["exitCode"] = info.ExitCode
			}
			return bOk(fmt.Sprintf("%s\n[status: %s]", fresh, status), meta)
		},
	)
}

func killShellTool(env *builtinEnv) Tool {
	return builtin(
		"kill_shell",
		"Stop a background bash command and everything it started.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"id": map[string]any{"type": "string", "description": "The background shell id bash returned"},
			},
			"required":             []string{"id"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			id, _ := argString(args, "id")
			j, ok := env.bg.get(id)
			if !ok {
				return bErr(fmt.Sprintf("kill_shell: no background shell %q", id), nil)
			}
			if !j.kill() {
				return bOk(fmt.Sprintf("Background shell %s had already %s", id, bgStatus(j.info())), map[string]any{"id": id, "killed": false})
			}
			return bOk(fmt.Sprintf("Killed background shell %s", id), map[string]any{"id": id, "killed": true})
		},
	)
}

// ---------------------------------------------------------------------------
// websearch
// ---------------------------------------------------------------------------

// WebSearchResult is one hit from a host search provider.
type WebSearchResult struct {
	Title   string `json:"title"`
	URL     string `json:"url"`
	Snippet string `json:"snippet"`
}

// WebSearchFunc is the host's search provider. The library ships none.
type WebSearchFunc func(ctx context.Context, query string, limit int) ([]WebSearchResult, error)

func webSearchTool(search WebSearchFunc) Tool {
	return builtin(
		"websearch",
		"Search the web. Returns numbered results with title, URL and snippet.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"query": map[string]any{"type": "string", "description": "The search query"},
				"limit": map[string]any{"type": "number", "description": "Maximum number of results (default 5)"},
			},
			"required":             []string{"query"},
			"additionalProperties": false,
		},
		func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			q, _ := argString(args, "query")
			if strings.TrimSpace(q) == "" {
				return bErr("websearch: query is required", nil)
			}
			limit := 5
			if l, ok := argNumber(args, "limit"); ok && l >= 1 {
				limit = int(l)
			}
			ctx := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				ctx = tctx.Ctx
			}
			results, err := search(ctx, q, limit)
			if err != nil {
				return bErr(fmt.Sprintf("websearch: %v", err), nil)
			}
			if len(results) > limit {
				results = results[:limit]
			}
			if len(results) == 0 {
				return bOk("No results.", map[string]any{"count": 0})
			}
			var sb strings.Builder
			for i, r := range results {
				if i > 0 {
					sb.WriteString("\n\n")
				}
				fmt.Fprintf(&sb, "%d. %s\n   %s", i+1, r.Title, r.URL)
				if r.Snippet != "" {
					fmt.Fprintf(&sb, "\n   %s", r.Snippet)
				}
			}
			return bOk(sb.String(), map[string]any{"count": len(results), "results": results})
		},
	)
}

// ---------------------------------------------------------------------------
// notebook_edit
// ---------------------------------------------------------------------------

func notebookEditTool(env *builtinEnv) Tool {
	return builtin(
		"notebook_edit",
		"Edit a Jupyter notebook (.ipynb) cell: replace its source, insert a new cell after it, or delete it. Replacing a code cell clears its outputs.",
		JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"path":       map[string]any{"type": "string", "description": "Path to the .ipynb file"},
				"cell_id":    map[string]any{"type": "string", "description": "The cell's id (or use cell_index)"},
				"cell_index": map[string]any{"type": "number", "description": "0-based cell position (used when cell_id is absent)"},
				"new_source": map[string]any{"type": "string", "description": "The new cell source (replace/insert)"},
				"cell_type":  map[string]any{"type": "string", "enum": []string{"code", "markdown"}, "description": "Cell type for insert (default code) or to change on replace"},
				"edit_mode":  map[string]any{"type": "string", "enum": []string{"replace", "insert", "delete"}, "description": "Default replace. insert adds the new cell AFTER the given cell, or first when none is given"},
			},
			"required":             []string{"path"},
			"additionalProperties": false,
		},
		func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			p, _ := argString(args, "path")
			if p == "" {
				return bErr("notebook_edit: path is required", nil)
			}
			full, rErr := env.resolvePath(p)
			if rErr != nil {
				return bErr(fmt.Sprintf("notebook_edit: %v", rErr), nil)
			}
			raw, err := os.ReadFile(full)
			if err != nil {
				return bErr(fmt.Sprintf("notebook_edit: %v", err), nil)
			}
			var nb map[string]any
			if err := json.Unmarshal(raw, &nb); err != nil {
				return bErr(fmt.Sprintf("notebook_edit: %s is not a notebook: %v", p, err), nil)
			}
			cells, _ := nb["cells"].([]any)
			mode, _ := argString(args, "edit_mode")
			if mode == "" {
				mode = "replace"
			}
			idx := -1
			if id, ok := argString(args, "cell_id"); ok && id != "" {
				for i, c := range cells {
					if cm, ok := c.(map[string]any); ok && cm["id"] == id {
						idx = i
						break
					}
				}
				if idx < 0 {
					return bErr(fmt.Sprintf("notebook_edit: no cell with id %q", id), nil)
				}
			} else if n, ok := argNumber(args, "cell_index"); ok {
				idx = int(n)
				if idx < 0 || idx >= len(cells) {
					return bErr(fmt.Sprintf("notebook_edit: cell_index %d out of range (0-%d)", idx, len(cells)-1), nil)
				}
			} else if mode != "insert" {
				return bErr("notebook_edit: cell_id or cell_index is required", nil)
			}
			src, _ := argString(args, "new_source")
			cellType, _ := argString(args, "cell_type")
			var summary string
			switch mode {
			case "replace":
				cm, _ := cells[idx].(map[string]any)
				cm["source"] = notebookSource(src)
				if cellType != "" {
					cm["cell_type"] = cellType
				}
				if cm["cell_type"] == "code" {
					cm["outputs"] = []any{}
					cm["execution_count"] = nil
				} else {
					delete(cm, "outputs")
					delete(cm, "execution_count")
				}
				summary = fmt.Sprintf("Replaced cell %d in %s", idx, p)
			case "insert":
				if cellType == "" {
					cellType = "code"
				}
				cell := map[string]any{"cell_type": cellType, "metadata": map[string]any{}, "source": notebookSource(src)}
				if cellType == "code" {
					cell["outputs"] = []any{}
					cell["execution_count"] = nil
				}
				at := idx + 1
				cells = append(cells[:at], append([]any{cell}, cells[at:]...)...)
				summary = fmt.Sprintf("Inserted %s cell at %d in %s", cellType, at, p)
			case "delete":
				cells = append(cells[:idx], cells[idx+1:]...)
				summary = fmt.Sprintf("Deleted cell %d from %s", idx, p)
			default:
				return bErr(fmt.Sprintf("notebook_edit: unknown edit_mode %q", mode), nil)
			}
			nb["cells"] = cells
			out, err := json.MarshalIndent(nb, "", " ")
			if err != nil {
				return bErr(fmt.Sprintf("notebook_edit: %v", err), nil)
			}
			out = append(out, '\n')
			if err := os.WriteFile(full, out, 0o644); err != nil {
				return bErr(fmt.Sprintf("notebook_edit: %v", err), nil)
			}
			meta := map[string]any{"cells": len(cells)}
			addDiffMeta(meta, []fileChange{{path: env.diffPath(p, full), status: "modified", before: string(raw), after: string(out)}})
			return bOk(summary, meta)
		},
	)
}

// notebookSource splits text into nbformat's list of lines, each keeping its "\n".
func notebookSource(s string) []any {
	if s == "" {
		return []any{}
	}
	parts := strings.SplitAfter(s, "\n")
	out := make([]any, 0, len(parts))
	for _, p := range parts {
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
