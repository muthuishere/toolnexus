import { createToolkit, defineTool } from "toolnexus"
import { oneShotClient } from "./oneshot.mjs"
const add = defineTool({ name: "add", description: "Add two numbers.",
  inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
  run: ({ a, b }) => `${a + b}` })
const tk = await createToolkit({ builtins: false, extraTools: [add] })
const [command, ...args] = process.argv.slice(2)
const r = await oneShotClient({ command, args }).run("Use the add tool to add 19 and 23, then tell me the sum.", { toolkit: tk })
console.log("text:", JSON.stringify(r.text), "toolCalls:", JSON.stringify(r.toolCalls))
