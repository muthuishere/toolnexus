// CLI client styles (SPEC §8C, ADR 0037): an agent CLI the user already has
// installed — and pays for — becomes the model behind an ordinary in-process
// client, selected by a closed enum, never by a string.
//
// Two transports, one constructor:
//
//   - ACP (primary, cli_acp.go): when the style declares an ACP launch and the
//     binary speaks it, the user's whole toolkit is served as a local MCP server
//     and handed to the agent in session/new mcpServers. The agent calls the
//     tools natively.
//   - one-shot CLI (fallback, this file): the CLI is run once per model turn
//     with the conversation rendered under a fixed preamble; its reply is parsed
//     as an OpenAI-shaped envelope and tool calls go back to the toolnexus loop.
//
// A style is data (clistyles/*.json, byte-identical to conformance/cli-styles/)
// and holds no binary: Command is always the caller's.
package toolnexus

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
	"sync"
	"time"
	"unicode/utf8"
)

//go:embed clistyles/*.json
var cliStylesFS embed.FS

//go:embed clistyles/preamble.txt
var CliPreamble string

// cliDefaultCallTimeoutMs bounds one CLI invocation / one ACP prompt (owner
// decision 2026-11-04).
const cliDefaultCallTimeoutMs = 300000

// CliStyle is one member of the closed set of CLI families a CLI client can
// drive. Each is a shape (argv, prompt mode, answer source, ACP launch), never
// a binary.
type CliStyle int

const (
	cliStyleClaude CliStyle = iota + 1 // zero value = no style chosen
	cliStyleCodex
	cliStyleCopilot
	cliStyleOpencode
	cliStyleDevin
	cliStyleGemini
	cliStyleGoose
	cliStyleCustom
)

// CliStyles is the enum namespace: CliStyles.Claude, CliStyles.Codex, …
var CliStyles = struct {
	Claude, Codex, Copilot, Opencode, Devin, Gemini, Goose, Custom CliStyle
}{cliStyleClaude, cliStyleCodex, cliStyleCopilot, cliStyleOpencode, cliStyleDevin, cliStyleGemini, cliStyleGoose, cliStyleCustom}

var cliStyleNames = map[CliStyle]string{
	cliStyleClaude: "claude", cliStyleCodex: "codex", cliStyleCopilot: "copilot", cliStyleOpencode: "opencode",
	cliStyleDevin: "devin", cliStyleGemini: "gemini", cliStyleGoose: "goose", cliStyleCustom: "custom",
}

// String is the style's shared file name. It is never accepted as input.
func (s CliStyle) String() string { return cliStyleNames[s] }

// CliTransport says how a CLI client reaches the agent.
type CliTransport int

const (
	cliTransportAuto CliTransport = iota // zero value
	cliTransportACP
	cliTransportCLI
)

// CliTransports: Auto (default) uses ACP when the style has an ACP launch and
// the binary answers initialize, else the one-shot CLI; ACP and CLI force one.
var CliTransports = struct{ Auto, ACP, CLI CliTransport }{cliTransportAuto, cliTransportACP, cliTransportCLI}

func (t CliTransport) String() string {
	switch t {
	case cliTransportACP:
		return "acp"
	case cliTransportCLI:
		return "cli"
	}
	return "auto"
}

// CliACPLaunch is a style's ACP launch: the args appended to Command to start
// it as an ACP agent, and, for agents that ignore session/new mcpServers, the
// project MCP file written into a fresh temp session cwd.
type CliACPLaunch struct {
	Args           []string `json:"args"`
	ProjectMCPFile string   `json:"projectMcpFile,omitempty"`
	// SystemHint, when set, is appended to the system prompt of every ACP
	// turn (a new system message when there is none). Data, not code: devin
	// only finds MCP tools when the prompt names the server.
	SystemHint string `json:"systemHint,omitempty"`
	Verified       string   `json:"verified,omitempty"`
	Notes          string   `json:"notes,omitempty"`
}

// CliStyleDef is one CLI's invocation recipe — data shared by every port.
// Args elements are strings or optional groups ([]any of strings, kept only
// when a model is set). Prompt: stdin | arg | file. Answer: stdout | out.
type CliStyleDef struct {
	Name      string            `json:"name"`
	Args      []any             `json:"args"`
	Prompt    string            `json:"prompt"`
	Answer    string            `json:"answer"`
	TimeoutMs int               `json:"timeoutMs,omitempty"`
	Env       map[string]string `json:"env,omitempty"`
	Verified  string            `json:"verified,omitempty"`
	Notes     string            `json:"notes,omitempty"`
	ACP       *CliACPLaunch     `json:"acp,omitempty"`
}

