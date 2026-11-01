// add-host-turn-durability: interrupted turns are saved, builtin killGraceMs, edit diffs.
// Hermetic — a scripted fetch stands in for the LLM.
import { test } from "node:test"
import assert from "node:assert/strict"
import os from "node:os"
import fs from "node:fs"
import path from "node:path"
import { createClient, createToolkit, defineTool, createBuiltinTools, InMemoryConversationStore } from "../dist/index.js"

type Style = "openai" | "anthropic"
const STYLES: Style[] = ["openai", "anthropic"]

/** The model asks for `fast` then `slow` in one turn. */
function toolTurnJson(style: Style) {
  if (style === "openai") {
    return {
      choices: [{ index: 0, finish_reason: "tool_calls", message: { role: "assistant", content: null, tool_calls: [
        { id: "c1", type: "function", function: { name: "fast", arguments: "{}" } },
        { id: "c2", type: "function", function: { name: "slow", arguments: "{}" } },
      ] } }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
    }
  }
  return {
    content: [{ type: "tool_use", id: "c1", name: "fast", input: {} }, { type: "tool_use", id: "c2", name: "slow", input: {} }],
    stop_reason: "tool_use", usage: { input_tokens: 1, output_tokens: 1 },
  }
}

function toolTurnSse(style: Style): string {
  const ev = (o: unknown) => `data: ${JSON.stringify(o)}\n\n`
  if (style === "openai") {
    return ev({ choices: [{ index: 0, delta: { tool_calls: [
      { index: 0, id: "c1", function: { name: "fast", arguments: "{}" } },
      { index: 1, id: "c2", function: { name: "slow", arguments: "{}" } },
    ] } }] }) + "data: [DONE]\n\n"
  }
  return [
    ev({ type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 0 } } }),
    ev({ type: "content_block_start", index: 0, content_block: { type: "tool_use", id: "c1", name: "fast" } }),
    ev({ type: "content_block_delta", index: 0, delta: { type: "input_json_delta", partial_json: "{}" } }),
    ev({ type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "c2", name: "slow" } }),
    ev({ type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: "{}" } }),
    ev({ type: "message_delta", delta: { stop_reason: "tool_use" }, usage: { output_tokens: 1 } }),
  ].join("")
}

function mockFetch(style: Style, mode: "tools" | "fail") {
  return async (_url: string, init: any) => {
    if (mode === "fail") return new Response("bad request", { status: 400 })
    const streaming = JSON.parse(init.body).stream === true
    return streaming
      ? new Response(toolTurnSse(style), { status: 200, headers: { "content-type": "text/event-stream" } })
      : new Response(JSON.stringify(toolTurnJson(style)), { status: 200, headers: { "content-type": "application/json" } })
  }
}

async function cancelKit(ctrl: AbortController) {
  return createToolkit({
    builtins: false,
    extraTools: [
      defineTool({ name: "fast", description: "fast", run: async () => "fast-done" }),
      defineTool({ name: "slow", description: "slow", run: () => {
        setTimeout(() => ctrl.abort(new Error("user cancelled")), 30)
        return new Promise<string>(() => {}) // never finishes on its own
      } }),
    ],
  })
}

const PRIOR = [{ role: "user", content: "earlier" }, { role: "assistant", content: "ok" }]

function assertInterrupted(style: Style, saved: any[]) {
  assert.deepEqual(saved.slice(0, 2), PRIOR)
  assert.deepEqual(saved[2], { role: "user", content: "go" })
  if (style === "openai") {
    assert.equal(saved[3].role, "assistant")
    assert.deepEqual(saved[3].tool_calls.map((c: any) => c.id), ["c1", "c2"])
    assert.deepEqual(saved[4], { role: "tool", tool_call_id: "c1", content: "fast-done" })
    assert.deepEqual(saved[5], { role: "tool", tool_call_id: "c2", content: "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first." })
    assert.equal(saved.length, 6)
  } else {
    assert.equal(saved[3].role, "assistant")
    assert.deepEqual(saved[3].content.map((b: any) => b.id), ["c1", "c2"])
    assert.deepEqual(saved[4], { role: "user", content: [
      { type: "tool_result", tool_use_id: "c1", content: "fast-done", is_error: false },
      { type: "tool_result", tool_use_id: "c2", content: "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.", is_error: true },
    ] })
    assert.equal(saved.length, 5)
  }
}

