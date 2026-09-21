// Package climodel is the spike implementation for ADR 0026: a CLI-backed
// model source that plugs into toolnexus.CreateInProcessClient's Generate seam.
//
// It hands a scripted or real one-shot agent CLI the verbatim assembled
// request body inside an <openai_request> envelope, strictly parses the
// <openai_response> it gets back, and repairs a malformed reply by resending
// the SAME request with a specific complaint appended — up to a bounded
// budget — rather than ever guessing at a corrupted reply.
package climodel

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

// PromptChannel picks how the assembled prompt reaches the CLI process.
type PromptChannel int

const (
	// PromptChannelFile writes the prompt to a temp file and substitutes
	// {{file}} in ArgvTemplate with its path. No length limit, no quoting
	// hazard — the preset the ADR recommends wherever the CLI supports it.
	PromptChannelFile PromptChannel = iota
	// PromptChannelArgv substitutes {{prompt}} in ArgvTemplate with the
	// prompt text directly. Subject to the OS argv/environment size limit
	// (E2BIG) — see argvlimit_test.go / gate 4.
	PromptChannelArgv
)

// ResponseChannel picks how the CLI's reply is read back.
type ResponseChannel int

const (
	// ResponseChannelStdout reads the reply from the process's stdout.
	ResponseChannelStdout ResponseChannel = iota
	// ResponseChannelFile substitutes {{out}} in ArgvTemplate with a temp
	// file path and reads the reply from there instead (codex's
	// --output-last-message shape).
	ResponseChannelFile
)

// LaunchEvent fires once per CLI process launch, including every repair
// attempt. It exists because toolnexus's own "llm" MetricEvent fires exactly
// once per Generate call (see SPIKE.md gate 5) — a CLI-backed source that
// wants repair attempts counted has to report them itself, on its own side
// channel, through this hook.
type LaunchEvent struct {
	Attempt   int      // 0 = first try, 1.. = repair attempts
	Complaint string   // the repair complaint sent this attempt, "" on attempt 0
	Argv      []string // the exact command line launched
}

// Options configures a CLI-backed Generate.
type Options struct {
	// Command is the CLI binary to run.
	Command string
	// ArgvTemplate is the argument list; {{prompt}}, {{file}}, {{out}} and
	// {{model}} are substituted per-call. This is the "CLI is configuration,
	// not code" surface from the ADR — a devin/claude/codex/copilot preset is
	// just a different Command + ArgvTemplate.
	ArgvTemplate []string

	PromptChannel   PromptChannel
	ResponseChannel ResponseChannel

	// RepairBudget is the number of ADDITIONAL attempts allowed after the
	// first, once a reply fails strict parse/validation. 0 means the first
	// reply must be clean or the call errors. Bounded on purpose — see gate 3.
	RepairBudget int

	// Model is reported to the CLI via {{model}} and echoed into metrics.
	Model string

	// TempDir overrides where prompt/out/echo files are written. Defaults to
	// os.MkdirTemp under os.TempDir().
	TempDir string

	// Timeout bounds a single CLI launch. Zero means no timeout (the spike's
	// fake CLI returns instantly; a real one would want this set).
	Timeout time.Duration

	// OnLaunch, if set, fires before every process launch — see LaunchEvent.
	OnLaunch func(LaunchEvent)
}

// errBudgetExhausted is returned (wrapped) when the repair budget runs out
// without a clean reply. Kept distinguishable so callers/tests can assert on
// termination rather than a hang.
type errBudgetExhausted struct {
	attempts  int
	lastFault string
}

func (e *errBudgetExhausted) Error() string {
	return fmt.Sprintf("climodel: repair budget exhausted after %d attempt(s), last fault: %s", e.attempts, e.lastFault)
}

// New builds a Generate function for toolnexus.CreateInProcessClient's
// InProcessOptions.Generate.
func New(opts Options) func(toolnexus.InProcessRequest) (toolnexus.InProcessResponse, error) {
	if opts.Command == "" {
		panic("climodel: Options.Command is required")
	}
	return func(req toolnexus.InProcessRequest) (toolnexus.InProcessResponse, error) {
		return generate(opts, req)
	}
}

