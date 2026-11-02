// add-tool-concurrency (SPEC §8 "Tool scheduling"), ported from golang/tool_concurrency_test.go.
// Hermetic: a scripted fetch answers turn 1 with one call per name, turn 2 with "done", in all
// four wire modes (openai/anthropic × run/stream).
import { test } from "node:test"
import assert from "node:assert/strict"
import { createClient, createToolkit, isConcurrencySafe, type Tool } from "../dist/index.js"

const json = (o: unknown) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } })
const sse = (events: unknown[], done = true) =>
  new Response(events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("") + (done ? "data: [DONE]\n\n" : ""), {
    status: 200, headers: { "content-type": "text/event-stream" },
  })

function scriptedFetch(names: string[]): typeof fetch {
  let turn = 0
  return (async (url: string | URL, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body ?? "{}"))
    turn++
    const first = turn === 1
    const anth = String(url).includes("messages")
    if (!anth && !body.stream) {
      const message = first
        ? { role: "assistant", content: null, tool_calls: names.map((n, i) => ({ id: `c${i + 1}`, type: "function", function: { name: n, arguments: "{}" } })) }
        : { role: "assistant", content: "done" }
      return json({ choices: [{ message }] })
    }
    if (anth && !body.stream) {
      return json(first
        ? { content: names.map((n, i) => ({ type: "tool_use", id: `c${i + 1}`, name: n, input: {} })), stop_reason: "tool_use" }
        : { content: [{ type: "text", text: "done" }], stop_reason: "end_turn" })
    }
    if (!anth) {
      return sse(first
        ? [...names.map((n, i) => ({ choices: [{ delta: { tool_calls: [{ index: i, id: `c${i + 1}`, type: "function", function: { name: n, arguments: "{}" } }] } }] })),
          { choices: [{ delta: {}, finish_reason: "tool_calls" }] }]
        : [{ choices: [{ delta: { content: "done" } }] }, { choices: [{ delta: {}, finish_reason: "stop" }] }])
    }
    const ev: unknown[] = []
    if (first) {
      names.forEach((n, i) => {
        ev.push({ type: "content_block_start", index: i, content_block: { type: "tool_use", id: `c${i + 1}`, name: n } })
        ev.push({ type: "content_block_delta", index: i, delta: { type: "input_json_delta", partial_json: "{}" } })
        ev.push({ type: "content_block_stop", index: i })
      })
      ev.push({ type: "message_delta", delta: { stop_reason: "tool_use" } })
    } else {
      ev.push({ type: "content_block_start", index: 0, content_block: { type: "text" } })
      ev.push({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "done" } })
      ev.push({ type: "message_delta", delta: { stop_reason: "end_turn" } })
    }
    ev.push({ type: "message_stop" })
    return sse(ev, false)
  }) as typeof fetch
}

class Tracker {
  inflight = 0
  peak = 0
  log: string[] = []
  tool(name: string, readOnly: boolean): Tool {
    return {
      name, description: name, source: "custom", inputSchema: { type: "object", properties: {} },
      hints: readOnly ? { readOnly: true, destructive: false, openWorld: false } : undefined,
      execute: async () => {
        this.inflight++
        this.peak = Math.max(this.peak, this.inflight)
        this.log.push(`start ${name}`)
        await new Promise((r) => setTimeout(r, 60))
        this.inflight--
        this.log.push(`end ${name}`)
        return { output: `out ${name}`, isError: false }
      },
    }
  }
}

const MODES = [
  { name: "openai-run", style: "openai", stream: false },
  { name: "anthropic-run", style: "anthropic", stream: false },
  { name: "openai-stream", style: "openai", stream: true },
  { name: "anthropic-stream", style: "anthropic", stream: true },
] as const

async function runSched(mode: (typeof MODES)[number], names: string[], tools: Tool[], maxToolConcurrency?: number): Promise<string[]> {
  const toolkit = await createToolkit({ builtins: false, extraTools: tools })
  try {
    const client = createClient({
      baseUrl: "http://mock.local", style: mode.style, model: "m", apiKey: "k", fetch: scriptedFetch(names), maxToolConcurrency,
    })
    if (!mode.stream) return (await client.run("go", { toolkit })).toolCalls.map((c) => c.name)
    let got: string[] = []
    for await (const ev of client.stream("go", { toolkit })) {
      if (ev.type === "done") got = ev.result.toolCalls.map((c) => c.name)
    }
    return got
  } finally {
    await toolkit.close()
  }
}

