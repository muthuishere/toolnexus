package climodel

import (
	"os/exec"
	"strings"
	"testing"

	toolnexus "github.com/muthuishere/toolnexus/golang"
)

// --- Gate 4: prompt-file vs argv — the argv path actually fails on a large
// prompt, on THIS machine, which is the justification for the file channel.

// bigBody returns an InProcessRequest whose assembled body is roughly n
// bytes of JSON — a stand-in for a large real prompt (a long file pasted into
// context, a big skill, a long transcript).
func bigBody(n int) map[string]any {
	return map[string]any{
		"model": "fake-model-1",
		"messages": []any{
			map[string]any{"role": "user", "content": strings.Repeat("x", n)},
		},
	}
}

func tryArgv(t *testing.T, scriptPath, statePath, workDir string, size int) error {
	t.Helper()
	opts := Options{
		Command:         fakecliPath,
		ArgvTemplate:    []string{"-prompt", "{{prompt}}", "-script", scriptPath, "-state", statePath},
		PromptChannel:   PromptChannelArgv,
		ResponseChannel: ResponseChannelStdout,
		Model:           "fake-model-1",
		TempDir:         workDir,
	}
	req := toolnexus.InProcessRequest{Body: bigBody(size)}
	_, err := generate(opts, req)
	return err
}

func TestArgvChannel_FailsOnALargePrompt_FileChannelDoesNot(t *testing.T) {
	ok := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}`)

	// A small prompt over argv works fine.
	{
		scriptPath, statePath, workDir := newScriptDir(t, []string{ok})
		if err := tryArgv(t, scriptPath, statePath, workDir, 1024); err != nil {
			t.Fatalf("a small (1KB) prompt over argv should succeed, got: %v", err)
		}
	}

	// A large prompt over argv fails — this machine's real argv/environment
	// limit, not a guess. See TestMeasureArgMax below for the measured
	// boundary and `getconf ARG_MAX`.
	{
		scriptPath, statePath, workDir := newScriptDir(t, []string{ok})
		err := tryArgv(t, scriptPath, statePath, workDir, 8<<20) // 8 MiB
		if err == nil {
			t.Fatalf("expected an 8MiB prompt over argv to fail on this machine, but it succeeded")
		}
		if !strings.Contains(err.Error(), "argument list too long") {
			t.Logf("argv failure did not use the expected E2BIG wording (still a real failure): %v", err)
		} else {
			t.Logf("confirmed E2BIG on argv channel: %v", err)
		}
	}

	// The SAME oversized prompt over the file channel succeeds — this is the
	// ADR's justification for preferring the file channel.
	{
		scriptPath, statePath, workDir := newScriptDir(t, []string{ok})
		opts := baseOpts(scriptPath, statePath, workDir)
		req := toolnexus.InProcessRequest{Body: bigBody(8 << 20)}
		if _, err := generate(opts, req); err != nil {
			t.Fatalf("the SAME 8MiB prompt over the FILE channel should succeed, got: %v", err)
		}
	}
}

// TestMeasureArgMax binary-searches this machine's real practical argv
// ceiling for a single exec() with a normal inherited environment (a
// developer shell's env, not a stripped one — the realistic case), and
// reports it next to `getconf ARG_MAX`. Its own pass/fail is loose (it can
// only assert the search converged); the interesting output is in -v logs,
// which SPIKE.md quotes verbatim.
func TestMeasureArgMax(t *testing.T) {
	if out, err := exec.Command("getconf", "ARG_MAX").CombinedOutput(); err == nil {
		t.Logf("getconf ARG_MAX = %s", strings.TrimSpace(string(out)))
	} else {
		t.Logf("getconf ARG_MAX: unavailable (%v)", err)
	}

	ok := wrap(`{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}`)

	fits := func(size int) bool {
		scriptPath, statePath, workDir := newScriptDir(t, []string{ok})
		return tryArgv(t, scriptPath, statePath, workDir, size) == nil
	}

	low, high := 1<<10, 1<<23 // 1 KiB .. 8 MiB
	if fits(high) {
		t.Fatalf("8MiB unexpectedly fit on argv — this machine's limit is higher than assumed, widen the search")
	}
	if !fits(low) {
		t.Fatalf("1KiB unexpectedly did NOT fit on argv — something else is wrong")
	}
	for high-low > 4096 { // converge to within 4KB
		mid := (low + high) / 2
		if fits(mid) {
			low = mid
		} else {
			high = mid
		}
	}
	t.Logf("measured argv ceiling on this machine: fits at %d bytes of message content, fails by %d bytes "+
		"(total argv includes the envelope wrapper + inherited environment, not just this payload)", low, high)
}
