/**
 * Agent runtime + subagents tests (SPEC.md §7D) — the 46 spike acceptance checks
 * rewritten as shipped tests, driven by the shared `examples/subagent-*` fixtures
 * where they map, plus the shipped-quality additions (runtime-wide store,
 * transcript rewind on pending, turn-gate death release, virtual clock).
 *
 * Hermetic: zero network, the LLM is a scripted in-process `fetch` implementing
 * the fixtures' mockLLM scripts. All timers run on a virtual clock.
 *
 * Run: npm run build && node --experimental-strip-types --test test/agents.test.ts
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { agents, createClient, createToolkit, defineTool, pending, type Tool } from "../dist/index.js"

const { AgentRuntime, agent, isVerbError } = agents
type Handle = agents.Handle
type AgentDef = agents.AgentDef

const FIXTURES = path.resolve(fileURLToPath(import.meta.url), "../../../examples")

function fixture(name: string): any {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name, "fixture.json"), "utf8"))
}

// ---------------------------------------------------------------------------
// Virtual clock (§7D: fixtures run on a virtual clock)
// ---------------------------------------------------------------------------

class VirtualClock implements agents.Clock {
  private t = 0
  private timers: Array<{ at: number; fn: () => void; done: boolean }> = []
  now(): number {
    return this.t
  }
  setTimeout(fn: () => void, ms: number): () => void {
    const timer = { at: this.t + ms, fn, done: false }
    this.timers.push(timer)
    return () => {
      timer.done = true
    }
  }
  advance(ms: number): void {
    const target = this.t + ms
    for (;;) {
      const due = this.timers.filter((x) => !x.done && x.at <= target).sort((a, b) => a.at - b.at)[0]
      if (!due) break
      this.t = due.at
      due.done = true
      due.fn()
    }
    this.t = target
  }
}

/** Flush promise chains queued behind the mock LLM's async hops. */
async function flush(rounds = 20): Promise<void> {
  for (let i = 0; i < rounds; i++) await new Promise<void>((r) => setImmediate(r))
}

function deferred<T = void>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

// ---------------------------------------------------------------------------
// Scripted mock LLM (openai style, keyed by body.model — the fixtures' mockLLM)
// ---------------------------------------------------------------------------

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

/** Controls for the m-slow model: `started` fires when the call is in flight;
 * `gate` releases it; an abort on the request signal rejects it. */
let slowCtl = { started: deferred(), gate: deferred() }
function newSlow() {
  slowCtl = { started: deferred(), gate: deferred() }
  return slowCtl
}

const mockFetch: typeof fetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const msgs: any[] = body.messages
  const toolMsgs = msgs.filter((m) => m.role === "tool")
  const sys = String(msgs.find((m) => m.role === "system")?.content ?? "")
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
      slowCtl.started.resolve()
      await new Promise<void>((resolve, reject) => {
        slowCtl.gate.promise.then(resolve)
        signal?.addEventListener("abort", () => reject(signal.reason ?? new Error("aborted")), { once: true })
      })
      return openaiResponse({ content: "slow-done" })
    }
    case "m-rogue": {
      if (toolMsgs.length === 0) return openaiResponse(call("task", { agent: "stranger", prompt: "x" }, "r1"))
      return openaiResponse({ content: `rogue: ${toolMsgs[0].content}` })
    }
    case "m-coder": {
      if (toolMsgs.length === 0) return openaiResponse(call("task", { agent: "explore", prompt: "find the bug" }, "t1"))
      return openaiResponse({ content: `fixed using: ${toolMsgs[0].content} [soul:${sys.includes("You are the CODER") ? "loaded" : "missing"}]` })
    }
    case "m-explore-bug": {
      if (toolMsgs.length === 0) return openaiResponse(call("lookup", { q: "bug" }, "e1"))
      return openaiResponse({ content: `bug at line 42 (${toolMsgs[0].content})` })
    }
    case "m-mia": {
      const last = String(msgs[msgs.length - 1]?.content ?? "")
      if (last.includes("Heartbeat")) {
        const hasTicks = last.includes("channel=timer")
        return openaiResponse({ content: sys.includes("water the plants") && hasTicks ? "Reminder: water the plants!" : "HEARTBEAT_OK" })
      }
      return openaiResponse({ content: `soul-sections:[${["SOUL.md", "USER.md", "MEMORY.md"].filter((f) => sys.includes(`## ${f}`)).join(",")}]` })
    }
    case "m-old-api": {
      if (toolMsgs.length === 0) return openaiResponse(call("explore", { prompt: "scan the repo" }, "b1"))
      return openaiResponse({ content: `old-api summary: ${toolMsgs[0].content}` })
    }
    default:
      return openaiResponse({ content: "ok" })
  }
}