for (const style of STYLES) {
  for (const mode of ["ask", "stream"] as const) {
    test(`durability(${style}/${mode}): cancel mid second tool saves prompt, finished pair, interrupted result`, async () => {
      const store = new InMemoryConversationStore()
      await store.save("t", PRIOR)
      const ctrl = new AbortController()
      const client = createClient({ baseUrl: "http://mock.invalid", style, model: "m", apiKey: "k", store, fetch: mockFetch(style, "tools") as any })
      const toolkit = await cancelKit(ctrl)
      const run = mode === "ask"
        ? client.ask("go", { toolkit, id: "t", signal: ctrl.signal })
        : (async () => { for await (const _ of client.stream("go", { toolkit, id: "t", signal: ctrl.signal })) { /* drain */ } })()
      await assert.rejects(run, /user cancelled/)
      assertInterrupted(style, (await store.get("t"))!)
    })

    test(`durability(${style}/${mode}): provider error on first call saves prior + prompt`, async () => {
      const store = new InMemoryConversationStore()
      await store.save("t", PRIOR)
      const client = createClient({ baseUrl: "http://mock.invalid", style, model: "m", apiKey: "k", store, fetch: mockFetch(style, "fail") as any, retries: 0 } as any)
      const run = mode === "ask"
        ? client.ask("go", { id: "t" })
        : (async () => { for await (const _ of client.stream("go", { id: "t" })) { /* drain */ } })()
      await assert.rejects(run)
      assert.deepEqual(await store.get("t"), [...PRIOR, { role: "user", content: "go" }])
    })
  }
}

test("durability: a failed first turn on a NEW id still saves the user prompt", async () => {
  const store = new InMemoryConversationStore()
  const client = createClient({ baseUrl: "http://mock.invalid", style: "openai", model: "m", apiKey: "k", store, fetch: mockFetch("openai", "fail") as any, retries: 0 } as any)
  await assert.rejects(client.ask("go", { id: "new" }))
  assert.deepEqual(await store.get("new"), [{ role: "user", content: "go" }])
})

test("durability: stream tool_result event carries the result metadata", async () => {
  let call = 0
  const fetchImpl = async () => {
    call++
    const body = call === 1
      ? `data: ${JSON.stringify({ choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: "c1", function: { name: "m", arguments: "{}" } }] } }] })}\n\ndata: [DONE]\n\n`
      : `data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: "fin" } }] })}\n\ndata: [DONE]\n\n`
    return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } })
  }
  const toolkit = await createToolkit({ builtins: false, extraTools: [defineTool({ name: "m", description: "m", run: async () => ({ output: "x", isError: false, metadata: { k: 1 } }) })] })
  const client = createClient({ baseUrl: "http://mock.invalid", style: "openai", model: "m", apiKey: "k", fetch: fetchImpl as any })
  const evs: any[] = []
  for await (const ev of client.stream("go", { toolkit })) evs.push(ev)
  assert.deepEqual(evs.find((e) => e.type === "tool_result").metadata, { k: 1 })
})

// ---- killGraceMs ----
const alive = (pgid: number) => { try { process.kill(-pgid, 0); return true } catch { return false } }

