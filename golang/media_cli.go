// CLI media clients (SPEC §7G.10): run a local subscription CLI once, headless,
// read its JSON-lines stdout and collect the files it made. Grok and Codex only
// supply the argv, a per-line parser, a folder finder and an unavailable matcher.

package toolnexus

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// CliEvent is what a CLI client's parser extracts from one JSON line.
type CliEvent struct {
	Session string      // job id (session / thread id); last wins
	Text    string      // appended to the run's text
	Paths   []string    // files the CLI reported
	Error   string      // replaces the run's last error
	Status  string      // reported as progress
	Usage   *MediaUsage // provider-reported usage (§7G.12 D21); last wins
}

// CliMediaClient is the CLI base. Build Grok or Codex with their constructors,
// or fill the fields for another CLI.
type CliMediaClient struct {
	UnsupportedMedia
	// ClientName is the client's name in messages and file names.
	ClientName string
	// Verbs are the supported verbs (image and/or video).
	Verbs []string
	// Config: Model (passed by Argv), Command (default DefaultCommand), Loop, Gather.
	Config MediaConfig
	// DefaultCommand is the argv prefix when Config.Command is empty.
	DefaultCommand []string
	// Env is added to the process environment ("NAME=value").
	Env []string
	// Templates maps a verb to its prompt template; "{prompt}" is replaced.
	Templates map[string]string
	// Argv builds the args after the command for one run.
	Argv func(verb, prompt, cwd string) []string
	// Parse turns one JSON line (decoded, and raw) into an event.
	Parse func(line map[string]any, raw []byte) CliEvent
	// Folder, when set, names a folder whose files are candidates once a session is known.
	Folder func(session string) string
	// Unavailable maps the last error to an unavailable message (ok=false ⇒ not unavailable).
	Unavailable func(verb, errMsg string) (string, bool)
}

// Name is the client's name.
func (c *CliMediaClient) Name() string { return c.ClientName }

// Supports reports whether verb is one of Verbs.
func (c *CliMediaClient) Supports(verb string) bool { return mediaHasStr(c.Verbs, verb) }

// Args: CLI clients accept prompt only.
func (c *CliMediaClient) Args(verb string) []MediaArg {
	if !c.Supports(verb) {
		return nil
	}
	return mediaArgsOf([]string{"prompt"})
}

func (c *CliMediaClient) Image(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return c.run(ctx, "image", args, call)
}
func (c *CliMediaClient) Video(ctx context.Context, args map[string]any, call MediaCall) (MediaResult, error) {
	return c.run(ctx, "video", args, call)
}