// ---------------------------------------------------------------------------
// Scoped tools + registry (matches the fixtures' `tools` / `registry` blocks)
// ---------------------------------------------------------------------------

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

function registry(overrides?: Partial<Record<string, Partial<AgentDef>>>): Record<string, AgentDef> {
  const base: Record<string, AgentDef> = {
    coordinator: { name: "coordinator", does: "splits and delegates", model: "m-coordinator", team: ["explore"] },
    explore: { name: "explore", does: "read-only research", model: "m-explore", tools: [lookup] },
    approverParent: { name: "approverParent", does: "delegates, holds approval authority", model: "m-approver-parent", team: ["asker"] },
    asker: { name: "asker", does: "needs approvals", model: "m-asker", tools: [checkSecret] },
    peer: { name: "peer", does: "team member", model: "m-peer" },
    looper: { name: "looper", does: "never finishes", model: "m-loop", tools: [lookup] },
    slow: { name: "slow", does: "slow worker", model: "m-slow" },
  }
  for (const [k, v] of Object.entries(overrides ?? {})) base[k] = { ...base[k], ...v } as AgentDef
  return base
}

function rt(opts?: Partial<agents.RuntimeOptions>): agents.AgentRuntime {
  return new AgentRuntime({ fetch: mockFetch, registry: registry(), clock: new VirtualClock(), ...opts })
}

/** Extract the §7D transition trace for one handle id (the fixture parity view). */
const TRANSITIONS = [
  "idle→running", "running→suspended", "suspended→running", "running→idle",
  "idle→closed", "suspended→closed", "running→closed",
]
function transitionsOf(trace: readonly string[], id: string): string[] {
  const out: string[] = []
  for (const line of trace) {
    if (!line.startsWith(`${id}: `)) continue
    const rest = line.slice(id.length + 2)
    for (const t of TRANSITIONS) {
      if (rest.startsWith(t)) {
        out.push(t)
        break
      }
    }
  }
  return out
}

/** Fixture arrows may be stateful ("idle→closed") or bare ("→closed") — suffix-match. */
function assertTransitions(trace: readonly string[], id: string, expected: string[]): void {
  const actual = transitionsOf(trace, id)
  assert.equal(actual.length, expected.length, `transitions of ${id}: ${actual.join(", ")} vs ${expected.join(", ")}`)
  expected.forEach((e, i) => {
    assert.ok(actual[i] === e || actual[i].endsWith(e), `transitions of ${id}[${i}]: ${actual[i]} vs ${e}`)
  })
}

// ===========================================================================
// S1/S2 — task fan-out · context isolation · parallel · usage roll-up
// (examples/subagent-fanout)
// ===========================================================================

test("S1/S2: parallel task fan-out with isolation and usage roll-up (fixture: subagent-fanout)", async () => {
  const fx = fixture("subagent-fanout")
  const runtime = rt()
  const coord = runtime.spawn(runtime.root, "coordinator")
  assert.ok(!isVerbError(coord))
  runtime.wake(coord, fx.run.prompt)
  const r = await runtime.wait(coord)

  // 1. parent reaches done
  assert.equal(r.status, fx.expect.status)
  // 2. parent got BOTH child answers
  for (const s of fx.expect.textContains) assert.ok(r.text.includes(s), r.text)
  assert.equal(r.text.split("found:").length - 1, fx.expect.textOccurrences["found:"], r.text)
  // 3. parent ran 2 turns only (children's turns not in parent)
  assert.equal(r.turns, fx.expect.parentTurns)
  // 4. two children spawned with deterministic ids
  for (const id of fx.expect.childIds) {
    assert.ok(runtime.trace.some((l) => l.startsWith(`${id}: spawned`)), `missing spawn of ${id}`)
  }
  // 5. children ran CONCURRENTLY (≥2 LLM calls in flight)
  assert.ok(runtime.maxObservedConcurrentTurns >= fx.expect.concurrentTurnsObservedAtLeast, String(runtime.maxObservedConcurrentTurns))
  // 6. usage rolls up to the parent (the ledger)
  assert.equal(coord.usageTotal, fx.expect.parentUsageTotal)
  // 7. children auto-closed after task
  assert.ok(runtime.list().every((h) => h.id === coord.id || h.state === "closed"))
  // transition-trace parity with the shared fixture
  for (const [id, expected] of Object.entries(fx.expect.transitions)) {
    assertTransitions(runtime.trace, id, expected as string[])
  }
})

// ===========================================================================
// S3 — suspension escalated INLINE: nearest interpreter wins
// (examples/subagent-escalation)
// ===========================================================================

