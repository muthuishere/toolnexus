package main

// CliMediaClient base: run a local subscription CLI once in headless JSON-lines mode, read its
// event stream, and collect the media files it reports. Grok and Codex only supply the command
// line and a per-event parser.

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

func urlq(s string) string { return url.QueryEscape(s) }

var mediaPathRe = regexp.MustCompile(`(/[^\s"'()\[\]]+\.(?:png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav))`)

// Event is what a client's parser extracts from one JSON line.
type Event struct {
	Session string   // job id (session/thread id)
	Text    string   // final-answer text chunk
	Paths   []string // media file paths reported by a tool
	Err     string   // a failed tool / turn message
	Status  string   // progress label
}

type CliMediaClient struct {
	Unsupported
	Cfg         Config
	Env         []string
	Args        func(v Verb, prompt, cwd string) []string // argv after Command
	Prompts     map[Verb]string                           // template with {prompt}
	Parse       func(line map[string]any) Event
	Folder      func(session string) string // optional per-client folder to glob after the run
	Unavailable func(msg string) bool       // account preconditions (quota, ZDR) → ErrUnavailable
	Capture     string
	name        string
}

func (c *CliMediaClient) Name() string { return c.name }
func (c *CliMediaClient) Image(ctx context.Context, a Args) (MediaResult, error) {
	return c.run(ctx, VImage, a)
}
func (c *CliMediaClient) Video(ctx context.Context, a Args) (MediaResult, error) {
	return c.run(ctx, VVideo, a)
}

func (c *CliMediaClient) run(ctx context.Context, v Verb, a Args) (MediaResult, error) {
	ctx, cancel := c.Cfg.withLoop(ctx)
	defer cancel()
	start := time.Now()
	cwd, _ := os.MkdirTemp("", "media-cli-")
	defer os.RemoveAll(cwd)
	prompt := strings.ReplaceAll(c.Prompts[v], "{prompt}", a.Prompt)
	cmd := exec.CommandContext(ctx, c.Cfg.Command[0], append(c.Cfg.Command[1:], c.Args(v, prompt, cwd)...)...)
	cmd.Dir = cwd
	cmd.Env = append(os.Environ(), c.Env...)
	out, _ := cmd.StdoutPipe()
	if c.Capture != "" {
		os.MkdirAll(filepath.Dir(c.Capture), 0o755)
		if f, err := os.Create(c.Capture + ".stderr.log"); err == nil {
			cmd.Stderr = f
			defer f.Close()
		}
	}
	if err := cmd.Start(); err != nil {
		return MediaResult{Status: "failed"}, fmt.Errorf("spawn %v: %w", c.Cfg.Command, err)
	}
	var logf *os.File
	if c.Capture != "" {
		logf, _ = os.Create(c.Capture)
		defer logf.Close()
	}
	res := MediaResult{}
	var text strings.Builder
	var paths []string
	seen := map[string]bool{}
	add := func(p string) {
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() && !seen[p] {
			seen[p] = true
			paths = append(paths, p)
		}
	}
	toolErr := ""
	sc := bufio.NewScanner(out)
	sc.Buffer(make([]byte, 64<<20), 64<<20)
	for sc.Scan() {
		if logf != nil {
			logf.Write(append(append([]byte(nil), sc.Bytes()...), '\n'))
		}
		var line map[string]any
		if json.Unmarshal(sc.Bytes(), &line) != nil {
			continue
		}
		e := c.Parse(line)
		if e.Session != "" {
			res.JobID = e.Session
		}
		text.WriteString(e.Text)
		for _, p := range e.Paths {
			add(p)
		}
		if e.Err != "" {
			toolErr = e.Err
		}
		if e.Status != "" {
			report(ctx, e.Status, start)
		}
	}
	werr := cmd.Wait()
	res.Text = text.String()
	if ctx.Err() != nil {
		res.Status = "cancelled"
		if ctx.Err() == context.DeadlineExceeded {
			res.Status = "timeout"
		}
		return res, ctx.Err()
	}
	for _, p := range mediaPathRe.FindAllString(res.Text, -1) { // paths named in the answer
		add(p)
	}
	if c.Folder != nil && res.JobID != "" {
		if m, _ := filepath.Glob(filepath.Join(c.Folder(res.JobID), "*")); m != nil {
			for _, p := range m {
				add(p)
			}
		}
	}
	// Only files of the verb's kind count: a video run that only produced its still image failed.
	want := map[Verb]string{VImage: "image", VVideo: "video"}[v]
	for _, p := range paths {
		if kindOf(mimeFor(p, "")) != want {
			continue
		}
		f, err := c.Cfg.fromLocal(c.name+"-"+string(v), p) // copy out of the CLI's own storage
		if err != nil {
			return res, err
		}
		res.Files = append(res.Files, f)
	}
	if len(res.Files) > 0 {
		res.Status = "completed"
		return res, nil
	}
	res.Status = "failed"
	if toolErr != "" {
		if c.Unavailable != nil && c.Unavailable(toolErr) {
			res.Status = "unavailable"
			return res, unavailable(trunc(toolErr, 400))
		}
		return res, fmt.Errorf("%s: no %s: %s", c.name, v, trunc(toolErr, 400))
	}
	return res, fmt.Errorf("%s: no %s file (exit=%v); text=%s", c.name, v, werr, trunc(res.Text, 300))
}