func mediaHasStr(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

var cliPathRe = regexp.MustCompile(`/[^\s"'()\[\]]+\.(?i:png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)`)

func (c *CliMediaClient) run(ctx context.Context, verb string, args map[string]any, call MediaCall) (MediaResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if !c.Supports(verb) {
		return unsupportedVerb(verb)
	}
	cfg := c.Config
	command := cfg.Command
	if len(command) == 0 {
		command = c.DefaultCommand
	}
	timeoutMs := cfg.timeoutMs()
	started := time.Now()
	deadline := started.Add(time.Duration(timeoutMs) * time.Millisecond)
	if !call.Deadline.IsZero() && call.Deadline.Before(deadline) {
		deadline = call.Deadline
	}
	progress := call.Progress
	if progress == nil {
		progress = cfg.Loop.OnProgress
	}
	jobID := ""
	report := func(status string) {
		if progress != nil {
			progress(MediaProgress{Verb: verb, Client: c.ClientName, JobID: jobID, Status: status,
				ElapsedMs: time.Since(started).Milliseconds()})
		}
	}
	fail := func(msg string) *MediaError { return &MediaError{Message: msg, Status: "failed", JobID: jobID} }

	cwd, err := os.MkdirTemp("", "toolnexus-media-cli-")
	if err != nil {
		return MediaResult{}, fail(fmt.Sprintf("%s: %v", c.ClientName, err))
	}
	defer os.RemoveAll(cwd)
	if real, err := filepath.EvalSymlinks(cwd); err == nil { // D17: the realpath
		cwd = real
	}
	prompt := strings.ReplaceAll(c.Templates[verb], "{prompt}", argText(args["prompt"]))
	argv := append(append([]string(nil), command[1:]...), c.Argv(verb, prompt, cwd)...)
	cmd := exec.Command(command[0], argv...)
	cmd.Dir = cwd
	cmd.Env = append(os.Environ(), c.Env...)
	cmd.WaitDelay = 2 * time.Second
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return MediaResult{}, fail(fmt.Sprintf("%s: %v", c.ClientName, err))
	}
	if err := startJob(cmd); err != nil {
		return MediaResult{}, mediaErr("unavailable", fmt.Sprintf("%s: cannot start %q", c.ClientName, command[0]))
	}

	// Cancellation or the deadline kills the whole process group.
	stop := make(chan struct{})
	killed := make(chan string, 1)
	timer := time.NewTimer(time.Until(deadline))
	go func() {
		defer timer.Stop()
		select {
		case <-ctx.Done():
			if errors.Is(ctx.Err(), context.DeadlineExceeded) { // D12
				killed <- "timeout"
			} else {
				killed <- "cancelled"
			}
		case <-timer.C:
			killed <- "timeout"
		case <-stop:
			return
		}
		_ = signalJob(cmd, false)
	}()

	var text strings.Builder
	var paths []string
	lastErr := ""
	var usage *MediaUsage
	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 64*1024), 64<<20)
	for sc.Scan() {
		raw := sc.Bytes()
		var line map[string]any
		if json.Unmarshal(raw, &line) != nil || line == nil {
			continue
		}
		e := c.Parse(line, append([]byte(nil), raw...))
		if e.Session != "" {
			jobID = e.Session
		}
		if e.Usage != nil {
			usage = e.Usage
		}
		text.WriteString(e.Text)
		paths = append(paths, e.Paths...)
		if e.Error != "" {
			lastErr = e.Error
		}
		if e.Status != "" {
			report(e.Status)
		}
	}
	_ = cmd.Wait()
	close(stop)
	select {
	case why := <-killed:
		if why == "cancelled" {
			e := mediaCancelled(ctx.Err())
			e.JobID = jobID
			return MediaResult{}, e
		}
		return MediaResult{}, &MediaError{Message: fmt.Sprintf("%s: timed out after %dms", c.ClientName, timeoutMs), Status: "timeout", JobID: jobID}
	default:
	}
	exitCode := -1
	if cmd.ProcessState != nil {
		exitCode = cmd.ProcessState.ExitCode()
	}

	// Candidates: parsed paths, then absolute paths in the text, then the folder.
	all := text.String()
	cands := append([]string(nil), paths...)
	cands = append(cands, cliPathRe.FindAllString(all, -1)...)
	if c.Folder != nil && jobID != "" {
		if entries, err := os.ReadDir(c.Folder(jobID)); err == nil {
			names := make([]string, 0, len(entries))
			for _, e := range entries {
				names = append(names, e.Name())
			}
			sort.Strings(names)
			for _, n := range names {
				cands = append(cands, filepath.Join(c.Folder(jobID), n))
			}
		}
	}
	seen := map[string]bool{}
	want := map[string]string{"image": "image/", "video": "video/"}[verb]
	kind := map[string]string{"image": "image", "video": "video"}[verb]
	var kept []string
	for _, p := range cands {
		if seen[p] {
			continue
		}
		seen[p] = true
		fi, err := os.Stat(p)
		if err != nil || !fi.Mode().IsRegular() {
			continue
		}
		if mt, ok := mimeFromExt(p); ok && strings.HasPrefix(mt, want) {
			kept = append(kept, p)
		}
	}
	if len(kept) > 0 {
		files := make([]MediaFile, 0, len(kept))
		for _, p := range kept {
			f, err := c.gatherLocal(verb, kind, p, fail)
			if err != nil {
				return MediaResult{}, err
			}
			files = append(files, f)
		}
		return MediaResult{Files: files, Text: all, JobID: jobID, Status: "completed", Usage: usage}, nil
	}
	if lastErr != "" {
		if c.Unavailable != nil {
			if msg, ok := c.Unavailable(verb, lastErr); ok {
				return MediaResult{}, &MediaError{Message: msg, Status: "unavailable", JobID: jobID}
			}
		}
		return MediaResult{}, fail(fmt.Sprintf("%s: no %s: %s", c.ClientName, verb, capRunes(lastErr, 400)))
	}
	msg := fmt.Sprintf("%s: no %s file in the run (exit code %d)", c.ClientName, verb, exitCode)
	if all != "" {
		msg += ": " + capRunes(all, 300)
	}
	return MediaResult{}, fail(msg)
}

