/**
 * Harness, loop and the completion gate (openspec/changes/add-harness-and-loop).
 * Hermetic — a scripted fetch stands in for the LLM, so no network and no key.
 *
 * These mirror golang/agents/loop_test.go case for case: the point of the change
 * is that seven ports agree, and a test that exists in one port only is how that
 * stops being true.
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import { agents, createToolkit, defineTool } from "../dist/index.js"

const { agent, harness, allTodosDone, guardedHooks, Loop } = agents as any

/** A fetch that replays scripted assistant messages, and records what was sent. */
function scripted(messages: any[]) {
  const sent: any[] = []
  let i = 0
  const fetchImpl = async (_url: string, init: any) => {
    sent.push(JSON.parse(init.body))
    const message = messages[Math.min(i, messages.length - 1)]
    i++
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

const say = (content: string) => ({ role: "assistant", content })
const callTodo = (todos: any[]) => ({
  role: "assistant",
  tool_calls: [{ id: "t1", type: "function", function: { name: "todowrite", arguments: JSON.stringify({ todos }) } }],
})

const baseOpts = (fetchImpl: any) => ({
  baseUrl: "http://scripted.invalid", style: "openai" as const,
  model: "test-model", apiKey: "unused", fetch: fetchImpl,
})

async function todoToolkit() {
  return createToolkit({
    builtins: {
      tools: {
        todowrite: true, bash: false, read: false, write: false, edit: false,
        glob: false, grep: false, webfetch: false, apply_patch: false, question: false,
      },
    },
  })
}

test("harness: a spec built through harness() is the spec", () => {
  const spec = { does: "x", soul: "y" }
  assert.equal(harness(spec), spec, "harness is a name, not a wrapper")
})

test("loop: absent completion + absent guardrails is unchanged", async () => {
  const { fetchImpl } = scripted([say("hello")])
  const tk = await createToolkit({ builtins: false })
  const a = agent("plain", { does: "answers" })
  const out = await a.loop(baseOpts(fetchImpl), tk).run("hi")
  assert.equal(out.status, "done")
  assert.equal(out.text, "hello")
  assert.equal(out.attempts, 1)
  assert.equal(out.stoppedBy, undefined, "a done run names no stop reason")
  await tk.close()
})

test("loop: the gate blocks an open todo, then passes once closed", async () => {
  // Attempt 1 must END with an open item: the client loops on tool calls, so a
  // closing todowrite in the same run would be judged and pass with no retry.
  const { fetchImpl } = scripted([
    callTodo([{ id: "1", text: "draft", completed: true }, { id: "2", text: "proofread", completed: false }]),
    say("I think I am finished"),
    callTodo([{ id: "1", text: "draft", completed: true }, { id: "2", text: "proofread", completed: true }]),
    say("all done"),
  ])
  const tk = await todoToolkit()
  const a = agent("gated", { does: "plans", completion: { verify: allTodosDone, maxAttempts: 3 } })
  const out = await a.loop(baseOpts(fetchImpl), tk).run("do the thing")
  assert.equal(out.status, "done")
  assert.ok(out.attempts >= 2, `expected a retry, got ${out.attempts} attempt(s)`)
  await tk.close()
})

test("loop: an unverifiable run stops LOUDLY, bounded by maxAttempts", async () => {
  const { fetchImpl } = scripted([say("done!")])
  const tk = await createToolkit({ builtins: false })
  const a = agent("never", {
    does: "never verifies",
    completion: { verify: () => ({ ok: false, reason: "always red" }), maxAttempts: 2 },
  })
  const out = await a.loop(baseOpts(fetchImpl), tk).run("go")
  assert.equal(out.status, "incomplete", "never a silent done")
  assert.equal(out.attempts, 2, "bounded by maxAttempts")
  assert.match(out.stoppedBy ?? "", /always red/, "the reason is named")
  assert.equal(out.result.limit, "completion", "structured, so a caller can tell WHICH limit")
  await tk.close()
})

test("loop: maxAttempts is required, not defaulted", async () => {
  const { fetchImpl } = scripted([say("hi")])
  const tk = await createToolkit({ builtins: false })
  const a = agent("bad", { does: "x", completion: { verify: () => ({ ok: true }), maxAttempts: 0 } })
  await assert.rejects(() => a.loop(baseOpts(fetchImpl), tk).run("go"), /maxAttempts/)
  await tk.close()
})

test("loop: no plan declared ⇒ the built-in verifier passes", async () => {
  const { fetchImpl } = scripted([say("answered without a plan")])
  const tk = await todoToolkit()
  const a = agent("noplan", { does: "x", completion: { verify: allTodosDone, maxAttempts: 2 } })
  const out = await a.loop(baseOpts(fetchImpl), tk).run("go")
  assert.equal(out.status, "done", "the gate must not punish an agent for not using the builtin")
  assert.equal(out.attempts, 1)
  await tk.close()
})

test("gate: judges ACCUMULATED work — a retry that drops the plan cannot escape", async () => {
  // Attempt 1 declares an open item; attempt 2 declares no plan at all. Judging
  // only the latest attempt would see "no plan" and pass.
  const { fetchImpl } = scripted([
    callTodo([{ id: "1", text: "ship it", completed: false }]),
    say("I am finished, honest"),
  ])
  const tk = await todoToolkit()
  const a = agent("escaper", { does: "x", completion: { verify: allTodosDone, maxAttempts: 2 } })
  const out = await a.loop(baseOpts(fetchImpl), tk).run("go")
  assert.equal(out.status, "incomplete", "the earlier open plan must still be visible")
  assert.match(out.stoppedBy ?? "", /ship it/)
  await tk.close()
})

test("guardrails: first deny wins, and a later guardrail cannot re-allow", async () => {
  const seen: string[] = []
  const hooks = guardedHooks(
    [
      (ev: any) => (ev.name === "danger" ? "policy: no" : "allow"),
      (_ev: any) => { seen.push("second ran"); return "allow" },
    ],
    undefined,
  )
  const denied = await hooks!.beforeTool!({ name: "danger", args: {}, turn: 1 } as any)
  assert.equal((denied as any).result.isError, true)
  assert.match((denied as any).result.output, /policy: no/)
  assert.equal(seen.length, 0, "a later guardrail never runs after a denial")

  const allowed = await hooks!.beforeTool!({ name: "safe", args: {}, turn: 1 } as any)
  assert.equal(allowed, undefined, "an allowed call falls through")
  assert.equal(seen.length, 1)
})

test("guardrails: an existing beforeTool runs only when every guardrail allows", async () => {
  let priorRuns = 0
  const hooks = guardedHooks(
    [(ev: any) => (ev.name === "danger" ? "nope" : "allow")],
    { beforeTool: async () => { priorRuns++; return undefined } },
  )
  await hooks!.beforeTool!({ name: "danger", args: {}, turn: 1 } as any)
  assert.equal(priorRuns, 0, "denied ⇒ the prior hook is not reached")
  await hooks!.beforeTool!({ name: "safe", args: {}, turn: 1 } as any)
  assert.equal(priorRuns, 1, "allowed ⇒ the prior hook runs")
})

test("guardrails survive the registry projection (so a delegated child is governed)", () => {
  const child = agent("child", {
    does: "does work",
    guardrails: [() => "denied by policy"],
  })
  const def = child.registry()["child"]
  assert.ok(def.hooks?.beforeTool, "the compiled guardrail must be on the projected def")
})

test("the completion gate is projected into the registry, so delegation inherits it", () => {
  const child = agent("child", {
    does: "does work",
    completion: { verify: allTodosDone, maxAttempts: 2 },
  })
  const def = child.registry()["child"]
  assert.equal(def.completion?.maxAttempts, 2, "the gate travels with the agent")
})

test("loop: a per-call model override reaches the wire; omitting it does not", async () => {
  const { fetchImpl, sent } = scripted([say("a"), say("b")])
  const tk = await createToolkit({ builtins: false })
  const l = agent("m", { does: "x" }).loop(baseOpts(fetchImpl), tk)
  await l.run("one", { model: "override-model" })
  await l.run("two")
  assert.equal(sent[0].model, "override-model", "the override reaches the request body")
  assert.equal(sent[1].model, "test-model", "and does not persist to the next call")
  await tk.close()
})

test("loop: turns accumulate across runs and status is observed", async () => {
  const { fetchImpl } = scripted([say("a"), say("b")])
  const tk = await createToolkit({ builtins: false })
  const l = agent("t", { does: "x" }).loop(baseOpts(fetchImpl), tk)
  assert.equal(l.status, "idle")
  await l.run("one")
  const afterFirst = l.turns
  await l.run("two")
  assert.ok(l.turns > afterFirst, "turns accumulate across runs")
  assert.equal(l.status, "idle")
  await tk.close()
})
