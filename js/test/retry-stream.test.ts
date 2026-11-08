// SPEC 8 Resilience: the streaming `retry` event and the `x-should-retry` override (issue #189).
// Hermetic: an injected fetch scripts each attempt; nothing leaves the process.
import { strict as assert } from "node:assert"
import test from "node:test"
import { createClient } from "../dist/index.js"

type Step = { status: number; headers?: Record<string, string> } | "network" | "ok"

const sse = (style: "openai" | "anthropic"): string =>
  style === "openai"
    ? 'data: {"choices":[{"delta":{"content":"hi"}}]}\n\ndata: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'
    : 'data: {"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}\n\n' +
      'data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n' +
      'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}\n\n' +
      'data: {"type":"content_block_stop","index":0}\n\n' +
      'data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}\n\n' +
      'data: {"type":"message_stop"}\n\n'

const json = (style: "openai" | "anthropic"): string =>
  style === "openai"
    ? JSON.stringify({ choices: [{ message: { content: "hi" } }], usage: {} })
    : JSON.stringify({ content: [{ type: "text", text: "hi" }], stop_reason: "end_turn", usage: { input_tokens: 1, output_tokens: 1 } })

function scripted(steps: Step[], style: "openai" | "anthropic") {
  const calls = { n: 0 }
  const fetch = async (_url: any, init: any): Promise<Response> => {
    const step = steps[Math.min(calls.n++, steps.length - 1)]
    if (step === "network") throw new TypeError("fetch failed")
    if (step === "ok") {
      const stream = JSON.parse(init.body).stream
      return new Response(stream ? sse(style) : json(style), { status: 200, headers: { "content-type": stream ? "text/event-stream" : "application/json" } })
    }
    return new Response("err", { status: step.status, headers: step.headers })
  }
  return { fetch: fetch as typeof globalThis.fetch, calls }
}

async function collect(client: any): Promise<{ events: any[]; error?: unknown }> {
  const events: any[] = []
  try {
    for await (const ev of client.stream("hello")) events.push(ev)
  } catch (error) {
    return { events, error }
  }
  return { events }
}

for (const style of ["openai", "anthropic"] as const) {
  const mk = (steps: Step[], extra: Record<string, unknown> = {}) => {
    const s = scripted(steps, style)
    const client = createClient({ baseUrl: "http://example.invalid/v1", style, model: "m", apiKey: "k", retries: 2, retryBaseMs: 1, fetch: s.fetch, ...extra })
    return { client, calls: s.calls }
  }

  test(`retry event (${style}): 429 + Retry-After: 0 yields one retry event before text and done`, async () => {
    const { client, calls } = mk([{ status: 429, headers: { "Retry-After": "0" } }, "ok"])
    const { events, error } = await collect(client)
    assert.equal(error, undefined)
    const types = events.filter((e) => e.type !== "usage").map((e) => e.type)
    assert.deepEqual(types.slice(0, 2), ["retry", "text"])
    assert.equal(types.at(-1), "done")
    assert.deepEqual(events[0], { type: "retry", attempt: 1, max: 2, delayMs: 0, status: 429, reason: "HTTP 429" })
    assert.equal(calls.n, 2)
  })

  test(`retry event (${style}): network error has reason network and no status`, async () => {
    const { client } = mk(["network", "ok"])
    const { events, error } = await collect(client)
    assert.equal(error, undefined)
    const r = events.find((e) => e.type === "retry")
    assert.equal(r.reason, "network")
    assert.equal(r.attempt, 1)
    assert.equal(r.max, 2)
    assert.ok(!("status" in r))
    assert.ok(r.delayMs >= 1 && r.delayMs < 102)
  })

  test(`retry event (${style}): 400 emits no retry event and errors`, async () => {
    const { client, calls } = mk([{ status: 400 }, "ok"])
    const { events, error } = await collect(client)
    assert.ok(error)
    assert.equal(events.filter((e) => e.type === "retry").length, 0)
    assert.equal(calls.n, 1)
  })

  test(`x-should-retry (${style}): true makes a 400 retry`, async () => {
    const { client, calls } = mk([{ status: 400, headers: { "x-should-retry": " TRUE " } }, "ok"])
    const { events, error } = await collect(client)
    assert.equal(error, undefined)
    assert.equal(events.filter((e) => e.type === "retry").length, 1)
    assert.equal(calls.n, 2)
  })

  test(`x-should-retry (${style}): False makes a 503 terminal after one attempt`, async () => {
    const { client, calls } = mk([{ status: 503, headers: { "X-Should-Retry": "False" } }, "ok"])
    const { events, error } = await collect(client)
    assert.ok(error)
    assert.equal(events.filter((e) => e.type === "retry").length, 0)
    assert.equal(calls.n, 1)
  })

  test(`x-should-retry (${style}): onError sees retryable:true and its fail wins`, async () => {
    const seen: any[] = []
    const { client, calls } = mk([{ status: 400, headers: { "x-should-retry": "true" } }, "ok"], {
      onError: (info: any) => { seen.push(info); return "fail" },
    })
    const { error } = await collect(client)
    assert.ok(error)
    assert.equal(seen.length, 1)
    assert.equal(seen[0].retryable, true)
    assert.equal(calls.n, 1)
  })

  test(`retry event (${style}): run() is unchanged and emits no events`, async () => {
    const { client, calls } = mk([{ status: 429, headers: { "Retry-After": "0" } }, "ok"])
    const res = await client.run("hello")
    assert.equal(res.text, "hi")
    assert.equal(calls.n, 2)
  })
}