test("S3: child suspends → parent's waitFor answers (fixture: subagent-escalation)", async () => {
  const fx = fixture("subagent-escalation")
  const runtime = rt({
    registry: registry({ approverParent: { waitFor: async (req) => ({ id: req.id, ok: true }) } }),
  })
  const p = runtime.spawn(runtime.root, "approverParent")
  assert.ok(!isVerbError(p))
  runtime.wake(p, fx.run.prompt)
  const r = await runtime.wait(p)

  // 1. run completed (no durable pending)
  assert.equal(r.status, fx.expect.status)
  // 2. the child's approval flowed through parent authority
  for (const s of fx.expect.textContains) assert.ok(r.text.includes(s), r.text)
  // 3. trace shows the suspended→running round trip
  assert.ok(runtime.trace.some((l) => l.includes("running→suspended")))
  assert.ok(runtime.trace.some((l) => l.includes("suspended→running")))
  // 4. escalation chose an ANCESTOR (not self)
  assert.ok(runtime.trace.some((l) => l.includes(`escalate → ${fx.expect.escalation.answeredBy} answers`)), runtime.trace.join("\n"))
  // transition-trace parity with the shared fixture
  for (const [id, expected] of Object.entries(fx.expect.transitions)) {
    assertTransitions(runtime.trace, id, expected as string[])
  }
})

// ===========================================================================
// S4 — durable pending at root + resume cascade from checkpoint
// (examples/subagent-durable-resume)
// ===========================================================================

test("S4: no interpreter anywhere → durable pending; resume() cascades and reattaches (fixture: subagent-durable-resume)", async () => {
  const fx = fixture("subagent-durable-resume")
  const runtime = rt()
  const p = runtime.spawn(runtime.root, "approverParent")
  assert.ok(!isVerbError(p))
  runtime.wake(p, fx.run.prompt)
  const r1 = await runtime.wait(p)

  // 1. root run returned status=pending
  assert.equal(r1.status, fx.phase1_expect.status)
  assert.equal(r1.pending?.kind, fx.phase1_expect.pendingKind)
  // 2. the Request carries the handle path at data.path — never a grafted field
  const dataPath = r1.pending?.data?.path as string[] | undefined
  assert.ok(Array.isArray(dataPath), JSON.stringify(r1.pending))
  assert.ok(fx.phase1_expect.pendingDataPathEndsWithin.includes(dataPath!.join("/")), dataPath!.join("/"))
  assert.equal((r1.pending as Record<string, unknown> | undefined)?.path, undefined, "path must ride data.path, not the Request root")
  // 3. both levels parked (suspended), zero tokens burning
  assert.ok(runtime.list().every((h) => h.state === "suspended"))

  const askerBefore = runtime.list().find((h) => h.id.includes("asker"))!.tokens
  const spawnsBefore = runtime.trace.filter((l) => l.includes(": spawned")).length

  await runtime.resume({ id: r1.pending!.id, ok: true })

  const trace = runtime.trace.join("\n")
  // 4. leaf resumed AT its checkpoint (prior turns preserved)
  assert.ok(trace.includes("resume with Answer(ok=true) at checkpoint"), trace)
  // 5. parent cascade REATTACHED to the finished child (no duplicate, no re-execution)
  for (const s of fx.phase2_expect.traceContains) assert.ok(trace.includes(s), trace)
  assert.equal(runtime.trace.filter((l) => l.includes(": spawned")).length, spawnsBefore, "no duplicate child ids")
  // 6. parent reached done after the cascade
  assert.equal(p.state, fx.phase2_expect.finalParentState)
  // 7. the child did not restart from scratch (usage grew, never reset)
  assert.ok(runtime.list().find((h) => h.id.includes("asker"))!.tokens > askerBefore)
  // transition-trace parity with the shared fixture
  for (const [id, expected] of Object.entries(fx.phase2_expect.transitions)) {
    assertTransitions(runtime.trace, id, expected as string[])
  }
})

test("§7D: a durable pending REWINDS the stored transcript to the pre-turn checkpoint", async () => {
  const runtime = rt()
  const p = runtime.spawn(runtime.root, "approverParent")
  assert.ok(!isVerbError(p))
  runtime.wake(p, "do the secret thing")
  const r1 = await runtime.wait(p)
  assert.equal(r1.status, "pending")

  // Both handles' transcripts are rewound: no halted-tool placeholder survives —
  // a resumed parent must re-invoke `task` (reattachment is the idempotency seam).
  for (const h of [p, ...p.children]) {
    const t = (await runtime.store.get(h.id)) ?? []
    assert.equal(t.length, 0, `expected rewound (empty) transcript for ${h.id}`)
  }

  await runtime.resume({ id: r1.pending!.id, ok: true })
  // After resume, the replayed turn (including the REAL task result) is persisted.
  const parentT = (await runtime.store.get(p.id)) ?? []
  assert.ok(parentT.some((m: any) => m.role === "tool" && String(m.content).includes("asker-done: secret-token")))
})

