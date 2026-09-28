/**
 * Judge batteries (SPEC.md §8B "Batteries", change add-judge-batteries) against the SHARED
 * fixtures in `examples/judge/batteries/`, plus hook tests mirroring golang/judge_batteries_test.go
 * and the beforeLLM `model` override (SPEC §8). Hermetic: static / custom classifiers and a
 * scripted fetch — no network, no live LLM.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync, readdirSync } from "node:fs"
import { fileURLToPath } from "node:url"
import {
  createClassifier,
  createClient,
  createToolkit,
  defineTool,
  staticClassifier,
  latestUserText,
  ToolGuardClassifier,
  ToolRelevanceClassifier,
  SkillRelevanceClassifier,
  ToolResultFilterClassifier,
  IsCompleteClassifier,
  AgentRouterClassifier,
  ContentGuardClassifier,
  ModelRouterClassifier,
  agents,
} from "../dist/index.js"

const dir = fileURLToPath(new URL("../../examples/judge/batteries/", import.meta.url))
const load = (f: string) => JSON.parse(readFileSync(dir + f, "utf8"))

const failing = () => createClassifier({ style: "custom", evaluate: async () => { throw new Error("boom") } })

/** static over the recorded calls; failing for `error: true`; test-failing when calls is empty. */
function classifierFor(c: any, name: string) {
  if (c.error) return failing()
  if (!c.calls?.length)
    return createClassifier({ style: "custom", evaluate: async () => { assert.fail(`${name}: classifier must not be called`) } })
  return staticClassifier(c.calls.map((k: any) => ({ state: k.state, questions: k.questions, response: k.response })))
}

function assertVerdict(want: Record<string, any>, got: Record<string, any>, name: string) {
  for (const [k, w] of Object.entries(want)) {
    if (k === "error") {
      assert.equal(got.error !== undefined && got.error !== null, w, `${name}: error present`)
      continue
    }
    assert.ok(k in got, `${name}: verdict lacks ${k}`)
    assert.deepEqual(got[k] ?? null, w, `${name}: ${k}`)
  }
}

const runners: Record<string, (cl: any, o: any, i: any) => Promise<any>> = {
  "tool-guard.json": (cl, o, i) =>
    new ToolGuardClassifier(cl, o).check({ name: i.name, arguments: i.arguments, description: i.description }),
  "tool-relevance.json": (cl, o, i) => new ToolRelevanceClassifier(cl, o).select(i.prompt, i.tools),
  "skill-relevance.json": (cl, o, i) => new SkillRelevanceClassifier(cl, o).select(i.prompt, i.skills),
  "tool-result-filter.json": (cl, o, i) => new ToolResultFilterClassifier(cl, o).filter(i.query, i.chunks),
  "is-complete.json": (cl, o, i) => new IsCompleteClassifier(cl, o).check(i.task, i.answer),
  "agent-router.json": (cl, o, i) => new AgentRouterClassifier(cl, o).pick(i.task, i.agents, i.fallback),
  "content-guard.json": (cl, o, i) => new ContentGuardClassifier(cl, o).check(i.text),
  "model-router.json": (cl, o, i) => new ModelRouterClassifier(cl, i.models, o).pick(i.prompt, i.fallback),
}

test("every battery fixture file has a runner", () => {
  const files = readdirSync(dir).filter((f) => f.endsWith(".json") && f !== "user-text-cases.json").sort()
  assert.deepEqual(files, Object.keys(runners).sort())
})

for (const [file, run] of Object.entries(runners)) {
  const fx = load(file)
  assert.ok(fx.cases.length > 0)
  for (const c of fx.cases) {
    test(`battery ${file}: ${c.name}`, async () => {
      const v = await run(classifierFor(c, c.name), c.options ?? {}, c.input)
      assertVerdict(c.want, v, c.name)
    })
  }
}

for (const c of load("user-text-cases.json").cases) {
  test(`latestUserText: ${c.name}`, () => assert.equal(latestUserText(c.messages), c.want))
}

