// SPIKE — completion verifier: "the agent cannot claim done until a check passes".
// Q1: can a host build it today, in userland?
// Q2: if yes, what does a built-in version give that userland cannot?
const J = "/Users/muthuishere/muthu/gitworkspace/nexus-workspace/toolnexus/js/dist"
const { agents, createClient, createToolkit, defineTool } = await import(`${J}/index.js`)
const { AgentRuntime } = agents

// The "work" the agent does: it claims done immediately, but the work is only
// actually correct on the 3rd attempt. A verifier is the only thing that knows.
let attempts = 0
const mockFetch = async (_u, init) => {
  const b = JSON.parse(String(init?.body))
  return new Response(JSON.stringify({
    choices: [{ message: { role: "assistant", content: `patch v${attempts}` } }],
    usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 },
  }), { status: 200, headers: { "content-type": "application/json" } })
}
// the domain check — pass only on the 3rd try
const runTests = async () => (++attempts >= 3 ? { ok: true } : { ok: false, reason: `2 tests failing (attempt ${attempts})` })

const toolkit = await createToolkit({ builtins: false })

// ===========================================================================
console.log("=== Q1: userland — host wraps run() in its own retry loop ===")
{
  attempts = 0
  const c = createClient({ style: "openai", baseUrl: "http://mock/v1", model: "m", apiKey: "x", fetch: mockFetch })
  let history = []
  let final = null, stoppedBy = null
  for (let i = 1; i <= 4; i++) {
    const r = await c.run(i === 1 ? "fix the bug" : "verification failed — fix it", { toolkit, history })
    const v = await runTests()
    if (v.ok) { final = r; stoppedBy = `verified on attempt ${i}`; break }
    history = [...r.messages, { role: "user", content: `verification failed: ${v.reason}` }]
    if (i === 4) stoppedBy = "gave up after 4 attempts"
  }
  console.log("  works:", !!final, "|", stoppedBy)
  console.log("  => YES, a host CAN build this today. So the built-in must justify itself on more.")
}

// ===========================================================================
console.log("")
console.log("=== Q2: does the userland version survive DELEGATION? ===")
// A parent agent delegates to `coder` via the §7D `task` tool. The host-side
// retry loop lives at the parent's call site — it cannot reach inside `task`.
{
  attempts = 0
  const registry = {
    coder: { name: "coder", does: "writes patches", model: "m" },
    lead:  { name: "lead",  does: "delegates",      model: "m", team: ["coder"] },
  }
  const rt = new AgentRuntime({ fetch: mockFetch, registry })
  const h = rt.spawn(rt.root, "coder")          // stand-in for a delegated child run
  rt.wake(h, "fix the bug")
  const res = await rt.wait(h)
  console.log("  child run status:", res.status, "| text:", JSON.stringify(res.text))
  console.log("  did ANY verification happen inside the delegated run?", attempts > 0 ? "yes" : "NO")
  console.log("  => the host's retry loop is at the CALL SITE. Delegation bypasses it entirely.")
  rt.close(h)
}

// ===========================================================================
console.log("")
console.log("=== Q3: as a HARNESS property, it travels with the agent ===")
// Sketch of the built-in: the loop calls the verifier before declaring done,
// feeds a failure back as an observation, counts attempts, and stops LOUDLY.
async function runWithCompletion(client, prompt, { toolkit, completion }) {
  let history = [], turnsUsed = 0
  for (let n = 1; n <= completion.maxAttempts; n++) {
    const r = await client.run(n === 1 ? prompt : "verification failed — continue", { toolkit, history })
    turnsUsed += r.turns ?? 1
    const v = await completion.verify(r)
    if (v.ok) return { ...r, status: "done", stoppedBy: null, attempts: n, turnsUsed }
    history = [...r.messages, { role: "user", content: `verification failed: ${v.reason}` }]
    if (n === completion.maxAttempts) {
      return { ...r, status: "incomplete",
               stoppedBy: `completion.verify failed ${n}×: ${v.reason}`, attempts: n, turnsUsed }
    }
  }
}
{
  attempts = 0
  const c = createClient({ style: "openai", baseUrl: "http://mock/v1", model: "m", apiKey: "x", fetch: mockFetch })
  const ok = await runWithCompletion(c, "fix the bug", { toolkit, completion: { verify: runTests, maxAttempts: 3 } })
  console.log(`  passing case : status=${ok.status} attempts=${ok.attempts} stoppedBy=${ok.stoppedBy}`)

  attempts = 99 // force permanent failure
  const neverPasses = async () => ({ ok: false, reason: "still red" })
  const bad = await runWithCompletion(c, "fix the bug", { toolkit, completion: { verify: neverPasses, maxAttempts: 3 } })
  console.log(`  failing case : status=${bad.status} attempts=${bad.attempts}`)
  console.log(`                 stoppedBy="${bad.stoppedBy}"`)
  console.log("  => bounded, and the stop is NAMED — never a silent done (§7D).")
}