// ===========================================================================
// S5 — the unsolicited rail: coalesced drain, provenance, timer dedupe
// (examples/subagent-lifecycle · coalescedDrain)
// ===========================================================================

test("S5: posts coalesce into ONE turn, provenance marked, ticks dedupe (fixture: subagent-lifecycle)", async () => {
  const fx = fixture("subagent-lifecycle").scenarios.coalescedDrain
  const runtime = rt()
  const peer = runtime.spawn(runtime.root, "peer")
  assert.ok(!isVerbError(peer))
  for (const step of fx.steps) {
    if (!step.post) continue
    runtime.post(peer, { from: step.post.from ?? "x", channel: step.post.channel ?? "peer", text: step.post.text })
  }
  runtime.wake(peer)
  const r = await runtime.wait(peer)
  // 1. one wake = ONE turn for all items
  assert.equal(r.turns, fx.expect.turns)
  // 2. 2 messages + 3 ticks coalesced to 3 rendered items
  assert.equal(r.text, fx.expect.text)
})

// ===========================================================================
// S6 — backpressure: inbox gate, concurrency gate, global turn gate
// (examples/subagent-lifecycle · inboxGate/concurrencyGate/turnGate)
// ===========================================================================

test("S6: inbox gate rejects loudly; concurrency gate queues FIFO; global turn gate caps LLM calls", async () => {
  const scenarios = fixture("subagent-lifecycle").scenarios

  // 1. inbox gate: bounded, full ⇒ loud synchronous reject to the sender
  const rt1 = rt({ inboxCap: scenarios.inboxGate.runtime.inboxCap })
  const peer = rt1.spawn(rt1.root, "peer")
  assert.ok(!isVerbError(peer))
  rt1.post(peer, { from: "a", channel: "peer", text: "1" })
  rt1.post(peer, { from: "a", channel: "peer", text: "2" })
  const third = rt1.post(peer, { from: "a", channel: "peer", text: "3" })
  assert.ok(!third.ok && third.error.includes("inbox full"))

  // 2. concurrency gate: 2nd child QUEUED then DEQUEUED via slot transfer; still completes
  const rt2 = rt({ registry: registry({ coordinator: { budget: { maxConcurrent: 1 } } }) })
  const c2 = rt2.spawn(rt2.root, "coordinator")
  assert.ok(!isVerbError(c2))
  rt2.wake(c2, scenarios.concurrencyGate.run.prompt)
  const r2 = await rt2.wait(c2)
  assert.equal(r2.status, scenarios.concurrencyGate.expect.status)
  for (const s of scenarios.concurrencyGate.expect.traceContains) {
    assert.ok(rt2.trace.some((l) => l.includes(s)), `missing "${s}"`)
  }
  // 3. never more child turns in flight than the slot allows (parent's own turn may overlap)
  assert.ok(rt2.maxObservedConcurrentTurns <= 2, String(rt2.maxObservedConcurrentTurns))

  // 4. global turn gate wraps ONLY the LLM HTTP call — cap 1 cannot deadlock the tree
  const rt3 = rt({ maxConcurrentTurns: scenarios.turnGate.runtime.maxConcurrentTurns })
  const c3 = rt3.spawn(rt3.root, "coordinator")
  assert.ok(!isVerbError(c3))
  rt3.wake(c3, scenarios.turnGate.run.prompt)
  const r3 = await rt3.wait(c3)
  assert.equal(r3.status, scenarios.turnGate.expect.status)
  assert.equal(rt3.maxObservedConcurrentTurns, scenarios.turnGate.expect.maxObservedConcurrentTurns)
})

test("§7D: the turn-gate slot is released on acquirer death (killed Run cannot starve the tree)", async () => {
  newSlow()
  const runtime = rt({ maxConcurrentTurns: 1 })
  const s = runtime.spawn(runtime.root, "slow")
  const p = runtime.spawn(runtime.root, "peer")
  assert.ok(!isVerbError(s) && !isVerbError(p))
  runtime.wake(s, "work slowly")
  await slowCtl.started.promise // slow now HOLDS the single slot
  runtime.wake(p, "go") // peer queues on the gate
  runtime.interrupt(s) // kill the holder mid-HTTP-call
  const r = await runtime.wait(p) // queued acquirer proceeds — no deadlock
  assert.equal(r.status, "done")
  assert.equal((await runtime.wait(s)).status, "interrupted")
})

