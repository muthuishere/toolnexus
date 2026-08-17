/**
 * createInProcessClient — a model in this process, with no wire configuration.
 * openspec/changes/add-in-process-client. Mirrored in all seven ports.
 */
import { strict as assert } from "node:assert"
import test from "node:test"
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

test("generate is required", () => {
  assert.throws(() => createInProcessClient({ model: "m" } as any), /requires a `generate` function/)
})
