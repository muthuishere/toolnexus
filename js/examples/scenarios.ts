/**
 * Tool-calling scenario matrix: single · multi · parallel · complex chain.
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/scenarios.ts
 */
import { createToolkit, defineTool, httpTool, createClient } from "../dist/index.js"

const tk = await createToolkit({})
tk.register(
  defineTool({
    name: "add", description: "Add two numbers.",
    inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
    run: ({ a, b }) => `${Number(a) + Number(b)}`,
  }),
  httpTool({
    name: "get_post", description: "Fetch a blog post by id; returns JSON with a title.", method: "GET",
    url: "https://jsonplaceholder.typicode.com/posts/{id}",
    inputSchema: { type: "object", properties: { id: { type: "number" } }, required: ["id"] },
  }),
  defineTool({
    name: "todays_post_id", description: "Returns the server-chosen post id to read today. Unguessable; must be called.",
    run: () => "3",
  }),
)

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) { console.log("no OPENROUTER_API_KEY — skipping"); process.exit(0) }
const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1", style: "openai",
  model: process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini", apiKey: KEY,
  systemPrompt: "You are a precise agent. Use tools; issue independent calls together in one step.",
})

const maxParallel = (m: any[]) => m.reduce((x, msg) => msg.role === "assistant" && Array.isArray(msg.tool_calls) ? Math.max(x, msg.tool_calls.length) : x, 0)
const toolTurns = (m: any[]) => m.filter((x) => x.role === "assistant" && Array.isArray(x.tool_calls) && x.tool_calls.length).length
const names = (r: any) => r.toolCalls.map((c: any) => c.name)

let allOk = true
function check(label: string, cond: boolean, detail: string) {
  allOk &&= cond
  console.log(`${cond ? "✅" : "❌"} ${label} — ${detail}`)
}

// 1) SINGLE: exactly one tool call
const s = await agent.run("Use the add tool to compute 7 + 8. Nothing else.", { toolkit: tk })
check("single", s.toolCalls.length === 1 && s.toolCalls[0].name === "add" && s.text.includes("15"),
  `calls=${JSON.stringify(names(s))} turns=${s.turns} tokens=${s.usage.totalTokens}`)

// 2) MULTI: two different tools used
const m = await agent.run("Compute 4 + 5 with add, and separately fetch post id 1 with get_post and give its title.", { toolkit: tk })
const tools = new Set(names(m))
check("multi (distinct tools)", tools.has("add") && tools.has("get_post"),
  `calls=${JSON.stringify(names(m))} distinct=${[...tools].length} turns=${m.turns}`)

// 3) PARALLEL: ≥2 calls in a single turn
const p = await agent.run("In a single step call add twice: add 2 and 3, and add 100 and 200.", { toolkit: tk })
check("parallel (one turn)", maxParallel(p.messages) >= 2 && names(p).filter((n: string) => n === "add").length >= 2,
  `maxCallsInOneTurn=${maxParallel(p.messages)} calls=${JSON.stringify(names(p))}`)

// 4) COMPLEX: dependent chain across 3 tools and ≥2 turns
const c = await agent.run(
  "Call todays_post_id to get an id. Fetch that post with get_post. Then use add to add that id to 100 and tell me the final total.",
  { toolkit: tk },
)
const cn = names(c)
check("complex (dependent multi-tool chain)",
  cn.includes("todays_post_id") && cn.includes("get_post") && cn.includes("add") && toolTurns(c.messages) >= 2 && c.text.includes("103"),
  `calls=${JSON.stringify(cn)} turns=${c.turns} chainDepth=${toolTurns(c.messages)} answerHas103=${c.text.includes("103")}`)

await tk.close()
console.log(allOk ? "\n✅ single + multi + parallel + complex all working" : "\n❌ some scenario failed")
if (!allOk) process.exit(1)
