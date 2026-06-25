/**
 * Conversation memory: a Conversation retains history across send() calls.
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/memory.ts
 */
import { createToolkit, createClient } from "../dist/index.js"

const tk = await createToolkit({})
const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) { console.log("no OPENROUTER_API_KEY — skipping"); process.exit(0) }

const agent = createClient({
  baseUrl: "https://openrouter.ai/api/v1", style: "openai",
  model: process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini", apiKey: KEY,
})

const convo = agent.conversation({ toolkit: tk })

const a = await convo.send("My name is Muthu and my favorite number is 7. Reply with just 'noted'.")
console.log("turn 1:", a.text.replace(/\n/g, " ").slice(0, 60), "| messages:", convo.messages.length)

const b = await convo.send("What is my name and favorite number?")
console.log("turn 2:", b.text.replace(/\n/g, " ").slice(0, 80), "| messages:", convo.messages.length)

await tk.close()
const remembered = /muthu/i.test(b.text) && /7|seven/i.test(b.text)
if (!remembered) { console.error("❌ conversation did not retain memory across turns"); process.exit(1) }
console.log("\n✅ conversation memory verified — turn 2 recalled facts from turn 1")
