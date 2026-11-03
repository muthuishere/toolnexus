package agents

import (
	"testing"

	tn "github.com/muthuishere/toolnexus/golang"
)

// Regression: RunResult.Files accumulates across completion-gate retries exactly
// like ToolCalls. An image made in attempt 1 must survive a retry whose own run
// produced no files.
func TestRunGatedAccumulatesFilesAcrossRetries(t *testing.T) {
	img := tn.RunFile{Type: "image", MimeType: "image/png", URL: "https://x/1.png", From: "generate_image", CallID: "c1"}
	attempts := []tn.RunResult{
		{Status: "done", ToolCalls: []tn.ToolCall{{Name: "generate_image"}}, Files: []tn.RunFile{img}},
		{Status: "done", ToolCalls: []tn.ToolCall{{Name: "noop"}}, Files: []tn.RunFile{}},
	}
	n := 0
	ask := func(string) (tn.RunResult, error) { r := attempts[n]; n++; return r, nil }
	verify := func(r tn.RunResult) (bool, string) { return len(r.ToolCalls) >= 2, "not yet" }
	out, err := runGated(ask, "go", &Completion{Verify: verify, MaxAttempts: 3})
	if err != nil {
		t.Fatal(err)
	}
	if len(out.ToolCalls) != 2 {
		t.Fatalf("toolCalls=%d", len(out.ToolCalls))
	}
	if len(out.Files) != 1 || out.Files[0].URL != img.URL {
		t.Fatalf("files lost across the gate retry: %+v", out.Files)
	}
}