// CliStyleDefFor returns the built-in definition of a style (false for
// Custom and the zero value).
func CliStyleDefFor(style CliStyle) (CliStyleDef, bool) {
	name, ok := cliStyleNames[style]
	if !ok || style == cliStyleCustom {
		return CliStyleDef{}, false
	}
	b, err := cliStylesFS.ReadFile("clistyles/" + name + ".json")
	if err != nil {
		return CliStyleDef{}, false
	}
	var d CliStyleDef
	if json.Unmarshal(b, &d) != nil {
		return CliStyleDef{}, false
	}
	return d, true
}

// LoadCliStyle reads a user style from a file path, or from JSON text that
// starts with "{". Pass the result as CliOptions.StyleDef.
func LoadCliStyle(pathOrJSON string) (CliStyleDef, error) {
	data := []byte(pathOrJSON)
	if !strings.HasPrefix(strings.TrimSpace(pathOrJSON), "{") {
		b, err := os.ReadFile(pathOrJSON)
		if err != nil {
			return CliStyleDef{}, fmt.Errorf("cli style: %w", err)
		}
		data = b
	}
	var d CliStyleDef
	if err := json.Unmarshal(data, &d); err != nil {
		return CliStyleDef{}, fmt.Errorf("cli style: %w", err)
	}
	return d, nil
}

// CliOptions configures CreateCliClient. Command is REQUIRED: the library never
// picks or defaults the binary. InProcessOptions.Model is passed to the CLI
// unchanged (empty ⇒ the CLI's own default model).
type CliOptions struct {
	InProcessOptions

	// Style picks a built-in recipe. Required unless StyleDef is set.
	Style CliStyle
	// StyleDef is a user style (LoadCliStyle); it wins over Style.
	StyleDef *CliStyleDef
	// Command is the CLI binary, a path or a name on PATH. Required.
	Command string

	// Args, Prompt and Answer describe the CLI for Style Custom.
	Args   []any
	Prompt string
	Answer string

	// Env is added to the child's environment; values expand ${VAR} from this
	// process's environment at call time and are never logged.
	Env map[string]string
	// CallTimeoutMs bounds one CLI invocation or one ACP prompt. Default
	// 300000. (InProcessOptions.TimeoutMs still bounds the whole run.)
	CallTimeoutMs int
	// Cwd is the child's working directory (default: this process's).
	Cwd string

	// Transport: CliTransports.Auto (default), .ACP or .CLI.
	Transport CliTransport
	// Toolkit is served to an ACP agent as a local MCP server. Pass the same
	// toolkit (or nil) to Run.
	Toolkit *Toolkit
	// ACPCommand, if set, is the full argv that starts the agent in ACP mode
	// (e.g. an adapter: npx -y @agentclientprotocol/claude-agent-acp). It
	// replaces Command + the style's acp.args for the ACP launch only.
	ACPCommand []string
	// BridgeCommand is the argv of a stdio⇄HTTP MCP bridge, used only for an
	// agent that accepts neither http MCP servers nor a project MCP file. The
	// server URL is appended; the bearer token goes in env TOOLNEXUS_MCP_TOKEN.
	// Default: `toolnexus mcp-bridge` when the toolnexus CLI is on PATH.
	BridgeCommand []string
	// OnACPUpdate observes every raw ACP session/update (must not block).
	OnACPUpdate func(json.RawMessage)
}

// CliClient is an ordinary *Client whose model is an agent CLI, plus Close
// (ends the ACP agent and the served toolkit) and Transport.
type CliClient struct {
	*Client

	style    CliStyleDef
	opts     CliOptions
	elems    []cliElem
	hasCLI   bool // the style has a usable one-shot transport
	env      map[string]string
	timeout  int
	acpArgv  []string
	mu       sync.Mutex
	chosen   CliTransport // Auto until the first call decides
	acp      *cliACPSession
	closed   bool
	lastNote string
}

// Transport reports the transport in use: "acp", "cli", or "auto" before the
// first model call has decided.
func (c *CliClient) Transport() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.chosen.String()
}

