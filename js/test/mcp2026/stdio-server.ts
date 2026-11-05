// A 2026-07-28 MCP server over stdio for the tests: the shared fixture (echo + greet + hello-world skill).
// Run: node --experimental-strip-types test/mcp2026/stdio-server.ts
import { createInterface } from "node:readline"
import { fixtureServer } from "./server.ts"
import { CODE_UNSUPPORTED_PROTOCOL, META_VERSION, PROTOCOL_VERSION, RpcError, type Obj } from "./protocol.ts"

const s = fixtureServer()
if (process.argv.includes("--slow-echo")) s.tool("slow", "waits 200ms", async () => (await new Promise((r) => setTimeout(r, 200)), "slow"))
if (process.argv.includes("--die-on-call")) s.tool("die", "exits the process", () => process.exit(3))
const rl = createInterface({ input: process.stdin, crlfDelay: Infinity })
rl.on("line", (line) => {
  let req: any
  try {
    req = JSON.parse(line)
  } catch {
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }) + "\n")
    return
  }
  if (!req || req.id === undefined || req.id === null) return
  void (async () => {
    const params: Obj = req.params && typeof req.params === "object" ? req.params : {}
    const mv = params._meta?.[META_VERSION] ?? ""
    const msg: Obj = { jsonrpc: "2.0", id: req.id }
    try {
      if (mv !== PROTOCOL_VERSION)
        throw new RpcError(CODE_UNSUPPORTED_PROTOCOL, "Unsupported protocol version", { supported: [PROTOCOL_VERSION], requested: mv })
      msg.result = await s.dispatch(String(req.method ?? ""), params)
    } catch (e) {
      msg.error = e instanceof RpcError ? e.toJSON() : { code: -32603, message: String((e as Error)?.message ?? e) }
    }
    process.stdout.write(JSON.stringify(msg) + "\n")
  })()
})