test("batteries: missing or bad onError is a constructor error naming onError", () => {
  const cl = failing()
  const ctors: Array<(o: any) => unknown> = [
    (o) => new ToolGuardClassifier(cl, o),
    (o) => new ToolRelevanceClassifier(cl, o),
    (o) => new SkillRelevanceClassifier(cl, o),
    (o) => new ToolResultFilterClassifier(cl, o),
    (o) => new IsCompleteClassifier(cl, o),
    (o) => new ContentGuardClassifier(cl, o),
  ]
  for (const mk of ctors) {
    assert.throws(() => mk({}), /onError/)
    assert.throws(() => mk({ onError: "maybe" }), /onError/)
    assert.throws(() => mk(undefined), /onError/)
    mk({ onError: "open" })
  }
})

// ---------------------------------------------------------------- hook helpers

/** A classifier answering every question from a fixed answers body. */
const answering = (answers: Record<string, any>, calls?: { n: number }) =>
  createClassifier({
    style: "custom",
    evaluate: async (state: any, questions: any) => {
      if (calls) calls.n++
      const rec = { state, questions, response: { model: "m", answers, calibrated: true } }
      return staticClassifier(rec).evaluate(state, questions)
    },
  })

const riskAns = (score: number, confidence = 0.9) => ({
  risk: { type: "score", score, confidence, probabilities: { "0": 0.25, "1": 0.25, "2": 0.25, "3": 0.25 }, legend: {} },
})

