// SPIKE 2 — the two HARD graph cases on shipped verbs:
//   (a) dynamic fan-out (proposal §15) — coordinator decides N workers at runtime
//   (b) §10 suspension THROUGH a host-side graph node — does a pending survive?
//
// (b) is the real test. If suspension does not survive, Graph genuinely needs
// library support and cannot be a pure host layer.
const J = "/Users/muthuishere/muthu/gitworkspace/nexus-workspace/toolnexus/js/dist"
const { agents, defineTool, pending } = await import(`${J}/index.js`)
const { AgentRuntime } = agents

const scripted = {
  "m-worker-a": () => "A done",
  "m-worker-b": () => "B done",
  "m-worker-c": () => "C done",
}
let askedOnce = false
const mockFetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  // the approver agent calls a tool that suspends the FIRST time only
  if (body.model === "m-approver") {
    const sawToolResult = body.messages.some((m) => m.role === "tool")
    const msg = sawToolResult
      ? { role: "assistant", content: "APPROVED after human said yes" }
      : {
          role: "assistant",
          content: null,
          tool_calls: [{ id: "t1", type: "function", function: { name: "ask_human", arguments: "{}" } }],
        }
    return new Response(
      JSON.stringify({ choices: [{ message: msg }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  const text = (scripted[body.model] ?? (() => "?"))()
  return new Response(
    JSON.stringify({ choices: [{ message: { role: "assistant", content: text } }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } }),
    { status: 200, headers: { "content-type": "application/json" } },
  )
}

// a tool that suspends (§10) the first time it is called
const askHuman = defineTool({
  name: "ask_human",
  description: "asks a human",
  inputSchema: { type: "object", properties: {} },
  run: async () => {
    if (!askedOnce) {
      askedOnce = true
      return pending({ id: "req-1", kind: "question", prompt: "Approve the deploy?" })
    }
    return "human said yes"
  },
})

const registry = {
  wa: { name: "wa", does: "worker a", model: "m-worker-a" },
  wb: { name: "wb", does: "worker b", model: "m-worker-b" },
  wc: { name: "wc", does: "worker c", model: "m-worker-c" },
  approver: { name: "approver", does: "needs human approval", model: "m-approver", tools: [askHuman] },
}

// ---- (a) dynamic fan-out: spawn N in parallel, join on all ---------------------
console.log("=== (a) dynamic fan-out + join ===")
{
  const rt = new AgentRuntime({ fetch: mockFetch, registry })
  const decidedAtRuntime = ["wa", "wb", "wc"] // a coordinator would compute this
  const handles = decidedAtRuntime.map((n) => rt.spawn(rt.root, n))
  handles.forEach((h) => rt.wake(h, "go"))
  const results = await Promise.all(handles.map((h) => rt.wait(h))) // <- the JOIN
  console.log("  fanned out to:", decidedAtRuntime.join(", "))
  console.log("  joined results:", results.map((r) => r.text).join(" | "))
  console.log("  all done:", results.every((r) => r.status === "done"))
  handles.forEach((h) => rt.close(h))
}

// ---- (b) does §10 suspension survive a host-side graph node? -------------------
console.log("")
console.log("=== (b) suspension through a host-side graph node ===")
{
  const rt = new AgentRuntime({ fetch: mockFetch, registry })
  const h = rt.spawn(rt.root, "approver")
  rt.wake(h, "deploy to prod?")
  const res1 = await rt.wait(h)
  console.log("  first wait  → status:", res1.status)
  console.log("  pending request:", JSON.stringify(res1.pending?.prompt ?? null))
  console.log("  handle state:", rt.inspect(h).state)

  if (res1.status === "pending") {
    // the HOST answers and resumes — no library change, this is the shipped §10 path
    // resume() returns void — the result arrives via a subsequent wait()
    await rt.resume({ id: res1.pending.id, ok: true, data: { value: "yes" } })
    const out = await rt.wait(h)
    console.log("  after resume → status:", out.status, "text:", JSON.stringify(out.text))
    console.log("  handle state:", rt.inspect(h).state)
    console.log("  SUSPENSION SURVIVES a host-driven graph node:", out.status === "done")
  } else {
    console.log("  !! no suspension surfaced — cannot conclude")
  }
}
