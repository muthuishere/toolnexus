// Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).
//
// Run: go run ./examples/03-tool-input
//
// In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can
// RETURN resultType "input_required" with a set of inputRequests (e.g. an elicitation form).
// The client answers them and re-sends the same call with inputResponses (+ the opaque
// requestState). CallTool runs those rounds for you: you only supply a Responder callback.
package main

import (
	"fmt"
	"log"
	"net/http/httptest"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	ts := httptest.NewServer(mcp.FixtureServer()) // `greet` asks for the user's name
	defer ts.Close()
	c := mcp.NewClient(ts.URL + "/mcp")

	// The Responder is called once per input request. key identifies the request inside the
	// round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
	// In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
	askUser := func(key string, req mcp.Obj) (mcp.Obj, error) {
		params, _ := req["params"].(map[string]any)
		fmt.Printf("server asks (%s): %v\n", req["method"], params["message"])
		// Answer like a person who filled the form. "decline" / "cancel" are the other actions.
		return mcp.Obj{"action": "accept", "content": mcp.Obj{"name": "Ada"}}, nil
	}

	res, err := c.CallTool("greet", mcp.Obj{}, askUser)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("result:", mcp.Text(res))
}
