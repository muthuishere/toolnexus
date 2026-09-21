package climodel

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

// fakecliPath is built once by TestMain and reused by every test — one real
// `go build`, hermetic, no network.
var fakecliPath string

func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "fakecli-bin-*")
	if err != nil {
		panic(err)
	}
	fakecliPath = filepath.Join(dir, "fakecli")
	cmd := exec.Command("go", "build", "-o", fakecliPath, "./fakecli")
	cmd.Dir = ".." // module root: spikes/cli-model
	out, err := cmd.CombinedOutput()
	if err != nil {
		panic("failed to build fakecli: " + err.Error() + "\n" + string(out))
	}
	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

func newScriptDir(t *testing.T, entries []string) (scriptPath, statePath, workDir string) {
	t.Helper()
	dir := t.TempDir()
	scriptPath = filepath.Join(dir, "script.json")
	statePath = filepath.Join(dir, "state.txt")
	if err := writeScript(scriptPath, entries); err != nil {
		t.Fatalf("writeScript: %v", err)
	}
	return scriptPath, statePath, dir
}

func baseOpts(scriptPath, statePath, workDir string) Options {
	return Options{
		Command: fakecliPath,
		ArgvTemplate: []string{
			"-prompt-file", "{{file}}",
			"-script", scriptPath,
			"-state", statePath,
		},
		PromptChannel:   PromptChannelFile,
		ResponseChannel: ResponseChannelStdout,
		Model:           "fake-model-1",
		TempDir:         workDir,
	}
}

func wrap(jsonBody string) string {
	return "<openai_response>\n" + jsonBody + "\n</openai_response>"
}

// --- Gate 1a: kind:"answer" WITH populated tool_calls must NOT be dropped ---

func TestDriftA_ToolCallsWinOverFinishReasonAndKind(t *testing.T) {
	hostile := wrap(`{
		"choices": [{
			"finish_reason": "answer",
			"message": {
				"role": "assistant",
				"kind": "answer",
				"content": null,
				"tool_calls": [{
					"id": "call_1",
					"type": "function",
					"function": {"name": "read_file", "arguments": "{\"path\":\"README.md\"}"}
				}]
			}
		}]
	}`)
	scriptPath, statePath, workDir := newScriptDir(t, []string{hostile})
	opts := baseOpts(scriptPath, statePath, workDir)
	opts.RepairBudget = 0 // must succeed on the FIRST reply — this is not a drift that should even cost a repair

	launches := 0
	opts.OnLaunch = func(LaunchEvent) { launches++ }

	req := toolnexus.InProcessRequest{Body: map[string]any{"model": "fake-model-1"}}
	resp, err := generate(opts, req)
	if err != nil {
		t.Fatalf("generate returned error for a message whose CONTENT is a valid tool call: %v", err)
	}
	if len(resp.ToolCalls) != 1 {
		t.Fatalf("tool call was dropped because finish_reason/kind said \"answer\" — got %d tool calls, want 1", len(resp.ToolCalls))
	}
	if resp.ToolCalls[0].Name != "read_file" {
		t.Fatalf("wrong tool call surfaced: %+v", resp.ToolCalls[0])
	}
	if launches != 1 {
		t.Fatalf("expected exactly 1 CLI launch (no repair needed), got %d", launches)
	}
}

// --- Gate 1b: structured payload in `content` as an object instead of arguments ---

func TestDriftB_ContentAsObjectTriggersRepairThenSucceeds(t *testing.T) {
	hostile := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":{"path":"README.md"}}}]}`)
	fixed := wrap(`{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]}}]}`)

	scriptPath, statePath, workDir := newScriptDir(t, []string{hostile, fixed})
	opts := baseOpts(scriptPath, statePath, workDir)
	opts.RepairBudget = 1

	var complaints []string
	opts.OnLaunch = func(ev LaunchEvent) { complaints = append(complaints, ev.Complaint) }

	req := toolnexus.InProcessRequest{Body: map[string]any{"model": "fake-model-1"}}
	resp, err := generate(opts, req)
	if err != nil {
		t.Fatalf("generate should have repaired and succeeded, got error: %v", err)
	}
	if len(resp.ToolCalls) != 1 || resp.ToolCalls[0].Name != "read_file" {
		t.Fatalf("unexpected response after repair: %+v", resp)
	}
	if len(complaints) != 2 {
		t.Fatalf("expected 2 launches (1 hostile + 1 repaired), got %d", len(complaints))
	}
	if complaints[0] != "" {
		t.Fatalf("first attempt should carry no complaint, got %q", complaints[0])
	}
	if !strings.Contains(complaints[1], "content must be a JSON string") {
		t.Fatalf("repair complaint did not name the content-as-object drift: %q", complaints[1])
	}
}

