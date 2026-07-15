#!/usr/bin/env node
// LangChain.js benchmark runner — mock-LLM only, ZERO cost.
//
// Same harness contract: cold init (MCP connect + tool discovery + model +
// agent) + per-request wall time for the fixed scenario. Deps live OUTSIDE the
// repo (LC_DIR, default /tmp/bench-venvs/js-langchain).
//
// Loop: langchain v1 createAgent({ model, tools }).invoke({ messages }) runs the
// tool-calling loop. MCP tools via @langchain/mcp-adapters MultiServerMCPClient
// over the shared stdio MCP server; ChatOpenAI points at the mock (Completions).
//
// Config:
//   mcp    : MCP tools from the shared stdio server
//   native : same tools defined locally with `tool()` (labelled "(native)")
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
const DIR = process.env.LC_DIR || "/tmp/bench-venvs/js-langchain"
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
const { createAgent, tool } = await import(nm("langchain/dist/index.js"))
const { ChatOpenAI } = await import(nm("@langchain/openai/dist/index.js"))
const { MultiServerMCPClient } = await import(nm("@langchain/mcp-adapters/dist/index.js"))
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
  return [
    tool(async ({ city }) => `The weather in ${city} is Sunny, 22C.`, {
      name: "get_weather",
      description: "Get the current weather for a city.",
      schema: z.object({ city: z.string() }),
    }),
    tool(async ({ a, b }) => String(a + b), {
      name: "add",
      description: "Add two integers.",
      schema: z.object({ a: z.number(), b: z.number() }),
    }),
  ]
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

    // ---- cold init: MCP connect + tool discovery + model + agent ----
    const t0 = performance.now()
    let tools
    if (CONFIG === "native") {
      tools = nativeTools()
    } else {
      mcpClient = new MultiServerMCPClient({
        mcpServers: {
          "bench-tools": {
            command: MCP_PY,
            args: [`${REPO}/benchmarks/mcp_server.py`],
            transport: "stdio",
          },
        },
      })
      tools = await mcpClient.getTools()
    }
    const model = new ChatOpenAI({
      model: "mock-model",
      temperature: 0,
      apiKey: "sk-mock-not-a-real-key",
      configuration: { baseURL: mockUrl },
    })
    const agent = createAgent({ model, tools })
    const initMs = performance.now() - t0

    const toolCount = tools.length
    const payload = { messages: [{ role: "user", content: QUESTION }] }

    // ---- warmup ----
    for (let i = 0; i < WARMUP; i++) await agent.invoke(payload)

    // ---- measured runs ----
    const lat = []
    let finalText = ""
    for (let i = 0; i < RUNS; i++) {
      const s = performance.now()
      const res = await agent.invoke(payload)
      lat.push(performance.now() - s)
      // The agent's final AI answer is not guaranteed to be the LAST array entry
      // (tool messages can trail it); pick the last AI message carrying text.
      finalText = ""
      for (const m of res.messages) {
        const type = typeof m.getType === "function" ? m.getType() : m._getType?.()
        if (type !== "ai") continue
        const c = typeof m.content === "string"
          ? m.content
          : Array.isArray(m.content)
            ? m.content.map((x) => x.text || "").join("")
            : ""
        if (c) finalText = c
      }
    }

    const sorted = [...lat].sort((a, b) => a - b)
    const mean = lat.reduce((a, b) => a + b, 0) / lat.length
    const nativeLabel = CONFIG === "native" ? " (native)" : ""

    process.stdout.write(
      JSON.stringify({
        name: `LangChain.js${nativeLabel}`,
        framework: `langchainjs-${CONFIG}`,
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
