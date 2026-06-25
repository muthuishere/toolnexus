/**
 * Live OpenRouter tool-calling round trip — proves the toolkit drives a real LLM.
 * Reads OPENROUTER_API_KEY from the environment (never hardcode it).
 *
 *   npm run build && OPENROUTER_API_KEY=... node --experimental-strip-types examples/openrouter_test.ts
 *
 * Flow: give the model the toolkit's OpenAI tool schema, ask it to echo a string
 * via the `everything_echo` MCP tool, run the tool call locally, feed the result
 * back, and print the final assistant answer.
 */
import { createToolkit } from "../dist/index.js"

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) {
  console.error("OPENROUTER_API_KEY not set")
  process.exit(1)
}
const MODEL = process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini"
const URL_ = "https://openrouter.ai/api/v1/chat/completions"

const tk = await createToolkit({
  mcpConfig: new URL("../../examples/mcp.json", import.meta.url).pathname,
  skillsDir: new URL("../../examples/skills", import.meta.url).pathname,
})

async function chat(messages: any[]) {
  const res = await fetch(URL_, {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: MODEL, messages, tools: tk.toOpenAI(), tool_choice: "auto" }),
  })
  if (!res.ok) throw new Error(`OpenRouter ${res.status}: ${await res.text()}`)
  return (await res.json()) as any
}

const messages: any[] = [
  { role: "system", content: "You are a tool-using agent. Use the provided tools when helpful.\n\n" + tk.skillsPrompt() },
  { role: "user", content: 'Use the everything_echo tool to echo the exact string "toolnexus works". Then tell me what it returned.' },
]

let final = ""
for (let turn = 0; turn < 5; turn++) {
  const data = await chat(messages)
  const msg = data.choices[0].message
  messages.push(msg)
  const calls = msg.tool_calls ?? []
  if (calls.length === 0) {
    final = msg.content ?? ""
    break
  }
  for (const call of calls) {
    const args = JSON.parse(call.function.arguments || "{}")
    console.log(`-> model called ${call.function.name}(${JSON.stringify(args)})`)
    const result = await tk.execute(call.function.name, args)
    console.log(`<- ${result.isError ? "ERROR" : "ok"}: ${result.output.slice(0, 120)}`)
    messages.push({ role: "tool", tool_call_id: call.id, content: result.output })
  }
}

console.log("\nFINAL ASSISTANT:\n" + final)
await tk.close()

if (!/toolnexus works/i.test(final)) {
  console.error("\n❌ expected echoed string in final answer")
  process.exit(1)
}
console.log("\n✅ JS OpenRouter round trip OK")
