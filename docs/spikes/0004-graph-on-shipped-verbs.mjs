// SPIKE — can a static graph (nodes/edges/conditions/retry-edge) be built on the
// SHIPPED §7D verbs alone, with ZERO library changes?
//
// If yes, "Graph" is a thin host-side layer over spawn/wake/wait/close, not a second
// agent runtime — which is exactly what the proposal's design rule 9 demands.
const J = "/Users/muthuishere/muthu/gitworkspace/nexus-workspace/toolnexus/js/dist"
const { agents } = await import(`${J}/index.js`)
const { AgentRuntime } = agents

// ---- a scripted mock LLM: each agent's model emits one final text --------------
let testAttempts = 0
const scripted = {
  "m-research": () => "FINDINGS: use a ring buffer",
  "m-code": () => "PATCH: implemented ring buffer",
  // fails the first time, passes the second — exercises the conditional retry edge
  "m-test": () => (++testAttempts === 1 ? "FAIL: off-by-one" : "PASS: all green"),
  "m-review": () => "LGTM",
}
const mockFetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const text = (scripted[body.model] ?? (() => "?"))()
  return new Response(
    JSON.stringify({
      choices: [{ message: { role: "assistant", content: text } }],
      usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
    }),
    { status: 200, headers: { "content-type": "application/json" } },
  )
}

const registry = {
  research: { name: "research", does: "investigates", model: "m-research" },
  code: { name: "code", does: "writes code", model: "m-code" },
  test: { name: "test", does: "runs tests", model: "m-test" },
  review: { name: "review", does: "reviews", model: "m-review" },
}

// =================================================================================
// THE ENTIRE "GRAPH ENGINE" — host-side, on shipped verbs only.
// =================================================================================
async function runGraph(rt, { nodes, edges, start, input }) {
  const trace = []
  const outputs = {}
  let current = start
  let payload = input
  let hops = 0

  while (current && current !== "END") {
    if (++hops > 20) throw new Error("graph: hop limit")
    const h = rt.spawn(rt.root, nodes[current])
    if (agents.isVerbError?.(h)) throw new Error(`spawn failed: ${h.error}`)
    rt.wake(h, payload)
    const res = await rt.wait(h)
    outputs[current] = res.text
    trace.push(`${current}:${res.status}`)
    rt.close(h)

    // resolve the transition — an edge is just a predicate over the node's result
    const next = edges[current]
    current = typeof next === "function" ? next(res, outputs) : next
    payload = res.text
  }
  return { trace, outputs }
}

// ---- the graph from the proposal's §14 static example ---------------------------
const rt = new AgentRuntime({ fetch: mockFetch, registry })
const { trace, outputs } = await runGraph(rt, {
  start: "research",
  input: "make the queue faster",
  nodes: { research: "research", code: "code", test: "test", review: "review" },
  edges: {
    research: "code",
    code: "test",
    // the decision node: failure loops BACK to code, success proceeds
    test: (res) => (res.text.startsWith("PASS") ? "review" : "code"),
    review: "END",
  },
})

console.log("graph trace:", trace.join("  →  "))
console.log("")
for (const [k, v] of Object.entries(outputs)) console.log(`  ${k.padEnd(9)} ${v}`)
console.log("")
const looped = trace.filter((t) => t.startsWith("code:")).length === 2
console.log("conditional retry edge fired (code ran twice):", looped)
console.log("terminated at review:", trace[trace.length - 1].startsWith("review:"))
console.log("")
console.log("engine size: the runGraph function above — no library change, shipped verbs only.")
