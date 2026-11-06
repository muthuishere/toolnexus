import { createInProcessClient, createToolkit, defineTool } from "toolnexus"

const add = defineTool({
  name: "add", description: "Add two numbers.",
  inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
  run: ({ a, b }) => `${a + b}`,
})

// Your "model": first turn asks for a tool, second turn streams the answer.
const client = createInProcessClient({
  model: "my-model",
  generateStream: async (req, emit) => {
    const last = req.messages.at(-1)
    if (last.role !== "tool") return { toolCalls: [{ name: "add", arguments: { a: 2, b: 3 } }] }
    const words = ["The ", "sum ", "is ", String(last.content), "."]
    for (const w of words) emit({ type: "text", delta: w }) // deltas must add up to content
    return { content: words.join("") }
  },
})

const toolkit = await createToolkit({ builtins: false, extraTools: [add] })
for await (const ev of client.stream("What is 2 + 3?", { toolkit })) {
  if (ev.type === "text") process.stdout.write(ev.delta)
  else if (ev.type === "tool_call") console.log(`[calling ${ev.name}]`)
}
console.log() // The sum is 5.