// ===========================================================================
// S7 — budgets: carve, live ancestor walk, loud incomplete
// (examples/subagent-budgets)
// ===========================================================================

test("S7: hierarchical carve · live-chain exhaustion = incomplete · maxTurns loud (fixture: subagent-budgets)", async () => {
  const fx = fixture("subagent-budgets")
  const runtime = rt()
  const c = runtime.spawn(runtime.root, "coordinator", fx.steps[0].spawn.budget)
  assert.ok(!isVerbError(c))
  const kid = runtime.spawn(c, "explore", fx.steps[1].spawn.budget)
  assert.ok(!isVerbError(kid))
  // 1. carve: child asked 500, parent pool 100 → effective 100
  assert.equal(kid.pool.tokens, fx.steps[1].expect.effectiveTokens)
  const kid2 = runtime.spawn(c, "explore")
  assert.ok(!isVerbError(kid2))
  // 2. maxChildren enforced at spawn (uniform error result, never a throw)
  const kid3 = runtime.spawn(c, "explore")
  assert.ok(isVerbError(kid3) && kid3.error.includes("maxChildren"))

  runtime.wake(kid, "go")
  await runtime.wait(kid)
  // 3. roll-up drains the PARENT pool too (the ledger)
  assert.equal(c.pool.tokens, fx.steps[4].expect.parentPoolAfter)

  runtime.wake(kid2, "go")
  const r2 = await runtime.wait(kid2)
  // 4. pool 20 > 0 at admission — the live walk still allows the sibling
  assert.equal(r2.status, fx.steps[5].expect.status)

  runtime.wake(kid2, "again")
  const r3 = await runtime.wait(kid2)
  // 5. ancestor pool exhausted → status=incomplete, partial work preserved, NO crash
  assert.equal(r3.status, fx.steps[6].expect.status)
  assert.ok(r3.text.includes(fx.steps[6].expect.errorContains), r3.text)

  // 6. maxTurns cap is LOUD: status=incomplete, never a silent done
  const rt4 = rt()
  const lp = rt4.spawn(rt4.root, "looper", fx.steps[7].spawn.budget)
  assert.ok(!isVerbError(lp))
  rt4.wake(lp, "loop forever")
  const rl = await rt4.wait(lp)
  assert.equal(rl.status, fx.steps[8].expect.status)
})

// ===========================================================================
// S8 — interrupt: aborts the TURN, not the agent (transactional drain restore)
// (examples/subagent-lifecycle · interrupt)
// ===========================================================================

test("S8: interrupt = turn-abort → idle with inbox restored; never a kill (fixture: subagent-lifecycle)", async () => {
  const fx = fixture("subagent-lifecycle").scenarios.interrupt
  newSlow()
  const runtime = rt()
  const s = runtime.spawn(runtime.root, "slow")
  assert.ok(!isVerbError(s))
  runtime.post(s, { from: "root", channel: "peer", text: "for later" })
  runtime.wake(s, "work slowly")
  await slowCtl.started.promise
  // 1. the agent is mid-turn (running)
  assert.equal(s.state, "running")
  const w = runtime.wait(s)
  runtime.interrupt(s)
  const r = await w
  // 2. the turn aborted → uniform isError result marked interrupted (no exception)
  assert.ok(r.isError)
  assert.equal(r.status, fx.expect.waiterStatus)
  // 3. the agent is IDLE (alive), not closed
  assert.equal(s.state, fx.expect.finalState)
  // 4. the transactional drain restored the item — inbox intact
  assert.equal(s.inbox.length, fx.expect.inboxLen)
})

test("§7D: forced close also restores drained inbox items (drain is transactional)", async () => {
  newSlow()
  const runtime = rt()
  const s = runtime.spawn(runtime.root, "slow")
  assert.ok(!isVerbError(s))
  runtime.post(s, { from: "root", channel: "peer", text: "for later" })
  runtime.wake(s, "work slowly")
  await slowCtl.started.promise
  await runtime.close(s, { force: true })
  assert.equal(s.state, "closed")
  assert.equal(s.inbox.length, 1, "drained item restored before close (close ≠ loss)")
})

// ===========================================================================
// S9 — close cascade: leaf-first; post-after-close rejected
// (examples/subagent-lifecycle · closeCascade)
// ===========================================================================

