// Spike (ADR 0037): a one-shot agent CLI as a tool-calling toolnexus client,
// selected by style name. Not library code; it proves the shape before ports.
package main

import (
	"bytes"
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	tn "github.com/muthuishere/toolnexus/golang"
)

//go:embed styles/*.json
var stylesFS embed.FS

//go:embed preamble.txt
var Preamble string

const defaultTimeoutMs = 600000

// Style is one CLI's invocation recipe — data, shared by every port.
type Style struct {
	Name      string            `json:"name"`
	Binary    string            `json:"-"` // always the caller's Command
	Args      []any             `json:"args"`
	Prompt    string            `json:"prompt"` // stdin | arg | file
	Answer    string            `json:"answer"` // stdout | out
	TimeoutMs int               `json:"timeoutMs,omitempty"`
	Env       map[string]string `json:"env,omitempty"`
	Verified  string            `json:"verified,omitempty"`
	Notes     string            `json:"notes,omitempty"`
}

// BuiltinStyles returns the embedded styles by name.
func BuiltinStyles() (map[string]Style, error) {
	out := map[string]Style{}
	ents, err := stylesFS.ReadDir("styles")
	if err != nil {
		return nil, err
	}
	for _, e := range ents {
		b, err := stylesFS.ReadFile("styles/" + e.Name())
		if err != nil {
			return nil, err
		}
		var s Style
		if err := json.Unmarshal(b, &s); err != nil {
			return nil, fmt.Errorf("style %s: %w", e.Name(), err)
		}
		out[s.Name] = s
	}
	return out, nil
}

// LoadCliStyle reads a user style from a path, or JSON text starting with "{".
func LoadCliStyle(pathOrJSON string) (Style, error) {
	data := []byte(pathOrJSON)
	if !strings.HasPrefix(strings.TrimSpace(pathOrJSON), "{") {
		b, err := os.ReadFile(pathOrJSON)
		if err != nil {
			return Style{}, fmt.Errorf("cli style: %w", err)
		}
		data = b
	}
	var s Style
	if err := json.Unmarshal(data, &s); err != nil {
		return Style{}, fmt.Errorf("cli style: %w", err)
	}
	return s, nil
}

// CliOptions: Style picks a built-in recipe (the argv/answer shape of a CLI
// family); Command is REQUIRED and names the binary (path or name). No style
// carries a default binary: "claude" style can drive any claude-compatible CLI. Style "custom" takes Args/Prompt/Answer explicitly; StyleDef passes
// a loaded user style. Every in-process client option applies.
// CliStyle is the closed set of CLI families a client can drive. Each is a
// shape (argv, prompt mode, answer source), never a binary.
type CliStyle int

const (
	StyleClaude CliStyle = iota + 1 // zero value = no style chosen
	StyleCodex
	StyleCopilot
	StyleOpencode
	StyleDevin
	StyleGemini
	StyleGoose
	StyleCustom
)

var styleNames = map[CliStyle]string{
	StyleClaude: "claude", StyleCodex: "codex", StyleCopilot: "copilot", StyleOpencode: "opencode",
	StyleDevin: "devin", StyleGemini: "gemini", StyleGoose: "goose", StyleCustom: "custom",
}

// String is the style's shared file name; it is not an input.
func (s CliStyle) String() string { return styleNames[s] }

type CliOptions struct {
	tn.InProcessOptions
	Style     CliStyle
	StyleDef  *Style
	Command   string
	Args      []any
	Prompt    string
	Answer    string
	Env       map[string]string
	TimeoutMs int
	Cwd       string
}

func (o CliOptions) resolve() (Style, error) {
	if o.Command == "" {
		return Style{}, errors.New("cli: Command is required (path or name of the CLI binary)")
	}
	if o.StyleDef != nil {
		s := *o.StyleDef
		s.Binary = o.Command
		return s, nil
	}
	if o.Style == StyleCustom {
		return Style{Name: "custom", Binary: o.Command, Args: o.Args, Prompt: o.Prompt, Answer: o.Answer}, nil
	}
	all, err := BuiltinStyles()
	if err != nil {
		return Style{}, err
	}
	s, ok := all[o.Style.String()]
	if !ok {
		return Style{}, errors.New("cli: Style is required (StyleClaude, StyleCodex, ...)")
	}
	s.Binary = o.Command
	return s, nil
}

var placeholderRe = regexp.MustCompile(`\{\{([^{}]*)\}\}`)

type elem struct {
	group bool
	parts []string
}

func normalize(args []any) ([]elem, error) {
	var out []elem
	for _, e := range args {
		switch v := e.(type) {
		case string:
			out = append(out, elem{parts: []string{v}})
		case []string:
			out = append(out, elem{group: true, parts: v})
		case []any:
			g := elem{group: true}
			for _, x := range v {
				s, ok := x.(string)
				if !ok {
					return nil, errors.New("cli: a group may only hold strings")
				}
				g.parts = append(g.parts, s)
			}
			out = append(out, g)
		default:
			return nil, errors.New("cli: an arg must be a string or a group of strings")
		}
	}
	return out, nil
}

