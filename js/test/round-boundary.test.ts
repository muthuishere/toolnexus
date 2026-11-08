/**
 * SPEC §8 `afterRound` (change add-round-boundary-hook, #192). Shared fixture:
 * examples/round-boundary/fixture.json (R1-R6). Hermetic: scripted fetch.
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import { agents, createClient, createToolkit, defineTool } from "../dist/index.js"

function openaiFetch(replies: any[]) {
  const sent: any[] = []
  let i = 0
  const fetchImpl = async (_url: string, init: any) => {
    const body = JSON.parse(init.body)
    sent.push(body)
    const message = replies[Math.min(i++, replies.length - 1)]
    if (body.stream) {
      const chunks: string[] = []
      if (message.tool_calls) {
        const c = message.tool_calls[0]
        chunks.push(JSON.stringify({ choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: c.id, type: "function", function: { name: c.function.name, arguments: c.function.arguments } }] } }] }))
        chunks.push(JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] }))
      } else {
        chunks.push(JSON.stringify({ choices: [{ index: 0, delta: { content: message.content } }] }))
        chunks.push(JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: "stop" }] }))
      }
      const text = chunks.map((c) => `data: ${c}\n\n`).join("") + "data: [DONE]\n\n"
      return new Response(text, { status: 200, headers: { "content-type": "text/event-stream" } })
    }
    return new Response(JSON.stringify({
      choices: [{ index: 0, message, finish_reason: message.tool_calls ? "tool_calls" : "stop" }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
    }), { status: 200, headers: { "content-type": "application/json" } })
  }
  return { fetchImpl, sent }
}

const say = (content: string) => ({ role: "assistant", content })
const callEcho = { role: "assistant", content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "echo", arguments: JSON.stringify({ text: "hi" }) } }] }

async function kit() {
  return createToolkit({ builtins: false, extraTools: [defineTool({ name: "echo", description: "echo", inputSchema: { type: "object", properties: { text: { type: "string" } } }, run: async (a: any) => String(a.text) })] })
}

function client(fetchImpl: any, hooks: any, maxTurns = 10) {
  return createClient({ baseUrl: "http://scripted.invalid", style: "openai", model: "m", apiKey: "unused", fetch: fetchImpl, hooks, maxTurns } as any)
}

async function go(mode: "run" | "stream", c: any, toolkit: any) {
  if (mode === "run") return c.run("go", { toolkit })
  let res: any
  for await (const ev of c.stream("go", { toolkit })) if (ev.type === "done") res = ev.result
  return res
}

const tail = (body: any, n: number) => body.messages.slice(-n).map((m: any) => ({ role: m.role, content: m.content }))

for (const mode of ["run", "stream"] as const) {
  test(`R1 inject after tool results (${mode})`, async () => {
    const { fetchImpl, sent } = openaiFetch([callEcho, say("done")])
    const calls: any[] = []
    const c = client(fetchImpl, { afterRound: (ev: any) => { calls.push({ turn: ev.turn, final: ev.final, text: ev.text }); return ev.final ? undefined : { inject: ["todo: 2 open"] } } })
    const r = await go(mode, c, await kit())
    assert.equal(sent.length, 2)
    assert.equal(r.text, "done")
    assert.deepEqual(tail(sent[1], 2), [{ role: "tool", content: "hi" }, { role: "user", content: "todo: 2 open" }])
    assert.deepEqual(calls, [{ turn: 0, final: false, text: "" }, { turn: 1, final: true, text: "done" }])
  })

  test(`R2 continue when the model stops (${mode})`, async () => {
    const { fetchImpl, sent } = openaiFetch([say("first"), say("second")])
    const calls: any[] = []
    const c = client(fetchImpl, { afterRound: (ev: any) => { calls.push({ turn: ev.turn, final: ev.final, text: ev.text }); return ev.final && ev.turn === 0 ? { inject: ["keep going"] } : undefined } })
    const r = await go(mode, c, await kit())
    assert.equal(sent.length, 2)
    assert.equal(r.text, "second")
    assert.deepEqual(tail(sent[1], 2), [{ role: "assistant", content: "first" }, { role: "user", content: "keep going" }])
    assert.deepEqual(calls, [{ turn: 0, final: true, text: "first" }, { turn: 1, final: true, text: "second" }])
  })

  test(`R3 empty injection ends normally (${mode})`, async () => {
    const { fetchImpl, sent } = openaiFetch([say("only")])
    let n = 0
    const r = await go(mode, client(fetchImpl, { afterRound: () => { n++; return { inject: [""] } } }), await kit())
    assert.equal(sent.length, 1); assert.equal(r.text, "only"); assert.equal(r.status, "done"); assert.equal(n, 1)
  })

  test(`R4 no continuation past maxTurns (${mode})`, async () => {
    const { fetchImpl, sent } = openaiFetch([say("only")])
    let n = 0
    const r = await go(mode, client(fetchImpl, { afterRound: () => { n++; return { inject: ["again"] } } }, 1), await kit())
    assert.equal(sent.length, 1); assert.equal(r.text, "only"); assert.equal(n, 0)
  })

  test(`R5 a failing hook stops the run (${mode})`, async () => {
    const { fetchImpl, sent } = openaiFetch([callEcho, say("never")])
    await assert.rejects(go(mode, client(fetchImpl, { afterRound: () => { throw new Error("round-boom") } }), await kit()), /round-boom/)
    assert.equal(sent.length, 1)
  })
}

test("R2 continue when the model stops (anthropic run)", async () => {
  const sent: any[] = []
  const texts = ["first", "second"]
  const fetchImpl = async (_u: string, init: any) => {
    sent.push(JSON.parse(init.body))
    const t = texts[Math.min(sent.length - 1, 1)]
    return new Response(JSON.stringify({ content: [{ type: "text", text: t }], stop_reason: "end_turn", usage: { input_tokens: 1, output_tokens: 1 } }), { status: 200, headers: { "content-type": "application/json" } })
  }
  const c = createClient({ baseUrl: "http://scripted.invalid", style: "anthropic", model: "m", apiKey: "unused", fetch: fetchImpl, hooks: { afterRound: (ev: any) => (ev.final && ev.turn === 0 ? { inject: ["keep going"] } : undefined) } } as any)
  const r = await c.run("go", { toolkit: await kit() })
  assert.equal(sent.length, 2); assert.equal(r.text, "second")
  assert.deepEqual(sent[1].messages.slice(-1), [{ role: "user", content: "keep going" }])
})

test("hook absent: byte-identical requests", async () => {
  const a = openaiFetch([callEcho, say("done")])
  const b = openaiFetch([callEcho, say("done")])
  await client(a.fetchImpl, undefined).run("go", { toolkit: await kit() })
  await client(b.fetchImpl, { afterRound: () => undefined }).run("go", { toolkit: await kit() })
  assert.deepEqual(a.sent, b.sent)
})

test("R6 the §7D agent path forwards afterRound", async () => {
  const { fetchImpl, sent } = openaiFetch([say("first"), say("second")])
  const { AgentRuntime, isVerbError } = agents as any
  const rt = new AgentRuntime({
    fetch: fetchImpl,
    registry: { a: { name: "a", does: "x", soul: "soul-a", model: "m" } },
    hooks: { afterRound: (ev: any) => (ev.final && ev.turn === 0 ? { inject: ["keep going"] } : undefined) },
  })
  const h = rt.spawn(rt.root, "a")
  assert.ok(!isVerbError(h))
  rt.wake(h, "go")
  const r = await rt.wait(h)
  await rt.close(rt.root)
  assert.equal(sent.length, 2)
  assert.equal(r.text, "second")
})
