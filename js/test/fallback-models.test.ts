// §8 Resilience: fallbackModels — opt-in model fallback chain on overload (503/529).
import { test } from "node:test"
import assert from "node:assert/strict"
import { createClient } from "../dist/index.js"

/** A fake fetch: `plan(model)` returns the status for that model; records request models. */
function fakeFetch(plan: (model: string) => number, stream = false) {
  const models: string[] = []
  const f = async (_url: any, init: any) => {
    const body = JSON.parse(init.body)
    models.push(body.model)
    const status = plan(body.model)
    if (status !== 200) return new Response("overloaded", { status })
    if (stream) {
      const sse = `data: ${JSON.stringify({ choices: [{ delta: { content: "hi" } }] })}\n\ndata: [DONE]\n\n`
      return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } })
    }
    return new Response(JSON.stringify({ choices: [{ message: { content: `from ${body.model}` } }], usage: {} }), { status: 200, headers: { "content-type": "application/json" } })
  }
  return { f, models }
}

const base = { baseUrl: "http://never.invalid", style: "openai" as const, model: "m1", apiKey: "k", retryBaseMs: 1 }

test("fallbackModels: 529 on m1 (retries 0) → m2 serves and is reported", async () => {
  const { f, models } = fakeFetch((m) => (m === "m1" ? 529 : 200))
  const events: any[] = []
  const after: string[] = []
  const c = createClient({ ...base, retries: 0, fallbackModels: ["", "m1", "m2"], fetch: f as any, onMetric: (e: any) => events.push(e), hooks: { afterLLM: (ev: any) => { after.push(ev.model) } } })
  const r = await c.run("q")
  assert.deepEqual(models, ["m1", "m2"])
  assert.equal(r.model, "m2")
  assert.equal(r.text, "from m2")
  assert.deepEqual(after, ["m2"])
  assert.equal(events.find((e) => e.event === "llm")?.model, "m2")
  assert.equal(events.find((e) => e.event === "run")?.model, "m2")
})

test("fallbackModels: retry budget spent on m1 first, fresh budget on m2; chain exhausted surfaces last error", async () => {
  const { f, models } = fakeFetch(() => 503)
  const c = createClient({ ...base, retries: 1, fallbackModels: ["m2"], fetch: f as any })
  await assert.rejects(c.run("q"), (e: any) => e.status === 503)
  assert.deepEqual(models, ["m1", "m1", "m2", "m2"])
})

test("fallbackModels: 429 never switches", async () => {
  const { f, models } = fakeFetch((m) => (m === "m1" ? 429 : 200))
  const c = createClient({ ...base, retries: 0, fallbackModels: ["m2"], fetch: f as any })
  await assert.rejects(c.run("q"), (e: any) => e.status === 429)
  assert.deepEqual(models, ["m1"])
})

test("fallbackModels: onError 'fail' on 503 does not switch", async () => {
  const { f, models } = fakeFetch((m) => (m === "m1" ? 503 : 200))
  const c = createClient({ ...base, retries: 2, onError: () => "fail", fallbackModels: ["m2"], fetch: f as any })
  await assert.rejects(c.run("q"), (e: any) => e.status === 503)
  assert.deepEqual(models, ["m1"])
})

test("fallbackModels: TOOLNEXUS_DISABLE_MODEL_FALLBACK disables the chain", async () => {
  for (const v of ["1", "TRUE"]) {
    process.env.TOOLNEXUS_DISABLE_MODEL_FALLBACK = v
    try {
      const { f, models } = fakeFetch((m) => (m === "m1" ? 529 : 200))
      const c = createClient({ ...base, retries: 0, fallbackModels: ["m2"], fetch: f as any })
      await assert.rejects(c.run("q"), (e: any) => e.status === 529)
      assert.deepEqual(models, ["m1"])
    } finally {
      delete process.env.TOOLNEXUS_DISABLE_MODEL_FALLBACK
    }
  }
})

test("fallbackModels: stream emits fallback before text", async () => {
  const { f, models } = fakeFetch((m) => (m === "m1" ? 529 : 200), true)
  const c = createClient({ ...base, retries: 0, fallbackModels: ["m2"], fetch: f as any })
  const evs: any[] = []
  for await (const e of c.stream("q")) evs.push(e)
  assert.deepEqual(models, ["m1", "m2"])
  assert.deepEqual(evs[0], { type: "fallback", from: "m1", to: "m2" })
  assert.equal(evs[1].type, "text")
  assert.equal(evs.at(-1).result.model, "m2")
})

test("fallbackModels: anthropic style rebuilds the body model", async () => {
  const models: string[] = []
  const f = async (_u: any, init: any) => {
    const b = JSON.parse(init.body); models.push(b.model)
    if (b.model === "m1") return new Response("x", { status: 503 })
    return new Response(JSON.stringify({ content: [{ type: "text", text: "ok" }], stop_reason: "end_turn", usage: {} }), { status: 200, headers: { "content-type": "application/json" } })
  }
  const c = createClient({ ...base, style: "anthropic", retries: 0, fallbackModels: ["m2"], fetch: f as any })
  const r = await c.run("q")
  assert.deepEqual(models, ["m1", "m2"])
  assert.equal(r.model, "m2")
})

test("fallbackModels: absent ⇒ unchanged (529 surfaces, single model)", async () => {
  for (const fb of [undefined, []]) {
    const { f, models } = fakeFetch((m) => (m === "m1" ? 529 : 200))
    const c = createClient({ ...base, retries: 0, fallbackModels: fb as any, fetch: f as any })
    await assert.rejects(c.run("q"), (e: any) => e.status === 529)
    assert.deepEqual(models, ["m1"])
  }
})
