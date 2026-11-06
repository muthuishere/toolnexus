// Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).
//
// Run: node examples/02-connect-stdio/main.ts
//
// stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is passed
// through (or captured via the stderr option) and never parsed. There are no HTTP headers and no
// OAuth over stdio: a stdio server takes its credentials from its own environment.
import * as mcp from "../../src/index.ts"

// Demo harness: this same script is the stdio server when started with SERVE_STDIO=1.
// In real use the command is your server, e.g. connectStdio("npx", { args: ["-y", "some-mcp-server"] }).
if (process.env.SERVE_STDIO === "1") {
  await mcp.serveStdio(mcp.fixtureServer())
  process.exit(0)
}

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
