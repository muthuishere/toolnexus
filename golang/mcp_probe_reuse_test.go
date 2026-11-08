package toolnexus

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// §2.2a: a stdio discover REPLY reuses the probe child (1 spawn); discover
// silence kills it and the frozen path respawns (2 spawns). Shared fixture.
func TestStdioDiscoverProbeReuse(t *testing.T) {
	if _, err := exec.LookPath("node"); err != nil {
		t.Skip("node not installed")
	}
	server, err := filepath.Abs("../examples/mcp-2026/legacy-stdio/server.mjs")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		mode  string
		spawn int
	}{{"error", 1}, {"silent", 2}} {
		t.Run(tc.mode, func(t *testing.T) {
			countFile := filepath.Join(t.TempDir(), "spawns.txt")
			cfg := McpConfig{"legacy": {
				Command: []string{"node", server},
				Env:     map[string]string{"SPAWN_COUNT_FILE": countFile, "DISCOVER_MODE": tc.mode},
				Timeout: 1500,
			}}
			src, err := LoadMcpWithContext(context.Background(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer src.Close()
			var echo *Tool
			for i := range src.Tools {
				if src.Tools[i].Name == "legacy_echo" {
					echo = &src.Tools[i]
				}
			}
			if echo == nil {
				t.Fatalf("legacy_echo missing; tools=%v", src.Tools)
			}
			res, err := echo.Execute(map[string]any{"text": "hi"}, &ToolContext{})
			if err != nil || res.Output != "echo:hi" {
				t.Fatalf("echo = %q, %v", res.Output, err)
			}
			b, _ := os.ReadFile(countFile)
			lines := strings.Split(strings.TrimSpace(string(b)), "\n")
			if len(lines) != tc.spawn {
				t.Fatalf("spawns = %d (%q), want %d", len(lines), b, tc.spawn)
			}
		})
	}
}
