// Spike for issue #89, JS port — the same four questions asked of `js/`.
//
// The Go port resumes a durably-halted run with `RunWithAnswer(history,
// pending, answer)`, which honours ONLY answer.data.output / .results.
// This file checks (1) whether that API exists in JS at all, and (2) what the
// inline waitFor path and the DOCUMENTED two-phase flow actually deliver.
//
// No network, no API key: the LLM is an injected `fetch`.
import { createClient, createToolkit, defineTool, pending, InMemoryConversationStore }
  from "../../../js/dist/index.js"

// ---------- mock LLM (OpenAI wire style) ----------
const mockFetch = async (_url, init) => {
  const body = JSON.parse(init.body)
  const last = [...body.messages].reverse().find(m => m.role === "tool")
  const message = last
    ? { role: "assistant", content: "model saw tool_result: " + String(last.content).trim() }
    : {
        role: "assistant", content: null,
        tool_calls: [{ id: "call_1", type: "function",
          function: { name: "ask_human", arguments: JSON.stringify({ question: "which environment?" }) } }],
      }
  return new Response(JSON.stringify({
    choices: [{ message }],
    usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
  }), { headers: { "content-type": "application/json" } })
}

// ---------- the suspending tool (§10 kind "input") ----------
let executions = 0, sawAnswer = null
const askHuman = defineTool({
  name: "ask_human",
  description: "ask the operator a question and wait for the reply",
  inputSchema: { type: "object", properties: { question: { type: "string" } } },
  async run(args, ctx) {
    executions++
    if (ctx?.answer) {
      sawAnswer = ctx.answer
      return { output: "human replied: " + JSON.stringify(ctx.answer.data) }
    }
    return pending({ id: "req_1", kind: "input", prompt: args.question })
  },
})

const toolkit = () => createToolkit({ builtins: false, extraTools: [askHuman] })
const client = (waitFor, store) => createClient({
  baseUrl: "http://mock/v1", style: "openai", model: "mock", apiKey: "none",
  fetch: mockFetch, waitFor, store,
})
const reset = () => { executions = 0; sawAnswer = null }

function report(label, data, res) {
  console.log(`\n== ${label} ==`)
  console.log(`  answer.data sent   : ${JSON.stringify(data)}`)
  console.log(`  RunResult.status   : ${JSON.stringify(res.status)}`)
  console.log(`  tool execute ran   : ${executions} time(s)`)
  console.log(`  tool saw ctx.answer: ${sawAnswer ? JSON.stringify(sawAnswer.data) : "<never re-executed>"}`)
  console.log(`  model's final text : ${res.text}`)
  console.log(`  VERDICT            : ${
    sawAnswer ? "answer delivered to the tool itself (ctx.answer)"
              : `value never reached the tool (status ${res.status})`}`)
}

// ---------- 0. does the Go durable-resume API exist here? ----------
const mod = await import("../../../js/dist/index.js")
const c0 = client()
const missing = ["runWithAnswer", "askWithAnswer"].filter(k => typeof c0[k] !== "function")
console.log("issue #89 — JS port (mock LLM, no network)")
console.log(`\n== 0. Go's durable-resume API in JS ==`)
console.log(`  Client methods absent : ${missing.join(", ") || "<none — all present>"}`)
console.log(`  relayTool exported    : ${typeof mod.relayTool === "function"}`)
console.log(`  VERDICT               : ${missing.length === 2
  ? "ABSENT — a durable JS host has NO supported way to hand results back"
  : "present"}`)

// ---------- A. inline waitFor, the key the issue reports ----------
for (const data of [{ value: "staging" }, { output: "staging" }, { answers: ["staging"] }]) {
  reset()
  const res = await client(async req => ({ id: req.id, ok: true, data })).run(
    "pick an environment", { toolkit: await toolkit() })
  report(`inline waitFor — data ${JSON.stringify(data)}`, data, res)
}

// ---------- E. the DOCUMENTED two-phase durable flow ----------
// site/src/content/docs/suspension.mdx:300-317 (phase 1) and :311-317 (phase 2)
reset()
const store = new InMemoryConversationStore()
const convId = "conv-1"
const halted = await client(undefined, store).ask("pick an environment", { toolkit: await toolkit(), id: convId })
console.log(`\n== E. the DOCS' two-phase flow — ask(id) + waitFor data{"answers":[...]} ==`)
console.log(`  phase 1 status     : ${JSON.stringify(halted.status)} (pending=${!!halted.pending})`)
const reply = "staging"
const done = await client(async req => ({ id: req.id, ok: true, data: { answers: [reply] } }), store)
  .ask("pick an environment", { toolkit: await toolkit(), id: convId })
report(`E (continued) — the docs claim done.status === "done"`, { answers: [reply] }, done)
