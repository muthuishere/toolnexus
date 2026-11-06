// usage: node main.mjs npx -y @agentclientprotocol/claude-agent-acp@0.100.0
import { createInProcessClient, createToolkit, defineTool, loadACP } from "toolnexus"

const add = defineTool({
  name: "add", description: "Add two numbers.",
  inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
  run: ({ a, b }) => `${a + b}`,
})
const toolkit = await createToolkit({ builtins: false, extraTools: [add] })
const server = await toolkit.asMcpServer()

// Permission policy: allow tool calls whose title names one of your tools, reject the rest.
// Return an optionId to pick it; return undefined to answer with the first reject option.
function allowOnly(names) {
  return (params) => {
    const title = params.toolCall?.title ?? ""
    const ok = names.some((n) => title.includes(n))
    return params.options.find((o) => o.kind.startsWith(ok ? "allow" : "reject"))?.optionId
  }
}

const [command, ...args] = process.argv.slice(2)
const agent = await loadACP({
  command, args,
  mcpServers: [{ type: "http", name: "toolnexus", url: server.url,
                 headers: [{ name: "Authorization", value: `Bearer ${server.token}` }] }],
  // Agent-specific knobs are opaque data: this one isolates Claude Code (see the per-agent notes).
  sessionMeta: { claudeCode: { options: { settingSources: [], strictMcpConfig: true, plugins: [], skills: [], tools: [] } } },
  model: process.env.ACP_MODEL,                    // e.g. "haiku"; passed through, never validated
  onPermission: allowOnly(toolkit.tools().map((t) => t.name)),
  onUpdate: (u) => { if (u.update?.sessionUpdate === "usage_update") console.error("[usage]", JSON.stringify(u.update)) },
  permissionTimeoutMs: 300_000,
})

const client = createInProcessClient({ model: command, generate: agent.generate })
try {
  const result = await client.run("Use the add tool to add 17 and 25. Reply with just the sum.", { toolkit: await createToolkit({ builtins: false }) })
  console.log(result.text)
} finally {
  await agent.close()
  await server.close()
}