test("killGraceMs=100: a TERM-ignoring job with a TERM-ignoring descendant is gone within 1s", { skip: process.platform === "win32" }, async () => {
  const bash = createBuiltinTools({ killGraceMs: 100 }).find((t) => t.name === "bash")!
  const runs = Number(process.env.KILL_GRACE_RUNS ?? 3)
  const times: number[] = []
  for (let r = 0; r < runs; r++) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-kg-"))
    const pidFile = path.join(dir, "pid")
    const ctrl = new AbortController()
    const job = bash.execute({ command: `trap '' TERM; (trap '' TERM; while :; do sleep 0.05; done) & echo $$ > ${pidFile}; while :; do sleep 0.05; done` }, { signal: ctrl.signal })
    while (!fs.existsSync(pidFile) || !fs.readFileSync(pidFile, "utf8").trim()) await new Promise((r) => setTimeout(r, 10))
    const pgid = Number(fs.readFileSync(pidFile, "utf8").trim())
    await new Promise((r) => setTimeout(r, 50))
    const t0 = performance.now()
    ctrl.abort()
    while (alive(pgid) && performance.now() - t0 < 3000) await new Promise((r) => setTimeout(r, 2))
    const ms = performance.now() - t0
    times.push(ms)
    const res = await job
    assert.equal(res.isError, true)
    assert.ok(ms < 1000, `group gone after ${ms}ms`)
  }
  const s = [...times].sort((a, b) => a - b)
  console.log(`killGraceMs=100 cancel->group-gone ms: min=${s[0].toFixed(1)} median=${s[Math.floor(s.length / 2)].toFixed(1)} max=${s[s.length - 1].toFixed(1)} (n=${s.length})`)
})

// ---- edit diffs ----
const tools = (baseDir: string) => Object.fromEntries(createBuiltinTools({ baseDir }).map((t) => [t.name, t]))

test("diff: edit one line is modified 1/1 with -/+ lines", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-diff-"))
  fs.writeFileSync(path.join(dir, "f.txt"), "a\nb\nc\n")
  const res = await tools(dir).edit.execute({ path: "f.txt", oldString: "b", newString: "B" })
  assert.equal(res.isError, false, res.output)
  assert.equal(res.output, "Edited f.txt (1 replacement)")
  assert.deepEqual(res.metadata!.files, [{ path: "f.txt", status: "modified", additions: 1, deletions: 1 }])
  const diff = res.metadata!.diff as string
  assert.match(diff, /^--- a\/f\.txt\n\+\+\+ b\/f\.txt\n/)
  assert.match(diff, /\n-b\n/)
  assert.match(diff, /\n\+B\n/)
  assert.equal(fs.readFileSync(path.join(dir, "f.txt"), "utf8"), "a\nB\nc\n")
})

test("diff: write of a new 3-line file is added 3/0", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-diff-"))
  const res = await tools(dir).write.execute({ path: "sub/n.txt", content: "1\n2\n3\n" })
  assert.deepEqual(res.metadata!.files, [{ path: "sub/n.txt", status: "added", additions: 3, deletions: 0 }])
  assert.match(res.metadata!.diff as string, /--- \/dev\/null\n\+\+\+ b\/sub\/n\.txt\n@@ -0,0 \+1,3 @@\n\+1\n\+2\n\+3\n/)
  assert.equal(res.metadata!.bytes, 6)
})

test("diff: apply_patch adding and deleting a file", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-diff-"))
  fs.writeFileSync(path.join(dir, "old.txt"), "x\ny\n")
  const patchText = "*** Begin Patch\n*** Add File: new.txt\n+hello\n+world\n*** Delete File: old.txt\n*** End Patch"
  const res = await tools(dir).apply_patch.execute({ patchText })
  assert.equal(res.isError, false, res.output)
  assert.deepEqual(res.metadata!.files, [
    { path: "new.txt", status: "added", additions: 2, deletions: 0 },
    { path: "old.txt", status: "deleted", additions: 0, deletions: 2 },
  ])
  const diff = res.metadata!.diff as string
  assert.match(diff, /\+\+\+ b\/new\.txt/)
  assert.match(diff, /--- a\/old\.txt\n\+\+\+ \/dev\/null\n@@ -1,2 \+0,0 @@\n-x\n-y\n/)
  assert.ok(!fs.existsSync(path.join(dir, "old.txt")))
})