func generate(opts Options, req toolnexus.InProcessRequest) (toolnexus.InProcessResponse, error) {
	// Canonical, deterministic JSON of the FULL assembled body — every key the
	// client put there, verbatim, including anything BodyTransform added that
	// this adapter has never heard of. json.Marshal on a map[string]any sorts
	// keys, so this is byte-reproducible from the same map.
	bodyJSON, err := json.Marshal(req.Body)
	if err != nil {
		return toolnexus.InProcessResponse{}, fmt.Errorf("climodel: cannot marshal assembled body: %w", err)
	}

	tmpDir := opts.TempDir
	if tmpDir == "" {
		d, err := os.MkdirTemp("", "climodel-*")
		if err != nil {
			return toolnexus.InProcessResponse{}, fmt.Errorf("climodel: cannot create temp dir: %w", err)
		}
		tmpDir = d
	}

	complaint := ""
	for attempt := 0; ; attempt++ {
		prompt := buildEnvelope(bodyJSON, complaint)

		argv, promptFilePath, outFilePath, err := buildArgv(opts, tmpDir, attempt, prompt)
		if err != nil {
			return toolnexus.InProcessResponse{}, err
		}

		if opts.OnLaunch != nil {
			opts.OnLaunch(LaunchEvent{Attempt: attempt, Complaint: complaint, Argv: append([]string{opts.Command}, argv...)})
		}

		raw, err := launch(opts, argv)
		if err != nil {
			// A launch failure (process could not start, non-zero exit, OS
			// argv limit, timeout) is not a model drift — do not spend the
			// repair budget on it, surface it immediately.
			return toolnexus.InProcessResponse{}, fmt.Errorf("climodel: CLI launch failed on attempt %d: %w", attempt, err)
		}

		var reply string
		if opts.ResponseChannel == ResponseChannelFile {
			b, err := os.ReadFile(outFilePath)
			if err != nil {
				return toolnexus.InProcessResponse{}, fmt.Errorf("climodel: cannot read response file %s: %w", outFilePath, err)
			}
			reply = string(b)
		} else {
			reply = raw
		}
		_ = promptFilePath // kept only for callers/tests that want to inspect it via TempDir

		resp, fault := parseAndValidate(reply, req)
		if fault == "" {
			return resp, nil
		}

		if attempt >= opts.RepairBudget {
			return toolnexus.InProcessResponse{}, &errBudgetExhausted{attempts: attempt + 1, lastFault: fault}
		}
		complaint = fault
	}
}

func buildEnvelope(bodyJSON []byte, complaint string) string {
	var b strings.Builder
	b.WriteString("<openai_request endpoint=\"/v1/chat/completions\">\n")
	b.Write(bodyJSON)
	b.WriteString("\n</openai_request>\n\n")
	b.WriteString("<instructions>\n")
	b.WriteString("Reply with ONLY the OpenAI response object inside <openai_response></openai_response> tags. ")
	b.WriteString("Do not add commentary outside the tags.\n")
	b.WriteString("</instructions>\n")
	if complaint != "" {
		b.WriteString("\n<repair>\n")
		b.WriteString("Your previous reply was rejected: ")
		b.WriteString(complaint)
		b.WriteString("\nSend the SAME request's answer again, corrected.\n")
		b.WriteString("</repair>\n")
	}
	return b.String()
}

func buildArgv(opts Options, tmpDir string, attempt int, prompt string) (argv []string, promptFilePath, outFilePath string, err error) {
	if opts.PromptChannel == PromptChannelFile {
		promptFilePath = filepath.Join(tmpDir, fmt.Sprintf("prompt-%d.txt", attempt))
		if err := os.WriteFile(promptFilePath, []byte(prompt), 0o644); err != nil {
			return nil, "", "", fmt.Errorf("climodel: cannot write prompt file: %w", err)
		}
	}
	if opts.ResponseChannel == ResponseChannelFile {
		outFilePath = filepath.Join(tmpDir, fmt.Sprintf("out-%d.txt", attempt))
	}

	argv = make([]string, 0, len(opts.ArgvTemplate))
	for _, tok := range opts.ArgvTemplate {
		switch tok {
		case "{{prompt}}":
			if opts.PromptChannel != PromptChannelArgv {
				return nil, "", "", fmt.Errorf("climodel: {{prompt}} placeholder used with a non-argv PromptChannel")
			}
			argv = append(argv, prompt)
		case "{{file}}":
			if opts.PromptChannel != PromptChannelFile {
				return nil, "", "", fmt.Errorf("climodel: {{file}} placeholder used with a non-file PromptChannel")
			}
			argv = append(argv, promptFilePath)
		case "{{out}}":
			if opts.ResponseChannel != ResponseChannelFile {
				return nil, "", "", fmt.Errorf("climodel: {{out}} placeholder used with a non-file ResponseChannel")
			}
			argv = append(argv, outFilePath)
		case "{{model}}":
			argv = append(argv, opts.Model)
		default:
			argv = append(argv, tok)
		}
	}
	return argv, promptFilePath, outFilePath, nil
}

