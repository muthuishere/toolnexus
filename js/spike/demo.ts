/**
 * SPIKE demo — 9 acceptance scenarios for the agent-runtime substrate v2.
 * Zero network, zero cost: the LLM is a scripted in-process `fetch` (same pattern as
 * the mock-LLM benchmarks). Each scenario asserts observable state transitions.
 *
 * Run: cd js && npm run build && node --experimental-strip-types spike/demo.ts
 */
import { defineTool, pending, type Tool } from "../dist/index.js"
import { AgentRuntime, type AgentDef, type Handle } from "./runtime.ts"

// ---------- scripted mock LLM (openai style, keyed by body.model) ----------
let slowGate: { promise: Promise<void>; release: () => void } | null = null

function openaiResponse(message: Record<string, unknown>, tokens = { prompt_tokens: 30, completion_tokens: 10, total_tokens: 40 }) {
  return new Response(JSON.stringify({ choices: [{ message: { role: "assistant", ...message } }], usage: tokens }), {
    status: 200,
    headers: { "content-type": "application/json" },
  })
}
const call = (name: string, args: Record<string, unknown>, id: string) => ({
  content: null,
  tool_calls: [{ id, type: "function", function: { name, arguments: JSON.stringify(args) } }],
})

const mockFetch: typeof fetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const msgs: any[] = body.messages
  const toolMsgs = msgs.filter((m) => m.role === "tool")
  const signal = init?.signal as AbortSignal | undefined

  switch (body.model) {
    case "m-coordinator": {
      if (toolMsgs.length === 0)
        return openaiResponse({
          content: null,
          tool_calls: [
            { id: "c1", type: "function", function: { name: "task", arguments: JSON.stringify({ agent: "explore", prompt: "find A" }) } },
            { id: "c2", type: "function", function: { name: "task", arguments: JSON.stringify({ agent: "explore", prompt: "find B" }) } },
          ],
        })
      return openaiResponse({ content: `synthesis: ${toolMsgs.map((m) => m.content).join(" + ")}` })
    }
    case "m-explore": {
      if (toolMsgs.length === 0) return openaiResponse(call("lookup", { q: "x" }, "e1"))
      return openaiResponse({ content: `found:${toolMsgs[0].content}` })
    }
    case "m-approver-parent": {
      if (toolMsgs.length === 0) return openaiResponse(call("task", { agent: "asker", prompt: "get the secret" }, "p1"))
      return openaiResponse({ content: `parent-final: ${toolMsgs[toolMsgs.length - 1].content}` })
    }
    case "m-asker": {
      const approved = toolMsgs.some((m) => String(m.content).includes("secret-token"))
      if (!approved) return openaiResponse(call("check_secret", {}, "a1"))
      return openaiResponse({ content: "asker-done: secret-token" })
    }
    case "m-peer": {
      const items = String(msgs[msgs.length - 1]?.content ?? "").match(/^\d+\./gm)?.length ?? 0
      return openaiResponse({ content: `processed ${items} items` })
    }
    case "m-loop": // never finishes: always another tool call → maxTurns/incomplete
      return openaiResponse(call("lookup", { q: "again" }, `l${toolMsgs.length}`))
    case "m-slow": {
      await new Promise<void>((resolve, reject) => {
        slowGate!.promise.then(resolve)
        signal?.addEventListener("abort", () => reject(signal.reason ?? new Error("aborted")), { once: true })
      })
      return openaiResponse({ content: "slow-done" })
    }
    default:
      return openaiResponse({ content: "ok" })
  }
}

// ---------- scoped tools ----------
const lookup: Tool = defineTool({
  name: "lookup",
  description: "look something up",
  inputSchema: { type: "object", properties: { q: { type: "string" } } },
  run: async (a: Record<string, unknown>) => `data(${a.q})`,
})
const checkSecret: Tool = defineTool({
  name: "check_secret",
  description: "needs human approval",
  inputSchema: { type: "object", properties: {} },
  run: async (_a: Record<string, unknown>, ctx?: { answer?: { ok: boolean } }) => {
    if (ctx?.answer?.ok) return "secret-token"
    return pending({ kind: "approval", prompt: "approve secret access?" })
  },
})

