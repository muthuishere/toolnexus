/**
 * createInProcessClient — a model in this process, with no wire configuration.
 * openspec/changes/add-in-process-client. Mirrored in all seven ports.
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import fs from "node:fs"
import { createInProcessClient, createToolkit, defineTool } from "../dist/index.js"

const add = defineTool({
  name: "add", description: "Add two numbers.",
  inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
  run: async ({ a, b }: any) => String(a + b),
})

test("no wire configuration is required", async () => {
  const tk = await createToolkit({ builtins: false })
  // No baseUrl. No apiKey. No style. That is the whole point.
  const client = createInProcessClient({
    model: "my-local",
    generate: () => ({ content: "hello from in-process" }),
  })
  const r = await client.run("hi", { toolkit: tk })
  assert.equal(r.text, "hello from in-process")
  assert.equal(r.status, "done")
  await tk.close()
})

test("generate sees the assembled request: messages, tools and model", async () => {
  const tk = await createToolkit({ builtins: false, extraTools: [add] })
  let seen: any
  const client = createInProcessClient({
    model: "my-local",
    systemPrompt: "You are terse.",
    generate: (req) => { seen = req; return { content: "ok" } },
  })
  await client.run("What is 2 + 3?", { toolkit: tk })
  assert.equal(seen.model, "my-local")
  assert.ok(seen.messages.some((m: any) => m.role === "system" && /terse/.test(m.content)), "systemPrompt reaches it")
  assert.ok(seen.messages.some((m: any) => m.role === "user" && /2 \+ 3/.test(m.content)))
  assert.equal(seen.tools[0].function.name, "add", "tool schemas are offered")
  await tk.close()
})

test("returning toolCalls runs the tool and loops back with the result", async () => {
  const tk = await createToolkit({ builtins: false, extraTools: [add] })
  let n = 0
  const client = createInProcessClient({
    model: "my-local",
    generate: (req) => {
      n++
      if (n === 1) return { toolCalls: [{ name: "add", arguments: { a: 2, b: 3 } }] }
      const last = req.messages.at(-1)
      return { content: `the answer is ${last.content}` }
    },
  })
  const r = await client.run("What is 2 + 3?", { toolkit: tk })
  assert.equal(r.toolCalls.length, 1)
  assert.equal(r.toolCalls[0].name, "add")
  assert.equal(r.toolCalls[0].output, "5")
  assert.match(r.text, /the answer is 5/)
  await tk.close()
})

test("arguments may be structured OR a pre-encoded string — same result", async () => {
  const tk = await createToolkit({ builtins: false, extraTools: [add] })
  const run = async (args: unknown) => {
    let n = 0
    const client = createInProcessClient({
      model: "m",
      generate: () => (++n === 1 ? { toolCalls: [{ name: "add", arguments: args }] } : { content: "done" }),
    })
    return (await client.run("go", { toolkit: tk })).toolCalls[0].output
  }
  assert.equal(await run({ a: 2, b: 3 }), "5", "structured")
  assert.equal(await run('{"a":2,"b":3}'), "5", "pre-encoded")
  await tk.close()
})

test("usage is optional, and reported when given", async () => {
  const tk = await createToolkit({ builtins: false })
  const bare = createInProcessClient({ model: "m", generate: () => ({ content: "x" }) })
  assert.equal((await bare.run("hi", { toolkit: tk })).usage.totalTokens, 0, "absent usage is zero, not a failure")

  const counted = createInProcessClient({
    model: "m",
    generate: () => ({ content: "x", usage: { promptTokens: 11, completionTokens: 4 } }),
  })
  const r = await counted.run("hi", { toolkit: tk })
  assert.equal(r.usage.promptTokens, 11)
  assert.equal(r.usage.totalTokens, 15, "total is derived when not given")
  await tk.close()
})

test("streaming is refused loudly, never faked as one chunk", async () => {
  const tk = await createToolkit({ builtins: false })
  const client = createInProcessClient({ model: "m", generate: () => ({ content: "x" }) })
  await assert.rejects(async () => {
    for await (const _ of client.stream("hi", { toolkit: tk })) { /* consume */ }
  }, /does not support streaming/)
  await tk.close()
})

