// Example 05 — lazy tool search: give the model 2 tools instead of 100.
//
// Run: node examples/05-tool-search/main.ts
//
// With toolSearch: true, c.tools() returns two meta-tools, search_tools(query, limit?) and
// call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then
// calls what it found. c.invoke runs whatever tool call the model emits.
import * as mcp from "../../src/index.ts"

// A server with 100 tools: 10 services x 10 verbs.
const srv = new mcp.Server()
for (const svc of ["github", "slack", "jira", "gmail", "calendar", "drive", "stripe", "postgres", "s3", "notion"]) {
  for (const verb of ["create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"]) {
    const name = `${svc}_${verb}`
    srv.tool(name, `${verb} an item in ${svc}.`, (args) => `${name} ran with ${args.text}`)
  }
}
const ts = await srv.listen()

const eager = new mcp.Client(ts.url)
const all = await eager.tools()
const size = (v: unknown) => JSON.stringify(v).length

// 1. Turn on tool search; description tells the model what this server is for.
const c = new mcp.Client(ts.url, { toolSearch: true, description: "SaaS workspace (GitHub, Slack, Jira, ...)" })
const meta = await c.tools()
console.log(`eager: ${all.length} tools, ${size(all)} bytes of schema`)
console.log(`lazy:  ${meta.length} tools, ${size(meta)} bytes of schema`)

// 2. The model calls search_tools ...
const hits = await c.searchTools("send a slack message create", 3)
console.log("top hit:", hits[0].name)

// 3. ... then call_tool with the name it found. invoke routes both meta-tools.
const res = await c.invoke("call_tool", { name: hits[0].name, arguments: { text: "hi team" } })
console.log("result:", mcp.text(res))

await ts.close()
