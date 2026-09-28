/**
 * Simple judgments (SPEC.md §8B, add-judge-adapters) against the SHARED fixtures in
 * `examples/judge/adapters/`. Hermetic: every classifier is `static`, `custom` or a fake fetch.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import {
  judge,
  State,
  context,
  questionMap,
  ask,
  gate,
  decide,
  applyPolicy,
  answersOf,
  staticClassifier,
  Tape,
  questionWire,
  createClassifier,
  decodeDecision,
  type NamedQuestion,
} from "../dist/index.js"

const dir = fileURLToPath(new URL("../../examples/judge/adapters/", import.meta.url))
const load = (f: string) => JSON.parse(readFileSync(dir + f, "utf8"))
const stateCases = load("state-cases.json")
const gateCases = load("gate-cases.json")

function build(q: any): NamedQuestion {
  if (q.kind === "noul") return judge.noul(q.name, q.instructions)
  if (q.kind === "choice") return judge.choice(q.name, q.instructions, q.options)
  return judge.score(q.name, q.instructions, q.levels)
}
const wire = (m: Record<string, any>) => Object.fromEntries(Object.entries(m).map(([k, q]) => [k, questionWire(q)]))

for (const c of stateCases.cases) {
  test(`state case: ${c.name}`, () => {
    const qs = c.questions.map(build)
    if (c.wantError) {
      assert.throws(() => questionMap(qs), (e: Error) => e.message.includes(c.wantError))
      return
    }
    const st = c.roleState
      ? State(c.roleState.role, c.roleState.data)
      : c.context
        ? context(c.context.context, c.context.message, c.context.extra)
        : c.state
    assert.deepEqual(st, c.wantState)
    assert.deepEqual(wire(questionMap(qs)), c.wantQuestions)
  })
}

const gq: NamedQuestion[] = Object.entries(gateCases.questions).map(([name, q]: [string, any]) =>
  q.type === "noul"
    ? judge.noul(name, q.instructions, q.criteria)
    : q.type === "choice"
      ? judge.choice(name, q.instructions, q.criteria)
      : judge.score(name, q.instructions, q.criteria),
)
const ST = { report: "gate case" }

for (const c of gateCases.cases) {
  test(`gate case: ${c.name}`, async () => {
    const cl = staticClassifier({ state: ST, questions: questionMap(gq), response: { answers: c.answers } })
    const out = c.policy
      ? await decide(cl, ST, gq, {
          rules: c.rules ?? gateCases.rules,
          default: c.policy.default,
          skipUncertain: c.policy.skipUncertain,
          bands: c.bands ?? undefined,
        })
      : await gate(cl, ST, gq, c.rules ?? gateCases.rules, c.bands ?? undefined)
    const w = c.want
    assert.deepEqual(
      { action: out.action, target: out.target, escalated: out.escalated },
      { action: w.action, target: w.target, escalated: w.escalated },
    )
    if (out.escalated) {
      assert.equal(out.request?.kind, "input")
      const d = out.request?.data as any
      assert.ok(d.answers)
      assert.equal(d.question, w.question)
      if (w.reason !== undefined) assert.equal(d.reason, w.reason)
      else assert.ok(d.reason)
      assert.equal(out.request?.id, w.requestId)
    } else assert.equal(out.request, undefined)
    const a = await ask(cl, ST, gq, c.bands ?? undefined)
    for (const [name, wa] of Object.entries(c.wantAnswers as Record<string, any>)) {
      const got: any = a[name]
      assert.ok(got, `answer ${name}`)
      assert.equal(got.value(), wa.value, `${name}.value`)
      if ("band" in wa) assert.equal(got.band, wa.band, `${name}.band`)
      if ("sure" in wa) assert.equal(got.sure, wa.sure, `${name}.sure`)
      if ("choice" in wa) assert.equal(got.pick(), wa.choice, `${name}.choice`)
    }
    assert.deepEqual(Object.keys(a).sort(), Object.keys(c.wantAnswers).sort())
  })
}

test("missing answer escalates naming it", async () => {
  const c = gateCases.cases.find((x: any) => x.name === "missing-component")
  const cl = staticClassifier({ state: ST, questions: questionMap(gq), response: { answers: c.answers } })
  const out = await gate(cl, ST, gq, gateCases.rules)
  assert.equal((out.request?.data as any).reason, 'missing answer "component"')
  assert.equal((out.request?.data as any).question, "component")
})

const decision = (answers: any) => decodeDecision({ answers }, "m")

test("bands: exclusive cut-points, custom cut-offs, near-uniform choice not sure", () => {
  const a = answersOf(decision({ lo: { type: "noul", noul: 0.3 }, hi: { type: "noul", noul: 0.7 } }))
  assert.equal(a.lo.band, "uncertain")
  assert.equal(a.hi.band, "uncertain")
  assert.equal(answersOf(decision({ x: { type: "noul", noul: 0.55 } }), { low: 0.2, high: 0.5 }).x.band, "yes")
  const ch = answersOf(decision({ c: { type: "choice", choice: "a", confidence: 0.8, probabilities: { a: 0.52, b: 0.48 } } }))
  assert.equal(ch.c.sure, false)
  assert.equal(ch.c.pick(), "a")
  assert.equal((ch.c as any).choice, "a")
  assert.equal(ch.c.value(), 0.8)
})

test("Answer.value reads a noul without type inspection", () => {
  assert.equal(answersOf(decision({ x: { type: "noul", noul: 0.96 } })).x.value(), 0.96)
})

test("State puts role next to the data; non-object goes under data", () => {
  const role = "You are Donkey Kong, you want to win."
  assert.deepEqual(State(role, { message_received: "jump" }), { role, message_received: "jump" })
  assert.deepEqual(State("r", "plain text"), { role: "r", data: "plain text" })
  const q = judge.noul("is_appropriate", "Does `message_received` contain insults?")
  assert.ok(!(q.question.instructions as string).includes(role))
})

test("Policy: empty default escalates 'no rule fired'; default fires; skipUncertain", () => {
  const ans = answersOf(decision({ a: { type: "noul", noul: 0.5 }, b: { type: "noul", noul: 0.9 } }))
  const none = applyPolicy(answersOf(decision({ b: { type: "noul", noul: 0.9 } })), {
    rules: [{ question: "b", below: 0.3, action: "fail" }],
  })
  assert.equal(none.escalated, true)
  assert.equal((none.request?.data as any).reason, "no rule fired")
  assert.equal(
    applyPolicy(answersOf(decision({ b: { type: "noul", noul: 0.9 } })), {
      rules: [{ question: "b", below: 0.3, action: "fail" }],
      default: "continue",
    }).action,
    "continue",
  )
  const rules = [
    { question: "a", at_least: 0.5, action: "first" },
    { question: "b", at_least: 0.8, action: "second" },
  ]
  assert.equal(applyPolicy(ans, { rules }).escalated, true)
  const skip = applyPolicy(ans, { rules, skipUncertain: true })
  assert.deepEqual([skip.action, skip.escalated], ["second", false])
})

test("decide runs a Policy through a classifier", async () => {
  const qs = [judge.noul("b", "b?")]
  const cl = staticClassifier({ state: ST, questions: questionMap(qs), response: { answers: { b: { type: "noul", noul: 0.1 } } } })
  const out = await decide(cl, ST, qs, { rules: [{ question: "b", below: 0.3, action: "fail" }] })
  assert.equal(out.action, "fail")
})

test("Tape records by call name and replays offline; a miss names the key", async () => {
  const qs = [judge.noul("x", "x?")]
  const live = createClassifier({ style: "custom", evaluate: () => decision({ x: { type: "noul", noul: 0.9 } }) })
  const rec = new Tape(live)
  await ask(rec.classifier("plan"), ST, qs)
  const replay = Tape.replay(JSON.parse(JSON.stringify(rec.entries)))
  const a = await ask(replay.classifier("plan"), ST, qs)
  assert.equal(a.x.band, "yes")
  const miss = Tape.replay({}).classifier("plan") // building never fails; the miss is reported on evaluate
  await assert.rejects(ask(miss, ST, qs), (e: Error) => e.message === 'tape: no recorded decision for call "plan"')
  // verbatim, not JSON-escaped: every port prints the same bytes
  await assert.rejects(ask(Tape.replay({}).classifier('a"b\\c'), ST, qs), (e: Error) => e.message === 'tape: no recorded decision for call "a"b\\c"')
})

test("byte-identity: builder request body == hand-written §8B body", async () => {
  process.env.TOOLNEXUS_JUDGE_TEST_KEY = "YOUR_KEY_HERE"
  const bodies: string[] = []
  const fetch: typeof globalThis.fetch = async (_u, init) => {
    bodies.push(String(init?.body))
    return new Response(JSON.stringify({ answers: { is_appropriate: { type: "noul", noul: 0.1 }, does_this_help: { type: "noul", noul: 0.2 } } }), { status: 200 })
  }
  const c = createClassifier({ fetch, apiKeyEnv: "TOOLNEXUS_JUDGE_TEST_KEY", retries: 0 })
  const vc = stateCases.cases[0]
  await ask(c, State(vc.state.role, { message_received: vc.state.message_received }), vc.questions.map(build))
  await c.evaluate(vc.wantState, vc.wantQuestions)
  assert.equal(bodies.length, 2)
  assert.equal(bodies[0], bodies[1])
})

// ---------------------------------------------------------------- evaluateBatch

const bq = { x: judge.noul("x", "x?").question }
const rec = (s: string, p: number) => ({ state: s, questions: bq, response: { answers: { x: { type: "noul", noul: p } } } })

test("evaluateBatch returns decisions in state order", async () => {
  const c = createClassifier({
    style: "custom",
    evaluate: async (s) => {
      await new Promise((r) => setTimeout(r, s === "a" ? 20 : 1))
      return decision({ x: { type: "noul", noul: s === "a" ? 0.1 : s === "b" ? 0.5 : 0.9 } })
    },
  })
  const ds = await c.evaluateBatch(["a", "b", "c"], bq)
  assert.deepEqual(ds.map((d) => d.noul("x").noul), [0.1, 0.5, 0.9])
})

test("evaluateBatch fails closed naming the state index", async () => {
  const c = staticClassifier([rec("a", 0.1), rec("c", 0.9)])
  await assert.rejects(() => c.evaluateBatch(["a", "b", "c"], bq), /state 1/)
})

test("evaluateBatch: several failures name the lowest index", async () => {
  const c = createClassifier({
    style: "custom",
    evaluate: async (s) => {
      if (s === "a") await new Promise((r) => setTimeout(r, 20)) // state 0 fails LAST
      if (s === "a" || s === "c") throw new Error(`boom ${s}`)
      return decision({ x: { type: "noul", noul: 0.5 } })
    },
  })
  await assert.rejects(() => c.evaluateBatch(["a", "b", "c"], bq), /^Error: classifier: state 0: /)
})

test("evaluateBatch: empty states is an error and sends nothing", async () => {
  let calls = 0
  const c = createClassifier({ style: "custom", evaluate: () => (calls++, decision({})) })
  await assert.rejects(() => c.evaluateBatch([], bq), /no states/)
  assert.equal(calls, 0)
})

test("evaluateBatch bounds concurrency at 16 by default", async () => {
  let live = 0
  let peak = 0
  const c = createClassifier({
    style: "custom",
    evaluate: async () => {
      peak = Math.max(peak, ++live)
      await new Promise((r) => setTimeout(r, 2))
      live--
      return decision({ x: { type: "noul", noul: 0.5 } })
    },
  })
  const ds = await c.evaluateBatch(Array.from({ length: 40 }, (_, i) => i), bq)
  assert.equal(ds.length, 40)
  assert.equal(peak, 16)
})
