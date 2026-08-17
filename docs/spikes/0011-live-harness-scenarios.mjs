// LIVE harness scenarios against OpenRouter. Real models, real tool calls.
//
// The API key is read from the environment at the point of use and is never
// printed, logged, or written to the report.
const J = "/Users/muthuishere/muthu/gitworkspace/nexus-workspace/toolnexus/js/dist"
const { agents, createClient, createToolkit, defineTool } = await import(`${J}/index.js`)
const { AgentRuntime } = agents

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) { console.error("OPENROUTER_API_KEY not in env"); process.exit(2) }
const BASE = "https://openrouter.ai/api/v1"
const MODEL = process.env.OPENROUTER_MODEL || "openai/gpt-4o-mini"

const client = (extra = {}) => createClient({
  style: "openai", baseUrl: BASE, model: MODEL, apiKey: KEY,
  retries: 2, retryBaseMs: 800, ...extra,
})

// ---- the pieces under test -------------------------------------------------
function harness({ soul, tools = [], budget, hooks, onMetric, guardrails = [], completion } = {}) {
  const beforeTool = guardrails.length
    ? (ev) => { for (const g of guardrails) { const v = g(ev); if (v && v !== "allow") return { result: { output: `denied: ${v}`, isError: true } } } }
    : undefined
  return { soul, tools, budget, onMetric, completion, hooks: beforeTool ? { ...(hooks ?? {}), beforeTool } : hooks }
}
const allTodosDone = (run) => {
  const last = [...(run.toolCalls ?? [])].reverse().find((t) => t.name === "todowrite")
  if (!last) return { ok: true, note: "no plan declared" }
  const todos = last.metadata?.todos ?? []
  const open = todos.filter((t) => !t.completed)
  return open.length ? { ok: false, reason: `${open.length}/${todos.length} open: ${open.map((t) => t.text).join("; ")}` } : { ok: true }
}
async function runWithCompletion(c, prompt, { toolkit, completion, soul }) {
  let history = [], attempts = 0, last = null
  for (let n = 1; n <= completion.maxAttempts; n++) {
    attempts = n
    last = await c.run(n === 1 ? prompt : "Your work was verified and did not pass. Fix it and finish.", { toolkit, history, systemPrompt: soul })
    const v = await completion.verify(last)
    if (v.ok) return { run: last, status: "done", attempts, stoppedBy: null }
    history = [...last.messages, { role: "user", content: `verification failed: ${v.reason}` }]
  }
  return { run: last, status: "incomplete", attempts, stoppedBy: `completion.verify failed ${attempts}×` }
}

// ---- scenarios -------------------------------------------------------------
const results = []
const record = (name, pass, detail, expect) => {
  results.push({ name, pass, detail, expect })
  console.log(`${pass ? "PASS" : "FAIL"}  ${name}\n      ${detail}`)
}

// S1 — a guardrail denies a tool, and the model must cope with the denial
async function s1() {
  const deploy = defineTool({ name: "deploy", description: "Deploy to an environment.",
    inputSchema: { type: "object", properties: { env: { type: "string" } }, required: ["env"] },
    run: async (a) => `DEPLOYED to ${a.env}` })
  const h = harness({
    soul: "You are an ops assistant. Use the deploy tool when asked to deploy.",
    tools: [deploy],
    guardrails: [(ev) => (ev.name === "deploy" && ev.args?.env === "prod" ? "prod deploys require human approval" : "allow")],
  })
  const tk = await createToolkit({ builtins: false, extraTools: h.tools })
  const r = await client({ hooks: h.hooks }).run("Deploy the app to prod.", { toolkit: tk, systemPrompt: h.soul })
  const denied = r.toolCalls.some((t) => t.name === "deploy" && t.isError)
  const notDeployed = !r.toolCalls.some((t) => t.output?.includes("DEPLOYED"))
  record("S1 guardrail denies prod deploy", denied && notDeployed,
    `toolCalls=${r.toolCalls.length} denied=${denied} neverExecuted=${notDeployed} | "${r.text.slice(0, 90)}"`,
    "the tool is denied and never executes; the model reports the block")
}

// S2 — completion gate on the real todowrite builtin
async function s2() {
  const tk = await createToolkit({ builtins: { tools: { todowrite: true, bash: false, read: false, write: false, edit: false, glob: false, grep: false, webfetch: false, apply_patch: false, question: false } } })
  const soul = "You plan with the todowrite tool. Call todowrite with your full list, marking items completed as you go. Keep it to 2 items."
  const out = await runWithCompletion(client(), "Plan and complete: (1) draft a changelog line, (2) proofread it. Use todowrite.", {
    toolkit: tk, soul, completion: { verify: allTodosDone, maxAttempts: 3 },
  })
  const v = allTodosDone(out.run)
  record("S2 completion gate over todowrite", out.status === "done" && v.ok,
    `status=${out.status} attempts=${out.attempts} verifier=${v.ok ? "pass" : v.reason ?? v.note}`,
    "the run only reports done once every todo is checked off")

  // S2b — the gate must CATCH a live run that really does leave an item open.
  // Seed the transcript with a genuine todowrite that has an unchecked item, so
  // the verifier is exercised against real model output rather than a happy path.
  const seed = await client().run(
    "Call todowrite with exactly these two items: {id:'1',text:'draft',completed:true} and {id:'2',text:'proofread',completed:false}. Then say done.",
    { toolkit: tk, systemPrompt: soul })
  const caught = allTodosDone(seed)
  record("S2b gate catches a genuinely open todo", caught.ok === false,
    `verifier=${caught.ok ? "PASS (did not catch — model may have marked all complete)" : "FAIL as intended — " + caught.reason}`,
    "an open item must block done; this exercises the gate against real model output")
}

