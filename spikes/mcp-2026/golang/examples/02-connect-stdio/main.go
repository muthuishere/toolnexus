// Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).
//
// Run: go run ./examples/02-connect-stdio
//
// stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is passed
// through (or captured via cmd.Stderr) and never parsed. There are no HTTP headers and no OAuth
// over stdio: a stdio server takes its credentials from its own environment.
package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	// Demo harness: this same binary is the stdio server when started with SERVE_STDIO=1.
	// In real use the command is your server, e.g. exec.Command("npx", "-y", "some-mcp-server").
	if os.Getenv("SERVE_STDIO") == "1" {
		mcp.ServeStdio(mcp.FixtureServer(), os.Stdin, os.Stdout)
		return
	}
	self, err := os.Executable()
	if err != nil {
		log.Fatal(err)
	}

	// 1. Describe the process to spawn (env, args, working dir: plain os/exec).
	cmd := exec.Command(self)
	cmd.Env = append(os.Environ(), "SERVE_STDIO=1")

	// 2. Start it and get a client. Same API as the HTTP client: Call, Tools, CallTool.
	c, err := mcp.NewStdioClient(cmd)
	if err != nil {
		log.Fatal(err)
	}
	defer c.Close() // stops the server process

	// 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
	tools, err := c.Tools()
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("tool count:", len(tools))

	res, err := c.CallTool("echo", mcp.Obj{"text": "hello over stdio"}, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("echo:", mcp.Text(res))
}
