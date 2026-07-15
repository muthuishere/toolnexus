#!/usr/bin/env node
// toolnexus (JS port) benchmark runner — mock-LLM only, ZERO cost.
//
// Same contract as run_toolnexus.py: cold init (build toolkit incl. MCP connect
// + discovery, build client) + per-request wall time for the fixed scenario.
// Imports the LOCALLY BUILT port from js/dist. Starts its own mock LLM on a free
// port and (for --config mcp/full) the shared stdio MCP server.
//
// Config:
//   mcp   : only the 3 MCP tools (builtins off)          -> apples-to-apples cell
//   full  : 3 MCP tools + 1 agent skill + 1 native tool  -> toolnexus's real shape
//   native: 1 native tool only (no MCP)                  -> loop overhead only
//
// Prints ONE JSON line: {name,framework,language,init_ms,tool_count,mean_ms,
//   p50_ms,p95_ms,n,final_ok}
//
// Env: BENCH_REPO (default: repo root inferred), MCP_PYTHON (python for MCP
//   server + mock), BENCH_RUNS, BENCH_WARMUP.
import { spawn } from "node:child_process"
import net from "node:net"
import path from "node:path"
import { fileURLToPath } from "node:url"
import http from "node:http"

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const REPO = process.env.BENCH_REPO || path.resolve(__dirname, "..")
const MCP_PY = process.env.MCP_PYTHON || process.env.PYTHON || "python3"
const RUNS = parseInt(process.env.BENCH_RUNS || "30", 10)
const WARMUP = parseInt(process.env.BENCH_WARMUP || "5", 10)
const QUESTION = "What's the weather in Paris and what is 2+2?"
const FINAL_EXPECT = "2 + 2 = 4"

const argv = process.argv.slice(2)
function argVal(flag, def) {
  const i = argv.indexOf(flag)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : def
}
const CONFIG = argVal("--config", "mcp") // mcp | full | native

const { createToolkit, createClient, defineTool } = await import(`${REPO}/js/dist/index.js`)

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
      const req = http.get(url, (res) => {
        res.resume()
        resolve()
      })
      req.on("error", () => {
        if (Date.now() > deadline) reject(new Error("mock LLM did not come up"))
        else setTimeout(tick, 50)
      })
    }
    tick()
  })
}

function mcpConfig() {
  return {
    mcpServers: {
      "bench-tools": {
        type: "local",
        command: [MCP_PY, `${REPO}/benchmarks/mcp_server.py`],
        enabled: true,
        timeout: 30000,
      },
    },
  }
}

function localMultiply() {
  return defineTool({
    name: "multiply",
    description: "Multiply two integers locally.",
    inputSchema: {
      type: "object",
      properties: { a: { type: "integer" }, b: { type: "integer" } },
      required: ["a", "b"],
    },
    run: (args) => String(Number(args.a) * Number(args.b)),
  })
}

// Native fallbacks matching the MCP server's get_weather + add, for the
// no-MCP variant (labelled "(native)").
function nativeTools() {
  return [
    defineTool({
      name: "get_weather",
      description: "Get the current weather for a city.",
      inputSchema: { type: "object", properties: { city: { type: "string" } }, required: ["city"] },
      run: (args) => `The weather in ${args.city} is Sunny, 22C.`,
    }),
    defineTool({
      name: "add",
      description: "Add two integers.",
      inputSchema: {
        type: "object",
        properties: { a: { type: "integer" }, b: { type: "integer" } },
        required: ["a", "b"],
      },
      run: (args) => String(Number(args.a) + Number(args.b)),
    }),
  ]
}

async function build(config) {
  if (config === "full") {
    return createToolkit({
      mcpConfig: mcpConfig(),
      skillsDir: `${REPO}/examples/skills`,
      extraTools: [localMultiply()],
      builtins: false,
    })
  }
  if (config === "native") {
    return createToolkit({ extraTools: nativeTools(), builtins: false })
  }
  // mcp
  return createToolkit({ mcpConfig: mcpConfig(), builtins: false })
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

  try {
    await waitHealth(`${mockUrl}/health`)

    // ---- cold init: toolkit build (MCP connect + discovery) + client ----
    const t0 = performance.now()
    const tk = await build(CONFIG)
    const client = createClient({
      baseUrl: mockUrl,
      style: "openai",
      model: "mock-model",
      apiKey: "sk-mock-not-a-real-key",
    })
    const initMs = performance.now() - t0

    const toolCount = tk.tools().length

    // ---- warmup ----
    for (let i = 0; i < WARMUP; i++) await client.run(QUESTION, { toolkit: tk })

    // ---- measured runs ----
    const lat = []
    let finalText = ""
    for (let i = 0; i < RUNS; i++) {
      const s = performance.now()
      const res = await client.run(QUESTION, { toolkit: tk })
      lat.push(performance.now() - s)
      finalText = res.text || ""
    }

    await tk.close()

    const sorted = [...lat].sort((a, b) => a - b)
    const mean = lat.reduce((a, b) => a + b, 0) / lat.length
    const nativeLabel = CONFIG === "native" ? " (native)" : ""

    process.stdout.write(
      JSON.stringify({
        name: `toolnexus (JS)${nativeLabel}`,
        framework: `toolnexus-js-${CONFIG}`,
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
    mock.kill()
  }
}

await main()
process.exit(0)