// S3 — the gate STOPS a run that can never verify (bounded, named)
async function s3() {
  const tk = await createToolkit({ builtins: false })
  const never = async () => ({ ok: false, reason: "always red" })
  const out = await runWithCompletion(client(), "Say hello.", { toolkit: tk, soul: "Be brief.", completion: { verify: never, maxAttempts: 2 } })
  record("S3 unverifiable run stops loudly", out.status === "incomplete" && out.attempts === 2 && !!out.stoppedBy,
    `status=${out.status} attempts=${out.attempts} stoppedBy="${out.stoppedBy}"`,
    "bounded by maxAttempts; stop reason is named, never a silent done")
}

// S4 — budget ceiling stops a real run loudly (§7D)
async function s4() {
  const spin = defineTool({ name: "spin", description: "Does one unit of work. Call it repeatedly.",
    inputSchema: { type: "object", properties: {} }, run: async () => "spun; call spin again" })
  const registry = { worker: { name: "worker", does: "spins", model: MODEL, tools: [spin],
    soul: "Call the spin tool repeatedly. Never stop on your own.", budget: { maxTurns: 3 } } }
  const rt = new AgentRuntime({ registry, llm: { baseUrl: BASE, style: "openai", apiKey: KEY, model: MODEL } })
  const h = rt.spawn(rt.root, "worker")
  rt.wake(h, "Begin spinning.")
  const r = await rt.wait(h)
  rt.close(h)
  record("S4 budget stops the run, named", r.status === "incomplete",
    `status=${r.status} turns=${r.turns} | "${String(r.text).slice(0, 80)}"`,
    "a maxTurns ceiling yields status:incomplete with the limit named — never done")
}

// S5 — delegation: a child agent runs under a parent's `task` tool
async function s5() {
  const registry = {
    writer: { name: "writer", does: "writes one short line of prose", model: MODEL, soul: "Reply with exactly one short sentence." },
    lead:   { name: "lead", does: "delegates", model: MODEL, team: ["writer"],
              soul: "You delegate. Use the task tool with agent 'writer' to get the line, then reply with it." },
  }
  const rt = new AgentRuntime({ registry, llm: { baseUrl: BASE, style: "openai", apiKey: KEY, model: MODEL } })
  const h = rt.spawn(rt.root, "lead")
  rt.wake(h, "Get me a one-line description of a ring buffer.")
  const r = await rt.wait(h)
  rt.close(h)
  const delegated = rt.trace.some((l) => l.includes("writer"))
  record("S5 delegation via the task tool", r.status === "done" && delegated,
    `status=${r.status} delegated=${delegated} | "${String(r.text).slice(0, 80)}"`,
    "the parent delegates to a scoped child; the child's transcript stays out of the parent")
}

// S6 — model switching per run, same conversation
async function s6() {
  const tk = await createToolkit({ builtins: false })
  const c = client()
  const r1 = await c.run("Remember the number 7. Reply OK.", { toolkit: tk })
  const r2 = await createClient({ style: "openai", baseUrl: BASE, model: MODEL, apiKey: KEY })
    .run("What number did I ask you to remember?", { toolkit: tk, history: r1.messages })
  const remembered = /7|seven/i.test(r2.text)
  record("S6 conversation survives across runs", remembered,
    `reply="${r2.text.slice(0, 70)}"`,
    "history threads across run() calls, so a model may change between turns")
}

const scenarios = [s1, s2, s3, s4, s5, s6]
for (const s of scenarios) {
  try { await s() } catch (e) { record(s.name, false, `THREW: ${String(e.message).slice(0, 120)}`, "scenario should complete") }
}
const passed = results.filter((r) => r.pass).length
console.log(`\n${passed}/${results.length} scenarios passed  (model: ${MODEL})`)
import("node:fs").then((fs) =>
  fs.writeFileSync("/private/tmp/claude-501/-Users-muthuishere-muthu-gitworkspace-nexus-workspace-toolnexus/caad4659-ffe6-417f-94c4-e15d7daf3aa0/scratchpad/live-results.json",
    JSON.stringify({ model: MODEL, passed, total: results.length, results }, null, 2)))
