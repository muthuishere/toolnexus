/**
 * The §8 seams on an agent run (SPEC.md §7D, "The §8 seams on an agent run").
 *
 * Shared fixture: `examples/agent-hooks/fixture.json` (scenarios H1-H6).
 *
 * `hooks` and `onMetric` are optional on BOTH `RuntimeOptions` and `AgentDef`,
 * resolved def-over-runtime (replace, never merge; each field independently) and
 * forwarded verbatim into the client the runtime builds for each turn. This is how
 * a §7F compactor reaches a §7D agent — and it interacts with the §10 durable-pending
 * rewind: a turn that compacts and then suspends is rewound to its FULL pre-turn
 * transcript, the compaction discarded.
 *
 * Hermetic: zero network, the LLM is an in-process recording `fetch`.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { agents, defineTool, pending, type Tool } from "../dist/index.js"

const { AgentRuntime, isVerbError, compactor } = agents
type AgentDef = agents.AgentDef

// ---------------------------------------------------------------------------
// Recording mock LLM — captures the exact transcript each turn sent, by model.
// ---------------------------------------------------------------------------

function openaiResponse(message: Record<string, unknown>) {
  return new Response(
    JSON.stringify({
      choices: [{ message: { role: "assistant", ...message } }],
      usage: { prompt_tokens: 30, completion_tokens: 10, total_tokens: 40 },
    }),
    { status: 200, headers: { "content-type": "application/json" } },
  )
}

class RecordingLLM {
  readonly sent: Record<string, any[][]> = {}
  readonly fetch: typeof fetch = async (_url, init) => {
    const body = JSON.parse(String(init?.body))
    const msgs: any[] = body.messages
    ;(this.sent[body.model] ??= []).push(msgs)
    if (body.model === "m-asker") {
      const toolMsgs = msgs.filter((m) => m.role === "tool")
      const approved = toolMsgs.some((m) => String(m.content).includes("secret-token"))
      if (!approved)
        return openaiResponse({
          content: null,
          tool_calls: [{ id: "a1", type: "function", function: { name: "check_secret", arguments: "{}" } }],
        })
      return openaiResponse({ content: "asker-done: secret-token" })
    }
    return openaiResponse({ content: "ok" })
  }
  last(model: string): any[] {
    const runs = this.sent[model] ?? []
    return runs[runs.length - 1] ?? []
  }
  count(model: string): number {
    return (this.sent[model] ?? []).length
  }
}

const checkSecret: Tool = defineTool({
  name: "check_secret",
  description: "needs human approval",
  inputSchema: { type: "object", properties: {} },
  run: async (_a: Record<string, unknown>, ctx?: { answer?: { ok: boolean } }) => {
    if (ctx?.answer?.ok) return "secret-token"
    return pending({ kind: "approval", prompt: "approve secret access?" })
  },
})

function reg(...defs: AgentDef[]): Record<string, AgentDef> {
  const m: Record<string, AgentDef> = {}
  for (const d of defs) m[d.name] = d
  return m
}

/** Seed a handle's stored transcript: leading soul + `pairs` user/assistant pairs. */
async function seedTranscript(runtime: agents.AgentRuntime, id: string, pairs: number): Promise<any[]> {
  const msgs: any[] = [{ role: "system", content: "SOUL-VERBATIM" }]
  for (let i = 0; i < pairs; i++) {
    msgs.push({ role: "user", content: "q".repeat(200) })
    msgs.push({ role: "assistant", content: "a".repeat(200) })
  }
  await runtime.store.save(id, msgs)
  return msgs
}

// ---------------------------------------------------------------------------
// 1 — a runtime-level beforeLLM runs inside an agent turn and reaches the wire
// ---------------------------------------------------------------------------

