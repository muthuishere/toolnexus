// Prompt cache + tool tiers, hermetic (no network, no key). Run:
//   npm run build && node --experimental-strip-types examples/prompt_cache.ts
//
// A local stub plays an OpenAI-style model. It sees ONLY the core tool schemas plus
// `tool_search` / `call_tool`, loads the deferred `weather` tool with tool_search, then calls it
// through call_tool. The tools array is the same on every request, so the cached prefix holds,
// and the stub reports cached tokens from the second request on.
import assert from "node:assert"
import http from "node:http"
import { createClient, createToolkit, defineTool } from "toolnexus"

const bodies: any[] = []
const replies = [
  { tool_calls: [{ id: "c1", type: "function", function: { name: "tool_search", arguments: '{"query":"select:weather"}' } }] },
  { tool_calls: [{ id: "c2", type: "function", function: { name: "call_tool", arguments: '{"name":"weather","arguments":{"city":"Paris"}}' } }] },
  { content: "Sunny in Paris." },
]
const server = http.createServer((req, res) => {
  let raw = ""
  req.on("data", (c) => (raw += c))
  req.on("end", () => {
    bodies.push(JSON.parse(raw))
    const n = bodies.length
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({
      choices: [{ message: { content: null, ...replies[n - 1] }, finish_reason: n < 3 ? "tool_calls" : "stop" }],
      // A real provider reports the cached part of the prompt; the stub does from request 2 on.
      usage: { prompt_tokens: 100, completion_tokens: 5, total_tokens: 105, prompt_tokens_details: { cached_tokens: n > 1 ? 80 : 0 } },
    }))
  })
})
await new Promise<void>((r) => server.listen(0, "127.0.0.1", r))
const port = (server.address() as any).port

const tk = await createToolkit({
  builtins: false,
  extraTools: [
    defineTool({ name: "echo", description: "Echo the text back.", run: ({ text }) => String(text) }),
    defineTool({ name: "weather", description: "Weather for a city.", run: ({ city }) => `sunny in ${city}` }),
  ],
})

const seen: string[] = []
const client = createClient({
  baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "stub",
  apiKey: "YOUR_KEY_HERE", // a local or custom baseUrl needs apiKey or apiKeyEnv (0.22)
  promptCache: { key: "session-42" },          // openai: sent as prompt_cache_key
  toolTiers: { core: ["echo"] },               // weather is listed inside tool_search's description
  hooks: { beforeTool: (ev) => { seen.push(ev.name) } },
})

const res = await client.run("weather in Paris?", { toolkit: tk })
console.log("answer        :", res.text)
console.log("tools sent    :", bodies[0].tools.map((t: any) => t.function.name).join(", "))
console.log("cache key     :", bodies[0].prompt_cache_key)
console.log("hook saw      :", seen.join(", "))
console.log("cache read    :", res.usage.cacheReadTokens)
console.log("tier plan     :", JSON.stringify(client.toolTierPlan(tk)))

assert.equal(res.text, "Sunny in Paris.")
assert.deepEqual(bodies[0].tools.map((t: any) => t.function.name), ["echo", "tool_search", "call_tool"])
// The tools array never changes mid-session — that is what keeps the cached prefix valid.
for (const b of bodies) assert.deepEqual(b.tools, bodies[0].tools)
assert.equal(bodies[0].prompt_cache_key, "session-42")
// call_tool is unwrapped before hooks: the permission gate sees the real tool.
assert.ok(seen.includes("weather"))
assert.equal(client.toolTierPlan(tk)?.weather, "listed")
assert.equal(res.usage.cacheReadTokens, 160) // 0 + 80 + 80, summed over the three requests

client.resetToolTiers(tk) // rebuild the plan at the next request (call at session start)
await tk.close()
server.close()
console.log("ok")
