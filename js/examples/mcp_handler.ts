/**
 * Embeddable MCP server (SPEC §7C.1): mount `tk.mcpHandler(...)` on your own HTTP server,
 * authenticate with a bearer token, give each caller its own tool list, and gate a destructive
 * tool behind a confirmation (elicitation when the client supports it, a host-shaped "re-call with
 * a nonce" result otherwise). Mirrors golang/examples/mcphandler.
 *
 *   npm run build && npm run example:mcp-handler
 *
 * Self-contained: starts on an ephemeral port and drives it with three MCP clients
 * (alice with elicitation, alice without, bob).
 */
import http from "node:http"
import type { AddressInfo } from "node:net"
import { Client as MCPClient } from "@modelcontextprotocol/sdk/client/index.js"
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js"
import { ElicitRequestSchema } from "@modelcontextprotocol/sdk/types.js"
import { createToolkit, identityFrom, type Tool } from "../dist/index.js"

type User = { name: string; role: "admin" | "viewer" }

// Demo tokens only. A real host verifies a JWT/OAuth token here.
const tokens: Record<string, User> = { "token-alice": { name: "alice", role: "admin" }, "token-bob": { name: "bob", role: "viewer" } }

function tools(u: User): Tool[] {
  const list: Tool[] = [
    {
      name: "list_records",
      description: "List records",
      source: "native",
      inputSchema: { type: "object" },
      annotations: { readOnlyHint: true },
      execute: async (_args, ctx) => ({ output: `records for ${(identityFrom(ctx) as User).name}: [1, 2]`, isError: false }),
    },
  ]
  if (u.role === "admin") {
    list.push({
      name: "delete_record",
      description: "Delete a record",
      source: "native",
      inputSchema: { type: "object", properties: { id: { type: "string" }, nonce: { type: "string" } } },
      annotations: { title: "Delete record", destructiveHint: true },
      execute: async (args) => ({ output: `deleted ${args.id}`, isError: false }),
    })
  }
  return list
}

const tk = await createToolkit({ builtins: false })
const mcp = tk.mcpHandler({
  name: "records",
  version: "1.0.0",
  authenticate: (req) => {
    const u = tokens[(req.headers.authorization ?? "").replace(/^Bearer /, "")]
    if (!u) throw new Error("invalid token")
    return u
  },
  wwwAuthenticate: () => `Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource"`,
  listTools: (ctx) => tools(identityFrom(ctx) as User),
  beforeCall: (_ctx, tool, args) => {
    if (!tool.annotations?.destructiveHint) return { kind: "allow" }
    if (args.nonce === "n-123") return { kind: "allow" } // the host's own nonce check
    return {
      kind: "confirm",
      message: "Really delete this record?",
      result: { output: `Confirmation needed: call delete_record again with "nonce":"n-123".`, isError: false },
    }
  },
  onCall: (ev) =>
    console.log(`  [onCall] ${ev.name} by ${(ev.identity as User).name} decision=${ev.decision} isError=${ev.isError} ${ev.resultBytes}B`),
})

// The host owns the server and routing; mount the handler on /mcp.
const server = http.createServer((req, res) => {
  if (req.url === "/mcp" || req.url?.startsWith("/mcp?")) return mcp(req, res)
  res.writeHead(404).end()
})
await new Promise<void>((r) => server.listen(0, "127.0.0.1", r))
const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`

const resp = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: `{"jsonrpc":"2.0","id":1,"method":"ping"}` })
await resp.text()
console.log(`no token -> ${resp.status}, WWW-Authenticate: ${resp.headers.get("www-authenticate")}`)

async function connect(token: string, elicit = false) {
  const c = new MCPClient({ name: "demo", version: "1" }, { capabilities: elicit ? { elicitation: {} } : {} })
  if (elicit) {
    c.setRequestHandler(ElicitRequestSchema, async (r) => {
      console.log(`  [client asked] ${r.params.message} -> accept`)
      return { action: "accept", content: { confirm: true } }
    })
  }
  await c.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { Authorization: "Bearer " + token } } }))
  return c
}
const show = async (c: MCPClient) => console.log("  tools:", (await c.listTools()).tools.map((t) => t.name))
async function callText(c: MCPClient, name: string, args: Record<string, unknown>) {
  try {
    const r: any = await c.callTool({ name, arguments: args })
    return r.content[0].text
  } catch (e) {
    return "error: " + (e as Error).message
  }
}

console.log("alice (admin, client supports elicitation):")
const alice = await connect("token-alice", true)
await show(alice)
console.log("  delete_record ->", await callText(alice, "delete_record", { id: "7" }))

console.log("alice (admin, client WITHOUT elicitation):")
const alice2 = await connect("token-alice")
console.log("  delete_record ->", await callText(alice2, "delete_record", { id: "7" }))
console.log("  delete_record+nonce ->", await callText(alice2, "delete_record", { id: "7", nonce: "n-123" }))

console.log("bob (viewer):")
const bob = await connect("token-bob")
await show(bob)
console.log("  delete_record ->", await callText(bob, "delete_record", { id: "7" }))

for (const c of [alice, alice2, bob]) await c.close()
server.closeAllConnections()
server.close()
await tk.close()
