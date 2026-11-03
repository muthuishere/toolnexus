// Example 01 — connect to an MCP server over Streamable HTTP and call a tool.
//
// Run: node examples/01-connect-http/main.ts
//
// The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
// Every request carries its own protocol version, so "connecting" is just new Client(url).
import * as mcp from "../../src/index.ts"

// A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
// In real use this is someone else's server, e.g. "https://api.example.com/mcp".
const ts = await mcp.fixtureServer().listen()

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

await ts.close()
