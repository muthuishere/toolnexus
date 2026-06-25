/**
 * Streaming: live text deltas + tool_call / tool_result / usage / done events.
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/streaming.ts
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

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) { console.log("no OPENROUTER_API_KEY — skipping"); process.exit(0) }
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1", style: "openai",
  model: process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini", apiKey: KEY,
})

let textDeltas = 0, streamedText = "", toolCallEv = 0, toolResultEv = 0
let done: any
process.stdout.write("stream: ")
for await (const ev of agent.stream("Use the add tool to compute 21 + 21, then say the result in one short sentence.", { toolkit: tk })) {
  if (ev.type === "text") { textDeltas++; streamedText += ev.delta; process.stdout.write(ev.delta) }
  else if (ev.type === "tool_call") { toolCallEv++; console.log(`\n[tool_call] ${ev.name}(${JSON.stringify(ev.args)})`) }
  else if (ev.type === "tool_result") { toolResultEv++; console.log(`[tool_result] ${ev.name} -> ${ev.output}`) }
  else if (ev.type === "usage") console.log(`[usage] ${JSON.stringify(ev.usage)}`)
  else if (ev.type === "done") done = ev.result
}
await tk.close()

console.log(`\n\nsummary: textDeltas=${textDeltas} toolCall=${toolCallEv} toolResult=${toolResultEv} | done.toolCallCount=${done?.toolCallCount} turns=${done?.turns} usage=${JSON.stringify(done?.usage)}`)
const ok = textDeltas > 1 && toolCallEv >= 1 && toolResultEv >= 1 && done && done.usage.totalTokens > 0 && done.text.includes("42")
if (!ok) { console.error("❌ streaming did not produce deltas + tool events + final usage/answer"); process.exit(1) }
console.log("✅ streaming verified: incremental text deltas, tool_call/tool_result events, final done with usage")