test("§7D seams: a runtime `hooks.beforeLLM` runs inside an agent turn and its rewrite reaches the wire", async () => {
  const mock = new RecordingLLM()
  let saw = 0
  const runtime = new AgentRuntime({
    fetch: mock.fetch,
    registry: reg({ name: "a", does: "x", soul: "soul-a", model: "m-a" }),
    hooks: {
      beforeLLM: (ev) => {
        saw++
        return { messages: [...ev.messages, { role: "system", content: "INJECTED-BY-HOOK" }] }
      },
    },
  })
  const h = runtime.spawn(runtime.root, "a")
  assert.ok(!isVerbError(h))
  runtime.wake(h, "hello")
  const r = await runtime.wait(h)
  assert.equal(r.isError, false, r.text)

  assert.ok(saw > 0, "beforeLLM never ran inside the agent turn")
  const sent = mock.last("m-a")
  assert.ok(
    sent.some((m: any) => m.content === "INJECTED-BY-HOOK"),
    `hook rewrite did not reach the wire; sent=${JSON.stringify(sent)}`,
  )
  await runtime.close(runtime.root)
})

// ---------------------------------------------------------------------------
// 2 — a runtime-level onMetric receives the §8 semantic events
// ---------------------------------------------------------------------------

test("§7D seams: a runtime `onMetric` receives llm + run events from an agent turn", async () => {
  const mock = new RecordingLLM()
  const events: string[] = []
  const runtime = new AgentRuntime({
    fetch: mock.fetch,
    registry: reg({ name: "a", does: "x", model: "m-a" }),
    onMetric: (ev) => events.push(ev.event),
  })
  const h = runtime.spawn(runtime.root, "a")
  assert.ok(!isVerbError(h))
  runtime.wake(h, "hi")
  const r = await runtime.wait(h)
  assert.equal(r.isError, false, r.text)

  assert.ok(events.includes("llm"), `expected an "llm" event, got ${JSON.stringify(events)}`)
  assert.ok(events.includes("run"), `expected a "run" event, got ${JSON.stringify(events)}`)
  await runtime.close(runtime.root)
})

// ---------------------------------------------------------------------------
// 3 — def-level REPLACES runtime-level, for that agent only
// ---------------------------------------------------------------------------

test("§7D seams: def `hooks` REPLACE the runtime's for that agent (never merged); siblings inherit", async () => {
  const mock = new RecordingLLM()
  const fired: Record<string, number> = { runtime: 0, defA: 0 }
  const mark = (tag: string): agents.AgentDef["hooks"] => ({
    beforeLLM: () => {
      fired[tag]++
    },
  })
  const runtime = new AgentRuntime({
    fetch: mock.fetch,
    hooks: mark("runtime"),
    registry: reg(
      { name: "a", does: "x", model: "m-a", hooks: mark("defA") },
      { name: "b", does: "x", model: "m-b" },
    ),
  })
  const ha = runtime.spawn(runtime.root, "a")
  const hb = runtime.spawn(runtime.root, "b")
  assert.ok(!isVerbError(ha) && !isVerbError(hb))
  runtime.wake(ha, "hi")
  assert.equal((await runtime.wait(ha)).isError, false)
  runtime.wake(hb, "hi")
  assert.equal((await runtime.wait(hb)).isError, false)

  assert.equal(fired.defA, 1, "agent A must run its own hook exactly once")
  assert.equal(fired.runtime, 1, "the runtime hook must run ONLY for B — never merged into A")
  await runtime.close(runtime.root)
})

// ---------------------------------------------------------------------------
// 4 — the two fields resolve INDEPENDENTLY
// ---------------------------------------------------------------------------