// ---------- registry ----------
function registry(overrides?: Partial<Record<string, Partial<AgentDef>>>): Record<string, AgentDef> {
  const base: Record<string, AgentDef> = {
    coordinator: { name: "coordinator", description: "splits and delegates", systemPrompt: "coordinate", model: "m-coordinator" },
    explore: { name: "explore", description: "read-only research", systemPrompt: "explore", model: "m-explore", tools: [lookup] },
    approverParent: { name: "approverParent", description: "delegates, holds approval authority", systemPrompt: "", model: "m-approver-parent" },
    asker: { name: "asker", description: "needs approvals", systemPrompt: "", model: "m-asker", tools: [checkSecret] },
    peer: { name: "peer", description: "team member", systemPrompt: "", model: "m-peer" },
    looper: { name: "looper", description: "never finishes", systemPrompt: "", model: "m-loop", tools: [lookup] },
    slow: { name: "slow", description: "slow worker", systemPrompt: "", model: "m-slow" },
  }
  for (const [k, v] of Object.entries(overrides ?? {})) base[k] = { ...base[k], ...v } as AgentDef
  return base
}

// ---------- harness ----------
let passed = 0
let failed = 0
function check(name: string, cond: boolean, detail = "") {
  if (cond) {
    passed++
    console.log(`  ✅ ${name}`)
  } else {
    failed++
    console.log(`  ❌ ${name} ${detail}`)
  }
}
function section(s: string) {
  console.log(`\n━━ ${s}`)
}

