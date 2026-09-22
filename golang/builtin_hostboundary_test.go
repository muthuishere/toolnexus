package toolnexus

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// The host boundary (ADR 0034): which interpreter runs, what a relative path
// means, and what a timeout kills. Each test carries its control, because the
// spike that produced these fixes twice reported a clean result from a broken
// probe (spikes/builtin-host-boundary/SPIKE.md §1).

func builtinNamed(t *testing.T, cfg any, name string) Tool {
	t.Helper()
	tools, err := SelectBuiltinsChecked(cfg)
	if err != nil {
		t.Fatalf("SelectBuiltinsChecked: %v", err)
	}
	for _, tool := range tools {
		if tool.Name == name {
			return tool
		}
	}
	t.Fatalf("builtin %q not found", name)
	return Tool{}
}

func runTool(t *testing.T, tool Tool, args map[string]any) ToolResult {
	t.Helper()
	res, err := tool.Execute(args, &ToolContext{Ctx: context.Background()})
	if err != nil {
		t.Fatalf("%s: unexpected go error: %v", tool.Name, err)
	}
	return res
}

// ---------------------------------------------------------------------------
// #102 — a timeout kills the job, not the shell
// ---------------------------------------------------------------------------

// TestBashTimeoutKillsTheWholeJob is the spike's assertion, as a test. The
// command's GRANDCHILD writes the marker: `sleep 0.2` in front stops the shell
// exec-optimising the single command away, which is the difference between
// measuring an orphan and measuring nothing.
func TestBashTimeoutKillsTheWholeJob(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX command shape; the Windows arm is measured in spikes/builtin-host-boundary/win/orphan")
	}
	dir := t.TempDir()
	marker := filepath.Join(dir, "orphan.marker")
	command := "sleep 0.2; sh -c 'sleep 1; touch " + marker + "'"

	bash := builtinNamed(t, nil, "bash")
	res := runTool(t, bash, map[string]any{"command": command, "timeout": 300})

	if !res.IsError {
		t.Fatalf("a timed-out command must be an error result; got %q", res.Output)
	}
	if !strings.Contains(res.Output, "timed out") {
		t.Fatalf("output should name the timeout; got %q", res.Output)
	}
	if res.Metadata["timedOut"] != true {
		t.Fatalf("metadata.timedOut should be true; got %v", res.Metadata["timedOut"])
	}
	if res.Metadata["killedTree"] != true {
		t.Fatalf("metadata.killedTree should be true; got %v", res.Metadata["killedTree"])
	}

	time.Sleep(2 * time.Second)
	if _, err := os.Stat(marker); err == nil {
		t.Fatalf("the grandchild outlived the kill: %s exists", marker)
	}
}

// TestBashTimeoutProbeCanActuallyWriteTheMarker is the control for the test
// above. Without it, a passing "no orphan" assertion could just mean the
// command never worked.
func TestBashTimeoutProbeCanActuallyWriteTheMarker(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX command shape")
	}
	dir := t.TempDir()
	marker := filepath.Join(dir, "control.marker")
	command := "sleep 0.2; sh -c 'sleep 1; touch " + marker + "'"

	bash := builtinNamed(t, nil, "bash")
	res := runTool(t, bash, map[string]any{"command": command, "timeout": 20000})
	if res.IsError {
		t.Fatalf("control run should succeed; got %q", res.Output)
	}
	if _, err := os.Stat(marker); err != nil {
		t.Fatalf("control: the command cannot write the marker at all (%v) — the orphan test proves nothing", err)
	}
}

// TestBashCancellationStopsTheWork — cancelling the surrounding context is the
// other half of #102: a run that is cancelled must not leave the machine loaded
// with what it started.
func TestBashCancellationStopsTheWork(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX command shape")
	}
	dir := t.TempDir()
	marker := filepath.Join(dir, "cancelled.marker")
	command := "sleep 0.2; sh -c 'sleep 1; touch " + marker + "'"

	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(300 * time.Millisecond); cancel() }()

	bash := builtinNamed(t, nil, "bash")
	res, err := bash.Execute(map[string]any{"command": command, "timeout": 60000}, &ToolContext{Ctx: ctx})
	if err != nil {
		t.Fatalf("unexpected go error: %v", err)
	}
	if !res.IsError || !strings.Contains(res.Output, "cancelled") {
		t.Fatalf("a cancelled command should report cancellation; got isError=%v %q", res.IsError, res.Output)
	}
	if res.Metadata["timedOut"] != false {
		t.Fatalf("a cancellation is not a timeout; metadata.timedOut = %v", res.Metadata["timedOut"])
	}
	time.Sleep(2 * time.Second)
	if _, statErr := os.Stat(marker); statErr == nil {
		t.Fatalf("cancellation left the job running: %s exists", marker)
	}
}

