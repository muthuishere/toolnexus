/**
 * JS SERVER runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md). Mirrors
 * golang/internal/interop/mcp2026server. Test tooling only: lives outside src/, never published.
 *
 *   node --experimental-strip-types server.ts [--keycloak] <skillsDir>
 */
import http from "node:http"
import type { AddressInfo } from "node:net"
import { createToolkit, identityFrom, loadMcpSkills, McpServerAuth, MCP_PROTOCOL_2026, pending, type McpHandlerConfig, type McpPrincipal, type Tool } from "../../dist/index.js"
import { startMcpDevAuthServer } from "../../test/support/mcpdevauth.ts"

const KEYCLOAK_ISSUER = "http://localhost:8180/realms/toolnexus"
const KEYCLOAK_PORT = 7199

let args = process.argv.slice(2)
const keycloak = args[0] === "--keycloak"
if (keycloak) args = args.slice(1)
const skillsDir = args[0] ?? "../examples/skills"

const as = keycloak ? undefined : await startMcpDevAuthServer({ clients: { "interop-client": ["http://127.0.0.1/callback"] } })
const issuer = as ? as.issuer : KEYCLOAK_ISSUER

const obj = { type: "object" }
const tools: Tool[] = [
  {
    name: "echo", description: "Echo the given text back.", source: "native",
    inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] },
    async execute(a) {
      const s = String(a.text ?? "")
      return { output: s, structured: { text: s }, isError: false }
    },
  },
  {
    name: "greet", description: "Greet the user; asks for their name.", source: "native", inputSchema: obj,
    async execute(_a, ctx) {
      if (ctx?.answer?.ok) return { output: `Hello, ${String(ctx.answer.data?.name ?? "")}!`, isError: false }
      return pending({ id: "name", kind: "input", prompt: "What is your name?",
        data: { schema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } } })
    },
  },
  {
    name: "wipe", description: "Destructive; asks for confirmation first.", source: "native", inputSchema: obj,
    async execute() { return { output: "wiped", isError: false } },
  },
  {
    name: "whoami", description: "Who is calling.", source: "native", inputSchema: obj,
    async execute(_a, ctx) { return { output: (identityFrom(ctx) as McpPrincipal | undefined)?.subject ?? "", isError: false } },
  },
]

const tk = await createToolkit({ builtins: false })
const seen: string[] = []
const calls: Record<string, number> = {}
const cfg = (): McpHandlerConfig => ({
  name: "interop", version: "1.0.0",
  protocolVersions: [MCP_PROTOCOL_2026],
  listTools: () => tools,
  listSkills: () => loadMcpSkills(skillsDir),
  beforeCall: (_ctx, t) => (t.name === "wipe" ? { kind: "confirm", message: "Really wipe?" } : undefined),
  onCall: (ev) => { calls[ev.name] = (calls[ev.name] ?? 0) + 1 },
})
const auth = keycloak
  ? new McpServerAuth({ issuer, audience: `http://127.0.0.1:${KEYCLOAK_PORT}/mcp`, scopes: { "*": ["mcp:tools"], whoami: ["mcp:greet"] } })
  : new McpServerAuth({ issuer, scopes: { "*": ["mcp"], whoami: ["mcp:whoami"] } })

const open = tk.mcpHandler(cfg())
const protectedH = tk.mcpHandler({ ...cfg(), authenticate: auth.authenticate })
const legacy = tk.mcpHandler({ stateless: true, listTools: () => tools })
const meta = auth.metadata()

const hdr = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? ""
const srv = http.createServer((req, res) => {
  const path = (req.url ?? "").split("?")[0]
  const log = (mount: string) => seen.push(`${mount} ${hdr(req.headers["mcp-protocol-version"])} ${hdr(req.headers["mcp-method"])}`)
  if (path === "/open/mcp") { log("open"); return open(req, res) }
  if (path === "/mcp") { log("auth"); return protectedH(req, res) }
  if (path === "/legacy/mcp") { log("legacy"); return legacy(req, res) }
  if (path === "/.well-known/oauth-protected-resource" || path === "/.well-known/oauth-protected-resource/mcp") return meta(req, res)
  if (path === "/log") {
    res.writeHead(200, { "Content-Type": "application/json" })
    return res.end(JSON.stringify({ seen, calls }) + "\n")
  }
  res.writeHead(404).end()
})
await new Promise<void>((r) => srv.listen(keycloak ? KEYCLOAK_PORT : 0, "127.0.0.1", r))
const port = (srv.address() as AddressInfo).port
process.stdout.write(JSON.stringify({ base: `http://127.0.0.1:${port}`, issuer }) + "\n")

// Serve until stdin closes (the runner owns our lifetime).
process.stdin.resume()
process.stdin.on("end", () => process.exit(0))
process.stdin.on("close", () => process.exit(0))
