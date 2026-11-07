#!/usr/bin/env node
// Shared fixture (SPEC §2.2a, change reuse-stdio-discover-probe): a PRE-2026 stdio MCP server
// that counts its own process starts.
//
//   SPAWN_COUNT_FILE  path; one line "start <pid>" is appended per process start (required)
//   DISCOVER_MODE     "error"  (default) answer server/discover with JSON-RPC -32601
//                     "silent" never answer server/discover (the client must time out + respawn)
//
// Speaks only the frozen protocol: initialize, notifications/initialized, tools/list, tools/call
// (one tool `echo` -> text "echo:<text>"). Stdlib only, newline-delimited JSON-RPC.
import { appendFileSync } from "node:fs"
import { createInterface } from "node:readline"

const countFile = process.env.SPAWN_COUNT_FILE
if (countFile) appendFileSync(countFile, `start ${process.pid}\n`)
const mode = process.env.DISCOVER_MODE || "error"

const send = (m) => process.stdout.write(JSON.stringify(m) + "\n")
const rl = createInterface({ input: process.stdin })
rl.on("line", (line) => {
  if (!line.trim()) return
  let m
  try { m = JSON.parse(line) } catch { return }
  if (m.id === undefined || m.id === null) return // notifications
  switch (m.method) {
    case "server/discover":
      if (mode === "silent") return
      return send({ jsonrpc: "2.0", id: m.id, error: { code: -32601, message: "Method not found: server/discover" } })
    case "initialize":
      return send({ jsonrpc: "2.0", id: m.id, result: {
        protocolVersion: m.params?.protocolVersion || "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: { name: "legacy-stdio", version: "1.0.0" } } })
    case "ping":
      return send({ jsonrpc: "2.0", id: m.id, result: {} })
    case "tools/list":
      return send({ jsonrpc: "2.0", id: m.id, result: { tools: [{
        name: "echo", description: "Echo the text back",
        inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] } }] } })
    case "tools/call":
      return send({ jsonrpc: "2.0", id: m.id, result: {
        content: [{ type: "text", text: "echo:" + (m.params?.arguments?.text ?? "") }] } })
    default:
      return send({ jsonrpc: "2.0", id: m.id, error: { code: -32601, message: "Method not found: " + m.method } })
  }
})
rl.on("close", () => process.exit(0))