test("S9: close(root) cascades leaf-first; closed handles reject posts (fixture: subagent-lifecycle)", async () => {
  const fx = fixture("subagent-lifecycle").scenarios.closeCascade
  const closeOrder: string[] = []
  const record: AgentDef["onClose"] = async (h) => {
    closeOrder.push(h.id)
  }
  const runtime = rt({ registry: registry({ coordinator: { onClose: record }, peer: { onClose: record } }) })
  const c = runtime.spawn(runtime.root, "coordinator")
  assert.ok(!isVerbError(c))
  const k1 = runtime.spawn(c, "peer")
  assert.ok(!isVerbError(k1))
  const k2 = runtime.spawn(k1, "peer")
  assert.ok(!isVerbError(k2))
  await runtime.close(runtime.root)
  // 1. leaf-first order (grandchild → child → parent), all closed
  assert.deepEqual(closeOrder, [k2.id, k1.id, c.id])
  assert.ok(runtime.list().every((h) => h.state === "closed"))
  // 2. post after close = loud error to the sender
  const late = runtime.post(k1, { from: "x", channel: "peer", text: fx.steps[1].post.text })
  assert.ok(!late.ok && late.error.includes(fx.steps[1].expect.errorContains))
})

// ===========================================================================
// S10/S11 — the Level-1 surface: agent(), teams, souls, registry closure
// ===========================================================================

test("S10: Level-1 UX — agent() + team wiring + soul file", async () => {
  const soulPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "tn-agents-")), "AGENTS.md")
  fs.writeFileSync(soulPath, "You are the CODER. Fix things surgically.")
  const explore = agent("explore", { does: "read-only research", uses: { tools: [lookup] }, model: "m-explore-bug" })
  const coder = agent("coder", { does: "implements changes", soulFile: soulPath, team: [explore], model: "m-coder", budget: { maxTokens: 10_000 } })
  const r = await coder.run("fix the failing test", { fetch: mockFetch, clock: new VirtualClock() })
  // 1. the coding agent completes via its team
  assert.equal(r.status, "done")
  assert.ok(r.text.includes("bug at line 42"), r.text)
  // 2. the soul FILE reached the child's system prompt
  assert.ok(r.text.includes("[soul:loaded]"), r.text)
})

test("S11: team scoping — the registry is the reachable graph; task targets = the team", () => {
  const stranger = agent("stranger", { does: "should be unreachable", model: "m-explore" })
  void stranger
  const explore = agent("explore", { does: "read-only research", uses: { tools: [lookup] }, model: "m-explore" })
  const coder = agent("coder", { does: "implements", team: [explore], model: "m-coder" })
  const reg = coder.registry()
  // 1. registry contains ONLY the transitive team closure
  assert.equal(Object.keys(reg).sort().join(","), "coder,explore")
  // 2. the coder's task targets are exactly its team
  assert.deepEqual(reg.coder.team, ["explore"])
})

test("§7D: task outside the team is refused with the team names listed; no team ⇒ no task tool", async () => {
  const runtime = rt({
    registry: registry({ rogue: { name: "rogue", does: "tries to escape its team", model: "m-rogue", team: ["explore"] } }),
  })
  const h = runtime.spawn(runtime.root, "rogue")
  assert.ok(!isVerbError(h))
  runtime.wake(h, "go")
  const r = await runtime.wait(h)
  assert.ok(r.text.includes(`not in this agent's team`), r.text)
  assert.ok(r.text.includes("explore"), "the refusal lists the team names")

  // Delegation is opt-in: an agent WITHOUT a team gets no task tool at all.
  const rt2 = rt({ registry: registry({ teamless: { name: "teamless", does: "no delegation rights", model: "m-rogue" } }) })
  const t = rt2.spawn(rt2.root, "teamless")
  assert.ok(!isVerbError(t))
  rt2.wake(t, "go")
  const r2 = await rt2.wait(t)
  assert.ok(r2.text.includes("Unknown tool: task"), r2.text)
})

test("§7D: graceful close escalates to interrupt after shutdownMs (virtual clock)", async () => {
  newSlow()
  const clock = new VirtualClock()
  const runtime = rt({ clock, shutdownMs: 200 })
  const s = runtime.spawn(runtime.root, "slow")
  assert.ok(!isVerbError(s))
  runtime.post(s, { from: "root", channel: "peer", text: "for later" })
  runtime.wake(s, "work slowly")
  await slowCtl.started.promise
  const closing = runtime.close(runtime.root) // graceful: bounded by shutdownMs, then escalate
  await flush()
  assert.equal(s.state, "running", "still inside the shutdown grace window")
  clock.advance(200)
  await closing
  assert.equal(s.state, "closed")
  assert.equal(s.inbox.length, 1, "escalated abort restored the drained item before close")
})