func validate(s Style, elems []elem, model string) error {
	if s.Binary == "" {
		return errors.New("cli: command is empty")
	}
	switch s.Prompt {
	case "stdin", "arg", "file":
	default:
		return fmt.Errorf("cli: prompt must be stdin, arg or file, not %q", s.Prompt)
	}
	switch s.Answer {
	case "stdout", "out":
	default:
		return fmt.Errorf("cli: answer must be stdout or out, not %q", s.Answer)
	}
	seen := map[string]bool{}
	for _, e := range elems {
		for _, p := range e.parts {
			for _, m := range placeholderRe.FindAllStringSubmatch(p, -1) {
				switch m[1] {
				case "prompt", "file", "out":
				case "model":
					if !e.group && model == "" {
						return errors.New("cli: the style needs a model: {{model}} is outside an optional group")
					}
				default:
					return fmt.Errorf("cli: unknown placeholder {{%s}}", m[1])
				}
				seen[m[1]] = true
			}
		}
	}
	if model != "" && !seen["model"] {
		return fmt.Errorf("cli: style %q takes no model", s.Name)
	}
	if s.Prompt == "arg" && !seen["prompt"] {
		return errors.New("cli: prompt \"arg\" needs {{prompt}} in args")
	}
	if s.Prompt == "file" && !seen["file"] {
		return errors.New("cli: prompt \"file\" needs {{file}} in args")
	}
	if s.Answer == "out" && !seen["out"] {
		return errors.New("cli: answer \"out\" needs {{out}} in args")
	}
	return nil
}

// Trace records each invocation for the spike report (never the env values).
type Trace struct {
	Argv    []string
	Ms      int64
	Bytes   int
	Reply   string
	Err     error
	Prompts int
}

// CreateCliClient builds an in-process client whose model is a one-shot CLI.
func CreateCliClient(o CliOptions, trace func(Trace)) (*tn.Client, error) {
	s, err := o.resolve()
	if err != nil {
		return nil, err
	}
	elems, err := normalize(s.Args)
	if err != nil {
		return nil, err
	}
	if err := validate(s, elems, o.Model); err != nil {
		return nil, err
	}
	env := map[string]string{}
	for k, v := range s.Env {
		env[k] = v
	}
	for k, v := range o.Env {
		env[k] = v
	}
	timeout := o.TimeoutMs
	if timeout <= 0 {
		timeout = s.TimeoutMs
	}
	if timeout <= 0 {
		timeout = defaultTimeoutMs
	}
	ip := o.InProcessOptions
	if ip.Model == "" {
		ip.Model = s.Name
	}
	ip.GenerateStream = nil
	ip.Generate = func(req tn.InProcessRequest) (tn.InProcessResponse, error) {
		prompt := RenderPrompt(req.Messages, req.Tools)
		start := time.Now()
		argv, text, err := invoke(s, elems, o.Model, prompt, env, timeout, o.Cwd)
		if trace != nil {
			trace(Trace{Argv: argv, Ms: time.Since(start).Milliseconds(), Bytes: len(prompt), Reply: text, Err: err})
		}
		if err != nil {
			return tn.InProcessResponse{}, err
		}
		return ParseReply(text), nil
	}
	return tn.CreateInProcessClient(ip), nil
}

// Available reports whether the resolved binary can be found.
func Available(o CliOptions) bool {
	s, err := o.resolve()
	if err != nil || s.Binary == "" {
		return false
	}
	_, err = exec.LookPath(s.Binary)
	return err == nil
}