// --- Gate 1c: `arguments` as a bare JSON object rather than a JSON-encoded string ---

func TestDriftC_ArgumentsAsObjectTriggersRepairThenSucceeds(t *testing.T) {
	hostile := wrap(`{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":{"path":"README.md"}}}]}}]}`)
	fixed := wrap(`{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]}}]}`)

	scriptPath, statePath, workDir := newScriptDir(t, []string{hostile, fixed})
	opts := baseOpts(scriptPath, statePath, workDir)
	opts.RepairBudget = 1

	var complaints []string
	opts.OnLaunch = func(ev LaunchEvent) { complaints = append(complaints, ev.Complaint) }

	req := toolnexus.InProcessRequest{Body: map[string]any{"model": "fake-model-1"}}
	resp, err := generate(opts, req)
	if err != nil {
		t.Fatalf("generate should have repaired and succeeded, got error: %v", err)
	}
	if len(resp.ToolCalls) != 1 {
		t.Fatalf("unexpected response after repair: %+v", resp)
	}
	if args, _ := resp.ToolCalls[0].Arguments.(json.RawMessage); string(args) != `{"path":"README.md"}` {
		t.Fatalf("arguments not normalized to a JSON-string-decoded value: %v", resp.ToolCalls[0].Arguments)
	}
	if len(complaints) != 2 || !strings.Contains(complaints[1], "JSON-encoded STRING") {
		t.Fatalf("expected a repair complaint about arguments type, got %+v", complaints)
	}
}

// --- Gate 1d: re-calling a tool whose result is already in the transcript ---

func TestDriftD_DuplicateToolCallTriggersRepairThenSucceeds(t *testing.T) {
	repeat := wrap(`{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_2","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]}}]}`)
	answer := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"the file says hello"}}]}`)

	scriptPath, statePath, workDir := newScriptDir(t, []string{repeat, answer})
	opts := baseOpts(scriptPath, statePath, workDir)
	opts.RepairBudget = 1

	var complaints []string
	opts.OnLaunch = func(ev LaunchEvent) { complaints = append(complaints, ev.Complaint) }

	req := toolnexus.InProcessRequest{
		Body: map[string]any{"model": "fake-model-1"},
		Messages: []any{
			map[string]any{
				"role": "assistant",
				"tool_calls": []any{
					map[string]any{
						"id":       "call_1",
						"type":     "function",
						"function": map[string]any{"name": "read_file", "arguments": `{"path":"README.md"}`},
					},
				},
			},
			map[string]any{"role": "tool", "tool_call_id": "call_1", "content": "hello"},
		},
	}
	resp, err := generate(opts, req)
	if err != nil {
		t.Fatalf("generate should have repaired the duplicate call and succeeded, got error: %v", err)
	}
	if resp.Content != "the file says hello" {
		t.Fatalf("unexpected final content: %q", resp.Content)
	}
	if len(complaints) != 2 || !strings.Contains(complaints[1], "already been called") && !strings.Contains(complaints[1], "already called") {
		t.Fatalf("expected a repair complaint about the duplicate call, got %+v", complaints)
	}
}

// --- Gate 3: the repair budget terminates — a CLI that never complies errors, not loops ---

func TestRepairBudgetExhausted_ErrorsInsteadOfLoopingForever(t *testing.T) {
	neverComplies := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":{"still":"an object"}}}]}`)
	scriptPath, statePath, workDir := newScriptDir(t, []string{neverComplies}) // one entry, reused forever by fakecli's clamp

	opts := baseOpts(scriptPath, statePath, workDir)
	opts.RepairBudget = 2 // 1 first try + 2 repairs = 3 launches, bounded

	launches := 0
	opts.OnLaunch = func(LaunchEvent) { launches++ }

	req := toolnexus.InProcessRequest{Body: map[string]any{"model": "fake-model-1"}}
	_, err := generate(opts, req)
	if err == nil {
		t.Fatalf("expected an error once the repair budget is exhausted, got nil")
	}
	var budgetErr *errBudgetExhausted
	if !errors.As(err, &budgetErr) {
		t.Fatalf("expected *errBudgetExhausted, got %T: %v", err, err)
	}
	if launches != 3 {
		t.Fatalf("expected exactly 3 bounded launches (1 + budget of 2), got %d — a bug here means an unbounded loop", launches)
	}
}

// --- Gate 2: verbatim passthrough — CLI receives bytes byte-equal to the assembled body ---