test("§7D seams: `hooks` and `onMetric` resolve independently — overriding one inherits the other", async () => {
  const mock = new RecordingLLM()
  let defHook = 0
  const metricModels: string[] = []
  const runtime = new AgentRuntime({
    fetch: mock.fetch,
    hooks: {
      beforeLLM: () => {
        throw new Error("runtime hooks must not run for an agent whose def overrides `hooks`")
      },
    },
    onMetric: (ev) => {
      if (ev.event === "llm") metricModels.push(ev.model)
    },
    registry: reg({
      name: "a",
      does: "x",
      model: "m-a",
      // overrides `hooks` but NOT `onMetric`
      hooks: {
        beforeLLM: () => {
          defHook++
        },
      },
    }),
  })
  const h = runtime.spawn(runtime.root, "a")
  assert.ok(!isVerbError(h))
  runtime.wake(h, "hi")
  const r = await runtime.wait(h)
  assert.equal(r.isError, false, r.text)

  assert.equal(defHook, 1, "the def's hook ran")
  assert.deepEqual(metricModels, ["m-a"], "the runtime's onMetric is still inherited")
  await runtime.close(runtime.root)
})

// ---------------------------------------------------------------------------
// 5 — THE POINT: a real §7F compactor attached per-agent compacts an agent turn
// ---------------------------------------------------------------------------

test("§7D seams: a §7F compactor attached via a def's `hooks` compacts an agent run", async () => {
  const mock = new RecordingLLM()
  const runtime = new AgentRuntime({
    fetch: mock.fetch,
    registry: reg({
      name: "long",
      does: "x",
      soul: "SOUL-VERBATIM",
      model: "m-a",
      hooks: {
        beforeLLM: compactor({ maxTokens: 200, keepTail: 80, summarize: () => "SUMMARY-OF-HEAD" }),
      },
    }),
  })
  const h = runtime.spawn(runtime.root, "long")
  assert.ok(!isVerbError(h))
  const pre = await seedTranscript(runtime, h.id, 40)
  assert.equal(((await runtime.store.get(h.id)) ?? []).length, 81, "seed must land on the handle's conversation id")

  runtime.wake(h, "next question")
  const r = await runtime.wait(h)
  assert.equal(r.isError, false, r.text)

  const sent = mock.last("m-a")
  assert.ok(sent.length < pre.length, `compaction did not shrink the transcript: pre=${pre.length} sent=${sent.length}`)
  assert.ok(sent.length < 20, `expected a much smaller wire transcript, got ${sent.length}`)
  assert.equal(sent[0]?.content, "SOUL-VERBATIM", "the leading system prompt must survive verbatim")
  assert.ok(JSON.stringify(sent).includes("SUMMARY-OF-HEAD"), "the summary system message is missing from the wire transcript")
  await runtime.close(runtime.root)
})

// ---------------------------------------------------------------------------
// 6 — §10 interaction: compact-then-suspend rewinds to the FULL pre-turn transcript
// ---------------------------------------------------------------------------

test("§7D seams: a turn that compacts and THEN suspends is rewound to its FULL pre-turn transcript", async () => {
  const mock = new RecordingLLM()
  const runtime = new AgentRuntime({
    fetch: mock.fetch,
    registry: reg({
      name: "asker",
      does: "needs approvals",
      model: "m-asker",
      tools: [checkSecret],
      hooks: {
        beforeLLM: compactor({ maxTokens: 200, keepTail: 80, summarize: () => "SUMMARY-OF-HEAD" }),
      },
    }),
  })
  const h = runtime.spawn(runtime.root, "asker")
  assert.ok(!isVerbError(h))
  const pre = await seedTranscript(runtime, h.id, 40)

  runtime.wake(h, "get the secret")
  const r = await runtime.wait(h)
  assert.equal(r.status, "pending", `fixture did not suspend: ${r.status} ${r.text}`)

  // the turn DID compact before it suspended
  const sent = mock.last("m-asker")
  assert.ok(sent.length < pre.length, "the suspended turn should have compacted on the wire")

  const post = (await runtime.store.get(h.id)) ?? []
  assert.equal(post.length, pre.length, "a compacted turn that suspends must rewind to the full pre-turn transcript")
  assert.deepEqual(post, pre, "the rewound transcript must be the pre-turn one, compaction discarded")
  await runtime.close(runtime.root)
})
