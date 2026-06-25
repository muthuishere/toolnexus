/**
 * Lifecycle hooks: beforeLLM / afterLLM / beforeTool / afterTool — observe, mutate,
 * and short-circuit. Run:
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/hooks.ts
 */
import { createToolkit, defineTool, httpTool, createClient, type ToolResult } from "../dist/index.js"

const tk = await createToolkit({})
let httpHits = 0
tk.register(
  defineTool({
    name: "add", description: "Add two numbers.",
    inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
    run: ({ a, b }) => `${Number(a) + Number(b)}`,
  }),
  httpTool({
    name: "get_post", description: "Fetch a post by id.", method: "GET",
    url: "https://jsonplaceholder.typicode.com/posts/{id}",
    inputSchema: { type: "object", properties: { id: { type: "number" } }, required: ["id"] },
  }),
)
// spy on whether get_post's real HTTP ever runs (it must NOT — we deny it in a hook)
const realGetPost = tk.get("get_post")!
const orig = realGetPost.execute.bind(realGetPost)
realGetPost.execute = async (a, c) => { httpHits++; return orig(a, c) }

const counts = { beforeLLM: 0, afterLLM: 0, beforeTool: 0, afterTool: 0, denied: 0 }
const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) { console.log("no OPENROUTER_API_KEY — skipping"); process.exit(0) }

const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai",
  model: process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini",
  apiKey: KEY,
  systemPrompt: "You are an agent. Use tools when asked.",
  hooks: {
    beforeLLM: ({ turn }) => { counts.beforeLLM++; console.log(`  [beforeLLM] turn ${turn}`) },
    afterLLM: ({ response, turn }) => { counts.afterLLM++; console.log(`  [afterLLM] turn ${turn} usage`, response.usage) },
    beforeTool: ({ name, args }) => {
      counts.beforeTool++
      console.log(`  [beforeTool] ${name}(${JSON.stringify(args)})`)
      if (name === "get_post") {
        counts.denied++
        // SHORT-CIRCUIT: deny the tool, model never sees real data, http never runs
        return { result: { output: "DENIED: get_post is blocked by policy", isError: true } as ToolResult }
      }
    },
    afterTool: ({ name, result }) => { counts.afterTool++; console.log(`  [afterTool] ${name} -> ${result.output.slice(0, 40)}`) },
  },
})

const out = await agent.run("Add 2 and 3 with the add tool, then fetch post id 1 with get_post.", { toolkit: tk })
await tk.close()

console.log("\nFINAL:", out.text.replace(/\n/g, " ").slice(0, 100))
console.log("hook counts:", counts, "| http actually hit:", httpHits, "| usage:", out.usage)

const ok =
  counts.beforeLLM >= 1 && counts.afterLLM >= 1 &&
  counts.beforeTool >= 2 && counts.afterTool >= 1 &&
  counts.denied >= 1 && httpHits === 0   // deny short-circuited the real HTTP call
if (!ok) { console.error("❌ hooks did not all fire / short-circuit failed"); process.exit(1) }
console.log("\n✅ all four hooks fired; beforeTool short-circuited get_post (0 real HTTP hits)")
