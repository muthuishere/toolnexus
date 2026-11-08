import { createToolkit, defineTool } from "toolnexus"

const add = defineTool({
  name: "add", description: "Add two numbers.",
  inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
  run: ({ a, b }) => `${a + b}`,
})

// Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
const toolkit = await createToolkit({ builtins: false, extraTools: [add] })
const server = await toolkit.asMcpServer() // { tools: [...] } narrows what is exposed
console.log("MCP endpoint:", server.url)   // http://127.0.0.1:<port>/mcp, POST only

// Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
const entry = { type: "http", name: "toolnexus", url: server.url,
                headers: [{ name: "Authorization", value: `Bearer ${server.token}` }] }
const res = await fetch(entry.url, {
  method: "POST",
  headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream", Authorization: entry.headers[0].value },
  body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }),
})
console.log("tools/list:", res.status)
await server.close()