// FallbackReason is why Auto fell back from ACP to the one-shot CLI ("" when
// it did not).
func (c *CliClient) FallbackReason() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lastNote
}

// Close ends the ACP agent's process group, stops the served toolkit and
// removes any temp session dir. Idempotent; a no-op for the one-shot CLI.
func (c *CliClient) Close() error {
	c.mu.Lock()
	s := c.acp
	c.acp, c.closed = nil, true
	c.mu.Unlock()
	if s != nil {
		return s.Close()
	}
	return nil
}

func (o CliOptions) resolveStyle() (CliStyleDef, error) {
	if o.Command == "" {
		return CliStyleDef{}, errors.New("cli: Command is required (path or name of the CLI binary)")
	}
	if o.StyleDef != nil {
		return *o.StyleDef, nil
	}
	if o.Style == cliStyleCustom {
		return CliStyleDef{Name: "custom", Args: o.Args, Prompt: o.Prompt, Answer: o.Answer}, nil
	}
	d, ok := CliStyleDefFor(o.Style)
	if !ok {
		return CliStyleDef{}, errors.New("cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)")
	}
	return d, nil
}

// CreateCliClient builds a client whose model is an installed agent CLI. It
// validates everything it can at construction; the transport (ACP or one-shot)
// is decided on the first model call.
func CreateCliClient(o CliOptions) (*CliClient, error) {
	d, err := o.resolveStyle()
	if err != nil {
		return nil, err
	}
	c := &CliClient{style: d, opts: o}

	if len(o.ACPCommand) > 0 {
		c.acpArgv = append([]string{}, o.ACPCommand...)
	} else if d.ACP != nil {
		c.acpArgv = append([]string{o.Command}, d.ACP.Args...)
	}
	if o.Transport == cliTransportACP && c.acpArgv == nil {
		return nil, fmt.Errorf("cli: style %q has no ACP launch; set ACPCommand or use CliTransports.CLI", d.Name)
	}
	if o.Transport != cliTransportACP {
		if d.Args == nil && d.Prompt == "" && d.Answer == "" && c.acpArgv != nil && o.Transport == cliTransportAuto {
			// an ACP-only user style: no one-shot fallback
		} else {
			elems, err := normalizeCliArgs(d.Args)
			if err != nil {
				return nil, err
			}
			if err := validateCliStyle(d, elems, o.Model); err != nil {
				return nil, err
			}
			c.elems, c.hasCLI = elems, true
		}
	}

	c.env = map[string]string{}
	for k, v := range d.Env {
		c.env[k] = v
	}
	for k, v := range o.Env {
		c.env[k] = v
	}
	c.timeout = o.CallTimeoutMs
	if c.timeout <= 0 {
		c.timeout = d.TimeoutMs
	}
	if c.timeout <= 0 {
		c.timeout = cliDefaultCallTimeoutMs
	}
	switch {
	case o.Transport == cliTransportCLI || c.acpArgv == nil:
		c.chosen = cliTransportCLI
	case o.Transport == cliTransportACP:
		c.chosen = cliTransportACP
	}

	ip := o.InProcessOptions
	if ip.Model == "" {
		ip.Model = d.Name
	}
	ip.Generate = nil
	ip.GenerateStream = c.generate
	c.Client = CreateInProcessClient(ip)
	return c, nil
}

// generate is the in-process model: it opens (or reuses) the ACP session, or
// runs the one-shot CLI.
func (c *CliClient) generate(ctx context.Context, req InProcessRequest, emit func(StreamEvent)) (InProcessResponse, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	s, err := c.session(ctx)
	if err != nil {
		return InProcessResponse{}, err
	}
	if s != nil {
		return s.generate(ctx, req, emit)
	}
	if stream, _ := req.Body["stream"].(bool); stream {
		return InProcessResponse{}, errors.New("cli: streaming is not supported by the one-shot CLI transport; use Run")
	}
	prompt := RenderCliPrompt(req.Messages, req.Tools)
	text, err := c.invoke(ctx, prompt)
	if err != nil {
		return InProcessResponse{}, err
	}
	return ParseCliReply(text), nil
}

