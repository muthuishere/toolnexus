/**
 * Anthropic-style endpoint: same toolkit, style:"anthropic" against api.anthropic.com.
 *   ANTHROPIC_API_KEY=... node --experimental-strip-types examples/anthropic.ts
 */
import { createToolkit, defineTool, createClient } from "../dist/index.js"

const tk = await createToolkit({})
tk.register(
  defineTool({
    name: "add", description: "Add two numbers.",
    inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
    run: ({ a, b }) => `${Number(a) + Number(b)}`,
  }),
)

const KEY = process.env.ANTHROPIC_API_KEY
if (!KEY) { console.log("no ANTHROPIC_API_KEY — skipping"); process.exit(0) }

const agent = createClient({
  baseUrl: "https://api.anthropic.com",
  style: "anthropic",
  model: process.env.ANTHROPIC_MODEL ?? "claude-haiku-4-5-20251001",
  apiKey: KEY,
})

// 1) non-streaming run with a tool call
const out = await agent.run("Use the add tool to compute 21 + 21, then state the result in one sentence.", { toolkit: tk })
console.log("run:", out.text.replace(/\n/g, " ").slice(0, 80))
console.log("toolCalls:", out.toolCalls.map((c) => `${c.name}=${c.output}`), "| usage:", out.usage, "| turns:", out.turns)

// 2) streaming
let deltas = 0, toolEv = 0
process.stdout.write("stream: ")
for await (const ev of agent.stream("Add 100 and 200 with the add tool, then say the total.", { toolkit: tk })) {
  if (ev.type === "text") { deltas++; process.stdout.write(ev.delta) }
  else if (ev.type === "tool_call") { toolEv++; console.log(`\n[tool_call] ${ev.name}(${JSON.stringify(ev.args)})`) }
  else if (ev.type === "tool_result") console.log(`[tool_result] ${ev.name} -> ${ev.output}`)
}
console.log()

await tk.close()
const ok = out.text.includes("42") && out.usage.totalTokens > 0 && out.toolCalls.length >= 1 && deltas > 0 && toolEv >= 1
if (!ok) { console.error("❌ anthropic-style run/stream did not work end to end"); process.exit(1) }
console.log("\n✅ Anthropic-style endpoint verified: tool calling (run) + streaming both work")
