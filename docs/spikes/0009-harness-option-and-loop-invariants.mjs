// SPIKE — (A) `harness` as an ADDITIVE option on agent, and (B) a loop with
// VERIFIABLE properties, both on the shipped runtime with zero library changes.
//
// Reference: deepseek-harness-master.
//   - docs/subsystems/core.md:20 — `Agent` is a contract, `agent-loop` is one
//     implementation, "so the loop stays swappable".
//   - docs/subsystems/invariants.md — checks assert on "authoritative event
//     streams or mutable data, never service or method presence"; failures are
//     attributed; and `verify-package-invariants` REJECTS an unexplained empty
//     installer — absence must carry a reason.
const J = "/Users/muthuishere/muthu/gitworkspace/nexus-workspace/toolnexus/js/dist"
const { agents, defineTool } = await import(`${J}/index.js`)
const { AgentRuntime } = agents

// ===========================================================================
// (A) harness() — a pure value constructor over the fields AgentDef already has,
//     plus `guardrails`, which is the one genuinely new thing.
// ===========================================================================
function harness({ soul, tools = [], budget, hooks, onMetric, guardrails = [] } = {}) {
  // guardrails compose into ONE beforeTool with first-deny-wins — later stages
  // cannot widen an earlier denial (deepseek packages/core/tools/README.md:25).
  const beforeTool = guardrails.length
    ? (ev) => {
        for (const g of guardrails) {
          const verdict = g(ev)
          if (verdict && verdict !== "allow") {
            return { output: `denied by guardrail: ${verdict}`, isError: true }
          }
        }
        return undefined
      }
    : undefined
  return {
    soul, tools, budget, onMetric,
    hooks: beforeTool ? { ...(hooks ?? {}), beforeTool } : hooks,
    __harness: true,
  }
}
// agent(name, {model, harness}) — the option form the proposal asks for.
const agent = (name, { does = name, model, harness: h = {}, ...rest }) => ({
  name, does, model, ...h, ...rest,
})

// ===========================================================================
// (B) Verifiable loop properties — invariants asserted over the SHIPPED
//     transition trace (`runtime.trace`), which is already the cross-port
//     conformance artifact. Each carries a name and a reason; a property with
//     nothing checkable must say so rather than be silently absent.
// ===========================================================================
const INVARIANTS = [
  {
    name: "suspended-exits-only-via-answer",
    why: "SPEC §7D: the Answer is the only exit from `suspended`, in both resume shapes.",
    check: (t) => !t.some((l) => /suspended→(?!running|idle|closed)/.test(l)),
  },
  {
    name: "no-transition-from-closed",
    why: "closed is terminal; a transition out of it would mean a handle was revived.",
    check: (t) => !t.some((l) => /closed→/.test(l)),
  },
  {
    name: "every-run-starts-idle-to-running",
    why: "a turn can only begin from idle (§7D handle state machine).",
    check: (t) => t.filter((l) => /→running/.test(l)).every((l) => /(idle|suspended)→running/.test(l)),
  },
  {
    name: "budget-stops-are-named",
    why: "a limit stop must be loud — never a silent done (§7D budgets).",
    check: (_t, res) => res.every((r) => r.status !== "incomplete" || r.text.length > 0),
  },
  {
    name: "no-invariant: scheduling order",
    why: "§7D leaves scheduling, thread placement and concurrency UNOBSERVABLE by " +
         "design — conformance is per-handle traces, not interleaving. Asserting an " +
         "order here would pin something the spec deliberately refuses to pin.",
    check: null, // deliberately absent, with the reason recorded — never silently missing
  },
]

function verifyLoop(trace, results) {
  return INVARIANTS.map((inv) => ({
    name: inv.name,
    status: inv.check === null ? "not-applicable" : inv.check(trace, results) ? "held" : "VIOLATED",
    why: inv.why,
  }))
}

// ---------------------------------------------------------------------------
const mockFetch = async (_u, init) => {
  const b = JSON.parse(String(init?.body))
  const wantsTool = b.model === "m-guarded" && !b.messages.some((m) => m.role === "tool")
  const msg = wantsTool
    ? { role: "assistant", content: null, tool_calls: [{ id: "t1", type: "function", function: { name: "deploy", arguments: "{}" } }] }
    : { role: "assistant", content: "done" }
  return new Response(JSON.stringify({ choices: [{ message: msg }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } }),
    { status: 200, headers: { "content-type": "application/json" } })
}

const deploy = defineTool({ name: "deploy", description: "deploys", inputSchema: { type: "object", properties: {} }, run: async () => "DEPLOYED" })

// a guardrail: policy only (ALLOW/DENY) — never domain correctness
const noProdDeploys = (ev) => (ev.tool?.name === "deploy" ? "deny: prod deploys need approval" : "allow")

const codingHarness = harness({ soul: "You are Ava.", tools: [deploy], guardrails: [noProdDeploys] })
const guarded = agent("guarded", { model: "m-guarded", harness: codingHarness })

console.log("=== (A) harness as an option ===")
console.log("  agent built from harness carries:", Object.keys(guarded).filter((k) => k !== "__harness").join(", "))
console.log("  guardrail compiled into hooks.beforeTool:", typeof guarded.hooks?.beforeTool === "function")

const rt = new AgentRuntime({ fetch: mockFetch, registry: { guarded } })
const h = rt.spawn(rt.root, "guarded")
rt.wake(h, "deploy to prod")
const res = await rt.wait(h)
console.log("  run status:", res.status, "| text:", JSON.stringify(res.text.slice(0, 60)))
console.log("  guardrail DENIED the tool (never executed):", !res.text.includes("DEPLOYED"))
rt.close(h)

console.log("")
console.log("=== (B) verifiable loop properties, over the shipped trace ===")
for (const r of verifyLoop(rt.trace, [res])) {
  const mark = r.status === "held" ? "✓" : r.status === "not-applicable" ? "—" : "✗"
  console.log(`  ${mark} ${r.name}  [${r.status}]`)
  if (r.status !== "held") console.log(`      ${r.why}`)
}
console.log("")
console.log("  trace lines asserted over:", rt.trace.length)

// ---------------------------------------------------------------------------
// NEGATIVE TEST — an invariant that cannot fail is decoration. Feed traces that
// violate each property and confirm every one is caught.
// ---------------------------------------------------------------------------
console.log("")
console.log("=== negative test: do the invariants actually catch violations? ===")
const bad = [
  ["suspended-exits-only-via-answer", ["root/a.1: suspended→done"]],
  ["no-transition-from-closed",        ["root/a.1: closed→running"]],
  ["every-run-starts-idle-to-running", ["root/a.1: closed→running"]],
]
let caught = 0
for (const [name, trace] of bad) {
  const r = verifyLoop(trace, [{ status: "done", text: "x" }]).find((x) => x.name === name)
  const ok = r.status === "VIOLATED"
  if (ok) caught++
  console.log(`  ${ok ? "✓ caught" : "✗ MISSED"}  ${name}`)
}
const r4 = verifyLoop([], [{ status: "incomplete", text: "" }]).find((x) => x.name === "budget-stops-are-named")
const ok4 = r4.status === "VIOLATED"
if (ok4) caught++
console.log(`  ${ok4 ? "✓ caught" : "✗ MISSED"}  budget-stops-are-named (silent incomplete)`)
console.log(`\n  ${caught}/4 violations detected — the invariants are not vacuous.`)