func launch(opts Options, argv []string) (string, error) {
	ctx := context.Background()
	var cancel context.CancelFunc
	if opts.Timeout > 0 {
		ctx, cancel = context.WithTimeout(ctx, opts.Timeout)
		defer cancel()
	}
	cmd := exec.CommandContext(ctx, opts.Command, argv...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("%w (stderr: %s)", err, strings.TrimSpace(stderr.String()))
	}
	return stdout.String(), nil
}

// parseAndValidate strictly parses a CLI reply and, on success, converts it to
// a toolnexus.InProcessResponse. On any drift it returns ("", "") on success
// or ("", fault) describing the SPECIFIC repair complaint to send back — it
// never guesses or degrades a malformed reply into plain content.
func parseAndValidate(reply string, req toolnexus.InProcessRequest) (toolnexus.InProcessResponse, string) {
	inner, ok := extractTag(reply, "openai_response")
	if !ok {
		return toolnexus.InProcessResponse{}, "no <openai_response>...</openai_response> tags found in your reply; reply with ONLY the OpenAI response object inside those tags"
	}

	var parsed struct {
		Choices []struct {
			Message      json.RawMessage `json:"message"`
			FinishReason string          `json:"finish_reason"`
		} `json:"choices"`
	}
	if err := json.Unmarshal([]byte(inner), &parsed); err != nil {
		return toolnexus.InProcessResponse{}, "the content inside <openai_response> is not valid JSON: " + err.Error()
	}
	if len(parsed.Choices) == 0 {
		return toolnexus.InProcessResponse{}, "the OpenAI response object has no choices[0]"
	}

	var msg struct {
		Content   json.RawMessage `json:"content"`
		ToolCalls []struct {
			ID       string `json:"id"`
			Type     string `json:"type"`
			Function struct {
				Name      string          `json:"name"`
				Arguments json.RawMessage `json:"arguments"`
			} `json:"function"`
		} `json:"tool_calls"`
	}
	if err := json.Unmarshal(parsed.Choices[0].Message, &msg); err != nil {
		return toolnexus.InProcessResponse{}, "choices[0].message is not a valid OpenAI message object: " + err.Error()
	}

	// Drift B: structured payload landed in `content` as a JSON object instead
	// of in tool_calls[].function.arguments. A valid `content` is either
	// absent/null or a JSON string — never a bare object/array.
	if len(msg.Content) > 0 && !bytes.Equal(bytes.TrimSpace(msg.Content), []byte("null")) {
		trimmed := bytes.TrimSpace(msg.Content)
		if len(trimmed) > 0 && trimmed[0] != '"' {
			return toolnexus.InProcessResponse{}, "message.content must be a JSON string (or absent), not a JSON object/array; " +
				"put a tool call's structured payload in tool_calls[].function.arguments instead"
		}
	}

	// Drift A: tool_calls present wins over whatever finish_reason/kind claims.
	// We dispatch on CONTENT, never on the declared label — so a
	// finish_reason of "answer" (or any other value) alongside a populated
	// tool_calls array must still be honored as a tool call, not dropped.
	if len(msg.ToolCalls) > 0 {
		calls := make([]toolnexus.InProcessToolCall, 0, len(msg.ToolCalls))
		for i, c := range msg.ToolCalls {
			// Drift C: arguments arriving as a bare JSON object rather than
			// the OpenAI-contractual JSON-encoded string.
			argsTrimmed := bytes.TrimSpace(c.Function.Arguments)
			if len(argsTrimmed) > 0 && argsTrimmed[0] != '"' {
				return toolnexus.InProcessResponse{}, fmt.Sprintf(
					"tool_calls[%d].function.arguments must be a JSON-encoded STRING (e.g. \"{\\\"path\\\":\\\"x\\\"}\"), "+
						"not a bare JSON object", i)
			}
			var argsStr string
			if len(argsTrimmed) == 0 {
				argsStr = "{}"
			} else if err := json.Unmarshal(argsTrimmed, &argsStr); err != nil {
				return toolnexus.InProcessResponse{}, fmt.Sprintf(
					"tool_calls[%d].function.arguments is not a valid JSON-encoded string: %s", i, err.Error())
			}

			// Drift D: re-calling a tool whose result is already in the
			// transcript.
			if alreadyAnswered(req, c.Function.Name, argsStr) {
				return toolnexus.InProcessResponse{}, fmt.Sprintf(
					"tool %q was already called with these exact arguments and its result is already in the "+
						"transcript; do not call it again, answer using the existing result", c.Function.Name)
			}

			id := c.ID
			if id == "" {
				id = fmt.Sprintf("call_%d", i)
			}
			calls = append(calls, toolnexus.InProcessToolCall{ID: id, Name: c.Function.Name, Arguments: json.RawMessage(argsStr)})
		}
		return toolnexus.InProcessResponse{ToolCalls: calls}, ""
	}

	var content string
	if len(msg.Content) > 0 && !bytes.Equal(bytes.TrimSpace(msg.Content), []byte("null")) {
		if err := json.Unmarshal(msg.Content, &content); err != nil {
			return toolnexus.InProcessResponse{}, "message.content is not a valid JSON string: " + err.Error()
		}
	}
	return toolnexus.InProcessResponse{Content: content}, ""
}