const reads = (tr: Tracker, n: number) => {
  const names = Array.from({ length: n }, (_, i) => `r${i + 1}`)
  return { names, tools: names.map((x) => tr.tool(x, true)) }
}

for (const mode of MODES) {
  test(`tool scheduling (${mode.name}): two unsafe calls serialize in emitted order`, async () => {
    const tr = new Tracker()
    const got = await runSched(mode, ["w1", "w2"], [tr.tool("w1", false), tr.tool("w2", false)])
    assert.equal(tr.peak, 1)
    assert.deepEqual(tr.log, ["start w1", "end w1", "start w2", "end w2"])
    assert.deepEqual(got, ["w1", "w2"])
  })

  test(`tool scheduling (${mode.name}): read-only calls overlap`, async () => {
    const tr = new Tracker()
    const { names, tools } = reads(tr, 6)
    const got = await runSched(mode, names, tools)
    assert.equal(tr.peak, 6)
    assert.deepEqual(got, names)
  })

  test(`tool scheduling (${mode.name}): the cap is honoured`, async () => {
    const tr = new Tracker()
    const { names, tools } = reads(tr, 6)
    await runSched(mode, names, tools, 2)
    assert.equal(tr.peak, 2)
  })

  test(`tool scheduling (${mode.name}): mixed runs keep order`, async () => {
    const tr = new Tracker()
    const names = ["r1", "r2", "w", "r3", "r4"]
    const tools = [tr.tool("r1", true), tr.tool("r2", true), tr.tool("w", false), tr.tool("r3", true), tr.tool("r4", true)]
    const got = await runSched(mode, names, tools)
    const pos = (e: string) => tr.log.indexOf(e)
    for (const r of ["r1", "r2"]) assert.ok(pos(`end ${r}`) < pos("start w"), tr.log.join(","))
    for (const r of ["r3", "r4"]) assert.ok(pos(`start ${r}`) > pos("end w"), tr.log.join(","))
    assert.equal(tr.peak, 2)
    assert.deepEqual(got, names)
  })
}

test("tool scheduling: env override, option wins, invalid values fall back", async () => {
  const saved = process.env.TOOLNEXUS_MAX_TOOL_CONCURRENCY
  const peak = async (env: string, max?: number) => {
    process.env.TOOLNEXUS_MAX_TOOL_CONCURRENCY = env
    const tr = new Tracker()
    const { names, tools } = reads(tr, 6)
    await runSched(MODES[0], names, tools, max)
    return tr.peak
  }
  try {
    assert.equal(await peak("3"), 3)
    assert.equal(await peak("3", 4), 4)
    for (const bad of ["0", "-2", "abc", ""]) assert.equal(await peak(bad), 6, `env ${JSON.stringify(bad)}`)
  } finally {
    if (saved === undefined) delete process.env.TOOLNEXUS_MAX_TOOL_CONCURRENCY
    else process.env.TOOLNEXUS_MAX_TOOL_CONCURRENCY = saved
  }
})

test("tool scheduling: the safety rule", async () => {
  const toolkit = await createToolkit({})
  try {
    const want: Record<string, boolean> = {
      read: true, grep: true, glob: true, webfetch: true, bash: false, write: false, edit: false, apply_patch: false,
    }
    for (const [name, safe] of Object.entries(want)) {
      const t = toolkit.get(name)
      assert.ok(t, `builtin ${name}`)
      assert.equal(isConcurrencySafe(t!, {}), safe, name)
    }
  } finally {
    await toolkit.close()
  }
  const base = { name: "x", description: "", source: "custom" as const, inputSchema: {}, execute: async () => ({ output: "", isError: false }) }
  assert.equal(isConcurrencySafe(base, {}), false, "no hints ⇒ unsafe")
  const own: Tool = { ...base, hints: { readOnly: true, destructive: false, openWorld: false }, concurrencySafe: (a) => a.dry === true }
  assert.equal(isConcurrencySafe(own, {}), false)
  assert.equal(isConcurrencySafe(own, { dry: true }), true)
})