test("failures are NOT retried — there is no wire to have a transient failure", async () => {
  const tk = await createToolkit({ builtins: false })
  let calls = 0
  const client = createInProcessClient({
    model: "m",
    generate: () => { calls++; throw new Error("my model blew up") },
  })
  const t0 = Date.now()
  await assert.rejects(() => client.run("hi", { toolkit: tk }), /my model blew up/)
  assert.equal(calls, 1, "a local generate that throws will throw again; retrying is backoff over a bug")
  assert.ok(Date.now() - t0 < 500, `should fail immediately, took ${Date.now() - t0}ms`)

  // NOT asserted: that an explicit `retries: N` re-runs a throwing generate. Whether a
  // raised error is retryable at all is per-port — python's loop catches only
  // HTTP/OS errors, so a plain throw there propagates on the first attempt whatever
  // `retries` says. That divergence predates this constructor; pinning it here would
  // assert something false in another port.

  await tk.close()
})

test("works with NO api key anywhere — not even in the environment", async () => {
  // The bug this pins: the client resolves a key from env and fails when it finds
  // none, so an in-process client still demanded one. Every local run passed because
  // a developer shell has OPENROUTER_API_KEY set; CI, which has none, failed all
  // seven ports. The env is stripped here so the test cannot be masked the same way.
  const saved = { ...process.env }
  for (const k of ["OPENROUTER_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"]) delete process.env[k]
  try {
    const tk = await createToolkit({ builtins: false })
    const client = createInProcessClient({ model: "m", generate: () => ({ content: "no key needed" }) })
    assert.equal((await client.run("hi", { toolkit: tk })).text, "no key needed")
    await tk.close()
  } finally {
    Object.assign(process.env, saved)
  }
})

test("generate is required", () => {
  assert.throws(() => createInProcessClient({ model: "m" } as any), /requires a `generate` \(or `generateStream`\) function/)
})

// conformance/acp-client/cases.json stream.inProcess — a streaming generate is incremental.
test("generateStream streams incrementally; non-text events pass through", async () => {
  const fx = JSON.parse(fs.readFileSync(new URL("../../conformance/acp-client/cases.json", import.meta.url), "utf8")).stream.inProcess
  const seen: string[] = []
  const waiters: Array<() => void> = []
  const consumerSaw = (delta: string) =>
    new Promise<void>((resolve, reject) => {
      if (seen.includes(delta)) return resolve()
      const t = setTimeout(() => reject(new Error(fx.notIncrementalError)), fx.waitMs)
      waiters.push(() => {
        if (seen.includes(delta)) {
          clearTimeout(t)
          resolve()
        }
      })
    })
  const client = createInProcessClient({
    model: "m",
    generateStream: async (_req, emit) => {
      for (const e of fx.emits) {
        const { awaitConsumer, ...ev } = e
        emit(ev)
        if (awaitConsumer) await consumerSaw(ev.delta)
      }
      return fx.returns
    },
  })
  const deltas: string[] = []
  const passThrough: any[] = []
  let done: any
  for await (const ev of client.stream(fx.prompt)) {
    const e = ev as any
    if (e.type === "text") {
      deltas.push(e.delta)
      seen.push(e.delta)
      for (const w of waiters.splice(0)) w()
    } else if (e.type === "thought" || e.type === "tool_call") passThrough.push(e)
    else if (e.type === "done") done = e.result
  }
  assert.deepEqual(deltas, fx.expectTextDeltas)
  assert.equal(passThrough.length, fx.expectPassThrough.length)
  fx.expectPassThrough.forEach((want: any, i: number) => {
    for (const k of Object.keys(want)) assert.deepEqual(passThrough[i][k], want[k], k)
  })
  assert.equal(done.text, fx.expectDoneText)
  assert.equal(done.toolCalls.length, fx.expectDoneToolCalls)
})

test("generateStream alone also serves a non-streaming run", async () => {
  const client = createInProcessClient({ model: "m", generateStream: (_r, emit) => (emit({ type: "text", delta: "x" }), { content: "whole" }) })
  assert.equal((await client.run("q")).text, "whole")
})