// alreadyAnswered scans the transcript handed to this turn for a prior
// assistant tool_calls entry with the same name+arguments that already has a
// matching tool-result message.
func alreadyAnswered(req toolnexus.InProcessRequest, name, argsJSON string) bool {
	type call struct{ id, name, args string }
	seen := map[string]call{}
	answeredIDs := map[string]bool{}

	for _, m := range req.Messages {
		mm, ok := m.(map[string]any)
		if !ok {
			continue
		}
		switch mm["role"] {
		case "assistant":
			tcs, _ := mm["tool_calls"].([]any)
			for _, raw := range tcs {
				tc, ok := raw.(map[string]any)
				if !ok {
					continue
				}
				id, _ := tc["id"].(string)
				fn, _ := tc["function"].(map[string]any)
				fname, _ := fn["name"].(string)
				fargs, _ := fn["arguments"].(string)
				seen[id] = call{id: id, name: fname, args: normalizeJSON(fargs)}
			}
		case "tool":
			if id, ok := mm["tool_call_id"].(string); ok {
				answeredIDs[id] = true
			}
		}
	}

	wantArgs := normalizeJSON(argsJSON)
	for id, c := range seen {
		if c.name == name && c.args == wantArgs && answeredIDs[id] {
			return true
		}
	}
	return false
}

// normalizeJSON re-marshals a JSON string through a generic map/slice/scalar
// so semantically-equal JSON with different key order or whitespace compares
// equal.
func normalizeJSON(s string) string {
	var v any
	if err := json.Unmarshal([]byte(s), &v); err != nil {
		return s
	}
	b, err := json.Marshal(v)
	if err != nil {
		return s
	}
	return string(b)
}

func extractTag(s, tag string) (string, bool) {
	open := "<" + tag
	close := "</" + tag + ">"
	oi := strings.Index(s, open)
	if oi == -1 {
		return "", false
	}
	gt := strings.Index(s[oi:], ">")
	if gt == -1 {
		return "", false
	}
	start := oi + gt + 1
	ci := strings.Index(s[start:], close)
	if ci == -1 {
		return "", false
	}
	return strings.TrimSpace(s[start : start+ci]), true
}

// canonicalBody re-marshals a map deterministically — exposed for tests that
// need to compute the "expected" verbatim bytes independently of Generate.
func canonicalBody(body map[string]any) []byte {
	b, _ := json.Marshal(body)
	return b
}

// writeScript and writeState are tiny test helpers kept in the package (not
// _test.go) only because fakecli's on-disk formats are part of this
// package's contract with its own test binary.
func writeScript(path string, entries []string) error {
	b, err := json.MarshalIndent(entries, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, b, 0o644)
}

func readState(path string) int {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	return n
}
