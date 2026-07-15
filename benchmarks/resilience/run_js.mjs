#!/usr/bin/env node
// toolnexus (JS port) RESILIENCE runner.
//
// Runs the eight adverse-condition scenarios (R1-R8) against the shared
// resilience mock LLM + local MCP stubs, printing ONE JSON line per scenario:
//   {lang,scenario,outcome,detail,elapsed_ms}
// outcome ∈ graceful | crash | hang | wrong. Imports the LOCALLY BUILT port
// from js/dist. Nothing calls a real LLM; nothing leaves the box.
//
// Env: BENCH_REPO, MCP_PYTHON, MOCK_BASE, MCP_HANG.
import { createRequire } from "node:module"
import net from "node:net"
import os from "node:os"
import fs from "node:fs"
import path from "node:path"

const REPO = process.env.BENCH_REPO
const MCP_PY = process.env.MCP_PYTHON
const MOCK_BASE = process.env.MOCK_BASE || "http://127.0.0.1:8901"
const MCP_HANG = process.env.MCP_HANG
const GOOD_SERVER = `${REPO}/benchmarks/mcp_server.py`
const HARD_CAP_MS = 20000

const { createToolkit, createClient, defineTool } = await import(`${REPO}/js/dist/index.js`)

const goodCfg = () => ({ type: "local", command: [MCP_PY, GOOD_SERVER], enabled: true, timeout: 30000 })

function closedPort() {
  // synchronous-ish: reserve then free a port. Use a fixed high port from an OS pick.
  return new Promise((resolve) => {
    const srv = net.createServer()
    srv.listen(0, "127.0.0.1", () => {
      const p = srv.address().port
      srv.close(() => resolve(p))
    })
  })
}

function boomTool() {
  return defineTool({
    name: "boom", description: "A tool that always raises.",
    inputSchema: { type: "object", properties: {} },
    run: () => { throw new Error("boom: native tool failed on purpose") },
  })
}

async function r1() {
  const cfg = { mcpServers: { good: goodCfg(),
    bad: { type: "local", command: ["/nonexistent/tn-badcmd-xyz"], enabled: true, timeout: 2000 } } }
  const tk = await createToolkit({ mcpConfig: cfg, builtins: false })
  try {
    const st = tk.mcpStatus(); const names = tk.tools().map((t) => t.name)
    const ok = st.bad === "failed" && st.good === "connected" && names.length > 0
    return [ok ? "graceful" : "wrong", `status=${JSON.stringify(st)} tools=${names.sort()}`]
  } finally { await tk.close() }
}

async function r2() {
  const port = await closedPort()
  const cfg = { mcpServers: { good: goodCfg(),
    remote: { type: "remote", url: `http://127.0.0.1:${port}/mcp`, enabled: true, timeout: 2000 } } }
  const tk = await createToolkit({ mcpConfig: cfg, builtins: false })
  try {
    const st = tk.mcpStatus(); const names = tk.tools().map((t) => t.name)
    const ok = st.remote === "failed" && st.good === "connected" && names.length > 0
    return [ok ? "graceful" : "wrong", `status=${JSON.stringify(st)} tools=${names.sort()}`]
  } finally { await tk.close() }
}

async function r3() {
  const cfg = { mcpServers: { good: goodCfg(),
    hang: { type: "local", command: [MCP_PY, MCP_HANG], enabled: true, timeout: 2000 } } }
  const tk = await createToolkit({ mcpConfig: cfg, builtins: false })
  try {
    const st = tk.mcpStatus(); const names = tk.tools().map((t) => t.name)
    const ok = st.hang === "failed" && st.good === "connected" && names.length > 0
    return [ok ? "graceful" : "wrong", `status=${JSON.stringify(st)} tools=${names.sort()}`]
  } finally { await tk.close() }
}

