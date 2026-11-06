# MCP quickstart: zero to tool call

> **JS port of the Go reference spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/js` (`import * as mcp from "./src/index.ts"`; Node 22.18+, no dependencies, no MCP SDK). Every
> code block is copied verbatim from a program under `examples/` that `test/examples.test.ts` runs and checks;
> `test/docs.test.ts` fails if a block here stops matching its source.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client.

## Over HTTP

<!-- from: examples/01-connect-http/main.ts -->
```ts
// 1. Point a client at the server's MCP endpoint. No handshake happens here.
const c = new mcp.Client(ts.url)

// 2. Optional: server/discover tells you which versions and capabilities the server has.
const info = await c.call("server/discover")
console.log(`supported versions: [${info.supportedVersions.join(" ")}]`)

// 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
const tools = await c.tools()
console.log("tools:", tools.map((t) => t.name).join(", "))

// 4. Call a tool. mcp.text returns the first text content of the result.
const res = await c.callTool("echo", { text: "hello over HTTP" })
console.log("echo:", mcp.text(res))
```

Output (`node examples/01-connect-http/main.ts`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. stderr is the server's log and is never parsed
(`stderr: "inherit" | "ignore" | <Writable>`). stdio has no HTTP headers and no OAuth: the server reads its
credentials from its own environment.

<!-- from: examples/02-connect-stdio/main.ts -->
```ts
// 1. Describe the process to spawn (args, env, cwd, stderr).
// 2. Start it and get a client. Same API as the HTTP client: call, tools, callTool.
const c = await mcp.connectStdio(process.execPath, {
  args: [import.meta.filename],
  env: { ...process.env, SERVE_STDIO: "1" },
})

// 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
const tools = await c.tools()
console.log("tool count:", tools.length)

const res = await c.callTool("echo", { text: "hello over stdio" })
console.log("echo:", mcp.text(res))

await c.close() // stops the server process
```

```text
tool count: 2
echo: hello over stdio
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `callTool` answers them with your `Responder` and re-sends
the call with `inputResponses` and the opaque `requestState`. You write only the callback:

<!-- from: examples/03-tool-input/main.ts -->
```ts
// The Responder is called once per input request. key identifies the request inside the
// round; req is the request itself ({ method: "elicitation/create", params: {...} }).
// In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
const askUser: mcp.Responder = async (key, req) => {
  console.log(`server asks (${req.method}): ${req.params?.message}`)
  // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
  return { action: "accept", content: { name: "Ada" } }
}

const res = await c.callTool("greet", {}, askUser)
console.log("result:", mcp.text(res))
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