async function main() {
  // S1+S2 — fan-out, isolation, parallelism, usage roll-up ------------------
  section("S1/S2 task fan-out · context isolation · parallel · usage roll-up")
  {
    const rt = new AgentRuntime({ fetch: mockFetch, registry: registry() })
    const coord = rt.spawn(rt.root, "coordinator") as Handle
    rt.wake(coord, "answer using two lookups")
    const r = await rt.wait(coord)
    check("parent reaches done", r.status === "done", r.text)
    check("parent got BOTH child answers", r.text.includes("found:data(x)") && r.text.split("found:").length === 3, r.text)
    check("parent ran 2 turns only (children's turns not in parent)", r.turns === 2, `turns=${r.turns}`)
    check("two children spawned with deterministic ids", rt.trace.some((l) => l.includes("/coordinator.1/explore.1:")) && rt.trace.some((l) => l.includes("/explore.2:")))
    check("children ran CONCURRENTLY (observed ≥2 turns in flight)", rt.maxObservedConcurrentTurns >= 2, `max=${rt.maxObservedConcurrentTurns}`)
    // roll-up: coord usage = own 2 turns×40 + children 2×(2 turns×40) = 240
    check("usage rolls up to parent (G3 ledger)", coord.usageTotal === 240, `usage=${coord.usageTotal}`)
    check("children auto-closed after task", rt.list().every((h) => h.id === coord.id || h.state === "closed"))
  }

  // S3 — suspension escalated INLINE: nearest interpreter wins --------------
  section("S3 child suspends → parent's waitFor answers (nearest interpreter)")
  {
    const rt = new AgentRuntime({
      fetch: mockFetch,
      registry: registry({ approverParent: { waitFor: async (req) => ({ id: req.id, ok: true }) } }),
    })
    const p = rt.spawn(rt.root, "approverParent") as Handle
    rt.wake(p, "do the secret thing")
    const r = await rt.wait(p)
    check("run completed (no durable pending)", r.status === "done", r.status)
    check("child's approval flowed through parent authority", r.text.includes("asker-done: secret-token"), r.text)
    check("trace shows suspended→running round-trip", rt.trace.some((l) => l.includes("running→suspended")) && rt.trace.some((l) => l.includes("suspended→running")))
    check("escalation chose an ANCESTOR (not self)", rt.trace.some((l) => l.includes("escalate → root/approverParent.1 answers")))
  }

  // S4 — durable pending at root + resume cascade from checkpoint -----------
  section("S4 no interpreter anywhere → durable pending; resume() cascades from checkpoint")
  {
    const rt = new AgentRuntime({ fetch: mockFetch, registry: registry() })
    const p = rt.spawn(rt.root, "approverParent") as Handle
    rt.wake(p, "do the secret thing")
    const r1 = await rt.wait(p)
    check("root run returned status=pending", r1.status === "pending", r1.status)
    check("Request carries the handle PATH to the leaf", JSON.stringify(r1.pending?.path ?? []).includes("approverParent.1"), JSON.stringify(r1.pending))
    check("both levels parked (suspended), zero tokens burning", rt.list().every((h) => h.state === "suspended"))
    const before = rt.list().find((h) => h.id.includes("asker"))!.tokens
    await rt.resume({ id: r1.pending!.id, ok: true })
    const r2 = await new Promise<string>((res) => setTimeout(() => res(rt.trace.join("\n")), 50))
    check("leaf resumed AT checkpoint (prior turns preserved)", r2.includes("resume with Answer(ok=true) at checkpoint"))
    check("parent cascade REATTACHED to the finished child (no re-execution)", r2.includes("task replay → REATTACH"))
    check("parent reached done after cascade", p.state === "idle", p.state)
    check("child did not restart from scratch (usage grew, not reset)", rt.list().find((h) => h.id.includes("asker"))!.tokens > before)
  }

  // S5 — team peer: coalesced drain + provenance + timer dedupe -------------
  section("S5 unsolicited rail: posts coalesce into ONE turn, provenance marked, ticks dedupe")
  {
    const rt = new AgentRuntime({ fetch: mockFetch, registry: registry() })
    const peer = rt.spawn(rt.root, "peer") as Handle
    rt.post(peer, { from: "root/coordinator.1", channel: "peer", text: "update 1" })
    rt.post(peer, { from: "external", channel: "external", text: "webhook payload" })
    rt.post(peer, { from: "clock", channel: "timer", text: "tick" })
    rt.post(peer, { from: "clock", channel: "timer", text: "tick" })
    rt.post(peer, { from: "clock", channel: "timer", text: "tick" })
    rt.wake(peer)
    const r = await rt.wait(peer)
    check("one wake = ONE turn for all items", r.turns === 1, `turns=${r.turns}`)
    check("2 messages + 3 ticks coalesced to 3 items", r.text === "processed 3 items", r.text)
  }

  // S6 — backpressure: inbox gate, concurrency gate, global turn gate -------
  section("S6 backpressure: loud inbox reject · per-parent concurrency queue · global turn gate")
  {
    const rt = new AgentRuntime({ fetch: mockFetch, registry: registry(), inboxCap: 2 })
    const peer = rt.spawn(rt.root, "peer") as Handle
    rt.post(peer, { from: "a", channel: "peer", text: "1" })
    rt.post(peer, { from: "a", channel: "peer", text: "2" })
    const third = rt.post(peer, { from: "a", channel: "peer", text: "3" })
    check("inbox gate: third post REJECTED loudly to sender", !third.ok && third.isError!.includes("inbox full"))

    const rt2 = new AgentRuntime({ fetch: mockFetch, registry: registry({ coordinator: { budget: { maxConcurrent: 1 } } }) })
    const c2 = rt2.spawn(rt2.root, "coordinator") as Handle
    rt2.wake(c2, "go")
    const r2 = await rt2.wait(c2)
    check("concurrency gate: 2nd child QUEUED then DEQUEUED, still completes", r2.status === "done" && rt2.trace.some((l) => l.includes("wake QUEUED")) && rt2.trace.some((l) => l.includes("DEQUEUED wake")), r2.text)
    check("concurrency gate: never >1 child turn in flight… but parent turn allowed", rt2.maxObservedConcurrentTurns <= 2)

    const rt3 = new AgentRuntime({ fetch: mockFetch, registry: registry(), maxConcurrentTurns: 1 })
    const c3 = rt3.spawn(rt3.root, "coordinator") as Handle
    rt3.wake(c3, "go")
    await rt3.wait(c3)
    check("global turn gate: with cap 1, max observed concurrent turns = 1", rt3.maxObservedConcurrentTurns === 1, `max=${rt3.maxObservedConcurrentTurns}`)
  }

  // S7 — budgets: carve, exhaustion=incomplete, maxTurns visible ------------
  section("S7 budgets: hierarchical carve · exhaustion = incomplete (never crash) · maxTurns loud")
  {
    const rt = new AgentRuntime({ fetch: mockFetch, registry: registry() })
    const c = rt.spawn(rt.root, "coordinator", { maxTokens: 100, maxChildren: 2 }) as Handle
    const kid = rt.spawn(c, "explore", { maxTokens: 500 }) as Handle
    check("carve: child asked 500, parent pool 100 → effective 100", kid.pool.tokens === 100, String(kid.pool.tokens))
    const kid2 = rt.spawn(c, "explore") as Handle
    const kid3 = rt.spawn(c, "explore")
    check("maxChildren enforced at spawn", "isError" in kid3 && kid3.isError.includes("maxChildren"))
    rt.wake(kid, "go")
    await rt.wait(kid) // burns 80 tokens (2 turns×40) from both kid and parent pools
    check("roll-up drains the PARENT pool too", c.pool.tokens === 20, String(c.pool.tokens))
    rt.wake(kid2, "go")
    const r2 = await rt.wait(kid2)
    check("pool nearly gone: sibling still ran (20 left > 0)", r2.status === "done")
    const r3 = await rt.runTurn(kid2, "again")
    check("pool exhausted → status=incomplete, partial work preserved, NO crash", r3.status === "incomplete" && r3.text.includes("budget exhausted"), r3.status)

    const rt4 = new AgentRuntime({ fetch: mockFetch, registry: registry({ looper: { budget: { maxTurns: 3 } } }) })
    const lp = rt4.spawn(rt4.root, "looper") as Handle
    rt4.wake(lp, "loop forever")
    const rl = await rt4.wait(lp)
    check("maxTurns cap is LOUD: status=incomplete (not silent done)", rl.status === "incomplete", rl.status)
  }

  // S8 — interrupt: aborts the TURN, not the agent --------------------------
  section("S8 interrupt = turn-abort → idle (inbox intact); NOT a kill")
  {
    slowGate = (() => {
      let release!: () => void
      const promise = new Promise<void>((r) => (release = r))
      return { promise, release }
    })()
    const rt = new AgentRuntime({ fetch: mockFetch, registry: registry() })
    const s = rt.spawn(rt.root, "slow") as Handle
    rt.post(s, { from: "root", channel: "peer", text: "for later" })
    rt.wake(s, "work slowly")
    await new Promise((r) => setTimeout(r, 30))
    check("agent is mid-turn (running)", s.state === "running")
    const w = rt.wait(s)
    rt.interrupt(s)
    const r = await w
    check("turn aborted → isError result, status=interrupted", r.isError && r.status === "interrupted", r.status)
    check("agent is IDLE (alive), not closed", s.state === "idle", s.state)
    check("inbox survived the interrupt", s.inbox.length === 1)
    slowGate.release()
  }

  // S9 — close cascade: leaf-first; post-after-close rejected ---------------
  section("S9 stop-all: close(root) cascades leaf-first; closed handles reject posts")
  {
    const rt = new AgentRuntime({ fetch: mockFetch, registry: registry() })
    const c = rt.spawn(rt.root, "coordinator") as Handle
    const k1 = rt.spawn(c, "peer") as Handle
    const k2 = rt.spawn(k1, "peer") as Handle // grandchild
    let closeOrder: string[] = []
    for (const h of [c, k1, k2]) (h.def as AgentDef).onClose = async (hh) => { closeOrder.push(hh.id) }
    await rt.close(rt.root)
    check("leaf-first order (grandchild → child → parent)", closeOrder[0] === k2.id && closeOrder[1] === k1.id && closeOrder[2] === c.id, closeOrder.join(" → "))
    const p = rt.post(k1, { from: "x", channel: "peer", text: "late" })
    check("post after close = loud isError", !p.ok && p.isError!.includes("closed"))
  }

  // ---------- summary ----------
  console.log(`\n${"═".repeat(60)}`)
  console.log(`  ${passed} passed · ${failed} failed  ${failed === 0 ? "— ALL SCENARIOS PASS ✅" : "❌"}`)
  console.log("═".repeat(60))
  if (process.argv.includes("--trace")) {
    console.log("\nFull transition trace of last runtime omitted — run scenarios individually.")
  }
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error("demo crashed:", e)
  process.exit(1)
})