// gatherLocal applies §7G.5 to a CLI's own file.
func (c *CliMediaClient) gatherLocal(verb, kind, p string, fail func(string) *MediaError) (MediaFile, error) {
	g := c.Config.Gather
	mode := g.Mode
	if mode == "" {
		mode = "download"
	}
	mime, _ := mimeFromExt(p)
	if mode == "url" {
		return MediaFile{Type: mediaPartType(kind), MimeType: mime, URL: "file://" + p, Path: p}, nil
	}
	data, err := os.ReadFile(p)
	if err != nil {
		return MediaFile{}, fail(fmt.Sprintf("%s: %v", verb, err))
	}
	if mode == "inline" {
		if int64(len(data)) > gatherMaxBytes(g) {
			return MediaFile{}, fail(fmt.Sprintf("%s: file exceeds gather.maxBytes (%d bytes)", verb, gatherMaxBytes(g)))
		}
		return MediaFile{Type: mediaPartType(kind), MimeType: mime, Data: base64.StdEncoding.EncodeToString(data)}, nil
	}
	f, err := gatherBytes(g, "download", c.ClientName, verb, kind, data, mime, "", fail)
	if err != nil {
		return MediaFile{}, err
	}
	if g.Keep != nil && !*g.Keep {
		_ = os.Remove(p)
	}
	return f, nil
}

// ---- Grok ----

// GrokMediaClient runs `grok -p … --output-format streaming-json` (image, video)
// on the user's Grok subscription. It never edits the user's Grok settings.
type GrokMediaClient struct{ *CliMediaClient }

// NewGrokMediaClient builds the Grok CLI client. Config.Model becomes -m.
func NewGrokMediaClient(cfg MediaConfig) *GrokMediaClient {
	c := &CliMediaClient{ClientName: "grok", Verbs: []string{"image", "video"}, Config: cfg,
		DefaultCommand: []string{"grok"},
		Env:            []string{`GROK_CONFIG={"features":{"image_gen":true,"video_gen":true}}`},
		Templates: map[string]string{
			"image": "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path.",
			"video": "Generate exactly one short video with your video generation tool, shortest duration available: {prompt}. Do not write code. Reply only with the file path.",
		}}
	c.Argv = func(verb, prompt, cwd string) []string {
		a := []string{"-p", prompt, "--cwd", cwd, "--output-format", "streaming-json", "--yolo", "--no-auto-update"}
		if cfg.Model != "" {
			a = append(a, "-m", cfg.Model)
		}
		return a
	}
	c.Parse = func(l map[string]any, raw []byte) CliEvent {
		var e CliEvent
		switch l["type"] {
		case "text":
			e.Text, _ = l["data"].(string)
		case "tool_call":
			e.Status = "tool " + fmt.Sprint(l["toolName"])
		case "tool_call_update":
			st, _ := l["status"].(string)
			if st != "" {
				e.Status = "tool " + st
			}
			switch st {
			case "completed":
				if p, _ := dotGet(l, "rawOutput.path").(string); p != "" {
					e.Paths = []string{p}
				}
			case "failed":
				if m, _ := dotGet(l, "rawOutput.message").(string); m != "" {
					e.Error = m
				} else {
					e.Error = rawField(raw, "rawOutput")
				}
			}
		case "end":
			e.Session, _ = l["sessionId"].(string)
			e.Status = "end " + fmt.Sprint(l["stopReason"])
			// D21: usage from the end event; total_cost_usd_ticks stays raw.
			u := MediaUsage{InputTokens: usageInt(dotGet(l, "usage.input_tokens")),
				OutputTokens: usageInt(dotGet(l, "usage.output_tokens")), CostUsd: usageFloat(l["total_cost_usd"])}
			if t, ok := l["total_cost_usd_ticks"]; ok && t != nil {
				u.Raw = map[string]any{"total_cost_usd_ticks": t}
			}
			e.Usage = mediaUsageOf(u)
		case "error":
			e.Error, _ = l["message"].(string)
		}
		return e
	}
	c.Unavailable = func(verb, m string) (string, bool) {
		low := strings.ToLower(m)
		if strings.Contains(low, "zero data retention") {
			return "grok: " + verb + " blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video storage (docs/media#grok-video-zdr)", true
		}
		if strings.Contains(low, "usage limit") {
			return "grok: " + capRunes(m, 400), true
		}
		return "", false
	}
	return &GrokMediaClient{c}
}

