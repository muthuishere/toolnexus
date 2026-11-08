package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMediaCmdMockPrintsPath(t *testing.T) {
	dir := t.TempDir()
	var out, errb bytes.Buffer
	code := mediaCmd([]string{"image", "--style", "grok-cli-mock", "--prompt", "a red apple", "--aspect-ratio", "16:9", "--out", dir}, &out, &errb)
	if code != 0 {
		t.Fatalf("exit %d: %s", code, errb.String())
	}
	p := strings.TrimSpace(out.String())
	if filepath.Dir(p) != dir || filepath.Ext(p) != ".png" {
		t.Fatalf("path = %q, want a .png in %s", p, dir)
	}
	if b, _ := os.ReadFile(p); string(b) != "toolnexus mock image" {
		t.Fatalf("file = %q", b)
	}
}

func TestMediaCmdUnsupportedVerbFails(t *testing.T) {
	var out, errb bytes.Buffer
	code := mediaCmd([]string{"video", "--style", "codex-cli", "--prompt", "x"}, &out, &errb)
	if code == 0 || !strings.Contains(errb.String(), `media: no style supports "video"`) {
		t.Fatalf("exit %d, stderr %q", code, errb.String())
	}
}

func TestMediaCmdFakeCLI(t *testing.T) {
	// The real grok-cli code path against the shared fake CLI (no network).
	fake, _ := filepath.Abs("../../../conformance/media/fake-cli/fake-cli.sh")
	fixture := filepath.Join(t.TempDir(), "f.jsonl")
	fo := t.TempDir()
	lines := `#file s/1.png PNGBYTES
{"type":"tool_call_update","status":"completed","rawOutput":{"path":"{out}/s/1.png"}}
{"type":"end","stopReason":"end_turn","sessionId":"s1"}
`
	os.WriteFile(fixture, []byte(lines), 0o644)
	bin := filepath.Join(t.TempDir(), "grok")
	os.WriteFile(bin, []byte("#!/bin/sh\nexec /bin/sh "+fake+" "+fixture+" "+fo+" \"$@\"\n"), 0o755)
	t.Setenv("PATH", filepath.Dir(bin)+string(os.PathListSeparator)+os.Getenv("PATH"))
	dir := t.TempDir()
	var out, errb bytes.Buffer
	if code := mediaCmd([]string{"image", "--style", "grok-cli", "--prompt", "cat", "--width", "64", "--out", dir}, &out, &errb); code != 0 {
		t.Fatalf("exit %d: %s", code, errb.String())
	}
	if p := strings.TrimSpace(out.String()); filepath.Dir(p) != dir {
		t.Fatalf("path = %q", p)
	}
	argv, _ := os.ReadFile(filepath.Join(fo, "argv.txt"))
	if !strings.Contains(string(argv), "cat (width 64 px)") {
		t.Fatalf("argv = %s", argv)
	}
}
