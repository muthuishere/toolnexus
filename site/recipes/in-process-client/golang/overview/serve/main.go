package main

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	tn "github.com/muthuishere/toolnexus/golang"
)

type addArgs struct {
	A float64 `json:"a"`
	B float64 `json:"b"`
}

func main() {
	ctx := context.Background()
	add := tn.NativeToolReflect("add", "Add two numbers.", func(_ context.Context, in addArgs) (string, error) {
		return strconv.FormatFloat(in.A+in.B, 'f', -1, 64), nil
	})
	// Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
	toolkit, err := tn.CreateToolkit(ctx, tn.Options{Builtins: false, ExtraTools: []tn.Tool{add}})
	if err != nil {
		panic(err)
	}
	server, err := toolkit.AsMCPServer(nil) // &tn.MCPServeConfig{Tools: []string{...}} narrows what is exposed
	if err != nil {
		panic(err)
	}
	defer server.Close()
	fmt.Println("MCP endpoint:", server.URL) // http://127.0.0.1:<port>/mcp, POST only

	// Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
	entry := map[string]any{"type": "http", "name": "toolnexus", "url": server.URL,
		"headers": []map[string]any{{"name": "Authorization", "value": "Bearer " + server.Token}}}
	req, _ := http.NewRequest("POST", entry["url"].(string),
		strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("Authorization", "Bearer "+server.Token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		panic(err)
	}
	res.Body.Close()
	fmt.Println("tools/list:", res.StatusCode)
}