// rawField returns the compact JSON text of a top-level field of a line, in
// the CLI's own key order.
func rawField(raw []byte, field string) string {
	var top map[string]json.RawMessage
	if json.Unmarshal(raw, &top) != nil {
		return ""
	}
	v, ok := top[field]
	if !ok {
		return "null"
	}
	// D15: compact JSON, object keys sorted by code point at every depth.
	var val any
	d := json.NewDecoder(bytes.NewReader(v))
	d.UseNumber()
	if d.Decode(&val) != nil {
		return string(v)
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if enc.Encode(val) != nil {
		return string(v)
	}
	return strings.TrimSuffix(buf.String(), "\n")
}

// ---- Codex ----

// CodexMediaClient runs `codex exec --json` (image) on the user's ChatGPT plan.
// The file comes from the reply text or $CODEX_HOME/generated_images/<thread>.
type CodexMediaClient struct{ *CliMediaClient }

// NewCodexMediaClient builds the Codex CLI client. Config.Model becomes -m.
func NewCodexMediaClient(cfg MediaConfig) *CodexMediaClient {
	c := &CliMediaClient{ClientName: "codex", Verbs: []string{"image"}, Config: cfg,
		DefaultCommand: []string{"codex"},
		Templates: map[string]string{
			"image": "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved.",
		}}
	c.Argv = func(verb, prompt, cwd string) []string {
		a := []string{"exec", "--json", "--skip-git-repo-check", "-s", "workspace-write"}
		if cfg.Model != "" {
			a = append(a, "-m", cfg.Model)
		}
		return append(a, prompt)
	}
	c.Parse = func(l map[string]any, raw []byte) CliEvent {
		var e CliEvent
		t, _ := l["type"].(string)
		e.Status = t
		switch t {
		case "thread.started":
			e.Session, _ = l["thread_id"].(string)
		case "item.completed":
			if it, _ := dotGet(l, "item.type").(string); it == "agent_message" {
				e.Text, _ = dotGet(l, "item.text").(string)
			}
		case "error", "turn.failed":
			if m, _ := l["message"].(string); m != "" {
				e.Error = m
			} else if m, _ := dotGet(l, "error.message").(string); m != "" {
				e.Error = m
			} else {
				e.Error = string(raw)
			}
		}
		return e
	}
	c.Folder = func(thread string) string {
		home := os.Getenv("CODEX_HOME")
		if home == "" {
			if h, err := os.UserHomeDir(); err == nil {
				home = filepath.Join(h, ".codex")
			}
		}
		return filepath.Join(home, "generated_images", thread)
	}
	c.Unavailable = func(verb, m string) (string, bool) {
		low := strings.ToLower(m)
		if strings.Contains(low, "usage limit") || strings.Contains(low, "quota") {
			return "codex: " + capRunes(m, 400), true
		}
		return "", false
	}
	return &CodexMediaClient{c}
}
