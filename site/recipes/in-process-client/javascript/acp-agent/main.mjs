// usage: node main.mjs npx -y @agentclientprotocol/claude-agent-acp@0.100.0
import { createInProcessClient, createToolkit, defineTool, loadACP } from "toolnexus"

const add = defineTool({
  name: "add", description: "Add two numbers.",
  inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
  run: ({ a, b }) => { console.log(`\n[tool] add(${a}, ${b})`); return `${a + b}` },
})

// 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
const toolkit = await createToolkit({ builtins: false, extraTools: [add] })
const server = await toolkit.asMcpServer()

// 2. Start the agent; mcpServers is sent to session/new exactly as written.
const [command, ...args] = process.argv.slice(2)
const agent = await loadACP({
  command, args,
  mcpServers: [{ type: "http", name: "toolnexus", url: server.url,
                 headers: [{ name: "Authorization", value: `Bearer ${server.token}` }] }],
  permissionTimeoutMs: 300_000, // one turn of a real agent can take minutes
})

// 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
const client = createInProcessClient({ model: command, generateStream: agent.generateStream })
const empty = await createToolkit({ builtins: false })
try {
  for await (const ev of client.stream("Use the add tool to add 17 and 25. Reply with just the sum.", { toolkit: empty })) {
    if (ev.type === "text") process.stdout.write(ev.delta)          // live text as the agent writes it
    else if (ev.type === "tool_call" && ev.via === "acp") console.log(`\n[agent tool] ${ev.name}`)
    else if (ev.type === "done") console.log("\nfinal:", ev.result.text)
  }
} finally {
  await agent.close()  // ends the agent's whole process group
  await server.close()
}
