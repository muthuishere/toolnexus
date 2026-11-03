package mcp2026

// The test binary doubles as a tiny stdio MCP server (TEST ONLY — the product server is HTTP only):
// MCP2026_STDIO_SERVER=1 makes TestMain serve newline-delimited JSON-RPC on stdin/stdout.

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
)

func TestMain(m *testing.M) {
	switch os.Getenv("MCP2026_STDIO_SERVER") {
	case "1":
		fmt.Fprintln(os.Stderr, `stdio-test-server ready {"jsonrpc":"2.0","id":1,"result":{}}`) // log noise, must not be parsed
		ServeStdio(mustServer(), os.Stdin, os.Stdout)
		os.Exit(0)
	case "exit":
		os.Exit(3)
	}
	os.Exit(m.Run())
}

func stdioClient(t *testing.T, mode string, stderr io.Writer) *Client {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=^$")
	cmd.Env = append(os.Environ(), "MCP2026_STDIO_SERVER="+mode)
	cmd.Stderr = stderr
	c, err := NewStdioClient(cmd)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func TestStdioSameEightSteps(t *testing.T) {
	if !runChecks("", func(c *Client) { *c = *stdioClient(t, "1", io.Discard) }) {
		t.Fatal("stdio checks failed")
	}
}

type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) { s.mu.Lock(); defer s.mu.Unlock(); return s.b.Write(p) }
func (s *syncBuf) String() string              { s.mu.Lock(); defer s.mu.Unlock(); return s.b.String() }

func TestStdioStderrCapturedNotParsedAndConcurrent(t *testing.T) {
	var errBuf syncBuf
	c := stdioClient(t, "1", &errBuf)
	var wg sync.WaitGroup
	for i := range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			want := fmt.Sprint("msg-", i)
			if r, err := c.CallTool("echo", obj{"text": want}, nil); err != nil || Text(r) != want {
				t.Errorf("%v %v", r, err)
			}
		}()
	}
	wg.Wait()
	c.Close()
	if !strings.Contains(errBuf.String(), "stdio-test-server ready") {
		t.Fatalf("stderr not captured: %q", errBuf.String())
	}
}

func TestStdioServerExitIsAnError(t *testing.T) {
	c := stdioClient(t, "exit", io.Discard)
	if _, err := c.Call("tools/list", nil); err == nil {
		t.Fatal("want error from an exited server")
	}
}
