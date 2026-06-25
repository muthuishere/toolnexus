/**
 * Full agent example: MCP tools + skills + a native tool + an HTTP tool, driven by
 * the unified client host loop. Run after `npm run build`:
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/agent.ts
 */
import { createToolkit, defineTool, httpTool, createClient } from "../dist/index.js"

const tk = await createToolkit({
  mcpConfig: new URL("../../examples/mcp.json", import.meta.url).pathname,
  skillsDir: new URL("../../examples/skills", import.meta.url).pathname,
})

// a native (annotation) tool
tk.register(
  defineTool({
    name: "add",
    description: "Add two numbers and return the sum.",
    inputSchema: {
      type: "object",
      properties: { a: { type: "number" }, b: { type: "number" } },
      required: ["a", "b"],
      additionalProperties: false,
    },
    run: ({ a, b }) => `${Number(a) + Number(b)}`,
  }),
)

// an HTTP tool (public test API, no auth)
tk.register(
  httpTool({
    name: "get_post",
    description: "Fetch a placeholder blog post by id from jsonplaceholder.",
    method: "GET",
    url: "https://jsonplaceholder.typicode.com/posts/{id}",
    inputSchema: {
      type: "object",
      properties: { id: { type: "number", description: "post id 1-100" } },
      required: ["id"],
      additionalProperties: false,
    },
  }),
)

console.log(
  "Tools:",
  tk.tools().map((t) => `${t.name} (${t.source})`),
)

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) {
  console.log("\n(no OPENROUTER_API_KEY — skipping live loop)")
  await tk.close()
  process.exit(0)
}

const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai",
  model: process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini",
  apiKey: KEY,
  systemPrompt: "You are a precise agent. Use tools to compute and fetch facts.",
})

const res = await agent.run("What is 21 + 21? Use the add tool. Then fetch post id 1 and tell me its title.", {
  toolkit: tk,
})
console.log("\nTool calls:", res.toolCalls.map((c) => c.name))
console.log("\nFINAL:\n" + res.text)

await tk.close()
if (!res.text.includes("42")) {
  console.error("❌ expected 42 in answer")
  process.exit(1)
}
console.log("\n✅ JS agent loop (native + http + mcp + skills) OK")