// ---------------------------------------------------------------------------
// #100 — the interpreter is chosen, and reported
// ---------------------------------------------------------------------------

func TestBashReportsTheResolvedInterpreter(t *testing.T) {
	bash := builtinNamed(t, nil, "bash")
	res := runTool(t, bash, map[string]any{"command": "echo hi"})
	shell, _ := res.Metadata["shell"].(string)
	if shell == "" {
		t.Fatalf("metadata.shell must name the interpreter that ran; got %#v", res.Metadata)
	}
	if runtime.GOOS != "windows" && shell != "/bin/sh -c" {
		t.Fatalf("POSIX default should be `/bin/sh -c`; got %q", shell)
	}
}

func TestHostSuppliedShellIsUsedVerbatim(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX interpreter")
	}
	cfg := BuiltinsConfig{Shell: []string{"sh", "-c"}}
	bash := builtinNamed(t, cfg, "bash")
	res := runTool(t, bash, map[string]any{"command": "echo verbatim"})
	if res.IsError || !strings.Contains(res.Output, "verbatim") {
		t.Fatalf("host-supplied shell should run the command; got isError=%v %q", res.IsError, res.Output)
	}
	if res.Metadata["shell"] != "sh -c" {
		t.Fatalf("metadata.shell should echo the host's argv; got %v", res.Metadata["shell"])
	}
}

// A shell that does not exist is a CONSTRUCTION failure when bash is enabled,
// and a non-event when it is not — the supported way to run on a box with no
// interpreter.
func TestMissingInterpreterFailsConstructionUnlessBashIsOff(t *testing.T) {
	// A detection that found nothing is carried on the env and surfaces as an
	// error rather than as a per-call "sh: not found" on turn fourteen.
	env := &builtinEnv{shellErr: errors.New("no shell interpreter found (tried: sh)")}
	if _, err := env.shellArgv(); err == nil {
		t.Fatalf("a failed detection must surface as an error")
	}

	off := BuiltinsConfig{Tools: map[string]bool{"bash": false}}
	tools, err := SelectBuiltinsChecked(off)
	if err != nil {
		t.Fatalf("with bash disabled, construction must succeed: %v", err)
	}
	for _, tool := range tools {
		if tool.Name == "bash" {
			t.Fatalf("bash should be absent")
		}
	}
}

// ---------------------------------------------------------------------------
// #101 — one base directory, and optional confinement
// ---------------------------------------------------------------------------

func TestBaseDirScopesRelativePaths(t *testing.T) {
	base := t.TempDir()
	cfg := BuiltinsConfig{BaseDir: base}

	write := builtinNamed(t, cfg, "write")
	res := runTool(t, write, map[string]any{"path": "sub/nested.txt", "content": "landed"})
	if res.IsError {
		t.Fatalf("write failed: %q", res.Output)
	}
	if _, err := os.Stat(filepath.Join(base, "sub", "nested.txt")); err != nil {
		t.Fatalf("relative write did not land under baseDir: %v", err)
	}
	cwd, _ := os.Getwd()
	if _, err := os.Stat(filepath.Join(cwd, "sub", "nested.txt")); err == nil {
		t.Fatalf("relative write leaked into the process cwd — the #101 incident")
	}

	read := builtinNamed(t, cfg, "read")
	if res := runTool(t, read, map[string]any{"path": "sub/nested.txt"}); res.IsError || res.Output != "landed" {
		t.Fatalf("read through baseDir: isError=%v %q", res.IsError, res.Output)
	}
}

func TestEmptyBaseDirIsTodaysBehaviour(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "absolute.txt")
	write := builtinNamed(t, BuiltinsConfig{}, "write")
	if res := runTool(t, write, map[string]any{"path": target, "content": "x"}); res.IsError {
		t.Fatalf("absolute path with no baseDir must behave as before: %q", res.Output)
	}
	if _, err := os.Stat(target); err != nil {
		t.Fatalf("absolute write did not land: %v", err)
	}
}

func TestApplyPatchResolvesPathsInsideThePatchText(t *testing.T) {
	base := t.TempDir()
	cfg := BuiltinsConfig{BaseDir: base}
	patch := "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch"

	ap := builtinNamed(t, cfg, "apply_patch")
	if res := runTool(t, ap, map[string]any{"patchText": patch}); res.IsError {
		t.Fatalf("apply_patch failed: %q", res.Output)
	}
	if _, err := os.Stat(filepath.Join(base, "pkg", "new.txt")); err != nil {
		t.Fatalf("apply_patch wrote outside baseDir — the path inside the patch text was not resolved: %v", err)
	}
}

