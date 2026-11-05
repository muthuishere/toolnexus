// Example 05 — lazy tool search: give the model 2 tools instead of 100.
//
// Run: go run ./examples/05-tool-search
//
// With c.ToolSearch = true, c.Tools() returns two meta-tools, search_tools(query, limit?) and
// call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then
// calls what it found. c.Invoke runs whatever tool call the model emits.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http/httptest"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	// A server with 100 tools: 10 services x 10 verbs.
	srv := mcp.NewServer()
	for _, svc := range []string{"github", "slack", "jira", "gmail", "calendar", "drive", "stripe", "postgres", "s3", "notion"} {
		for _, verb := range []string{"create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"} {
			name := svc + "_" + verb
			srv.Tool(name, fmt.Sprintf("%s an item in %s.", verb, svc), func(_ context.Context, args mcp.Obj) (any, error) {
				return name + " ran with " + fmt.Sprint(args["text"]), nil
			})
		}
	}
	ts := httptest.NewServer(srv)
	defer ts.Close()

	eager := mcp.NewClient(ts.URL + "/mcp")
	all, _ := eager.Tools()

	// 1. Turn on tool search; Description tells the model what this server is for.
	c := mcp.NewClient(ts.URL + "/mcp")
	c.ToolSearch = true
	c.Description = "SaaS workspace (GitHub, Slack, Jira, ...)"
	meta, err := c.Tools()
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("eager: %d tools, %d bytes of schema\n", len(all), size(all))
	fmt.Printf("lazy:  %d tools, %d bytes of schema\n", len(meta), size(meta))

	// 2. The model calls search_tools ...
	hits, err := c.SearchTools("send a slack message create", 3)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("top hit:", hits[0]["name"])

	// 3. ... then call_tool with the name it found. Invoke routes both meta-tools.
	res, err := c.Invoke("call_tool", mcp.Obj{"name": hits[0]["name"], "arguments": mcp.Obj{"text": "hi team"}}, nil)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("result:", mcp.Text(res))
}

func size(v any) int { b, _ := json.Marshal(v); return len(b) }
