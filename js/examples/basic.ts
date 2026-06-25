/**
 * Minimal end-to-end example. Run after `npm run build` (or with a TS runner):
 *   node --experimental-strip-types examples/basic.ts
 *
 * Uses the shared sample fixtures in ../../examples/.
 */
import { createToolkit } from "../dist/index.js"

const tk = await createToolkit({
  mcpConfig: new URL("../../examples/mcp.json", import.meta.url).pathname,
  skillsDir: new URL("../../examples/skills", import.meta.url).pathname,
})

console.log("MCP status:", tk.mcpStatus())
console.log(
  "Tools:",
  tk.tools().map((t) => `${t.name} (${t.source})`),
)
console.log("\nSystem-prompt skill catalog:\n" + tk.skillsPrompt())

console.log("\nOpenAI tool schema (first 2):")
console.log(JSON.stringify(tk.toOpenAI().slice(0, 2), null, 2))

// Load a skill (progressive disclosure) — works with no MCP servers running.
const res = await tk.execute("skill", { name: "hello-world" })
console.log("\nskill(hello-world) ->\n" + res.output)

await tk.close()
