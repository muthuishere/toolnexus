#!/usr/bin/env node
// Vercel AI SDK benchmark runner — mock-LLM only, ZERO cost.
//
// Same harness contract as run_toolnexus_js.mjs: cold init (build MCP client +
// discover tools + build model) + per-request wall time for the fixed scenario.
// Deps live OUTSIDE the repo (VERCEL_DIR, default /tmp/bench-venvs/js-vercel).
//
// Loop: generateText({ model, tools, prompt, stopWhen: stepCountIs(N) }) — the
// SDK runs the multi-step tool-calling loop. MCP tools via @ai-sdk/mcp's
// createMCPClient + Experimental_StdioMCPTransport over the shared MCP server.
//
// Config:
//   mcp    : 2 MCP tools (get_weather, add) from the shared stdio server
//   native : same 2 tools defined locally (labelled "(native)")
//
// Prints ONE JSON line: {name,framework,language,init_ms,tool_count,mean_ms,
//   p50_ms,p95_ms,n,final_ok}
import { spawn } from "node:child_process"
import net from "node:net"
import path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import http from "node:http"

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const REPO = process.env.BENCH_REPO || path.resolve(__dirname, "..")
const MCP_PY = process.env.MCP_PYTHON || process.env.PYTHON || "python3"
const DIR = process.env.VERCEL_DIR || "/tmp/bench-venvs/js-vercel"
const RUNS = parseInt(process.env.BENCH_RUNS || "30", 10)
const WARMUP = parseInt(process.env.BENCH_WARMUP || "5", 10)
const QUESTION = "What's the weather in Paris and what is 2+2?"
const FINAL_EXPECT = "2 + 2 = 4"

const argv = process.argv.slice(2)
const CONFIG = (() => {
  const i = argv.indexOf("--config")
  return i >= 0 ? argv[i + 1] : "mcp"
})()

const nm = (p) => pathToFileURL(`${DIR}/node_modules/${p}`).href
const { generateText, stepCountIs, tool } = await import(nm("ai/dist/index.js"))
const { createOpenAI } = await import(nm("@ai-sdk/openai/dist/index.js"))
const { createMCPClient } = await import(nm("@ai-sdk/mcp/dist/index.js"))
const { Experimental_StdioMCPTransport } = await import(nm("@ai-sdk/mcp/dist/mcp-stdio/index.js"))
const { z } = await import(nm("zod/index.js"))

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

function nativeTools() {
  return {
    get_weather: tool({
      description: "Get the current weather for a city.",
      inputSchema: z.object({ city: z.string() }),
      execute: async ({ city }) => `The weather in ${city} is Sunny, 22C.`,
    }),
    add: tool({
      description: "Add two integers.",
      inputSchema: z.object({ a: z.number(), b: z.number() }),
      execute: async ({ a, b }) => String(a + b),
    }),
  }
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

  let mcpClient
  try {
    await waitHealth(`${mockUrl}/health`)

    // ---- cold init: MCP connect + tool discovery + model ----
    const t0 = performance.now()
    let tools
    if (CONFIG === "native") {
      tools = nativeTools()
    } else {
      mcpClient = await createMCPClient({
        transport: new Experimental_StdioMCPTransport({
          command: MCP_PY,
          args: [`${REPO}/benchmarks/mcp_server.py`],
        }),
      })
      tools = await mcpClient.tools()
    }
    const openai = createOpenAI({ baseURL: mockUrl, apiKey: "sk-mock-not-a-real-key" })
    // .chat() targets the OpenAI Chat Completions API (what the mock speaks);
    // the bare openai("id") default in v7 is the Responses API.
    const model = openai.chat("mock-model")
    const initMs = performance.now() - t0

    const toolCount = Object.keys(tools).length
    const call = () =>
      generateText({ model, tools, prompt: QUESTION, stopWhen: stepCountIs(6) })

    // ---- warmup ----
    for (let i = 0; i < WARMUP; i++) await call()

    // ---- measured runs ----
    const lat = []
    let finalText = ""
    for (let i = 0; i < RUNS; i++) {
      const s = performance.now()
      const res = await call()
      lat.push(performance.now() - s)
      finalText = res.text || ""
    }

    const sorted = [...lat].sort((a, b) => a - b)
    const mean = lat.reduce((a, b) => a + b, 0) / lat.length
    const nativeLabel = CONFIG === "native" ? " (native)" : ""

    process.stdout.write(
      JSON.stringify({
        name: `Vercel AI SDK${nativeLabel}`,
        framework: `vercel-ai-${CONFIG}`,
        language: "js",
        init_ms: Math.round(initMs * 100) / 100,
        tool_count: toolCount,
        mean_ms: Math.round(mean * 100) / 100,
        p50_ms: Math.round(pct(sorted, 50) * 100) / 100,
        p95_ms: Math.round(pct(sorted, 95) * 100) / 100,
        n: lat.length,
        final_ok: finalText.includes(FINAL_EXPECT),
      }) + "\n",
    )
  } finally {
    if (mcpClient) { try { await mcpClient.close() } catch {} }
    mock.kill()
  }
}

await main()
process.exit(0)
