# MCP quickstart: zero to tool call

> **Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/golang` (import path `toolnexus/spikes/mcp2026`). Every code block is copied
> verbatim from a program under `examples/` that `examples_test.go` runs and checks; `docs_test.go` fails if a
> block here stops matching its source.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client.

## Over HTTP

<!-- from: examples/01-connect-http/main.go -->
```go
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
```

Output (`go run ./examples/01-connect-http`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. stderr is the server's log and is never parsed.
stdio has no HTTP headers and no OAuth: the server reads its credentials from its own environment.

<!-- from: examples/02-connect-stdio/main.go -->
```go
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
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `CallTool` answers them with your `Responder` and re-sends
the call with `inputResponses` and the opaque `requestState`. You write only the callback:

<!-- from: examples/03-tool-input/main.go -->
```go
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
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
