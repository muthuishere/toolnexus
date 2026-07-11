/**
 * combined.ts — every tool source in ONE deterministic run.
 *
 *   a plain function  ·  an MCP server  ·  an agent skill  ·  a remote A2A agent
 *   ...and then the agent pauses to ask YOU a question, and resumes.
 *
 * The model is a local scripted stub (no API key, no cost, identical every run),
 * so this is also the exact script the demo video is recorded from.
 *
 *   npm run build && node --experimental-strip-types examples/combined.ts
 */
import http from "node:http"
import assert from "node:assert/strict"
import {
	createToolkit,
	defineTool,
	createClient,
	agent,
	type Answer,
	type Request,
} from "../dist/index.js"

const MCP = new URL("../../examples/mcp.json", import.meta.url).pathname
const SKILLS = new URL("../../examples/skills", import.meta.url).pathname

// ── a tiny mock OpenAI endpoint: returns tool-calls / final text on demand ──
function mockLLM(handler: (messages: any[]) => any): http.Server {
	return http.createServer((req, res) => {
		let body = ""
		req.on("data", (c) => (body += c))
		req.on("end", () => {
			const msg = handler(JSON.parse(body).messages)
			res.writeHead(200, { "content-type": "application/json" })
			res.end(
				JSON.stringify({
					choices: [{ message: msg }],
					usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
				}),
			)
		})
	})
}
const listen = (s: http.Server): Promise<number> =>
	new Promise((r) => s.listen(0, () => r((s.address() as any).port)))
let _id = 0
const toolCall = (name: string, args: any) => ({
	role: "assistant",
	content: null,
	tool_calls: [
		{ id: "c" + _id++, type: "function", function: { name, arguments: JSON.stringify(args) } },
	],
})
const final = (t: string) => ({ role: "assistant", content: t })

// ── 1. the SERVED (inner) A2A agent: its own skill-bearing toolkit + trivial LLM ──
const innerLLM = mockLLM(() => final("REVIEWED: looks good to ship."))
const innerPort = await listen(innerLLM)
const servedTk = await createToolkit({ skillsDir: SKILLS, builtins: false }) // card exposes `hello-world`
const innerClient = createClient({
	baseUrl: `http://127.0.0.1:${innerPort}`,
	style: "openai",
	model: "inner",
	apiKey: "k",
})
const handle = await servedTk.serve("127.0.0.1:0", { client: innerClient, a2a: { name: "reviewer" } })

// ── 2. the OUTER toolkit: function + MCP + skill + A2A + builtins(question) ──
const outerTk = await createToolkit({
	mcpConfig: MCP, //                                     2 — MCP server tools
	skillsDir: SKILLS, //                                  3 — the `skill` tool
	agents: [agent({ card: handle.url + "/.well-known/agent-card.json", pollEvery: 5 })], // 4 — A2A
	// builtins default ON →                               5 — the `question` tool is present
})
outerTk.register(
	defineTool({
		//                                                  1 — a plain function tool (source: "native")
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

// discover the runtime names of the MCP + A2A tools (don't hardcode)
const mcpTool = outerTk.tools().find((t) => t.source === "mcp")!.name //  e.g. everything_echo
const a2aTool = outerTk.tools().find((t) => t.source === "a2a")!.name //  e.g. reviewer_hello-world

// ── 3. the SCRIPTED outer model: walk the plan by counting prior tool-call turns ──
const plan = [
	() => toolCall("add", { a: 21, b: 21 }), //                        step 0: function tool
	() => toolCall(mcpTool, { message: "hi from toolnexus" }), //      step 1: MCP tool
	() => toolCall("skill", { name: "hello-world" }), //              step 2: agent skill
	() => toolCall(a2aTool, { task: "please review the plan" }), //   step 3: A2A remote agent
	() =>
		toolCall("question", {
			questions: [{ question: "Ship it?", options: ["yes", "no"] }],
		}), //                                                          step 4: the suspension
]
const outerLLM = mockLLM((messages) => {
	const step = messages.filter(
		(m: any) => m.role === "assistant" && Array.isArray(m.tool_calls) && m.tool_calls.length,
	).length
	return step < plan.length
		? plan[step]()
		: final("All five sources exercised — you chose to ship. Done.")
})
const outerPort = await listen(outerLLM)

// ── 4. the client: waitFor answers the question suspension so the loop resumes ──
const client = createClient({
	baseUrl: `http://127.0.0.1:${outerPort}`,
	style: "openai",
	model: "outer",
	apiKey: "k",
	waitFor: async (req: Request): Promise<Answer> => {
		console.log(`  ↪ agent suspended (${req.kind}): ${req.prompt}`)
		return { id: req.id, ok: true, data: { answers: ["yes"] } } // you answer → it resumes
	},
})

console.log("Running the whole demo (deterministic, no live LLM)…\n")
const res = await client.run("Do the whole demo.", { toolkit: outerTk })

console.log("\ntool calls in order:")
for (const c of res.toolCalls) console.log(`  • ${c.name} → ${c.output}`)
console.log(`\nstatus: ${res.status} · turns: ${res.turns}`)
console.log(`final:  ${res.text}`)

assert.equal(res.status, "done")
assert.deepEqual(
	res.toolCalls.map((c) => c.name),
	["add", mcpTool, "skill", a2aTool, "question"],
)

await outerTk.close()
await servedTk.close()
await handle.stop()
outerLLM.close()
innerLLM.close()
console.log("\n✅ function + MCP + skill + A2A + question-suspension — one deterministic run")
