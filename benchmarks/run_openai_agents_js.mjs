#!/usr/bin/env node
// OpenAI Agents SDK (JS, @openai/agents) benchmark runner — mock-LLM only, ZERO cost.
//
// Same harness contract as run_vercel_ai.mjs: starts its own mock on a free
// port, cold init + per-request wall time for the fixed scenario, ONE JSON line.
// Deps live OUTSIDE the repo (OPENAI_AGENTS_JS_DIR).
//
// MCP: the SDK's native MCPServerStdio over the shared stdio server, ONE
// persistent session. Model: OpenAIChatCompletionsModel (Chat Completions —
// the SDK default is the Responses API, which the mock does not speak) over an
// OpenAI client pointed at the mock. Tracing disabled (no backend calls).
//   cold init   = MCPServerStdio.connect() + listTools() + client + model + Agent
//   per-request = run(agent, QUESTION)
import { spawn } from "node:child_process"
import net from "node:net"
import path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import http from "node:http"
import { createRequire } from "node:module"

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const REPO = process.env.BENCH_REPO || path.resolve(__dirname, "..")
const MCP_PY = process.env.MCP_PYTHON || process.env.PYTHON || "python3"
const DIR = process.env.OPENAI_AGENTS_JS_DIR || "/tmp/bench-venvs/js-openai-agents"
const RUNS = parseInt(process.env.BENCH_RUNS || "30", 10)
const WARMUP = parseInt(process.env.BENCH_WARMUP || "5", 10)
const QUESTION = "What's the weather in Paris and what is 2+2?"
const FINAL_EXPECT = "2 + 2 = 4"

const req = createRequire(`${DIR}/package.json`)
const imp = (p) => import(pathToFileURL(req.resolve(p)).href)
const { Agent, run, MCPServerStdio, OpenAIChatCompletionsModel, setTracingDisabled } = await imp("@openai/agents")
const { default: OpenAI } = await imp("openai")
setTracingDisabled(true)

function freePort() {
  return new Promise((resolve) => {
    const srv = net.createServer()
    srv.listen(0, "127.0.0.1", () => {
      const p = srv.address().port
      srv.close(() => resolve(p))
    })
  })
}

function waitHealth(url, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  return new Promise((resolve, reject) => {
    const tick = () => {
      const req = http.get(url, (res) => { res.resume(); resolve() })
      req.on("error", () => {
        if (Date.now() > deadline) reject(new Error("mock LLM did not come up"))
        else setTimeout(tick, 50)
      })
    }
    tick()
  })
}

function pct(sorted, p) {
  if (!sorted.length) return 0
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))
  return sorted[idx]
}

async function main() {
  const port = await freePort()
  const mockUrl = `http://127.0.0.1:${port}`
  const mock = spawn(MCP_PY, [`${REPO}/benchmarks/mock_llm.py`, "--port", String(port)], {
    stdio: ["ignore", "ignore", "inherit"],
  })
  process.on("exit", () => mock.kill())

  let server
  try {
    await waitHealth(`${mockUrl}/health`)

    const t0 = performance.now()
    server = new MCPServerStdio({
      command: MCP_PY,
      args: [`${REPO}/benchmarks/mcp_server.py`],
      cacheToolsList: true,
    })
    await server.connect()
    const toolCount = (await server.listTools()).length
    const client = new OpenAI({ baseURL: mockUrl, apiKey: "sk-mock-not-a-real-key" })
    const model = new OpenAIChatCompletionsModel(client, "mock-model")
    const agent = new Agent({
      name: "Assistant",
      instructions: "You are a helpful assistant.",
      model,
      mcpServers: [server],
      modelSettings: { temperature: 0 },
    })
    const initMs = performance.now() - t0

    const call = () => run(agent, QUESTION)
    for (let i = 0; i < WARMUP; i++) await call()

    const lat = []
    let finalText = ""
    for (let i = 0; i < RUNS; i++) {
      const s = performance.now()
      const res = await call()
      lat.push(performance.now() - s)
      finalText = res.finalOutput || ""
    }

    const sorted = [...lat].sort((a, b) => a - b)
    const mean = lat.reduce((a, b) => a + b, 0) / lat.length
    process.stdout.write(
      JSON.stringify({
        name: "OpenAI Agents SDK (JS)",
        framework: "openai-agents-js-mcp",
        language: "js",
        init_ms: Math.round(initMs * 100) / 100,
        tool_count: toolCount,
        mean_ms: Math.round(mean * 100) / 100,
        p50_ms: Math.round(pct(sorted, 50) * 100) / 100,
        p95_ms: Math.round(pct(sorted, 95) * 100) / 100,
        n: lat.length,
        final_ok: finalText.includes(FINAL_EXPECT),
        final_text: finalText,
      }) + "\n",
    )
  } finally {
    if (server) { try { await server.close() } catch {} }
    mock.kill()
  }
}

await main()
process.exit(0)
