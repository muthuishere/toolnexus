/**
 * ACP (Agent Client Protocol) as the model behind the client loop — issue #96,
 * ADR 0031 (openspec/changes/add-acp-model-source).
 *
 * HONEST HEADER: the "warm session" win this example prints is the ACP agent
 * CLI's process-startup cost amortised across turns, NOT a protocol-level
 * speedup — session/prompt itself is not faster than any other wire. See
 * ADR 0031's measurements.
 *
 * Spawns a real ACP agent CLI (devin or opencode), registers one trivial
 * local tool, and drives it through the ordinary toolnexus tool-calling loop
 * for two turns — proving MCP, skills and native tools work unchanged when
 * the model behind the loop is an ACP agent instead of an HTTP LLM. Requires
 * the agent CLI installed and authenticated on PATH; it is NOT hermetic and
 * is NOT run by CI.
 *
 * Run after `npm run build`:
 *   node --experimental-strip-types examples/acp.ts                         # spawns `devin acp` (default)
 *   TOOLNEXUS_ACP_CMD="opencode acp" node --experimental-strip-types examples/acp.ts   # or opencode instead
 */
import { createToolkit, defineTool, loadACP, createInProcessClient } from "../dist/index.js"

// Agent command is selectable: TOOLNEXUS_ACP_CMD (default "devin acp"). Both
// devin and opencode speak ACP live — `devin acp` and `opencode acp` both
// answer `initialize` with protocolVersion 1.
const acpCmd = process.env.TOOLNEXUS_ACP_CMD ?? "devin acp"
const [command, ...args] = acpCmd.split(/\s+/)

const tk = await createToolkit({
  mcpConfig: new URL("../../examples/mcp.json", import.meta.url).pathname,
  skillsDir: new URL("../../examples/skills", import.meta.url).pathname,
})

// a trivial native tool — proves tool-calling works unchanged through ACP
tk.register(
  defineTool({
    name: "clock",
    description: "Return the current UTC time.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    run: () => new Date().toISOString(),
  }),
)

console.log(`Spawning ACP agent: ${command} ${args.join(" ")}`)
let acp
try {
  acp = await loadACP({ command, args, cwd: process.cwd() })
} catch (err) {
  console.error("acp connect failed (is the CLI installed + authenticated?):", err)
  await tk.close()
  process.exit(1)
}

const agent = createInProcessClient({
  model: command,
  generate: acp.generate,
  systemPrompt: "You are a precise agent. Use tools to compute and fetch facts.",
})

const turns = [
  "What time is it right now? Use the clock tool.",
  "What did the clock tool just return, verbatim?",
]

// Two turns on the SAME warm ACP session/process — this is the whole point:
// the process-startup cost was paid once by loadACP, not per turn.
for (const [i, prompt] of turns.entries()) {
  const start = Date.now()
  const res = await agent.run(prompt, { toolkit: tk })
  const elapsed = Date.now() - start
  console.log(`\n--- turn ${i + 1} (${elapsed}ms) ---`)
  console.log("prompt:", prompt)
  if (res.toolCalls.length > 0) {
    console.log(
      "tool calls:",
      res.toolCalls.map((c) => c.name),
    )
  }
  console.log("answer:", res.text.trim())
}

await acp.close()
await tk.close()
console.log(`\n✅ JS ACP example OK — warm session across ${turns.length} turns`)
