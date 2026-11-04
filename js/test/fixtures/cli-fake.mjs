#!/usr/bin/env node
// Hermetic stand-ins for js/test/cli.test.ts (SPEC §8C), mirroring golang/cli_test.go.
// Built-ins only. One executable plays three parts:
//   cli-fake.mjs mcp-bridge <url>     the stdio MCP bridge (runMcpBridge from dist)
//   TN_FAKE_ACP=1 cli-fake.mjs acp    a fake ACP agent
//   TN_FAKE_CLI=<mode> cli-fake.mjs … a fake one-shot CLI
import fs from "node:fs"
import path from "node:path"
import readline from "node:readline"
import { spawn } from "node:child_process"

const argv = process.argv.slice(2)

if (argv.length === 2 && argv[0] === "mcp-bridge") {
  const { runMcpBridge } = await import(new URL("../../dist/index.js", import.meta.url).href)
  try {
    await runMcpBridge(argv[1], process.env.TOOLNEXUS_MCP_TOKEN ?? "", process.stdin, process.stdout)
  } catch (e) {
    process.stderr.write(String(e?.message ?? e) + "\n")
    process.exit(1)
  }
} else if (process.env.TN_FAKE_ACP === "1" && argv[0] === "acp") {
  await fakeACPAgent()
} else if (process.env.TN_FAKE_CLI) {
  await fakeOneShot(process.env.TN_FAKE_CLI)
} else {
  process.stderr.write("cli-fake: no part selected\n")
  process.exit(9)
}

// ---------------------------------------------------------------------------
// Fake one-shot CLI. Prompt from stdin, or the arg after --p, or the file after --f.
// Answers on stdout, or into the file after --o.
// ---------------------------------------------------------------------------
async function fakeOneShot(mode) {
  let prompt = ""
  let outFile = ""
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--p") prompt = argv[++i]
    else if (argv[i] === "--f") prompt = fs.readFileSync(argv[++i], "utf8")
    else if (argv[i] === "--o") outFile = argv[++i]
    else if (argv[i] === "--argv") {
      process.stdout.write(JSON.stringify({ content: argv.join(" ") }))
      return
    }
  }
  if (!prompt) {
    const chunks = []
    for await (const c of process.stdin) chunks.push(c)
    prompt = Buffer.concat(chunks).toString("utf8")
  }
  if (process.env.TN_FAKE_REC) fs.appendFileSync(process.env.TN_FAKE_REC, prompt + "\n=====\n")
  let reply
  switch (mode) {
    case "exit":
      process.stderr.write("boom: bad login")
      process.exit(3)
    case "empty":
      return
    case "sleep":
      await new Promise((r) => setTimeout(r, 10000))
      break
    case "nooutfile":
      process.stdout.write("progress only")
      return
    case "tool":
      if (prompt.includes("BADGE-77Q")) reply = JSON.stringify({ content: "Your badge is BADGE-77Q." })
      else
        reply =
          "```json\n" +
          `{"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_badge_number","arguments":"{}"}}]}` +
          "\n```\n\n"
      break
    default:
      reply = `{"content":"hello"}`
  }
  if (outFile) {
    process.stdout.write("progress on stdout\n")
    fs.writeFileSync(outFile, reply)
    return
  }
  process.stdout.write(reply)
}