// session returns the live ACP session, opening it on first use; nil means
// the one-shot transport is in use.
func (c *CliClient) session(ctx context.Context) (*cliACPSession, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil, errors.New("cli: client is closed")
	}
	if c.chosen == cliTransportCLI {
		return nil, nil
	}
	if c.acp != nil {
		return c.acp, nil
	}
	s, err := openCliACPSession(ctx, c)
	if err != nil {
		var launch *cliACPLaunchError
		if c.chosen == cliTransportAuto && errors.As(err, &launch) && c.hasCLI {
			c.chosen = cliTransportCLI
			c.lastNote = err.Error()
			return nil, nil
		}
		return nil, err
	}
	c.acp, c.chosen = s, cliTransportACP
	return s, nil
}

// ---------------------------------------------------------------------------
// Argv templating and validation.
// ---------------------------------------------------------------------------

var cliPlaceholderRe = regexp.MustCompile(`\{\{([^{}]*)\}\}`)

type cliElem struct {
	group bool
	parts []string
}

func normalizeCliArgs(args []any) ([]cliElem, error) {
	var out []cliElem
	for _, e := range args {
		switch v := e.(type) {
		case string:
			out = append(out, cliElem{parts: []string{v}})
		case []string:
			out = append(out, cliElem{group: true, parts: v})
		case []any:
			g := cliElem{group: true}
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

func validateCliStyle(d CliStyleDef, elems []cliElem, model string) error {
	switch d.Prompt {
	case "stdin", "arg", "file":
	default:
		return fmt.Errorf("cli: prompt must be stdin, arg or file, not %q", d.Prompt)
	}
	switch d.Answer {
	case "stdout", "out":
	default:
		return fmt.Errorf("cli: answer must be stdout or out, not %q", d.Answer)
	}
	seen := map[string]bool{}
	for _, e := range elems {
		for _, p := range e.parts {
			for _, m := range cliPlaceholderRe.FindAllStringSubmatch(p, -1) {
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
		return fmt.Errorf("cli: style %q takes no model", d.Name)
	}
	if d.Prompt == "arg" && !seen["prompt"] {
		return errors.New(`cli: prompt "arg" needs {{prompt}} in args`)
	}
	if d.Prompt == "file" && !seen["file"] {
		return errors.New(`cli: prompt "file" needs {{file}} in args`)
	}
	if d.Answer == "out" && !seen["out"] {
		return errors.New(`cli: answer "out" needs {{out}} in args`)
	}
	return nil
}

// ---------------------------------------------------------------------------
// The envelope: render and tolerant parse.
// ---------------------------------------------------------------------------

// RenderCliPrompt is PREAMBLE + "\nREQUEST:\n" + {"messages","tools"}: the
// OpenAI-shaped request, unmodified, as compact JSON without HTML escaping.
func RenderCliPrompt(messages, tools []any) string {
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
	return CliPreamble + "\nREQUEST:\n" + strings.TrimSuffix(buf.String(), "\n")
}

// ParseCliReply is the tolerant six-step envelope parser: (1) trim; (2) strip
// one markdown fence; (3) parse the whole text as one JSON object, else (4)
// the span from the first "{" to the last "}"; (5) unwrap choices[0].message
// or message; (6) tool_calls (flat or {function:{}}, string or object
// arguments) win, else content. Anything that is not an envelope is returned
// verbatim as content.
func ParseCliReply(text string) InProcessResponse {
	s := strings.TrimSpace(text)
	if strings.HasPrefix(s, "```") {
		if i := strings.Index(s, "\n"); i >= 0 {
			s = s[i+1:]
		} else {
			s = ""
		}
		s = strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(s), "```"))
	}
	obj, ok := cliParseObject(s)
	if !ok {
		if i := strings.Index(s, "{"); i >= 0 {
			if j := strings.LastIndex(s, "}"); j > i {
				obj, ok = cliParseObject(s[i : j+1])
			}
		}
	}
	if !ok {
		return InProcessResponse{Content: text}
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
		var calls []InProcessToolCall
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
			call := InProcessToolCall{Name: name, Arguments: fn["arguments"]}
			if call.Arguments == nil {
				call.Arguments = map[string]any{}
			}
			if id, _ := e["id"].(string); id != "" {
				call.ID = id
			}
			calls = append(calls, call)
		}
		if len(calls) > 0 {
			return InProcessResponse{ToolCalls: calls}
		}
	}
	if c, has := obj["content"]; has {
		switch v := c.(type) {
		case string:
			return InProcessResponse{Content: v}
		case nil:
			return InProcessResponse{}
		default:
			b, _ := json.Marshal(v)
			return InProcessResponse{Content: string(b)}
		}
	}
	return InProcessResponse{Content: text}
}

func cliParseObject(s string) (map[string]any, bool) {
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

// ---------------------------------------------------------------------------
// One-shot invocation.
// ---------------------------------------------------------------------------

var cliEnvRefRe = regexp.MustCompile(`\$\{[A-Za-z_][A-Za-z0-9_]*\}`)

func expandCliEnv(env map[string]string) []string {
	out := make([]string, 0, len(env))
	for k, v := range env {
		out = append(out, k+"="+cliEnvRefRe.ReplaceAllStringFunc(v, func(m string) string { return os.Getenv(m[2 : len(m)-1]) }))
	}
	return out
}

// cliArgv renders the argv for one invocation; it returns the temp files it
// created so the caller removes them on every path.
func (c *CliClient) cliArgv(prompt string) (argv []string, outPath string, tmp []string, err error) {
	model := c.opts.Model
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
			_ = os.Remove(name) // the CLI creates it; absence ⇒ "no output file"
		}
		return name, err
	}
	var filePath string
	sub := func(p string) string {
		return cliPlaceholderRe.ReplaceAllStringFunc(p, func(m string) string {
			switch m[2 : len(m)-2] {
			case "prompt":
				return prompt
			case "model":
				return model
			case "file":
				if filePath == "" && err == nil {
					filePath, err = temp(&prompt)
				}
				return filePath
			case "out":
				if outPath == "" && err == nil {
					outPath, err = temp(nil)
				}
				return outPath
			}
			return m
		})
	}
	argv = []string{c.opts.Command}
	for _, e := range c.elems {
		if e.group && model == "" {
			continue
		}
		for _, p := range e.parts {
			argv = append(argv, sub(p))
		}
	}
	return argv, outPath, tmp, err
}

func (c *CliClient) invoke(ctx context.Context, prompt string) (string, error) {
	argv, outPath, tmp, err := c.cliArgv(prompt)
	defer func() {
		for _, f := range tmp {
			_ = os.Remove(f)
		}
	}()
	if err != nil {
		return "", fmt.Errorf("cli: %w", err)
	}
	bin, lerr := exec.LookPath(argv[0])
	if lerr != nil {
		return "", fmt.Errorf("cli: command not found: %s", argv[0])
	}
	cmd := exec.Command(bin, argv[1:]...)
	cmd.Dir = c.opts.Cwd
	if len(c.env) > 0 {
		cmd.Env = append(os.Environ(), expandCliEnv(c.env)...)
	}
	if c.style.Prompt == "stdin" {
		cmd.Stdin = strings.NewReader(prompt)
	} else {
		cmd.Stdin = strings.NewReader("")
	}
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := startJob(cmd); err != nil {
		return "", fmt.Errorf("cli: command not found: %s", argv[0])
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	timer := time.NewTimer(time.Duration(c.timeout) * time.Millisecond)
	defer timer.Stop()
	var werr error
	select {
	case werr = <-done:
	case <-timer.C:
		_ = signalJob(cmd, false)
		<-done
		return "", fmt.Errorf("cli: timed out after %dms", c.timeout)
	case <-ctx.Done():
		_ = signalJob(cmd, false)
		<-done
		return "", ctx.Err()
	}
	if werr != nil {
		var ee *exec.ExitError
		if errors.As(werr, &ee) {
			msg := strings.TrimSpace(stderr.String())
			if msg == "" {
				return "", fmt.Errorf("cli: exit %d", ee.ExitCode())
			}
			if utf8.RuneCountInString(msg) > 1000 {
				msg = string([]rune(msg)[:1000]) + "…"
			}
			return "", fmt.Errorf("cli: exit %d: %s", ee.ExitCode(), msg)
		}
		return "", fmt.Errorf("cli: %w", werr)
	}
	text := stdout.String()
	if c.style.Answer == "out" {
		b, err := os.ReadFile(outPath)
		if err != nil {
			return "", errors.New("cli: no output file")
		}
		text = string(b)
	}
	if strings.TrimSpace(text) == "" {
		return "", errors.New("cli: empty reply")
	}
	return text, nil
}
