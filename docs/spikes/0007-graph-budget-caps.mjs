// S4 (corrected): budget belongs on AgentDef / spawn, not RuntimeOptions.
import { DIST as J, check, report } from "./_harness.mjs"
const { agents } = await import(`${J}/index.js`)
const { AgentRuntime } = agents
const mockFetch = async () => new Response(
  JSON.stringify({ choices: [{ message: { role: "assistant", content: "ok" } }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } }),
  { status: 200, headers: { "content-type": "application/json" } })

// budget on the DEF: 25 tokens ≈ 2 turns of 10
const registry = { w: { name: "w", does: "w", model: "m", budget: { maxTokens: 25 } } }
const rt = new AgentRuntime({ fetch: mockFetch, registry })
const h = rt.spawn(rt.root, "w")
const seen = []
for (let i = 0; i < 5; i++) {
  rt.wake(h, `turn ${i}`)
  const r = await rt.wait(h)
  seen.push(`${r.status}(${r.totalTokens})`)
  if (r.status !== "done") break
}
console.log("=== S4 corrected: budget on AgentDef, repeated turns on ONE handle ===")
console.log("  statuses:", seen.join(" → "))
const stopped = seen.some((s) => s.startsWith("incomplete"))
check("stopped LOUDLY with 'incomplete'", stopped === true)
check("never silently 'done' past budget", (stopped || seen.length < 5) === true)

// and the maxChildren cap
console.log("")
console.log("=== S4b: maxChildren cap is a loud verb error ===")
const reg2 = { p: { name: "p", does: "p", model: "m", budget: { maxChildren: 2 } }, c: { name: "c", does: "c", model: "m" } }
const rt2 = new AgentRuntime({ fetch: mockFetch, registry: reg2 })
const p = rt2.spawn(rt2.root, "p")
const kids = [0, 1, 2, 3].map(() => rt2.spawn(p, "c"))
const errs = kids.filter((k) => agents.isVerbError(k))
console.log(`  spawned=${kids.length - errs.length} rejected=${errs.length}`)
check("excess children are rejected", errs.length > 0)
check("rejection is loud and names the limit", errs[0]?.error?.includes("maxChildren") === true)

report("0007 graph budget caps")