// ===========================================================================
// S12 — persona pattern on the runtime primitives: soul sections, heartbeat on
// the virtual clock, silent HEARTBEAT_OK, clean stop. (The agent-home sugar —
// agentFromDir/startAgent — ships in the next change; the substrate carries it.)
// ===========================================================================

test("S12: soul sections + virtual-clock heartbeat + silent OK + clean close", async () => {
  const sections = [
    "## SOUL.md\n\nYou are Mia. Warm, brief.",
    "## USER.md\n\nThe user is Muthu.",
    "## MEMORY.md\n\n- Likes green tea.",
    "## HEARTBEAT.md\n\nOn heartbeat: if it is watering day, remind to water the plants.",
  ].join("\n\n")
  const mia = agent("mia", { does: "persona agent", soul: sections, model: "m-mia" })

  // 1. soul sections reach the system prompt (## headings visible to the model)
  const direct = await mia.run("hello", { fetch: mockFetch, clock: new VirtualClock() })
  assert.equal(direct.text, "soul-sections:[SOUL.md,USER.md,MEMORY.md]")

  // Heartbeat = the unsolicited rail driven by the injectable clock (userland).
  const clock = new VirtualClock()
  const runtime = new AgentRuntime({ fetch: mockFetch, registry: mia.registry(), clock })
  const h = runtime.spawn(runtime.root, "mia")
  assert.ok(!isVerbError(h))
  const reports: string[] = []
  const beat = () => {
    runtime.post(h, { from: "clock", channel: "timer", text: "tick" })
    if (h.state === "idle") {
      runtime.wake(h, "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.")
      void runtime.wait(h).then((r) => {
        if (!r.isError && !r.text.includes("HEARTBEAT_OK")) reports.push(r.text)
      })
    }
    clock.setTimeout(beat, 25)
  }
  clock.setTimeout(beat, 25)
  for (let i = 0; i < 4; i++) {
    clock.advance(25)
    await flush()
  }
  // 2. the heartbeat woke the agent repeatedly
  const hbTurns = runtime.trace.filter((l) => l.includes("idle→running")).length
  assert.ok(hbTurns >= 2, `turns=${hbTurns}`)
  // 3. only substantive beats report (HEARTBEAT_OK stays silent)
  assert.ok(reports.every((t) => t.includes("water")), JSON.stringify(reports))

  // A quiet persona (no HEARTBEAT.md duty) reports NOTHING
  const quiet = agent("quiet", { does: "persona agent", soul: "## SOUL.md\n\nYou are calm.", model: "m-mia" })
  const clock2 = new VirtualClock()
  const rt2 = new AgentRuntime({ fetch: mockFetch, registry: quiet.registry(), clock: clock2 })
  const q = rt2.spawn(rt2.root, "quiet")
  assert.ok(!isVerbError(q))
  const quietReports: string[] = []
  rt2.post(q, { from: "clock", channel: "timer", text: "tick" })
  rt2.wake(q, "Heartbeat. If nothing needs attention, reply HEARTBEAT_OK.")
  const qr = await rt2.wait(q)
  if (!qr.isError && !qr.text.includes("HEARTBEAT_OK")) quietReports.push(qr.text)
  assert.deepEqual(quietReports, [])
  await rt2.close(rt2.root)

  // 4. the agent closes cleanly on stop
  await runtime.close(runtime.root)
  assert.equal(h.state, "closed")
})

// ===========================================================================
// S13 — the bridge: an Agent IS a Tool in the classic API
// ===========================================================================

test("S13: agent.asTool() inside the classic createToolkit/createClient API", async () => {
  const explore = agent("explore", { does: "read-only research", uses: { tools: [lookup] }, model: "m-explore-bug" })
  const toolkit = await createToolkit({ builtins: false, extraTools: [explore.asTool({ fetch: mockFetch, clock: new VirtualClock() })] })
  const client = createClient({ baseUrl: "http://mock.local", style: "openai", model: "m-old-api", apiKey: "test", fetch: mockFetch })
  const r = await client.run("summarize", { toolkit })
  // 1. the old API called the agent like any tool and got only its final text
  assert.ok(r.text.includes("old-api summary:") && r.text.includes("bug at line 42"), r.text)
  // 2. agent metadata surfaced through the tool result
  assert.equal(r.toolCalls[0]?.metadata?.agent, "explore")
})

// ===========================================================================
// Shipped-quality additions: runtime-wide store, wait semantics, capability
// rule, suspended-is-not-idle, and the §8 "incomplete" client status.
// ===========================================================================