func TestBashDefaultsItsWorkdirToBaseDir(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX command shape")
	}
	base := t.TempDir()
	if err := os.WriteFile(filepath.Join(base, "marker.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	bash := builtinNamed(t, BuiltinsConfig{BaseDir: base}, "bash")
	res := runTool(t, bash, map[string]any{"command": "ls marker.txt"})
	if res.IsError || !strings.Contains(res.Output, "marker.txt") {
		t.Fatalf("bash should default its workdir to baseDir; got isError=%v %q", res.IsError, res.Output)
	}
}

func TestConfinementRefusesEscapes(t *testing.T) {
	base := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := BuiltinsConfig{BaseDir: base, ConfineToBaseDir: true}
	read := builtinNamed(t, cfg, "read")

	for _, p := range []string{"../escape.txt", filepath.Join(outside, "secret.txt")} {
		res := runTool(t, read, map[string]any{"path": p})
		if !res.IsError || !strings.Contains(res.Output, "outside baseDir") {
			t.Fatalf("%s should be refused; got isError=%v %q", p, res.IsError, res.Output)
		}
	}

	// Control: a path INSIDE the base is still served, so the refusals above are
	// the confinement working and not the tool being broken.
	if err := os.WriteFile(filepath.Join(base, "ok.txt"), []byte("fine"), 0o644); err != nil {
		t.Fatal(err)
	}
	if res := runTool(t, read, map[string]any{"path": "ok.txt"}); res.IsError {
		t.Fatalf("a path inside baseDir must still be read: %q", res.Output)
	}
}

// A symlink is the escape a lexical check misses: the path is inside, the file
// is not. (On Windows the equivalent is a directory junction, which needs no
// privilege — measured in the spike, unverified in CI because we have no
// Windows runner.)
func TestConfinementFollowsSymlinksBeforeDeciding(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink creation needs privilege on Windows; junctions are covered in the spike")
	}
	base := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(base, "link")); err != nil {
		t.Fatal(err)
	}
	read := builtinNamed(t, BuiltinsConfig{BaseDir: base, ConfineToBaseDir: true}, "read")
	res := runTool(t, read, map[string]any{"path": "link/secret.txt"})
	if !res.IsError {
		t.Fatalf("a symlink out of baseDir must be refused; got %q", res.Output)
	}
}

// Confinement has to hold for a file that does not exist yet, or it is not a
// check for `write` at all.
func TestConfinementCoversPathsThatDoNotExistYet(t *testing.T) {
	base := t.TempDir()
	write := builtinNamed(t, BuiltinsConfig{BaseDir: base, ConfineToBaseDir: true}, "write")

	if res := runTool(t, write, map[string]any{"path": "../escape.txt", "content": "x"}); !res.IsError {
		t.Fatalf("a write to a non-existent path outside baseDir must be refused; got %q", res.Output)
	}
	if res := runTool(t, write, map[string]any{"path": "deep/new/file.txt", "content": "x"}); res.IsError {
		t.Fatalf("a write to a new path INSIDE baseDir must succeed; got %q", res.Output)
	}
}

// ---------------------------------------------------------------------------
// §4A — a grep match line carries the path it was sorted by
// ---------------------------------------------------------------------------

func TestGrepEmitsWalkRootRelativeForwardSlashedPaths(t *testing.T) {
	base := t.TempDir()
	if err := os.MkdirAll(filepath.Join(base, "tree", "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(base, "tree", "sub", "a.txt"), []byte("needle\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	grep := builtinNamed(t, BuiltinsConfig{BaseDir: base}, "grep")
	res := runTool(t, grep, map[string]any{"pattern": "needle", "path": "tree"})
	if res.IsError {
		t.Fatalf("grep failed: %q", res.Output)
	}
	want := "sub/a.txt:1:needle"
	if res.Output != want {
		t.Fatalf("grep must emit the walk-root-relative, /-separated path it sorts by.\n got: %q\nwant: %q", res.Output, want)
	}
	if strings.Contains(res.Output, base) {
		t.Fatalf("grep leaked the absolute path into the match line: %q", res.Output)
	}
	if strings.Contains(res.Output, "\\") {
		t.Fatalf("grep emitted a platform separator; §4A requires `/` everywhere: %q", res.Output)
	}
}
