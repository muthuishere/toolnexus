# MCP quickstart: zero to tool call

> **C# port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/csharp` (project `src/Mcp2026`, namespace `Mcp2026`). Every code block is copied verbatim from a
> program under `examples/` that `ExamplesTests` runs and checks; `DocsTests` fails if a block here stops matching
> its source. Behaviour and example output are identical to the Go reference spike.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client.

## Over HTTP

<!-- from: examples/01-connect-http/Program.cs -->
```csharp
// 1. Point a client at the server's MCP endpoint. No handshake happens here.
var c = new McpClient(ts.Url + "/mcp");

// 2. Optional: server/discover tells you which versions and capabilities the server has.
var info = await c.CallAsync("server/discover");
Console.WriteLine($"supported versions: [{string.Join(" ", J.Strings(info["supportedVersions"]))}]");

// 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
var tools = await c.ToolsAsync();
Console.WriteLine("tools: " + string.Join(", ", tools.Select(t => J.Str(t["name"]))));

// 4. Call a tool. Mcp.Text returns the first text content of the result.
var res = await c.CallToolAsync("echo", new JsonObject { ["text"] = "hello over HTTP" });
Console.WriteLine("echo: " + Mcp.Text(res));
```

Output (`dotnet run --project examples/01-connect-http`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. stderr is the server's log and is never parsed.
stdio has no HTTP headers and no OAuth: the server reads its credentials from its own environment.

<!-- from: examples/02-connect-stdio/Program.cs -->
```csharp
// 1. Describe the process to spawn (env, args, working dir: plain ProcessStartInfo).
var psi = new ProcessStartInfo(Environment.ProcessPath!, self);
psi.Environment["SERVE_STDIO"] = "1";

// 2. Start it and get a client. Same API as the HTTP client: CallAsync, ToolsAsync, CallToolAsync.
await using var c = McpClient.StartStdio(psi); // disposing stops the server process

// 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
var tools = await c.ToolsAsync();
Console.WriteLine("tool count: " + tools.Count);

var res = await c.CallToolAsync("echo", new JsonObject { ["text"] = "hello over stdio" });
Console.WriteLine("echo: " + Mcp.Text(res));
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `CallToolAsync` answers them with your `Responder` and re-sends
the call with `inputResponses` and the opaque `requestState`. You write only the callback:

<!-- from: examples/03-tool-input/Program.cs -->
```csharp
// The Responder is called once per input request. key identifies the request inside the
// round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
// In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
Responder askUser = (key, req) =>
{
    Console.WriteLine($"server asks ({req["method"]}): {req["params"]?["message"]}");
    // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
    return Task.FromResult(new JsonObject { ["action"] = "accept", ["content"] = new JsonObject { ["name"] = "Ada" } });
};

var res = await c.CallToolAsync("greet", [], askUser);
Console.WriteLine("result: " + Mcp.Text(res));
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
