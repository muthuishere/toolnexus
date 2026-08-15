// STRESS — the host-side graph layer under load and failure.
// The spikes proved it WORKS. This asks whether it holds up, and where it breaks.
const J = "/Users/muthuishere/muthu/gitworkspace/nexus-workspace/toolnexus/js/dist"
const { agents } = await import(`${J}/index.js`)
const { AgentRuntime } = agents

const mk = (text, { fail = false, delayMs = 0 } = {}) => async () => {
  if (delayMs) await new Promise((r) => setTimeout(r, delayMs))
  if (fail) throw new Error("upstream exploded")
  return text
}
function makeFetch(script) {
  return async (_url, init) => {
    const body = JSON.parse(String(init?.body))
    const fn = script[body.model]
    if (!fn) return new Response("no model", { status: 500 })
    const text = await fn()
    return new Response(
      JSON.stringify({ choices: [{ message: { role: "assistant", content: text } }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
}

// ---------- S1: wide fan-out ---------------------------------------------------
console.log("=== S1: wide fan-out (N=60 concurrent nodes) ===")
{
  const N = 60
  const registry = {}
  const script = {}
  for (let i = 0; i < N; i++) {
    registry[`w${i}`] = { name: `w${i}`, does: "w", model: `m${i}` }
    script[`m${i}`] = mk(`r${i}`)
  }
  const rt = new AgentRuntime({ fetch: makeFetch(script), registry })
  const t0 = Date.now()
  const hs = Object.keys(registry).map((n) => rt.spawn(rt.root, n))
  const bad = hs.filter((h) => agents.isVerbError(h))
  hs.filter((h) => !agents.isVerbError(h)).forEach((h) => rt.wake(h, "go"))
  const rs = await Promise.all(hs.filter((h) => !agents.isVerbError(h)).map((h) => rt.wait(h)))
  console.log(`  spawned=${hs.length - bad.length} rejected=${bad.length} done=${rs.filter((r) => r.status === "done").length} in ${Date.now() - t0}ms`)
  if (bad.length) console.log(`  first rejection: ${bad[0].error}`)
  console.log(`  all results distinct: ${new Set(rs.map((r) => r.text)).size === rs.length}`)
}

// ---------- S2: deep chain -----------------------------------------------------
console.log("")
console.log("=== S2: deep sequential chain (200 hops) ===")
{
  const registry = { step: { name: "step", does: "s", model: "m" } }
  const rt = new AgentRuntime({ fetch: makeFetch({ m: mk("ok") }), registry })
  const t0 = Date.now()
  let hops = 0
  try {
    for (let i = 0; i < 200; i++) {
      const h = rt.spawn(rt.root, "step")
      if (agents.isVerbError(h)) throw new Error(`spawn rejected at hop ${i}: ${h.error}`)
      rt.wake(h, `hop ${i}`)
      const r = await rt.wait(h)
      if (r.status !== "done") throw new Error(`hop ${i} status ${r.status}`)
      rt.close(h)
      hops++
    }
    console.log(`  completed ${hops} hops in ${Date.now() - t0}ms — no leak, no cap hit`)
  } catch (e) {
    console.log(`  STOPPED after ${hops} hops: ${e.message}`)
  }
}

// ---------- S3: a failing node mid-graph --------------------------------------
console.log("")
console.log("=== S3: node failure — does it cross the boundary as a RESULT, not a throw? ===")
{
  const registry = { good: { name: "good", does: "g", model: "mg" }, bad: { name: "bad", does: "b", model: "mb" } }
  const rt = new AgentRuntime({ fetch: makeFetch({ mg: mk("fine"), mb: mk("", { fail: true }) }), registry })
  let threw = null
  let res = null
  try {
    const h = rt.spawn(rt.root, "bad")
    rt.wake(h, "go")
    res = await rt.wait(h)
  } catch (e) {
    threw = e.message
  }
  console.log(`  threw to host: ${threw ?? "no"}`)
  console.log(`  result status: ${res?.status}  isError: ${res?.isError}`)
  console.log(`  §7D boundary rule holds (failure is a RESULT the graph can branch on): ${!threw && res?.isError === true}`)
  // ...and the graph can keep going after it
  const h2 = rt.spawn(rt.root, "good")
  rt.wake(h2, "continue")
  const r2 = await rt.wait(h2)
  console.log(`  graph continues after a failed node: ${r2.status === "done"}`)
}

// ---------- S4: budget exhaustion mid-graph -----------------------------------
console.log("")
console.log("=== S4: budget limit — loud 'incomplete', or silent 'done'? ===")
{
  const registry = { w: { name: "w", does: "w", model: "m" } }
  const rt = new AgentRuntime({
    fetch: makeFetch({ m: mk("ok") }),
    registry,
    budget: { maxTokens: 25 }, // ~2 turns worth
  })
  const seen = []
  for (let i = 0; i < 4; i++) {
    const h = rt.spawn(rt.root, "w")
    if (agents.isVerbError(h)) { seen.push(`spawn:${h.error.slice(0, 40)}`); break }
    rt.wake(h, "go")
    const r = await rt.wait(h)
    seen.push(r.status)
    rt.close(h)
    if (r.status !== "done") break
  }
  console.log(`  statuses: ${seen.join(" → ")}`)
  console.log(`  stopped LOUDLY (never a silent done): ${seen.some((s) => s !== "done")}`)
}