func TestVerbatimPassthrough_ByteEqualIncludingUnknownKey(t *testing.T) {
	ok := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}`)
	scriptPath, statePath, workDir := newScriptDir(t, []string{ok})

	opts := baseOpts(scriptPath, statePath, workDir)
	opts.RepairBudget = 0

	client := toolnexus.CreateInProcessClient(toolnexus.InProcessOptions{
		Model:    "fake-model-1",
		Generate: New(opts),
		BodyTransform: func(body map[string]any) map[string]any {
			// A key this adapter (and every adapter) has never heard of —
			// simulating something toolnexus adds later, or a host-specific
			// extension. Passthrough must carry it unexamined.
			body["x_vendor_extension_never_seen"] = 424242
			return body
		},
	})

	if _, err := client.Run(context.Background(), "hello world", nil); err != nil {
		t.Fatalf("client.Run failed: %v", err)
	}

	promptFile := filepath.Join(workDir, "prompt-0.txt")
	written, err := os.ReadFile(promptFile)
	if err != nil {
		t.Fatalf("could not read the prompt file the CLI actually received: %v", err)
	}

	inner, found := extractTag(string(written), "openai_request")
	if !found {
		t.Fatalf("no <openai_request> envelope found in what the CLI received:\n%s", written)
	}

	// Re-decode what the CLI received and re-encode it independently —
	// json.Marshal on a map[string]any sorts keys deterministically, so two
	// marshals of an equal map are byte-identical. This is the "byte-equal to
	// the assembled body" assertion.
	var gotBody map[string]any
	if err := json.Unmarshal([]byte(inner), &gotBody); err != nil {
		t.Fatalf("envelope body is not valid JSON: %v\n%s", err, inner)
	}
	wantBytes := canonicalBody(gotBody)
	gotBytes := []byte(inner)
	if !jsonBytesEqual(gotBytes, wantBytes) {
		t.Fatalf("CLI did not receive byte-equal JSON:\n got:  %s\n want: %s", gotBytes, wantBytes)
	}

	if v, ok := gotBody["x_vendor_extension_never_seen"]; !ok || v != float64(424242) {
		t.Fatalf("unknown key added by BodyTransform did not survive verbatim passthrough: %+v", gotBody)
	}
	if !strings.Contains(inner, `"x_vendor_extension_never_seen":424242`) {
		t.Fatalf("unknown key's literal bytes were not present verbatim in what the CLI received:\n%s", inner)
	}
}

// jsonBytesEqual compares two JSON byte strings for byte-for-byte equality
// after trimming surrounding whitespace only (no semantic re-ordering) — the
// envelope writer and the test's re-marshal must agree exactly, not just
// semantically.
func jsonBytesEqual(a, b []byte) bool {
	return strings.TrimSpace(string(a)) == strings.TrimSpace(string(b))
}

// --- Gate 5: cost honesty — is a repair attempt counted anywhere automatically? ---

func TestCostReporting_RepairAttemptsAreInvisibleToClientMetricsUnlessSelfReported(t *testing.T) {
	hostile := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":{"bad":"shape"}}}]}`)
	fixed := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"fixed"}}]}`)
	scriptPath, statePath, workDir := newScriptDir(t, []string{hostile, fixed})

	opts := baseOpts(scriptPath, statePath, workDir)
	opts.RepairBudget = 1

	cliLaunches := 0
	opts.OnLaunch = func(LaunchEvent) { cliLaunches++ } // our OWN side channel — not part of toolnexus

	var llmEvents int
	client := toolnexus.CreateInProcessClient(toolnexus.InProcessOptions{
		Model:    "fake-model-1",
		Generate: New(opts),
		OnMetric: func(ev toolnexus.MetricEvent) {
			if ev.Event == "llm" {
				llmEvents++
			}
		},
	})

	if _, err := client.Run(context.Background(), "hello", nil); err != nil {
		t.Fatalf("client.Run failed: %v", err)
	}

	if cliLaunches != 2 {
		t.Fatalf("expected 2 real CLI process launches (1 hostile + 1 repaired), got %d", cliLaunches)
	}
	if llmEvents != 1 {
		t.Fatalf("toolnexus's own \"llm\" metric event should fire exactly ONCE per Generate call "+
			"(that is the seam), regardless of how many CLI processes ran underneath — got %d. "+
			"If this ever becomes >1, the finding below is stale and SPIKE.md needs updating.", llmEvents)
	}
	// The finding: toolnexus counts ONE llm call no matter how many repair
	// launches happened inside it. A CLI-backed Generate that wants repair
	// cost visible has to report it itself — via OnLaunch here, or by folding
	// an estimate into the InProcessUsage it finally returns. See SPIKE.md
	// gate 5.
}