async function r4() {
  const p = path.join(os.tmpdir(), `tn-bad-${Date.now()}.json`)
  fs.writeFileSync(p, '{ "mcpServers": { "x": { "type": "local", ')
  try {
    let tk
    try { tk = await createToolkit({ mcpConfig: p, builtins: false }) }
    catch (e) { return ["graceful", `raised ${e.constructor.name}: ${String(e.message).slice(0, 80)}`] }
    await tk.close()
    return ["wrong", "malformed config did not raise"]
  } finally { fs.unlinkSync(p) }
}

async function runLLM(suffix, { retries = 2, tools = [] } = {}) {
  const tk = await createToolkit({ extraTools: tools, builtins: false })
  try {
    const client = createClient({ baseUrl: `${MOCK_BASE}/${suffix}`, style: "openai",
      model: "mock-model", apiKey: "sk-mock-not-real", retries, retryBaseMs: 1 })
    return await client.run("hello", { toolkit: tk })
  } finally { await tk.close() }
}

async function r5() {
  try { const r = await runLLM("e402"); return ["wrong", `402 did not surface; text=${JSON.stringify(r.text)}`] }
  catch (e) { return ["graceful", `surfaced ${e.constructor.name}: ${String(e.message).slice(0, 80)}`] }
}

async function r6() {
  const suffix = `retry/${Math.random().toString(16).slice(2)}`
  let r
  try { r = await runLLM(suffix, { retries: 3 }) }
  catch (e) { return ["crash", `did not recover from 429: ${e.constructor.name}: ${String(e.message).slice(0, 80)}`] }
  if (r.text && r.text.includes("RESILIENCE_OK")) return ["graceful", `retried then succeeded: text=${JSON.stringify(r.text)}`]
  return ["wrong", `unexpected final text=${JSON.stringify(r.text)}`]
}

async function r7() {
  try { const r = await runLLM("e500", { retries: 2 }); return ["wrong", `500 did not surface; text=${JSON.stringify(r.text)}`] }
  catch (e) { return ["graceful", `surfaced after retries ${e.constructor.name}: ${String(e.message).slice(0, 80)}`] }
}

async function r8() {
  let r
  try { r = await runLLM("boom", { tools: [boomTool()] }) }
  catch (e) { return ["crash", `tool error not fed back: ${e.constructor.name}: ${String(e.message).slice(0, 80)}`] }
  const boomErrs = r.toolCalls.filter((c) => c.name === "boom" && c.isError)
  if (r.text && r.text.includes("RESILIENCE_OK") && boomErrs.length)
    return ["graceful", `tool error fed back, loop reached final: text=${JSON.stringify(r.text)}`]
  return ["wrong", `text=${JSON.stringify(r.text)} boomErrors=${boomErrs.length}`]
}

const SCENARIOS = { R1: r1, R2: r2, R3: r3, R4: r4, R5: r5, R6: r6, R7: r7, R8: r8 }

async function runOne(key, fn) {
  const t0 = Date.now()
  let outcome, detail
  try {
    const res = await Promise.race([
      fn(),
      new Promise((_, rej) => setTimeout(() => rej(new Error("__HARD_CAP__")), HARD_CAP_MS)),
    ])
    ;[outcome, detail] = res
  } catch (e) {
    if (e && e.message === "__HARD_CAP__") { outcome = "hang"; detail = `exceeded ${HARD_CAP_MS}ms hard cap` }
    else { outcome = "crash"; detail = `harness caught ${e?.constructor?.name}: ${String(e?.message).slice(0, 100)}` }
  }
  return { lang: "js", scenario: key, outcome, detail, elapsed_ms: Math.round((Date.now() - t0) * 10) / 10 }
}

const only = process.env.ONLY_SCENARIO
for (const [key, fn] of Object.entries(SCENARIOS)) {
  if (only && key !== only) continue
  process.stdout.write(JSON.stringify(await runOne(key, fn)) + "\n")
}
process.exit(0)
