/**
 * Verify parallel tool calling (many calls in one turn) and chained tool calling
 * (a later call depends on an earlier result, across turns).
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/advanced.ts
 */
import { createToolkit, defineTool, httpTool, createClient } from "../dist/index.js"

const tk = await createToolkit({})
tk.register(
  defineTool({
    name: "add",
    description: "Add two numbers and return the sum.",
    inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
    run: ({ a, b }) => `${Number(a) + Number(b)}`,
  }),
  httpTool({
    name: "get_post",
    description: "Fetch a placeholder blog post by id.",
    method: "GET",
    url: "https://jsonplaceholder.typicode.com/posts/{id}",
    inputSchema: { type: "object", properties: { id: { type: "number" } }, required: ["id"] },
  }),
  // opaque: the model CANNOT guess this value, so it must call it first and wait
  defineTool({
    name: "todays_post_id",
    description: "Returns the server-chosen blog post id to read today. Cannot be guessed; you must call it.",
    run: () => "3",
  }),
)

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) {
  console.log("no OPENROUTER_API_KEY — skipping")
  process.exit(0)
}
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai",
  model: process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini",
  apiKey: KEY,
  systemPrompt: "You are a precise agent. Prefer issuing independent tool calls together in one step.",
})

/** Largest number of tool calls the model emitted in a single assistant turn. */
function maxParallel(messages: any[]): number {
  let m = 0
  for (const msg of messages) if (msg.role === "assistant" && Array.isArray(msg.tool_calls)) m = Math.max(m, msg.tool_calls.length)
  return m
}
/** Number of assistant turns that issued tool calls (chain depth). */
function toolTurns(messages: any[]): number {
  return messages.filter((m) => m.role === "assistant" && Array.isArray(m.tool_calls) && m.tool_calls.length).length
}

// ---- A) PARALLEL: two independent adds, ideally in one turn ----
const a = await agent.run(
  "In a single step, call the add tool twice: add 2 and 3, and add 100 and 200. Then report both sums.",
  { toolkit: tk },
)
console.log("A) parallel — tool calls:", a.toolCalls.map((c) => `${c.name}(${JSON.stringify(c.args)})`))
console.log("A) max calls in one turn:", maxParallel(a.messages), "| answer:", a.text.replace(/\n/g, " ").slice(0, 80))

// ---- B) CHAIN: second call depends on an OPAQUE first result (forces a 2nd turn) ----
const b = await agent.run(
  "Call todays_post_id to find which post to read, then use get_post to fetch that exact id and tell me its title.",
  { toolkit: tk },
)
console.log("\nB) chain — tool calls:", b.toolCalls.map((c) => `${c.name}(${JSON.stringify(c.args)})`))
console.log("B) tool-calling turns (chain depth):", toolTurns(b.messages))
console.log("B) answer:", b.text.replace(/\n/g, " ").slice(0, 100))

await tk.close()

// ---- assertions ----
const parallelOK = maxParallel(a.messages) >= 2
const calledAdd = a.toolCalls.filter((c) => c.name === "add").length >= 2
// true chain: todays_post_id THEN get_post(id=3) across ≥2 turns (id=3 is unguessable)
const chainOK =
  toolTurns(b.messages) >= 2 &&
  b.toolCalls.some((c) => c.name === "todays_post_id") &&
  b.toolCalls.some((c) => c.name === "get_post" && Number(c.args.id) === 3)
console.log(`\nparallel: ${parallelOK ? "✅ multiple calls in one turn" : "⚠️ model serialized"} | ran ${a.toolCalls.length} adds`)
console.log(`chain:    ${chainOK ? "✅ get_post(id=3) used the opaque result across turns" : "❌ chain not observed"}`)
if (!calledAdd || !parallelOK || !chainOK) {
  console.error("❌ expected ≥2 parallel adds AND a real cross-turn chain (todays_post_id → get_post(id=3))")
  process.exit(1)
}
console.log("\n✅ parallel + chained tool calling verified")
