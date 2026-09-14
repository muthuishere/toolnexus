// STRESS — the host-side graph layer under load and failure.
// The spikes proved it WORKS. This asks whether it holds up, and where it breaks.
//
// 2026-09-14 (ADR 0020 pass): two defects in this file itself, both worth recording.
//   1. It hardcoded an absolute home directory, so nobody but the author could run it.
//   2. S4 put `budget` on RuntimeOptions, which HAS NO SUCH FIELD — so it was silently
//      ignored and the case "showed" budgets not being enforced. ADR 0017 records the
//      correction (budget lives on AgentDef / spawn) and 0007 demonstrates it, but this
//      file was never fixed, so it kept printing `false` under a scary label. Fixed
//      below, with the wrong placement kept as an explicit negative case — because the
//      silent-ignore is itself a finding worth asserting.
import { DIST, section, check, note, report } from "./_harness.mjs"

const { agents } = await import(`${DIST}/index.js`)
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
section("S1: wide fan-out (N=60 concurrent nodes)")
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
  note(`spawned=${hs.length - bad.length} rejected=${bad.length} done=${rs.filter((r) => r.status === "done").length} in ${Date.now() - t0}ms`)
  check("all 60 spawned, none rejected", bad.length === 0)
  check("all 60 completed done", rs.filter((r) => r.status === "done").length === N)
  check("all results distinct", new Set(rs.map((r) => r.text)).size === rs.length)
}

// ---------- S2: deep chain -----------------------------------------------------
section("S2: deep sequential chain (200 hops)")
{
  const registry = { step: { name: "step", does: "s", model: "m" } }
  const rt = new AgentRuntime({ fetch: makeFetch({ m: mk("ok") }), registry })
  const t0 = Date.now()
  let hops = 0
  let stopped = null
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
  } catch (e) {
    stopped = e.message
  }
  note(`completed ${hops} hops in ${Date.now() - t0}ms`)
  check("200 hops, no cap hit and no leak", hops === 200 && stopped === null, stopped ?? "")
}

// ---------- S3: a failing node mid-graph --------------------------------------
section("S3: node failure — does it cross the boundary as a RESULT, not a throw?")
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
  note(`threw to host: ${threw ?? "no"} | result status: ${res?.status} isError: ${res?.isError}`)
  check("§7D boundary rule holds (failure is a RESULT the graph can branch on)", !threw && res?.isError === true)
  const h2 = rt.spawn(rt.root, "good")
  rt.wake(h2, "continue")
  const r2 = await rt.wait(h2)
  check("graph continues after a failed node", r2.status === "done")
}

// ---------- S4: budget exhaustion mid-graph -----------------------------------
section("S4: budget limit — loud 'incomplete', or silent 'done'?")
{
  // CORRECTED placement: budget belongs to the AgentDef (and to spawn), never to
  // RuntimeOptions. Repeated turns on ONE handle so the pool actually drains.
  const registry = { w: { name: "w", does: "w", model: "m", budget: { maxTokens: 25 } } }
  const rt = new AgentRuntime({ fetch: makeFetch({ m: mk("ok") }), registry })
  const h = rt.spawn(rt.root, "w")
  const seen = []
  for (let i = 0; i < 4; i++) {
    rt.wake(h, "go")
    const r = await rt.wait(h)
    seen.push(`${r.status}${r.totalTokens ? `(${r.totalTokens})` : ""}`)
    if (r.status !== "done") break
  }
  note(`statuses: ${seen.join(" → ")}`)
  check("stopped LOUDLY — never a silent done past budget", seen.some((s) => s.startsWith("incomplete")))
}

// ---------- S4-neg: the mistake this file used to make -------------------------
section("S4-neg: budget on RuntimeOptions is SILENTLY ignored (the original defect)")
{
  const registry = { w: { name: "w", does: "w", model: "m" } }
  const rt = new AgentRuntime({
    fetch: makeFetch({ m: mk("ok") }),
    registry,
    budget: { maxTokens: 25 }, // <-- no such field on RuntimeOptions
  })
  const h = rt.spawn(rt.root, "w")
  const seen = []
  for (let i = 0; i < 4; i++) {
    rt.wake(h, "go")
    const r = await rt.wait(h)
    seen.push(r.status)
    if (r.status !== "done") break
  }
  note(`statuses: ${seen.join(" → ")}`)
  check("misplaced budget is ignored with NO error — a real usability trap", seen.every((s) => s === "done"))
  note("Finding: an unknown RuntimeOptions key is accepted silently. A typo'd or")
  note("misplaced budget therefore reads as 'budgets don't work'. Worth a spec pin.")
}

// ---------- S4b: maxChildren --------------------------------------------------
section("S4b: maxChildren cap is a loud verb error")
{
  const registry = { w: { name: "w", does: "w", model: "m" } }
  const rt = new AgentRuntime({ fetch: makeFetch({ m: mk("ok") }), registry })
  const parent = rt.spawn(rt.root, "w", { maxChildren: 2, maxDepth: 5 })
  const kids = [0, 1, 2, 3].map(() => rt.spawn(parent, "w"))
  const rejected = kids.filter((k) => agents.isVerbError(k))
  note(`spawned=${kids.length - rejected.length} rejected=${rejected.length}`)
  check("excess children rejected", rejected.length === 2)
  check("rejection names the limit", rejected[0]?.error?.includes("maxChildren") === true, `"${rejected[0]?.error}"`)
}

report("0006 graph stress")