// RenderPrompt = PREAMBLE + "\nREQUEST:\n" + {"messages","tools"}.
func RenderPrompt(messages, tools []any) string {
	if messages == nil {
		messages = []any{}
	}
	if tools == nil {
		tools = []any{}
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(struct {
		Messages []any `json:"messages"`
		Tools    []any `json:"tools"`
	}{messages, tools})
	return Preamble + "\nREQUEST:\n" + strings.TrimSuffix(buf.String(), "\n")
}

var envRefRe = regexp.MustCompile(`\$\{[A-Za-z_][A-Za-z0-9_]*\}`)

func invoke(s Style, elems []elem, model, prompt string, env map[string]string, timeoutMs int, cwd string) ([]string, string, error) {
	var tmp []string
	defer func() {
		for _, f := range tmp {
			_ = os.Remove(f)
		}
	}()
	temp := func(content *string) (string, error) {
		f, err := os.CreateTemp("", "toolnexus-cli-*")
		if err != nil {
			return "", err
		}
		name := f.Name()
		tmp = append(tmp, name)
		if content != nil {
			_, err = f.WriteString(*content)
		}
		_ = f.Close()
		if content == nil {
			_ = os.Remove(name)
		}
		return name, err
	}
	var filePath, outPath string
	var subErr error
	sub := func(p string) string {
		return placeholderRe.ReplaceAllStringFunc(p, func(m string) string {
			switch m[2 : len(m)-2] {
			case "prompt":
				return prompt
			case "model":
				return model
			case "file":
				if filePath == "" {
					filePath, subErr = temp(&prompt)
				}
				return filePath
			case "out":
				if outPath == "" {
					outPath, subErr = temp(nil)
				}
				return outPath
			}
			return m
		})
	}
	argv := []string{s.Binary}
	for _, e := range elems {
		if e.group && model == "" {
			continue
		}
		for _, p := range e.parts {
			argv = append(argv, sub(p))
		}
	}
	if subErr != nil {
		return argv, "", fmt.Errorf("cli: %w", subErr)
	}
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Dir = cwd
	if len(env) > 0 {
		e := os.Environ()
		for k, v := range env {
			e = append(e, k+"="+envRefRe.ReplaceAllStringFunc(v, func(m string) string { return os.Getenv(m[2 : len(m)-1]) }))
		}
		cmd.Env = e
	}
	if s.Prompt == "stdin" {
		cmd.Stdin = strings.NewReader(prompt)
	} else {
		cmd.Stdin = strings.NewReader("")
	}
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		return argv, "", fmt.Errorf("cli: command not found: %s", argv[0])
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	var werr error
	select {
	case werr = <-done:
	case <-ctx.Done():
		_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		<-done
		return argv, "", fmt.Errorf("cli: timed out after %dms", timeoutMs)
	}
	if werr != nil {
		var ee *exec.ExitError
		if errors.As(werr, &ee) {
			msg := strings.TrimSpace(stderr.String())
			if msg == "" {
				return argv, "", fmt.Errorf("cli: exit %d", ee.ExitCode())
			}
			if utf8.RuneCountInString(msg) > 1000 {
				msg = string([]rune(msg)[:1000]) + "…"
			}
			return argv, "", fmt.Errorf("cli: exit %d: %s", ee.ExitCode(), msg)
		}
		return argv, "", fmt.Errorf("cli: %w", werr)
	}
	text := stdout.String()
	if s.Answer == "out" {
		b, err := os.ReadFile(outPath)
		if err != nil {
			return argv, "", errors.New("cli: no output file")
		}
		text = string(b)
	}
	if strings.TrimSpace(text) == "" {
		return argv, "", errors.New("cli: empty reply")
	}
	return argv, text, nil
}

// ParseReply: the tolerant 6-step envelope parser (design of add-acp-tool-calling).
func ParseReply(text string) tn.InProcessResponse {
	s := strings.TrimSpace(text)
	if strings.HasPrefix(s, "```") {
		if i := strings.Index(s, "\n"); i >= 0 {
			s = s[i+1:]
		} else {
			s = ""
		}
		s = strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(s), "```"))
	}
	obj, ok := parseObject(s)
	if !ok {
		if i := strings.Index(s, "{"); i >= 0 {
			if j := strings.LastIndex(s, "}"); j > i {
				obj, ok = parseObject(s[i : j+1])
			}
		}
	}
	if !ok {
		return tn.InProcessResponse{Content: text}
	}
	if ch, isArr := obj["choices"].([]any); isArr && len(ch) > 0 {
		if c0, isObj := ch[0].(map[string]any); isObj {
			if m, isObj := c0["message"].(map[string]any); isObj {
				obj = m
			}
		}
	} else if m, isObj := obj["message"].(map[string]any); isObj {
		obj = m
	}
	if tcs, isArr := obj["tool_calls"].([]any); isArr {
		var calls []tn.InProcessToolCall
		for _, el := range tcs {
			e, isObj := el.(map[string]any)
			if !isObj {
				continue
			}
			fn := e
			if f, isObj := e["function"].(map[string]any); isObj {
				fn = f
			}
			name, _ := fn["name"].(string)
			if name == "" {
				continue
			}
			call := tn.InProcessToolCall{Name: name, Arguments: fn["arguments"]}
			if call.Arguments == nil {
				call.Arguments = map[string]any{}
			}
			if id, _ := e["id"].(string); id != "" {
				call.ID = id
			}
			calls = append(calls, call)
		}
		if len(calls) > 0 {
			return tn.InProcessResponse{ToolCalls: calls}
		}
	}
	if c, has := obj["content"]; has {
		switch v := c.(type) {
		case string:
			return tn.InProcessResponse{Content: v}
		case nil:
			return tn.InProcessResponse{}
		default:
			b, _ := json.Marshal(v)
			return tn.InProcessResponse{Content: string(b)}
		}
	}
	return tn.InProcessResponse{Content: text}
}

func parseObject(s string) (map[string]any, bool) {
	dec := json.NewDecoder(strings.NewReader(s))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, false
	}
	if strings.TrimSpace(s[dec.InputOffset():]) != "" {
		return nil, false // trailing text is a failure, never ignored
	}
	m, ok := v.(map[string]any)
	return m, ok
}
