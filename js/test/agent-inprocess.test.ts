/**
 * Tests for ADR 0030 / issue #95: agents.RuntimeOptions.inProcess, the semantic
 * counterpart to RuntimeOptions.fetch, so a host whose model is a plain function
 * doesn't have to hand-build a `fetch`-shaped adapter to reach the sub-agent
 * runtime. Ported from spikes/inprocess-subagent/SPIKE.md and the Go reference
 * at golang/agents/inprocess_test.go.
 *
 * Run: npm run build && node --experimental-strip-types --test test/agent-inprocess.test.ts
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import { agents, createInProcessClient, createToolkit, type InProcessRequest, type InProcessResponse } from "../dist/index.js"

const { AgentRuntime, isVerbError } = agents

/**
 * scriptedModel is the fake model shared by BOTH the top-level client and the
 * sub-agent runtime below — the reporter's actual case. It never touches
 * HTTP/fetch: it is handed the assembled InProcessRequest and returns one
 * InProcessResponse. It instruments concurrency so the gate test can assert
 * on real overlap, not just a trust-me counter.
 */
class ScriptedModel {
  inFlight = 0
  maxSeen = 0
  overlaps = 0
  calls = 0
  holdMs: number
  constructor(holdMs = 0) {
    this.holdMs = holdMs
  }

  generate = async (req: InProcessRequest): Promise<InProcessResponse> => {
    this.inFlight++
    this.calls++
    if (this.inFlight > this.maxSeen) this.maxSeen = this.inFlight
    if (this.inFlight > 1) this.overlaps++
    try {
      if (this.holdMs > 0) await new Promise((r) => setTimeout(r, this.holdMs))
      return { content: `ok model=${req.model} turn-done` }
    } finally {
      this.inFlight--
    }
  }
}

// ---------------------------------------------------------------------------
// 1. ONE generate function serving BOTH createInProcessClient (top-level) AND
//    the sub-agent runtime's new `inProcess` option, with no copied adapter
//    code — AgentRuntime builds its `fetch` by calling the SAME
//    createInProcessFetch export that createInProcessClient calls.
// ---------------------------------------------------------------------------

test("inProcess: one generate() serves both the top-level client and the sub-agent runtime", async () => {
  const m = new ScriptedModel()

  // Top-level client, driven by m.generate directly.
  const client = createInProcessClient({ model: "spike-model", generate: m.generate })
  const tk = await createToolkit({ builtins: false })
  const topRes = await client.run("hello", { toolkit: tk })
  assert.equal(topRes.status, "done", topRes.text)
  await tk.close()

  // Sub-agent runtime, driven by the SAME m.generate via RuntimeOptions.inProcess.
  const rt = new AgentRuntime({
    inProcess: m.generate,
    registry: { worker: { name: "worker", does: "a scripted worker" } },
  })
  const h = rt.spawn(rt.root, "worker")
  assert.ok(!isVerbError(h))
  rt.wake(h as agents.Handle, "do the thing")
  const subRes = await rt.wait(h as agents.Handle)
  assert.equal(subRes.status, "done", subRes.text)

  assert.equal(m.calls, 2, "one top-level ask, one sub-agent turn")
})

// ---------------------------------------------------------------------------
// 2. Construction-time validation errors when both the wire-shaped option and
//    the new `inProcess` option are set — never resolved by precedence.
// ---------------------------------------------------------------------------

test("inProcess: fetch + inProcess together throws at construction", () => {
  const m = new ScriptedModel()
  assert.throws(
    () =>
      new AgentRuntime({
        inProcess: m.generate,
        fetch: (async () => new Response("{}")) as typeof fetch,
        registry: { worker: { name: "worker", does: "a scripted worker" } },
      }),
    /fetch and RuntimeOptions.inProcess are mutually exclusive/,
  )
})

test("inProcess: llm + inProcess together throws at construction", () => {
  const m = new ScriptedModel()
  assert.throws(
    () =>
      new AgentRuntime({
        inProcess: m.generate,
        llm: { baseUrl: "http://example.invalid" },
        registry: { worker: { name: "worker", does: "a scripted worker" } },
      }),
    /llm and RuntimeOptions.inProcess are mutually exclusive/,
  )
})

// ---------------------------------------------------------------------------
// 3. The global turn/concurrency gate still applies on the new path, WITH a
//    negative control proving the overlap detector actually fires.
// ---------------------------------------------------------------------------

test("inProcess: global turn gate holds at maxConcurrentTurns=1 (zero overlaps observed)", async () => {
  const m = new ScriptedModel(15)
  const rt = new AgentRuntime({
    inProcess: m.generate,
    maxConcurrentTurns: 1,
    registry: { worker: { name: "worker", does: "a scripted worker" } },
  })

  const N = 5
  const results = await Promise.all(
    Array.from({ length: N }, (_, i) => {
      const h = rt.spawn(rt.root, "worker")
      assert.ok(!isVerbError(h))
      rt.wake(h as agents.Handle, `job ${i}`)
      return rt.wait(h as agents.Handle)
    }),
  )
  for (const r of results) assert.equal(r.status, "done", r.text)

  assert.equal(
    m.overlaps,
    0,
    `gate FALSIFIED: ${m.overlaps} call(s) observed >1 in flight (maxSeen=${m.maxSeen}) with ` +
      `maxConcurrentTurns=1 — the global turn gate did not wrap the inProcess transport`,
  )
  assert.equal(rt.maxObservedConcurrentTurns, 1)
})

test("inProcess: negative control — maxConcurrentTurns=5 DOES observe overlap (detector is live)", async () => {
  const m = new ScriptedModel(15)
  const rt = new AgentRuntime({
    inProcess: m.generate,
    maxConcurrentTurns: 5,
    registry: { worker: { name: "worker", does: "a scripted worker" } },
  })

  const N = 5
  const results = await Promise.all(
    Array.from({ length: N }, (_, i) => {
      const h = rt.spawn(rt.root, "worker")
      assert.ok(!isVerbError(h))
      rt.wake(h as agents.Handle, `job ${i}`)
      return rt.wait(h as agents.Handle)
    }),
  )
  for (const r of results) assert.equal(r.status, "done", r.text)

  assert.ok(
    m.overlaps > 0,
    `control INVALID: expected overlap with maxConcurrentTurns=5 and a 15ms hold, got 0 ` +
      `overlaps (maxSeen=${m.maxSeen}) — the detector proves nothing`,
  )
})
