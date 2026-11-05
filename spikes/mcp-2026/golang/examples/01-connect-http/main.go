// Example 01 — connect to an MCP server over Streamable HTTP and call a tool.
//
// Run: go run ./examples/01-connect-http
//
// The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
// Every request carries its own protocol version, so "connecting" is just NewClient(url).
package main

import (
	"fmt"
	"log"
	"net/http/httptest"
	"strings"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	// A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
	// In real use this is someone else's server, e.g. "https://api.example.com/mcp".
	ts := httptest.NewServer(mcp.FixtureServer())
	defer ts.Close()

	// 1. Point a client at the server's MCP endpoint. No handshake happens here.
	c := mcp.NewClient(ts.URL + "/mcp")

	// 2. Optional: server/discover tells you which versions and capabilities the server has.
	info, err := c.Call("server/discover", nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("supported versions:", info["supportedVersions"])

	// 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
	tools, err := c.Tools()
	if err != nil {
		log.Fatal(err)
	}
	var names []string
	for _, t := range tools {
		names = append(names, t["name"].(string))
	}
	fmt.Println("tools:", strings.Join(names, ", "))

	// 4. Call a tool. mcp.Text returns the first text content of the result.
	res, err := c.CallTool("echo", mcp.Obj{"text": "hello over HTTP"}, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("echo:", mcp.Text(res))
}