// ---- Grok: `grok -p … --output-format streaming-json --yolo` -----------------------

type GrokMediaClient struct{ *CliMediaClient }

func NewGrok(c Config) *GrokMediaClient {
	if len(c.Command) == 0 {
		c.Command = []string{"grok"}
	}
	c = c.withDefaults("", "", "", "download")
	b := &CliMediaClient{Unsupported: supportsOf(VImage, VVideo), Cfg: c, name: "grok", Prompts: map[Verb]string{}}
	b.Env = []string{`GROK_CONFIG={"features":{"image_gen":true,"video_gen":true}}`}
	b.Args = func(v Verb, prompt, cwd string) []string {
		args := []string{"-p", prompt, "--cwd", cwd, "--output-format", "streaming-json", "--yolo", "--no-auto-update"}
		if c.Model != "" {
			args = append(args, "-m", c.Model)
		}
		return args
	}
	b.Prompts[VImage] = "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path."
	b.Prompts[VVideo] = "Generate exactly one short video with your video generation tool, shortest duration available: {prompt}. Do not write code. Reply only with the file path."
	b.Parse = func(l map[string]any) Event {
		var e Event
		switch l["type"] {
		case "text":
			e.Text, _ = l["data"].(string)
		case "tool_call":
			e.Status = fmt.Sprintf("tool %v", l["toolName"])
		case "tool_call_update":
			st, _ := l["status"].(string)
			e.Status = "tool " + st
			if p, _ := dig(l, "rawOutput.path").(string); p != "" && st == "completed" {
				e.Paths = []string{p}
			}
			if st == "failed" {
				e.Err, _ = dig(l, "rawOutput.message").(string)
				if e.Err == "" {
					b, _ := json.Marshal(l["rawOutput"])
					e.Err = string(b)
				}
			}
		case "end":
			e.Session, _ = l["sessionId"].(string)
			e.Status = "end " + fmt.Sprint(l["stopReason"])
		case "error":
			e.Err, _ = l["message"].(string)
		}
		return e
	}
	b.Unavailable = func(m string) bool {
		m = strings.ToLower(m)
		return strings.Contains(m, "zero data retention") || strings.Contains(m, "usage limit")
	}
	return &GrokMediaClient{b}
}

// ---- Codex: `codex exec --json` -------------------------------------------------------

type CodexMediaClient struct{ *CliMediaClient }

func NewCodex(c Config) *CodexMediaClient {
	if len(c.Command) == 0 {
		c.Command = []string{"codex"}
	}
	c = c.withDefaults("", "", "", "download")
	b := &CliMediaClient{Unsupported: supportsOf(VImage), Cfg: c, name: "codex", Prompts: map[Verb]string{}}
	b.Args = func(v Verb, prompt, cwd string) []string {
		args := []string{"exec", "--json", "--skip-git-repo-check", "-s", "workspace-write"}
		if c.Model != "" {
			args = append(args, "-m", c.Model)
		}
		return append(args, prompt)
	}
	b.Prompts[VImage] = "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved."
	b.Parse = func(l map[string]any) Event {
		var e Event
		t, _ := l["type"].(string)
		e.Status = t
		switch t {
		case "thread.started":
			e.Session, _ = l["thread_id"].(string)
		case "item.completed":
			if it, _ := dig(l, "item.type").(string); it == "agent_message" {
				e.Text, _ = dig(l, "item.text").(string)
			}
		case "error", "turn.failed":
			bb, _ := json.Marshal(l)
			e.Err = string(bb)
		}
		return e
	}
	// No event carries the image: the reply text names it, and codex keeps it per thread here.
	b.Folder = func(thread string) string {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, ".codex", "generated_images", thread)
	}
	b.Unavailable = func(m string) bool {
		m = strings.ToLower(m)
		return strings.Contains(m, "usage limit") || strings.Contains(m, "quota")
	}
	return &CodexMediaClient{b}
}
