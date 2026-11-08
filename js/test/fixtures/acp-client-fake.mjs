#!/usr/bin/env node
// Fake ACP agent for js/test/acp-client.test.ts (SPEC §8C, conformance/acp-client/cases.json).
// Built-ins only. Records initialize / session/new / set_config_option / set_model params as
// JSON lines to TN_FAKE_REC (when set) and honours the fakeEnv flags:
//   TN_FAKE_DRIP             thought, then drip-1..3 message chunks 200ms apart
//   TN_FAKE_TOOLUPDATE       send stream.acpToolUpdate.updates from cases.json, then end_turn
//   TN_FAKE_NO_CONFIG_OPTION reject session/set_config_option (⇒ client falls back to set_model)
//   TN_FAKE_EXIT_ON_PROMPT   exit without replying to session/prompt
//   TN_FAKE_FS_REQUEST       send fs/read_text_file first; reply "ok" only after its error lands
import fs from "node:fs"
import readline from "node:readline"

const env = process.env
const send = (m) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", ...m }) + "\n")
const record = (method, params) => {
  if (env.TN_FAKE_REC) fs.appendFileSync(env.TN_FAKE_REC, JSON.stringify({ method, params }) + "\n")
}
const chunk = (sessionUpdate, text) =>
  send({ method: "session/update", params: { sessionId: "s1", update: { sessionUpdate, content: { type: "text", text } } } })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let promptId

readline.createInterface({ input: process.stdin }).on("line", async (line) => {
  let msg
  try {
    msg = JSON.parse(line)
  } catch {
    return
  }
  if (msg.method === undefined) {
    // reply to our fs request
    if (msg.id === "fs-1" && promptId !== undefined) {
      const ok = msg.error && msg.error.code === -32601
      chunk("agent_message_chunk", ok ? "ok" : "bad:" + JSON.stringify(msg))
      send({ id: promptId, result: { stopReason: "end_turn" } })
    }
    return
  }
  const { id, method, params } = msg
  switch (method) {
    case "initialize":
      record(method, params)
      return send({ id, result: { protocolVersion: 1, agentCapabilities: {} } })
    case "session/new":
      record(method, params)
      return send({ id, result: { sessionId: "s1" } })
    case "session/set_config_option":
      record(method, params)
      if (env.TN_FAKE_NO_CONFIG_OPTION) return send({ id, error: { code: -32601, message: "method not found" } })
      return send({ id, result: {} })
    case "session/set_model":
      record(method, params)
      return send({ id, result: {} })
    case "session/prompt":
      if (env.TN_FAKE_EXIT_ON_PROMPT) process.exit(0)
      if (env.TN_FAKE_FS_REQUEST) {
        promptId = id
        return send({ id: "fs-1", method: "fs/read_text_file", params: { sessionId: "s1", path: "/x" } })
      }
      if (env.TN_FAKE_DRIP) {
        chunk("agent_thought_chunk", "pondering")
        const parts = ["drip-1 ", "drip-2 ", "drip-3"]
        for (let i = 0; i < parts.length; i++) {
          if (i > 0) await sleep(200)
          chunk("agent_message_chunk", parts[i])
        }
        return send({ id, result: { stopReason: "end_turn" } })
      }
      if (env.TN_FAKE_TOOLUPDATE) {
        const cases = JSON.parse(fs.readFileSync(new URL("../../../conformance/acp-client/cases.json", import.meta.url), "utf8"))
        for (const update of cases.stream.acpToolUpdate.updates) send({ method: "session/update", params: { sessionId: "s1", update } })
        return send({ id, result: { stopReason: "end_turn" } })
      }
      chunk("agent_message_chunk", "hello")
      return send({ id, result: { stopReason: "end_turn" } })
    default:
      if (id !== undefined) send({ id, error: { code: -32601, message: "method not found" } })
  }
})