test("§7D: ONE runtime-wide ConversationStore — a handle's transcript genuinely grows across turns", async () => {
  const runtime = rt()
  const peer = runtime.spawn(runtime.root, "peer")
  assert.ok(!isVerbError(peer))
  runtime.wake(peer, "first")
  await runtime.wait(peer)
  const after1 = (await runtime.store.get(peer.id)) ?? []
  assert.ok(after1.length > 0, "turn 1 persisted under the handle id")
  runtime.wake(peer, "second")
  await runtime.wait(peer)
  const after2 = (await runtime.store.get(peer.id)) ?? []
  assert.ok(after2.length > after1.length, `history must grow (${after1.length} → ${after2.length})`)
  const userMsgs = after2.filter((m: any) => m.role === "user").map((m: any) => String(m.content))
  assert.ok(userMsgs.some((c) => c.includes("first")) && userMsgs.some((c) => c.includes("second")))
})

test("§7D: wait resolves with the next result or the LAST result (settled handles answer immediately)", async () => {
  const runtime = rt()
  const peer = runtime.spawn(runtime.root, "peer")
  assert.ok(!isVerbError(peer))
  runtime.wake(peer, "go")
  const first = await runtime.wait(peer)
  assert.equal(first.status, "done")
  // registration AFTER completion: resolves immediately with the recorded result
  const again = await runtime.wait(peer)
  assert.deepEqual(again, first)
})

test("§7D: wait timeout is an explicit timeout error and the child keeps running (virtual clock)", async () => {
  newSlow()
  const clock = new VirtualClock()
  const runtime = rt({ clock })
  const s = runtime.spawn(runtime.root, "slow")
  assert.ok(!isVerbError(s))
  runtime.wake(s, "work slowly")
  await slowCtl.started.promise
  const w = runtime.wait(s, { timeoutMs: 100 })
  clock.advance(100)
  const r = await w
  assert.equal(r.status, "timeout")
  assert.equal(s.state, "running", "the child keeps running after a wait timeout")
  slowCtl.gate.resolve()
  assert.equal((await runtime.wait(s)).status, "done")
})

test("§7D: waiting is reserved to the spawner (handles are capabilities)", async () => {
  const runtime = rt()
  const a = runtime.spawn(runtime.root, "peer")
  const b = runtime.spawn(runtime.root, "slow")
  assert.ok(!isVerbError(a) && !isVerbError(b))
  const refused = await runtime.wait(a, { by: b }) // b did not spawn a
  assert.ok(refused.isError && refused.text.includes("only the spawner"))
})

test("§7D: suspended is not idle — posts buffer, wakes do not transition; only the Answer wakes", async () => {
  const runtime = rt()
  const p = runtime.spawn(runtime.root, "approverParent")
  assert.ok(!isVerbError(p))
  runtime.wake(p, "do the secret thing")
  const r1 = await runtime.wait(p)
  assert.equal(r1.status, "pending")
  const posted = runtime.post(p, { from: "external", channel: "external", text: "nudge" })
  assert.ok(posted.ok)
  const woke = runtime.wake(p, "hurry up")
  assert.ok(woke.ok)
  assert.equal(p.state, "suspended", "no transition without the Answer")
  assert.equal(p.inbox.length, 1, "the item buffered")
  await runtime.resume({ id: r1.pending!.id, ok: true })
  assert.equal(p.state, "idle")
})

test("§7D: interrupt on a suspended handle cancels the pending Request → idle (escape hatch)", async () => {
  const runtime = rt()
  const p = runtime.spawn(runtime.root, "approverParent")
  assert.ok(!isVerbError(p))
  runtime.wake(p, "do the secret thing")
  const r1 = await runtime.wait(p)
  assert.equal(r1.status, "pending")
  const leaf = p.children[0]
  runtime.interrupt(leaf)
  assert.equal(leaf.state, "idle")
  assert.equal(leaf.pendingRequest, undefined)
})

test("§8 addendum: a maxTurns stop with no final text is status incomplete on the classic client", async () => {
  const toolkit = await createToolkit({ builtins: false, extraTools: [lookup] })
  const client = createClient({ baseUrl: "http://mock.local", style: "openai", model: "m-loop", apiKey: "test", fetch: mockFetch, maxTurns: 2 })
  const r = await client.run("loop forever", { toolkit })
  assert.equal(r.status, "incomplete")
  assert.equal(r.limit, "maxTurns")
  assert.equal(r.turns, 2)

  // a normal completion is untouched
  const done = await createClient({ baseUrl: "http://mock.local", style: "openai", model: "m-peer", apiKey: "test", fetch: mockFetch }).run("hi", { toolkit })
  assert.equal(done.status, "done")
  assert.equal(done.limit, undefined)
})