/** A scripted openai fetch: replays assistant messages and records every body. */
function scripted(messages: any[]) {
  const sent: any[] = []
  let i = 0
  const fetchImpl = async (_url: string, init: any) => {
    sent.push(JSON.parse(init.body))
    const message = messages[Math.min(i++, messages.length - 1)]
    return new Response(
      JSON.stringify({
        choices: [{ index: 0, message, finish_reason: message.tool_calls ? "tool_calls" : "stop" }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  return { fetchImpl, sent }
}

const callTool = (name: string, args: object = {}) => ({
  role: "assistant",
  content: null,
  tool_calls: [{ id: "c1", type: "function", function: { name, arguments: JSON.stringify(args) } }],
})
const say = (content: string) => ({ role: "assistant", content })

async function toolkitWith(counter: { n: number }, names = ["danger"]) {
  return createToolkit({
    builtins: false,
    extraTools: names.map((name) =>
      defineTool({ name, description: `the ${name} tool`, run: () => { counter.n++; return "ran" } }),
    ),
  })
}

const client = (fetchImpl: any, extra: object = {}) =>
  createClient({ baseUrl: "http://scripted.invalid", style: "openai", model: "configured", apiKey: "unused", fetch: fetchImpl, ...extra })

// ---------------------------------------------------------------- ToolGuard hook

test("ToolGuard hook: ask halts pending with the guard's Request; tool never runs; next not called", async () => {
  const ran = { n: 0 }
  const nexts = { n: 0 }
  const guard = new ToolGuardClassifier(answering(riskAns(1.8)), { onError: "closed" })
  const { fetchImpl } = scripted([callTool("danger", { x: 1 }), say("done")])
  const res = await client(fetchImpl, { hooks: { beforeTool: guard.asHook(() => { nexts.n++ }) } }).run("go", { toolkit: await toolkitWith(ran) })
  assert.equal(res.status, "pending")
  assert.deepEqual(res.pending, {
    id: "toolguard:c1",
    kind: "approval",
    prompt: "Approve the call to danger? (medium risk)",
    data: { tool: "danger", arguments: { x: 1 }, reason: "medium risk", risk: 1.8 },
  })
  assert.equal(ran.n, 0)
  assert.equal(nexts.n, 0)
})

test("ToolGuard hook: deny short-circuits; allow calls next and runs the tool", async () => {
  const deny = new ToolGuardClassifier(answering(riskAns(2.9)), { onError: "closed" })
  let nexts = 0
  const d = await deny.asHook(() => { nexts++ })({ name: "danger", args: {}, id: "c1", turn: 0 })
  assert.deepEqual(d, { result: { output: "denied by tool guard: high risk", isError: true } })
  assert.equal(nexts, 0)

  const ran = { n: 0 }
  const allow = new ToolGuardClassifier(answering(riskAns(0.2)), { onError: "closed" })
  const { fetchImpl } = scripted([callTool("danger"), say("done")])
  const res = await client(fetchImpl, { hooks: { beforeTool: allow.asHook(() => { nexts++ }) } }).run("go", { toolkit: await toolkitWith(ran) })
  assert.equal(res.status, "done")
  assert.equal(nexts, 1)
  assert.equal(ran.n, 1)
  // absent next: allow is no override
  assert.equal(await allow.asHook()({ name: "danger", args: {}, id: "c1", turn: 0 }), undefined)
})

test("ToolGuard hook: approved via waitFor runs the tool once", async () => {
  const ran = { n: 0 }
  const seen: any[] = []
  const guard = new ToolGuardClassifier(answering(riskAns(1.8)), { onError: "closed" })
  const { fetchImpl } = scripted([callTool("danger"), say("done")])
  const res = await client(fetchImpl, {
    hooks: { beforeTool: guard.asHook() },
    waitFor: async (req: any) => { seen.push(req); return { id: req.id, ok: true } },
  }).run("go", { toolkit: await toolkitWith(ran) })
  assert.equal(res.status, "done")
  assert.equal(seen.length, 1)
  assert.equal(seen[0].id, "toolguard:c1")
  assert.equal(ran.n, 1)
})

// ---------------------------------------------------------------- ToolRelevance hook

test("ToolRelevance hook: drops a tool from the request body", async () => {
  const ans = {
    keep: { type: "noul", noul: 0.9 },
    drop: { type: "noul", noul: 0.05 },
  }
  const rel = new ToolRelevanceClassifier(answering(ans), { onError: "open" })
  const { fetchImpl, sent } = scripted([say("ok")])
  await client(fetchImpl, { hooks: { beforeLLM: rel.asHook() } }).run("please", { toolkit: await toolkitWith({ n: 0 }, ["keep", "drop"]) })
  assert.deepEqual(sent[0].tools.map((t: any) => t.function.name), ["keep"])
})

// ---------------------------------------------------------------- ContentGuard hook

test("ContentGuard hook: block throws before any request; review delegates; closed error message", async () => {
  const block = new ContentGuardClassifier(answering({ harmful: { type: "noul", noul: 0.95 }, prompt_injection: { type: "noul", noul: 0.9 } }), { onError: "closed" })
  const { fetchImpl, sent } = scripted([say("ok")])
  await assert.rejects(() => client(fetchImpl, { hooks: { beforeLLM: block.asHook() } }).run("x"), {
    message: "content guard blocked: harmful, prompt_injection",
  })
  assert.equal(sent.length, 0)

  let nexts = 0
  const review = new ContentGuardClassifier(answering({ harmful: { type: "noul", noul: 0.5 }, prompt_injection: { type: "noul", noul: 0.01 } }), { onError: "closed" })
  const out = await review.asHook(() => { nexts++; return { tools: [] } })({ messages: [{ role: "user", content: "x" }], tools: [1], model: "m", turn: 0 })
  assert.equal(nexts, 1)
  assert.deepEqual(out, { tools: [] })

  const errGuard = new ContentGuardClassifier(failing(), { onError: "closed" })
  await assert.rejects(() => errGuard.asHook()({ messages: [{ role: "user", content: "x" }], tools: [], model: "m", turn: 0 }), {
    message: "content guard blocked: classifier error",
  })
  const openGuard = new ContentGuardClassifier(failing(), { onError: "open" })
  assert.equal(await openGuard.asHook()({ messages: [{ role: "user", content: "x" }], tools: [], model: "m", turn: 0 }), undefined)
})

// ---------------------------------------------------------------- ToolResultFilter hook

test("ToolResultFilter hook: filters multi-chunk; passes single-chunk/error/parts through with no call", async () => {
  const calls = { n: 0 }
  const f = new ToolResultFilterClassifier(
    answering({ "0": { type: "noul", noul: 0.9 }, "1": { type: "noul", noul: 0.02 }, "2": { type: "noul", noul: 0.5 } }, calls),
    { onError: "open" },
  )
  let seen: any
  const hook = f.asHook((ev: any) => { seen = ev.result })
  const out: any = await hook({ name: "t", args: {}, result: { output: "a\n\nb\n\nc", isError: false }, id: "c1", turn: 0 })
  assert.equal(out.result.output, "a\n\nc")
  assert.equal(seen.output, "a\n\nc", "next sees the filtered result")
  assert.equal(calls.n, 1)

  const bare = f.asHook()
  for (const result of [
    { output: "single", isError: false },
    { output: "a\n\nb", isError: true },
    { output: "a\n\nb", isError: false, parts: [{ type: "image", mimeType: "image/png", data: "x" }] },
  ]) {
    assert.equal(await bare({ name: "t", args: {}, result, id: "c1", turn: 0 } as any), undefined)
  }
  assert.equal(calls.n, 1, "no classifier call on pass-through")

  // next's override wins
  const won: any = await f.asHook(() => ({ result: { output: "next", isError: false } }))({ name: "t", args: {}, result: { output: "a\n\nb\n\nc", isError: false }, turn: 0 })
  assert.equal(won.result.output, "next")
})

// ---------------------------------------------------------------- ModelRouter hook

const models = [
  { id: "small-fast", description: "Short factual answers; cheapest." },
  { id: "large-reasoning", description: "Multi-step reasoning and code." },
]
const pickAns = (choice: string, confidence: number, probabilities: Record<string, number>) => ({
  model: { type: "choice", choice, confidence, probabilities },
})

test("ModelRouter hook: sure pick routes the body model; unsure keeps configured", async () => {
  const sure = new ModelRouterClassifier(answering(pickAns("small-fast", 0.91, { "small-fast": 0.91, "large-reasoning": 0.09 })), models)
  const a = scripted([say("ok")])
  await client(a.fetchImpl, { hooks: { beforeLLM: sure.asHook() } }).run("capital of France?")
  assert.equal(a.sent[0].model, "small-fast")

  const unsure = new ModelRouterClassifier(answering(pickAns("small-fast", 0.6, { "small-fast": 0.6, "large-reasoning": 0.4 })), models)
  const b = scripted([say("ok")])
  await client(b.fetchImpl, { hooks: { beforeLLM: unsure.asHook() } }).run("refactor")
  assert.equal(b.sent[0].model, "configured")
})

test("ModelRouter hook: unsure or sure-of-configured returns NO override; next's model wins and sees the routed model", async () => {
  const ev = (model: string) => ({ messages: [{ role: "user", content: "q" }], tools: [], model, turn: 0 })
  const unsure = new ModelRouterClassifier(answering(pickAns("small-fast", 0.6, { "small-fast": 0.6, "large-reasoning": 0.4 })), models)
  assert.equal(await unsure.asHook()(ev("configured")), undefined)
  const sure = new ModelRouterClassifier(answering(pickAns("small-fast", 0.91, { "small-fast": 0.91, "large-reasoning": 0.09 })), models)
  assert.equal(await sure.asHook()(ev("small-fast")), undefined, "sure of the configured model: no override")
  assert.deepEqual(await sure.asHook()(ev("configured")), { model: "small-fast" })

  let nextSaw = ""
  const out = await sure.asHook((e: any) => { nextSaw = e.model; return { model: "next-model" } })(ev("configured"))
  assert.equal(nextSaw, "small-fast")
  assert.deepEqual(out, { model: "next-model" })
  // next returning nothing keeps the battery's override
  assert.deepEqual(await sure.asHook(() => undefined)(ev("configured")), { model: "small-fast" })
})

// ---------------------------------------------------------------- beforeLLM model override (SPEC §8)

test("beforeLLM model override: turn 0 only (openai run); afterLLM sees both", async () => {
  const seen: string[] = []
  const { fetchImpl, sent } = scripted([callTool("danger"), say("done")])
  await client(fetchImpl, {
    hooks: {
      beforeLLM: (e: any) => (e.turn === 0 ? { model: "override" } : { model: "" }),
      afterLLM: (e: any) => { seen.push(e.model) },
    },
  }).run("go", { toolkit: await toolkitWith({ n: 0 }) })
  assert.deepEqual(sent.map((b) => b.model), ["override", "configured"])
  assert.deepEqual(seen, ["override", "configured"])
})

test("beforeLLM model override: anthropic run, and null keeps the configured model", async () => {
  const sent: any[] = []
  let i = 0
  const replies = [
    { content: [{ type: "tool_use", id: "c1", name: "danger", input: {} }], stop_reason: "tool_use" },
    { content: [{ type: "text", text: "done" }], stop_reason: "end_turn" },
  ]
  const fetchImpl = async (_u: string, init: any) => {
    sent.push(JSON.parse(init.body))
    return new Response(JSON.stringify({ ...replies[Math.min(i++, 1)], usage: { input_tokens: 1, output_tokens: 1 } }), {
      status: 200, headers: { "content-type": "application/json" },
    })
  }
  const seen: string[] = []
  await createClient({
    baseUrl: "http://scripted.invalid", style: "anthropic", model: "configured", apiKey: "unused", fetch: fetchImpl as any,
    hooks: { beforeLLM: (e: any) => (e.turn === 0 ? { model: "override" } : { model: null }), afterLLM: (e: any) => { seen.push(e.model) } },
  }).run("go", { toolkit: await toolkitWith({ n: 0 }) })
  assert.deepEqual(sent.map((b) => b.model), ["override", "configured"])
  assert.deepEqual(seen, ["override", "configured"])
})

test("beforeLLM model override: openai stream", async () => {
  const sent: any[] = []
  const fetchImpl = async (_u: string, init: any) => {
    sent.push(JSON.parse(init.body))
    const sse = `data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: "hi" }, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`
    return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } })
  }
  const seen: string[] = []
  const c = client(fetchImpl, { hooks: { beforeLLM: () => ({ model: "override" }), afterLLM: (e: any) => { seen.push(e.model) } } })
  for await (const _ of c.stream("go")) { /* drain */ }
  assert.deepEqual(sent.map((b) => b.model), ["override"])
  assert.deepEqual(seen, ["override"])
})

// ---------------------------------------------------------------- tool-relevance hookCases (§8B)

for (const c of load("tool-relevance.json").hookCases ?? []) {
  test(`battery tool-relevance.json hookCase: ${c.name}`, async () => {
    const hook = new ToolRelevanceClassifier(classifierFor(c, c.name), c.options ?? {}).asHook()
    const ov: any = await hook(structuredClone(c.event))
    const got = ov?.tools ? ov.tools.map((t: any) => c.event.tools.findIndex((e: any) => JSON.stringify(e) === JSON.stringify(t))) : null
    assert.deepEqual(got, c.want.tools, `${c.name}: kept tool indices`)
  })
}

// ------------------------------------------- beforeLLM failure + model reporting, every entry point (§8, §11)

type Entry = { name: string; style: "openai" | "anthropic"; body: () => Response; go: (c: any) => Promise<any> }

const jsonRes = (o: any) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } })
const sseRes = (s: string) => new Response(s, { status: 200, headers: { "content-type": "text/event-stream" } })
const oaiJson = () => jsonRes({ choices: [{ index: 0, message: { role: "assistant", content: "hi" }, finish_reason: "stop" }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })
const antJson = () => jsonRes({ content: [{ type: "text", text: "hi" }], stop_reason: "end_turn", usage: { input_tokens: 1, output_tokens: 1 } })
const oaiSse = () => sseRes(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: "hi" }, finish_reason: "stop" }] })}\n\ndata: [DONE]\n\n`)
const antSse = () => sseRes([
  { type: "message_start", message: { usage: { input_tokens: 1 } } },
  { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
  { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "hi" } },
  { type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 1 } },
].map((e) => `data: ${JSON.stringify(e)}\n\n`).join(""))

const drain = async (c: any) => { let r: any; for await (const e of c.stream("go")) if (e.type === "done") r = e.result; return r }

const entries: Entry[] = [
  { name: "run openai", style: "openai", body: oaiJson, go: (c) => c.run("go") },
  { name: "run anthropic", style: "anthropic", body: antJson, go: (c) => c.run("go") },
  { name: "stream openai", style: "openai", body: oaiSse, go: drain },
  { name: "stream anthropic", style: "anthropic", body: antSse, go: drain },
  { name: "translate openai", style: "openai", body: oaiJson, go: (c) => c.translate({ messages: [{ role: "user", content: "go" }] }) },
  { name: "translate anthropic", style: "anthropic", body: antJson, go: (c) => c.translate({ messages: [{ role: "user", content: "go" }] }) },
]

function entryClient(e: Entry, hooks: any) {
  const sent: any[] = []
  const metrics: any[] = []
  const fetchImpl = async (_u: string, init: any) => { sent.push(JSON.parse(init.body)); return e.body() }
  const c = createClient({ baseUrl: "http://scripted.invalid", style: e.style, model: "configured", apiKey: "unused", fetch: fetchImpl as any, hooks, onMetric: (m: any) => metrics.push(m) })
  return { c, sent, metrics }
}

for (const e of entries) {
  test(`beforeLLM failure stops the call, no request sent: ${e.name}`, async () => {
    const { c, sent } = entryClient(e, { beforeLLM: async () => { throw new Error("hook boom") } })
    await assert.rejects(e.go(c), /hook boom/)
    assert.equal(sent.length, 0)
  })

  test(`beforeLLM model override is transmitted and reported: ${e.name}`, async () => {
    const { c, sent, metrics } = entryClient(e, { beforeLLM: () => ({ model: "override" }) })
    const r = await e.go(c)
    assert.deepEqual(sent.map((b) => b.model), ["override"])
    assert.equal(r.model, "override")
    assert.deepEqual(metrics.filter((m) => m.event === "llm").map((m) => m.model), ["override"])
    if (!e.name.startsWith("translate")) assert.deepEqual(metrics.filter((m) => m.event === "run").map((m) => m.model), ["override"])
  })

  test(`beforeLLM with no model keeps the configured model: ${e.name}`, async () => {
    const { c, sent, metrics } = entryClient(e, { beforeLLM: () => ({ model: "" }) })
    const r = await e.go(c)
    assert.deepEqual(sent.map((b) => b.model), ["configured"])
    assert.equal(r.model, "configured")
    assert.ok(metrics.filter((m) => m.event === "llm" || m.event === "run").every((m) => m.model === "configured"))
  })
}

test("model reporting: run result + run metric carry the LAST call's model; llm metric per turn", async () => {
  const { fetchImpl } = scripted([callTool("danger"), say("done")])
  const metrics: any[] = []
  const r = await client(fetchImpl, {
    onMetric: (m: any) => metrics.push(m),
    hooks: { beforeLLM: (e: any) => (e.turn === 0 ? undefined : { model: "late" }) },
  }).run("go", { toolkit: await toolkitWith({ n: 0 }) })
  assert.deepEqual(metrics.filter((m) => m.event === "llm").map((m) => m.model), ["configured", "late"])
  assert.equal(r.model, "late")
  assert.deepEqual(metrics.filter((m) => m.event === "run").map((m) => m.model), ["late"])
})

test("model reporting: a pending run reports the overridden model", async () => {
  const guard = new ToolGuardClassifier(answering(riskAns(1.8)), { onError: "closed" })
  const { fetchImpl } = scripted([callTool("danger"), say("done")])
  const metrics: any[] = []
  const r = await client(fetchImpl, { onMetric: (m: any) => metrics.push(m), hooks: { beforeLLM: () => ({ model: "override" }), beforeTool: guard.asHook() } })
    .run("go", { toolkit: await toolkitWith({ n: 0 }) })
  assert.equal(r.status, "pending")
  assert.equal(r.model, "override")
  assert.deepEqual(metrics.filter((m) => m.event === "run").map((m) => m.model), ["override"])
})

test("model reporting: a failed run's run metric carries the transmitted model; a hook failure before any call carries configured", async () => {
  const metrics: any[] = []
  const failFetch = async () => new Response("nope", { status: 400 })
  await assert.rejects(client(failFetch, { retries: 0, onMetric: (m: any) => metrics.push(m), hooks: { beforeLLM: () => ({ model: "override" }) } }).run("go"))
  assert.deepEqual(metrics.filter((m) => m.event === "llm" || m.event === "run").map((m) => [m.event, m.model]), [["llm", "override"], ["run", "override"]])
  const m2: any[] = []
  await assert.rejects(client(failFetch, { onMetric: (m: any) => m2.push(m), hooks: { beforeLLM: () => { throw new Error("x") } } }).run("go"))
  assert.deepEqual(m2.filter((m) => m.event === "run").map((m) => m.model), ["configured"])
})

test("beforeLLM failure stops the call, no request sent: agent loop run", async () => {
  const sent: any[] = []
  const fetchImpl = async (_u: string, init: any) => { sent.push(init.body); return oaiJson() }
  const tk = await createToolkit({ builtins: false })
  const a = agents.agent("failing", { does: "x", hooks: { beforeLLM: async () => { throw new Error("hook boom") } } })
  await assert.rejects(
    a.loop({ baseUrl: "http://scripted.invalid", style: "openai", model: "configured", apiKey: "unused", fetch: fetchImpl as any }, tk).run("go"),
    /hook boom/,
  )
  assert.equal(sent.length, 0)
  await tk.close()
})
