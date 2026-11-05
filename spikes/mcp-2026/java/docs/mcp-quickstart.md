# MCP quickstart: zero to tool call

> **Java port of the spike — the Go spike is the reference (openspec `upgrade-mcp-2026-07-28`).** The API below is
> `spikes/mcp-2026/java` (package `io.github.muthuishere.toolnexus.mcp2026`). Every code block is copied verbatim
> from a program under `examples/` that `ExamplesTest` runs and checks; `DocsTest` fails if a block here stops
> matching its source.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client.

## Over HTTP

<!-- from: examples/01-connect-http/Main.java -->
```java
            // 1. Point a client at the server's MCP endpoint. No handshake happens here.
            Client c = new Client(ts.url() + "/mcp");

            // 2. Optional: server/discover tells you which versions and capabilities the server has.
            Map<String, Object> info = c.call("server/discover", null);
            System.out.println("supported versions: " + info.get("supportedVersions"));

            // 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
            List<Map<String, Object>> tools = c.tools();
            List<String> names = tools.stream().map(t -> (String) t.get("name")).toList();
            System.out.println("tools: " + String.join(", ", names));

            // 4. Call a tool. Mcp.text returns the first text content of the result.
            Map<String, Object> res = c.callTool("echo", Mcp.obj("text", "hello over HTTP"), null);
            System.out.println("echo: " + Mcp.text(res));
```

Output (`./gradlew -q runExample -Pex=01`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. stderr is the server's log and is never parsed
(inherited, or copied to the `OutputStream` you pass to `Client.stdio(pb, stderr)`). stdio has no HTTP headers
and no OAuth: the server reads its credentials from its own environment.

<!-- from: examples/02-connect-stdio/Main.java -->
```java
        // 1. Describe the process to spawn (env, args, working dir: plain ProcessBuilder).
        ProcessBuilder cmd = new ProcessBuilder(java, "-cp", self, "ex02.Main");
        cmd.environment().put("SERVE_STDIO", "1");

        // 2. Start it and get a client. Same API as the HTTP client: call, tools, callTool.
        try (Client c = Client.stdio(cmd)) { // close() stops the server process

            // 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
            List<Map<String, Object>> tools = c.tools();
            System.out.println("tool count: " + tools.size());

            Map<String, Object> res = c.callTool("echo", Mcp.obj("text", "hello over stdio"), null);
            System.out.println("echo: " + Mcp.text(res));
```

```text
tool count: 2
echo: hello over stdio
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `callTool` answers them with your `Client.Responder` and
re-sends the call with `inputResponses` and the opaque `requestState`. You write only the callback:

<!-- from: examples/03-tool-input/Main.java -->
```java
            // The Responder is called once per input request. key identifies the request inside the
            // round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
            // In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
            Client.Responder askUser = (key, req) -> {
                Map<String, Object> params = Mcp.map(req.get("params"));
                System.out.println("server asks (" + req.get("method") + "): " + params.get("message"));
                // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
                return Mcp.obj("action", "accept", "content", Mcp.obj("name", "Ada"));
            };

            Map<String, Object> res = c.callTool("greet", Mcp.obj(), askUser);
            System.out.println("result: " + Mcp.text(res));
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

Errors are unchecked: a JSON-RPC error is an `RpcError` (`code`, `data`), anything else an `McpException`.

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
