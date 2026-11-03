# MCP quickstart: zero to tool call

> **Elixir port of the Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API
> below is `spikes/mcp-2026/elixir` (modules `Mcp2026.*`). Every code block is copied verbatim from a script under
> `examples/` that `test/examples_test.exs` runs and checks; `test/docs_test.exs` fails if a block here stops
> matching its source.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client.

## Over HTTP

<!-- from: examples/01-connect-http.exs -->
```elixir
# 1. Point a client at the server's MCP endpoint. No handshake happens here.
c = Client.new(srv.url)

# 2. Optional: server/discover tells you which versions and capabilities the server has.
{:ok, info} = Client.call(c, "server/discover")
IO.puts("supported versions: [#{Enum.join(info["supportedVersions"], " ")}]")

# 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
{:ok, tools} = Client.tools(c)
IO.puts("tools: " <> Enum.map_join(tools, ", ", & &1["name"]))

# 4. Call a tool. Mcp2026.text/1 returns the first text content of the result.
{:ok, res} = Client.call_tool(c, "echo", %{"text" => "hello over HTTP"})
IO.puts("echo: " <> Mcp2026.text(res))
```

Output (`mix run examples/01-connect-http.exs`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

Every call returns `{:ok, result}` or `{:error, reason}`; a JSON-RPC error is `%Mcp2026.RpcError{code, message, data}`.

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. The server's stderr is inherited (its log) and
never parsed. stdio has no HTTP headers and no OAuth: the server reads its credentials from its own environment.

<!-- from: examples/02-connect-stdio.exs -->
```elixir
# 1. Describe the process to spawn: command, args, env.
args = ["run", "--no-compile", "--no-deps-check", __ENV__.file]

# 2. Start it and get a client. Same API as the HTTP client: call, tools, call_tool.
{:ok, c} = Stdio.start("mix", args, env: [{"SERVE_STDIO", "1"}])

# 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
{:ok, tools} = Client.tools(c)
IO.puts("tool count: #{length(tools)}")

{:ok, res} = Client.call_tool(c, "echo", %{"text" => "hello over stdio"})
IO.puts("echo: " <> Mcp2026.text(res))

Client.close(c)  # stops the server process
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `Client.call_tool/4` answers them with your responder and
re-sends the call with `inputResponses` and the opaque `requestState`. You write only the function:

<!-- from: examples/03-tool-input.exs -->
```elixir
# The responder is called once per input request. key identifies the request inside the
# round; req is the request itself (%{"method" => "elicitation/create", "params" => ...}).
# In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
ask_user = fn _key, req ->
  IO.puts("server asks (#{req["method"]}): #{req["params"]["message"]}")
  # Answer like a person who filled the form. "decline" / "cancel" are the other actions.
  %{"action" => "accept", "content" => %{"name" => "Ada"}}
end

{:ok, res} = Client.call_tool(c, "greet", %{}, ask_user)
IO.puts("result: " <> Mcp2026.text(res))
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
