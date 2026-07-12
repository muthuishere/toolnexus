/**
 * model.ts — the deterministic plumbing behind the demo video.
 *
 * This file is NOT what the video is about — it is the scaffolding that lets
 * `agent.ts` run with ZERO API key and produce byte-identical output on every
 * take (so re-recording is safe). It hides two ugly-but-boring things:
 *
 *   1. a local mock OpenAI endpoint that walks a fixed tool-calling plan, and
 *   2. a second toolkit served over A2A so `agent.ts` has a real remote agent
 *      to call.
 *
 * `agent.ts` imports `scriptedModel()` and `serveReviewer()` from here and
 * otherwise uses only the real toolnexus API. Every server is `.unref()`-ed so
 * the process exits cleanly once the run resolves.
 */
import http from "node:http"
import { createToolkit, createClient } from "toolnexus"

const SKILLS = new URL("../../../examples/skills", import.meta.url).pathname

// ── a tiny mock OpenAI endpoint ────────────────────────────────────────────
function mockLLM(handler: (body: any) => any): http.Server {
	const s = http.createServer((req, res) => {
		let body = ""
		req.on("data", (c) => (body += c))
		req.on("end", () => {
			const msg = handler(JSON.parse(body))
			res.writeHead(200, { "content-type": "application/json" })
			res.end(
				JSON.stringify({
					choices: [{ message: msg }],
					usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
				}),
			)
		})
	})
	return s
}
const listen = (s: http.Server): Promise<number> =>
	new Promise((r) =>
		s.listen(0, () => {
			s.unref() // don't keep the event loop alive
			r((s.address() as any).port)
		}),
	)

let _id = 0
const toolCall = (name: string, args: any) => ({
	role: "assistant",
	content: null,
	tool_calls: [
		{ id: "c" + _id++, type: "function", function: { name, arguments: JSON.stringify(args) } },
	],
})
const final = (t: string) => ({ role: "assistant", content: t })

/** Start the inner "reviewer" toolkit and serve it over A2A. Returns its card URL. */
export async function serveReviewer(): Promise<{ card: string; stop: () => Promise<void> }> {
	const inner = mockLLM(() => final("REVIEWED: looks good to ship."))
	const innerPort = await listen(inner)
	const tk = await createToolkit({ skillsDir: SKILLS, builtins: false })
	const client = createClient({
		baseUrl: `http://127.0.0.1:${innerPort}`,
		style: "openai",
		model: "reviewer",
		apiKey: "k",
	})
	const handle = await tk.serve("127.0.0.1:0", { client, a2a: { name: "reviewer" } })
	return {
		card: handle.url + "/.well-known/agent-card.json",
		stop: async () => {
			await handle.stop()
			await tk.close()
			inner.close()
		},
	}
}

/**
 * A scripted local model: on each turn it looks at how many tool-calls have
 * already happened and returns the next step of a fixed plan. MCP + A2A tool
 * names are discovered from the request's `tools` array, so nothing is
 * hardcoded. Returns the client options `agent.ts` spreads into createClient.
 */
export async function scriptedModel(): Promise<{
	baseUrl: string
	style: "openai"
	model: string
	apiKey: string
}> {
	const server = mockLLM((body) => {
		const names: string[] = (body.tools ?? []).map((t: any) => t.function?.name ?? "")
		const mcp = names.find((n) => n.includes("echo")) ?? "echo"
		const a2a = names.find((n) => n.startsWith("reviewer")) ?? "reviewer"
		const step = (body.messages ?? []).filter(
			(m: any) => m.role === "assistant" && Array.isArray(m.tool_calls) && m.tool_calls.length,
		).length
		const plan = [
			() => toolCall("add", { a: 21, b: 21 }), //                   your function
			() => toolCall(mcp, { message: "hi from toolnexus" }), //     an MCP tool
			() => toolCall("skill", { name: "hello-world" }), //          an agent skill
			() => toolCall(a2a, { task: "please review the plan" }), //   a remote A2A agent
			() =>
				toolCall("question", {
					questions: [{ question: "Ship it?", options: ["yes", "no"] }],
				}), //                                                     the suspension
		]
		return step < plan.length
			? plan[step]()
			: final("All five sources exercised — you chose to ship. Done.")
	})
	const port = await listen(server)
	return { baseUrl: `http://127.0.0.1:${port}`, style: "openai", model: "demo", apiKey: "k" }
}
