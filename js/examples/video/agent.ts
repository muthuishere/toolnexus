import { createToolkit, defineTool, createClient, agent } from "toolnexus"
import { scriptedModel, serveReviewer } from "./model.ts"

// A separate agent — its own toolkit — running behind an A2A endpoint:
const reviewer = await serveReviewer()

// Assemble every tool source into ONE toolkit:
const tk = await createToolkit({
	mcpConfig: "../../../examples/mcp.json", // MCP servers -> their tools
	skillsDir: "../../../examples/skills", //  agent skills -> the `skill` tool
	agents: [agent({ card: reviewer.card })], // a remote A2A agent -> a tool
	// built-in tools (bash, read, question, …) are on by default
})

// ...plus one of your own plain functions:
tk.register(
	defineTool({
		name: "add",
		description: "Add two numbers.",
		inputSchema: {
			type: "object",
			properties: { a: { type: "number" }, b: { type: "number" } },
			required: ["a", "b"],
		},
		run: ({ a, b }) => `${Number(a) + Number(b)}`,
	}),
)

// A client whose loop calls tools — and pauses to ask YOU when one suspends:
const client = createClient({
	...(await scriptedModel()),
	waitFor: async (req) => {
		console.log(`  ⏸  agent asks: ${req.prompt}`)
		return { id: req.id, ok: true, data: { answers: ["yes"] } } // you answer -> it resumes
	},
})

const res = await client.run("Do the whole demo.", { toolkit: tk })

console.log("\ntool calls, in order:")
for (const c of res.toolCalls) {
	const out = c.output.replace(/\s+/g, " ").trim()
	console.log(`  • ${c.name} -> ${out.length > 52 ? out.slice(0, 52) + '…' : out}`)
}
console.log(`\n${res.text}`)

await tk.close()
await reviewer.stop()