// ---------------------------------------------------------------------------
// Fake ACP agent. FAKE_HTTP=1 advertises mcpCapabilities.http. FAKE_FAIL_INIT=1 exits
// before initialize. FAKE_NO_CONFIG=1 rejects session/set_config_option. FAKE_PROJECT=1
// reads the server from <cwd>/.mcp.json. TN_FAKE_REC records every method + params.
// ---------------------------------------------------------------------------
async function fakeACPAgent() {
  if (process.env.FAKE_FAIL_INIT === "1") {
    process.stderr.write("unknown flag: acp")
    process.exit(2)
  }
  const send = (m) => process.stdout.write(JSON.stringify(m) + "\n")
  const rec = (line) => process.env.TN_FAKE_REC && fs.appendFileSync(process.env.TN_FAKE_REC, line + "\n")
  const waiters = new Map()
  const queue = []
  let wake = null
  const rl = readline.createInterface({ input: process.stdin })
  rl.on("line", (line) => {
    let m
    try {
      m = JSON.parse(line)
    } catch {
      return
    }
    if (m.method === undefined && waiters.has(m.id)) {
      const w = waiters.get(m.id)
      waiters.delete(m.id)
      w(m)
      return
    }
    queue.push(m)
    wake?.()
  })
  rl.on("close", () => {
    queue.push(null)
    wake?.()
  })
  const next = async () => {
    while (queue.length === 0) await new Promise((r) => (wake = r))
    wake = null
    return queue.shift()
  }
  let reqId = 1000
  const ask = (callId, title) =>
    new Promise((resolve) => {
      const id = ++reqId
      waiters.set(id, (r) => resolve(r?.result?.outcome?.optionId ?? ""))
      send({
        jsonrpc: "2.0",
        id,
        method: "session/request_permission",
        params: {
          sessionId: "s1",
          toolCall: { toolCallId: callId, title },
          options: [
            { optionId: "yes", kind: "allow_once" },
            { optionId: "no", kind: "reject_once" },
          ],
        },
      })
    })
  const update = (u) => send({ jsonrpc: "2.0", method: "session/update", params: { sessionId: "s1", update: u } })

  let entry = null
  let cwd = ""
  for (;;) {
    const m = await next()
    if (m === null) return
    rec(`${m.method} ${JSON.stringify(m.params)}`)
    switch (m.method) {
      case "initialize":
        send({
          jsonrpc: "2.0",
          id: m.id,
          result: { protocolVersion: 1, agentCapabilities: { mcpCapabilities: { http: process.env.FAKE_HTTP === "1" } } },
        })
        break
      case "session/new":
        cwd = m.params.cwd
        if (m.params.mcpServers?.length === 1) entry = m.params.mcpServers[0]
        send({ jsonrpc: "2.0", id: m.id, result: { sessionId: "s1" } })
        break
      case "session/set_config_option":
        if (process.env.FAKE_NO_CONFIG === "1") send({ jsonrpc: "2.0", id: m.id, error: { code: -32601, message: "nope" } })
        else send({ jsonrpc: "2.0", id: m.id, result: {} })
        break
      case "session/set_model":
        send({ jsonrpc: "2.0", id: m.id, result: {} })
        break
      case "session/prompt": {
        if (process.env.FAKE_PROJECT === "1") {
          const pf = JSON.parse(fs.readFileSync(path.join(cwd, ".mcp.json"), "utf8"))
          const e = pf.mcpServers.toolnexus
          entry = {
            type: "http",
            url: e.url,
            headers: Object.entries(e.headers ?? {}).map(([name, value]) => ({ name, value })),
          }
        }
        // devin-like: the name is only on the tool_call update; the permission title is empty.
        update({ sessionUpdate: "tool_call", toolCallId: "tc1", title: "Calling get_badge_number from toolnexus" })
        const ours = await ask("tc1", "")
        update({ sessionUpdate: "tool_call", toolCallId: "tc2", title: "Bash" })
        const own = await ask("tc2", "Bash")
        const fake = await ask("tc3", "my_get_badge_number_wrapper")
        const again = await ask("tc1", "Bash")
        // an agent->client request the client does not implement must get -32601
        const unknown = await new Promise((resolve) => {
          const id = ++reqId
          waiters.set(id, (r) => resolve(r?.error?.code ?? "none"))
          send({ jsonrpc: "2.0", id, method: "fs/read_text_file", params: { sessionId: "s1", path: "/etc/hosts" } })
        })
        const text = `perm ours=${ours} own=${own} fake=${fake} again=${again} unknown=${unknown}; ` + (await callServed(entry))
        update({ sessionUpdate: "agent_message_chunk", content: { type: "text", text } })
        send({ jsonrpc: "2.0", id: m.id, result: { stopReason: "end_turn" } })
        break
      }
    }
  }
}

// Calls get_badge_number and load_skill on the handed MCP server (http with its headers,
// or by spawning the stdio entry), and probes the http endpoint without a token.
async function callServed(entry) {
  const reqs = [
    `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"fake","version":"0"}}}`,
    `{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}`,
    `{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_badge_number","arguments":{}}}`,
    `{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"load_skill","arguments":{"name":"hello-world"}}}`,
  ]
  let replies = []
  let noauth = 0
  if (entry?.type === "http") {
    const hdr = {}
    for (const h of entry.headers) hdr[h.name] = h.value
    for (const r of reqs) {
      try {
        const resp = await fetch(entry.url, {
          method: "POST",
          headers: { "content-type": "application/json", accept: "application/json, text/event-stream", ...hdr },
          body: r,
        })
        replies.push(await resp.text())
      } catch {
        return "http error"
      }
    }
    try {
      const resp = await fetch(entry.url, { method: "POST", headers: { "content-type": "application/json" }, body: reqs[0] })
      noauth = resp.status
      await resp.text()
    } catch {}
  } else if (entry) {
    const env = { ...process.env }
    for (const e of entry.env) env[e.name] = e.value
    const out = await new Promise((resolve) => {
      const child = spawn(entry.command, entry.args, { env, stdio: ["pipe", "pipe", "inherit"] })
      let buf = ""
      child.stdout.setEncoding("utf8").on("data", (d) => (buf += d))
      child.on("close", (code) => resolve(code === 0 ? buf : null))
      child.on("error", () => resolve(null))
      child.stdin.end(reqs.join("\n") + "\n")
    })
    if (out === null) return "bridge error"
    replies = out.trim().split("\n")
  }
  return `entry=${entry?.type ?? "<nil>"} noauth=${noauth} replies=${replies.join(" | ")}`
}
